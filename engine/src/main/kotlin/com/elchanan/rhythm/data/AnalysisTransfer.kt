package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.MusicPrint
import com.elchanan.rhythm.engine.SoundPrint
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * A versioned, checked interchange format for measurements made on a computer.
 *
 * A desktop song id is a hash of its Windows path while an Android song id is
 * assigned by MediaStore. Neither id is allowed onto the wire as identity.
 * Records carry the facts that survive the move instead, and [match] accepts
 * only a unique destination. A missed song costs one later analysis; a wrong
 * match quietly poisons every recommendation made from it, so ambiguity is a
 * refusal rather than a best guess.
 */
object AnalysisTransfer {
    const val EXTENSION = "rhythm-analysis"
    const val MIME = "application/vnd.rhythm.analysis"
    /**
     * 2 carries the sound print, the music print and the mood readings as
     * well, now that the desktop runs the models. 1 is still read: a file
     * without them imports its measurements and leaves those to the phone.
     */
    const val FORMAT_VERSION = 2
    const val ANALYZER_VERSION = 1
    const val CAP_ACOUSTIC = "acoustic"
    const val CAP_SEMANTIC_TAGS = "semantic-tags"
    const val CAP_SOUND_PRINT = "sound-print"
    const val CAP_MUSIC_MODEL = "music-model"

    private const val MAGIC = "RHYTHM_ANALYSIS"
    private const val MAX_TRACKS = 250_000
    private val whitespace = Regex("\\s+")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    data class Track(
        val fileName: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val title: String,
        val artist: String,
        val album: String,
        val trackNumber: Int,
        val feature: AudioFeatureEntity
    )

    data class Bundle(
        val createdAt: Long,
        val analyzerVersion: Int,
        val capabilities: Set<String>,
        val tracks: List<Track>
    )

    data class MatchResult(
        val features: List<AudioFeatureEntity>,
        val unmatched: Int,
        val ambiguous: Int,
        val invalid: Int
    )

    fun encode(
        songs: List<SongEntity>,
        features: Map<Long, AudioFeatureEntity>,
        createdAt: Long = System.currentTimeMillis(),
        analyzerVersion: Int = ANALYZER_VERSION,
        capabilities: Set<String> = setOf(CAP_ACOUSTIC)
    ): String {
        val body = buildString {
            append(MAGIC).append('\t').append(FORMAT_VERSION)
                .append('\t').append(analyzerVersion)
                .append('\t').append(createdAt)
                .append('\t').append(capabilities.sorted().joinToString(","))
                .append('\n')
            for (song in songs) {
                val f = features[song.id] ?: continue
                if (!valid(f)) continue
                append("T")
                field(encodeText(fileName(song.path)))
                field(song.sizeBytes)
                field(song.durationMs)
                field(encodeText(song.title))
                field(encodeText(song.artistName))
                field(encodeText(song.albumName))
                field(song.trackNumber)
                field(f.analyzedAt)
                field(f.bpm)
                field(f.bpmConfidence)
                field(f.musicalKey)
                field(f.mode)
                field(f.energy)
                field(f.brightness)
                field(f.flatness)
                field(f.dynamics)
                field(f.onsetRate)
                field(encodeText(f.chroma))
                field(encodeText(f.timbre))
                field(encodeText(f.timbreVar))
                field(encodeText(f.shape))
                field(f.scaleMode)
                field(f.scaleConfidence)
                field(encodeText(f.chroma24))
                field(encodeText(f.tags))
                field(encodeText(f.soundPrint))
                field(encodeText(f.musicPrint))
                field(encodeText(f.musicMoods))
                append('\n')
            }
        }
        return body + "SHA256\t" + sha256(body) + "\n"
    }

    /**
     * The file was written by a newer Rhythm than the one reading it. Its own
     * kind, so the app can say "update" rather than "damaged": a phone a few
     * versions behind the computer is the usual case, and the file is fine.
     */
    class NewerVersionException(what: String) : IllegalArgumentException("written by a newer version: $what")

