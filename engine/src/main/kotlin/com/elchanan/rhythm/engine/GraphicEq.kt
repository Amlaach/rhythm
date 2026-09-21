package com.elchanan.rhythm.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * A thirty one band graphic equaliser, computed here rather than asked for.
 *
 * Android does ship an equaliser, and the app still offers it, but it is not
 * this. `android.media.audiofx.Equalizer` reports whatever the device decided
 * to implement, which on almost every phone is five bands - one slider for
 * everything between 1 kHz and 8 kHz. That is enough to make music duller or
 * brighter and not enough to do anything specific, and no amount of interface
 * can invent resolution the effect does not have.
 *
 * So the filtering happens in the player's own audio path: thirty one peaking
 * biquads per channel at the ISO third octave centres, which is what a studio
 * graphic equaliser is. The cost is real but small - about sixty filters over
 * a stereo stream, a few hundred thousand multiply-adds a second, low enough
 * that it does not show up against decoding the file in the first place.
 *
 * Everything in this file is arithmetic with no Android in it, which is why
 * it sits in :engine: the phone runs these filters inside an ExoPlayer audio
 * processor and the desktop runs them inside its own playback loop, over the
 * same coefficients from the same solver. Thirty one bands that behaved
 * differently on the two builds would be thirty one bands nobody could
 * describe.
 */
object EqBands {

    /**
     * The ISO 266 third octave centres. Thirty one of them covers 20 Hz to
     * 20 kHz, which is the whole of hearing and the reason hardware graphic
     * equalisers have settled on this exact set for fifty years.
     */
    val FREQUENCIES = intArrayOf(
        20, 25, 32, 40, 50, 63, 80, 100, 125, 160,
        200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600,
        2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000,
        20000
    )

    val COUNT = FREQUENCIES.size

    /** Gain limits in millibels, matching what the sliders offer. */
    const val MIN_MB = -1200
    const val MAX_MB = 1200

    /** Pre-amplifier limits in millibels. */
    const val PREAMP_MIN_MB = -1200
    const val PREAMP_MAX_MB = 600

    /**
     * Width of each filter, in octaves.
     *
     * A third of an octave, matching the spacing, so neighbouring filters
     * cross near their half power points and a row of equal gains adds up to
     * a line rather than a row of bumps.
     *
     * Stated as a bandwidth rather than as a Q on purpose. The two are the
     * same thing at low frequencies - a third of an octave is Q 4.318 - but
     * they come apart badly at the top, because the transform that turns an
     * analogue filter into a digital one squeezes the frequency axis as it
     * approaches half the sample rate. Designing the top bands at a fixed Q
     * leaves them far narrower than the gaps between them: measured over a
     * sweep, 14 kHz and 18 kHz came out 3 to 5 dB below the bands either side
     * of them. Asking for a bandwidth instead carries the correction, and the
     * same sweep is flat to 1.3 dB.
     */
    const val BANDWIDTH_OCTAVES = 1f / 3f

    /**
     * How close to half the sample rate a band may sit before it is dropped.
     *
     * At 44.1 kHz the 20 kHz band is at 0.45 of the rate and still worth
     * building; past about 0.48 the filter is so wide it stops being a band
     * at all, so it is left out rather than allowed to act as a shelf.
     */
    const val NYQUIST_LIMIT = 0.48f

    /** "1.2k" rather than "1250": the label has to fit under a slider. */
    fun label(hz: Int): String = when {
        hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000}k"
        hz >= 1000 -> {
            val whole = hz / 1000
            val tenth = (hz % 1000) / 100
            if (tenth == 0) "${whole}k" else "$whole.${tenth}k"
        }
        else -> hz.toString()
    }
}

/**
 * One peaking filter, as five coefficients and two words of state per channel.
 *
 * Transposed direct form II. Of the four textbook arrangements it is the one
 * that keeps its state small and well scaled at 32 bit, which matters here
 * because the low bands sit at 20 Hz against a 48 kHz sample rate - a pole
 * that close to the unit circle will grind any less careful arrangement into
 * noise.
 */
class Biquad {

    var b0 = 1f
        private set
    var b1 = 0f
        private set
    var b2 = 0f
        private set
    var a1 = 0f
        private set
    var a2 = 0f
        private set

    /** True once [peaking] has been given a frequency it can actually filter. */
    var usable = false
        private set

