package com.strauss.wifiota

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gives the app a Network handle pointing at the water bar.
 *
 * On Android (Samsung especially) a Wi-Fi AP without internet does NOT become
 * the default route: traffic keeps leaving over mobile data and requests to
 * 192.168.4.1 time out. Sockets must be built on the Network object returned
 * here - see OtaClient.
 *
 * Two ways in:
 *   1. attachToCurrentWifi() - the phone is already joined to the bar from the
 *      system Wi-Fi settings. No dialog, no prompt.
 *   2. connect(prefix, pass)  - Android shows its own picker listing every AP
 *      whose name starts with the prefix, and the user chooses.
 */
class BarNetwork(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    /** Current usable network, or null while nothing is bound. */
    @Volatile
    var network: Network? = null
        private set

    private var pickerCallback: ConnectivityManager.NetworkCallback? = null
    private var attachCallback: ConnectivityManager.NetworkCallback? = null

    val isConnected: Boolean get() = network != null

    /**
     * Binds to the Wi-Fi the phone is already on, without asking the user
     * anything. Matches only a network with no internet - which is exactly what
     * a bar AP looks like - so it will not grab the office Wi-Fi by mistake.
     *
     * Returns null if nothing matched within the timeout. Whether this really
     * is a bar has to be settled by pinging it afterwards.
     */
    suspend fun attachToCurrentWifi(timeoutMs: Int = ATTACH_TIMEOUT_MS): Network? =
        suspendCancellableCoroutine { cont ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val settled = AtomicBoolean(false)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(net: Network) {
                    network = net
                    if (settled.compareAndSet(false, true)) cont.resume(net)
                }

                override fun onLost(net: Network) {
                    network = null
                }

                override fun onUnavailable() {
                    if (settled.compareAndSet(false, true)) cont.resume(null)
                }
            }

            attachCallback = cb
            cm.requestNetwork(cb, timeoutMs)
            cont.invokeOnCancellation { release() }
        }

    /**
     * Asks the system to join a bar AP by name prefix. Android draws the picker
     * and the user confirms - that dialog cannot be bypassed by an app.
     *
     * @param ssidPrefix case-sensitive; a match-all pattern is rejected by Android.
     */
    suspend fun connect(ssidPrefix: String, passphrase: String?): Network =
        suspendCancellableCoroutine { cont ->
            val specifier = WifiNetworkSpecifier.Builder()
                // Prefix match: every bar whose name starts with this shows up.
                .setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
                .apply { if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase) }
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val settled = AtomicBoolean(false)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(net: Network) {
                    network = net
                    // Fires again with a fresh handle if the bar reboots.
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

            pickerCallback = cb
            cm.requestNetwork(cb, CONNECT_TIMEOUT_MS)
            cont.invokeOnCancellation { release() }
        }

    /** Drops only the silent attachment, keeping any picker binding alive. */
    fun releaseAttachment() {
        attachCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        attachCallback = null
        if (pickerCallback == null) network = null
    }

    /** Always call when finished, otherwise the phone stays pinned to the bar. */
    fun release() {
        attachCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        pickerCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        attachCallback = null
        pickerCallback = null
        network = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val ATTACH_TIMEOUT_MS = 6_000
    }
}
