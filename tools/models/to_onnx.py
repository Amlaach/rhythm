"""
The phone's models, for the Windows build.

TensorFlow Lite's Java artifact is Android only; on a desktop JVM there is
nothing to load it with. ONNX Runtime has a Java binding for Windows, so the
desktop runs the very same networks in ONNX form. Converted from the .tflite
files the phone ships rather than from the upstream originals, so both builds
run the same weights: the fp16 EffNet stays fp16, the int8 YAMNet stays int8.

  app/src/main/assets/yamnet.tflite        -> desktop/.../models/yamnet.onnx
  app/src/main/assets/music/effnet.tflite  -> desktop/.../models/effnet.onnx
  app/src/main/assets/music/<head>.tflite  -> desktop/.../models/<head>.onnx
  app/src/main/assets/music/heads.json     -> desktop/.../models/heads.json

Every converted model is run against TensorFlow Lite on the same inputs and
the conversion is refused if the outputs differ by more than rounding. What
was measured is written to models.json next to the models, along with the
hash of each source file, so `--check` (no TensorFlow needed, run by CI)
can tell when the phone's models changed and these were not converted again.

Three things tf2onnx gets wrong or leaves awkward, fixed here:
  - YAMNet's last layer is an int8 fully connected layer with a bias, and
    tf2onnx drops the bias. It is added back. Without it the 521 scores come
    out tens of quantisation steps off; with it, a song's averaged scores
    agree with the phone's to within a few thousandths.
  - YAMNet's 1024 wide print is an int8 tensor whose scale lives in the
    .tflite. A float output is added that dequantises it the way the phone
    does, scale * (q - zero point), so the desktop never needs the scale.
  - EffNet's weights are fp16 in the .tflite and come out as fp32. They are
    stored as fp16 again with a cast in front, which is exact (every weight
    is checked to survive the round trip) and halves the file.

Usage: python tools/models/to_onnx.py            convert and verify
       python tools/models/to_onnx.py --check    sources unchanged?

Models: YAMNet, Apache 2.0. Discogs-EffNet and heads, MTG-UPF, CC BY-NC-SA 4.0.
"""
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ROOT, "desktop", "src", "main", "resources", "models")
MANIFEST = os.path.join(OUT, "models.json")
OPSET = 17


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sources():
    """(source .tflite, output name) for every model the phone ships."""
    heads = json.load(open(os.path.join(ASSETS, "music", "heads.json")))
    out = [(os.path.join(ASSETS, "yamnet.tflite"), "yamnet"),
           (os.path.join(ASSETS, "music", "effnet.tflite"), "effnet")]
    for h in heads["heads"]:
        out.append((os.path.join(ASSETS, "music", h["name"] + ".tflite"), h["name"]))
    return out


def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, "/")


def check():
    if not os.path.exists(MANIFEST):
        sys.exit("no %s: run tools/models/to_onnx.py" % rel(MANIFEST))
    manifest = json.load(open(MANIFEST))
    recorded = {m["source"]: m for m in manifest["models"]}
    stale = []
    for src, name in sources():
        m = recorded.get(rel(src))
        if m is None or m["sourceSha256"] != sha256(src):
            stale.append(rel(src))
        elif m["sha256"] != sha256(os.path.join(OUT, name + ".onnx")):
            stale.append(name + ".onnx")
    heads_src = os.path.join(ASSETS, "music", "heads.json")
    if sha256(heads_src) != sha256(os.path.join(OUT, "heads.json")):
        stale.append("heads.json")
    if stale:
        sys.exit("the desktop models are out of date with the phone's (%s): "
                 "run tools/models/to_onnx.py and commit the result" % ", ".join(stale))
    print("desktop models match the phone's:", len(recorded), "models")


# ---------------------------------------------------------------- conversion

def tf2onnx(src, dst):
    cmd = [sys.executable, "-m", "tf2onnx.convert", "--tflite", src, "--output", dst, "--opset", str(OPSET)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-3000:], r.stderr[-3000:])
        sys.exit("tf2onnx failed on " + src)


def tflite_tensors(src):
    import tensorflow as tf
    it = tf.lite.Interpreter(model_path=src)
    it.allocate_tensors()
    return it, {t["index"]: t for t in it.get_tensor_details()}


