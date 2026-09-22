package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SoundPrintTest {

    @Test fun aPrintSurvivesStorageToWithinOneStep() {
        val r = Random(1)
        val values = FloatArray(SoundPrint.DIMS) { r.nextFloat() * 6f }
        val back = SoundPrint.unpack(SoundPrint.pack(values))!!
        for (i in values.indices) assertEquals(values[i], back[i], 6f / 255f)
    }

    @Test fun base64MatchesTheStandardEncoding() {
        // Checked against the JVM's own encoder here, where it exists - the
        // hand written one is for phones older than Android 8, which lack it.
        val r = Random(2)
        for (n in listOf(0, 1, 2, 3, 4, 5, 1024)) {
            val bytes = ByteArray(n) { r.nextInt(256).toByte() }
            val ours = SoundPrint.Base64.encode(bytes)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), ours)
            assertArrayEquals(bytes, SoundPrint.Base64.decode(ours))
        }
    }

    @Test fun neverMadeAndTriedAreNotPrints() {
        assertNull(SoundPrint.unpack(""))
        assertNull(SoundPrint.unpack(SoundPrint.TRIED))
        assertNull(SoundPrint.unpack("not base64!"))
        assertNull(SoundPrint.unpack(SoundPrint.Base64.encode(ByteArray(10))))
    }

    @Test fun centringIsWhatMakesSongsDistinguishable() {
        // Two groups on top of a large shared positive level, as a ReLU layer
        // produces. Raw, every pair looks alike; centred, the groups separate.
        val r = Random(3)
        fun print(group: Int) = FloatArray(SoundPrint.DIMS) { i ->
            3f + (if ((i % 2 == 0) == (group == 0)) 0.6f else 0f) + r.nextFloat() * 0.2f
        }
        val prints = (0 until 10).associate { it.toLong() to print(it % 2) }
        val c = SoundPrint.centred(prints)
        val same = SoundPrint.similarity(c.getValue(0L), c.getValue(2L))
        val other = SoundPrint.similarity(c.getValue(0L), c.getValue(1L))
        assertTrue("same group $same, other group $other", same > 0.5 && other < -0.5)
    }
}

class SoundCheckTest {

    private fun song(id: Long, artist: String) = SongEntity(
        id, "שיר $id", "שיר $id", artist, Names.normalizeKey(artist), "al$id", id,
        240_000, 1, 2020, null, "/m/$id", "/m/$artist", 0, 1
    )

    /** Identical measured sound for everyone, so only the print can tell styles apart. */
    private fun feature(id: Long, print: FloatArray?, r: Random) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L, bpm = 100f + r.nextFloat() * 20f, bpmConfidence = 0.9f,
        musicalKey = 0, mode = 1, energy = 0.5f + r.nextFloat() * 0.1f,
        brightness = 0.4f + r.nextFloat() * 0.1f, flatness = 0.1f, dynamics = 0.8f,
        onsetRate = 1f, chroma = "1,0,0,0,0,0,0,0,0,0,0,0",
        timbre = "0,0,0,0,0,0,0,0,0,0,0,0", timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0",
        soundPrint = print?.let { SoundPrint.pack(it) } ?: ""
    )

    private fun library(printsSeparate: Boolean): Triple<List<SongEntity>, Map<Long, AudioFeatureEntity>, Map<String, String>> {
        val r = Random(9)
        val songs = ArrayList<SongEntity>()
        val features = HashMap<Long, AudioFeatureEntity>()
        val styles = HashMap<String, String>()
        var id = 1L
        for (a in 0 until 8) {
            val artist = "אמן$a"
            val style = if (a % 2 == 0) "חסידי" else "מזרחי"
            styles[Names.normalizeKey(artist)] = style
            repeat(6) {
                songs.add(song(id, artist))
                val print = FloatArray(SoundPrint.DIMS) { i ->
                    val signal = if (printsSeparate && (i % 2 == 0) == (style == "חסידי")) 1.5f else 0f
                    2f + signal + r.nextFloat()
                }
                features[id] = feature(id, print, r)
                id++
            }
        }
        return Triple(songs, features, styles)
    }

    @Test fun aPrintThatSeparatesStylesIsSeenToDoSo() {
        val (songs, features, styles) = library(printsSeparate = true)
        val r = SoundCheck.measure(songs, features, styles)!!
        assertEquals(48, r.songs)
        assertTrue("print ${r.print} should be near perfect", r.print > 0.95)
        assertTrue("the measured sound is identical, so it should be near chance", r.current < r.chance + 0.25)
    }

    @Test fun aPrintThatIsNoiseScoresAboutChance() {
        val (songs, features, styles) = library(printsSeparate = false)
        val r = SoundCheck.measure(songs, features, styles)!!
        assertEquals(r.chance, r.print, 0.2)
    }

    @Test fun itNeedsPrintsAndLabelsToSayAnything() {
        val (songs, features, styles) = library(printsSeparate = true)
        // These artists are not in the built-in catalogue, so without the
        // user's tags nothing is labelled.
        assertNull("no labels", SoundCheck.measure(songs, features, emptyMap()))
        val unprinted = features.mapValues { it.value.copy(soundPrint = "") }
        assertNull("no prints", SoundCheck.measure(songs, unprinted, styles))
    }
}

