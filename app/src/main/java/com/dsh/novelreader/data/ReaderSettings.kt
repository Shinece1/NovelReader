package com.dsh.novelreader.data

import android.content.Context

/** Reading themes: background / body text / secondary text / accent. */
enum class ReaderTheme(val key: String, val bg: Int, val fg: Int, val secondary: Int, val accent: Int) {
    DAY("day", 0xFFFDFBF7.toInt(), 0xFF1E1E1E.toInt(), 0xFF8A8A8A.toInt(), 0xFF5B7C5B.toInt()),
    EYE("eye", 0xFFCBE3C3.toInt(), 0xFF22301C.toInt(), 0xFF5C6B54.toInt(), 0xFF3E6B3E.toInt()),
    SEPIA("sepia", 0xFFF3E3C6.toInt(), 0xFF463223.toInt(), 0xFF8B7355.toInt(), 0xFF8A6A3B.toInt()),
    NIGHT("night", 0xFF121212.toInt(), 0xFFBDBDBD.toInt(), 0xFF6E6E6E.toInt(), 0xFF7BA57B.toInt());

    companion object {
        fun fromKey(key: String?): ReaderTheme = values().firstOrNull { it.key == key } ?: DAY
    }
}

/** How pages are turned: tap zones or a swipe gesture (never both). */
enum class PageTurnMode(val key: String) {
    TAP("tap"),
    SWIPE("swipe");

    companion object {
        fun fromKey(key: String?): PageTurnMode = values().firstOrNull { it.key == key } ?: TAP
    }
}

/**
 * Reader typography and theme settings, persisted with SharedPreferences
 * (explicitly allowed by the spec, and enough for a handful of scalars).
 */
class ReaderSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT, 19)
        set(value) {
            prefs.edit().putInt(KEY_FONT, value.coerceIn(MIN_FONT, MAX_FONT)).apply()
        }

    var lineSpacing: Float
        get() = prefs.getFloat(KEY_SPACING, 1.6f)
        set(value) {
            prefs.edit().putFloat(KEY_SPACING, value.coerceIn(MIN_SPACING, MAX_SPACING)).apply()
        }

    var marginDp: Int
        get() = prefs.getInt(KEY_MARGIN, 22)
        set(value) {
            prefs.edit().putInt(KEY_MARGIN, value.coerceIn(MIN_MARGIN, MAX_MARGIN)).apply()
        }

    var theme: ReaderTheme
        get() = ReaderTheme.fromKey(prefs.getString(KEY_THEME, ReaderTheme.DAY.key))
        set(value) {
            prefs.edit().putString(KEY_THEME, value.key).apply()
        }

    /** true = show scanned pages / figures as images, false = text only. */
    var showImagePages: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_PAGES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IMAGE_PAGES, value).apply()
        }

    /** true = keep original paragraph spacing (高度还原), false = compact re-flow. */
    var fidelity: Boolean
        get() = prefs.getBoolean(KEY_FIDELITY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FIDELITY, value).apply()
        }

    /** Tap zones or swipe gestures; the two modes are mutually exclusive. */
    var pageTurnMode: PageTurnMode
        get() = PageTurnMode.fromKey(prefs.getString(KEY_TURN_MODE, PageTurnMode.TAP.key))
        set(value) {
            prefs.edit().putString(KEY_TURN_MODE, value.key).apply()
        }

    companion object {
        const val MIN_FONT = 14
        const val MAX_FONT = 32
        const val MIN_SPACING = 1.0f
        const val MAX_SPACING = 2.4f
        const val SPACING_STEP = 0.1f
        const val MIN_MARGIN = 8
        const val MAX_MARGIN = 44
        const val MARGIN_STEP = 4
        private const val KEY_FONT = "font_sp"
        private const val KEY_SPACING = "line_spacing"
        private const val KEY_MARGIN = "margin_dp"
        private const val KEY_THEME = "theme"
        private const val KEY_IMAGE_PAGES = "show_image_pages"
        private const val KEY_FIDELITY = "fidelity"
        private const val KEY_TURN_MODE = "page_turn_mode"
    }
}
