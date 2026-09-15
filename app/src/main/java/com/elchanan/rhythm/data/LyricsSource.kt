package com.elchanan.rhythm.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.elchanan.rhythm.data.db.LyricsEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.playback.MediaItems
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale

/** One timed line of an LRC file. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Finds lyrics without any network access, in this order:
 *
 *  1. an `.lrc` file sitting next to the audio file (timed)
 *  2. a `.txt` file sitting next to the audio file (plain)
 *  3. lyrics embedded in the file's own tags - ID3v2 USLT / SYLT for mp3,
 *     Vorbis comments for flac and ogg
 *
 * Steps 1 and 2 need a folder the user granted through the system picker,
 * because a `.lrc` is not a media file and READ_MEDIA_AUDIO does not cover it.
 * Step 3 needs nothing extra: it reads the audio file we already have access to.
 */
object LyricsSource {

    private const val HEADER_BYTES = 3 * 1024 * 1024

    /** filename without extension -> document uri, cached per tree */
    private var indexedTree: String? = null
    private var index: Map<String, Uri> = emptyMap()

    // -----------------------------------------------------------------------
    // public entry point
    // -----------------------------------------------------------------------

    fun find(context: Context, song: SongEntity, treeUri: Uri?): LyricsEntity? {
        sidecar(context, song, treeUri)?.let { return it }
        embedded(context, song)?.let { return it }
        return null
    }

    // -----------------------------------------------------------------------
    // sidecar files
    // -----------------------------------------------------------------------

    private fun buildIndex(context: Context, treeUri: Uri) {
        if (indexedTree == treeUri.toString()) return
        val map = HashMap<String, Uri>()
        runCatching {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching
            walk(root, map, 0)
        }
        index = map
        indexedTree = treeUri.toString()
    }

