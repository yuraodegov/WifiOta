package com.strauss.wifiota

/**
 * Field pilot build switches.
 *
 * The pilot ships a cut-down app: one bundled firmware image, no folder
 * picking, no server catalog, no component choice. None of that code was
 * deleted - it is guarded by [V1] instead of commented out, so it still
 * compiles and cannot silently rot while it is switched off. Turning the
 * full app back on is a one-line change here.
 *
 * Commenting the code out would have been the other option and a worse one:
 * dead text is never checked by the compiler, so by the time it is needed it
 * no longer builds against the code that moved on around it.
 */
object Pilot {

    /**
     * true  - pilot: firmware comes from the APK, Tamar only.
     * false - full app: folder picker, server catalog, HMI/ADDON/RC choice.
     */
    const val V1 = true

    /**
     * Code asked for before Setup opens.
     *
     * This keeps a technician out of the network settings by accident, nothing
     * more, and it should not be described as a security control. It ships
     * inside the APK, so anyone who unpacks the file can read it.
     */
    const val FLASH_PIN = "0000"
}
