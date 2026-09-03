package com.strauss.wifiota

import android.content.Context
import org.json.JSONObject

/**
 * The firmware image shipped inside the APK.
 *
 * Source of truth is `firmware/manifest.json` in the project root. Gradle adds
 * that folder as an assets directory, so both the manifest and the .bin next to
 * it end up at the root of the APK's assets. Nothing here is downloaded and
 * nothing is picked by the user - what was in the project at build time is what
 * gets flashed.
 *
 * Expected manifest:
 *
 *     {
 *       "model":     "Tamar",
 *       "component": "hmi",
 *       "version":   "0.02.131",
 *       "file":      "element-p-hmi-0.02.131_enc.bin",
 *       "sha256":    "a1b2..."      // optional
 *     }
 *
 * `model` must match a [BarModel.id]. It is not decoration: the flash button
 * stays disabled unless the connected bar reports that same model, so a Tamar
 * image can never be pushed into a Premium.
 *
 * `sha256` is optional. When present it is checked against the bytes actually
 * read out of the APK - that catches a corrupted or mismatched file before it
 * reaches the bar, not after.
 */
data class BundledFirmware(
    val model: String,
    val component: String,
    val version: String,
    val fileName: String,
    val sha256: String?
) {

    /** The image itself, as an asset stream. */
    fun source(): FwSource = FwSource.Asset(fileName)

    /** True when this image is meant for the given model. */
    fun matches(m: BarModel?): Boolean =
        m != null && m.id.equals(model, ignoreCase = true)

    companion object {

        const val MANIFEST = "manifest.json"

        /**
         * Reads and validates the manifest.
         *
         * Returns the parsed entry, or null with a reason. The reason is always
         * returned - a build with no usable firmware must say why in the log
         * rather than just showing a dead button.
         */
        fun load(context: Context): Pair<BundledFirmware?, String> {
            val raw = try {
                context.assets.open(MANIFEST).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                return null to "no $MANIFEST in this build"
            }

            val fw = try {
                val o = JSONObject(raw)
                BundledFirmware(
                    model = o.getString("model"),
                    component = o.optString("component", "hmi"),
                    version = o.getString("version"),
                    fileName = o.getString("file"),
                    sha256 = o.optString("sha256").ifBlank { null }
                )
            } catch (e: Exception) {
                return null to "$MANIFEST is malformed: ${e.message}"
            }

            // A manifest pointing at a file that never made it into the APK is
            // worse than no manifest: it looks ready and fails at flash time.
            val present = try {
                context.assets.open(fw.fileName).use { true }
            } catch (e: Exception) {
                false
            }
            if (!present) return null to "${fw.fileName} is missing from the build"

            return fw to "${fw.model}/${fw.component} v${fw.version} (${fw.fileName})"
        }
    }
}
