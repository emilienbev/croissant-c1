package com.bleural.croissant

import android.content.Context
import android.provider.Settings
import com.bleural.croissant.tailscale.Root
import java.text.Normalizer

/**
 * The device's own name: Android's device_name, apart from the name on the boot screens. The
 * installer sets it (installer/croissant/devicename.py, the same rule), Settings > Device
 * renames it, and its [hostname] is the R1's name on the tailnet.
 */
object DeviceName {
    const val MAX = 24
    private const val KEY = "device_name"
    // (?U): \w takes letters beyond ASCII, as Python's does in the installer.
    private val ALLOWED = Regex("(?U)[\\w .'&+-]+")
    private val ASCII = Regex("[A-Za-z0-9]")

    /** Why [name] cannot be the device name, or null. */
    fun problem(name: String): String? = when {
        name.length !in 1..MAX -> "1 to $MAX characters"
        !ALLOWED.matches(name) -> "letters, digits, spaces or . ' & + -"
        !ASCII.containsMatchIn(name) -> "needs a letter from a to z or a digit"
        else -> null
    }

    /** [name] as a DNS label for Tailscale: "Émi's R1" is emis-r1. Accents and apostrophes drop, the rest turns to dashes. */
    fun hostname(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace("'", "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(63)

    /** The name now, or null when nothing set it (a build installed before the installer asked for it). */
    fun read(ctx: Context): String? = Settings.Global.getString(ctx.contentResolver, KEY)?.ifEmpty { null }

    /** Written as root (device_name needs WRITE_SECURE_SETTINGS); false when su refused it. Blocks: call off the main thread. */
    fun write(name: String): Boolean = Root.run("settings put global $KEY '${name.replace("'", "'\\''")}'") != null
}