    fun decode(content: String): Bundle {
        val normalized = content.replace("\r\n", "\n")
        val footerStart = normalized.lastIndexOf("SHA256\t")
        require(footerStart > 0) { "missing checksum" }
        val body = normalized.substring(0, footerStart)
        val supplied = normalized.substring(footerStart + 7).trim()
        require(supplied.length == 64 && supplied.equals(sha256(body), ignoreCase = true)) {
            "checksum mismatch"
        }

        val lines = body.lineSequence().filter { it.isNotEmpty() }.iterator()
        require(lines.hasNext()) { "empty transfer" }
        val header = lines.next().split('\t')
        require(header.size == 5 && header[0] == MAGIC) { "not a Rhythm analysis file" }
        val format = header[1].toIntOrNull()
        require(format != null && format >= 1) { "unsupported format" }
        if (format > FORMAT_VERSION) throw NewerVersionException("format $format")
        val fields = if (format >= 2) 29 else 26
        val analyzer = header[2].toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("invalid analyzer version")
        if (analyzer > ANALYZER_VERSION) throw NewerVersionException("analyzer $analyzer")
        val created = header[3].toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("invalid creation time")
        val capabilities = header[4].split(',').filter { it.isNotBlank() }.toSet()
        require(CAP_ACOUSTIC in capabilities) { "missing acoustic measurements" }

        val tracks = ArrayList<Track>()
        while (lines.hasNext()) {
            require(tracks.size < MAX_TRACKS) { "too many tracks" }
            val p = lines.next().split('\t')
            require(p.size == fields && p[0] == "T") { "invalid track row" }
            val f = AudioFeatureEntity(
                songId = 0L,
                analyzedAt = long(p, 8),
                bpm = float(p, 9),
                bpmConfidence = float(p, 10),
                musicalKey = int(p, 11),
                mode = int(p, 12),
                energy = float(p, 13),
                brightness = float(p, 14),
                flatness = float(p, 15),
                dynamics = float(p, 16),
                onsetRate = float(p, 17),
                chroma = decodeText(p[18]),
                timbre = decodeText(p[19]),
                timbreVar = decodeText(p[20]),
                shape = decodeText(p[21]),
                scaleMode = int(p, 22),
                scaleConfidence = float(p, 23),
                chroma24 = decodeText(p[24]),
                tags = decodeText(p[25]),
                soundPrint = if (fields > 26) printOrEmpty(decodeText(p[26]), SoundPrint::unpack) else "",
                musicPrint = if (fields > 26) printOrEmpty(decodeText(p[27]), MusicPrint::unpack) else "",
                musicMoods = if (fields > 26) decodeText(p[28]) else ""
            ).let { if (it.musicPrint.isEmpty()) it.copy(musicMoods = "") else it }
            require(valid(f)) { "invalid measurements" }
            tracks += Track(
                fileName = decodeText(p[1]),
                sizeBytes = long(p, 2),
                durationMs = long(p, 3),
                title = decodeText(p[4]),
                artist = decodeText(p[5]),
                album = decodeText(p[6]),
                trackNumber = int(p, 7),
                feature = f
            )
        }
        return Bundle(created, analyzer, capabilities, tracks)
    }

    fun match(
        bundle: Bundle,
        songs: List<SongEntity>,
        existing: Map<Long, AudioFeatureEntity> = emptyMap()
    ): MatchResult {
        val byFile = songs.groupBy { fileKey(fileName(it.path), it.sizeBytes) }
        val byMetadata = songs.groupBy { metadataKey(it.title, it.artistName) }
        val claimed = HashSet<Long>()
        val out = ArrayList<AudioFeatureEntity>()
        var unmatched = 0
        var ambiguous = 0
        var invalid = 0

        for (track in bundle.tracks) {
            if (!valid(track.feature) || track.durationMs < 0 || track.sizeBytes < 0) {
                invalid++
                continue
            }
            var candidates = byFile[fileKey(track.fileName, track.sizeBytes)].orEmpty()
                .filter { durationCompatible(track.durationMs, it.durationMs) }
            if (candidates.size != 1) {
                candidates = byMetadata[metadataKey(track.title, track.artist)].orEmpty()
                    .filter { durationCompatible(track.durationMs, it.durationMs) }
                if (candidates.size > 1 && track.album.isNotBlank()) {
                    val album = candidates.filter { key(it.albumName) == key(track.album) }
                    if (album.isNotEmpty()) candidates = album
                }
                if (candidates.size > 1 && track.trackNumber > 0) {
                    val numbered = candidates.filter { it.trackNumber == track.trackNumber }
                    if (numbered.isNotEmpty()) candidates = numbered
                }
                if (candidates.size > 1 && track.fileName.isNotBlank()) {
                    val named = candidates.filter {
                        key(fileName(it.path)) == key(track.fileName)
                    }
                    if (named.isNotEmpty()) candidates = named
                }
            }

            if (candidates.isEmpty()) {
                unmatched++
                continue
            }
            if (candidates.size != 1 || candidates[0].id in claimed) {
                ambiguous++
                continue
            }
            val song = candidates[0]
            claimed += song.id
            val old = existing[song.id]
            // A computer without a model must never erase what the phone's
            // model already made. The moods go with the music print they were
            // read from, never one song's print with another's moods.
            val keepMusic = track.feature.musicPrint.isEmpty() && old != null
            out += track.feature.copy(
                songId = song.id,
                tags = track.feature.tags.ifBlank { old?.tags.orEmpty() },
                soundPrint = track.feature.soundPrint.ifEmpty { old?.soundPrint.orEmpty() },
                musicPrint = if (keepMusic) old!!.musicPrint else track.feature.musicPrint,
                musicMoods = if (keepMusic) old!!.musicMoods else track.feature.musicMoods
            )
        }
        return MatchResult(out, unmatched, ambiguous, invalid)
    }