def restore_fc_bias(model, src):
    """Adds back the bias tf2onnx leaves off a quantised fully connected layer."""
    import numpy as np
    from onnx import helper, numpy_helper
    it, tensors = tflite_tensors(src)
    g = model.graph
    fixed = 0
    for op in it._get_ops_details():
        if op["op_name"] != "FULLY_CONNECTED" or len(op["inputs"]) < 3 or op["inputs"][2] < 0:
            continue
        bias_t = tensors[op["inputs"][2]]
        out_name = tensors[op["outputs"][0]]["name"]
        raw = it.get_tensor(bias_t["index"])
        scale = bias_t["quantization"][0]
        bias = (raw.astype(np.float64) * scale).astype(np.float32) if raw.dtype != np.float32 else raw
        matmuls = [n for n in g.node if n.op_type == "MatMul" and n.output[0].startswith(out_name)]
        for mm in matmuls:
            consumers = [n for n in g.node if mm.output[0] in n.input]
            if any(n.op_type == "Add" for n in consumers):
                continue
            name = mm.output[0]
            mm.output[0] = name + "_before_bias"
            bias_name = out_name.split(";")[0] + "_restored_bias"
            g.initializer.append(numpy_helper.from_array(bias, bias_name))
            at = list(g.node).index(mm)
            g.node.insert(at + 1, helper.make_node("Add", [mm.output[0], bias_name], [name]))
            fixed += 1
    return fixed


def rename_outputs(model, names):
    """Stable output names: an Identity per output, whatever tf2onnx called it."""
    import onnx
    from onnx import helper
    g = model.graph
    old = list(g.output)
    assert len(old) == len(names), (len(old), names)
    del g.output[:]
    for o, new in zip(old, names):
        g.node.append(helper.make_node("Identity", [o.name], [new]))
        v = onnx.ValueInfoProto()
        v.CopyFrom(o)
        v.name = new
        g.output.append(v)


def add_dequantised_output(model, src, tflite_output_index, new_name):
    """A float copy of an int8 output: scale * (q - zero point), as the phone reads it."""
    import numpy as np
    import onnx
    from onnx import helper, numpy_helper, TensorProto
    it, _ = tflite_tensors(src)
    detail = it.get_output_details()[tflite_output_index]
    scale, zero = detail["quantization"]
    g = model.graph
    q = g.output[tflite_output_index].name
    g.initializer.append(numpy_helper.from_array(np.array(scale, dtype=np.float32), new_name + "_scale"))
    g.initializer.append(numpy_helper.from_array(np.array(zero, dtype=np.int8), new_name + "_zero"))
    g.node.append(helper.make_node("DequantizeLinear", [q, new_name + "_scale", new_name + "_zero"], [new_name]))
    v = onnx.ValueInfoProto()
    v.CopyFrom(g.output[tflite_output_index])
    v.name = new_name
    v.type.tensor_type.elem_type = TensorProto.FLOAT
    g.output.append(v)
    return scale, zero


def store_fp16(model):
    """fp32 weights that are exactly fp16, stored as fp16 behind a Cast."""
    import numpy as np
    from onnx import helper, numpy_helper, TensorProto
    g = model.graph
    moved = 0
    keep = []
    casts = []
    for init in g.initializer:
        a = numpy_helper.to_array(init)
        if a.dtype == np.float32 and a.size > 64 and np.array_equal(a.astype(np.float16).astype(np.float32), a):
            half = numpy_helper.from_array(a.astype(np.float16), init.name + "_fp16")
            keep.append(half)
            casts.append(helper.make_node("Cast", [half.name], [init.name], to=TensorProto.FLOAT))
            moved += a.size
        else:
            keep.append(init)
    del g.initializer[:]
    g.initializer.extend(keep)
    for c in reversed(casts):
        g.node.insert(0, c)
    return moved


# ---------------------------------------------------------------- checking

def run_tflite(src, x):
    import numpy as np
    import tensorflow as tf
    it = tf.lite.Interpreter(model_path=src)
    inp = it.get_input_details()[0]
    if list(inp["shape"]) != list(x.shape):
        it.resize_tensor_input(inp["index"], x.shape)
    it.allocate_tensors()
    it.set_tensor(inp["index"], x)
    it.invoke()
    outs = []
    for o in it.get_output_details():
        v = it.get_tensor(o["index"])
        if v.dtype == np.int8:
            s, z = o["quantization"]
            v = (s * (v.astype(np.int32) - z)).astype(np.float32)
        outs.append(v.astype(np.float32))
    return outs


def run_onnx(path, x):
    import onnxruntime as ort
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    return s.run(None, {s.get_inputs()[0].name: x})


