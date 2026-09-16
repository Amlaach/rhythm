package com.elchanan.rhythm.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which queue entries the user put there and which ones the endless
 * radio appended by itself, so the queue screen can show "next up" separately
 * from "autoplay" the way YouTube Music does.
 *
 * Process wide, because the service appends and the UI displays.
 */
object QueueMeta {

    private val _autoAdded = MutableStateFlow<Set<Long>>(emptySet())
    val autoAdded: StateFlow<Set<Long>> = _autoAdded.asStateFlow()

    /**
     * What the queue was started from - an album, a mix, a mood - so the queue
     * screen can say where this came from instead of showing a bare list with
     * no explanation of why these songs are here.
     */
    private val _source = MutableStateFlow<String?>(null)
    val source: StateFlow<String?> = _source.asStateFlow()

    fun setSource(label: String?) {
        _source.value = label?.takeIf { it.isNotBlank() }
    }

    fun markAuto(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        _autoAdded.value = _autoAdded.value + ids
    }

    fun markManual(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        _autoAdded.value = _autoAdded.value - ids.toSet()
    }

    fun reset() {
        _autoAdded.value = emptySet()
        _source.value = null
    }

    fun isAuto(songId: Long): Boolean = songId in _autoAdded.value
}
