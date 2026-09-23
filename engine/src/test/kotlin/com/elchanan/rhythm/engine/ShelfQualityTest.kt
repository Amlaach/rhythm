package com.elchanan.rhythm.engine

import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.TimeZone

/**
 * The shelves that promise "like this one" keep the promise, and are still
 * songs the listener likes.
 *
 * Before the neighbourhood was chosen first (see Recommender.pick, nearest),
 * on this library: "same sound" 22% of its other artists' songs from the
 * seed's genre, an artist's radio 27%, "because you liked" 7%, a song's
 * radio 38% - where picking at random gives 12.5%. They now sit around three
 * quarters and more. The floors below leave room for honest tuning and none
 * for going back.
 */
class ShelfQualityTest {

    companion object {
        private var savedZone: TimeZone? = null
        @BeforeClass @JvmStatic fun fixZone() {
            savedZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
        @AfterClass @JvmStatic fun restoreZone() { savedZone?.let { TimeZone.setDefault(it) } }

        private const val MIN_SAME_GENRE = 0.6
        private const val MIN_TASTE_LIFT = 0.5
    }

    @Test fun similarShelvesAreSimilarAndStillLiked() {
        val lib = EngineFixture.build(genres = ShelfQuality.GENRES)
        val e = EngineGoldenTest.engine(lib, EngineFixture.NOW)
        val library = ShelfQuality.libraryTaste(lib, e)
        val measures = ShelfQuality.all(lib, e)
        val kinds = measures.groupBy { it.label }
        assertTrue("every kind of similar shelf is on the page: ${kinds.keys}",
            kinds.keys.containsAll(listOf("same sound", "artist radio", "because", "radio")))
        for ((kind, list) in kinds) {
            val same = list.map { it.sameGenre }.average()
            val taste = list.map { it.taste }.average()
            assertTrue("$kind: only ${"%.0f".format(same * 100)}% from the seed's genre\n${list.joinToString("\n")}",
                same >= MIN_SAME_GENRE)
            assertTrue("$kind: taste %.2f against a library average of %.2f".format(taste, library),
                taste >= library + MIN_TASTE_LIFT)
        }
    }
}