def inputs_for(kind, n):
    import numpy as np
    rng = np.random.default_rng(20260923)
    xs = []
    for k in range(n):
        if kind == "effnet":
            # In the range MusicMel produces, log10(1 + 10000 * mel): 0 to 4.
            # Far outside it (random values of five and more) the network is
            # chaotic and two correct runtimes part ways; nothing real is there.
            bands = 0.3 * np.sin(np.arange(96) / (6.0 + k))
            x = rng.standard_normal((1, 128, 96)) * (0.4 + 0.02 * k) + 1.0 + 0.05 * k + bands
            xs.append(np.clip(x, 0.0, 4.0).astype(np.float32))
        else:
            xs.append((np.abs(rng.standard_normal((1, 1280))) * (0.1 + 0.1 * k)).astype(np.float32))
    return xs


def synthetic_song(k, seconds=32):
    """A few harmonics that swell and fade, a beat on most, noise on some."""
    import numpy as np
    rng = np.random.default_rng(1000 + k)
    t = np.arange(seconds * 16000) / 16000.0
    x = np.zeros_like(t)
    f0 = 110.0 * (1.2 ** (k % 8))
    for h in range(1, 6):
        x += 0.2 / h * np.sin(2 * math.pi * f0 * h * t + rng.uniform(0, 6)) * (0.6 + 0.4 * np.sin(2 * math.pi * 0.25 * h * t))
    if k % 3:
        x += (np.mod(t, 0.5) < 0.03) * rng.standard_normal(t.size) * 0.5
    x += 0.02 * (k % 4) * rng.standard_normal(t.size)
    return (x / np.max(np.abs(x)) * 0.7).astype(np.float32)


def verify_yamnet(src, dst, songs=12):
    """
    YAMNet is int8 on both sides, and the two runtimes requantise with
    different rounding; a frame of a pure tone can sit on a rounding edge and
    move a score by a dozen steps of 1/256. What the app keeps is not a frame
    but the average over a song, so that is what is compared: the same
    framing the analyser uses (0.975 s frames, half a frame apart).
    """
    import numpy as np
    import tensorflow as tf
    import onnxruntime as ort
    it = tf.lite.Interpreter(model_path=src)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    outs = it.get_output_details()
    scale, zero = outs[1]["quantization"]
    s = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
    name = s.get_inputs()[0].name
    worst = {"songScoreMaxAbs": 0.0, "songPrintMinCos": 1.0, "top10Overlap": 10, "frameScoreMaxAbs": 0.0}
    for k in range(songs):
        w = synthetic_song(k)
        a = np.zeros(521); b = np.zeros(521); pa = np.zeros(1024); pb = np.zeros(1024)
        n = 0
        for o in range(0, w.size - 15600 + 1, 7800):
            x = w[o:o + 15600]
            it.set_tensor(inp["index"], x)
            it.invoke()
            fa = it.get_tensor(outs[0]["index"])[0]
            qa = it.get_tensor(outs[1]["index"]).reshape(-1).astype(np.int32)
            fb, qb = s.run(None, {name: x})
            worst["frameScoreMaxAbs"] = max(worst["frameScoreMaxAbs"], float(np.max(np.abs(fa - fb[0]))))
            a += fa; b += fb[0]
            pa += scale * (qa - zero); pb += qb.reshape(-1)
            n += 1
        a /= n; b /= n; pa /= n; pb /= n
        worst["songScoreMaxAbs"] = max(worst["songScoreMaxAbs"], float(np.max(np.abs(a - b))))
        worst["songPrintMinCos"] = min(worst["songPrintMinCos"], float(pa @ pb / (np.linalg.norm(pa) * np.linalg.norm(pb))))
        overlap = len(set(np.argsort(-a)[:10]) & set(np.argsort(-b)[:10]))
        worst["top10Overlap"] = min(worst["top10Overlap"], overlap)
    ok = worst["songScoreMaxAbs"] <= 0.01 and worst["songPrintMinCos"] >= 0.9999 and worst["top10Overlap"] >= 9
    return ok, worst


def verify(kind, src, dst, n=24):
    import numpy as np
    if kind == "yamnet":
        ok, worst = verify_yamnet(src, dst)
    else:
        worst = {"maxAbs": 0.0, "minCos": 1.0}
        for x in inputs_for(kind, n):
            p = run_tflite(src, x)[0]
            q = run_onnx(dst, x)[0]
            worst["maxAbs"] = max(worst["maxAbs"], float(np.max(np.abs(p - q))))
            worst["minCos"] = min(worst["minCos"], float(np.dot(p.ravel(), q.ravel()) / (np.linalg.norm(p) * np.linalg.norm(q) + 1e-12)))
        ok = worst["minCos"] >= 0.99999 if kind == "effnet" else worst["maxAbs"] <= 1e-5
    print("  %-16s %s %s" % (os.path.basename(dst), "ok" if ok else "MISMATCH", json.dumps(worst)))
    if not ok:
        sys.exit("conversion of %s does not reproduce TensorFlow Lite" % src)
    return worst


