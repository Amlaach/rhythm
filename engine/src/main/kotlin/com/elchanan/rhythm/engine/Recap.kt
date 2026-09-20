package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.HistoryEntity
import com.elchanan.rhythm.data.db.SongEntity
import java.util.Calendar

/**
 * What a year of listening looked like.
 *
 * Everything here is counted from the play history and nothing is stored, so
 * the numbers cannot go stale and cannot disagree with the rows they came
 * from. A recap that says "1,204 plays" while the history holds 1,198 is
 * worse than no recap.
 */
data class RecapData(
    val totalMinutes: Long,
    val totalPlays: Int,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val longestStreakDays: Int,
    val currentStreakDays: Int,
    /** Plays per hour of the day, midnight first. Always 24 long. */
    val byHour: List<Int>,
    val topArtists: List<Pair<String, Int>>,
    val topSongs: List<Pair<SongEntity, Int>>,
    val firstPlayAt: Long,
    val busiestDay: String
)

/**
 * The recap, counted once for both builds.
 *
 * It lives here rather than beside either database because it is arithmetic
 * over rows and nothing else - no Room, no JDBC, no Android. The phone and
 * the desktop keep their history in different places and hand the same rows
 * to the same code, which is the only way the two can be relied on to agree.
 */
object Recap {

    private val WEEKDAYS =
        listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")

    private const val DAY_MS = 86_400_000L

    fun build(
        history: List<HistoryEntity>,
        songs: Map<Long, SongEntity>,
        now: Long = System.currentTimeMillis()
    ): RecapData {
        val byHour = IntArray(24)
        val perArtist = HashMap<String, Int>()
        val perSong = HashMap<Long, Int>()
        val days = HashSet<Long>()
        val perWeekday = IntArray(7)
        var totalMs = 0L

        val calendar = Calendar.getInstance()
        for (row in history) {
            totalMs += row.listenedMs
            calendar.timeInMillis = row.playedAt
            byHour[calendar.get(Calendar.HOUR_OF_DAY)]++
            perWeekday[calendar.get(Calendar.DAY_OF_WEEK) - 1]++
            // Local midnight is not what this divides on - it divides on UTC
            // midnight, which is what makes a streak cheap to count. The two
            // differ for anyone listening in the small hours, and a streak is
            // about whether a day was touched at all.
            days.add(row.playedAt / DAY_MS)
            perSong[row.songId] = (perSong[row.songId] ?: 0) + 1
            songs[row.songId]?.let { song ->
                perArtist[song.artistName] = (perArtist[song.artistName] ?: 0) + 1
            }
        }

        // The longest run of consecutive days with at least one play.
        var longest = 0
        var run = 0
        var previous = Long.MIN_VALUE
        for (day in days.sorted()) {
            run = if (day == previous + 1) run + 1 else 1
            if (run > longest) longest = run
            previous = day
        }

        // The run ending today, counted backwards from today. A run that ended
        // yesterday is not a current streak; that is the point of a streak.
        var current = 0
        var cursor = now / DAY_MS
        while (days.contains(cursor)) {
            current++
            cursor--
        }

        val busiest = perWeekday.indices.maxByOrNull { perWeekday[it] } ?: 0

        return RecapData(
            totalMinutes = totalMs / 60_000,
            totalPlays = history.size,
            distinctSongs = perSong.size,
            distinctArtists = perArtist.size,
            longestStreakDays = longest,
            currentStreakDays = current,
            byHour = byHour.toList(),
            topArtists = perArtist.entries.sortedByDescending { it.value }.take(10)
                .map { it.key to it.value },
            topSongs = perSong.entries.sortedByDescending { it.value }.take(15)
                .mapNotNull { entry -> songs[entry.key]?.let { it to entry.value } },
            firstPlayAt = history.minOfOrNull { it.playedAt } ?: 0L,
            busiestDay = WEEKDAYS.getOrElse(busiest) { "" }
        )
    }
}