    /**
     * Designs a peaking filter, from the Audio EQ Cookbook.
     *
     * @param freq centre frequency in Hz.
     * @param sampleRate of the stream being filtered.
     * @param gainDb boost or cut at the centre.
     * @param bandwidthOctaves width between the half gain points.
     */
    fun peaking(
        freq: Float,
        sampleRate: Int,
        gainDb: Float,
        bandwidthOctaves: Float = EqBands.BANDWIDTH_OCTAVES
    ) {
        if (freq <= 0f || freq >= sampleRate * EqBands.NYQUIST_LIMIT) {
            usable = false
            identity()
            return
        }
        usable = true
        if (abs(gainDb) < 0.01f) {
            identity()
            return
        }

        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = (2.0 * PI * freq / sampleRate).toFloat()
        val cosW = cos(w0)
        val sinW = sin(w0)
        // The cookbook's bandwidth form. The w0/sin(w0) inside the sinh is the
        // whole correction: it is 1 at low frequencies and grows towards the
        // top, widening the filter by exactly as much as the transform had
        // narrowed it.
        val alpha = sinW * sinh(LN2 / 2f * bandwidthOctaves * w0 / sinW)

        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cosW) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cosW) / a0
        a2 = (1f - alpha / a) / a0
    }

    private fun identity() {
        b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
    }

    /** Gain of this filter at [freq], in decibels. */
    fun magnitudeDb(freq: Float, sampleRate: Int): Float =
        magnitudeDb(Probe(freq, sampleRate))

    /**
     * Gain at a frequency whose sines and cosines have already been worked out.
     *
     * Both the solver and the drawing evaluate all thirty one filters at the
     * same set of frequencies, and the four trigonometric calls per point are
     * most of the cost. Hoisting them out of the inner loop turns a few
     * thousand calls per frame into a couple of hundred, which is the
     * difference between a curve that follows a finger and one that lags it.
     */
    fun magnitudeDb(probe: Probe): Float {
        val numRe = b0 + b1 * probe.cos1 + b2 * probe.cos2
        val numIm = -(b1 * probe.sin1 + b2 * probe.sin2)
        val denRe = 1.0 + a1 * probe.cos1 + a2 * probe.cos2
        val denIm = -(a1 * probe.sin1 + a2 * probe.sin2)
        val den = denRe * denRe + denIm * denIm
        val num = numRe * numRe + numIm * numIm
        if (den <= 0.0 || num <= 0.0) return 0f
        // Power rather than amplitude, so the square root cancels into the
        // ten. This runs ten thousand times per frame while a slider is being
        // dragged, and a square root each time is worth not taking.
        return (10.0 * log10(num / den)).toFloat()
    }

    /** One frequency, with the trigonometry the magnitude formula needs. */
    class Probe(freq: Float, sampleRate: Int) {
        private val w = 2.0 * PI * freq / sampleRate
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2 * w)
        val sin2 = sin(2 * w)
    }

    private companion object {
        const val LN2 = 0.6931472f
    }
}

/**
 * Works out what to ask each filter for, so that the user gets what the
 * sliders say.
 *
 * Thirty one filters in series do not act independently. Each one's skirt
 * reaches a third of an octave either side, which is exactly where its
 * neighbours are, so their gains add: setting every slider to +6 dB and
 * building the filters directly produces +9.9 dB, not +6. Measured across
 * several shapes, the error between what the sliders said and what came out
 * ran from 2 to 6 dB - enough that the numbers printed next to the sliders
 * would simply be wrong.
 *
 * The fix is to treat the slider positions as the wanted result rather than
 * as filter settings, and solve for the filter gains that produce it. There
 * is a closed form - invert the interaction matrix - but it needs care to
 * stay conditioned, and it is not necessary: the interaction is strongly
 * diagonal, so nudging each filter by its own error converges in a handful of
 * passes. Measured: 1 pass leaves 1.0 dB, 2 leaves 0.23, 4 leaves 0.01.
 */
object EqSolver {

    private const val ROUNDS = 5
    private const val DAMPING = 0.9f

    /**
     * A filter is allowed to work harder than the slider asks, but not without
     * limit: a deliberately awful setting - every other band hard up, the rest
     * hard down - is not fully reachable, and without a ceiling the solver
     * would chase it into gains that are loud, pointless and slow to settle.
     */
    private const val MAX_FILTER_DB = 20f

