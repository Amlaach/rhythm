package com.elchanan.rhythm.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared by Android and Windows. Stored independently in each platform's preferences. */
object UiLanguage {
    var code by mutableStateOf("he")
    val english: Boolean get() = code == "en"
}
