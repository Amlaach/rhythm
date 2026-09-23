package com.elchanan.rhythm.engine

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.floor

/**
 * The two stretches of the year when many listeners hear only vocal music:
 * Sefirat HaOmer and the Three Weeks.
 *
 * Everything is counted from the first day of Pesach, and that is enough,
 * because the months from Nisan to Av have the same length in every year -
 * Nisan 30, Iyar 29, Sivan 30, Tammuz 29. So:
 *
 *  - the Omer is counted from 16 Nisan to 5 Sivan: Pesach + 1 .. Pesach + 49,
 *    with Lag BaOmer (Pesach + 33) a day of music again;
 *  - the Three Weeks run from 17 Tammuz to 9 Av: Pesach + 91 .. Pesach + 112,
 *    and one day more when 9 Av is a Shabbat and the fast moves to Sunday.
 *
 * The date of Pesach comes from Gauss's formula, which needs no tables and no
 * java.time - this has to run on Android 5. Civil dates, from midnight: the
 * Hebrew day begins at nightfall, and the few hours either side are not worth
 * a sunset calculation.
 */
object JewishSeasons {

    enum class Season(val label: String) {
        SEFIRA("ספירת העומר"),
        THREE_WEEKS("שלושת השבועות")
    }

    /** The season [millis] falls in, in the device's time zone, or null. */
    fun at(millis: Long, zone: TimeZone = TimeZone.getDefault()): Season? {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        val today = epochDay(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return on(today, c.get(Calendar.YEAR))
    }

    /** The season on an epoch day of the given Gregorian year. */
    fun on(epochDay: Long, year: Int): Season? {
        val offset = epochDay - pesach(year)
        return when {
            offset in 1L..49L && offset != LAG_BAOMER -> Season.SEFIRA
            offset in 91L..112L -> Season.THREE_WEEKS
            offset == 113L && dayOfWeek(pesach(year) + 112) == SATURDAY -> Season.THREE_WEEKS
            else -> null
        }
    }

    private const val LAG_BAOMER = 33L
    private const val SATURDAY = 6

    /**
     * 15 Nisan of the Hebrew year that begins its Pesach in Gregorian [year],
     * as an epoch day. Gauss's formula gives the date in the Julian calendar
     * as a day of March; the Gregorian correction then moves it.
     */
    fun pesach(year: Int): Long {
        val a = (12 * year + 12) % 19
        val b = year % 4
        val q = 20.0955877 + 1.5542418 * a + 0.25 * b - 0.003177794 * year
        var m = floor(q).toInt()
        val fraction = q - m
        val c = (m + 3 * year + 5 * b + 1).mod(7)
        when {
            c == 2 || c == 4 || c == 6 -> m += 1
            c == 1 && a > 6 && fraction >= 0.63287037 -> m += 2
            c == 0 && a > 11 && fraction >= 0.89772376 -> m += 1
        }
        val gregorian = year / 100 - year / 400 - 2
        return epochDay(year, 3, 1) + (m - 1) + gregorian
    }

    /** Days since 1970-01-01 of a Gregorian date (Howard Hinnant's algorithm). */
    fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /**
     * 0 Sunday .. 6 Saturday, as the Hebrew week counts. 1970-01-01 was a
     * Thursday. Kotlin's mod rather than Math.floorMod, which Android only
     * has from 7.0.
     */
    fun dayOfWeek(epochDay: Long): Int = (epochDay + 4).mod(7L).toInt()
}
