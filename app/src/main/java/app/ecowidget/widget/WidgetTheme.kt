package app.ecowidget.widget

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import app.ecowidget.R

/** Thèmes du widget. Le fond est un drawable (dégradé + coins arrondis), les couleurs sont ici. */
enum class WidgetTheme(
    val label: String,
    @DrawableRes val background: Int,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val rain: Color,
    val warn: Color,
    val previewStart: Color,
    val previewEnd: Color,
) {
    NIGHT(
        "Nuit", R.drawable.widget_bg_night,
        text = Color(0xFFF8FAFC), muted = Color(0xFF94A3B8), accent = Color(0xFF38BDF8),
        rain = Color(0xFF60A5FA), warn = Color(0xFFFBBF24),
        previewStart = Color(0xFF0B1220), previewEnd = Color(0xFF1B2740),
    ),
    OCEAN(
        "Océan", R.drawable.widget_bg_ocean,
        text = Color(0xFFFFFFFF), muted = Color(0xFFA5C8E4), accent = Color(0xFF5EEAD4),
        rain = Color(0xFF93C5FD), warn = Color(0xFFFCD34D),
        previewStart = Color(0xFF0B3C5D), previewEnd = Color(0xFF14697A),
    ),
    AURORA(
        "Aurore", R.drawable.widget_bg_aurora,
        text = Color(0xFFFFFFFF), muted = Color(0xFFC4B5FD), accent = Color(0xFFF0ABFC),
        rain = Color(0xFFA5B4FC), warn = Color(0xFFFCD34D),
        previewStart = Color(0xFF1E1B4B), previewEnd = Color(0xFF6D28D9),
    ),
    LIGHT(
        "Clair", R.drawable.widget_bg_light,
        text = Color(0xFF0F172A), muted = Color(0xFF64748B), accent = Color(0xFF0284C7),
        rain = Color(0xFF2563EB), warn = Color(0xFFB45309),
        previewStart = Color(0xFFF8FAFC), previewEnd = Color(0xFFDCE6F2),
    ),
    GLASS(
        "Verre", R.drawable.widget_bg_glass,
        text = Color(0xFFFFFFFF), muted = Color(0xFFCBD5E1), accent = Color(0xFF7DD3FC),
        rain = Color(0xFF93C5FD), warn = Color(0xFFFCD34D),
        previewStart = Color(0xFF111827), previewEnd = Color(0xFF1F2937),
    );

    companion object {
        fun fromName(name: String?): WidgetTheme? = entries.firstOrNull { it.name == name }
    }
}
