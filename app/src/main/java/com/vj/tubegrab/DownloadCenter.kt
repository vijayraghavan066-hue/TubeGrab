package com.vj.tubegrab

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * App-wide download queue. Lives outside the screen, so downloads keep going when you
 * rotate the phone or press back (they stop only if Android kills the app).
 */
object DownloadCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slots = Semaphore(2)              // max 2 downloads at once
    private val jobs = HashMap<String, Job>()
    private val canceled = HashSet<String>()
    private val pausedForWifi = HashSet<String>()

    /** Newest first. Compose observes this directly. */
    val items = mutableStateListOf<DlState>()

    fun latestFor(videoId: String): DlState? = items.firstOrNull { it.item.id == videoId }

    fun start(context: Context, item: VideoItem, format: DlFormat) {
        val app = context.applicationContext
        val key = "${item.id}_${System.currentTimeMillis()}"
        items.add(0, DlState(key = key, item = item, format = format, message = "Waiting…"))


        jobs[key] = scope.launch {
            try {
                // Loop so a download paused by losing Wi-Fi resumes when Wi-Fi comes back
                while (true) {
                    if (!NetworkMonitor.downloadsAllowed()) {
                        update(key) { it.copy(status = DlStatus.Queued, progress = -1f, message = "Waiting for Wi-Fi…") }
                        NetworkMonitor.awaitDownloadsAllowed()
                    }
                    val finished = slots.withPermit { runOnce(app, key, item, format) }
                    if (finished) break
                }
            } catch (e: Throwable) {
                if (key in canceled || e is CancellationException) {
                    update(key) { it.copy(status = DlStatus.Canceled, progress = -1f, message = "Canceled") }
                } else {
                    update(key) { it.copy(status = DlStatus.Failed, progress = -1f, message = Engine.cleanError(e)) }
                }
            } finally {
                Engine.cleanup(app, key)
                jobs.remove(key)
                canceled.remove(key)
                pausedForWifi.remove(key)
            }
        }
    }

    /** One download attempt. Returns false if it was paused because Wi-Fi was lost. */
    private suspend fun runOnce(app: Context, key: String, item: VideoItem, format: DlFormat): Boolean {
        if (key in canceled) throw CancellationException()
        // Could have switched to mobile data while waiting for a free slot
        if (!NetworkMonitor.downloadsAllowed()) return false
        update(key) { it.copy(status = DlStatus.Running, message = "Getting video info…") }

        // Stop yt-dlp right away if Wi-Fi drops (it resumes from the partial file later)
        val watcher = scope.launch {
            NetworkMonitor.awaitDownloadsBlocked()
            pausedForWifi.add(key)
            Engine.cancel(key)
        }
        val result = try {
            Engine.download(app, item, format, key) { p, line ->
                scope.launch {
                    update(key) { s ->
                        if (s.status == DlStatus.Running) s.copy(progress = p, message = describe(p, line, s.message))
                        else s
                    }
                }
            }
        } catch (e: Throwable) {
            if (key in pausedForWifi && key !in canceled) {
                pausedForWifi.remove(key)
                update(key) { it.copy(status = DlStatus.Queued, progress = -1f, message = "Paused: waiting for Wi-Fi…") }
                return false
            }
            throw e
        } finally {
            watcher.cancel()
        }
        if (key in canceled) throw CancellationException()

        if (result.split) {
            // One MP3 per song, grouped in a folder named after the video
            val album = Saver.safeFolderName(item.title)
            val tracks = ArrayList<SavedTrack>()
            result.files.forEachIndexed { i, f ->
                update(key) { it.copy(progress = -1f, message = "Saving song ${i + 1} of ${result.files.size}…") }
                val uri = Saver.publish(app, f, audio = true, subFolder = album)
                if (uri != null) tracks += SavedTrack(f.nameWithoutExtension, uri)
            }
            update(key) {
                it.copy(
                    status = DlStatus.Done,
                    progress = 100f,
                    savedUri = tracks.firstOrNull()?.uri,
                    tracks = tracks,
                    folder = "Music/TubeGrab/$album",
                    message = "${tracks.size} songs saved to Music/TubeGrab/$album",
                )
            }
        } else {
            update(key) { it.copy(progress = -1f, message = "Saving…") }
            val uri = Saver.publish(app, result.files.first(), format.isAudio)
            val where = "${if (format.isAudio) "Music" else "Movies"}/TubeGrab"
            update(key) {
                it.copy(
                    status = DlStatus.Done,
                    progress = 100f,
                    savedUri = uri,
                    folder = where,
                    message = if (format == DlFormat.Mp3Split)
                        "No song chapters found in this video, so it was saved as one MP3 in $where"
                    else "Saved to $where",
                )
            }
        }
        return true
    }

    fun cancel(key: String) {
        canceled.add(key)
        Engine.cancel(key)
        // A download that's still waiting (for Wi-Fi or a free slot) has no process to stop
        if (items.firstOrNull { it.key == key }?.status == DlStatus.Queued) jobs[key]?.cancel()
    }

    fun retry(context: Context, state: DlState) {
        items.removeAll { it.key == state.key }
        start(context, state.item, state.format)
    }

    fun clearFinished() {
        items.removeAll { it.status != DlStatus.Running && it.status != DlStatus.Queued }
    }

    /** Remove one song from a split download after it was deleted from the phone. */
    fun removeTrack(key: String, uri: android.net.Uri) {
        update(key) { s ->
            val left = s.tracks.filterNot { it.uri == uri }
            s.copy(
                tracks = left,
                message = if (left.isEmpty()) "All songs deleted" else "${left.size} songs in ${s.folder}",
            )
        }
    }

    private fun update(key: String, change: (DlState) -> DlState) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) items[i] = change(items[i])
    }

    /** Turn a raw yt-dlp output line into a short status text. */
    private fun describe(p: Float, line: String, previous: String): String = when {
        line.contains("[SplitChapters]") -> "Splitting into songs…"
        line.contains("[ExtractAudio]") -> "Converting to MP3…"
        line.contains("[Merger]") -> "Merging video + audio…"
        line.contains("[Metadata]") -> "Adding tags…"
        line.contains("[download]") && line.contains("%") ->
            line.substringAfter("[download]").replace(Regex("\\s+"), " ").trim()
        p >= 0 -> "${p.toInt()}%"
        else -> previous
    }
}
