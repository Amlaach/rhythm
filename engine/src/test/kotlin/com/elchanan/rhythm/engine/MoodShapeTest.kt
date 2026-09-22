package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import org.junit.Assert.*
import org.junit.Test

class MoodShapeTest {

    /**
     * The indices are read against the code that writes them.
     *
     * Reading the wrong slot would take brightRise for energySpread and lift
     * the arousal of every track that merely gets brighter, with nothing
     * visible to say so. Checked against Analysis.merge rather than trusted.
     */
    @Test fun theShapeSlotsAreWhereMergeWritesThem() {
        val flat = Analysis.merge(1, List(8) { window(energy = 0.4) })
        val swinging = Analysis.merge(
            2,
            // A whisper for most of it, then a choir - the case this is for.
            List(6) { window(energy = 0.05) } + List(2) { window(energy = 1.4) }
        )
        val flatShape = Features.parseVector(flat.shape, Analysis.SHAPE_DIMS)
        val swingShape = Features.parseVector(swinging.shape, Analysis.SHAPE_DIMS)

        assertEquals(0.0, flatShape[Analysis.SHAPE_ENERGY_SPREAD], 1e-6)
        assertEquals(0.0, flatShape[Analysis.SHAPE_CONTRAST], 1e-6)
        assertTrue("spread", swingShape[Analysis.SHAPE_ENERGY_SPREAD] > 0.5)
        assertTrue("contrast", swingShape[Analysis.SHAPE_CONTRAST] > 1.0)

        // And the case rise alone would have missed: loud in the middle, quiet
        // at both ends, so last minus first is nothing at all.
        val middle = Analysis.merge(
            3,
            List(3) { window(energy = 0.05) } + List(2) { window(energy = 1.4) } +
                List(3) { window(energy = 0.05) }
        )
        val middleShape = Features.parseVector(middle.shape, Analysis.SHAPE_DIMS)
        assertEquals("rise sees nothing", 0.0, middleShape[0], 1e-6)
        assertTrue("contrast sees it", middleShape[Analysis.SHAPE_CONTRAST] > 1.0)
    }

    @Test fun aTrackThatExplodesIsNotScoredAsStillAsOneThatNeverMoves() {
        // Identical averages, different insides. Before the shape vector was
        // read these two were indistinguishable.
        val steady = feature(1, spread = 0.0, contrast = 0.0)
        val swinging = feature(2, spread = 1.1, contrast = 2.4)
        val library = listOf(steady, swinging) + List(10) { i ->
            feature(10L + i, spread = 0.1 * i, contrast = 0.2 * i)
        }
        val model = MoodModel(library)
        assertTrue(model.ready)
        assertTrue(
            "swinging ${model.arousal(swinging)} should exceed steady ${model.arousal(steady)}",
            model.arousal(swinging) > model.arousal(steady)
        )
    }

    @Test fun aTrackAnalysedBeforeTheShapeVectorKeepsItsOldScore() {
        // An older row has no shape at all, and must not be nudged on zeros.
        val library = List(12) { i -> feature(i.toLong(), spread = 0.4, contrast = 0.8) }
        val withShape = MoodModel(library)
        val without = MoodModel(library.map { it.copy(shape = "") })
        val target = library.first()
        assertTrue(withShape.arousal(target) >= without.arousal(target))
        assertTrue(without.arousal(target) in 0.0..1.0)
    }

    @Test fun theLiftNeverLeavesTheRange() {
        val library = List(12) { i ->
            feature(i.toLong(), spread = 5.0 * i, contrast = 9.0 * i, energy = 0.95f)
        }
        val model = MoodModel(library)
        for (f in library) {
            assertTrue(f.songId.toString(), model.arousal(f) in 0.0..1.0)
        }
    }

    @Test fun calmMeansCalmAllTheWayThroughAndNotOnAverage() {
        // The rule stated plainly: someone asking for רגוע wants the whole
        // track, not a track whose average happens to be low because it
        // whispers for three minutes before a choir arrives.
        val whisperThenChoir = feature(1, spread = 1.3, contrast = 2.8, energy = 0.06f)
        val genuinelyCalm = feature(2, spread = 0.02, contrast = 0.04, energy = 0.06f)
        val library = listOf(whisperThenChoir, genuinelyCalm) + List(10) { i ->
            feature(10L + i, spread = 0.08 * i, contrast = 0.15 * i, energy = 0.06f)
        }
        val model = MoodModel(library)

        assertTrue("a steady quiet track is calm", model.matches(Mood.CALM, genuinelyCalm))
        assertFalse(
            "a track that erupts is not calm, whatever its average",
            model.matches(Mood.CALM, whisperThenChoir)
        )
        // Night makes the same promise and keeps it the same way.
        assertFalse(model.matches(Mood.NIGHT, whisperThenChoir))
    }

