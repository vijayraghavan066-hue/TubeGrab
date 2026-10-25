package com.vj.tubegrab

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Small persisted settings. */
object AppSettings {
    private var prefs: SharedPreferences? = null
    private val _wifiOnly = MutableStateFlow(true)
    /** Default ON: never use mobile data for downloads. */
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        _wifiOnly.value = p.getBoolean("wifi_only", true)
    }

    fun setWifiOnly(value: Boolean) {
        prefs?.edit()?.putBoolean("wifi_only", value)?.apply()
        _wifiOnly.value = value
    }
}

/** Watches whether the phone is on Wi-Fi (or Ethernet) vs mobile data. */
object NetworkMonitor {
    private val _onWifi = MutableStateFlow(false)
    val onWifi: StateFlow<Boolean> = _onWifi.asStateFlow()

    private var cm: ConnectivityManager? = null

    private fun NetworkCapabilities?.isWifiLike(): Boolean =
        this != null && (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

    private fun recompute() {
        val c = cm ?: return
        _onWifi.value = c.getNetworkCapabilities(c.activeNetwork).isWifiLike()
    }

    fun start(context: Context) {
        val c = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm = c
        recompute()
        c.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = recompute()
            override fun onLost(network: Network) {
                _onWifi.value = false
                recompute()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _onWifi.value = caps.isWifiLike()
            }
        })
    }

    /** True when a download may run right now (Wi-Fi, or Wi-Fi-only switched off). */
    fun downloadsAllowed(): Boolean = !AppSettings.wifiOnly.value || onWifi.value

    val downloadsAllowedFlow: Flow<Boolean> =
        combine(onWifi, AppSettings.wifiOnly) { wifi, only -> !only || wifi }

    suspend fun awaitDownloadsAllowed() {
        downloadsAllowedFlow.first { it }
    }

    suspend fun awaitDownloadsBlocked() {
        downloadsAllowedFlow.first { !it }
    }
}
