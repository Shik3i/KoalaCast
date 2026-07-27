package net.koalastuff.koalacast.core.model

/**
 * The nine colour palettes, matching the web client one for one — same ids, same
 * order, same default. The colour values themselves live in `core:ui`, generated
 * from the web client's stylesheet so the two cannot drift apart.
 */
enum class PaletteId(val id: String) {
    EUCALYPTUS("eucalyptus"),
    FJORD("fjord"),
    EMBER("ember"),
    LAVENDER("lavender"),
    AURORA("aurora"),
    SANDSTONE("sandstone"),
    OBSIDIAN("obsidian"),
    PAPER("paper"),
    ULTRAVIOLET("ultraviolet"),
    ;

    companion object {
        /** Same default as the web client. */
        val DEFAULT = FJORD

        /** Tolerant of anything DataStore hands back, including a removed id. */
        fun fromId(value: String?): PaletteId =
            entries.firstOrNull { it.id == value } ?: DEFAULT
    }
}