def write_check(yamnet_src):
    """
    What the desktop's self-check compares against (desktop ModelCheck.kt):
    the phone's own YAMNet, through TensorFlow Lite, on the shared test
    signal - averaged the way the analyser averages it - and Essentia's own
    EffNet embedding and head readings for that signal's first patch, from
    the fixture tools/models/convert.py wrote when the phone's models were made.
    """
    import numpy as np
    import tensorflow as tf
    sys.path.insert(0, os.path.dirname(__file__))
    from convert import signal  # the same signal MusicMel.testSignal builds
    x = signal()
    it = tf.lite.Interpreter(model_path=yamnet_src)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    outs = it.get_output_details()
    scale, zero = outs[1]["quantization"]
    scores = np.zeros(521)
    prints = np.zeros(1024)
    n = 0
    for o in range(0, x.size - 15600 + 1, 7800):
        it.set_tensor(inp["index"], x[o:o + 15600])
        it.invoke()
        scores += it.get_tensor(outs[0]["index"])[0]
        prints += scale * (it.get_tensor(outs[1]["index"]).reshape(-1).astype(np.int32) - zero)
        n += 1
    essentia = json.load(open(os.path.join(ROOT, "engine", "src", "test", "resources", "effnet_reference.json")))
    json.dump({
        "about": "References for the desktop's model self-check; written by tools/models/to_onnx.py.",
        "signal": "MusicMel.testSignal(30)",
        "yamnetFrames": n,
        "yamnetScores": [round(float(v), 6) for v in scores / n],
        "yamnetPrint": [round(float(v), 6) for v in prints / n],
        "effnetEmbedding0": essentia["embedding0"],
        "heads0": essentia["heads0"],
    }, open(os.path.join(OUT, "check.json"), "w"))


def convert():
    import onnx
    import tf2onnx as t2o
    os.makedirs(OUT, exist_ok=True)
    work = tempfile.mkdtemp()
    models = []
    for src, name in sources():
        raw = os.path.join(work, name + ".onnx")
        dst = os.path.join(OUT, name + ".onnx")
        print(name, "<-", rel(src), flush=True)
        tf2onnx(src, raw)
        model = onnx.load(raw)
        notes = {}
        if name == "yamnet":
            notes["biasesRestored"] = restore_fc_bias(model, src)
            scale, zero = add_dequantised_output(model, src, 1, "print_float")
            # Keep only what the analyser reads: the scores and the float print.
            outputs = [o for o in model.graph.output]
            keep = [outputs[0], outputs[2]]
            del model.graph.output[:]
            model.graph.output.extend(keep)
            rename_outputs(model, ["scores", "print"])
            notes["printScale"] = float(scale)
            notes["printZeroPoint"] = int(zero)
            kind = "yamnet"
        elif name == "effnet":
            outs = list(model.graph.output)
            wide = [o for o in outs if o.type.tensor_type.shape.dim[-1].dim_value == 1280]
            del model.graph.output[:]
            model.graph.output.extend(wide[:1])
            rename_outputs(model, ["embedding"])
            notes["fp16Weights"] = store_fp16(model)
            kind = "effnet"
        else:
            rename_outputs(model, ["output"])
            kind = "head"
        onnx.checker.check_model(model)
        onnx.save(model, dst)
        measured = verify(kind, src, dst)
        models.append({
            "name": name,
            "source": rel(src),
            "sourceSha256": sha256(src),
            "file": name + ".onnx",
            "sha256": sha256(dst),
            "bytes": os.path.getsize(dst),
            "input": model.graph.input[0].name,
            "comparedWithTFLite": measured,
            **notes,
        })
    shutil.copyfile(os.path.join(ASSETS, "music", "heads.json"), os.path.join(OUT, "heads.json"))
    write_check(os.path.join(ASSETS, "yamnet.tflite"))
    import onnxruntime as ort
    json.dump({
        "about": "The phone's models in ONNX form for the Windows build, made by tools/models/to_onnx.py.",
        "tf2onnx": t2o.__version__,
        "opset": OPSET,
        "checkedWithOnnxRuntime": ort.__version__,
        "models": models,
    }, open(MANIFEST, "w"), indent=1)
    shutil.rmtree(work, ignore_errors=True)
    print("wrote", rel(OUT))


if __name__ == "__main__":
    if "--check" in sys.argv:
        check()
    else:
        convert()
