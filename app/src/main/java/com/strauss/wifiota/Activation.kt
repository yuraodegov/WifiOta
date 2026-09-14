package com.strauss.wifiota

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ties an installation to one phone.
 *
 * The flow has no server in it, which is the point - a technician at a customer
 * site has the bar's access point and nothing else:
 *
 *   1. The app shows a device id derived from this phone, e.g. A7F3-21B9.
 *   2. The technician reads it out to whoever hands out activations.
 *   3. That person runs tools/activation-code.ps1 with the id and gets a code.
 *   4. The code is accepted on that phone and on no other, because the code is
 *      computed from the id and the id comes from the phone.
 *
 * Copying the APK to another phone therefore gets nowhere on its own: the id
 * there is different, so the code that was issued does not fit.
 *
 * What this does NOT do, and should not be described as doing: the shared
 * secret below is compiled into the APK. Anyone who unpacks the file and finds
 * it can mint codes for any device. That raises the effort from "install the
 * copied APK" to "decompile it and find the key", which is worth doing, but it
 * is a speed bump and not a lock. An installation that genuinely cannot be
 * cloned needs a server to issue the codes.
 */
object Activation {

    /**
     * Stable per-app, per-device value. Survives app updates; changes on a
     * factory reset, and differs between apps signed by different keys - so
     * moving from the debug build to the release build will produce a new id
     * and require a new code. That is expected, not a fault.
     */
    @SuppressLint("HardwareIds")
    private fun rawId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /**
     * What the technician reads out: eight characters as XXXX-XXXX.
     *
     * Crockford's base32 alphabet, which leaves out I, L, O and U so that
     * nothing in it can be misheard as a digit or as another letter. Forty bits
     * of the hash - far more than enough to tell a few hundred phones apart.
     */
    fun deviceId(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawId(context).toByteArray())
        val sb = StringBuilder()
        var buffer = 0L
        for (i in 0 until 5) buffer = (buffer shl 8) or (digest[i].toLong() and 0xFF)
        for (i in 7 downTo 0) {
            sb.append(ALPHABET[((buffer shr (i * 5)) and 0x1F).toInt()])
        }
        sb.insert(4, '-')
        return sb.toString()
    }

    /** The code that belongs to [deviceId]. Six digits. */
    fun codeFor(deviceId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.ACTIVATION_SECRET.toByteArray(), "HmacSHA256"))
        val h = mac.doFinal(deviceId.uppercase().toByteArray())
        // Truncation as in RFC 4226: take the offset from the last nibble, read
        // four bytes there, drop the sign bit. Keeps the digits evenly spread
        // instead of favouring whatever the first bytes happen to be.
        val offset = (h[h.size - 1].toInt() and 0x0F)
        val value = ((h[offset].toInt() and 0x7F) shl 24) or
            ((h[offset + 1].toInt() and 0xFF) shl 16) or
            ((h[offset + 2].toInt() and 0xFF) shl 8) or
            (h[offset + 3].toInt() and 0xFF)
        return "%06d".format(value % 1_000_000)
    }

    /** True when [entered] is the code for this phone. */
    fun accepts(context: Context, entered: String): Boolean =
        entered == codeFor(deviceId(context))

    const val CODE_LENGTH = 6

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
}
