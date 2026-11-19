package com.vj.tidy

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class Media(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val taken: Long,      // ms
    val modified: Long,   // s (used to know if a photo changed since last scan)
    val path: String,     // e.g. "DCIM/Camera/"
    val width: Int,
    val height: Int,
    val video: Boolean,
    val durationMs: Long,
) {
    val isScreenshot get() = path.contains("screenshot", true) || name.startsWith("Screenshot", true)
    val isWhatsApp get() = path.contains("whatsapp", true)
}

/** Near-identical photos. [bestId] is the one the AI suggests keeping. */
data class DupGroup(val items: List<Media>, val bestId: Long)

data class ScanResult(
    val images: List<Media>,
    val videos: List<Media>,
    val analysis: Map<Long, Analysis>,
    val groups: List<DupGroup>,
) {
    fun without(ids: Set<Long>): ScanResult = copy(
        images = images.filter { it.id !in ids },
        videos = videos.filter { it.id !in ids },
        groups = groups.mapNotNull { g ->
            val left = g.items.filter { it.id !in ids }
            when {
                left.size < 2 -> null
                left.any { it.id == g.bestId } -> g.copy(items = left)
                else -> DupGroup(left, left.first().id)
            }
        },
    )

    fun items(cat: Category, blurLevel: Int): List<Media> = when (cat) {
        Category.DUPLICATES -> groups.flatMap { it.items }
        Category.BLURRY -> {
            val limit = BLUR_LEVELS[blurLevel]
            images.filter { m ->
                val a = analysis[m.id]
                a != null && a.readable && !m.isScreenshot && a.detail > 12f && a.sharp < limit
            }.sortedBy { analysis[it.id]?.sharp }
        }
        Category.DARK -> images.filter { m ->
            val a = analysis[m.id]
            a != null && a.readable && !m.isScreenshot && a.bright < 28f
        }.sortedBy { analysis[it.id]?.bright }
        Category.SCREENSHOTS -> images.filter { it.isScreenshot }.sortedBy { it.taken }
        Category.WHATSAPP -> (images + videos).filter { it.isWhatsApp }.sortedByDescending { it.size }
        Category.BIG_VIDEOS -> videos.filter { it.size >= 50L * 1024 * 1024 }.sortedByDescending { it.size }
    }

    /** Space freed if the default selection is trashed (duplicates: everything except the best shot). */
    fun savings(cat: Category, blurLevel: Int): Long = when (cat) {
        Category.DUPLICATES -> groups.sumOf { g -> g.items.filter { it.id != g.bestId }.sumOf { it.size } }
        else -> items(cat, blurLevel).sumOf { it.size }
    }

    companion object {
        /** Blur sensitivity Low / Medium / High → sharpness below this counts as blurry. */
        val BLUR_LEVELS = floatArrayOf(15f, 35f, 70f)
    }
}

enum class Category(val emoji: String, val title: String, val help: String, val preselect: Boolean) {
    DUPLICATES("🧬", "Duplicates & similar shots",
        "Near-identical photos grouped together. The AI keeps the sharpest, best-lit shot with eyes open (★) and selects the rest.", true),
    BLURRY("🌫️", "Blurry photos", "Shaky or out-of-focus shots. Change the sensitivity if it's catching good photos.", true),
    DARK("🌑", "Dark & pocket shots", "Almost-black photos, usually taken by accident.", true),
    SCREENSHOTS("📱", "Screenshots", "Oldest first. Nothing is selected — pick the ones you don't need.", false),
    WHATSAPP("💬", "WhatsApp media", "Photos & videos saved from WhatsApp chats, biggest first.", false),
    BIG_VIDEOS("🎬", "Large videos", "Videos over 50 MB, biggest first.", false),
}

