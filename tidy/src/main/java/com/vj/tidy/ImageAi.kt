package com.vj.tidy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the app "sees" in one photo. Everything runs on the phone; nothing is uploaded.
 *  hash   – 64-bit difference hash: near-identical photos have hashes a few bits apart
 *  sharp  – Laplacian variance: low = blurry / shaky
 *  bright – average brightness 0..255: low = dark / pocket shot
 *  detail – contrast (std-dev of brightness): low = flat / blank. -1 = couldn't read the photo
 */
data class Analysis(val hash: Long, val sharp: Float, val bright: Float, val detail: Float) {
    val readable get() = detail >= 0f

    companion object {
        val UNREADABLE = Analysis(0L, 0f, 0f, -1f)
    }
}

object ImageAi {
    /** Analysis is done on a copy shrunk so its longest side is this many pixels. */
    private const val SIDE = 512

    fun analyze(c: Context, uri: Uri): Analysis {
        val bmp = decode(c, uri, SIDE) ?: return Analysis.UNREADABLE
        try {
            val w = bmp.width
            val h = bmp.height
            if (w < 9 || h < 8) return Analysis.UNREADABLE
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val g = FloatArray(w * h)
            var sum = 0.0
            var sumSq = 0.0
            for (i in px.indices) {
                val p = px[i]
                val y = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                g[i] = y
                sum += y
                sumSq += y * y
            }
            val n = g.size
            val mean = sum / n
            val std = sqrt(max(0.0, sumSq / n - mean * mean))
            return Analysis(dhash(g, w, h), laplacianVariance(g, w, h), mean.toFloat(), std.toFloat())
        } finally {
            bmp.recycle()
        }
    }

    /** Decodes a downsized copy (fast: JPEG decoders skip detail when sampling). Longest side ≈ [side]. */
    fun decode(c: Context, uri: Uri, side: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (max(w, h) / (sample * 2) >= side) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val longest = max(raw.width, raw.height)
        if (longest <= side) raw
        else {
            val scale = side.toFloat() / longest
            Bitmap.createScaledBitmap(raw, max(1, (raw.width * scale).toInt()), max(1, (raw.height * scale).toInt()), true)
                .also { if (it !== raw) raw.recycle() }
        }
    }.getOrNull()

    /** 9×8 grid of average brightness; each bit = "is this cell brighter than its right neighbour". */
    private fun dhash(g: FloatArray, w: Int, h: Int): Long {
        val cells = FloatArray(72)
        for (cy in 0 until 8) for (cx in 0 until 9) {
            val x0 = cx * w / 9
            val x1 = max(x0 + 1, (cx + 1) * w / 9)
            val y0 = cy * h / 8
            val y1 = max(y0 + 1, (cy + 1) * h / 8)
            var s = 0f
            var k = 0
            for (y in y0 until y1) for (x in x0 until x1) { s += g[y * w + x]; k++ }
            cells[cy * 9 + cx] = s / k
        }
        var hash = 0L
        var bit = 0
        for (cy in 0 until 8) for (cx in 0 until 8) {
            if (cells[cy * 9 + cx] > cells[cy * 9 + cx + 1]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    /** Variance of the edge filter: sharp photos have strong edges, blurry ones don't. */
    private fun laplacianVariance(g: FloatArray, w: Int, h: Int): Float {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val l = g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]
                sum += l
                sumSq += l * l
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    // ---------------- AI best-shot picker ----------------

    private val faces by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eyes open + smiling
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    /**
     * 0 when there are no faces. Otherwise ~0..1.3: rewards everyone's eyes being open, plus a bit for smiles.
     * Uses Google's on-device ML Kit face model.
     */
    suspend fun faceScore(c: Context, m: Media): Float {
        val bmp = decode(c, m.uri, 720) ?: return 0f
        return try {
            val rotation = runCatching {
                c.contentResolver.openInputStream(m.uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }.getOrDefault(0)
            val found = faces.process(InputImage.fromBitmap(bmp, rotation)).await()
            if (found.isEmpty()) 0f
            else found.map { f ->
                val eyes = min(f.leftEyeOpenProbability ?: 0.5f, f.rightEyeOpenProbability ?: 0.5f)
                val smile = f.smilingProbability ?: 0f
                eyes + 0.3f * smile
            }.average().toFloat()
        } catch (e: Exception) {
            0f
        } finally {
            bmp.recycle()
        }
    }

    /** Quality without faces: sharpness, good exposure, resolution; originals beat WhatsApp copies. */
    fun baseScore(m: Media, a: Analysis): Float {
        var s = ln(1f + max(0f, a.sharp)) - abs(a.bright - 120f) / 120f
        s += min(1f, m.width.toFloat() * m.height / 12_000_000f) * 0.5f
        if (m.isWhatsApp) s -= 0.7f
        if (m.isScreenshot) s -= 0.3f
        return s
    }
}
