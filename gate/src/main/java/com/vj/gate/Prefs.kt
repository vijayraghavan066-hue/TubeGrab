package com.vj.gate

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The three Gatewise tiles at Arbour Ponds, plus a status check. */
enum class Gate(val key: String, val title: String, val defaultLabel: String) {
    ENTRANCE("ENTRANCE", "Entrance Gate", "Entrance Gate"),
    PED("PED", "Pedestrian Gate", "Pedestrian Gate"),
    SIDE("SIDE", "Side Pedestrian", "Side Pedestrian");

    companion object {
        fun fromKey(k: String): Gate? = entries.firstOrNull { it.key.equals(k, ignoreCase = true) }
    }
}

/**
 * Settings live only on the phone (this repo is public, so no phone numbers or codes in the code).
 */
object Prefs {
    const val SENDER = "sender"
    const val RECEIVER = "receiver"

    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("gate", Context.MODE_PRIVATE)

    fun mode(c: Context) = sp(c).getString("mode", "") ?: ""
    fun setMode(c: Context, v: String) = sp(c).edit().putString("mode", v).apply()

    // Shared secret (both phones must have the same one)
    fun code(c: Context) = sp(c).getString("code", "") ?: ""
    fun setCode(c: Context, v: String) = sp(c).edit().putString("code", v.trim()).apply()

    // Sender
    fun target(c: Context) = sp(c).getString("target", "") ?: ""
    fun setTarget(c: Context, v: String) = sp(c).edit().putString("target", v.trim()).apply()
    fun sendLocation(c: Context) = sp(c).getBoolean("sendLoc", true)
    fun setSendLocation(c: Context, v: Boolean) = sp(c).edit().putBoolean("sendLoc", v).apply()

    // Receiver
    fun allowed(c: Context) = sp(c).getString("allowed", "") ?: ""
    fun setAllowed(c: Context, v: String) = sp(c).edit().putString("allowed", v.trim()).apply()
    fun gatewisePkg(c: Context) = sp(c).getString("pkg", "") ?: ""
    fun setGatewisePkg(c: Context, v: String) = sp(c).edit().putString("pkg", v.trim()).apply()
    fun label(c: Context, g: Gate) = sp(c).getString("label_${g.key}", g.defaultLabel) ?: g.defaultLabel
    fun setLabel(c: Context, g: Gate, v: String) = sp(c).edit().putString("label_${g.key}", v.trim()).apply()

    fun requireNearby(c: Context) = sp(c).getBoolean("nearby", true)
    fun setRequireNearby(c: Context, v: Boolean) = sp(c).edit().putBoolean("nearby", v).apply()
    fun radius(c: Context) = sp(c).getInt("radius", 250)
    fun setRadius(c: Context, v: Int) = sp(c).edit().putInt("radius", v).apply()
    fun gateLocation(c: Context): Pair<Double, Double>? {
        val lat = sp(c).getString("glat", null)?.toDoubleOrNull() ?: return null
        val lon = sp(c).getString("glon", null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }
    fun setGateLocation(c: Context, lat: Double, lon: Double) =
        sp(c).edit().putString("glat", lat.toString()).putString("glon", lon.toString()).apply()

    /** True when [number] is one of the allowed numbers (compares the last 10 digits). */
    fun isAllowed(c: Context, number: String): Boolean {
        val n = last10(number)
        if (n.isEmpty()) return false
        return allowed(c).split(',', ';', ' ', '\n').map { last10(it) }.any { it.isNotEmpty() && it == n }
    }

    fun last10(s: String) = s.filter { it.isDigit() }.takeLast(10)
}

/** Small on-phone log so you can see what happened. */
object History {
    private val _items = MutableStateFlow<List<String>>(emptyList())
    val items: StateFlow<List<String>> = _items.asStateFlow()

    fun load(c: Context) {
        val raw = c.getSharedPreferences("gate", Context.MODE_PRIVATE).getString("history", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        _items.value = (0 until arr.length()).map { arr.getString(it) }
    }

    fun add(c: Context, line: String) {
        val stamp = SimpleDateFormat("MMM d, h:mm:ss a", Locale.US).format(Date())
        val list = (listOf("$stamp · $line") + _items.value.ifEmpty { load(c); _items.value }).take(40)
        _items.value = list
        c.getSharedPreferences("gate", Context.MODE_PRIVATE).edit()
            .putString("history", JSONArray(list).toString()).apply()
    }

    fun clear(c: Context) {
        _items.value = emptyList()
        c.getSharedPreferences("gate", Context.MODE_PRIVATE).edit().putString("history", "[]").apply()
    }
}

/**
 * Text format:  GATE <ENTRANCE|PED|SIDE|STATUS> <code> [@lat,lon]
 * Example:      GATE ENTRANCE 4821 @27.95123,-82.45678
 * Replies from the gate phone start with REPLY_PREFIX.
 */
data class Command(val gate: Gate?, val status: Boolean, val code: String, val lat: Double?, val lon: Double?) {
    companion object {
        const val REPLY_PREFIX = "Gate Remote:"

        fun build(gate: Gate?, code: String, loc: Pair<Double, Double>?): String {
            val what = gate?.key ?: "STATUS"
            val at = loc?.let { " @%.5f,%.5f".format(Locale.US, it.first, it.second) } ?: ""
            return "GATE $what $code$at"
        }

        fun parse(body: String): Command? {
            val parts = body.trim().split(Regex("\\s+"))
            if (parts.size < 3 || !parts[0].equals("GATE", ignoreCase = true)) return null
            val status = parts[1].equals("STATUS", ignoreCase = true)
            val gate = Gate.fromKey(parts[1])
            if (!status && gate == null) return null
            var lat: Double? = null
            var lon: Double? = null
            parts.getOrNull(3)?.takeIf { it.startsWith("@") }?.drop(1)?.split(',')?.let {
                lat = it.getOrNull(0)?.toDoubleOrNull()
                lon = it.getOrNull(1)?.toDoubleOrNull()
            }
            return Command(gate, status, parts[2], lat, lon)
        }
    }
}
