package com.strauss.wifiota

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Joins the water bar access point and keeps the Network handle alive.
 *
 * This is the part that has no equivalent in wifi_ota_gui.py. On a laptop you
 * join the AP in the OS and every socket goes there. On Android (Samsung in
 * particular) an AP without internet does NOT become the default route:
 * traffic keeps leaving over mobile data and every request to 192.168.4.1
 * times out. Sockets must be built on this Network explicitly - see OtaClient.
 *
 * Connect once, then keep this instance for the whole session. The handle is
 * refreshed automatically if the bar reboots and its AP comes back.
 */
class BarNetwork(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    /** Current usable network, or null while the AP is down. */
    @Volatile
    var network: Network? = null
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    val isConnected: Boolean get() = network != null

    /**
     * Requests the AP and suspends until the system grants it. The user gets a
     * system dialog and has to approve joining - that cannot be bypassed.
     *
     * @param passphrase WPA2 password, or null/empty for an open AP.
     */
    suspend fun connect(ssid: String, passphrase: String?): Network =
        suspendCancellableCoroutine { cont ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply { if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase) }
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // The bar has no internet. Leave this capability in and the
                // request never matches - onUnavailable fires instead.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val settled = AtomicBoolean(false)

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(net: Network) {
                    network = net
                    // Fires again with a fresh handle if the bar reboots,
                    // which is why the field is updated on every call.
                    if (settled.compareAndSet(false, true)) cont.resume(net)
                }

                override fun onLost(net: Network) {
                    network = null
                }

                override fun onUnavailable() {
                    if (settled.compareAndSet(false, true)) {
                        cont.resumeWithException(
                            IllegalStateException("AP not found, or the dialog was declined")
                        )
                    }
                }
            }

            callback = cb
            cm.requestNetwork(request, cb, CONNECT_TIMEOUT_MS)
            cont.invokeOnCancellation { release() }
        }

    /** Always call when finished, otherwise the phone stays pinned to the bar. */
    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
    }
}