    /**
     * @param targetMb the wanted gain at each band centre, in millibels.
     * @return the gain to design each filter with, in decibels.
     */
    fun solve(targetMb: List<Int>, sampleRate: Int): FloatArray {
        val target = FloatArray(EqBands.COUNT) { (targetMb.getOrNull(it) ?: 0) / 100f }
        val gains = FloatArray(EqBands.COUNT)
        val usable = BooleanArray(EqBands.COUNT) {
            EqBands.FREQUENCIES[it] < sampleRate * EqBands.NYQUIST_LIMIT
        }
        for (i in gains.indices) if (usable[i]) gains[i] = target[i]

        val scratch = Array(EqBands.COUNT) { Biquad() }
        val probes = Array(EqBands.COUNT) {
            Biquad.Probe(EqBands.FREQUENCIES[it].toFloat(), sampleRate)
        }
        repeat(ROUNDS) {
            for (band in gains.indices) {
                scratch[band].peaking(
                    EqBands.FREQUENCIES[band].toFloat(), sampleRate, gains[band]
                )
            }
            for (band in gains.indices) {
                if (!usable[band]) continue
                var actual = 0f
                for (f in scratch) actual += f.magnitudeDb(probes[band])
                gains[band] = (gains[band] + DAMPING * (target[band] - actual))
                    .coerceIn(-MAX_FILTER_DB, MAX_FILTER_DB)
            }
        }
        return gains
    }
}

/**
 * What the user has asked the equaliser to do, as a value.
 *
 * Immutable so the audio thread can read it without a lock: the settings
 * screen builds a whole new one and swaps it in, and the worst a reader can
 * see is the previous set of gains for one more buffer.
 */
data class EqSettings(
    val enabled: Boolean,
    /** Gain per band in millibels, [EqBands.COUNT] long. */
    val bands: List<Int>,
    /** Overall gain applied before the filters, in millibels. */
    val preampMb: Int
) {

    fun withBand(index: Int, millibels: Int): EqSettings {
        if (index !in bands.indices) return this
        val next = bands.toMutableList()
        next[index] = millibels.coerceIn(EqBands.MIN_MB, EqBands.MAX_MB)
        return copy(bands = next)
    }

    /** True when every slider sits at zero, i.e. the filters would do nothing. */
    val isFlat: Boolean get() = bands.all { it == 0 } && preampMb == 0

    companion object {
        val FLAT = EqSettings(false, List(EqBands.COUNT) { 0 }, 0)

        fun of(enabled: Boolean, bands: List<Int>, preampMb: Int): EqSettings {
            val fixed = (0 until EqBands.COUNT).map {
                (bands.getOrNull(it) ?: 0).coerceIn(EqBands.MIN_MB, EqBands.MAX_MB)
            }
            return EqSettings(enabled, fixed, preampMb.coerceIn(EqBands.PREAMP_MIN_MB, EqBands.PREAMP_MAX_MB))
        }
    }
}

/**
 * The filter bank: coefficients for one set of settings, and the running state
 * for however many channels are being played.
 *
 * Separate from the audio processor so the whole of the arithmetic can be run
 * and measured without an Android audio session, and so the settings screen
 * can draw the exact curve the audio is getting rather than an artist's
 * impression of it.
 */
class EqFilters {

    private var sampleRate = 0
    private var channels = 0

    private val filters = Array(EqBands.COUNT) { Biquad() }

    /** [channel][band * 2], the two state words of each transposed biquad. */
    private var state: Array<FloatArray> = emptyArray()

    /** Which bands are doing anything, so the inner loop can skip the rest. */
    private var live = IntArray(0)

    private var preamp = 1f
    private var bypass = true

    fun configure(sampleRate: Int, channels: Int, settings: EqSettings) {
        val shapeChanged = sampleRate != this.sampleRate || channels != this.channels
        this.sampleRate = sampleRate
        this.channels = channels
        if (shapeChanged) {
            state = Array(channels) { FloatArray(EqBands.COUNT * 2) }
        }
        update(settings)
    }

