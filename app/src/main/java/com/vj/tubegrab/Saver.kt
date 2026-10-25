package com.vj.tubegrab

import android.content.ContentValues
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Moves a finished download into the phone's public Music/TubeGrab or Movies/TubeGrab folder. */
object Saver {

    private fun mimeOf(file: File, audio: Boolean): String = when (file.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "webm" -> if (audio) "audio/webm" else "video/webm"
        "mkv" -> "video/x-matroska"
        "mp4" -> if (audio) "audio/mp4" else "video/mp4"
        else -> if (audio) "audio/mpeg" else "video/mp4"
    }

    /** Folder-safe version of a video title, e.g. for an album folder. */
    fun safeFolderName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80)
            .ifBlank { "Album" }

    /**
     * Copies [file] into Music/TubeGrab (audio) or Movies/TubeGrab (video),
     * optionally inside [subFolder] (used for split albums).
     */
    suspend fun publish(context: Context, file: File, audio: Boolean, subFolder: String? = null): Uri? =
        withContext(Dispatchers.IO) {
        val mime = mimeOf(file, audio)
        val tail = if (subFolder.isNullOrBlank()) "TubeGrab" else "TubeGrab/$subFolder"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (audio)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/" + tail
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Couldn't create the output file")
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(
                if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            )
            val dir = File(base, tail).apply { mkdirs() }
            var dest = File(dir, file.name)
            var n = 1
            while (dest.exists()) {
                dest = File(dir, "${file.nameWithoutExtension} (${n++}).${file.extension}")
            }
            file.copyTo(dest)
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime)) { _, uri ->
                    if (cont.isActive) cont.resume(uri)
                }
            }
        }
    }

    sealed class DeleteResult {
        data object Deleted : DeleteResult()
        /** Android wants the user to confirm (e.g. files saved before an app reinstall). */
        data class NeedsConfirm(val sender: IntentSender) : DeleteResult()
        data class Failed(val message: String) : DeleteResult()
    }

    fun delete(context: Context, uri: Uri): DeleteResult {
        val resolver = context.contentResolver
        return try {
            resolver.delete(uri, null, null)
            DeleteResult.Deleted
        } catch (e: SecurityException) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    DeleteResult.NeedsConfirm(MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender)
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    DeleteResult.NeedsConfirm(e.userAction.actionIntent.intentSender)
                else -> DeleteResult.Failed(e.message ?: "Not allowed")
            }
        } catch (e: Exception) {
            DeleteResult.Failed(e.message ?: "Delete failed")
        }
    }
}
