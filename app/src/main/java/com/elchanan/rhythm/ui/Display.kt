package com.elchanan.rhythm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the app is drawn, where the answer has to reach every screen at once.
 *
 * Read from the preferences when the activity starts and written by the
 * settings that change it, so a switch takes effect on the spot rather than
 * on the next launch.
 */
object Display {
    /**
     * For small screens: everything drawn a size smaller - text, spacing,
     * artwork and the tab bar together - so the same design fits, rather than
     * a second, cramped one. Off by default.
     */
    var compact by mutableStateOf(false)

    /** The folders as a tab of their own on the bar at the bottom, beside the library. Off by default. */
    var foldersTab by mutableStateOf(false)

    /** How much smaller, for sizes and for text. Tuned so a 5" phone gains about a row and a half per screen. */
    const val COMPACT_SIZE = 0.86f
    const val COMPACT_TEXT = 0.93f
}