    private fun durationCompatible(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return false
        return abs(a - b) <= max(2_000L, max(a, b) / 100L)
    }

    private fun fileKey(name: String, size: Long): String = key(name) + "\u0000" + size
    private fun metadataKey(title: String, artist: String): String = key(title) + "\u0000" + key(artist)

    private fun key(value: String): String = punctuation.replace(
        value.trim().lowercase(Locale.ROOT), " "
    ).replace(whitespace, " ").trim()

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun valid(f: AudioFeatureEntity): Boolean =
        f.analyzedAt >= 0 && f.bpm.isFinite() && f.bpmConfidence.isFinite() &&
            f.energy.isFinite() && f.brightness.isFinite() && f.flatness.isFinite() &&
            f.dynamics.isFinite() && f.onsetRate.isFinite() && f.scaleConfidence.isFinite()

    /**
     * A print as stored, if it reads back as one; [SoundPrint.TRIED] as it
     * is; anything else dropped to empty rather than the row refused - the
     * measurements are still good, and an empty print is simply made again.
     */
    private fun printOrEmpty(stored: String, unpack: (String) -> FloatArray?): String = when {
        stored.isEmpty() || stored == SoundPrint.TRIED -> stored
        unpack(stored) != null -> stored
        else -> ""
    }

    private fun StringBuilder.field(value: Any) { append('\t').append(value) }
    private fun encodeText(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return buildString((bytes.size * 4 + 2) / 3) {
            var at = 0
            while (at < bytes.size) {
                val a = bytes[at++].toInt() and 0xff
                val hasB = at < bytes.size
                val b = if (hasB) bytes[at++].toInt() and 0xff else 0
                val hasC = at < bytes.size
                val c = if (hasC) bytes[at++].toInt() and 0xff else 0
                append(BASE64[a ushr 2])
                append(BASE64[((a and 3) shl 4) or (b ushr 4)])
                if (hasB) append(BASE64[((b and 15) shl 2) or (c ushr 6)])
                if (hasC) append(BASE64[c and 63])
            }
        }
    }

    private fun decodeText(value: String): String {
        require(value.length % 4 != 1) { "invalid text" }
        val out = ByteArray(value.length * 3 / 4 + 2)
        var used = 0
        var at = 0
        while (at < value.length) {
            val a = BASE64.indexOf(value[at++]).also { require(it >= 0) { "invalid text" } }
            val b = BASE64.indexOf(value[at++]).also { require(it >= 0) { "invalid text" } }
            out[used++] = ((a shl 2) or (b ushr 4)).toByte()
            if (at < value.length) {
                val c = BASE64.indexOf(value[at++]).also { require(it >= 0) { "invalid text" } }
                out[used++] = (((b and 15) shl 4) or (c ushr 2)).toByte()
                if (at < value.length) {
                    val d = BASE64.indexOf(value[at++]).also { require(it >= 0) { "invalid text" } }
                    out[used++] = (((c and 3) shl 6) or d).toByte()
                }
            }
        }
        return out.copyOf(used).toString(Charsets.UTF_8)
    }
    private fun int(parts: List<String>, at: Int): Int =
        parts[at].toIntOrNull() ?: throw IllegalArgumentException("invalid integer")
    private fun long(parts: List<String>, at: Int): Long =
        parts[at].toLongOrNull() ?: throw IllegalArgumentException("invalid long")
    private fun float(parts: List<String>, at: Int): Float =
        parts[at].toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("invalid number")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
