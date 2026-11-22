package app.ecowidget.widget

import android.graphics.RectF
import app.ecowidget.data.Tile
import app.ecowidget.data.TileSummary

/** Situations qui pilotent le choix des tuiles. */
internal enum class Scn { DRY, ANNOUNCED, RAIN, WINTER, HEAT, OFFLINE, STORM }

/** Les tuiles que le widget sait dessiner. */
internal enum class BKind {
    HERO, WIND, HUM, PRESS, UV, AIR, POLLEN, HOME, TOMORROW, WEEK,
    RAIN60, RAIN12, RATE, SNOW24, SNOW_ALT, SNOW_LIM, FROST, OFFLINE, STORM, GUSTS,
    HUM_MINI, PRESS_MINI, SHORT,
}

/** Grande grille (3 colonnes), bandeau (héros bas + une rangée) ou rangée de petites tuiles. */
internal enum class BLayout { GRID, BAND, SHORT }

/** Une forme possible pour une tuile : `c` colonnes × `r` rangées. */
internal class Shape(val c: Int, val r: Int)

// ───────────────────────────── Données des tuiles ─────────────────────────────

internal class TdHero(
    val label: String,
    val value: String,
    val unit: String,
    val colorTemp: Double?,
    val feel: String?,
    val stats: List<Pair<String, String>>,
    val trendUp: Boolean?,
    val trendText: String?,
    val obs: List<Double>,
    val fc: List<Double>,
    val tint: BTint,
    val curve: Int,
    val zero: Boolean,
    val below: Int?,
    val forecastOnly: Boolean,
)

internal class TdWind(val speed: Double, val gust: Double?, val dir: Double?)
internal class TdHum(val hum: Double, val dew: Double?)
internal class TdPress(val p: Double, val delta: Double?, val series: List<Double>)
internal class TdUv(val uv: Double, val peak: String, val solar: Double?)
internal class TdAir(val label: String, val center: String, val word: String, val sub: String, val frac: Float, val color: Int)
internal class TdPollen(val rows: List<Pair<String, Int>>)
internal class TdHome(val temp: Double, val hum: Double?)
internal class TdTom(val icon: Int, val iconColor: Int, val hi: Double, val lo: Double, val desc: String, val l1: String, val l2: String)
internal class WeekDay(val label: String, val icon: Int, val iconColor: Int, val hi: Int, val lo: Int, val today: Boolean)
internal class TdWeek(val days: List<WeekDay>)
internal class TdRain60(val bins: List<Double>, val rate: Double, val word: String, val endTxt: String?, val storm: Boolean)
internal class TdRain12(
    val mm: List<Double>,
    val prob: List<Double>,
    val hours: List<String>,
    val start: String,
    val total: String,
    val end: String,
    val risk: String,
    val totalMm: Double,
    val startShort: String,
)
internal class TdRate(val rate: Double, val word: String, val cumul: String, val since: String)
internal class TdSnow24(val vals: List<Double>, val total: Double, val sub: String, val axis: List<String>)
internal class TdSnowAlt(val limit: Double, val station: Double, val iso: Double?, val sub: String, val topM: Double)
internal class TdFrost(val temps: List<Double>, val title: String, val value: String, val sub: String, val xl: List<String>)
internal class TdOffline(val last: String, val since: String)
internal class TdStorm(val minutes: Int, val whenTxt: String, val word: String, val rate: String, val gust: String)
internal class TdGusts(val vals: List<Double>, val hours: List<String>, val peak: String)
internal class TdMiniHum(val hum: Double)
internal class TdMiniPress(val p: Double, val up: Boolean?)

/** Une tuile posée sur la grille : position et taille en dp, dans le dessin. */
internal class BCell(
    val kind: BKind,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val spanC: Int,
    val spanR: Int,
    /** Fiche ouverte au toucher (null : aucune, le fond actualise). */
    val tile: Tile?,
    val tab: Int,
    val desc: String,
    val d: Any?,
    /** Format bas : le résumé de la tuile. */
    val s: TileSummary? = null,
)

/** Pastille d'alerte : texte, icône, teinte. */
internal class BentoPill(val text: String, val icon: Int, val tint: Int)

/** Texte de la pastille tel qu'il tient dans la place disponible. */
internal class PillLayout(val text: String, val size: Float, val width: Float, val height: Float)

/**
 * Mise en page du widget « Bento sombre », en dp : calculée une fois, puis servant au dessin (bitmap)
 * et aux zones cliquables posées par-dessus, pour que les deux restent alignés.
 */
internal class BentoPlan(
    val layout: BLayout,
    val scn: Scn,
    val rows: Int,
    val w: Float,
    val h: Float,
    val headerH: Float,
    val cells: List<BCell>,
    val pill: BentoPill?,
    val pillLayout: PillLayout?,
    /** Zone de la pastille, dans l'en-tête. */
    val pillRect: RectF?,
    val refresh: RectF,
    val place: String,
    val status: String,
    val warn: Boolean,
) {
    val short: Boolean get() = layout == BLayout.SHORT
}
