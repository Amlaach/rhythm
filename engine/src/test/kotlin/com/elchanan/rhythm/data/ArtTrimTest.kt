package com.elchanan.rhythm.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** A sleeve padded out to a video frame is shown without the padding - and nothing else is touched. */
class ArtTrimTest {

    private val rust = 0xFF5A2A22.toInt()

    /** A busy picture: the kind of pixels a real sleeve has. */
    private fun busy(random: Random) = (0xFF shl 24) or random.nextInt(0xFFFFFF)

    /** A w x h frame, [bar] wide flat bars on the long sides around a busy middle. */
    private fun padded(w: Int, h: Int, barA: Int, barB: Int, colour: Int = rust, noise: Int = 0): IntArray {
        val r = Random(7)
        return IntArray(w * h) { i ->
            val x = i % w
            if (x < barA || x >= w - barB) {
                // JPEG never leaves a flat bar quite flat.
                val n = if (noise == 0) 0 else r.nextInt(-noise, noise + 1)
                val c = (colour and 0xFF) + n
                (colour and 0xFFFFFF00.toInt()) or c.coerceIn(0, 255)
            } else {
                busy(r)
            }
        }
    }

    @Test fun aSquareSleeveInAVideoFrameLosesItsBars() {
        // 320 x 180, a 180 wide sleeve in the middle: 70 px of bar each side.
        val crop = ArtTrim.crop(padded(320, 180, 70, 70, noise = 6), 320, 180)
        assertArrayEquals(intArrayOf(71, 0, 249, 180), crop)
    }

    @Test fun aTallFrameLosesItsTopAndBottom() {
        val w = 180; val h = 320
        val r = Random(3)
        val pixels = IntArray(w * h) { i -> val y = i / w; if (y < 70 || y >= h - 70) rust else busy(r) }
        assertArrayEquals(intArrayOf(0, 71, 180, 249), ArtTrim.crop(pixels, w, h))
    }

    @Test fun realCoversAreLeftAlone() {
        val r = Random(11)
        // square: a real sleeve, whatever its edges look like
        val square = IntArray(200 * 200) { i -> if (i % 200 < 60) rust else busy(r) }
        assertNull(ArtTrim.crop(square, 200, 200))
        // a wide photo with no bars at all
        assertNull(ArtTrim.crop(IntArray(320 * 180) { busy(r) }, 320, 180))
        // flat colour on one side only: composition, not padding
        assertNull(ArtTrim.crop(padded(320, 180, 90, 0), 320, 180))
        // unequal bars: an off-centre subject on a plain wall
        assertNull(ArtTrim.crop(padded(320, 180, 110, 30), 320, 180))
        // bars so thin they are a border line
        assertNull(ArtTrim.crop(padded(320, 180, 4, 4), 320, 180))
    }

    /** A 4:3 thumbnail: [top] rows of near-black above and below [inside]. */
    private fun letterbox(w: Int, h: Int, top: Int, inside: (Int, Int) -> Int): IntArray {
        val r = Random(9)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (y < top || y >= h - top) {
                val v = 12 + r.nextInt(0, 5)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else inside(x, y - top)
        }
    }

    @Test fun aLetterboxedVideoFrameLosesItsBlackBars() {
        // YouTube's hqdefault: 480 x 360, a 480 x 270 frame between two 45 px bars.
        val r = Random(4)
        val pixels = letterbox(480, 360, 45) { _, _ -> busy(r) }
        assertArrayEquals(intArrayOf(0, 46, 480, 314), ArtTrim.crop(pixels, 480, 360))
    }

    @Test fun aSleeveInALetterboxedFrameLosesBoth() {
        // The frame is itself a 270 wide sleeve with 105 px of flat bar each side.
        val r = Random(6)
        val pixels = letterbox(480, 360, 45) { x, _ -> if (x < 105 || x >= 375) rust else busy(r) }
        assertArrayEquals(intArrayOf(106, 46, 374, 314), ArtTrim.crop(pixels, 480, 360))
    }

    @Test fun darkBandsThatAreNotLetterboxingStay() {
        val r = Random(8)
        // Bands of a colour, not black: part of the design.
        val w = 480; val h = 360
        val coloured = IntArray(w * h) { i -> val y = i / w; if (y < 45 || y >= h - 45) rust else busy(r) }
        assertNull(ArtTrim.crop(coloured, w, h))
        // Black, but what is between them is not the shape of a video.
        assertNull(ArtTrim.crop(letterbox(480, 400, 30) { _, _ -> busy(r) }, 480, 400))
        // Black on one side only.
        val one = IntArray(w * h) { i -> if (i / w < 60) 0xFF0C0C0C.toInt() else busy(r) }
        assertNull(ArtTrim.crop(one, w, h))
    }

    @Test fun barsThatAreNotFlatStay() {
        // A blurred copy of the sleeve as the padding: not one colour, so not cut.
        val r = Random(5)
        val w = 320; val h = 180
        val pixels = IntArray(w * h) { i -> busy(r) }
        assertNull(ArtTrim.crop(pixels, w, h))
    }
}