    /**
     * Recomputes the coefficients without touching the running state.
     *
     * Keeping the state is what makes moving a slider silent. Each filter's
     * memory is its last two samples of input and output, and those are still
     * true after the shape changes; throwing them away instead puts a step in
     * the signal, which is audible as a click on every touch event.
     */
    fun update(settings: EqSettings) {
        if (sampleRate <= 0) return
        preamp = if (settings.enabled) dbToLinear(settings.preampMb / 100f) else 1f
        val gains = if (settings.enabled) {
            EqSolver.solve(settings.bands, sampleRate)
        } else {
            FloatArray(EqBands.COUNT)
        }
        val active = ArrayList<Int>(EqBands.COUNT)
        for (band in 0 until EqBands.COUNT) {
            filters[band].peaking(EqBands.FREQUENCIES[band].toFloat(), sampleRate, gains[band])
            if (filters[band].usable && abs(gains[band]) >= 0.01f) active.add(band)
        }
        live = active.toIntArray()
        bypass = !settings.enabled || (live.isEmpty() && settings.preampMb == 0)
    }

    /** True when the filters would leave the signal exactly as it arrived. */
    val isBypassing: Boolean get() = bypass

    fun reset() {
        for (channel in state) channel.fill(0f)
    }

    /**
     * Filters one interleaved frame in place.
     *
     * @param frame one sample per channel, overwritten with the result.
     */
    fun processFrame(frame: FloatArray) {
        if (bypass) return
        for (channel in 0 until channels) {
            var x = frame[channel] * preamp
            val s = state[channel]
            for (band in live) {
                val f = filters[band]
                val i = band * 2
                val y = f.b0 * x + s[i]
                s[i] = f.b1 * x - f.a1 * y + s[i + 1]
                s[i + 1] = f.b2 * x - f.a2 * y
                x = y
            }
            frame[channel] = softClip(x)
        }
    }

    companion object {

        fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

        /**
         * Keeps a boosted signal inside full scale.
         *
         * Anything above about +3 dB on a modern master runs out of headroom,
         * and integer audio does not run out gracefully - it wraps, which is
         * not distortion but a burst of noise. Below three quarters of full
         * scale this is exactly the identity, and above it the curve bends the
         * remaining quarter asymptotically towards 1. It is continuous in its
         * first derivative at the knee, so a signal crossing the threshold
         * does not pick up an edge on the way through.
         */
        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            val over = a - KNEE
            val room = 1f - KNEE
            val y = KNEE + room * (over / (over + room))
            return if (x < 0f) -y else y
        }

        private const val KNEE = 0.75f
    }
}

/**
 * The response the current settings actually produce, for drawing.
 *
 * Computed from the coefficients rather than from the slider positions,
 * because they are not the same curve: neighbouring filters overlap, so three
 * adjacent bands at +6 dB give closer to +9 dB in the middle. A picture drawn
 * from the slider positions would quietly disagree with the sound.
 */
object EqResponse {

    /** Nominal rate for the drawing. Real playback is 44.1 or 48 kHz. */
    const val DRAW_RATE = 48000

    /**
     * @param points how many frequencies to evaluate, spread evenly on a log
     *   scale between 20 Hz and 20 kHz.
     * @return gain in decibels at each point.
     */
    fun curve(settings: EqSettings, points: Int = 160): FloatArray {
        val gains = if (settings.enabled) {
            EqSolver.solve(settings.bands, DRAW_RATE)
        } else {
            FloatArray(EqBands.COUNT)
        }
        val filters = Array(EqBands.COUNT) { band ->
            Biquad().apply {
                peaking(EqBands.FREQUENCIES[band].toFloat(), DRAW_RATE, gains[band])
            }
        }
        val preampDb = if (settings.enabled) settings.preampMb / 100f else 0f
        return FloatArray(points) { i ->
            val probe = Biquad.Probe(frequencyAt(i.toFloat() / (points - 1)), DRAW_RATE)
            var db = preampDb
            for (f in filters) db += f.magnitudeDb(probe)
            db
        }
    }

    /** Position 0..1 across the drawing to a frequency, logarithmically. */
    fun frequencyAt(fraction: Float): Float =
        (20.0 * (1000.0).pow(fraction.toDouble())).toFloat()

    /** The inverse, for placing a band's label under the right part of the curve. */
    fun fractionOf(freq: Float): Float =
        (log10(freq / 20.0) / 3.0).toFloat().coerceIn(0f, 1f)
}

