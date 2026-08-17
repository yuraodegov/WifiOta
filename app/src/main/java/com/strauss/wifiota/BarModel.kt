package com.strauss.wifiota

/**
 * The bar models this app knows about.
 *
 * `get_info` reports a short code in `bar_type` - the one confirmed so far is
 * "P3". Everything else in the `codes` lists is a placeholder and must be
 * corrected against real devices before relying on it: guessing wrong here
 * means firmware from the wrong folder gets offered.
 *
 * `folder` is the sub-folder name inside the firmware root the user picks once.
 */
data class BarModel(
    val id: String,
    val name: String,
    val subtitle: String,
    val folder: String,
    val codes: List<String>
) {
    companion object {
        val ALL = listOf(
            BarModel(
                id = "tamar",
                name = "Tamar",
                subtitle = "Countertop",
                folder = "tamar",
                // TODO: confirm the bar_type code(s) Tamar reports.
                codes = listOf("TAMAR", "T1")
            ),
            BarModel(
                id = "primium1",
                name = "Primium 1",
                subtitle = "Under-counter",
                folder = "primium1",
                // TODO: confirm.
                codes = listOf("P1")
            ),
            BarModel(
                id = "primium23",
                name = "Primium 2 / 3",
                subtitle = "Under-counter",
                folder = "primium23",
                // "P3" is confirmed from a real device; P2 is assumed.
                codes = listOf("P2", "P3")
            )
        )

        /** Matches a reported bar_type to a model, ignoring case and spacing. */
        fun forBarType(barType: String?): BarModel? {
            val key = barType?.trim()?.uppercase()?.replace(" ", "") ?: return null
            return ALL.firstOrNull { m -> m.codes.any { it.uppercase() == key } }
        }
    }
}