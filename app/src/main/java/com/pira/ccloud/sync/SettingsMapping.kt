package com.pira.ccloud.sync

import com.pira.ccloud.data.model.FontType

/**
 * Converts the synced settings between this app's stored values and the canonical values shared
 * with the Apple app. Subtitle colors are stored here as ARGB ints, but only the palette offered in
 * Settings (which is the same on both apps) can sync; any other color stays on the device.
 */
object SettingsMapping {
    const val MIN_SEEK_INTERVAL = 5
    const val MAX_SEEK_INTERVAL = 30

    private const val OPAQUE = 0xFF000000.toInt()

    /** Text colors by canonical name, as opaque ARGB. */
    val textColors: Map<String, Int> = linkedMapOf(
        "yellow" to (OPAQUE or 0xFFFF00),
        "white" to (OPAQUE or 0xFFFFFF),
        "black" to OPAQUE,
        "red" to (OPAQUE or 0xFF0000),
        "blue" to (OPAQUE or 0x0000FF),
        "green" to (OPAQUE or 0x00FF00)
    )

    /** Subtitle backgrounds by canonical name. "glass" is half-transparent black. */
    val backgrounds: Map<String, Int> = linkedMapOf(
        "none" to 0x00000000,
        "glass" to 0x80000000.toInt(),
        "white" to (OPAQUE or 0xFFFFFF),
        "black" to OPAQUE,
        "red" to (OPAQUE or 0xFF0000),
        "blue" to (OPAQUE or 0x0000FF),
        "green" to (OPAQUE or 0x00FF00)
    )

    const val DEFAULT_TEXT_COLOR = "yellow"
    const val DEFAULT_BACKGROUND = "glass"
    const val DEFAULT_FONT = "system"
    const val DEFAULT_SEEK_INTERVAL = 10

    /** The canonical name of a text color, matched on RGB, or null if it isn't in the palette. */
    fun textColorName(argb: Int): String? =
        textColors.entries.firstOrNull { rgb(it.value) == rgb(argb) }?.key

    fun textColorArgb(name: String): Int? = textColors[name]

    /** The canonical name of a background, or null if it isn't in the palette. */
    fun backgroundName(argb: Int): String? {
        val alpha = argb ushr 24
        return when {
            alpha == 0 -> "none"
            alpha == 0xFF -> backgrounds.entries
                .firstOrNull { it.key != "none" && it.key != "glass" && rgb(it.value) == rgb(argb) }
                ?.key
            // Compose writes 50% black as alpha 128; accept any partial black as "glass"
            rgb(argb) == 0 -> "glass"
            else -> null
        }
    }

    fun backgroundArgb(name: String): Int? = backgrounds[name]

    fun fontName(type: FontType): String = when (type) {
        FontType.DEFAULT -> "system"
        FontType.VAZIRMATN -> "vazirmatn"
    }

    fun fontType(name: String): FontType? = when (name) {
        "system" -> FontType.DEFAULT
        "vazirmatn" -> FontType.VAZIRMATN
        else -> null
    }

    /** [seconds] if it's a valid seek interval, else null. */
    fun seekInterval(seconds: Int): Int? = seconds.takeIf { it in MIN_SEEK_INTERVAL..MAX_SEEK_INTERVAL }

    private fun rgb(argb: Int) = argb and 0xFFFFFF
}
