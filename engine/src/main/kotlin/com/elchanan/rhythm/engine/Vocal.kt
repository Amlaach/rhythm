package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import java.util.Locale

/**
 * Vocal-only music - "ווקאלי", a cappella - made for the Omer and the Three
 * Weeks, when many people hear no instruments.
 *
 * Those recordings are a substitute, not a taste: outside those weeks nobody
 * who owns them wants them in an evening's mix. So they are held back from
 * everything the engine generates except during [JewishSeasons], and during
 * those weeks, if asked, they are all it plays. They stay in the library and
 * in search either way.
 *
 * Decided in this order: what the user said about the song; the word on the
 * file - the title, the album, the folder, the style tags - since releases
 * like this are nearly always called what they are; and last what YAMNet
 * heard, its "A capella" class, which is a guess and needs to be sure.
 */
object Vocal {

    /** Spellings on file names and tags. Lower case; Hebrew has no case. */
    private val WORDS = listOf(
        "ווקאלי", "ווקאל", "ווקלי", "אקפלה", "א-קפלה", "א קפלה", "אקאפלה", "א-קאפלה",
        "acapella", "a capella", "a cappella", "acappella", "a-cappella", "vocal"
    )

    /** YAMNet's "A capella" class. */
    const val IDX_A_CAPELLA = 250

    /** How sure YAMNet has to be before its word alone is taken. */
    const val HEARD_THRESHOLD = 0.2f

    /** Whether the words on a song or its artist's tags say it is vocal-only. */
    fun named(song: SongEntity, artistStyles: String = "", ownStyles: String = ""): Boolean {
        val text = listOf(song.title, song.albumName, song.path, artistStyles, ownStyles)
            .joinToString(" ").lowercase(Locale.ROOT)
        return WORDS.any { it in text }
    }

    fun heard(feature: AudioFeatureEntity?): Boolean {
        val tags = feature?.tags ?: return false
        val score = AudioTags.pick(tags, intArrayOf(IDX_A_CAPELLA))?.get(0) ?: return false
        return score >= HEARD_THRESHOLD
    }

    /**
     * The verdict for one song. [SongStatsEntity.vocal] is the user's word:
     * 1 is vocal, 0 is not, -1 is undecided and left to the name and the sound.
     */
    fun isVocal(
        song: SongEntity,
        stats: SongStatsEntity?,
        feature: AudioFeatureEntity?,
        artistStyles: String = ""
    ): Boolean = when (stats?.vocal ?: -1) {
        1 -> true
        0 -> false
        else -> named(song, artistStyles, stats?.styles.orEmpty()) || heard(feature)
    }
}
