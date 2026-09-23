package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.data.AnalysisTransfer
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes a complete transfer beside a temporary file, then replaces it. */
internal object AnalysisExport {
    fun write(
        destination: File,
        songs: List<SongEntity>,
        features: Map<Long, AudioFeatureEntity>
    ): Int {
        val available = songs.count { it.id in features }
        require(available > 0) { "nothing analysed" }
        destination.parentFile?.mkdirs()
        val target = if (destination.extension.equals(AnalysisTransfer.EXTENSION, true)) {
            destination
        } else {
            File(destination.parentFile, destination.name + "." + AnalysisTransfer.EXTENSION)
        }
        val capabilities = buildSet {
            add(AnalysisTransfer.CAP_ACOUSTIC)
            if (features.values.any { it.tags.isNotBlank() }) {
                add(AnalysisTransfer.CAP_SEMANTIC_TAGS)
            }
            if (features.values.any { it.soundPrint.length > 1 }) add(AnalysisTransfer.CAP_SOUND_PRINT)
            if (features.values.any { it.musicPrint.length > 1 }) add(AnalysisTransfer.CAP_MUSIC_MODEL)
        }
        val content = AnalysisTransfer.encode(songs, features, capabilities = capabilities)
        // Parse what will be written before it can replace a user's previous
        // export. This also checks its checksum and the record count.
        require(AnalysisTransfer.decode(content).tracks.size == available)

        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.outputStream().buffered().use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
        }
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return available
    }
}
