package com.vj.tubegrab

import android.net.Uri
import java.util.Locale

data class VideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Long,   // -1 when unknown (e.g. live)
    val views: Long,         // -1 when unknown
    val url: String,
    val thumbnail: String,
    val source: String = SOURCE_YOUTUBE,
)

const val SOURCE_YOUTUBE = "YouTube"
const val SOURCE_ARCHIVE = "Internet Archive"
const val SOURCE_DAILYMOTION = "Dailymotion"

sealed class DlFormat(val label: String, val isAudio: Boolean) {
    data object Mp3 : DlFormat("MP3 audio", true)
    data object M4a : DlFormat("M4A audio (no re-encode, faster)", true)
    data class Video(val height: Int) : DlFormat("Video ${height}p (MP4)", false)

    companion object {
        // getter (not a stored field) avoids a class-initialization-order trap with the nested objects
        val all: List<DlFormat> get() = listOf(Mp3, M4a, Video(360), Video(720), Video(1080))
    }
}

enum class DlStatus { Queued, Running, Done, Failed, Canceled }

data class DlState(
    val key: String,
    val item: VideoItem,
    val format: DlFormat,
    val status: DlStatus = DlStatus.Queued,
    val progress: Float = -1f,   // 0..100, -1 = unknown
    val message: String = "Starting…",
    val savedUri: Uri? = null,
)

sealed class EngineStatus {
    data object Starting : EngineStatus()
    data object Updating : EngineStatus()
    data class Ready(val version: String?) : EngineStatus()
    data class Error(val message: String) : EngineStatus()
}

fun formatDuration(sec: Long): String {
    if (sec < 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatViews(v: Long): String = when {
    v < 0 -> ""
    v >= 1_000_000_000 -> String.format(Locale.US, "%.1fB views", v / 1e9)
    v >= 1_000_000 -> String.format(Locale.US, "%.1fM views", v / 1e6)
    v >= 1_000 -> String.format(Locale.US, "%.1fK views", v / 1e3)
    else -> "$v views"
}
