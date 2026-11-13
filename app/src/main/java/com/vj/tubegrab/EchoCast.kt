package com.vj.tubegrab

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Sends a video's audio to the "Tube Grab" Alexa skill (see alexa-skill/ in this repo).
 *
 * How it works: the phone finds the audio stream URL and posts it to a private ntfy.sh topic
 * (the "Echo code"). When you say "Alexa, open Tube Grab", the skill reads that topic and the
 * Echo streams the audio itself, so the phone doesn't need to stay connected.
 */
object EchoCast {
    private const val PREFS = "echo"
    private const val KEY_CODE = "code"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Status line per video id, shown under its Echo button. */
    val status = mutableStateMapOf<String, String>()

    /** The private topic shared with the skill. Created once, then kept. */
    fun code(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_CODE, null)?.let { return it }
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val code = "tubegrab-" + (1..16).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        p.edit().putString(KEY_CODE, code).apply()
        return code
    }

    /**
     * playNow = true starts a new list (the Echo plays this one when you open the skill);
     * playNow = false adds it after whatever was sent before.
     */
    fun send(context: Context, item: VideoItem, playNow: Boolean) {
        val app = context.applicationContext
        status[item.id] = "Getting audio for Echo…"
        scope.launch {
            try {
                val url = Engine.streamUrl(app, item)
                post(code(app), JSONObject().apply {
                    put("v", 1)
                    put("mode", if (playNow) "play" else "queue")
                    put("title", item.title.take(120))
                    put("by", item.channel.take(60))
                    put("url", url)
                }.toString())
                status[item.id] = if (playNow) "Sent ✓ Say \"Alexa, open Tube Grab\""
                else "Queued on Echo ✓"
            } catch (e: Throwable) {
                status[item.id] = "Echo: " + Engine.cleanError(e)
            }
        }
    }

    private suspend fun post(topic: String, body: String) = withContext(Dispatchers.IO) {
        val conn = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setRequestProperty("Cache", "yes")       // keep it for the skill to read (12 h)
            conn.setRequestProperty("Firebase", "no")     // no push notifications needed
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) error("Couldn't reach the Echo relay (HTTP ${conn.responseCode})")
        } finally {
            conn.disconnect()
        }
    }
}
