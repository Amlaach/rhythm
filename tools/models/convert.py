"""
Fetches MTG-UPF's Discogs-EffNet and its mood heads, converts them to TFLite
for the phone, and checks the conversion against Essentia itself.

Runs on GitHub Actions (the development sandbox cannot reach the model
host). Nothing here ships; what ships is what it writes:

  app/src/main/assets/music/effnet.tflite        the embedding model
  app/src/main/assets/music/<head>.tflite        one small model per head
  app/src/main/assets/music/heads.json           head names, classes, order
  engine/src/test/resources/effnet_reference.json  a fixture for the Kotlin
      mel front end: the same synthetic signal, and what Essentia made of it

Models: Essentia models by MTG-UPF, CC BY-NC-SA 4.0.
"""
import json
import math
import os
import subprocess
import sys
import urllib.request

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORK = os.path.join(ROOT, "build", "models")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "music")
FIXTURE = os.path.join(ROOT, "engine", "src", "test", "resources", "effnet_reference.json")
BASE = "https://essentia.upf.edu/models"

EFFNET_PB = f"{BASE}/feature-extractors/discogs-effnet/discogs-effnet-bs64-1.pb"
EFFNET_ONNX = f"{BASE}/feature-extractors/discogs-effnet/discogs-effnet-bsdynamic-1.onnx"

HEADS = [
    "mood_happy", "mood_sad", "mood_relaxed", "mood_aggressive", "mood_party",
    "danceability", "mood_acoustic", "mood_electronic",
    "deam", "emomusic", "muse",
]

SR = 16000


def fetch(url, name):
    os.makedirs(WORK, exist_ok=True)
    path = os.path.join(WORK, name)
    if not os.path.exists(path):
        print("fetch", url, flush=True)
        urllib.request.urlretrieve(url, path)
    print("  ", name, os.path.getsize(path), "bytes")
    return path


def signal(seconds=30):
    """The same deterministic test signal the Kotlin test builds."""
    n = np.arange(int(SR * seconds), dtype=np.float64)
    t = n / SR
    x = (0.30 * np.sin(2 * math.pi * 220.0 * t)
         + 0.20 * np.sin(2 * math.pi * 660.0 * t) * (0.5 + 0.5 * np.sin(2 * math.pi * 2.0 * t))
         + 0.10 * np.sin(2 * math.pi * 3000.0 * t)
         + 0.15 * np.sin(2 * math.pi * (100.0 + 40.0 * t) * t))
    return x.astype(np.float32)


def essentia_reference(audio, pb):
    import essentia.standard as es
    out = {}
    for start in (True, False):
        tim = es.TensorflowInputMusiCNN()
        frames = np.array([tim(f) for f in es.FrameGenerator(audio, frameSize=512, hopSize=256, startFromZero=start)])
        out[start] = frames
        print("mel startFromZero=%s frames=%d" % (start, len(frames)))
    emb = es.TensorflowPredictEffnetDiscogs(graphFilename=pb, output="PartitionedCall:1")(audio)
    print("essentia embeddings", emb.shape)
    return out, np.array(emb)


def patches(mel, hop=62, size=128):
    count = (len(mel) - size) // hop + 1
    return np.stack([mel[i * hop:i * hop + size] for i in range(count)]).astype(np.float32)


def run_tflite(path, batch):
    import tensorflow as tf
    it = tf.lite.Interpreter(model_path=path)
    inp = it.get_input_details()[0]
    outs = it.get_output_details()
    results = []
    for x in batch:
        x = x[None, ...].astype(np.float32)
        if list(inp["shape"]) != list(x.shape):
            it.resize_tensor_input(inp["index"], x.shape)
        it.allocate_tensors()
        it.set_tensor(inp["index"], x)
        it.invoke()
        results.append([it.get_tensor(o["index"])[0] for o in outs])
    return results, outs