    @Test fun theLiftAloneWouldNotHaveBeenEnough() {
        // Why the filter asks as well as the lift. On its own the correction
        // is small - by design, since arousal is shared with every mood - and
        // a quiet enough average survives it.
        val quiet = 0.10
        val lifted = quiet + MoodModel.SWING_WEIGHT * 1.0 * (1.0 - quiet)
        assertTrue("the lift alone leaves it under the calm bar", lifted <= 0.40)
    }

    @Test fun aGuessedTempoDoesNotMakeAFreeRhythmPieceEnergetic() {
        // The reported bug. A niggun in free time gives the detector nothing
        // to lock onto, it answers anyway, and a sparse pulse aliases upward -
        // one beat every two seconds comes back as a confident sounding 120.
        // Weighted equally with a real measurement, that put the calmest
        // recordings in a library at the top of קצבי.
        val freeRhythm = tempo(1, bpm = 124f, confidence = 0.08f, onsets = 0.9f)
        val reallyFast = tempo(2, bpm = 138f, confidence = 0.85f, onsets = 4.4f)
        val library = listOf(freeRhythm, reallyFast) + List(12) { i ->
            tempo(10L + i, bpm = 72f + i, confidence = 0.7f, onsets = 1.2f + i * 0.05f)
        }
        val model = MoodModel(library)

        assertTrue("a genuinely fast track is still energetic", model.matches(Mood.ENERGETIC, reallyFast))
        assertFalse(
            "a guessed tempo must not carry a free rhythm piece into קצבי",
            model.matches(Mood.ENERGETIC, freeRhythm)
        )
        // And the unsure one scores lower than the sure one on the axis itself.
        assertTrue(model.arousal(freeRhythm) < model.arousal(reallyFast))
    }

    @Test fun aMoodListLeadsWithItsStrongestExample() {
        // Matching is a yes or no. Asking for קצבי and being handed the
        // quietest track that cleared the bar is a correct answer to the wrong
        // question.
        val songs = (0 until 12).map { song(it.toLong(), "artist-$it") }
        val features = songs.associate { s ->
            val i = s.id.toInt()
            s.id to tempo(s.id, bpm = 120f + i * 4, confidence = 0.9f, onsets = 3.5f + i * 0.2f)
        }
        val ordered = Mood.strongest(songs, features, Mood.ENERGETIC)
        assertTrue(ordered.isNotEmpty())
        val model = MoodModel(features.values)
        val strengths = ordered.map { model.strength(Mood.ENERGETIC, features[it.id]) }
        assertEquals(strengths.sortedDescending(), strengths)
        // The same songs, just in a useful order.
        assertEquals(
            Mood.filter(songs, features, Mood.ENERGETIC).map { it.id }.toSet(),
            ordered.map { it.id }.toSet()
        )
    }

    @Test fun calmLeadsWithTheCalmest() {
        // The mirror of the above, so "strongest" is not quietly "loudest".
        val songs = (0 until 12).map { song(it.toLong(), "artist-$it") }
        val features = songs.associate { s ->
            val i = s.id.toInt()
            s.id to tempo(s.id, bpm = 60f + i * 2, confidence = 0.9f, onsets = 0.8f + i * 0.05f)
        }
        val ordered = Mood.strongest(songs, features, Mood.CALM)
        val model = MoodModel(features.values)
        assertTrue(ordered.isNotEmpty())
        assertEquals(
            ordered.first().id,
            ordered.minByOrNull { model.arousal(features.getValue(it.id)) }?.id
        )
    }

    private fun tempo(id: Long, bpm: Float, confidence: Float, onsets: Float) =
        Analysis.blankFor(id).copy(
            energy = 0.3f,
            bpm = bpm,
            bpmConfidence = confidence,
            onsetRate = onsets,
            brightness = 1500f,
            shape = List(6) { 0.0 }.joinToString(",")
        )

    private fun song(id: Long, artist: String) = com.elchanan.rhythm.data.db.SongEntity(
        id, "song-$id", "song-$id", artist, artist, "album", 1, 180000,
        1, 2026, null, "/music/$id.mp3", "/music", 0, 1000
    )

    private fun window(energy: Double) = Analysis.WindowStats(
        energy = energy, brightness = 1200.0, flatness = 0.2, dynamics = 0.3,
        bpm = 100.0, bpmConfidence = 0.5, onsetRate = 2.0,
        chroma = DoubleArray(12) { 1.0 }, chroma24 = DoubleArray(24) { 1.0 },
        mfccMean = DoubleArray(12) { 0.5 }, mfccVar = DoubleArray(12) { 0.1 }
    )

    private fun feature(
        id: Long,
        spread: Double,
        contrast: Double,
        energy: Float = 0.3f
    ): AudioFeatureEntity = Analysis.blankFor(id).copy(
        energy = energy,
        bpm = 90f,
        brightness = 1500f,
        onsetRate = 2f,
        // energyRise, energySpread, brightRise, onsetRise, drift, contrast
        shape = listOf(0.0, spread, 0.0, 0.0, 0.0, contrast).joinToString(",")
    )
}
