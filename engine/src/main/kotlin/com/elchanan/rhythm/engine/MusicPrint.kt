package com.elchanan.rhythm.engine

import java.util.Locale

/**
 * Discogs-EffNet's summary of a recording: 1280 numbers from the layer MTG
 * put there to be used as an embedding, averaged over the song's patches.
 *
 * Where YAMNet's print describes a sound in terms learned from every kind of
 * noise on YouTube, this one was learned from two million records labelled
 * with 400 styles of music - it is trained on the difference between one kind
 * of music and another, which is exactly the comparison the app makes.
 *
 * The layer is not bounded the way YAMNet's ReLU6 is, so each print carries
 * its own range: "low;high;base64" at eight bits a value.
 */
object MusicPrint {

    const val DIMS = 1280

    /** Attempted and could not be made, as [SoundPrint.TRIED]. */
    const val TRIED = "-"

    fun pack(values: FloatArray): String {
        require(values.size == DIMS) { "a music print has $DIMS values, not ${values.size}" }
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (v in values) {
            if (v < low) low = v
            if (v > high) high = v
        }
        val span = (high - low).takeIf { it > 1e-9f } ?: 1f
        val bytes = ByteArray(DIMS) { i ->
            ((values[i] - low) / span * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return String.format(Locale.ROOT, "%.6g;%.6g;", low, high) + SoundPrint.Base64.encode(bytes)
    }

    fun unpack(stored: String): FloatArray? {
        if (stored.isEmpty() || stored == TRIED) return null
        val parts = stored.split(';', limit = 3)
        if (parts.size != 3) return null
        val low = parts[0].toFloatOrNull() ?: return null
        val high = parts[1].toFloatOrNull() ?: return null
        val bytes = SoundPrint.Base64.decode(parts[2]) ?: return null
        if (bytes.size != DIMS) return null
        val span = high - low
        return FloatArray(DIMS) { i -> low + (bytes[i].toInt() and 0xFF) / 255f * span }
    }
}

/**
 * What MTG's heads say about a song, read off its music print: moods as a
 * probability each, danceability, and arousal and valence on the 1..9 scale
 * people rated them on. Stored as "name=value" pairs.
 */
object MusicMoods {

    fun encode(values: Map<String, Float>): String =
        values.entries.joinToString(",") { (k, v) -> String.format(Locale.ROOT, "%s=%.3f", k, v) }

    fun parse(raw: String): Map<String, Float> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(',').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(i + 1).toFloatOrNull()?.let { part.substring(0, i) to it }
        }.toMap()
    }
}
