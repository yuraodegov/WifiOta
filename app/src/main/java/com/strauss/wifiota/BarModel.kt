package com.strauss.wifiota

/**
 * The bar models this app knows about.
 *
 * `get_info` reports a short code in `bar_type`. Two are confirmed against real
 * hardware: "S" (Tamar) and "P3" (Primium 3). Anything still marked UNCONFIRMED
 * is a guess and must be checked on a device before it can be trusted - a wrong
 * code here means firmware from the wrong folder gets offered for flashing.
 *
 * Never add a code "just in case": an extra entry cannot help, and it can make a
 * bar of one model match another model's folder.
 *
 * `folder` is the sub-folder name, both inside the firmware root the user picks
 * and inside the server manifest, so the two sources stay interchangeable.
 */
data class BarModel(
    val id: String,
    val name: String,
    val subtitle: String,
    val folder: String,
    val codes: List<String>,
    /**
     * Drawable resource for the device photo, or 0 when none is bundled.
     * Kept as a plain res id so BarModel stays free of Android context.
     */
    val photo: Int = 0
) {
    companion object {
        val ALL = listOf(
            BarModel(
                id = "tamar",
                name = "Tamar",
                subtitle = "Countertop",
                folder = "tamar",
                // CONFIRMED 21/08/2026: a live Tamar running ver_hmi 0.02.132
                // answered bar_type "S".
                codes = listOf("S"),
                photo = R.drawable.photo_tamar
            ),
            BarModel(
                id = "primium1",
                name = "Primium 1",
                subtitle = "Under-counter",
                folder = "primium1",
                // UNCONFIRMED: no Primium 1 has been queried yet. "P1" only
                // follows the pattern of "P3" - it has never been observed.
                codes = listOf("P1"),
                photo = R.drawable.photo_premium1
            ),
            BarModel(
                id = "primium23",
                name = "Primium 2 / 3",
                subtitle = "Under-counter",
                folder = "primium23",
                // "P3" CONFIRMED from a real device. "P2" is UNCONFIRMED and
                // assumed from the same pattern.
                codes = listOf("P2", "P3"),
                photo = R.drawable.photo_premium23
            )
        )

        /** Matches a reported bar_type to a model, ignoring case and spacing. */
        fun forBarType(barType: String?): BarModel? {
            val key = barType?.trim()?.uppercase()?.replace(" ", "") ?: return null
            return ALL.firstOrNull { m -> m.codes.any { it.uppercase() == key } }
        }
    }
}