package com.dividendstream.app.core

/**
 * Which colour scheme the person has chosen.
 *
 * [System] is a real third option rather than a default in disguise: somebody who switches
 * their phone to light at sunrise expects this app to come with it, and collapsing that into
 * "light" the first time they open the setting would quietly break it.
 */
enum class ThemePreference(val key: String, val label: String) {
    System("system", "Follow system"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    /** Resolves to an actual scheme, given what the operating system currently says. */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        System -> systemIsDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** An unrecognised stored value is [System] rather than a crash on launch. */
        fun of(key: String?): ThemePreference =
            entries.firstOrNull { it.key == key } ?: System
    }
}
