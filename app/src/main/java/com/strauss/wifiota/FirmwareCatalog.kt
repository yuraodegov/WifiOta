package com.strauss.wifiota

import org.json.JSONObject

/**
 * What the server says is available.
 *
 * Azure Blob Storage has no usable directory listing, so a plain link to a
 * "folder" tells the app nothing. A manifest next to the images is what makes
 * the whole thing work - one file, fetched first, describing everything else.
 *
 * manifest.json:
 * {
 *   "updated": "2026-08-20",
 *   "models": {
 *     "primium23": {
 *       "hmi":   { "version": "0.03.132",
 *                  "file": "element-p-hmi-0.03.132_enc.bin",
 *                  "sha256": "ab12...", "size": 1892352 },
 *       "fizzz": { "version": "00.00.403",
 *                  "file": "addon-fizz-00.00.403.bin",
 *                  "sha256": "cd34...", "size": 262144 }
 *     }
 *   }
 * }
 *
 * Keys under "models" are the same folder names BarModel already uses, so the
 * connected bar maps straight onto a manifest entry.
 *
 * "sha256" is not decoration: the image is verified against it before it is
 * allowed anywhere near a device, and the same value is later handed to the
 * bar in the upload query.
 *
 * "file" is resolved against the manifest's own folder as <base>/<model>/<file>
 * unless the entry carries an absolute "url".
 */
data class CatalogEntry(
    /** "hmi", "fizzz" or "rc" - matches what OtaClient sends. */
    val component: String,
    val version: String,
    val fileName: String,
    val sha256: String,
    val size: Long,
    /** Absolute override; null means build the URL from the base. */
    val url: String?
)

data class FirmwareCatalog(
    val updated: String,
    /** model folder -> its components. */
    val models: Map<String, List<CatalogEntry>>
) {
    fun entriesFor(folder: String): List<CatalogEntry> = models[folder].orEmpty()

    companion object {
        /** Component keys accepted in the manifest, in flashing order. */
        private val COMPONENTS = listOf("hmi", "fizzz", "rc")

        /**
         * Returns null when the text is not a manifest at all - a login page or
         * an Azure error XML both arrive as HTTP 200 with a body.
         */
        fun parse(json: String): FirmwareCatalog? = try {
            val root = JSONObject(json)
            val modelsJson = root.getJSONObject("models")
            val models = mutableMapOf<String, List<CatalogEntry>>()

            for (folder in modelsJson.keys()) {
                val perModel = modelsJson.getJSONObject(folder)
                val entries = mutableListOf<CatalogEntry>()
                for (component in COMPONENTS) {
                    val e = perModel.optJSONObject(component) ?: continue
                    val file = e.optString("file")
                    if (file.isEmpty()) continue
                    entries += CatalogEntry(
                        component = component,
                        version = e.optString("version"),
                        fileName = file,
                        sha256 = e.optString("sha256").lowercase(),
                        size = e.optLong("size", 0L),
                        url = e.optString("url").ifEmpty { null }
                    )
                }
                if (entries.isNotEmpty()) models[folder] = entries
            }

            if (models.isEmpty()) null
            else FirmwareCatalog(root.optString("updated"), models)
        } catch (e: Exception) {
            null
        }
    }
}