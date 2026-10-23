package com.aktvhome.launcher.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.aktvhome.launcher.data.DayMode
import com.aktvhome.launcher.data.ThemeId
import java.time.LocalTime

/** Every color a theme paints with. Day and night variants are blended by [dayness]. */
data class ThemeColors(
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val ink: Color,
    val inkSoft: Color,
    val glow: Color,
    val accent: Color,
    val pill: Color,
    val pillInk: Color,
    val glass: Color,
    val edge: Color,
    val wave1: Color = Color.Transparent,
    val wave2: Color = Color.Transparent,
    val wave3: Color = Color.Transparent,
    /** Solid background for panels and dialogs, so their text always has full contrast. */
    val surface: Color = bgMid,
) {
    /**
     * Scenery (sky, glow, waves) blends smoothly; text and panel colors switch at the midpoint.
     * Blending those too would leave grey text on a grey panel around 6 AM and 6 PM.
     */
    fun mix(other: ThemeColors, t: Float): ThemeColors {
        val ui = if (t >= 0.5f) other else this
        return ThemeColors(
            lerp(bgTop, other.bgTop, t), lerp(bgMid, other.bgMid, t), lerp(bgBottom, other.bgBottom, t),
            ui.ink, ui.inkSoft, lerp(glow, other.glow, t),
            ui.accent, ui.pill, ui.pillInk, ui.glass, ui.edge,
            lerp(wave1, other.wave1, t), lerp(wave2, other.wave2, t), lerp(wave3, other.wave3, t),
            ui.surface,
        )
    }
}

object Palettes {

    private val kingdomDay = ThemeColors(
        bgTop = Color(0xFFE9ECFF), bgMid = Color(0xFFF6F3FF), bgBottom = Color(0xFFFFFAF3),
        ink = Color(0xFF141A3A), inkSoft = Color(0xFF141A3A).copy(alpha = 0.72f),
        glow = Color(0xFF8CAAFF).copy(alpha = 0.55f), accent = Color(0xFF5B7BFF),
        pill = Color(0xFF141A3A), pillInk = Color.White,
        glass = Color.White.copy(alpha = 0.62f), edge = Color(0xFF141A3A).copy(alpha = 0.10f),
        surface = Color(0xFFF5F6FF),
    )

    private val kingdomNight = ThemeColors(
        bgTop = Color(0xFF050A1F), bgMid = Color(0xFF0B1A3A), bgBottom = Color(0xFF0B2A3A),
        ink = Color(0xFFEEF3FF), inkSoft = Color(0xFFEEF3FF).copy(alpha = 0.72f),
        glow = Color(0xFF288CC8).copy(alpha = 0.45f), accent = Color(0xFF9FE3FF),
        pill = Color(0xFFEEF3FF), pillInk = Color(0xFF0B1A3A),
        glass = Color.White.copy(alpha = 0.07f), edge = Color.White.copy(alpha = 0.14f),
        surface = Color(0xFF0E1830),
    )

    private val tideDay = ThemeColors(
        bgTop = Color(0xFF8FD3FF), bgMid = Color(0xFFD8F1FF), bgBottom = Color(0xFFF5FBFF),
        ink = Color(0xFF06324D), inkSoft = Color(0xFF06324D).copy(alpha = 0.75f),
        glow = Color(0xFFFFECA0).copy(alpha = 0.75f), accent = Color(0xFFFFF3C4),
        pill = Color(0xFF06324D), pillInk = Color.White,
        glass = Color.White.copy(alpha = 0.30f), edge = Color.White.copy(alpha = 0.55f),
        wave1 = Color(0xFF0F78AD), wave2 = Color(0xFF2A9FD0), wave3 = Color(0xFF5CC3E6),
        surface = Color(0xFFF2F8FF),
    )

    private val tideNight = ThemeColors(
        bgTop = Color(0xFF02040F), bgMid = Color(0xFF0A1433), bgBottom = Color(0xFF142454),
        ink = Color(0xFFEAF2FF), inkSoft = Color(0xFFEAF2FF).copy(alpha = 0.72f),
        glow = Color(0xFFB4C8FF).copy(alpha = 0.45f), accent = Color(0xFFEEF2FF),
        pill = Color(0xFFEAF2FF), pillInk = Color(0xFF0A1433),
        glass = Color.White.copy(alpha = 0.08f), edge = Color.White.copy(alpha = 0.18f),
        wave1 = Color(0xFF050C22), wave2 = Color(0xFF0A1636), wave3 = Color(0xFF0F1F4A),
        surface = Color(0xFF0B1430),
    )

    fun colors(theme: ThemeId, dayness: Float): ThemeColors = when (theme) {
        ThemeId.KINGDOM -> kingdomNight.mix(kingdomDay, dayness)
        ThemeId.TIDE -> tideNight.mix(tideDay, dayness)
    }
}

/**
 * 1 = full day, 0 = full night. In Auto, day runs 6 AM to 6 PM with a one-hour blend
 * centered on each switch, so the screen eases over instead of flipping.
 */
fun dayness(mode: DayMode, now: LocalTime): Float = when (mode) {
    DayMode.DAY -> 1f
    DayMode.NIGHT -> 0f
    DayMode.AUTO -> {
        val h = now.hour + now.minute / 60f
        when {
            h < 5.5f -> 0f
            h < 6.5f -> h - 5.5f
            h < 17.5f -> 1f
            h < 18.5f -> 18.5f - h
            else -> 0f
        }
    }
}

fun greeting(now: LocalTime): String = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}
