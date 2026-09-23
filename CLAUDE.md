# Rhythm - notes for Claude

## Working with the owner
- Answer in Hebrew. Always.
- Android is built only by CI (the sandbox cannot reach Google's maven).
  Locally: `sh tools/typecheck-desktop.sh` (engine + desktop compile),
  `python3 tools/check_imports.py`, and the engine tests - run them inside
  the tree the typecheck script builds (`$TMPDIR/rhythm-typecheck`,
  `sh gradlew :engine:test`).
- Run engine tests on JDK 17, like CI: `apt-get install openjdk-17-jdk-headless`,
  then `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 sh gradlew :engine:test
  -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` in the typecheck
  tree. On JDK 19+ the regex `\b` stops treating Hebrew letters as word
  characters, so `Versions` groups remixes differently. JDK 17 and Android
  (ICU) agree with each other, and EngineGoldenTest is recorded on 17.
- EngineGoldenTest pins every recommendation. A change that is not meant to
  alter recommendations must pass it unchanged. Only re-record the golden
  file (`RHYTHM_GOLDEN_UPDATE=1`) when the owner asked for the algorithm
  itself to change.
- CI runs on pull requests and on pushes to main, not on other branches.
- Merge only when the owner says "מזג" / "תמזג", and only with CI green.
- The recommendations are the heart of the app and took a long time to get
  right. Never change what the algorithm computes as a side effect of
  another change.

## Known issue, not fixed yet: engine memory on large libraries

Measured September 2026 (JVM, synthetic library): one `Recommender`
snapshot holds about 10.5 KB per song.

| songs  | one snapshot |
|--------|--------------|
| 2,000  | 21 MB        |
| 5,000  | 53 MB        |
| 10,000 | 105 MB       |
| 20,000 | 213 MB       |

Why:
- `MusicRepository.buildRecommender()` reads every `audio_features` row and
  hands them all to the engine. Each row carries its prints as text, about
  4.5 KB: music print ~1.7 KB, sound print ~1.4 KB, tags ~0.6 KB.
- `AcousticSpace` only ever uses them folded to 128 dims, but the raw
  strings stay in memory for the life of the snapshot.
- There can be two snapshots at once: the one `MainViewModel` keeps, and
  the one `PlaybackService` builds for queue continuation and radio.
- Snapshots are rebuilt from the database on every feed refresh and every
  queue extension.
- Low-end phones give an app a heap of about 128-256 MB, so libraries of
  roughly 5,000+ songs are at risk there.

Also: the analysis pass (`AnalysisManager`) now runs on its own thread at
background priority (PR #32). TFLite uses 2 threads, and there is still no
charging-only option. Weak phones can take around 10-20 s per song for
hours on the first run (estimate).

Status: steps 1-2 below are done (PR #31): the golden test, and
`Recommender.leanFeatures`. Measured result: 10k songs 100 -> 80 MB, 20k
songs 201 -> 162 MB, about 20%, less than first estimated. The sound print
must stay, because MoodModel reads it lazily. `largeHeap` is on. A further
exact-preserving idea not done: drop the sound print too when no song has
mood marks, since MoodModel only reads prints when marks exist.

Plan (owner approved):
1. Before touching anything, write a golden test. Build a large synthetic
   library with plays, likes, skips, dates and moods. Record every output:
   per-song scores, feed order, radio, continuation, calibration report,
   mood results. The refactor must reproduce them exactly - identical
   order and scores. The test stays in CI for good.
2. Keep only what the engine uses. Fold the prints once, when the
   snapshot is built, with the same arithmetic and still in doubles, then
   drop the raw strings from the rows the engine holds. First check every
   reader of `features[...]`: StyleLearner (music print folded to 64),
   `Vocal.heard` and `Spoken` (tags), moods (`musicMoods`), SoundCheck,
   calibration. Expected: 10,000 songs from ~105 MB to ~25-35 MB (estimate).
3. Run the analysis at background thread priority (done), with an option
   to analyse only while charging (not done). This does not affect results.
4. Not agreed, too risky for now: one shared snapshot between the screens
   and the player service (risk of stale data - a like not felt at once),
   and Float instead of Double (changes scores slightly and can reorder
   near ties).

User reports that point to this issue:
- The app closes by itself / crashes, especially on a large library or a
  cheap phone. It often happens a few seconds after opening, when the
  queue refills (every ~15 songs), or on "רענן" in the home screen.
- The home screen or a mix takes a long time to appear. "Start radio"
  waits several seconds.
- Taps don't respond for a moment, or scrolling stutters, while music
  plays.
- The phone heats up or the battery drains during the first analysis, and
  the UI lags while "מנתח" is running.
- Music stops after the app was in the background. Android killed it for
  memory.
- It only happens to people with thousands of songs, never with a few
  hundred.

## The Windows build and the models

- The desktop runs the phone's models (YAMNet, Discogs-EffNet, the mood
  heads) through ONNX Runtime, from `desktop/src/main/resources/models`.
  Those files are converted from the phone's own .tflite files by
  `tools/models/to_onnx.py`, which checks each one against TensorFlow Lite
  and refuses a mismatch. tf2onnx drops the bias of YAMNet's last layer;
  the script puts it back.
- If the phone's models change, run `python tools/models/to_onnx.py`
  (needs `tensorflow-cpu==2.15.*`, `tf2onnx==1.16.1`, `onnxruntime==1.26.0`,
  `numpy<2` in a venv; PyPI is reachable from the sandbox) and commit the
  result. CI runs `to_onnx.py --check` and fails when they are out of step.
- `desktop/audio/ModelCheck` runs the models on `MusicMel.testSignal()`
  against the phone's and Essentia's readings. CI runs it on Linux
  (`:desktop:test`) and inside the built Windows image
  (`Rhythm.exe --check-models=<file>`). `:desktop:test` cannot run in the
  sandbox (Compose needs Google's maven at runtime); run the check with
  jshell over the typecheck tree's classes instead.
- Desktop rows and stats carry the same columns as the phone's; the desktop
  `Store` adds missing columns by itself from its DDL.
