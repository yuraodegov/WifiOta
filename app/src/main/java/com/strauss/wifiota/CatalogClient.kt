package com.strauss.wifiota

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches the manifest and the images from Azure Blob Storage.
 *
 * Deliberately NOT built on a bound Network the way OtaClient is: this traffic
 * must go out over the phone's normal route - office Wi-Fi or mobile data.
 * While the phone is attached to a bar's access point there is no internet on
 * that interface, so downloading and flashing are separate phases by nature.
 *
 * Auth: a container that is publicly readable needs nothing. A private one
 * needs a SAS token, which Azure expects as query parameters - it is appended
 * to every request rather than sent as a header.
 */
class CatalogClient(baseUrl: String, sasToken: String) {

    /** Base without a trailing slash, e.g. https://x.blob.core.windows.net/firmware */
    private val base = baseUrl.trim().trimEnd('/')

    /** SAS query string without a leading '?' or '&'; empty when public. */
    private val sas = sasToken.trim().trimStart('?', '&')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = base.startsWith("http")

    private fun withSas(url: String): String {
        if (sas.isEmpty()) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url$sep$sas"
    }

    fun manifestUrl(): String = withSas("$base/$MANIFEST")

    fun entryUrl(modelFolder: String, entry: CatalogEntry): String =
        withSas(entry.url ?: "$base/$modelFolder/${entry.fileName}")

    /**
     * Reads manifest.json.
     *
     * @return the catalog, or null with the reason in [detail].
     */
    fun fetchCatalog(): Pair<FirmwareCatalog?, String> = try {
        val req = Request.Builder()
            .url(manifestUrl())
            // Blob storage and any CDN in front of it will happily serve a
            // stale manifest otherwise, and the app would miss a new build.
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                !r.isSuccessful -> null to "HTTP ${r.code}"
                else -> {
                    val cat = FirmwareCatalog.parse(body)
                    if (cat == null) null to "Manifest is not valid JSON (HTTP ${r.code})"
                    else cat to "updated ${cat.updated}"
                }
            }
        }
    } catch (e: Exception) {
        null to (e.message ?: e.toString())
    }

    /**
     * Streams one image to [dest] and checks it against the manifest hash.
     *
     * The hash is the whole point of downloading rather than copying by hand:
     * a truncated or filtered file is caught here instead of on the device.
     * On mismatch [dest] is removed - a bad image must not be left where the
     * scanner can find it.
     *
     * @return null on success, or the reason it failed.
     */
    fun download(
        url: String,
        expectedSha: String,
        dest: File,
        onProgress: (Int) -> Unit
    ): String? = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return "HTTP ${r.code}"
            val body = r.body ?: return "Empty response"
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha.isNotEmpty() && actual != expectedSha) {
                dest.delete()
                "sha256 mismatch: expected $expectedSha, got $actual"
            } else null
        }
    } catch (e: Exception) {
        dest.delete()
        e.message ?: e.toString()
    }

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}