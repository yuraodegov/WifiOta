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

/**
 * Port of the upload flow from wifi_ota_gui.py.
 *
 * Proven request:
 *   POST http://192.168.4.1/ota/upload
 *        ?version=<ver>&sha256=<sha>&component=<hmi|fizzz>&transactionComplete=true
 *   Content-Type: application/octet-stream
 *   Body: the raw .bin - NOT multipart.
 *
 * Server replies:
 *   200 "FOTA file uploaded successfully" -> accepted
 *   400 "Incorrect request"               -> wrong component/params
 *   500                                   -> bar not ready (MSA busy) -> retry
 *   connection failure                    -> bar rebooting -> retry
 */
/**
 * Streams the firmware in small chunks so real upload progress can be reported.
 * A plain toRequestBody() writes everything in one go and gives no feedback.
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
            written += n
            val percent = written * 100 / data.size
            // Report only on change - otherwise the UI thread is flooded.
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }
        sink.flush()
    }

    private companion object { const val CHUNK = 32 * 1024 }
}

class OtaClient(network: Network, private val host: String) {

    private val client = OkHttpClient.Builder()
        // The single most important line in this app: sockets are created on
        // the bar's network instead of the phone's default route.
        .socketFactory(network.socketFactory)
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)   // retries are handled per status code below
        .build()

    /** Reachability check. Any HTTP reply, even an error, means the bar is up. */
    fun ping(): Pair<Boolean, String> = try {
        client.newCall(Request.Builder().url("http://$host$INFO_PATH").build())
            .execute().use { r ->
                val body = r.body?.string()?.trim().orEmpty()
                true to "HTTP ${r.code}: ${body.ifEmpty { "(empty)" }}"
            }
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }

    /** Official pre-upload step. Best effort - a failure here is not fatal. */
    fun prepareFota(): Pair<Boolean, String> = try {
        client.newCall(Request.Builder().url("http://$host$PREPARE_PATH").build())
            .execute().use { r -> true to "HTTP ${r.code}" }
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }

    /**
     * Single POST. Returns (httpCode or null on transport failure, body, accepted).
     */
    private fun uploadOnce(
        bytes: ByteArray,
        version: String,
        sha: String,
        component: String,
        transactionComplete: Boolean,
        onProgress: (Int) -> Unit
    ): Triple<Int?, String, Boolean> {
        // Query parameters in the proven order: version, sha256, component,
        // transactionComplete. component may be empty (RC firmware).
        val url = HttpUrl.Builder()
            .scheme("http").host(host).encodedPath(UPLOAD_PATH)
            .addQueryParameter("version", version)
            .addQueryParameter("sha256", sha)
            .apply {
                if (component.isNotEmpty()) addQueryParameter("component", component)
                if (transactionComplete) addQueryParameter("transactionComplete", "true")
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .post(ProgressBody(bytes, onProgress))
            .build()

        return try {
            client.newCall(request).execute().use { r ->
                val body = r.body?.string()?.trim().orEmpty()
                Triple(r.code, body, body.lowercase().contains(SUCCESS_TEXT))
            }
        } catch (e: Exception) {
            Triple(null, e.message ?: e.toString(), false)
        }
    }

    /**
     * Full upload with the retry policy from the desktop tool.
     *
     * @param component "hmi", "fizzz", or "" for RC.
     */
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
    ): Boolean {
        val attempts = if (autoRetry) RETRY_ON_500 + 1 else 1
        val label = component.ifEmpty { "(none)" }
        log("Uploading ${bytes.size / 1024} KB  component=$label version=$version")

        for (i in 1..attempts) {
            log("Attempt $i/$attempts...")
            onProgress(0)
            val (code, body, ok) =
                uploadOnce(bytes, version, sha, component, transactionComplete, onProgress)

            when {
                ok -> {
                    log("HTTP $code: $body")
                    return true
                }
                // 500 is not a broken request. The MSA is busy and the bar is
                // not ready. Wait and repeat - do not restructure the request.
                code == 500 && i < attempts -> {
                    log("HTTP 500: $body - bar busy/MSA down")
                    countdown(onWait)
                }
                code == null && i < attempts -> {
                    log("Connection failed ($body). Bar may be rebooting")
                    countdown(onWait)
                }
                code == 400 -> {
                    log("HTTP 400: $body - wrong component/params.")
                    return false
                }
                else -> {
                    log("HTTP $code: $body")
                    return false
                }
            }
        }

        log("All attempts failed. Check the addon (HC) is connected and MSA shows 'connected'.")
        return false
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
        const val INFO_PATH = "/ap?tk=tk&command=get_info"
        const val PREPARE_PATH = "/ap?tk=tk&command=fota_prepare"
        const val UPLOAD_PATH = "/ota/upload"
        const val SUCCESS_TEXT = "uploaded successfully"

        const val CONNECT_TIMEOUT_SEC = 5L
        const val UPLOAD_TIMEOUT_SEC = 120L
        const val RETRY_ON_500 = 4
        const val RETRY_WAIT_SEC = 8
    }
}
