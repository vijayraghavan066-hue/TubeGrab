package com.vj.tidy

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Perms {
    fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
}

/** Small log of update events, shown under Auto-update. */
object UpdateLog {
    private const val PREFS = "updatelog"
    private val _items = MutableStateFlow<List<String>>(emptyList())
    val items: StateFlow<List<String>> = _items.asStateFlow()

    fun load(c: Context) {
        _items.value = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("lines", "").orEmpty().split('\n').filter { it.isNotBlank() }
    }

    fun add(c: Context, line: String) {
        val stamp = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())
        val next = (listOf("$stamp  $line") + _items.value).take(10)
        _items.value = next
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lines", next.joinToString("\n")).apply()
    }
}

fun Long.prettySize(): String = when {
    this >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", this / (1L shl 30).toDouble())
    this >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", this / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0f KB", this / 1024.0)
}

fun Long.prettyDuration(): String {
    val s = this / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
