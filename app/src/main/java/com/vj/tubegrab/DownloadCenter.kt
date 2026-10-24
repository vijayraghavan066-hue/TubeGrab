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

    /** Newest first. Compose observes this directly. */
    val items = mutableStateListOf<DlState>()

    fun latestFor(videoId: String): DlState? = items.firstOrNull { it.item.id == videoId }

    fun start(context: Context, item: VideoItem, format: DlFormat) {
        val app = context.applicationContext
        val key = "${item.id}_${System.currentTimeMillis()}"
        items.add(0, DlState(key = key, item = item, format = format, message = "Waiting…"))

        jobs[key] = scope.launch {
            try {
                slots.withPermit {
                    if (key in canceled) throw CancellationException()
                    update(key) { it.copy(status = DlStatus.Running, message = "Getting video info…") }

                    val file = Engine.download(app, item, format, key) { p, line ->
                        scope.launch {
                            update(key) { s ->
                                if (s.status == DlStatus.Running) s.copy(progress = p, message = describe(p, line, s.message))
                                else s
                            }
                        }
                    }
                    if (key in canceled) throw CancellationException()

                    update(key) { it.copy(progress = -1f, message = "Saving…") }
                    val uri = Saver.publish(app, file, format.isAudio)
                    update(key) {
                        it.copy(
                            status = DlStatus.Done,
                            progress = 100f,
                            savedUri = uri,
                            message = "Saved to ${if (format.isAudio) "Music" else "Movies"}/TubeGrab",
                        )
                    }
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
            }
        }
    }

    fun cancel(key: String) {
        canceled.add(key)
        Engine.cancel(key)
    }

    fun retry(context: Context, state: DlState) {
        items.removeAll { it.key == state.key }
        start(context, state.item, state.format)
    }

    fun clearFinished() {
        items.removeAll { it.status != DlStatus.Running && it.status != DlStatus.Queued }
    }

    private fun update(key: String, change: (DlState) -> DlState) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) items[i] = change(items[i])
    }

    /** Turn a raw yt-dlp output line into a short status text. */
    private fun describe(p: Float, line: String, previous: String): String = when {
        line.contains("[ExtractAudio]") -> "Converting to MP3…"
        line.contains("[Merger]") -> "Merging video + audio…"
        line.contains("[Metadata]") -> "Adding tags…"
        line.contains("[download]") && line.contains("%") ->
            line.substringAfter("[download]").replace(Regex("\\s+"), " ").trim()
        p >= 0 -> "${p.toInt()}%"
        else -> previous
    }
}
