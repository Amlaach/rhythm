package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lecture was reported in "קצבי". A fast, loud speaker reads as energetic
 * to every measure the moods use, and a short talk the detector left as
 * music went straight into the list. Talking is not a mood.
 */
class SpeechNotAMoodTest {

    private fun tags(speech: Float, music: Float): String {
        val scores = FloatArray(521)
        scores[AudioTags.SPEECH_INDICES[AudioTags.SLOT_SPEECH]] = speech
        scores[AudioTags.SPEECH_INDICES[AudioTags.SLOT_MUSIC]] = music
        return AudioTags.compress(scores)
    }

    private fun song(id: Long) = SongEntity(
        id = id, title = "t$id", titleLower = "t$id", artistName = "A", artistKey = "a",
        albumName = "", albumId = id, durationMs = 5 * 60_000L, trackNumber = 0, year = 0, genre = null,
        path = "/m/$id.mp3", folder = "/m", dateAddedSec = 0, sizeBytes = 0
    )

    // Everything a mood reads as "energetic": fast, a clear beat, loud, busy.
    private fun energetic(id: Long, tags: String) = AudioFeatureEntity(
        songId = id, analyzedAt = 1, bpm = 150f, bpmConfidence = 0.9f,
        musicalKey = 2, mode = 1, energy = 0.5f, brightness = 0.4f,
        flatness = 0.1f, dynamics = 0.2f, onsetRate = 5f,
        chroma = "1,2,3", timbre = "4,5,6", timbreVar = "7,8,9",
        shape = "0.1,0.2", scaleMode = 3, scaleConfidence = 0.7f,
        chroma24 = "1,2,3,4", tags = tags
    )

    @Test
    fun theModelsHearingDecides() {
        assertTrue(Spoken.speechAhead(energetic(1, tags(speech = 0.8f, music = 0.1f))))
        assertFalse(Spoken.speechAhead(energetic(2, tags(speech = 0.4f, music = 0.9f))))
        // A close call is left to the detector and the listener.
        assertFalse(Spoken.speechAhead(energetic(3, tags(speech = 0.5f, music = 0.45f))))
        // Nothing heard, nothing held back.
        assertFalse(Spoken.speechAhead(energetic(4, "")))
    }

    @Test
    fun aTalkIsNotInAMoodList() {
        val songs = (1L..12L).map { song(it) }
        val features = songs.associate { s ->
            s.id to if (s.id == 1L) energetic(1, tags(speech = 0.85f, music = 0.05f))
            else energetic(s.id, tags(speech = 0.1f, music = 0.8f)).copy(bpm = 80f + s.id * 7f, energy = 0.1f + s.id * 0.03f)
        }
        // The same track heard as music does make a mood list - so it is
        // the hearing that keeps it out, not its sound.
        val asMusic = features + (1L to features.getValue(1L).copy(tags = tags(speech = 0.1f, music = 0.8f)))
        assertTrue(Mood.entries.any { mood -> Mood.strongest(songs, asMusic, mood).any { it.id == 1L } })
        for (mood in Mood.entries) {
            val list = Mood.strongest(songs, features, mood)
            assertFalse("$mood took the talk", list.any { it.id == 1L })
            assertEquals(list.map { it.id }.toSet(), Mood.filter(songs, features, mood).map { it.id }.toSet())
        }
    }
}
