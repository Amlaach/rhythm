package com.elchanan.rhythm.engine

import kotlin.math.sqrt

/**
 * YAMNet's own summary of a recording: 1024 numbers, one per channel of its
 * last layer before the classifier, averaged over the song.
 *
 * The model was already producing these for every frame of every song the
 * phone analysed, and they were thrown away - only the 521 class scores built
 * on top of them were kept, and only 25 groups of those reach the learner.
 * The scores answer "is there an accordion in this"; this answers "what does
 * it sound like", in the model's own terms, and is what a comparison between
 * two songs should be made of.
 *
 * Stored at eight bits a channel. The layer is a ReLU6, so every value is in
 * 0..6 and 256 steps across that range lose nothing a similarity would feel.
 */
object SoundPrint {

    const val DIMS = 1024

    /**
     * What is stored when a print was attempted and could not be made - the
     * model would not load on this device, or the file would not decode.
     *
     * Distinct from empty, which means "never tried". The analysis pass fills
     * in every row that is empty, so without this a song whose print fails
     * would be picked up again on every pass, for ever.
     */
    const val TRIED = "-"

    private const val TOP = 6.0f

    fun pack(values: FloatArray): String {
        require(values.size == DIMS) { "a sound print has $DIMS values, not ${values.size}" }
        val bytes = ByteArray(DIMS) { i ->
            val step = (values[i].coerceIn(0f, TOP) / TOP * 255f + 0.5f).toInt()
            step.coerceIn(0, 255).toByte()
        }
        return Base64.encode(bytes)
    }

    /** The print, or null for one that was never made or does not parse. */
    fun unpack(stored: String): FloatArray? {
        if (stored.isEmpty() || stored == TRIED) return null
        val bytes = Base64.decode(stored) ?: return null
        if (bytes.size != DIMS) return null
        return FloatArray(DIMS) { i -> (bytes[i].toInt() and 0xFF) / 255f * TOP }
    }

    /**
     * Every print with the library's average taken off, scaled to length one.
     *
     * Necessary rather than tidy. A ReLU layer is non-negative throughout, so
     * two raw prints always point roughly the same way and every pair of
     * songs looks alike. What distinguishes a song is how it departs from the
     * rest of this library, and that is what is left after the mean goes.
     */
    fun centred(prints: Map<Long, FloatArray>): Map<Long, FloatArray> {
        if (prints.isEmpty()) return emptyMap()
        val mean = DoubleArray(DIMS)
        for (p in prints.values) for (i in 0 until DIMS) mean[i] += p[i].toDouble()
        val count = prints.size.toDouble()
        for (i in 0 until DIMS) mean[i] = mean[i] / count
        return prints.mapValues { (_, p) ->
            val out = FloatArray(DIMS)
            var norm = 0.0
            for (i in 0 until DIMS) {
                val d = p[i] - mean[i]
                out[i] = d.toFloat()
                norm += d * d
            }
            val length = sqrt(norm)
            if (length > 1e-9) for (i in 0 until DIMS) out[i] = (out[i] / length).toFloat()
            out
        }
    }

    /** Cosine of two already centred, unit length prints: -1..1. */
    fun similarity(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in 0 until DIMS) dot += a[i] * b[i]
        return dot
    }

    /**
     * Base 64, by hand.
     *
     * java.util.Base64 is not there before Android 8, and this app runs from
     * Android 5 - so on the oldest phones it supports, the library version
     * would compile, pass every test here, and crash on first use.
     */
    internal object Base64 {
        private const val ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private val INDEX = IntArray(128) { -1 }.also { table ->
            ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
        }

        fun encode(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size + 2) / 3 * 4)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                out.append(ALPHABET[b0 shr 2])
                out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
                out.append(if (i + 1 < bytes.size) ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
                out.append(if (i + 2 < bytes.size) ALPHABET[b2 and 0x3F] else '=')
                i += 3
            }
            return out.toString()
        }

        fun decode(text: String): ByteArray? {
            if (text.length % 4 != 0) return null
            val padding = text.takeLastWhile { it == '=' }.length
            if (padding > 2) return null
            val out = ByteArray(text.length / 4 * 3 - padding)
            var o = 0
            var i = 0
            while (i < text.length) {
                val c = IntArray(4)
                for (j in 0 until 4) {
                    val ch = text[i + j]
                    c[j] = if (ch == '=') 0 else {
                        val v = if (ch.code < 128) INDEX[ch.code] else -1
                        if (v < 0) return null
                        v
                    }
                }
                val triple = (c[0] shl 18) or (c[1] shl 12) or (c[2] shl 6) or c[3]
                if (o < out.size) out[o++] = (triple shr 16).toByte()
                if (o < out.size) out[o++] = (triple shr 8).toByte()
                if (o < out.size) out[o++] = triple.toByte()
                i += 4
            }
            return out
        }
    }
}
