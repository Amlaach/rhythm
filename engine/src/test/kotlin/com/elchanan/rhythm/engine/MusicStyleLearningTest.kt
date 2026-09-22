package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * The style learner deciding for itself whether the music print helps.
 */
class MusicStyleLearningTest {

    private fun song(id: Long, artist: String) = SongEntity(
        id, "song-$id", "song-$id", artist, artist, "album", 1, 180000,
        1, 2026, null, "/music/$id.mp3", "/music", 0, 1000
    )

    /** Every measured feature the same up to noise; only the print may differ. */
    private fun feature(id: Long, folk: Boolean, printSeparates: Boolean, r: Random): AudioFeatureEntity {
        val print = FloatArray(MusicPrint.DIMS) { i ->
            val signal = if (printSeparates && (i % 2 == 0) == folk) 1.2f else 0f
            signal + r.nextFloat() * 0.5f
        }
        return Analysis.blankFor(id).copy(
            energy = 0.5f + r.nextFloat() * 0.05f,
            bpm = 110f + r.nextFloat() * 5f,
            brightness = 1000f + r.nextFloat() * 50f,
            onsetRate = 3f + r.nextFloat() * 0.2f,
            musicPrint = MusicPrint.pack(print)
        )
    }

    private fun learn(printSeparates: Boolean): LearnResult {
        val r = Random(5)
        val songs = ArrayList<SongEntity>()
        val features = HashMap<Long, AudioFeatureEntity>()
        val styles = HashMap<String, String>()
        for (artist in 0 until 8) {
            val folk = artist % 2 == 0
            styles["artist-$artist"] = if (folk) "folk" else "rock"
            repeat(16) {
                val id = (artist * 16 + it).toLong()
                songs.add(song(id, "artist-$artist"))
                features[id] = feature(id, folk, printSeparates, r)
            }
        }
        return StyleLearning.learn(songs, emptyMap(), styles, features)
    }

    @Test fun aPrintThatHearsTheStylesIsUsed() {
        val result = learn(printSeparates = true)
        assertTrue("with ${result.withMusicF1} without ${result.withoutMusicF1}", result.usedMusic)
        assertTrue(result.withMusicF1!! > result.withoutMusicF1!! + 0.2)
        assertTrue(result.accepted.any { it.style == "folk" })
        assertTrue(StyleLearning.report(result).contains("הלמידה משתמשת בטביעה המוזיקלית"))
    }

    @Test fun aPrintThatHearsNothingIsLeftOut() {
        val result = learn(printSeparates = false)
        assertFalse("with ${result.withMusicF1} without ${result.withoutMusicF1} accepted ${result.accepted.map { it.style }}", result.usedMusic)
    }

    @Test fun theFoldedPrintHasNamesInTheModelsExplanation() {
        assertEquals(
            "טביעה מוזיקלית 1",
            StyleLearner.featureName(StyleTraining.BASE_INPUTS)
        )
        val f = feature(1, true, true, Random(1))
        assertEquals(StyleTraining.BASE_INPUTS + StyleTraining.MUSIC_INPUTS, StyleTraining.featuresFor(f, music = true)!!.size)
        assertNull(StyleTraining.featuresFor(f.copy(musicPrint = MusicPrint.TRIED), music = true))
    }
}
