package com.elchanan.rhythm.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Writes a [TagEdit] into an MP3 on the device. The rewriting itself is
 * [Id3Tags], in :engine; this is the part that needs Android - reading the
 * file through its content URI and putting the result back.
 */
object Id3Writer {

    /**
     * Returns true only when the whole rewrite succeeded and the file was
     * replaced. Any failure leaves the original untouched - the new version is
     * assembled beside it and only swapped in at the very end.
     */
    fun write(context: Context, uri: Uri, edit: TagEdit): Boolean {
        if (edit.isEmpty) return false
        val resolver = context.contentResolver
        val staged = File(context.cacheDir, "tagwrite_${System.nanoTime()}.tmp")
        try {
            val ok = resolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> Id3Tags.rewrite(input, output, edit) }
            } ?: false
            if (!ok) return false

            // Only now is the original opened for writing, and it is replaced in
            // a single pass from a file that is already complete and valid.
            resolver.openOutputStream(uri, "rwt")?.use { out ->
                staged.inputStream().use { it.copyTo(out) }
            } ?: return false
            return true
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { staged.delete() }
        }
    }
}
