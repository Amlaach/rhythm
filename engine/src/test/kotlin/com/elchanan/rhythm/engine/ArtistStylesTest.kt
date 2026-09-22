package com.elchanan.rhythm.engine

import org.junit.Assert.*
import org.junit.Test

class ArtistStylesTest {

    @Test fun theSameSingerIsFoundUnderEverySpelling() {
        // The reason aliases exist. Names.normalizeKey keeps these apart -
        // correctly, since it cannot know they are one person - so a catalogue
        // that matched on it alone would miss almost every real file.
        val spellings = listOf(
            "אהרל'ה סאמעט",       // ASCII apostrophe
            "אהרל׳ה סאמעט",  // Hebrew geresh, which normalizeKey keeps
            "אהרלה סאמט",
            "Aharale Samet"
        )
        for (name in spellings) {
            assertEquals(name, ArtistStyles.HASIDIC, ArtistStyles.styleFor(name))
        }
        // And they really were different keys, or this test proves nothing.
        assertTrue(spellings.map { Names.normalizeKey(it) }.distinct().size > 1)
    }

    @Test fun spacingAndDashesDoNotMatter() {
        for (name in listOf("מרדכי בן דוד", "מרדכי בן־דוד", "מרדכי בןדוד", "MBD")) {
            assertEquals(name, ArtistStyles.HASIDIC, ArtistStyles.styleFor(name))
        }
    }

    @Test fun aCollaborationIsFiledUnderWhoeverIsCreditedFirst() {
        // The same rule the rest of the app uses for an artist field.
        assertEquals(
            ArtistStyles.HASIDIC,
            ArtistStyles.styleFor("מוטי שטיינמץ feat. אברהם פריד")
        )
        assertEquals(ArtistStyles.ISRAELI_POP, ArtistStyles.styleFor("ישי ריבו & חנן בן ארי"))
    }

    @Test fun anyoneNotInTheCatalogueGetsNothing() {
        // The normal case, and it must stay silent rather than guess.
        assertNull(ArtistStyles.styleFor("זמר שלא קיים"))
        assertNull(ArtistStyles.styleFor(""))
        assertNull(ArtistStyles.styleFor("   "))
        assertNull(ArtistStyles.styleFor(Names.UNKNOWN_ARTIST))
    }

    @Test fun noNameIsClaimedByTwoStyles() {
        // A duplicate would make one of the two silently unreachable, and the
        // catalogue is long enough that it would not be noticed by eye.
        val seen = HashMap<String, String>()
        for (seed in ArtistStyles.CATALOGUE) {
            for (name in listOf(seed.name) + seed.aliases) {
                val key = ArtistStyles.matchKey(name)
                val existing = seen.put(key, seed.name)
                // Only a collision between two different artists matters. An
                // alias that collapses onto its own canonical name is merely
                // redundant, and there is no way to see that by eye.
                if (existing != null && existing != seed.name) {
                    fail("$name is claimed by both $existing and ${seed.name}")
                }
            }
        }
    }

    @Test fun everyStyleHasEnoughArtistsToSurviveTheSplit() {
        // Seeds exist to be trained on, and the test splits by artist: a style
        // arriving with one or two artists would fail the moment it was used,
        // which is not a thing to ship.
        val byStyle = ArtistStyles.CATALOGUE.groupBy { it.style }
        assertEquals(4, byStyle.size)
        for ((style, seeds) in byStyle) {
            assertTrue("$style has only ${seeds.size} artists", seeds.size >= 8)
        }
    }

    @Test fun everySeedIsWellFormed() {
        for (seed in ArtistStyles.CATALOGUE) {
            assertTrue(seed.name, seed.name.isNotBlank())
            assertTrue(seed.style, seed.style in ArtistStyles.STYLES)
            assertTrue(seed.name, ArtistStyles.matchKey(seed.name).isNotEmpty())
            assertEquals(seed.name, seed.style, ArtistStyles.styleFor(seed.name))
        }
    }
}