def convert_effnet_onnx(onnx_path):
    import onnx
    model = onnx.load(onnx_path)
    names = [i.name for i in model.graph.input]
    print("onnx inputs", [(i.name, [d.dim_value or d.dim_param for d in i.type.tensor_type.shape.dim])
                          for i in model.graph.input])
    print("onnx outputs", [o.name for o in model.graph.output])
    out = os.path.join(WORK, "effnet_onnx2tf")
    # -kat: the input is [batch, frames, bands], not channels-first; left to
    # itself onnx2tf transposes it as if it were and the first convolution fails.
    cmd = ["onnx2tf", "-i", onnx_path, "-o", out, "-b", "1", "-osd", "-kt"] + names
    print(" ".join(cmd), flush=True)
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(r.stdout[-3000:])
    print(r.stderr[-3000:])
    return out if r.returncode == 0 else None


def quantize(saved_model_dir, name, float16=False):
    import tensorflow as tf
    conv = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    if float16:
        conv.target_spec.supported_types = [tf.float16]
    data = conv.convert()
    path = os.path.join(WORK, name)
    open(path, "wb").write(data)
    print("wrote", name, len(data), "bytes")
    return path


def convert_frozen(pb, inputs, outputs, shape, name, quant=True):
    import tensorflow as tf
    conv = tf.compat.v1.lite.TFLiteConverter.from_frozen_graph(
        pb, input_arrays=inputs, output_arrays=outputs, input_shapes={inputs[0]: shape})
    if quant:
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
    data = conv.convert()
    path = os.path.join(WORK, name)
    open(path, "wb").write(data)
    print("wrote", name, len(data), "bytes")
    return path


def batch_of_one(pb):
    import tensorflow as tf
    from tensorflow.core.framework import graph_pb2
    g = graph_pb2.GraphDef()
    g.ParseFromString(open(pb, "rb").read())
    changed = 0
    # The model's body is a function (the graph calls it through
    # PartitionedCall), so its nodes live in the library, not the top level.
    nodes = list(g.node)
    for f in g.library.function:
        nodes.extend(f.node_def)
        for arg in f.arg_attr.values():
            if "_output_shapes" in arg.attr:
                del arg.attr["_output_shapes"]
            if "_user_specified_name" in arg.attr:
                pass
        for key in list(f.attr.keys()):
            if key.startswith("_input_shapes") or key == "_output_shapes":
                del f.attr[key]
    for node in nodes:
        # Every node carries the shapes it had at export, batch of 64 included,
        # and constant folding trusts them over the new placeholder.
        for key in ("_output_shapes", "_input_shapes"):
            if key in node.attr:
                del node.attr[key]
        if node.op == "Placeholder" and "shape" in node.attr:
            dims = node.attr["shape"].shape.dim
            if dims and dims[0].size == 64:
                dims[0].size = 1
                changed += 1
        if node.op == "Const" and node.attr["dtype"].type == tf.int32.as_datatype_enum:
            t = tf.make_ndarray(node.attr["value"].tensor)
            if t.ndim == 1 and 2 <= t.size <= 5 and t[0] == 64:
                t = t.copy()
                t[0] = -1
                node.attr["value"].tensor.CopyFrom(tf.make_tensor_proto(t, dtype=tf.int32))
                changed += 1
        if node.op == "PartitionedCall" or node.op == "StatefulPartitionedCall":
            for key in ("_output_shapes", "Tout_shapes"):
                if key in node.attr:
                    del node.attr[key]
    print("batch surgery changed", changed, "nodes")
    path = os.path.join(WORK, "effnet_single.pb")
    open(path, "wb").write(g.SerializeToString())
    return path


def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


def pick_embedding(results, outs):
    """The 1280 wide output, whichever index the converter gave it."""
    for i, o in enumerate(outs):
        if o["shape"][-1] == 1280:
            return np.array([r[i] for r in results]), o["name"]
    raise SystemExit("no 1280 wide output in " + str([o["shape"] for o in outs]))


