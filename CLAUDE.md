# Rhythm - notes for Claude

## Working with the owner
- Answer in Hebrew. Always.
- Android is built only by CI (the sandbox cannot reach Google's maven).
  Locally: `sh tools/typecheck-desktop.sh` (engine + desktop compile),
  `python3 tools/check_imports.py`, and the engine tests - run them inside
  the tree the typecheck script builds (`$TMPDIR/rhythm-typecheck`,
  `sh gradlew :engine:test`).
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

Also: the analysis pass (`AnalysisManager`) runs on `Dispatchers.Default`
at normal thread priority. TFLite uses 2 threads, and there is no
charging-only option. Weak phones can take around 10-20 s per song for
hours on the first run (estimate).

Agreed plan (owner approved the approach; asked to wait until users report
problems):
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
3. Run the analysis at background thread priority, with an option to
   analyse only while charging. This does not affect results.
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
