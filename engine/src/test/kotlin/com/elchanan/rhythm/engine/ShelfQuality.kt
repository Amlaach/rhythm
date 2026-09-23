package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity

/**
 * How well the shelves that promise "similar" keep that promise.
 *
 * Measured on [EngineFixture] built with genres, where the answer is known:
 * a song's genre. A shelf built on one song ought to be mostly that song's
 * genre, from artists other than its own as well - picking at random gives
 * one in [GENRES]. And it ought still to be songs the listener likes, or it
 * would be "similar" and useless: so how well the shelf scores for the
 * listener is measured beside it.
 */
object ShelfQuality {

    const val GENRES = 8

    class Measure(val label: String, val sameGenre: Double, val otherArtists: Int, val taste: Double, val skipped: Int) {
        override fun toString() =
            "%s: same genre %.0f%% of %d by other artists, taste %.2f, heavily skipped %d".format(
                java.util.Locale.ROOT, label, sameGenre * 100, otherArtists, taste, skipped
            )
    }

    fun measure(label: String, seed: SongEntity, shelf: List<SongEntity>, lib: EngineFixture.Library, e: Recommender): Measure {
        val genre = lib.genreOf.getValue(seed.id)
        val others = shelf.filter { it.id != seed.id && it.artistKey != seed.artistKey }
        val same = if (others.isEmpty()) 0.0 else others.count { lib.genreOf[it.id] == genre }.toDouble() / others.size
        val taste = shelf.filter { it.id != seed.id }.map { e.totalScore(it) }.average()
        val skipped = shelf.count { (lib.stats[it.id]?.skipCount ?: 0) >= 5 }
        return Measure(label, same, others.size, taste, skipped)
    }

    /** Every similar-shelf in the engine, measured. */
    fun all(lib: EngineFixture.Library, e: Recommender): List<Measure> {
        val out = ArrayList<Measure>()
        val byId = lib.songs.associateBy { it.id }
        val feed = e.buildFeed()
        for (section in feed) {
            if (section.id.startsWith("because:")) {
                val seed = byId.getValue(section.id.removePrefix("because:").toLong())
                out += measure("because", seed, section.songs, lib, e)
            }
            for (mix in section.mixes) {
                if (mix.id.startsWith("mix:sound:")) {
                    out += measure("same sound", mix.songs.first(), mix.songs.drop(1), lib, e)
                }
                if (mix.id.startsWith("mix:artist:")) {
                    val key = mix.id.removePrefix("mix:artist:")
                    val seed = mix.songs.first { it.artistKey == key }
                    out += measure("artist radio", seed, mix.songs.filter { it.artistKey != key }, lib, e)
                }
            }
        }
        for (seed in lib.songs.filterIndexed { i, s -> i % 150 == 7 && lib.genreOf.containsKey(s.id) }) {
            out += measure("radio", seed, e.radio(seed, 40).drop(1), lib, e)
        }
        return out
    }

    /** The library's own average score, the level "taste" is read against. */
    fun libraryTaste(lib: EngineFixture.Library, e: Recommender): Double =
        lib.songs.map { e.totalScore(it) }.average()
}
