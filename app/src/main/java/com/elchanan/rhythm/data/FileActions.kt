package com.elchanan.rhythm.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.elchanan.rhythm.data.db.SongEntity

/**
 * Sending a file somewhere else, and removing it from the device.
 *
 * Both are ordinary things for a music player to offer and neither is
 * straightforward on Android, because the rules changed twice. What a file
 * belongs to, and who may delete it, depends on the version the app is
 * running on, so the version checks here are the feature rather than noise
 * around it.
 */
object FileActions {

    fun uriOf(song: SongEntity): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)

    /**
     * An intent for handing the file to another app.
     *
     * The read permission has to travel with the intent. Without the flag the
     * receiving app gets a uri it is not allowed to open, which fails in a way
     * that looks like a broken file rather than a missing permission.
     */
    fun shareIntent(songs: List<SongEntity>): Intent? {
        if (songs.isEmpty()) return null
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        return if (songs.size == 1) {
            val song = songs.first()
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uriOf(song))
                putExtra(Intent.EXTRA_SUBJECT, song.title)
                putExtra(Intent.EXTRA_TITLE, song.title)
                addFlags(flags)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(songs.map { uriOf(it) })
                )
                addFlags(flags)
            }
        }
    }

    /** What happened when a delete was attempted. */
    sealed interface DeleteOutcome {
        /** Gone. [count] files were removed. */
        data class Done(val count: Int) : DeleteOutcome

        /**
         * The system wants to ask the user first, which is right: deleting
         * someone's files is not something an app should be able to do
         * quietly. Launching this shows the platform's own dialog, and the
         * files go only if they say yes.
         */
        data class NeedsConfirmation(val request: IntentSender) : DeleteOutcome

        data class Failed(val reason: String) : DeleteOutcome
    }

    /**
     * Removes files from the device.
     *
     * On Android 11 and up the system always asks, whatever permissions the
     * app holds, and that dialog is the only way. Below that the app's storage
     * permission covers it and the delete goes straight through - so the app
     * has to ask first itself, which the caller does.
     */
    fun delete(context: Context, songs: List<SongEntity>): DeleteOutcome {
        if (songs.isEmpty()) return DeleteOutcome.Done(0)
        val uris = songs.map { uriOf(it) }
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                DeleteOutcome.NeedsConfirmation(
                    MediaStore.createDeleteRequest(resolver, uris).intentSender
                )
            }.getOrElse { DeleteOutcome.Failed(it.message.orEmpty()) }
        }

        var removed = 0
        for (uri in uris) {
            val outcome = runCatching { resolver.delete(uri, null, null) }
            val rows = outcome.getOrNull()
            if (rows != null && rows > 0) {
                removed++
                continue
            }
            val error = outcome.exceptionOrNull()
            // Android 10 hands back a recoverable exception carrying its own
            // confirmation dialog for files the app did not write itself.
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                error is android.app.RecoverableSecurityException
            ) {
                return DeleteOutcome.NeedsConfirmation(
                    error.userAction.actionIntent.intentSender
                )
            }
        }
        return if (removed > 0) {
            DeleteOutcome.Done(removed)
        } else {
            DeleteOutcome.Failed("לא ניתן למחוק את הקבצים")
        }
    }
}
