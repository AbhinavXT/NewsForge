package com.abhinavxt.newsforge.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * A complete chrome palette: surfaces, text and accent, for one brightness.
 *
 * The ink tokens are surfaces and the chalk tokens are text, in elevation order, whichever
 * way round the brightness goes: [ink900] is always the page and [ink800] always a card,
 * so every screen reads the same in light and dark without knowing which it is in.
 *
 * Surfaces take a faint cast of the accent rather than staying neutral grey. A green accent
 * on blue-grey surfaces looks pasted on; the same accent on surfaces with a trace of green
 * in them reads as one palette.
 *
 * Accents are deliberately low in saturation. The accent marks selection and "yours", and
 * at full strength a theme stops being a tint and becomes the colour of the whole screen —
 * which also drowns the category colours, the only colour here that carries meaning.
 */
data class Palette(
    val isLight: Boolean,
    val ink950: Color,
    val ink900: Color,
    val ink800: Color,
    val ink700: Color,
    val ink600: Color,
    val ink500: Color,
    val hairline: Color,
    val chalk100: Color,
    val chalk300: Color,
    val chalk500: Color,
    val accent: Color,
    /** A soft wash of the accent: what selected chips and segments are filled with. */
    val accentDim: Color,
    /** Text and icons drawn on [accentDim]. */
    val onAccentDim: Color,
    /** The far end of the brand gradient. */
    val accentEnd: Color,
    /** Text on a solid [accent] fill, chosen per palette for contrast. */
    val onAccent: Color,
)

/** Whether the app is light, dark, or follows the phone. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun parse(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * A theme: one hue family, in a dark and a light palette.
 *
 * Every theme leaves the meaning of the category and price colours alone — an order win is
 * green in every theme — and only the shade shifts with brightness, deeper on light
 * surfaces so it still reads. What a theme changes is the room those colours sit in.
 */
enum class AppTheme(val label: String, val dark: Palette, val light: Palette) {
    OCEAN(
        label = "Ocean",
        dark = Palette(
            isLight = false,
            ink950 = Color(0xFF07090D),
            ink900 = Color(0xFF0A0D12),
            ink800 = Color(0xFF11151C),
            ink700 = Color(0xFF181D26),
            ink600 = Color(0xFF212733),
            ink500 = Color(0xFF2C3341),
            hairline = Color(0xFF1E242E),
            chalk100 = Color(0xFFECF0F6),
            chalk300 = Color(0xFFA9B4C3),
            chalk500 = Color(0xFF748191),
            accent = Color(0xFF7F9CDB),
            accentDim = Color(0xFF1B2333),
            onAccentDim = Color(0xFFB9C9EC),
            accentEnd = Color(0xFF9A8FD0),
            onAccent = Color(0xFF0B1220),
        ),
        light = Palette(
            isLight = true,
            ink950 = Color(0xFFE8ECF2),
            ink900 = Color(0xFFF3F5F9),
            ink800 = Color(0xFFFFFFFF),
            ink700 = Color(0xFFEDF0F5),
            ink600 = Color(0xFFE2E7EF),
            ink500 = Color(0xFFCBD2DD),
            hairline = Color(0xFFE0E5EC),
            chalk100 = Color(0xFF151A22),
            chalk300 = Color(0xFF4A5466),
            chalk500 = Color(0xFF666E7E),
            accent = Color(0xFF4A68A8),
            accentDim = Color(0xFFE4E9F3),
            onAccentDim = Color(0xFF2F4A82),
            accentEnd = Color(0xFF6E62A8),
            onAccent = Color(0xFFFFFFFF),
        ),
    ),

