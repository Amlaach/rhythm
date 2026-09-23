package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.TimeZone

/**
 * The recommendations, pinned.
 *
 * Every answer the engine gives - the score and explanation of every song,
 * the whole home feed, the mixes, radio, queue continuation, sequencing,
 * transitions, calibration, the taste report, search - is written out for
 * [EngineFixture]'s library and digested, one digest per kind of answer, and
 * compared with the digests in `src/test/resources/engine-golden.txt`.
 *
 * So a change that was meant to be about something else - memory, speed,
 * tidiness - cannot quietly change what anyone is recommended. If this fails
 * and the change was not meant to touch the recommendations, the change is
 * wrong. If it was meant to, look at what moved (the failing kinds are named,
 * and the full text of the answers is written next to the build), and only
 * then record the new digests:
 *
 *     RHYTHM_GOLDEN_UPDATE=1 ./gradlew :engine:test --tests '*EngineGoldenTest*'
 */
class EngineGoldenTest {

    companion object {
        private var savedZone: TimeZone? = null

        // Time of day, weekday and the seasons are read in the device's zone.
        @BeforeClass @JvmStatic fun fixZone() {
            savedZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }

        @AfterClass @JvmStatic fun restoreZone() {
            savedZone?.let { TimeZone.setDefault(it) }
        }

        private const val GOLDEN = "engine-golden.txt"

        /** The engine exactly as the app builds it: the space from the full rows, the rest lean. */
        fun engine(lib: EngineFixture.Library, now: Long): Recommender = build(lib, now, lean = true)

        /** The engine holding every feature row whole, as it was before [Recommender.leanFeatures]. */
        fun fullEngine(lib: EngineFixture.Library, now: Long): Recommender = build(lib, now, lean = false)

        private fun build(lib: EngineFixture.Library, now: Long, lean: Boolean): Recommender {
            val rows = lib.features.filter { it.energy > 0f }
            return Recommender(
                songs = lib.songs,
                stats = lib.stats,
                artists = ArtistStyles.withCatalogue(lib.artists, lib.songs),
                affinity = lib.affinity,
                transitions = lib.transitions,
                features = if (lean) Recommender.leanFeatures(rows) else rows.associateBy { it.songId },
                acoustic = if (rows.size >= 8) AcousticSpace(rows) else null,
                tuning = EngineFixture.TUNING,
                now = now,
                feedSeed = 7L,
                spoken = lib.spoken,
                lastHeard = lib.lastHeard,
                vocal = lib.vocal
            )
        }

        private fun ids(songs: List<SongEntity>) = songs.joinToString(",") { it.id.toString() }

        private fun d(v: Double) = java.lang.Double.toString(v)

        /**
         * Everything the engine answers, as text, by kind. The order the
         * questions are asked in is fixed, because a few answers remember
         * what came before them.
         */
        fun answers(e: Recommender, lib: EngineFixture.Library): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            val songs = lib.songs
            val byId = songs.associateBy { it.id }

            out["season"] = e.season?.name ?: "none"

            out["versions"] = e.versionTypes.entries.sortedBy { it.key }
                .joinToString("\n") { "${it.key}=${it.value}" }

            out["scores"] = songs.joinToString("\n") { "${it.id} ${d(e.totalScore(it))}" }

            out["explain"] = songs.joinToString("\n") { s ->
                s.id.toString() + " " + e.explain(s).joinToString(" | ") { "${it.label}=${d(it.value)}:${it.detail}" }
            }

            out["styles"] = songs.joinToString("\n") { "${it.id} ${e.stylesInForce(it).joinToString(",")}" }

            out["blind"] = songs.joinToString("\n") { s ->
                "${s.id} ${e.blindSignals(s).joinToString(",") { d(it) }} ${e.verdict(s.id)}"
            }

            out["feed"] = e.buildFeed().joinToString("\n") { section ->
                "${section.id}|${section.title}|${section.subtitle}|${section.kind}|${ids(section.songs)}|" +
                    section.mixes.joinToString(";") { "${it.id}/${it.title}/${it.subtitle}/${ids(it.songs)}" }
            }

            out["pickedMood"] = e.pickedMood.toString()

            out["dailyMixes"] = e.dailyMixes(6).joinToString("\n") { "${it.id}/${it.title}/${it.subtitle}/${ids(it.songs)}" }

            val seeds = songs.filterIndexed { i, _ -> i % 240 == 3 }
            out["radio"] = seeds.joinToString("\n") { "${it.id}: ${ids(e.radio(it, 40))}" }

            val played = lib.stats.values.filter { it.playCount > 0 }.map { it.songId }.sorted()
            out["continuation"] = (0 until 6).joinToString("\n") { k ->
                val recent = played.drop(k * 37).take(5)
                val exclude = played.drop(k * 37 + 5).take(30).toSet()
                "$recent: ${ids(e.continuation(recent, exclude, 20))}"
            }

            out["sequence"] = (0 until 4).joinToString("\n") { k ->
                val list = songs.drop(k * 300).take(40)
                ids(e.sequence(list.first(), list.drop(1)))
            }

            out["transitions"] = (0 until 400).joinToString("\n") { k ->
                val a = songs[(k * 131) % songs.size].id
                val b = songs[(k * 197 + 11) % songs.size].id
                "$a>$b ${d(e.transitionScore(a, b))} ${e.samePiece(a, b)} ${e.sameRecording(a, b)}"
            }

            val rows = e.calibrationRows()
            out["calibrationRows"] = rows.joinToString("\n") { r ->
                "${r.group} ${r.positive} ${r.signals.joinToString(",") { d(it) }}"
            }
            out["calibration"] = SignalCalibration.describe(SignalCalibration.run(rows))

            out["sequenceReport"] = e.evaluateSequence(played.take(300), 60)?.toString() ?: "null"

            out["taste"] = e.tasteReport().toString()

            out["search"] = listOf("שיר", "שיר 12", "אמן 4", "artist", "ווקאלי", "live", "רמיקס", "להקת", "zzz")
                .joinToString("\n") { q -> "$q: ${ids(e.search(q, 60))}" }

            out["lookups"] = listOf(1L, 500L, 1234L).joinToString("\n") { id ->
                "$id ${byId.getValue(id).title}"
            }
            return out
        }

        fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Both moments: an ordinary week, and the Omer, when vocal music takes over. */
        fun allAnswers(build: (EngineFixture.Library, Long) -> Recommender): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((label, now) in listOf("week" to EngineFixture.NOW, "sefira" to EngineFixture.NOW_IN_SEFIRA)) {
                val lib = EngineFixture.build(now = now)
                for ((kind, text) in answers(build(lib, now), lib)) out["$label.$kind"] = text
            }
            return out
        }
    }

    @Test fun theRecommendationsAreExactlyWhatTheyWere() {
        val answers = allAnswers(::engine)
        val digests = answers.mapValues { digest(it.value) }

        // The full answers beside the build, for reading when something moved.
        val dump = File("build/engine-golden")
        dump.mkdirs()
        for ((kind, text) in answers) File(dump, "$kind.txt").writeText(text)

        val rendered = digests.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
        if (System.getenv("RHYTHM_GOLDEN_UPDATE") != null) {
            File("src/test/resources/$GOLDEN").apply { parentFile.mkdirs() }.writeText(rendered)
            return
        }

        val stored = javaClass.classLoader.getResource(GOLDEN)?.readText()
        if (stored == null) fail("No $GOLDEN - record it with RHYTHM_GOLDEN_UPDATE=1")
        val expected = stored!!.lines().filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        val changed = digests.filter { (kind, hash) -> expected[kind] != hash }.keys +
            expected.keys.filter { it !in digests }
        assertEquals(
            "The recommendations changed in: $changed. The answers are in engine/build/engine-golden.",
            emptySet<String>(), changed.toSet()
        )
    }

    /**
     * The lean rows give the very same answers as the whole ones, line by
     * line - not merely the same digests - so a difference says where it is.
     */
    @Test fun leanRowsChangeNothing() {
        val lean = allAnswers(::engine)
        val full = allAnswers(::fullEngine)
        assertEquals(full.keys, lean.keys)
        for ((kind, text) in full) {
            val a = text.lines()
            val b = lean.getValue(kind).lines()
            val at = a.indices.firstOrNull { it >= b.size || a[it] != b[it] }
            if (at != null || a.size != b.size) {
                fail("$kind differs at line ${at ?: b.size}:\n whole: ${a.getOrNull(at ?: b.size)}\n lean:  ${b.getOrNull(at ?: b.size)}")
            }
        }
    }

    /** The fixture has to actually reach what it claims to, or the digests pin nothing. */
    @Test fun theFixtureReachesEveryPartOfTheEngine() {
        val lib = EngineFixture.build()
        val e = engine(lib, EngineFixture.NOW)
        val feed = e.buildFeed()
        assert(feed.size >= 8) { "feed has ${feed.size} sections" }
        assert(feed.any { it.mixes.isNotEmpty() }) { "no mixes" }
        val rows = e.calibrationRows()
        assert(rows.count { it.positive } >= SignalCalibration.MIN_EACH) { "too few loved songs for calibration" }
        assert(rows.count { !it.positive } >= SignalCalibration.MIN_EACH) { "too few rejected songs for calibration" }
        val withMusic = lib.features.count { MusicPrint.unpack(it.musicPrint) != null }
        val soundOnly = lib.features.count { MusicPrint.unpack(it.musicPrint) == null && SoundPrint.unpack(it.soundPrint) != null }
        assert(withMusic > 1000 && soundOnly > 100) { "prints: music $withMusic, sound only $soundOnly" }
        assert(lib.vocal.size >= 15) { "only ${lib.vocal.size} vocal songs" }
        assert(lib.stats.values.count { it.moods.isNotEmpty() } >= 30) { "too few mood corrections" }
        assertEquals("SEFIRA", engine(EngineFixture.build(now = EngineFixture.NOW_IN_SEFIRA), EngineFixture.NOW_IN_SEFIRA).season?.name)
    }
}
