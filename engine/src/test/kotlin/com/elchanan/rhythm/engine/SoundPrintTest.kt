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
