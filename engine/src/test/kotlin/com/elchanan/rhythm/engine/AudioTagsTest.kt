package com.elchanan.rhythm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The read-back side of the tagging model: what a stored row says a track is
 * made of, and what it deliberately leaves out.
 *
 * The indices below are YAMNet's own: 229 is "Middle Eastern music", 204 the
 * accordion, 211 pop, 271 to 274 the four mood classes.
 */
class AudioTagsTest {

    @Test fun storedScoresUseDotsRegardlessOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val scores = FloatArray(521)
            scores[211] = 0.25f
            val stored = AudioTags.compress(scores)
            assertEquals("211:0.2500", stored)
            assertTrue(AudioTags.hints(stored).isNotEmpty())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun aSongTheModelNeverSawHasNoHints() {
        assertTrue(AudioTags.hints("").isEmpty())
        assertTrue(AudioTags.hints("   ").isEmpty())
    }

    @Test fun aStoredClassComesBackAsItsLabel() {
        assertEquals("מזרחי", AudioTags.hints("229:0.42").first())
        assertTrue("אקורדיון" in AudioTags.hints("204:0.30,229:0.20"))
    }

    @Test fun aClassUnderItsOwnThresholdIsNotAHint() {
        // The Middle Eastern group fires from 0.04. Below that the class map
        // has already decided the evidence is not worth reporting.
        assertTrue(AudioTags.hints("229:0.001").isEmpty())
    }

    @Test fun theStrongestEvidenceIsHeardFirstAndTheListIsShort() {
        val stored = "204:0.9,229:0.8,211:0.7,214:0.6,186:0.5,148:0.4,193:0.3"
        assertEquals(listOf("אקורדיון", "מזרחי", "פופ", "רוק", "כינור"), AudioTags.hints(stored))
        assertEquals(2, AudioTags.hints(stored, limit = 2).size)
    }

    /**
     * Moods are left out of the hint line on purpose: [Moods] answers that
     * question from tempo, dynamics and the shape of the library, and a raw
     * "שמח" beside a mood filter that disagrees with it would be worse than no
     * hint at all.
     */
    @Test fun theMoodClassesAreNeverHints() {
        assertTrue(AudioTags.hints("271:0.9,272:0.9,273:0.9,274:0.9").isEmpty())
    }

    /**
     * The hint line is a smaller view, not a different model: the classifier
     * can still ask for every group in [AudioTags.ALL], which is what keeps the
     * training features unchanged by what a screen chooses to show.
     */
    @Test fun theClassifierStillSeesTheClassesTheHintsLeaveOut() {
        val mood = AudioTags.tagsFor(AudioTags.decompress("271:0.9"), listOf(AudioTags.HAPPY))
        assertEquals(listOf("שמח"), mood)
        assertTrue(AudioTags.hints("271:0.9").isEmpty())
    }
}