class SoundCheckLonelyTest {
    private fun song(id: Long, artist: String) = SongEntity(
        id, "שיר $id", "שיר $id", artist, Names.normalizeKey(artist), "al$id", id,
        240_000, 1, 2020, null, "/m/$id", "/m/$artist", 0, 1
    )

    @Test fun aStyleWithOneArtistIsLeftOutRatherThanScoredZero() {
        // The first run on a real library: 2%, 2% and 1% against chance, because
        // most of its styles belonged to one artist each and so had no right
        // answer among other artists' songs.
        val r = kotlin.random.Random(4)
        val songs = ArrayList<SongEntity>()
        val features = HashMap<Long, AudioFeatureEntity>()
        val styles = HashMap<String, String>()
        var id = 1L
        val cast = listOf("א" to "חסידי", "ב" to "חסידי", "ג" to "מזרחי", "ד" to "מזרחי", "ה" to "חזנות")
        for ((artist, style) in cast) {
            styles[Names.normalizeKey(artist)] = style
            repeat(10) {
                songs.add(song(id, artist))
                val print = FloatArray(SoundPrint.DIMS) { i ->
                    2f + (if (i % 3 == listOf("חסידי", "מזרחי", "חזנות").indexOf(style)) 1.5f else 0f) + r.nextFloat()
                }
                features[id] = AudioFeatureEntity(
                    songId = id, analyzedAt = 1L, bpm = 100f, bpmConfidence = 0.9f, musicalKey = 0,
                    mode = 1, energy = 0.5f, brightness = 0.4f, flatness = 0.1f, dynamics = 0.8f,
                    onsetRate = 1f, chroma = "1,0,0,0,0,0,0,0,0,0,0,0",
                    timbre = "0,0,0,0,0,0,0,0,0,0,0,0", timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0",
                    soundPrint = SoundPrint.pack(print)
                )
                id++
            }
        }
        val result = SoundCheck.measure(songs, features, styles)!!
        assertEquals("the one-artist style is set aside", 10, result.lonely)
        assertEquals(40, result.songs)
        // 10 same-style songs among 40 by other artists: exactly a quarter.
        assertEquals(0.25, result.chance, 1e-9)
        assertTrue("a print that separates them is seen to", result.print > 0.9)
    }
}

class CatalogueInTheEngineTest {
    private fun song(id: Long, artist: String) = SongEntity(
        id, "שיר $id", "שיר $id", artist, Names.normalizeKey(artist), "al$id", id,
        240_000, 1, 2020, null, "/m/$id", "/m/$artist", 0, 1
    )

    @Test fun whatWasTypedWinsAndTheCatalogueFillsTheRest() {
        val songs = listOf(song(1, "ישי ריבו"), song(2, "אברהם פריד"), song(3, "פלוני"))
        val stored = mapOf(
            Names.normalizeKey("אברהם פריד") to com.elchanan.rhythm.data.db.ArtistEntity(
                Names.normalizeKey("אברהם פריד"), "אברהם פריד", rating = 4, styles = "ליטאי"
            )
        )
        val merged = ArtistStyles.withCatalogue(stored, songs)
        assertEquals("ישראלי", merged[Names.normalizeKey("ישי ריבו")]?.styles)
        assertEquals("the user's own tag is not overridden", "ליטאי", merged[Names.normalizeKey("אברהם פריד")]?.styles)
        assertNull("an artist the catalogue does not know stays untagged", merged[Names.normalizeKey("פלוני")])
    }

    @Test fun theCatalogueSpeaksTheUsersWordsSoTheDefaultRuleApplies() {
        // The default rule is "חסידי, ישראלי". The catalogue used to say
        // "פופ ישראלי", which the rule never matched.
        val rule = Styles.Separations.parse(Styles.DEFAULT_SEPARATIONS)
        assertTrue(rule.clash(listOf(ArtistStyles.HASIDIC), listOf(ArtistStyles.ISRAELI_POP)))
        assertTrue(ArtistStyles.ISRAELI_POP in Styles.SUGGESTED)
    }
}