def main():
    os.makedirs(ASSETS, exist_ok=True)
    pb = fetch(EFFNET_PB, "effnet.pb")
    onnx_path = fetch(EFFNET_ONNX, "effnet.onnx")

    audio = signal()
    mels, reference = essentia_reference(audio, pb)

    candidates = []
    saved = convert_effnet_onnx(onnx_path)
    if saved:
        for fp16 in (False, True):
            try:
                candidates.append(quantize(saved, "effnet_onnx_%s.tflite" % ("fp16" if fp16 else "int8"), fp16))
            except Exception as e:  # keep going: the other routes may work
                print("quantize failed", fp16, e)
    # The frozen graph is fixed at a batch of 64. Rewrite the placeholder to a
    # batch of one, and any constant shape that spells out the 64, then let the
    # comparison against Essentia say whether the surgery was sound.
    try:
        single = batch_of_one(pb)
        for outputs in (["PartitionedCall:1"], ["PartitionedCall"]):
            try:
                candidates.append(convert_frozen(single, ["serving_default_melspectrogram"], outputs, [1, 128, 96],
                                                 "effnet_pb_%d.tflite" % len(candidates)))
            except Exception as e:
                print("frozen conversion failed", outputs, e)
    except Exception as e:
        print("graph surgery failed", e)

    best = None
    for start, mel in mels.items():
        batch = patches(mel)
        n = min(len(batch), len(reference))
        for path in candidates:
            try:
                results, outs = run_tflite(path, batch[:n])
                emb, out_name = pick_embedding(results, outs)
            except Exception as e:
                print("run failed", path, e)
                continue
            sims = [cos(emb[i], reference[i]) for i in range(n)]
            score = min(sims)
            print("startFromZero=%s %s out=%s patches=%d min cos=%.5f mean cos=%.5f size=%d"
                  % (start, os.path.basename(path), out_name, n, score, float(np.mean(sims)), os.path.getsize(path)))
            key = (score > 0.999, -os.path.getsize(path), score)
            if best is None or key > best[0]:
                best = (key, path, start, emb[0])
    if best is None or best[0][2] < 0.99:
        raise SystemExit("no conversion reproduces Essentia: " + str(best and best[0]))
    _, path, start, first = best
    print("chosen", os.path.basename(path), "startFromZero", start)
    os.replace(path, os.path.join(ASSETS, "effnet.tflite"))

    # Heads: small MLPs on the 1280 wide embedding.
    import essentia.standard as es
    heads_meta = []
    head_ref = {}
    for head in HEADS:
        stem = f"{head}-discogs-effnet-1"
        folder = f"{BASE}/classification-heads/{head}"
        try:
            meta = json.load(open(fetch(f"{folder}/{stem}.json", stem + ".json")))
            hpb = fetch(f"{folder}/{stem}.pb", stem + ".pb")
        except Exception as e:
            print("head missing", head, e)
            continue
        schema = meta.get("schema", {})
        inp = schema["inputs"][0]["name"]
        outs = [o for o in schema["outputs"] if o.get("output_purpose") == "predictions"] or schema["outputs"][:1]
        out = outs[0]["name"]
        try:
            hpath = convert_frozen(hpb, [inp], [out], [1, 1280], head + ".tflite", quant=False)
        except Exception as e:
            print("head conversion failed", head, e)
            continue
        ref = es.TensorflowPredict2D(graphFilename=hpb, input=inp, output=out)(reference[:4].astype(np.float32))
        mine, _ = run_tflite(hpath, reference[:4].astype(np.float32))
        mine = np.array([m[0] for m in mine])
        diff = float(np.max(np.abs(np.array(ref) - mine)))
        print(head, "classes", meta.get("classes"), "max diff", diff)
        if diff > 1e-3:
            print("  skipped: conversion does not match")
            continue
        os.replace(hpath, os.path.join(ASSETS, head + ".tflite"))
        heads_meta.append({"name": head, "classes": meta.get("classes"), "type": meta.get("type"),
                           "dataset": meta.get("dataset", {}).get("name")})
        head_ref[head] = np.array(ref)[0].tolist()

    json.dump({"license": "Essentia models by MTG-UPF, CC BY-NC-SA 4.0",
               "embedding": 1280, "patch": 128, "bands": 96, "heads": heads_meta},
              open(os.path.join(ASSETS, "heads.json"), "w"), indent=1)

    mel = mels[start]
    os.makedirs(os.path.dirname(FIXTURE), exist_ok=True)
    json.dump({
        "startFromZero": bool(start),
        "frames": len(mel),
        # a few frames spread through the signal, every band
        "melAt": {str(i): mel[i].tolist() for i in (0, 1, 2, 50, 300, len(mel) - 1)},
        "embedding0": [float(v) for v in first],
        "heads0": head_ref,
    }, open(FIXTURE, "w"))
    print("done")


if __name__ == "__main__":
    sys.exit(main())
