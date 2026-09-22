package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * The mood reading learning from the user's corrections.
 *
 * The rules read a mood off tempo, onsets, key and brightness - and for a lot
 * of music they are simply wrong: a fast, busy niggun that everyone hears as
 * calm. The user says so on a few songs; the rest of that kind should follow.
 */
class MoodTeachingTest {

    /** A print that leans towards one region of the embedding. */
    private fun print(region: Int, r: Random): String {
        val v = FloatArray(SoundPrint.DIMS) { r.nextFloat() * 0.3f }
        for (i in region * 100 until region * 100 + 100) v[i] = v[i] + 3f + r.nextFloat()
        return SoundPrint.pack(v)
    }

    /** Fast and busy by every measure the rules use - "calm" is never their answer. */
    private fun busy(id: Long, region: Int, r: Random) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L, bpm = 140f + r.nextFloat() * 8f, bpmConfidence = 0.9f,
        musicalKey = 0, mode = 1, energy = 0.7f + r.nextFloat() * 0.1f,
        brightness = 0.6f + r.nextFloat() * 0.1f, flatness = 0.1f, dynamics = 1f,
        onsetRate = 5f + r.nextFloat(), chroma = "1,0,0,0,0,0,0,0,0,0,0,0",
        timbre = "0,0,0,0,0,0,0,0,0,0,0,0", timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0",
        soundPrint = print(region, r)
    )

    private val random = Random(4)
    // region 1: the kind the listener hears as calm; region 7: genuinely driving
    private val features = (1L..40L).map { busy(it, if (it <= 20) 1 else 7, random) }
    private val byId = features.associateBy { it.songId }

    @Test fun whatTheUserSaidAlwaysWins() {
        val marks = mapOf(25L to mapOf(Mood.CALM to true), 3L to mapOf(Mood.ENERGETIC to false))
        val model = MoodModel(features, marks)
        assertTrue(model.matches(Mood.CALM, byId[25L]))
        assertFalse(model.matches(Mood.ENERGETIC, byId[3L]))
    }

    @Test fun afewCorrectionsTeachTheRestOfThatSound() {
        val plain = MoodModel(features)
        assertTrue(
            "the fixture should fool the rules",
            (1L..20L).none { plain.matches(Mood.CALM, byId[it]) }
        )
        val marks = HashMap<Long, Map<Mood, Boolean>>()
        for (id in 1L..5L) marks[id] = mapOf(Mood.CALM to true)
        for (id in 21L..23L) marks[id] = mapOf(Mood.CALM to false)
        marks[24L] = mapOf(Mood.ENERGETIC to true) // an opposite counts as "not calm"
        val taught = MoodModel(features, marks)
        val calmNow = (6L..20L).count { taught.matches(Mood.CALM, byId[it]) }
        val drivingCalm = (25L..40L).count { taught.matches(Mood.CALM, byId[it]) }
        assertTrue("only $calmNow of 15 unmarked songs of that sound became calm", calmNow >= 13)
        assertEquals("driving songs must not become calm", 0, drivingCalm)

        val report = taught.report().first { it.mood == Mood.CALM }
        assertEquals(5, report.yes)
        assertEquals(4, report.no)
        assertTrue(report.usingLearned)
        assertTrue(report.learnedAccuracy!! > report.ruleAccuracy)
        // the strongest calm songs are now that sound
        val strongest = Mood.strongest(
            (1L..40L).map { song(it) }, byId, Mood.CALM, marks
        )
        assertTrue(strongest.isNotEmpty() && strongest.all { it.id <= 20 })
    }

    @Test fun tooFewCorrectionsLeaveTheRulesInCharge() {
        val marks = mapOf(1L to mapOf(Mood.CALM to true), 21L to mapOf(Mood.CALM to false))
        val model = MoodModel(features, marks)
        assertFalse(model.matches(Mood.CALM, byId[6L]))
        assertFalse(model.report().first { it.mood == Mood.CALM }.usingLearned)
    }

    @Test fun marksRoundTrip() {
        val raw = MoodMarks.with(MoodMarks.with("", Mood.CALM, true), Mood.ENERGETIC, false)
        assertEquals(mapOf(Mood.CALM to true, Mood.ENERGETIC to false), MoodMarks.parse(raw))
        assertEquals(mapOf(Mood.ENERGETIC to false), MoodMarks.parse(MoodMarks.with(raw, Mood.CALM, null)))
        assertEquals(emptyMap<Mood, Boolean>(), MoodMarks.parse("NONSENSE,,"))
    }

    private fun song(id: Long) = com.elchanan.rhythm.data.db.SongEntity(
        id, "שיר $id", "שיר $id", "אמן", "אמן", "al", 1, 240_000L, 1, 2020, null,
        "/m/$id", "/m", 1L, 1
    )
}
