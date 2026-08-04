package com.strauss.wifiota

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

/**
 * Port of the file-matching / hashing helpers from wifi_ota_gui.py.
 *
 * The desktop tool scans a folder recursively and picks the shallowest match.
 * Here the folder comes from the Storage Access Framework, so DocumentFile
 * replaces pathlib, but the patterns and the priority order are identical.
 */
object Firmware {

    data class Set(
        val hmi: DocumentFile? = null,
        val addon: DocumentFile? = null,
        val rc: DocumentFile? = null
    )

    private const val MAX_DEPTH = 3

    /** Breadth-first scan, so shallower files win - same as the desktop rule. */
    fun scan(root: DocumentFile): Set {
        var hmiEnc: DocumentFile? = null
        var hmiPlain: DocumentFile? = null
        var addon: DocumentFile? = null
        var rc: DocumentFile? = null

        var level = listOf(root)
        var depth = 0

        while (level.isNotEmpty() && depth <= MAX_DEPTH) {
            val next = mutableListOf<DocumentFile>()
            for (dir in level) {
                for (f in dir.listFiles()) {
                    if (f.isDirectory) { next += f; continue }
                    val name = (f.name ?: "").lowercase()
                    if (!name.endsWith(".bin")) continue

                    when {
                        name.startsWith("addon-fizz") ->
                            if (addon == null) addon = f
                        name.startsWith("rc") ->
                            if (rc == null) rc = f
                        name.contains("hmi") && name.contains("enc") ->
                            if (hmiEnc == null) hmiEnc = f
                        name.contains("hmi") && !name.contains("addon") ->
                            if (hmiPlain == null) hmiPlain = f
                    }
                }
            }
            level = next
            depth++
        }

        return Set(hmi = hmiEnc ?: hmiPlain, addon = addon, rc = rc)
    }

    /**
     * Pull the version out of a firmware filename.
     *
     *   element-p-hmi-0.03.130_enc.bin -> 0.03.130
     *   addon-fizz-00.00.402.bin       -> 00.00.402
     *
     * Picks the dotted group that fits the component, not just the first match.
     */
    fun versionFromName(name: String, component: String): String {
        val groups = Regex("""\d{1,2}\.\d{2,3}\.\d{2,3}""")
            .findAll(name).map { it.value }.toList()
        if (groups.isEmpty()) return ""

        return when (component) {
            "hmi" -> groups.firstOrNull {
                        (it.startsWith("0.0") || it.startsWith("0.1") || it.startsWith("1.0")) &&
                            !it.startsWith("00.")
                     }
                     ?: groups.firstOrNull { !it.startsWith("00.") }
                     ?: groups.first()

            "rc" -> groups.first()

            // addon: prefer the 00.00.xxx style
            else -> groups.firstOrNull { it.startsWith("00.") } ?: groups.last()
        }
    }

    /**
     * Reads the file and returns its bytes plus the lowercase hex sha256.
     *
     * Firmware images are a few MB, so holding one in memory is fine and keeps
     * the upload a single RequestBody. Revisit if images ever grow past ~16 MB.
     */
    fun readAndHash(context: Context, file: DocumentFile): Pair<ByteArray, String> {
        val bytes = context.contentResolver.openInputStream(file.uri)!!
            .use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return bytes to sha
    }
}