object Scanner {
    /** True while scanning, so auto-update waits. */
    @Volatile var busy = false

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scan(c: Context, progress: (String, Float) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        busy = true
        try {
            progress("Finding photos & videos…", 0f)
            val images = query(c, video = false)
            val videos = query(c, video = true)

            // 1) Analyze each photo (cached, so later scans only look at new photos)
            val cache = Cache.load(c)
            val todo = images.filter { cache[it.id]?.first != it.modified }
            val done = AtomicInteger(0)
            val fresh = ConcurrentHashMap<Long, Analysis>()
            val workers = Dispatchers.Default.limitedParallelism(4)
            if (todo.isNotEmpty()) progress("AI checking photos… 0 / ${todo.size}", 0f)
            coroutineScope {
                todo.chunked(200).forEach { chunk ->
                    chunk.map { m ->
                        async(workers) {
                            fresh[m.id] = runCatching { ImageAi.analyze(c, m.uri) }.getOrDefault(Analysis.UNREADABLE)
                            val d = done.incrementAndGet()
                            if (d % 10 == 0 || d == todo.size) {
                                progress("AI checking photos… $d / ${todo.size}", 0.8f * d / todo.size)
                            }
                        }
                    }.awaitAll()
                    coroutineContext.ensureActive()
                }
            }
            val analysis = HashMap<Long, Analysis>(images.size)
            val keep = HashMap<Long, Pair<Long, Analysis>>(images.size)
            for (m in images) {
                val a = fresh[m.id] ?: cache[m.id]?.second ?: continue
                analysis[m.id] = a
                keep[m.id] = m.modified to a
            }
            Cache.save(c, keep)

            // 2) Group near-identical photos
            progress("Finding duplicates…", 0.82f)
            val clusters = cluster(images, analysis)

            // 3) AI picks the best shot in each group
            val groups = ArrayList<DupGroup>(clusters.size)
            val total = clusters.sumOf { it.size }.coerceAtLeast(1)
            var seen = 0
            for (g in clusters) {
                coroutineContext.ensureActive()
                val best = g.maxBy { m ->
                    seen++
                    if (seen % 5 == 0) progress("AI picking best shots… $seen / $total", 0.85f + 0.15f * seen / total)
                    ImageAi.baseScore(m, analysis.getValue(m.id)) + 2f * ImageAi.faceScore(c, m)
                }
                // Best first, the rest in time order
                groups += DupGroup(listOf(best) + g.filter { it.id != best.id }, best.id)
            }
            groups.sortByDescending { g -> g.items.sumOf { it.size } }

            progress("Done", 1f)
            ScanResult(images, videos, analysis, groups)
        } finally {
            busy = false
        }
    }

    private fun query(c: Context, video: Boolean): List<Media> {
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        if (video) proj += MediaStore.Video.VideoColumns.DURATION
        val out = ArrayList<Media>()
        c.contentResolver.query(coll, proj.toTypedArray(), null, null, null)?.use { cur ->
            val iId = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iTaken = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iMod = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iPath = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iW = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iDur = if (video) cur.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) else -1
            while (cur.moveToNext()) {
                val id = cur.getLong(iId)
                val mod = cur.getLong(iMod)
                val taken = cur.getLong(iTaken).takeIf { it > 0 } ?: (mod * 1000)
                out += Media(
                    id = id,
                    uri = ContentUris.withAppendedId(coll, id),
                    name = cur.getString(iName).orEmpty(),
                    size = cur.getLong(iSize),
                    taken = taken,
                    modified = mod,
                    path = cur.getString(iPath).orEmpty(),
                    width = cur.getInt(iW),
                    height = cur.getInt(iH),
                    video = video,
                    durationMs = if (iDur >= 0) cur.getLong(iDur) else 0L,
                )
            }
        }
        return out
    }

    /**
     * Two photos are "the same" if their hashes are ≤4 bits apart (copies, re-saves, WhatsApp versions),
     * or ≤10 bits apart and taken within 2 minutes of each other (bursts, retakes).
     */
    private fun cluster(images: List<Media>, an: Map<Long, Analysis>): List<List<Media>> {
        // Skip blank/flat images: their hashes are noise and would all match each other
        val list = images.filter { (an[it.id]?.detail ?: -1f) > 6f }.sortedBy { it.taken }
        val n = list.size
        val hashes = LongArray(n) { an.getValue(list[it].id).hash }
        val times = LongArray(n) { list[it].taken }
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        val global = n <= 40_000 // compare every pair unless the library is huge
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val close = times[j] - times[i] <= 120_000
                if (!close && !global) break
                val d = java.lang.Long.bitCount(hashes[i] xor hashes[j])
                if (d <= 4 || (close && d <= 10)) {
                    val a = find(i)
                    val b = find(j)
                    if (a != b) parent[b] = a
                }
            }
        }
        return (0 until n).groupBy { find(it) }.values
            .filter { it.size in 2..40 }
            .map { idx -> idx.map { list[it] } }
    }
}

/** Remembers each photo's analysis so rescans are quick. */
private object Cache {
    private fun file(c: Context) = File(c.filesDir, "analysis-v1.txt")

    fun load(c: Context): HashMap<Long, Pair<Long, Analysis>> {
        val map = HashMap<Long, Pair<Long, Analysis>>()
        runCatching {
            file(c).forEachLine { line ->
                val p = line.split(',')
                if (p.size == 6) {
                    map[p[0].toLong()] = p[1].toLong() to Analysis(p[2].toLong(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat())
                }
            }
        }
        return map
    }

    fun save(c: Context, map: Map<Long, Pair<Long, Analysis>>) {
        runCatching {
            val tmp = File(c.filesDir, "analysis-v1.tmp")
            tmp.bufferedWriter().use { w ->
                for ((id, v) in map) {
                    val a = v.second
                    w.write("$id,${v.first},${a.hash},${a.sharp},${a.bright},${a.detail}\n")
                }
            }
            tmp.renameTo(file(c))
        }
    }
}
