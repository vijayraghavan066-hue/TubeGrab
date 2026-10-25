package com.vj.tubegrab

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A temporary download that deletes itself after [expiresAt]. */
data class ExpiringFile(
    val uri: Uri,
    val title: String,
    val savedAt: Long,
    val expiresAt: Long,
    val needsConfirm: Boolean = false,   // Android wants the user's OK to delete it
) {
    fun daysLeft(now: Long = System.currentTimeMillis()): Int =
        (((expiresAt - now).coerceAtLeast(0) + DAY_MS - 1) / DAY_MS).toInt()

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** Persists the "delete after N days" timers so they survive app restarts and updates. */
object ExpiryStore {
    const val TEMP_DAYS = 30

    private var prefs: SharedPreferences? = null
    private val _files = MutableStateFlow<List<ExpiringFile>>(emptyList())
    val files: StateFlow<List<ExpiringFile>> = _files.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("expiry", Context.MODE_PRIVATE)
        prefs = p
        _files.value = try {
            val arr = JSONArray(p.getString("files", "[]"))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ExpiringFile(
                    uri = Uri.parse(o.getString("uri")),
                    title = o.optString("title"),
                    savedAt = o.optLong("savedAt"),
                    expiresAt = o.getLong("expiresAt"),
                    needsConfirm = o.optBoolean("needsConfirm", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(list: List<ExpiringFile>) {
        _files.value = list.sortedBy { it.expiresAt }
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("title", it.title)
                    .put("savedAt", it.savedAt)
                    .put("expiresAt", it.expiresAt)
                    .put("needsConfirm", it.needsConfirm)
            )
        }
        prefs?.edit()?.putString("files", arr.toString())?.apply()
    }

    fun add(uri: Uri, title: String, days: Int = TEMP_DAYS) {
        val now = System.currentTimeMillis()
        save(_files.value.filterNot { it.uri == uri } + ExpiringFile(uri, title, now, now + days * ExpiringFile.DAY_MS))
    }

    /** "Keep forever": cancel the timer (or forget a file that's already gone). */
    fun remove(uri: Uri) = save(_files.value.filterNot { it.uri == uri })

    fun removeAll(uris: Collection<Uri>) = save(_files.value.filterNot { it.uri in uris })

    fun isTemporary(uri: Uri?): ExpiringFile? = uri?.let { u -> _files.value.firstOrNull { it.uri == u } }

    /**
     * Deletes every expired file it can. Files Android won't let us delete silently
     * (e.g. saved before an app reinstall) are flagged so the app can ask the user.
     */
    suspend fun cleanUp(context: Context) = withContext(Dispatchers.IO) {
        init(context)
        val now = System.currentTimeMillis()
        val keep = ArrayList<ExpiringFile>()
        for (f in _files.value) {
            if (f.expiresAt > now) { keep += f; continue }
            when (Saver.delete(context, f.uri)) {
                is Saver.DeleteResult.Deleted -> Unit                 // gone: drop the timer
                is Saver.DeleteResult.NeedsConfirm -> keep += f.copy(needsConfirm = true)
                is Saver.DeleteResult.Failed -> Unit                  // already removed by the user
            }
        }
        save(keep)
    }

    /** Daily background job, so files get deleted even if the app isn't opened. */
    fun scheduleDailyCleanup(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "tubegrab-expiry-cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}

class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ExpiryStore.cleanUp(applicationContext)
        return Result.success()
    }
}