    private fun walk(dir: DocumentFile, out: HashMap<String, Uri>, depth: Int) {
        if (depth > 6) return
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                walk(child, out, depth + 1)
            } else {
                val name = child.name ?: continue
                val lower = name.lowercase(Locale.ROOT)
                if (lower.endsWith(".lrc") || lower.endsWith(".txt")) {
                    out[lower] = child.uri
                }
            }
        }
    }

    private fun sidecar(context: Context, song: SongEntity, treeUri: Uri?): LyricsEntity? {
        if (treeUri == null) return null
        buildIndex(context, treeUri)
        if (index.isEmpty()) return null

        val base = File(song.path).nameWithoutExtension.lowercase(Locale.ROOT)
        val alternative = "${song.artistName} - ${song.title}".lowercase(Locale.ROOT)

        val candidates = listOf("$base.lrc", "$alternative.lrc", "$base.txt", "$alternative.txt")
        for (candidate in candidates) {
            val uri = index[candidate] ?: continue
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.use { readText(it) }
            }.getOrNull().orEmpty()
            if (content.isBlank()) continue
            val isLrc = candidate.endsWith(".lrc") && content.contains('[')
            return LyricsEntity(
                songId = song.id,
                text = if (isLrc) stripTimestamps(content) else content.trim(),
                synced = if (isLrc) content else "",
                source = "file",
                updatedAt = System.currentTimeMillis()
            )
        }
        return null
    }

    private fun readText(stream: InputStream): String {
        val bytes = stream.readBytes()
        // most .lrc files are UTF-8; fall back to the platform default on failure
        return runCatching { String(bytes, Charsets.UTF_8) }
            .getOrElse { String(bytes, Charset.forName("windows-1255")) }
    }

    // -----------------------------------------------------------------------
    // embedded tags
    // -----------------------------------------------------------------------

    private fun embedded(context: Context, song: SongEntity): LyricsEntity? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(MediaItems.songUri(song.id))?.use { input ->
                val buffer = ByteArray(HEADER_BYTES)
                var read = 0
                while (read < HEADER_BYTES) {
                    val n = input.read(buffer, read, HEADER_BYTES - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        if (bytes.size < 16) return null

        val found = parseId3(bytes) ?: parseVorbis(bytes) ?: return null
        val (plain, lrc) = found
        if (plain.isBlank() && lrc.isBlank()) return null
        return LyricsEntity(
            songId = song.id,
            text = if (plain.isNotBlank()) plain.trim() else stripTimestamps(lrc),
            synced = lrc,
            source = "embedded",
            updatedAt = System.currentTimeMillis()
        )
    }

    /** Returns plain text to synced LRC, either of which may be empty. */
    private fun parseId3(data: ByteArray): Pair<String, String>? {
        if (data.size < 10) return null
        if (data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) {
            return null
        }
        val major = data[3].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val tagSize = syncSafe(data, 6)
        var pos = 10
        val end = minOf(data.size, 10 + tagSize)

        if (flags and 0x40 != 0 && pos + 4 <= end) {
            val extendedSize = if (major >= 4) syncSafe(data, pos) else beInt(data, pos)
            pos += extendedSize.coerceAtLeast(4)
        }

        val idLength = if (major == 2) 3 else 4
        val sizeLength = if (major == 2) 3 else 4
        val flagLength = if (major == 2) 0 else 2

        var plain = ""
        var lrc = ""

        while (pos + idLength + sizeLength + flagLength <= end) {
            val id = String(data, pos, idLength, Charsets.ISO_8859_1)
            if (id.isBlank() || id[0] == '\u0000') break
            val size = when {
                major >= 4 -> syncSafe(data, pos + idLength)
                major == 2 -> ((data[pos + 3].toInt() and 0xFF) shl 16) or
                    ((data[pos + 4].toInt() and 0xFF) shl 8) or
                    (data[pos + 5].toInt() and 0xFF)
                else -> beInt(data, pos + idLength)
            }
            val bodyStart = pos + idLength + sizeLength + flagLength
            if (size <= 0 || bodyStart + size > end) break

            when (id) {
                "USLT", "ULT" -> if (plain.isEmpty()) {
                    plain = readUslt(data, bodyStart, size)
                }
                "SYLT", "SLT" -> if (lrc.isEmpty()) {
                    lrc = readSylt(data, bodyStart, size)
                }
            }
            pos = bodyStart + size
        }
        return if (plain.isBlank() && lrc.isBlank()) null else plain to lrc
    }

    private fun readUslt(data: ByteArray, start: Int, size: Int): String {
        if (size < 5) return ""
        val encoding = data[start].toInt() and 0xFF
        var pos = start + 4 // encoding byte + 3 language bytes
        val end = start + size
        // skip the null terminated content descriptor
        pos = skipTerminator(data, pos, end, encoding)
        if (pos >= end) return ""
        return decode(data, pos, end - pos, encoding).trim()
    }

    private fun readSylt(data: ByteArray, start: Int, size: Int): String {
        if (size < 7) return ""
        val encoding = data[start].toInt() and 0xFF
        val timeFormat = data[start + 4].toInt() and 0xFF // 2 == milliseconds
        var pos = start + 6
        val end = start + size
        pos = skipTerminator(data, pos, end, encoding)

        val lines = ArrayList<LyricLine>()
        while (pos < end) {
            val textEnd = findTerminator(data, pos, end, encoding)
            if (textEnd < 0) break
            val text = decode(data, pos, textEnd - pos, encoding)
            var next = textEnd + terminatorSize(encoding)
            if (next + 4 > end) break
            val stamp = beInt(data, next).toLong()
            next += 4
            if (timeFormat == 2) lines.add(LyricLine(stamp, text.trim()))
            pos = next
        }
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n") { "${formatStamp(it.timeMs)}${it.text}" }
    }

    /** Vorbis comments inside a flac or ogg header. */
    private fun parseVorbis(data: ByteArray): Pair<String, String>? {
        val text = String(data, 0, minOf(data.size, 512 * 1024), Charsets.ISO_8859_1)
        val markers = listOf("LYRICS=", "UNSYNCEDLYRICS=", "unsyncedlyrics=", "lyrics=")
        for (marker in markers) {
            val idx = text.indexOf(marker)
            if (idx < 0) continue
            val from = idx + marker.length
            // vorbis fields are length prefixed, but scanning to the next control
            // byte is enough to lift readable text out of the header
            val builder = StringBuilder()
            var i = from
            while (i < text.length && builder.length < 20_000) {
                val c = text[i]
                if (c.code < 9 || (c.code in 11..12) || (c.code in 14..31)) break
                builder.append(c)
                i++
            }
            val raw = builder.toString()
            val decoded = runCatching {
                String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            }.getOrDefault(raw)
            if (decoded.isNotBlank()) {
                val isLrc = decoded.contains('[') && Regex("\\[\\d{1,2}:\\d{2}").containsMatchIn(decoded)
                return if (isLrc) "" to decoded else decoded to ""
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // byte helpers
    // -----------------------------------------------------------------------

    private fun syncSafe(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    private fun beInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun terminatorSize(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun findTerminator(data: ByteArray, from: Int, end: Int, encoding: Int): Int {
        val step = terminatorSize(encoding)
        var i = from
        while (i + step <= end) {
            if (step == 1) {
                if (data[i].toInt() == 0) return i
            } else {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
            }
            i += step
        }
        return -1
    }

    private fun skipTerminator(data: ByteArray, from: Int, end: Int, encoding: Int): Int {
        val idx = findTerminator(data, from, end, encoding)
        return if (idx < 0) end else idx + terminatorSize(encoding)
    }

    private fun decode(data: ByteArray, offset: Int, length: Int, encoding: Int): String {
        if (length <= 0 || offset + length > data.size) return ""
        return when (encoding) {
            1 -> String(data, offset, length, Charsets.UTF_16)
            2 -> String(data, offset, length, Charsets.UTF_16BE)
            3 -> String(data, offset, length, Charsets.UTF_8)
            else -> String(data, offset, length, Charsets.ISO_8859_1)
        }.trimEnd('\u0000')
    }

    // -----------------------------------------------------------------------
    // LRC handling
    // -----------------------------------------------------------------------

    private val TIMESTAMP = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parseLrc(content: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in content.lines()) {
            val matches = TIMESTAMP.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val text = raw.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val minutes = m.groupValues[1].toLongOrNull() ?: continue
                val seconds = m.groupValues[2].toLongOrNull() ?: continue
                val fraction = m.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> (fraction.toLongOrNull() ?: 0L) * 100
                    2 -> (fraction.toLongOrNull() ?: 0L) * 10
                    else -> fraction.take(3).toLongOrNull() ?: 0L
                }
                out.add(LyricLine(minutes * 60_000 + seconds * 1000 + millis, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    fun stripTimestamps(content: String): String = content.lines()
        .map { TIMESTAMP.replace(it, "").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    fun formatStamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val hundredths = (safe % 1000) / 10
        return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
    }

    fun buildLrc(lines: List<LyricLine>): String =
        lines.sortedBy { it.timeMs }.joinToString("\n") { "${formatStamp(it.timeMs)}${it.text}" }
}
