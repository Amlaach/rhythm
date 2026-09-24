package com.elchanan.rhythm.data

import kotlin.math.abs
import kotlin.math.ln

/**
 * Finds the cover inside a picture that was padded out to a video's shape.
 *
 * Songs taken from video sites often carry the video's thumbnail as their
 * cover: a square sleeve in the middle of a 16:9 frame, with flat bars of one
 * colour down both sides. Shown whole, the bars read as a frame that has
 * nothing to do with the album; shown cropped to a square, they are what is
 * left of it. This finds the bars so they can be cut away and only the
 * sleeve is shown.
 *
 * Careful, because a real cover can have flat colour at its edges too - a
 * small figure on a plain background. So it only acts on the signature of
 * padding and nothing else:
 *  - the picture is not square to begin with (real sleeves nearly always are);
 *  - the bars are along its long sides, on both of them, and about equally
 *    wide (padding is centred; a composition rarely is);
 *  - each bar is one flat colour, top to bottom;
 *  - what is left is closer to square than what there was.
 * Anything else is left exactly as it is.
 *
 * Pixels are ARGB ints, row by row, the layout both Android and the desktop
 * hand them out in.
 */
object ArtTrim {

    /** How far from the bar's own colour a pixel may be and still count as bar: JPEG is never quite flat. */
    private const val TOLERANCE = 30

    /** The share of a line's pixels that must match for the line to be bar. */
    private const val MATCHING = 0.94

    /** Thinner than this and it is an edge or a border line, not padding. */
    private const val MIN_BAR = 0.04

    /** How unequal the two bars may be, as a share of the length they are measured along. */
    private const val SYMMETRY = 0.04

    /**
     * The part of the picture to keep, as `[left, top, right, bottom]` with
     * right and bottom exclusive, or null when it should be shown as it is.
     */
    fun crop(pixels: IntArray, width: Int, height: Int): IntArray? {
        if (width < 16 || height < 16 || pixels.size < width * height) return null
        val aspect = width.toDouble() / height
        if (aspect in 0.9..1.11) return null
        return if (width > height) {
            val left = bar(width, { i, s -> pixels[s * width + i] }, height, fromEnd = false)
            val right = bar(width, { i, s -> pixels[s * width + i] }, height, fromEnd = true)
            if (!padding(left, right, width)) return null
            val kept = width - left - right
            if (!squarer(kept.toDouble() / height, aspect)) return null
            intArrayOf(left, 0, width - right, height)
        } else {
            val top = bar(height, { i, s -> pixels[i * width + s] }, width, fromEnd = false)
            val bottom = bar(height, { i, s -> pixels[i * width + s] }, width, fromEnd = true)
            if (!padding(top, bottom, height)) return null
            val kept = height - top - bottom
            if (!squarer(width.toDouble() / kept, aspect)) return null
            intArrayOf(0, top, width, height - bottom)
        }
    }

    /**
     * How many lines from one edge are flat bar. [at] gives the pixel at
     * position s along line i. Measured against the colour of the outermost
     * line, and the edge of the sleeve is trimmed by one more line, so a
     * softened border between bar and picture does not stay behind.
     */
    private fun bar(lines: Int, at: (Int, Int) -> Int, length: Int, fromEnd: Boolean): Int {
        val step = maxOf(1, length / 96)
        val edge = if (fromEnd) lines - 1 else 0
        val reference = average(edge, at, length, step)
        var count = 0
        while (count < lines / 2) {
            val line = if (fromEnd) lines - 1 - count else count
            if (!flat(line, at, length, step, reference)) break
            count++
        }
        return if (count == 0) 0 else minOf(count + 1, lines / 2)
    }

    private fun flat(line: Int, at: (Int, Int) -> Int, length: Int, step: Int, reference: Int): Boolean {
        var samples = 0
        var matching = 0
        var s = 0
        while (s < length) {
            samples++
            if (close(at(line, s), reference)) matching++
            s += step
        }
        return matching >= samples * MATCHING
    }

    private fun average(line: Int, at: (Int, Int) -> Int, length: Int, step: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        var s = 0
        while (s < length) {
            val p = at(line, s)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            s += step
        }
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun close(a: Int, b: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= TOLERANCE &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= TOLERANCE &&
            abs((a and 0xFF) - (b and 0xFF)) <= TOLERANCE

    private fun padding(a: Int, b: Int, length: Int): Boolean =
        a >= length * MIN_BAR && b >= length * MIN_BAR && abs(a - b) <= length * SYMMETRY + 2

    private fun squarer(after: Double, before: Double): Boolean =
        after in 0.7..1.43 && abs(ln(after)) < abs(ln(before)) - 0.05
}
