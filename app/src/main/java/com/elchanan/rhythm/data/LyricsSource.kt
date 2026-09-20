package com.elchanan.rhythm.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.elchanan.rhythm.data.db.LyricsEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Lyrics
import com.elchanan.rhythm.playback.MediaItems
import java.io.File

/**
 * Finding a song's lyrics on an Android device.
 *
 * A .lrc or .txt beside the audio file first, which needs a folder permission
 * because those are not media files, then the tags inside the audio itself.
 * What is in the bytes once they are open is [Lyrics]' work in :engine,
 * shared with the desktop build.
 */
object LyricsSource {

    private const val HEADER_BYTES = 3 * 1024 * 1024

    /** filename without extension -> document uri, cached per tree */
    private var indexedTree: String? = null

    private var index: Map<String, Uri> = emptyMap()

    fun find(context: Context, song: SongEntity, treeUri: Uri?): LyricsEntity? {
        sidecar(context, song, treeUri)?.let { return it }
        embedded(context, song)?.let { return it }
        return null
    }

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
                context.contentResolver.openInputStream(uri)?.use { Lyrics.readText(it) }
            }.getOrNull().orEmpty()
            if (content.isBlank()) continue
            val isLrc = candidate.endsWith(".lrc") && content.contains('[')
            return LyricsEntity(
                songId = song.id,
                text = if (isLrc) Lyrics.stripTimestamps(content) else content.trim(),
                synced = if (isLrc) content else "",
                source = "file",
                updatedAt = System.currentTimeMillis()
            )
        }
        return null
    }

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

        val found = Lyrics.parseId3(bytes) ?: Lyrics.parseVorbis(bytes) ?: return null
        val (plain, lrc) = found
        if (plain.isBlank() && lrc.isBlank()) return null
        return LyricsEntity(
            songId = song.id,
            text = if (plain.isNotBlank()) plain.trim() else Lyrics.stripTimestamps(lrc),
            synced = lrc,
            source = "embedded",
            updatedAt = System.currentTimeMillis()
        )
    }
}
