package com.vj.tubegrab

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Thin wrapper around yt-dlp (via youtubedl-android) for search, info and downloads. */
object Engine {
    private const val TAG = "TubeGrab"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initLock = Mutex()
    @Volatile private var initialized = false

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Starting)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun warmUp(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                ensureReady(app)
                update(app)
            } catch (e: Exception) {
                Log.e(TAG, "warm-up failed", e)
            }
        }
    }

    suspend fun ensureReady(context: Context) = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (initialized) return@withLock
            try {
                val app = context.applicationContext
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                initialized = true
                _status.value = EngineStatus.Ready(version(app))
            } catch (e: Exception) {
                _status.value = EngineStatus.Error("Engine failed to start: ${e.message}")
                throw e
            }
        }
    }

    /** Pull the newest yt-dlp from GitHub (YouTube breaks old versions regularly). */
    suspend fun update(context: Context) = withContext(Dispatchers.IO) {
        ensureReady(context)
        val app = context.applicationContext
        _status.value = EngineStatus.Updating
        try {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed (will use bundled version)", e)
        }
        _status.value = EngineStatus.Ready(version(app))
    }

    private fun version(app: Context): String? =
        try { YoutubeDL.getInstance().version(app) } catch (e: Exception) { null }

    /** Don't run yt-dlp while its own file is being replaced by an update. */
    private suspend fun awaitIdle(context: Context) {
        ensureReady(context)
        status.first { it !is EngineStatus.Updating }
    }

    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("youtu.be/") || t.startsWith("www.youtube.com/") || t.startsWith("youtube.com/") ||
            t.startsWith("m.youtube.com/")
    }

    /** Text search on YouTube, or info for a pasted link (video or playlist). */
    suspend fun search(context: Context, query: String, max: Int = 25): List<VideoItem> =
        withContext(Dispatchers.IO) {
            awaitIdle(context)
            val q = query.trim()
            val target = when {
                looksLikeUrl(q) -> if (q.startsWith("http")) q else "https://$q"
                else -> "ytsearch$max:$q"
            }
            val req = YoutubeDLRequest(target).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-warnings")
            }
            val out = YoutubeDL.getInstance().execute(req).out
            parse(out)
        }

    private fun parse(json: String): List<VideoItem> {
        val root = JSONObject(json.trim())
        val entries = root.optJSONArray("entries")
        return if (entries != null) {
            (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.toItem() }
        } else {
            listOfNotNull(root.toItem())
        }
    }

    private fun JSONObject.str(key: String): String =
        if (has(key) && !isNull(key)) optString(key) else ""

    private fun JSONObject.num(key: String): Double =
        if (has(key) && !isNull(key)) optDouble(key, -1.0) else -1.0

    private fun JSONObject.toItem(): VideoItem? {
        val id = str("id").ifBlank { return null }
        val isYouTube = str("ie_key").startsWith("Youtube", ignoreCase = true) ||
            str("extractor_key").startsWith("Youtube", ignoreCase = true) ||
            str("url").contains("youtu") || str("webpage_url").contains("youtu")
        // Skip channels/playlists that sometimes show up in search results
        if (isYouTube && id.length != 11) return null

        val url = str("webpage_url").ifBlank {
            if (isYouTube) "https://www.youtube.com/watch?v=$id" else str("url")
        }
        val thumbFromList = optJSONArray("thumbnails")?.let { arr ->
            if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.str("url") else null
        }.orEmpty()
        val thumb = if (isYouTube) "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        else str("thumbnail").ifBlank { thumbFromList }

        return VideoItem(
            id = id,
            title = str("title").ifBlank { "(untitled)" },
            channel = str("channel").ifBlank { str("uploader") },
            durationSec = num("duration").toLong(),
            views = num("view_count").toLong(),
            url = url,
            thumbnail = thumb,
        )
    }

    /** Search-as-you-type suggestions (same source YouTube's own search box uses). */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank() || looksLikeUrl(query)) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val conn = URL(
                "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&ie=utf-8&oe=utf-8&q=$q"
            ).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val arr = JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }.take(8)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun workDir(context: Context, key: String) = File(context.cacheDir, "dl/$key")

    /**
     * Download [item] in [format] into a private temp folder and return the finished file.
     * [onProgress] gets (percent 0..100 or -1, raw yt-dlp output line).
     */
    suspend fun download(
        context: Context,
        item: VideoItem,
        format: DlFormat,
        key: String,
        onProgress: (Float, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        awaitIdle(context)
        val dir = workDir(context, key).apply { deleteRecursively(); mkdirs() }

        val req = YoutubeDLRequest(item.url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-warnings")
            addOption("-o", "${dir.absolutePath}/%(title).120B.%(ext)s")
            when (format) {
                DlFormat.Mp3 -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-metadata")
                }
                DlFormat.M4a -> {
                    addOption("-f", "ba[ext=m4a]/ba/b")
                    addOption("--embed-metadata")
                }
                is DlFormat.Video -> {
                    val h = format.height
                    // Prefer H.264 + AAC so it plays in every phone's gallery/player
                    addOption(
                        "-f",
                        "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b"
                    )
                    addOption("--merge-output-format", "mp4")
                }
            }
        }

        YoutubeDL.getInstance().execute(req, key) { progress, _, line ->
            onProgress(progress, line)
        }

        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: error("Download finished but no file was produced")
    }

    fun cancel(key: String) {
        try { YoutubeDL.getInstance().destroyProcessById(key) } catch (_: Exception) {}
    }

    fun cleanup(context: Context, key: String) {
        scope.launch { workDir(context, key).deleteRecursively() }
    }

    /** Turn yt-dlp's noisy stderr into one readable line. */
    fun cleanError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        val lines = msg.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { it.contains("ERROR") } ?: lines.firstOrNull() ?: msg
        return line.substringAfter("ERROR:").trim().take(220)
    }
}
