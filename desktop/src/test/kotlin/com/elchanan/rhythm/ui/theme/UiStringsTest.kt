package com.elchanan.rhythm.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** The English interface translates the interface, and never the music's own names. */
class UiStringsTest {

    @Test fun interfaceCopyIsTranslated() {
        assertEquals("Share", UiStrings.translate("שיתוף", "en"))
        assertEquals("שיתוף", UiStrings.translate("שיתוף", "he"))
    }

    @Test fun theLibrarysOwnNamesAreLeftAsTheyAre() {
        // "בית" is the Home tab, and also a song called בית.
        assertEquals("Home", UiStrings.translate("בית", "en"))
        UiStrings.protectNames(listOf("בית", "ישי ריבו", "אבי"))
        try {
            assertEquals("בית", UiStrings.translate("בית", "en"))
            assertEquals("ישי ריבו", UiStrings.translate("ישי ריבו", "en"))
            assertEquals("אבי", UiStrings.translate("אבי", "en"))
        } finally {
            UiStrings.protectNames(emptyList())
        }
    }

    @Test fun namesAreNotInTheTableAtAll() {
        // Picked up from code as if they were interface copy, once.
        assertEquals("ישי ריבו", UiStrings.translate("ישי ריבו", "en"))
        assertEquals("אבי", UiStrings.translate("אבי", "en"))
    }
}
