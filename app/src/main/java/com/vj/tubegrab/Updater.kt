package com.vj.tubegrab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Play-Store-style self updater.
 * Every push to GitHub builds a release tagged "build-N" (N = build number = versionCode).
 * The app compares N with its own versionCode, downloads the APK for this phone's CPU,
 * and hands it to Android's installer (one tap to confirm).
 */
object Updater {
    private const val REPO = "vijayraghavan066-hue/TubeGrab"

    data class Release(val build: Long, val title: String, val apkUrl: String, val sizeBytes: Long)

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val percent: Int) : State()
        data class ReadyToInstall(val release: Release, val file: File) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun currentBuild(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** [silent] = launch-time check: don't show "up to date" or errors. */
    suspend fun check(context: Context, silent: Boolean) = withContext(Dispatchers.IO) {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading) return@withContext
        if (!silent) _state.value = State.Checking
        try {
            val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "TubeGrab-updater")
            val body = try {
                if (conn.responseCode !in 200..299) error("GitHub returned HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val json = JSONObject(body)
            val build = json.optString("tag_name").removePrefix("build-").toLongOrNull()
                ?: error("Unexpected release tag")
            val assets = json.getJSONArray("assets")
            val byName = (0 until assets.length()).associate { i ->
                val a = assets.getJSONObject(i)
                a.optString("name") to (a.optString("browser_download_url") to a.optLong("size"))
            }
            // Best APK for this phone's CPU, falling back to the universal one
            val pick = Build.SUPPORTED_ABIS.asSequence()
                .map { "app-$it-release.apk" }
                .firstOrNull { it in byName }
                ?: "app-universal-release.apk".takeIf { it in byName }
                ?: error("No APK found in the latest release")
            val (url, size) = byName.getValue(pick)
            val release = Release(build, json.optString("name").ifBlank { "build $build" }, url, size)

            _state.value = if (build > currentBuild(context)) State.Available(release) else {
                if (silent) State.Idle else State.UpToDate
            }
        } catch (e: Exception) {
            _state.value = if (silent) State.Idle else State.Error("Update check failed: ${e.message}")
        }
    }

    suspend fun download(context: Context, release: Release) = withContext(Dispatchers.IO) {
        _state.value = State.Downloading(release, 0)
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "TubeGrab-build-${release.build}.apk")
        try {
            var url = URL(release.apkUrl)
            var conn: HttpURLConnection
            // Follow GitHub's redirect to its file host manually (works across hosts reliably)
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "TubeGrab-updater")
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) {
                    val next = conn.getHeaderField("Location") ?: error("Bad redirect")
                    conn.disconnect()
                    url = URL(url, next)
                    continue
                }
                if (code !in 200..299) error("Download failed: HTTP $code")
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = State.Downloading(release, pct)
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            _state.value = State.ReadyToInstall(release, out)
        } catch (e: Exception) {
            out.delete()
            _state.value = State.Error("Update download failed: ${e.message}")
        }
    }

    /**
     * Opens Android's installer. The first time, Android asks to allow TubeGrab to install
     * apps — we send the user to that switch; after turning it on they tap Install again.
     */
    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dismiss() {
        val s = _state.value
        if (s !is State.Downloading) _state.value = State.Idle
    }
}