/**
 * Named starting points.
 *
 * Deliberately gentle. A preset's job is to be somewhere reasonable to start
 * adjusting from, and most equaliser presets in the wild are demonstrations of
 * how much the equaliser can do - +12 dB of bass that sounds impressive for
 * about a minute and tiring after that. Nothing here exceeds 6 dB.
 *
 * The values are in millibels at the thirty one centres, written as a short
 * list of (frequency, gain) anchors and interpolated across the rest, which is
 * how they stay readable and stay smooth.
 */
object EqPresets {

    data class Preset(val name: String, val anchors: List<Pair<Int, Int>>)

    val ALL: List<Preset> = listOf(
        Preset("שטוח", emptyList()),
        Preset("בס", listOf(20 to 550, 63 to 500, 125 to 320, 250 to 120, 500 to 0, 20000 to 0)),
        Preset("בס עמוק", listOf(20 to 600, 50 to 600, 100 to 400, 200 to 150, 400 to 0, 3150 to 0, 10000 to 150, 20000 to 200)),
        Preset("קול ברור", listOf(20 to -300, 100 to -150, 250 to 0, 1000 to 250, 2500 to 400, 4000 to 300, 8000 to 100, 20000 to 0)),
        Preset("חזנות", listOf(20 to -200, 80 to -100, 200 to 100, 500 to 200, 1250 to 300, 3150 to 250, 6300 to 100, 20000 to 0)),
        Preset("כלי נגינה", listOf(20 to 0, 125 to 150, 400 to -100, 1000 to 0, 3150 to 200, 8000 to 300, 16000 to 250, 20000 to 200)),
        Preset("חתונה", listOf(20 to 400, 80 to 350, 250 to 100, 800 to -50, 2000 to 150, 5000 to 250, 12500 to 300, 20000 to 250)),
        Preset("רוק", listOf(20 to 300, 100 to 250, 400 to -150, 1000 to 0, 3150 to 250, 8000 to 300, 20000 to 200)),
        Preset("פופ", listOf(20 to 100, 100 to 200, 400 to 100, 1000 to -50, 2500 to 150, 6300 to 250, 20000 to 150)),
        Preset("ג'אז", listOf(20 to 250, 125 to 200, 500 to -100, 2000 to 100, 6300 to 200, 16000 to 250, 20000 to 200)),
        Preset("קלאסי", listOf(20 to 200, 80 to 150, 500 to 0, 2000 to 0, 8000 to 150, 20000 to 250)),
        Preset("אוזניות", listOf(20 to 350, 63 to 250, 200 to 0, 1000 to 0, 3150 to 150, 6300 to -100, 10000 to 200, 20000 to 250)),
        Preset("רמקול טלפון", listOf(20 to -600, 100 to -400, 315 to 0, 800 to 250, 2000 to 350, 5000 to 200, 10000 to -200, 20000 to -400)),
        Preset("לילה", listOf(20 to -400, 100 to -250, 500 to 100, 1600 to 200, 4000 to 100, 10000 to -150, 20000 to -300))
    )

    /**
     * Expands a preset's anchors into a gain for every band.
     *
     * Interpolated on a log frequency axis, because that is the axis hearing
     * uses: halfway between 100 Hz and 1 kHz is 316 Hz, not 550.
     */
    fun bands(preset: Preset): List<Int> {
        if (preset.anchors.isEmpty()) return List(EqBands.COUNT) { 0 }
        val sorted = preset.anchors.sortedBy { it.first }
        return EqBands.FREQUENCIES.map { hz ->
            val f = hz.toFloat()
            val before = sorted.lastOrNull { it.first <= hz }
            val after = sorted.firstOrNull { it.first >= hz }
            when {
                before == null -> sorted.first().second
                after == null -> sorted.last().second
                before.first == after.first -> before.second
                else -> {
                    val span = log10(after.first.toFloat() / before.first)
                    val into = log10(f / before.first)
                    val t = if (span <= 0f) 0f else (into / span).coerceIn(0f, 1f)
                    (before.second + (after.second - before.second) * t).toInt()
                }
            }
        }
    }

    /** Which preset the given gains correspond to, or null once they are edited. */
    fun matching(bands: List<Int>): String? =
        ALL.firstOrNull { preset ->
            val theirs = bands(preset)
            theirs.indices.all { abs(theirs[it] - (bands.getOrNull(it) ?: 0)) <= 10 }
        }?.name
}
