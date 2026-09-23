package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shiurim told from songs by what the model hears, at any length.
 *
 * Scores are in [AudioTags.SPEECH_INDICES] order: speech, child speech,
 * conversation, narration, singing, music - roughly what YAMNet gives each
 * kind of recording averaged over its probes.
 */
class SpokenDetectionTest {

    private fun track(minutes: Double) = SongEntity(
        id = 1, title = "x", titleLower = "x", artistName = "a", artistKey = "a", albumName = "",
        albumId = 0, durationMs = (minutes * 60_000).toLong(), trackNumber = 0, year = 0, genre = null,
        path = "/x.mp3", folder = "/", dateAddedSec = 0, sizeBytes = 0
    )

    private fun heard(speech: Float, singing: Float, music: Float) =
        floatArrayOf(speech, 0f, 0.05f, 0.05f, singing, music)

    private val shiur = heard(speech = 0.85f, singing = 0.02f, music = 0.05f)
    private val shiurOverNiggun = heard(speech = 0.75f, singing = 0.05f, music = 0.35f)
    private val song = heard(speech = 0.35f, singing = 0.5f, music = 0.85f)
    private val concert = heard(speech = 0.4f, singing = 0.55f, music = 0.8f)

    @Test fun anEightMinuteShiurIsTalking() {
        assertTrue(Spoken.isSpoken(track(8.0), null, shiur))
    }

    @Test fun aShortDvarTorahIsTalkingToo() {
        // Refused outright before, whatever the model heard, for being under eight minutes.
        assertTrue(Spoken.isSpoken(track(4.5), null, shiur))
    }

    @Test fun aShiurWithANiggunUnderneathIsStillTalking() {
        assertTrue(Spoken.isSpoken(track(9.0), null, shiurOverNiggun))
    }

    @Test fun songsOfAnyLengthStayMusic() {
        assertFalse(Spoken.isSpoken(track(3.5), null, song))
        assertFalse(Spoken.isSpoken(track(12.0), null, song))
        assertFalse("a long concert", Spoken.isSpoken(track(55.0), null, concert))
    }

    @Test fun withoutTheModelShortTracksStayMusic() {
        assertFalse(Spoken.isSpoken(track(5.0), null, null))
    }

    @Test fun whatTheListenerSaidWins() {
        assertFalse(Spoken.isSpoken(track(8.0), null, shiur, stored = 0))
        assertTrue(Spoken.isSpoken(track(3.0), null, song, stored = 1))
    }
}
