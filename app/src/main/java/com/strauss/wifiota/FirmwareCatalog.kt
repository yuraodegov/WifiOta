package com.strauss.wifiota

import android.content.Context
import java.io.File

/**
 * The app's own firmware directory.
 *
 *   filesDir/firmware/<model>/                 current images
 *   filesDir/firmware/<model>/archive/         images that were replaced
 *
 * Private storage needs no permission and no folder picking, and it survives
 * app restarts. It does NOT survive an uninstall - anything that must be kept
 * lives on the server, not here.
 *
 * A replaced image is never deleted. It is moved into archive/ and stays there
 * until the user clears it deliberately: rolling back to yesterday's build in
 * front of a customer is worth a few megabytes.
 */
class FirmwareStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "firmware")

    fun modelDir(folder: String): File =
        File(root, folder).apply { mkdirs() }

    fun archiveDir(folder: String): File =
        File(modelDir(folder), Firmware.ARCHIVE_DIR).apply { mkdirs() }

    /** Current images for a model, archive excluded. */
    fun scan(folder: String): Firmware.Set {
        val dir = File(root, folder)
        return if (dir.isDirectory) Firmware.scanLocal(dir) else Firmware.Set()
    }

    /** True when anything at all has been downloaded for this model. */
    fun hasAnything(folder: String): Boolean = !scan(folder).isEmpty

    /** Version currently held locally for a component, or null. */
    fun localVersion(folder: String, component: String): String? {
        val set = scan(folder)
        val src = when (component) {
            "hmi" -> set.hmi
            "fizzz" -> set.addon
            "rc" -> set.rc
            else -> null
        } ?: return null
        return Firmware.versionFromName(src.name, component).ifEmpty { null }
    }

    fun tempFile(fileName: String): File =
        File(context.cacheDir, "dl_$fileName")

    /**
     * Moves a verified download into place, archiving whatever it replaces.
     *
     * Everything already in the model folder that belongs to the same component
     * goes to archive/ first - including a file with the same name, which is
     * renamed rather than overwritten so a re-download never destroys the copy
     * that is known to work.
     *
     * @return the installed file.
     */
    fun install(folder: String, component: String, fileName: String, temp: File): File {
        val dir = modelDir(folder)
        val archive = archiveDir(folder)

        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.lowercase().endsWith(".bin") }
            .filter { Firmware.componentFromName(it.name) == component }
            .forEach { old -> archiveOne(old, archive) }

        val target = File(dir, fileName)
        if (!temp.renameTo(target)) {
            // Different mount points (cacheDir vs filesDir on some devices)
            // make rename fail; copying is the fallback, not the default.
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /** Keeps every archived copy: same name gets a numeric suffix. */
    private fun archiveOne(old: File, archive: File) {
        var dest = File(archive, old.name)
        var n = 1
        while (dest.exists()) {
            val base = old.name.removeSuffix(".bin")
            dest = File(archive, "$base($n).bin")
            n++
        }
        if (!old.renameTo(dest)) {
            old.copyTo(dest, overwrite = false)
            old.delete()
        }
    }

    /** Archived images for a model, newest first. */
    fun archived(folder: String): List<File> =
        archiveDir(folder).listFiles().orEmpty()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }

    fun clearArchive(folder: String): Int {
        val files = archived(folder)
        files.forEach { it.delete() }
        return files.size
    }
}