    /** Grey-green. The calm one. */
    SAGE(
        label = "Sage",
        dark = Palette(
            isLight = false,
            ink950 = Color(0xFF080A09),
            ink900 = Color(0xFF0C0F0D),
            ink800 = Color(0xFF131715),
            ink700 = Color(0xFF1A1F1C),
            ink600 = Color(0xFF232A26),
            ink500 = Color(0xFF2F3732),
            hairline = Color(0xFF1F2522),
            chalk100 = Color(0xFFE9EFEB),
            chalk300 = Color(0xFFABB7B0),
            chalk500 = Color(0xFF75827B),
            accent = Color(0xFF9DB4A5),
            accentDim = Color(0xFF1C2420),
            onAccentDim = Color(0xFFC3D3C8),
            accentEnd = Color(0xFFB9C3A2),
            onAccent = Color(0xFF0E1511),
        ),
        light = Palette(
            isLight = true,
            ink950 = Color(0xFFE8ECE9),
            ink900 = Color(0xFFF3F5F3),
            ink800 = Color(0xFFFFFFFF),
            ink700 = Color(0xFFEDF1EE),
            ink600 = Color(0xFFE2E8E4),
            ink500 = Color(0xFFCAD3CD),
            hairline = Color(0xFFE0E6E2),
            chalk100 = Color(0xFF141A16),
            chalk300 = Color(0xFF4A564F),
            chalk500 = Color(0xFF666F6A),
            accent = Color(0xFF557562),
            accentDim = Color(0xFFE4ECE6),
            onAccentDim = Color(0xFF3A5646),
            accentEnd = Color(0xFF7A8458),
            onAccent = Color(0xFFFFFFFF),
        ),
    ),

    /** Dusty lavender. */
    IRIS(
        label = "Iris",
        dark = Palette(
            isLight = false,
            ink950 = Color(0xFF09080D),
            ink900 = Color(0xFF0D0C12),
            ink800 = Color(0xFF14131B),
            ink700 = Color(0xFF1C1A24),
            ink600 = Color(0xFF26232F),
            ink500 = Color(0xFF332F3E),
            hairline = Color(0xFF23202B),
            chalk100 = Color(0xFFEFEDF6),
            chalk300 = Color(0xFFB1ACC2),
            chalk500 = Color(0xFF827D96),
            accent = Color(0xFFADA3D6),
            accentDim = Color(0xFF221F30),
            onAccentDim = Color(0xFFCFC8EA),
            accentEnd = Color(0xFFD3AFCF),
            onAccent = Color(0xFF150F24),
        ),
        light = Palette(
            isLight = true,
            ink950 = Color(0xFFECEAF2),
            ink900 = Color(0xFFF5F4F9),
            ink800 = Color(0xFFFFFFFF),
            ink700 = Color(0xFFEFEDF5),
            ink600 = Color(0xFFE5E2EE),
            ink500 = Color(0xFFCFCADC),
            hairline = Color(0xFFE4E1EB),
            chalk100 = Color(0xFF18151F),
            chalk300 = Color(0xFF534D63),
            chalk500 = Color(0xFF6E6A7E),
            accent = Color(0xFF6A5FA0),
            accentDim = Color(0xFFEAE7F4),
            onAccentDim = Color(0xFF4D4384),
            accentEnd = Color(0xFF9A5F94),
            onAccent = Color(0xFFFFFFFF),
        ),
    ),

