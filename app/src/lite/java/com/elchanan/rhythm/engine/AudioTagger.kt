package com.elchanan.rhythm.engine

import android.content.Context

/**
 * The lite build's stand-in for the tagging model.
 *
 * Same shape as the real one in the full build, and [create] simply never
 * succeeds. Everything downstream already has to cope with a device where the
 * model fails to load - an old processor, a corrupted asset - so the absence of
 * it here needs no special handling anywhere else: callers check for null, and
 * the app carries on measuring tempo, key and energy exactly as it did before
 * any of this existed.
 *
 * Keeping the class rather than guarding every call site with a build flag is
 * deliberate. The alternative spreads knowledge of which build this is across
 * the whole codebase, and that is how two builds quietly become two different
 * apps.
 */
class AudioTagger private constructor() {

    fun scores(waveform16k: FloatArray): FloatArray? = null

    fun close() = Unit

    companion object {
        const val SAMPLE_RATE = 16000

        /** Always null: this build ships without the model. */
        fun create(context: Context): AudioTagger? = null
    }
}
