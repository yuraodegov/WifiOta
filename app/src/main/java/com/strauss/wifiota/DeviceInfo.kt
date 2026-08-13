package com.strauss.wifiota

import org.json.JSONObject

/**
 * What the bar reports about itself.
 *
 * Raw reply from `/ap_tk=tk&command=get_info`:
 *   {"bar_type":"P3","ver_hmi":"0.03.132","hardware":"",
 *    "plugins":[{"type":"fizzz","state":0,
 *                "ver_installed":"","ver_local":"00.00.403"}]}
 *
 * Note the path uses an underscore, not a question mark. With `/ap?tk=tk&...`
 * the web server answers a bare "Error" - that difference cost a lot of time,
 * so do not "fix" it back.
 */
data class DeviceInfo(
    val barType: String,
    val hmiVersion: String,
    val hardware: String,
    val plugins: List<Plugin>
) {
    data class Plugin(
        val type: String,
        val state: Int,
        /** Version running on the plugin itself; empty when it is not attached. */
        val installed: String,
        /** Version the HMI holds for that plugin. */
        val local: String
    )

    /** Installed version for a component, or null when the bar cannot tell us. */
    fun installedVersion(component: String): String? = when (component) {
        "hmi" -> hmiVersion.ifBlank { null }
        "fizzz" -> plugins.firstOrNull { it.type == "fizzz" }
            ?.let { it.installed.ifBlank { null } }
        else -> null   // RC is not reported by get_info
    }

    companion object {
        fun parse(json: String): DeviceInfo? = try {
            val o = JSONObject(json)
            val plugins = mutableListOf<Plugin>()
            o.optJSONArray("plugins")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    plugins += Plugin(
                        type = p.optString("type"),
                        state = p.optInt("state", 0),
                        installed = p.optString("ver_installed"),
                        local = p.optString("ver_local")
                    )
                }
            }
            DeviceInfo(
                barType = o.optString("bar_type"),
                hmiVersion = o.optString("ver_hmi"),
                hardware = o.optString("hardware"),
                plugins = plugins
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Compares dotted firmware versions numerically, so "0.03.132" beats "0.02.131"
 * and leading zeros in "00.00.403" do not confuse anything.
 *
 * @return negative if a < b, zero if equal, positive if a > b.
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').mapNotNull { it.trim().toIntOrNull() }
    val pb = b.split('.').mapNotNull { it.trim().toIntOrNull() }
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x - y
    }
    return 0
}
