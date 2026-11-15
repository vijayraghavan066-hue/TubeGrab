package com.vj.gate

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** SMS + location helpers. */
object Phone {

    fun has(c: Context, perm: String) =
        ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED

    fun sendSms(c: Context, to: String, text: String): Boolean = try {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            c.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, null, null)
        else sms.sendTextMessage(to, null, text, null, null)
        true
    } catch (e: Exception) {
        History.add(c, "⚠️ Couldn't send text to $to: ${e.message}")
        false
    }

    /** Best effort current location within ~8 s; falls back to a recent last-known fix. */
    @SuppressLint("MissingPermission")
    suspend fun location(c: Context): Location? {
        if (!has(c, Manifest.permission.ACCESS_FINE_LOCATION) && !has(c, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 60_000 }
            .minByOrNull { it.accuracy }
        if (recent != null && recent.accuracy < 50) return recent
        if (providers.isEmpty()) return recent

        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(l)
                    }
                    @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                providers.forEach { runCatching { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) } }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
        return fresh ?: recent ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0]
    }

    /** Finds the Gatewise app by its launcher name. */
    fun findGatewise(c: Context): String? {
        val pm = c.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        return apps.firstOrNull {
            it.loadLabel(pm).toString().contains("gatewise", ignoreCase = true) ||
                it.activityInfo.packageName.contains("gatewise", ignoreCase = true)
        }?.activityInfo?.packageName
    }

    fun gatewisePackage(c: Context): String? =
        Prefs.gatewisePkg(c).ifBlank { null }?.takeIf { isInstalled(c, it) } ?: findGatewise(c)

    fun isInstalled(c: Context, pkg: String) =
        c.packageManager.getLaunchIntentForPackage(pkg) != null
}