    /** Muted teal. */
    LAGOON(
        label = "Lagoon",
        dark = Palette(
            isLight = false,
            ink950 = Color(0xFF06090A),
            ink900 = Color(0xFF0A0E0F),
            ink800 = Color(0xFF111718),
            ink700 = Color(0xFF172022),
            ink600 = Color(0xFF20292C),
            ink500 = Color(0xFF2B373A),
            hairline = Color(0xFF1C2628),
            chalk100 = Color(0xFFE8F0F1),
            chalk300 = Color(0xFFA7B8BA),
            chalk500 = Color(0xFF708386),
            accent = Color(0xFF82B9B3),
            accentDim = Color(0xFF172625),
            onAccentDim = Color(0xFFB4D6D2),
            accentEnd = Color(0xFF8CA9CF),
            onAccent = Color(0xFF071413),
        ),
        light = Palette(
            isLight = true,
            ink950 = Color(0xFFE6EDEE),
            ink900 = Color(0xFFF2F6F6),
            ink800 = Color(0xFFFFFFFF),
            ink700 = Color(0xFFEBF1F1),
            ink600 = Color(0xFFE0E8E9),
            ink500 = Color(0xFFC6D2D4),
            hairline = Color(0xFFDDE6E7),
            chalk100 = Color(0xFF121A1B),
            chalk300 = Color(0xFF475759),
            chalk500 = Color(0xFF647071),
            accent = Color(0xFF3B7570),
            accentDim = Color(0xFFE0ECEB),
            onAccentDim = Color(0xFF285A56),
            accentEnd = Color(0xFF4A6E9E),
            onAccent = Color(0xFFFFFFFF),
        ),
    ),

    /**
     * No hue at all, on neutral greys. For anyone who wants the category colours to be the
     * only colour on the screen.
     */
    GRAPHITE(
        label = "Graphite",
        dark = Palette(
            isLight = false,
            ink950 = Color(0xFF080808),
            ink900 = Color(0xFF0C0C0D),
            ink800 = Color(0xFF141415),
            ink700 = Color(0xFF1C1C1E),
            ink600 = Color(0xFF262628),
            ink500 = Color(0xFF333336),
            hairline = Color(0xFF222224),
            chalk100 = Color(0xFFEDEDEF),
            chalk300 = Color(0xFFADADB3),
            chalk500 = Color(0xFF7D7D84),
            accent = Color(0xFFC8C8CE),
            accentDim = Color(0xFF242427),
            onAccentDim = Color(0xFFE2E2E6),
            accentEnd = Color(0xFF8E8E96),
            onAccent = Color(0xFF111113),
        ),
        light = Palette(
            isLight = true,
            ink950 = Color(0xFFEBEBED),
            ink900 = Color(0xFFF5F5F6),
            ink800 = Color(0xFFFFFFFF),
            ink700 = Color(0xFFEEEEF0),
            ink600 = Color(0xFFE4E4E7),
            ink500 = Color(0xFFCDCDD2),
            hairline = Color(0xFFE2E2E5),
            chalk100 = Color(0xFF161618),
            chalk300 = Color(0xFF4F4F55),
            chalk500 = Color(0xFF6E6E73),
            accent = Color(0xFF45454C),
            accentDim = Color(0xFFE7E7EA),
            onAccentDim = Color(0xFF2E2E33),
            accentEnd = Color(0xFF77777F),
            onAccent = Color(0xFFFFFFFF),
        ),
    );

    fun palette(light: Boolean): Palette = if (light) this.light else dark

    companion object {
        val DEFAULT = OCEAN

        /** Unknown or missing names fall back to the default rather than failing. */
        fun parse(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The theme in force, as snapshot state.
 *
 * A global rather than a CompositionLocal on purpose. The palette tokens in Color.kt are
 * read from draw lambdas in the charts as well as from composables, and a CompositionLocal
 * cannot be read from a draw lambda. Snapshot state can be read from both, and both are
 * invalidated when it changes — composition recomposes, draw scopes redraw — so switching
 * theme or brightness repaints everything without restarting the activity.
 */
object ThemeState {
    var current: AppTheme by mutableStateOf(AppTheme.DEFAULT)
    var mode: ThemeMode by mutableStateOf(ThemeMode.DEFAULT)

    /** Whether the phone is in dark mode; kept current by the root theme composable. */
    var systemDark: Boolean by mutableStateOf(true)

    val isLight: Boolean
        get() = when (mode) {
            ThemeMode.LIGHT -> true
            ThemeMode.DARK -> false
            ThemeMode.SYSTEM -> !systemDark
        }

    val palette: Palette get() = current.palette(isLight)
}
