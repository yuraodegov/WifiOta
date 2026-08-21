package com.strauss.wifiota

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * A firmware image, wherever it came from.
 *
 * Two origins have to look the same to the rest of the app: a file the user
 * picked through the Storage Access Framework, and a file this app downloaded
 * into its own private storage. Everything above this class works with the
 * name and the bytes, never with the Uri or the path.
 */
sealed class FwSource {

    abstract val name: String
    abstract fun open(context: Context): InputStream

    /** Picked by the user through the system file/folder picker. */
    class Saf(val doc: DocumentFile) : FwSource() {
        override val name: String get() = doc.name ?: "firmware.bin"
        override fun open(context: Context): InputStream =
            context.contentResolver.openInputStream(doc.uri)
                ?: error("Cannot open ${doc.uri}")
    }

    /** Downloaded by the app into filesDir - no permissions involved. */
    class Local(val file: File) : FwSource() {
        override val name: String get() = file.name
        override fun open(context: Context): InputStream = file.inputStream()
    }
}

/**
 * Port of the file-matching / hashing helpers from wifi_ota_gui.py.
 *
 * The desktop tool scans a folder recursively and picks the shallowest match.
 * The same rule applies here for both origins: shallower wins, and the first
 * match at a given depth wins.
 */
object Firmware {

    data class Set(
        val hmi: FwSource? = null,
        val addon: FwSource? = null,
        val rc: FwSource? = null
    ) {
        val isEmpty: Boolean get() = hmi == null && addon == null && rc == null
    }

    private const val MAX_DEPTH = 3

    /** Sub-folder holding replaced images; never scanned as a source. */
    const val ARCHIVE_DIR = "archive"

    /**
     * Collects candidates by name and keeps the first of each kind.
     *
     * Encrypted HMI images win over plain ones because that is what the bar
     * expects; the plain file is only a fallback for older builds.
     */
    private class Picker {
        private var hmiEnc: FwSource? = null
        private var hmiPlain: FwSource? = null
        private var addon: FwSource? = null
        private var rc: FwSource? = null

        fun offer(src: FwSource) {
            val name = src.name.lowercase()
            if (!name.endsWith(".bin")) return
            when {
                name.startsWith("addon-fizz") -> if (addon == null) addon = src
                name.startsWith("rc") -> if (rc == null) rc = src
                name.contains("hmi") && name.contains("enc") ->
                    if (hmiEnc == null) hmiEnc = src
                name.contains("hmi") && !name.contains("addon") ->
                    if (hmiPlain == null) hmiPlain = src
            }
        }

        fun result() = Set(hmi = hmiEnc ?: hmiPlain, addon = addon, rc = rc)
    }

    /** Breadth-first scan of a folder the user picked. */
    fun scan(root: DocumentFile): Set {
        val picker = Picker()
        var level = listOf(root)
        var depth = 0

        while (level.isNotEmpty() && depth <= MAX_DEPTH) {
            val next = mutableListOf<DocumentFile>()
            for (dir in level) {
                for (f in dir.listFiles()) {
                    if (f.isDirectory) {
                        if (!f.name.equals(ARCHIVE_DIR, ignoreCase = true)) next += f
                        continue
                    }
                    picker.offer(FwSource.Saf(f))
                }
            }
            level = next
            depth++
        }
        return picker.result()
    }

    /**
     * Breadth-first scan of a directory this app owns.
     *
     * The archive folder is skipped deliberately: replaced images are kept for
     * the user to fall back on by hand, not to be offered as current.
     */
    fun scanLocal(root: File): Set {
        val picker = Picker()
        var level = listOf(root)
        var depth = 0

        while (level.isNotEmpty() && depth <= MAX_DEPTH) {
            val next = mutableListOf<File>()
            for (dir in level) {
                for (f in dir.listFiles().orEmpty()) {
                    if (f.isDirectory) {
                        if (!f.name.equals(ARCHIVE_DIR, ignoreCase = true)) next += f
                        continue
                    }
                    picker.offer(FwSource.Local(f))
                }
            }
            level = next
            depth++
        }
        return picker.result()
    }

    /**
     * Which component a file belongs to, judged by its name.
     * Returns null when the name gives no clue - better to refuse than to
     * flash an image into the wrong component.
     */
    fun componentFromName(name: String): String? {
        val n = name.lowercase()
        return when {
            n.startsWith("addon-fizz") || n.contains("fizz") -> "fizzz"
            n.startsWith("rc") -> "rc"
            n.contains("hmi") -> "hmi"
            else -> null
        }
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
     * Reads the image and returns its bytes plus the lowercase hex sha256.
     *
     * Firmware images are a few MB, so holding one in memory is fine and keeps
     * the upload a single RequestBody. Revisit if images ever grow past ~16 MB.
     */
    fun readAndHash(context: Context, src: FwSource): Pair<ByteArray, String> {
        val bytes = src.open(context).use { it.readBytes() }
        return bytes to sha256(bytes)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}