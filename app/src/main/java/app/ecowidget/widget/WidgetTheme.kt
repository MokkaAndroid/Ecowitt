package app.ecowidget.widget

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import app.ecowidget.R

/**
 * Thèmes du widget. Chaque thème a un fond normal et un fond « pluie » (bleuté) :
 * le widget bascule seul sur le second quand il pleut.
 */
enum class WidgetTheme(
    val label: String,
    @DrawableRes val background: Int,
    @DrawableRes val backgroundRain: Int,
    val isLight: Boolean,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val rain: Color,
    val warn: Color,
    val previewStart: Color,
    val previewEnd: Color,
) {
    NIGHT(
        "Nuit", R.drawable.widget_bg_night, R.drawable.widget_bg_night_rain, false,
        text = Color(0xFFF8FAFC), muted = Color(0xFF94A3B8), accent = Color(0xFF38BDF8),
        rain = Color(0xFF60A5FA), warn = Color(0xFFFBBF24),
        previewStart = Color(0xFF0B1220), previewEnd = Color(0xFF1B2740),
    ),
    OCEAN(
        "Océan", R.drawable.widget_bg_ocean, R.drawable.widget_bg_ocean_rain, false,
        text = Color(0xFFFFFFFF), muted = Color(0xFFA5C8E4), accent = Color(0xFF5EEAD4),
        rain = Color(0xFF93C5FD), warn = Color(0xFFFCD34D),
        previewStart = Color(0xFF0B3C5D), previewEnd = Color(0xFF14697A),
    ),
    AURORA(
        "Aurore", R.drawable.widget_bg_aurora, R.drawable.widget_bg_aurora_rain, false,
        text = Color(0xFFFFFFFF), muted = Color(0xFFC4B5FD), accent = Color(0xFFF0ABFC),
        rain = Color(0xFFA5B4FC), warn = Color(0xFFFCD34D),
        previewStart = Color(0xFF1E1B4B), previewEnd = Color(0xFF6D28D9),
    ),
    LIGHT(
        "Clair", R.drawable.widget_bg_light, R.drawable.widget_bg_light_rain, true,
        text = Color(0xFF0F172A), muted = Color(0xFF64748B), accent = Color(0xFF0284C7),
        rain = Color(0xFF2563EB), warn = Color(0xFFB45309),
        previewStart = Color(0xFFF8FAFC), previewEnd = Color(0xFFDCE6F2),
    ),
    GLASS(
        "Verre", R.drawable.widget_bg_glass, R.drawable.widget_bg_glass_rain, false,
        text = Color(0xFFFFFFFF), muted = Color(0xFFCBD5E1), accent = Color(0xFF7DD3FC),
        rain = Color(0xFF93C5FD), warn = Color(0xFFFCD34D),
        previewStart = Color(0xFF111827), previewEnd = Color(0xFF1F2937),
    );

    /** Fond translucide des cartes (pluie, heures) posées sur le fond du widget. */
    @get:DrawableRes
    val card: Int get() = if (isLight) R.drawable.card_light else R.drawable.card_dark

    companion object {
        fun fromName(name: String?): WidgetTheme? = entries.firstOrNull { it.name == name }
    }
}

/** Couleur qui suit la température : bleu glacé sous 0 °C, vert vers 15 °C, orange puis rouge quand il fait chaud. */
object TempColors {
    private val DARK = listOf(
        -10.0 to 0xFF93C5FD, 0.0 to 0xFF67E8F9, 8.0 to 0xFF5EEAD4, 15.0 to 0xFF86EFAC,
        22.0 to 0xFFFDE047, 28.0 to 0xFFFDBA74, 35.0 to 0xFFF87171,
    )
    private val LIGHT = listOf(
        -10.0 to 0xFF2563EB, 0.0 to 0xFF0891B2, 8.0 to 0xFF0D9488, 15.0 to 0xFF16A34A,
        22.0 to 0xFFCA8A04, 28.0 to 0xFFEA580C, 35.0 to 0xFFDC2626,
    )

    fun forTemp(t: Double?, light: Boolean, fallback: Color): Color {
        if (t == null) return fallback
        val stops = if (light) LIGHT else DARK
        if (t <= stops.first().first) return Color(stops.first().second)
        if (t >= stops.last().first) return Color(stops.last().second)
        for (i in 0 until stops.size - 1) {
            val (t0, c0) = stops[i]
            val (t1, c1) = stops[i + 1]
            if (t in t0..t1) {
                val f = ((t - t0) / (t1 - t0)).toFloat()
                return lerp(Color(c0), Color(c1), f)
            }
        }
        return fallback
    }

    private fun lerp(a: Color, b: Color, f: Float) = Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f,
    )
}
