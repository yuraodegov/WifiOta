package com.strauss.wifiota

import android.net.Network
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

/** What actually happened to an upload attempt. */
enum class UploadOutcome {
    /** The bar answered "uploaded successfully". */
    CONFIRMED,

    /** Every byte left the phone, then the link died before the reply came.
     *  Normal for HMI: the bar reboots as soon as it has the image. */
    DELIVERED_UNCONFIRMED,

    FAILED
}

/**
 * Streams the firmware in small chunks so upload progress reflects the network.
 *
 * flush() after every chunk is what makes the figure honest: without it Okio
 * buffers the whole image in memory and the bar reports 100 % instantly while
 * the device has barely started receiving.
 */
private class ProgressBody(
    private val data: ByteArray,
    private val onProgress: (Int) -> Unit
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = data.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var written = 0
        var lastPercent = -1
        while (written < data.size) {
            val n = minOf(CHUNK, data.size - written)
            sink.write(data, written, n)
            sink.flush()          // push it out now, not at the end
            written += n
            val percent = written * 100 / data.size
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }
    }

    private companion object { const val CHUNK = 8 * 1024 }
}

/**
 * Port of the upload flow from wifi_ota_gui.py.
 *
 *   POST http://192.168.4.1/ota/upload
 *        ?version=<ver>&sha256=<sha>&component=<hmi|fizzz>&transactionComplete=true
 *   Content-Type: application/octet-stream, body = the raw .bin (not multipart).
 */
class OtaClient(network: Network, private val host: String) {

    private val client = OkHttpClient.Builder()
        // Sockets are created on the bar's network, not the phone's default route.
        .socketFactory(network.socketFactory)
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Reads the bar's self-description. Returns null when the reply is not the
     * expected JSON (older firmware answers a bare "Error").
     */
    fun getInfo(): DeviceInfo? = try {
        client.newCall(Request.Builder().url("http://$host$INFO_PATH").build())
            .execute().use { r -> DeviceInfo.parse(r.body?.string().orEmpty()) }
    } catch (e: Exception) {
        null
    }

    fun ping(): Pair<Boolean, String> = try {
        client.newCall(Request.Builder().url("http://$host$INFO_PATH").build())
            .execute().use { r ->
                val body = r.body?.string()?.trim().orEmpty()
                true to "HTTP ${r.code}: ${body.ifEmpty { "(empty)" }}"
            }
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }

    fun prepareFota(): Pair<Boolean, String> = try {
        client.newCall(Request.Builder().url("http://$host$PREPARE_PATH").build())
            .execute().use { r -> true to "HTTP ${r.code}" }
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }

    private data class Attempt(
        val code: Int?,
        val body: String,
        val accepted: Boolean,
        val fullySent: Boolean
    )

    private fun uploadOnce(
        bytes: ByteArray,
        version: String,
        sha: String,
        component: String,
        transactionComplete: Boolean,
        onProgress: (Int) -> Unit
    ): Attempt {
        val url = HttpUrl.Builder()
            .scheme("http").host(host).encodedPath(UPLOAD_PATH)
            .addQueryParameter("version", version)
            .addQueryParameter("sha256", sha)
            .apply {
                if (component.isNotEmpty()) addQueryParameter("component", component)
                if (transactionComplete) addQueryParameter("transactionComplete", "true")
            }
            .build()

        var sentAll = false
        val body = ProgressBody(bytes) { percent ->
            if (percent >= 100) sentAll = true
            onProgress(percent)
        }

        return try {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                val text = r.body?.string()?.trim().orEmpty()
                Attempt(r.code, text, text.lowercase().contains(SUCCESS_TEXT), sentAll)
            }
        } catch (e: Exception) {
            Attempt(null, e.message ?: e.toString(), false, sentAll)
        }
    }

    suspend fun upload(
        bytes: ByteArray,
        version: String,
        sha: String,
        component: String,
        transactionComplete: Boolean,
        autoRetry: Boolean,
        onProgress: (Int) -> Unit,
        onWait: (Int) -> Unit,
        log: (String) -> Unit
    ): UploadOutcome {
        val attempts = if (autoRetry) RETRY_ON_500 + 1 else 1
        log("Sending ${bytes.size / 1024} KB  component=${component.ifEmpty { "(none)" }} version=$version")

        for (i in 1..attempts) {
            log("Attempt $i/$attempts...")
            onProgress(0)
            val a = uploadOnce(bytes, version, sha, component, transactionComplete, onProgress)

            when {
                a.accepted -> {
                    log("HTTP ${a.code}: ${a.body}")
                    return UploadOutcome.CONFIRMED
                }

                // The whole image left the phone and only then the link died.
                // The bar has the file and is writing it - retrying would only
                // hammer a device that is already busy, or a dead AP.
                a.code == null && a.fullySent -> {
                    log("All bytes sent, then the link closed (${a.body}). " +
                        "The bar has the image and is writing it.")
                    return UploadOutcome.DELIVERED_UNCONFIRMED
                }

                // 500 is not a broken request: the MSA is busy, the bar is not
                // ready. Wait and repeat with the request untouched.
                a.code == 500 && i < attempts -> {
                    log("HTTP 500: ${a.body} - bar busy/MSA down")
                    countdown(onWait)
                }

                a.code == null && i < attempts -> {
                    log("Connection failed before the file was sent (${a.body})")
                    countdown(onWait)
                }

                a.code == 400 -> {
                    log("HTTP 400: ${a.body} - wrong component/params.")
                    return UploadOutcome.FAILED
                }

                else -> {
                    log("HTTP ${a.code}: ${a.body}")
                    return UploadOutcome.FAILED
                }
            }
        }

        log("All attempts failed. Check the addon (HC) is connected and MSA shows 'connected'.")
        return UploadOutcome.FAILED
    }

    /** Exact 8 s wait, reported second by second - this one is not a guess. */
    private suspend fun countdown(onWait: (Int) -> Unit) {
        for (left in RETRY_WAIT_SEC downTo 1) {
            onWait(left)
            delay(1000)
        }
        onWait(0)
    }

    companion object {
        // Underscore, not "?" - with a question mark the server returns "Error".
        const val INFO_PATH = "/ap_tk=tk&command=get_info"

        const val PREPARE_PATH = "/ap_tk=tk&command=fota_prepare"
        const val UPLOAD_PATH = "/ota/upload"
        const val SUCCESS_TEXT = "uploaded successfully"

        const val CONNECT_TIMEOUT_SEC = 5L
        const val UPLOAD_TIMEOUT_SEC = 300L
        const val RETRY_ON_500 = 4
        const val RETRY_WAIT_SEC = 8
    }
}
