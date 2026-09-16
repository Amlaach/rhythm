package com.elchanan.rhythm.data

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carries a tag correction all the way into the file, including the part Android
 * makes awkward: asking permission.
 *
 * The app does not own the music, so from Android 11 on it cannot simply open
 * those files for writing. The system grants access per set of files, through a
 * dialog only an activity can show - which is why this exposes the request as
 * something the UI launches rather than trying to write and hoping.
 */
class TagFileWriter(private val context: Context) {

    data class Item(val songId: Long, val title: String, val artist: String)

    data class Outcome(val written: Int, val failed: Int) {
        val ok: Boolean get() = failed == 0
    }

    fun uriFor(songId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)

    /**
     * Whether the old storage permission still has to be asked for.
     *
     * Before Android 11 there is no per file dialog - writing is covered by the
     * same broad permission as reading, and the app only ever asked for the read
     * half of it.
     */
    fun needsLegacyPermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    /**
     * The dialog to show before writing, or null when none is needed - which is
     * the case below Android 11, where the storage permission already covers it.
     */
    fun permissionRequest(items: List<Item>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (items.isEmpty()) return null
        return runCatching {
            MediaStore.createWriteRequest(
                context.contentResolver,
                items.map { uriFor(it.songId) }
            ).intentSender
        }.getOrNull()
    }

    /**
     * Rewrites each file's tag. Runs off the main thread - each song means
     * reading and rewriting several megabytes.
     *
     * Failures are counted rather than thrown: one unwritable file should not
     * stop the rest, and the caller reports the total either way.
     */
    suspend fun write(items: List<Item>): Outcome = withContext(Dispatchers.IO) {
        var written = 0
        var failed = 0
        for (item in items) {
            val uri = uriFor(item.songId)
            val done = try {
                Id3Writer.write(context, uri, item.title, item.artist, null)
            } catch (e: RecoverableSecurityException) {
                false
            } catch (e: SecurityException) {
                false
            }
            if (done) written++ else failed++
        }
        // MediaStore still holds the old title until it re-reads the file.
        if (written > 0) runCatching {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.let {
                context.contentResolver.notifyChange(it, null)
            }
        }
        Outcome(written, failed)
    }
}
