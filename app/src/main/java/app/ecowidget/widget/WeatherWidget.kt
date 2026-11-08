package app.ecowidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.ecowidget.R
import app.ecowidget.data.CachedState
import app.ecowidget.data.Derived
import app.ecowidget.data.Forecast
import app.ecowidget.data.HourPoint
import app.ecowidget.data.Metric
import app.ecowidget.data.MetricValue
import app.ecowidget.data.OmCatalog
import app.ecowidget.data.OmItem
import app.ecowidget.data.OmSection
import app.ecowidget.data.Storage
import app.ecowidget.data.WeatherCodes
import app.ecowidget.data.WeatherRepository
import app.ecowidget.data.WeatherSnapshot
import app.ecowidget.data.WidgetConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

class WeatherWidget : GlanceAppWidget() {

    // Exact : on connaît la taille réelle du widget et on adapte la mise en page.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = Storage.widgetConfig(context, appWidgetId)
        val state = WeatherRepository.cached(context)
        val derived = WeatherRepository.derived(context, state.snapshot)
        val info = ForecastInfo(
            forecast = if (config.forecastEnabled) WeatherRepository.forecast(context) else null,
            error = Storage.forecastError(context),
            place = Storage.location(context)?.label,
            hasLocation = Storage.location(context) != null,
        )
        provideContent { WidgetBody(config, state, derived, info) }
    }
}

/** Appui sur le widget = actualisation immédiate. */
class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WeatherRepository.refresh(context, force = true)
        WeatherWidget().updateAll(context)
    }
}

/** Flèche des prévisions : bascule entre températures corrigées de l'altitude et valeurs brutes du modèle. */
class ToggleAltitudeCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Storage.setAltitudeCorrected(context, !Storage.altitudeCorrected(context))
        WeatherWidget().updateAll(context)
    }
}

// ───────────────────────────── Modèles d'affichage ─────────────────────────────

private data class Status(val text: String, val warn: Boolean)

private data class ForecastInfo(
    val forecast: Forecast?,
    val error: String?,
    val place: String?,
    val hasLocation: Boolean,
)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H'h'")

// Hauteurs estimées (dp) des blocs, marge haute comprise, pour décider de ce qui rentre.
private const val PAD_H = 14
private const val PAD_V = 10
private const val HEADER_H = 22f
private const val HERO_MIN_WITH_WX = 62f
private const val WX_COLUMN_W = 96f
private const val NOTICE_H = 18f
private const val RAIN_LINE_H = 24f
private const val RAIN_CARD_H = 92f
private const val HOURS_STRIP_H = 54f
private const val HOURS_CURVE_H = 96f
private const val SPARK_H = 44f
private const val SPARK_DRAW_H = 38f
private const val MAX_HERO_SP = 72
private const val RAIN_BARS_H = 30f
private const val CURVE_H = 26f
private const val STATION_ROW_H = 38f
private const val FACT_ROW_H = 38f
// Glance n'affiche que 10 enfants par colonne : on plafonne le nombre de lignes de chaque bloc.
private const val MAX_STATION_ROWS = 3
private const val MAX_FACT_ROWS = 2

private fun statusOf(state: CachedState, showTime: Boolean): Status {
    val snap = state.snapshot ?: return Status("", true)
    val obsMs = snap.observedAtSec?.times(1000) ?: state.fetchedAtMs
    val ageMin = (System.currentTimeMillis() - obsMs) / 60_000
    val time = if (obsMs > 0) {
        TIME_FORMAT.format(Instant.ofEpochMilli(obsMs).atZone(ZoneId.systemDefault()))
    } else {
        "--:--"
    }
    val suffix = when {
        state.error != null -> " · hors ligne"
        ageMin > 15 -> " · ancien"
        else -> ""
    }
    val text = if (showTime || suffix.isNotEmpty()) time + suffix else ""
    return Status(text, warn = suffix.isNotEmpty())
}

/** Température (°C) qui pilote la couleur d'une mesure, ou null si la mesure n'est pas une température. */
private fun tempOf(m: Metric, s: WeatherSnapshot, d: Derived?): Double? = when (m) {
    Metric.OUTDOOR_TEMP -> s.outdoorTemp
    Metric.FEELS_LIKE -> s.feelsLike
    Metric.DEW_POINT -> s.dewPoint
    Metric.DAY_MIN -> d?.dayMin
    Metric.DAY_MAX -> d?.dayMax
    Metric.INDOOR_TEMP -> s.indoorTemp
    else -> null
}

private fun frostBadge(t: Double?): String? = when {
    t == null -> null
    t <= 0.0 -> "Gel"
    t <= 3.0 -> "Risque de gel"
    else -> null
}

private fun trendColor(p: WidgetTheme, trend: Int): Color = when {
    trend > 0 -> if (p.isLight) Color(0xFFEA580C) else Color(0xFFFDBA74)
    else -> if (p.isLight) Color(0xFF0284C7) else Color(0xFF7DD3FC)
}

private fun trendGlyph(trend: Int?): String? = when {
    trend == null || trend == 0 -> null
    trend > 0 -> "▲"
    else -> "▼"
}

private fun joinValue(v: MetricValue): String = when {
    v.unit.isEmpty() -> v.value
    v.unit.startsWith("°") || v.unit == "%" -> v.value + v.unit
    else -> v.value + " " + v.unit
}

// ───────────────────────────── Mise en page ─────────────────────────────

@Composable
private fun WidgetBody(config: WidgetConfig, state: CachedState, derived: Derived?, info: ForecastInfo) {
    val p = config.theme
    val size = LocalSize.current
    val snap = state.snapshot
    val raining = snap?.isRaining == true

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(if (raining) p.backgroundRain else p.background))
            .clickable(actionRunCallback<RefreshCallback>())
            .padding(horizontal = PAD_H.dp, vertical = PAD_V.dp),
    ) {
        if (snap == null) {
            EmptyState(p, state)
        } else {
            // L'accent suit la température ; il passe au bleu pluie quand il pleut.
            val accent = if (raining) p.rain else TempColors.forTemp(snap.outdoorTemp, p.isLight, p.accent)
            val status = statusOf(state, config.showTime)
            when {
                size.height < 100.dp -> Compact(p, accent, config, snap, derived, info, status, size.width)
                size.height.value >= SECTIONS_MIN_H -> Sections(p, accent, config, snap, derived, info, status, size)
                else -> Full(p, accent, config, snap, derived, info, status, size)
            }
        }
    }
}

@Composable
private fun EmptyState(p: WidgetTheme, state: CachedState) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "ECOWIDGET",
            style = TextStyle(color = ColorProvider(p.muted), fontSize = 9.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = state.error ?: "En attente des données…",
            style = TextStyle(color = ColorProvider(if (state.error != null) p.warn else p.text), fontSize = 12.sp),
            maxLines = 2,
        )
    }
}

/** Format court (hauteur < 100 dp) : valeur principale, météo du moment, puis quelques mesures. */
@Composable
private fun Compact(
    p: WidgetTheme,
    accent: Color,
    config: WidgetConfig,
    snap: WeatherSnapshot,
    derived: Derived?,
    info: ForecastInfo,
    status: Status,
    width: Dp,
) {
    val hero = config.metrics.first()
    val heroValue = hero.read(snap, derived)
    val heroColor = heroColor(p, hero, heroValue, snap, derived)
    val f = info.forecast
    val omItems = if (config.forecastEnabled && f != null) config.forecastMetrics.mapNotNull { OmCatalog.byId(it) } else emptyList()
    // Le message pluie est prioritaire sur le format court.
    val firstOm = omItems.firstOrNull { it.id == OmCatalog.RAIN_MESSAGE }
        ?: omItems.firstOrNull { it.id != OmCatalog.NEXT_HOURS }

    var room = width.value - 2f * PAD_H - 100f
    val showOm = config.forecastEnabled && f != null && room >= 100f
    if (showOm) room -= 100f
    val chipCount = (room / 74f).toInt().coerceIn(0, 3)

    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hero.label.uppercase(),
                style = TextStyle(
                    color = ColorProvider(if (status.warn) p.warn else p.muted),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            HeroValue(p, heroValue, 30.sp, heroColor)
        }
        if (showOm && f != null) {
            Spacer(modifier = GlanceModifier.width(8.dp))
            Image(
                provider = ImageProvider(WeatherCodes.icon(f.code, f.isDay ?: true)),
                contentDescription = null,
                modifier = GlanceModifier.size(22.dp),
                colorFilter = ColorFilter.tint(ColorProvider(accent)),
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Column {
                Text(
                    text = WeatherCodes.label(f.code, f.isDay ?: true),
                    style = TextStyle(color = ColorProvider(p.text), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                if (firstOm != null) {
                    Text(
                        text = joinValue(firstOm.read(f, snap)),
                        style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
            }
        }
        config.metrics.drop(1).take(chipCount).forEach { m ->
            Spacer(modifier = GlanceModifier.width(10.dp))
            val v = m.read(snap, derived)
            InfoChip(p, accent, m.icon, chipValue(m, v), m.label, v.highlight, v.trend, isQuiet(v))
        }
    }
}

/** Où s'affiche le message pluie, du plus discret au plus complet. */
private enum class RainMode { HIDDEN, IN_HERO, LINE, CARD }

private fun heroBlock(sp: Int, withWeather: Boolean): Float =
    maxOf(if (withWeather) HERO_MIN_WITH_WX else 0f, 12f + sp * 1.25f)

/** Intensité (mm/h) de chacune des 12 barres de 5 min de l'heure à venir. */
private fun rainBins(f: Forecast, stationRate: Double? = null): List<Double> = List(12) { i ->
    val mid = i * 5 + 2.5
    val model = f.rainSteps.firstOrNull { mid >= it.startMin && mid < it.startMin + it.minutes }?.mmPerHour ?: 0.0
    // Les premières minutes suivent la mesure de la station quand elle détecte de la pluie.
    if (i == 0 && stationRate != null && stationRate > 0.0) maxOf(model, stationRate) else model
}

/** Valeur « à zéro » : estompée pour ne pas encombrer (0 °C reste une vraie mesure). */
private fun isQuiet(v: MetricValue): Boolean {
    if (v.highlight) return false
    if (v.value == "Aucune") return true
    if (v.unit.startsWith("°")) return false
    val x = v.value.replace(',', '.').filter { !it.isWhitespace() }.toDoubleOrNull() ?: return false
    return x == 0.0
}

/**
 * Format large : le héros (mesure principale + météo du moment) en haut, le bandeau de la station en bas,
 * et entre les deux ce que la hauteur permet : pluie (ligne ou barres), heures (bandeau ou courbe), courbe 24 h,
 * infos, en-tête. La place qui reste est répartie entre les blocs plutôt que laissée en un seul grand vide.
 */
@Composable
private fun Full(
    p: WidgetTheme,
    accent: Color,
    config: WidgetConfig,
    snap: WeatherSnapshot,
    derived: Derived?,
    info: ForecastInfo,
    status: Status,
    size: DpSize,
) {
    val w = size.width.value
    val innerW = w - 2f * PAD_H
    val f = info.forecast.takeIf { config.forecastEnabled }
    val items = if (f != null) config.forecastMetrics.mapNotNull { OmCatalog.byId(it) } else emptyList()

    val rainItem = items.firstOrNull { it.id == OmCatalog.RAIN_MESSAGE }
    val rainValue = if (rainItem != null && f != null) rainItem.read(f, snap) else null
    val hasRain = rainValue != null && rainValue.value != Metric.MISSING.value
    val rainRelevant = hasRain && (snap.isRaining == true || f?.nextRainMin != null)
    // Les heures choisies passent avant les infos ; sinon elles ne viennent qu'en bonus, quand il reste de la place.
    val hoursSelected = items.any { it.id == OmCatalog.NEXT_HOURS }
    val hasHours = f != null && f.hours.size >= 2
    val wantRange = items.any { it.id == "DAY_RANGE" }
    val facts = items.filter { it.id !in SECTION_SPECIAL }
    val ecoChips = config.metrics.drop(1)
    val hero = config.metrics.first()
    val canSpark = hero == Metric.OUTDOOR_TEMP && (derived?.tempSeries?.size ?: 0) >= 3

    // ---- Budget de hauteur : le héros et une ligne de station sont obligatoires, le reste par priorité ----
    val columns = (innerW / 72f).toInt().coerceIn(2, 5)
    val factColumns = if (w >= 300f) 3 else 2
    var rem = size.height.value - 2f * PAD_V
    val notice = config.forecastEnabled && f == null
    if (notice) rem -= NOTICE_H

    var heroSp = 36
    rem -= heroBlock(heroSp, f != null)

    var stationRows = 0
    val stationRowsMax = min(MAX_STATION_ROWS, ceil(ecoChips.size / columns.toDouble()).toInt())
    if (stationRowsMax > 0) { stationRows = 1; rem -= STATION_ROW_H }

    var rainMode = if (hasRain) RainMode.IN_HERO else RainMode.HIDDEN
    if (hasRain) {
        if (rainRelevant && rem >= RAIN_CARD_H) {
            rainMode = RainMode.CARD
            rem -= RAIN_CARD_H
        } else if (rem >= RAIN_LINE_H) {
            rainMode = RainMode.LINE
            rem -= RAIN_LINE_H
        }
    }
    var hours = false
    var curve = false
    var header = false
    var spark = false
    var factRows = 0
    if (hoursSelected && hasHours && rem >= HOURS_STRIP_H) { hours = true; rem -= HOURS_STRIP_H }
    if (facts.isNotEmpty() && rem >= FACT_ROW_H) { factRows = 1; rem -= FACT_ROW_H }
    if (rem >= HEADER_H) { header = true; rem -= HEADER_H }
    if (hours && rem >= HOURS_CURVE_H - HOURS_STRIP_H) { curve = true; rem -= HOURS_CURVE_H - HOURS_STRIP_H }
    if (rainMode == RainMode.LINE && rem >= RAIN_CARD_H - RAIN_LINE_H) {
        rainMode = RainMode.CARD
        rem -= RAIN_CARD_H - RAIN_LINE_H
    }
    if (!hours && hasHours && rem >= HOURS_CURVE_H) { hours = true; curve = true; rem -= HOURS_CURVE_H }
    if (canSpark && rem >= SPARK_H) { spark = true; rem -= SPARK_H }
    while (stationRows < stationRowsMax && rem >= STATION_ROW_H) { stationRows++; rem -= STATION_ROW_H }
    val factRowsMax = min(MAX_FACT_ROWS, ceil(facts.size / factColumns.toDouble()).toInt())
    while (factRows in 1 until factRowsMax && rem >= FACT_ROW_H) { factRows++; rem -= FACT_ROW_H }
    while (heroSp < MAX_HERO_SP) {
        val extra = heroBlock(heroSp + 4, f != null) - heroBlock(heroSp, f != null)
        if (rem < extra) break
        heroSp += 4
        rem -= extra
    }
    // Ce qui reste agrandit un peu les graphiques ; le reliquat est réparti entre les blocs.
    var barsH = RAIN_BARS_H
    if (rainMode == RainMode.CARD) {
        val extra = rem.coerceIn(0f, 24f)
        barsH += extra
        rem -= extra
    }
    var curveH = CURVE_H
    if (curve) {
        val extra = rem.coerceIn(0f, 16f)
        curveH += extra
        rem -= extra
    }

    val heroValue = hero.read(snap, derived)
    val heroColor = heroColor(p, hero, heroValue, snap, derived)
    val badge = if (hero == Metric.OUTDOOR_TEMP && innerW >= 260f) frostBadge(snap.outdoorTemp) else null
    val heroLabel = hero.label.uppercase() +
        (if (!header && status.text.isNotEmpty()) " · " + status.text else "")

    // Glance n'affiche que les 10 premiers enfants d'une colonne : chaque bloc a sa propre colonne,
    // et des espaceurs élastiques répartissent la hauteur libre entre eux.
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            if (header) Header(p, shortPlace(info.place), status)
            HeroRow(
                p = p,
                accent = accent,
                heroLabel = heroLabel,
                labelWarn = !header && status.warn,
                badge = badge,
                heroValue = heroValue,
                heroColor = heroColor,
                heroSp = heroSp,
                f = f,
                wxLine = wxLine(p, f, rainMode, rainValue, wantRange),
            )
            if (notice) ForecastNotice(p, info)
            if (spark) TrendSparkline(p, accent, derived, innerW)
            if (rainMode == RainMode.LINE && rainValue != null) RainBanner(p, accent, rainValue)
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (rainMode == RainMode.CARD && rainValue != null && f != null) {
            Column(modifier = GlanceModifier.fillMaxWidth()) { RainCard(p, f, rainValue, w, barsH, snap.rainRate.takeIf { snap.isRaining == true }) }
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        if (hours && f != null) {
            val count = min(f.hours.size, (innerW / 44f).toInt().coerceIn(2, 7))
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                if (curve) HoursCurveCard(p, accent, f.hours, count, w, curveH) else HoursStrip(p, accent, f.hours, count)
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            ecoChips.take(stationRows * columns).chunked(columns).forEach { rowItems ->
                StationRow(p, rowItems, columns, snap, derived)
            }
            if (factRows > 0) {
                ChipGrid(factColumns, facts.take(factRows * factColumns)) { m ->
                    if (f != null) {
                        val v = m.read(f, snap)
                        InfoChip(p, accent, m.icon, joinValue(v), m.shortLabel, v.highlight, null, isQuiet(v))
                    }
                }
            }
        }
    }
}

/** Seule la commune s'affiche : « Moûtiers, Auvergne-Rhône-Alpes, France » devient « Moûtiers ». */
private fun shortPlace(label: String?): String? = label?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

/** Ligne sous la météo du moment : le message pluie quand il n'a pas sa place ailleurs, sinon mini · maxi. */
private fun wxLine(p: WidgetTheme, f: Forecast?, mode: RainMode, rain: MetricValue?, wantRange: Boolean): Pair<String, Color>? {
    if (f == null) return null
    if (mode == RainMode.IN_HERO && rain != null) {
        return joinValue(rain) to (if (rain.highlight) p.rain else p.muted)
    }
    val lo = f.dayMin
    val hi = f.dayMax
    if (wantRange && lo != null && hi != null) {
        return (Metric.fmt(lo, 0) + "° · " + Metric.fmt(hi, 0) + "°") to p.muted
    }
    return null
}

private fun heroColor(p: WidgetTheme, m: Metric, v: MetricValue, s: WeatherSnapshot, d: Derived?): Color {
    if (v.highlight) return p.rain
    val t = tempOf(m, s, d) ?: return p.text
    return TempColors.forTemp(t, p.isLight, p.text)
}

/** Dans une pastille, « Pluie en cours » reste court : l'intensité est visible ailleurs. */
private fun chipValue(m: Metric, v: MetricValue): String =
    if (m == Metric.RAIN_NOW) v.value else joinValue(v)

// ───────────────────────────── Blocs ─────────────────────────────

// ───────────────────────────── Format « sections » (grands widgets) ─────────────────────────────

// Hauteurs estimées (dp) des blocs du format « sections », marge basse comprise.
private const val SECTIONS_MIN_H = 270f
private const val S_HEADER_H = 42f
private const val S_FCARD_H = 78f
private const val S_HOURS_H = 72f
private const val S_TILE_ROW_H = 54f
private const val S_STATION_BASE = 116f
private const val S_MAX_HERO_SP = 56
private const val S_MAX_STATION_ROWS = 2
private const val S_MAX_OM_ROWS = 3

private const val APPARENT_ID = "current.apparent_temperature"

/** Infos Open-Meteo qui ont leur place dédiée (carte prévisions, alerte pluie) et ne sont donc pas des tuiles. */
private val SECTION_SPECIAL = setOf(OmCatalog.RAIN_MESSAGE, OmCatalog.NEXT_HOURS, "DAY_RANGE", APPARENT_ID)

private data class Tile(
    val icon: Int,
    val label: String,
    val value: String,
    val unit: String,
    val highlight: Boolean,
    val quiet: Boolean,
    val trend: Int?,
)

private fun tileOf(icon: Int, label: String, v: MetricValue) =
    Tile(icon, label, v.value, v.unit, v.highlight, isQuiet(v), v.trend)

/** « Ressenti 9° · +1,2° / h » sous la température de la station. */
private fun heroSub(hero: Metric, snap: WeatherSnapshot, derived: Derived?): String? {
    if (hero != Metric.OUTDOOR_TEMP) return null
    val parts = ArrayList<String>()
    snap.feelsLike?.let { parts.add("Ressenti " + Metric.fmt(it, 0) + "°") }
    val trend = derived?.tempTrend1h
    if (trend != null && abs(trend) >= 0.2) {
        parts.add((if (trend > 0) "+" else "−") + Metric.fmt(abs(trend), 1) + "° / h")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Format « sections » : en-tête, carte prévisions (météo, ressenti, mini/maxi, heures), tuiles Open-Meteo,
 * carte station (mesure principale, courbe 24 h, tuiles). La hauteur libre est répartie entre les blocs.
 */
@Composable
private fun Sections(
    p: WidgetTheme,
    accent: Color,
    config: WidgetConfig,
    snap: WeatherSnapshot,
    derived: Derived?,
    info: ForecastInfo,
    status: Status,
    size: DpSize,
) {
    val w = size.width.value
    val innerW = w - 2f * PAD_H
    val f = info.forecast.takeIf { config.forecastEnabled }
    val items = if (f != null) config.forecastMetrics.mapNotNull { OmCatalog.byId(it) } else emptyList()

    val rainItem = items.firstOrNull { it.id == OmCatalog.RAIN_MESSAGE }
    val rainValue = if (rainItem != null && f != null) rainItem.read(f, snap) else null
    // Le message pluie ne s'affiche en carte (avec barres) que s'il y a de la pluie en cours ou prévue ;
    // sinon la tuile « Prochaine pluie » suffit.
    val rainAlert = rainValue != null && rainValue.value != Metric.MISSING.value &&
        (snap.isRaining == true || f?.nextRainMin != null)
    val wantHours = f != null && f.hours.size >= 2 && items.any { it.id == OmCatalog.NEXT_HOURS }
    val showRange = items.any { it.id == "DAY_RANGE" }
    val showFeels = items.any { it.id == APPARENT_ID }
    val omTiles = if (f != null) {
        items.filter { it.id !in SECTION_SPECIAL }.map { tileOf(it.icon, it.shortLabel, it.read(f, snap)) }
    } else {
        emptyList()
    }
    val hero = config.metrics.first()
    val ecoTiles = config.metrics.drop(1).map { m -> tileOf(m.icon, m.label, m.read(snap, derived)) }
    val columns = if (innerW >= 290f) 3 else 2

    // ---- Budget de hauteur : la carte station est obligatoire, le reste par priorité ----
    var rem = size.height.value - 2f * PAD_V
    val notice = config.forecastEnabled && f == null
    if (notice) rem -= NOTICE_H
    var heroSp = 40
    rem -= S_STATION_BASE

    var alert = false
    var fcard = false
    var hours = false
    var header = false
    var stationRows = 0
    var omRows = 0
    val stationMaxRows = min(S_MAX_STATION_ROWS, ceil(ecoTiles.size / columns.toDouble()).toInt())
    val omMaxRows = min(S_MAX_OM_ROWS, ceil(omTiles.size / columns.toDouble()).toInt())
    if (f != null && rainAlert && rem >= RAIN_CARD_H) { alert = true; rem -= RAIN_CARD_H }
    if (f != null && rem >= S_FCARD_H) { fcard = true; rem -= S_FCARD_H }
    if (stationMaxRows > 0 && rem >= S_TILE_ROW_H) { stationRows = 1; rem -= S_TILE_ROW_H }
    if (omMaxRows > 0 && rem >= S_TILE_ROW_H) { omRows = 1; rem -= S_TILE_ROW_H }
    if (fcard && wantHours && rem >= S_HOURS_H) { hours = true; rem -= S_HOURS_H }
    if (rem >= S_HEADER_H) { header = true; rem -= S_HEADER_H }
    while (stationRows in 1 until stationMaxRows && rem >= S_TILE_ROW_H) { stationRows++; rem -= S_TILE_ROW_H }
    while (omRows in 1 until omMaxRows && rem >= S_TILE_ROW_H) { omRows++; rem -= S_TILE_ROW_H }
    while (heroSp < S_MAX_HERO_SP && rem >= 5f) { heroSp += 4; rem -= 5f }

    val heroValue = hero.read(snap, derived)
    val heroColor = heroColor(p, hero, heroValue, snap, derived)
    val lo = f?.dayMin
    val hi = f?.dayMax
    val range = if (showRange && lo != null && hi != null) {
        (Metric.fmt(hi, 0) + "°") to (Metric.fmt(lo, 0) + "°")
    } else {
        null
    }
    val feels = if (showFeels) {
        f?.raw?.number(OmSection.CURRENT, "apparent_temperature")?.let { "Ressenti " + Metric.fmt(it, 0) + "°" }
    } else {
        null
    }

    // Glance n'affiche que 10 enfants par colonne : un bloc = une colonne, et des espaceurs élastiques
    // répartissent la hauteur libre entre eux.
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (header || alert || notice) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                if (header) SectionsHeader(p, shortPlace(info.place))
                if (notice) ForecastNotice(p, info)
                if (alert && rainValue != null && f != null) RainCard(p, f, rainValue, w, RAIN_BARS_H, snap.rainRate.takeIf { snap.isRaining == true })
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        if (fcard && f != null) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                ForecastCard(p, accent, f, feels, range, hours, innerW)
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        if (omRows > 0) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                TileRows(p, accent, omTiles.take(omRows * columns), columns)
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            StationCard(
                p = p,
                accent = accent,
                hero = hero,
                heroValue = heroValue,
                heroColor = heroColor,
                heroSp = heroSp,
                sub = heroSub(hero, snap, derived),
                badge = if (hero == Metric.OUTDOOR_TEMP && innerW >= 260f) frostBadge(snap.outdoorTemp) else null,
                status = status,
                derived = derived,
                innerW = innerW,
                tiles = ecoTiles.take(stationRows * columns),
                columns = columns,
            )
        }
    }
}

@Composable
private fun SectionsHeader(p: WidgetTheme, place: String?) {
    val date = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(LocalDate.now())
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = place ?: "Ecowidget",
                style = TextStyle(color = ColorProvider(p.text), fontSize = 17.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = date,
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = null,
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(ColorProvider(p.muted)),
        )
    }
}

/** Carte prévisions : météo du moment, ressenti, maxi / mini, puis les prochaines heures. */
@Composable
private fun ForecastCard(
    p: WidgetTheme,
    accent: Color,
    f: Forecast,
    feels: String?,
    range: Pair<String, String>?,
    hours: Boolean,
    innerW: Float,
) {
    val day = f.isDay ?: true
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(ImageProvider(p.card))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(WeatherCodes.icon(f.code, day)),
                contentDescription = null,
                modifier = GlanceModifier.size(36.dp),
                colorFilter = ColorFilter.tint(ColorProvider(accent)),
            )
            Spacer(modifier = GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = WeatherCodes.label(f.code, day),
                    style = TextStyle(color = ColorProvider(p.text), fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                if (feels != null) {
                    Text(
                        text = feels,
                        style = TextStyle(color = ColorProvider(p.muted), fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
            if (range != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = range.first,
                            style = TextStyle(color = ColorProvider(p.text), fontSize = 22.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                        Text(
                            text = " / " + range.second,
                            style = TextStyle(color = ColorProvider(p.muted), fontSize = 15.sp),
                            maxLines = 1,
                        )
                    }
                    val alt = f.altitude?.takeIf { abs(it.offsetC) >= 0.1 }
                    if (alt != null) {
                        // Flèche : corrigé de l'altitude (accent) <-> valeur du modèle (grisé).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = GlanceModifier
                                .padding(vertical = 2.dp)
                                .clickable(actionRunCallback<ToggleAltitudeCallback>()),
                        ) {
                            Text(
                                text = if (alt.applied) "max / min · ${alt.userM.roundToInt()} m" else "max / min · modèle",
                                style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp),
                                maxLines = 1,
                            )
                            Spacer(modifier = GlanceModifier.width(4.dp))
                            Image(
                                provider = ImageProvider(R.drawable.ic_swap),
                                contentDescription = "Basculer entre la température corrigée de l'altitude et celle du modèle",
                                modifier = GlanceModifier.size(12.dp),
                                colorFilter = ColorFilter.tint(ColorProvider(if (alt.applied) accent else p.muted)),
                            )
                        }
                    } else {
                        Text(
                            text = "max / min",
                            style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (hours) {
            Box(modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp)) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ColorProvider(p.muted.copy(alpha = 0.25f))),
                ) {}
            }
            HoursStrip(p, accent, f.hours, min(f.hours.size, ((innerW - 24f) / 44f).toInt().coerceIn(2, 7)), highlightMax = true)
        }
    }
}

/** Carte station : étiquette et heure, mesure principale avec sa courbe 24 h, puis les tuiles. */
@Composable
private fun StationCard(
    p: WidgetTheme,
    accent: Color,
    hero: Metric,
    heroValue: MetricValue,
    heroColor: Color,
    heroSp: Int,
    sub: String?,
    badge: String?,
    status: Status,
    derived: Derived?,
    innerW: Float,
    tiles: List<Tile>,
    columns: Int,
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val series = derived?.tempSeries
    val heroWidth = heroValue.value.length * 0.58f * heroSp + 46f
    val sparkW = min(170f, innerW - 24f - heroWidth - 12f)
    val sparkH = min(heroSp * 1.2f, 52f)
    val spark = if (hero == Metric.OUTDOOR_TEMP && series != null && sparkW >= 70f) {
        Sparkline.render(
            series = series,
            widthPx = (sparkW * density).toInt(),
            heightPx = (sparkH * density).toInt(),
            density = density,
            lineColor = accent.toArgb(),
            gridColor = p.muted.copy(alpha = 0.55f).toArgb(),
        )
    } else {
        null
    }
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(ImageProvider(p.card))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "STATION ECOWITT" + if (hero != Metric.OUTDOOR_TEMP) " · " + hero.label.uppercase() else "",
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (badge != null) {
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = badge,
                    style = TextStyle(color = ColorProvider(p.accent), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = GlanceModifier
                        .background(ImageProvider(R.drawable.badge_frost))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (status.text.isNotEmpty()) {
                Text(
                    text = status.text,
                    style = TextStyle(color = ColorProvider(if (status.warn) p.warn else p.muted), fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                HeroValue(p, heroValue, heroSp.sp, heroColor)
                if (sub != null) {
                    Text(
                        text = sub,
                        style = TextStyle(color = ColorProvider(p.muted), fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
            if (spark != null) {
                Spacer(modifier = GlanceModifier.width(12.dp))
                Image(
                    provider = ImageProvider(spark),
                    contentDescription = null,
                    modifier = GlanceModifier.width(sparkW.dp).height(sparkH.dp),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        if (tiles.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            TileRows(p, accent, tiles, columns)
        }
    }
}

@Composable
private fun TileRows(p: WidgetTheme, accent: Color, tiles: List<Tile>, columns: Int) {
    tiles.chunked(columns).forEach { rowTiles ->
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            rowTiles.forEach { t ->
                Box(modifier = GlanceModifier.defaultWeight().padding(2.dp)) { TileCell(p, accent, t) }
            }
            repeat(columns - rowTiles.size) {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
        }
    }
}

/** Une tuile : icône et libellé en haut, valeur en dessous (estompée quand elle est à zéro). */
@Composable
private fun TileCell(p: WidgetTheme, accent: Color, t: Tile) {
    val arrow = trendGlyph(t.trend)
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(ImageProvider(p.card))
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(t.icon),
                contentDescription = null,
                modifier = GlanceModifier.size(12.dp),
                colorFilter = ColorFilter.tint(ColorProvider(if (t.highlight) p.rain else if (t.quiet) p.muted else accent)),
            )
            Spacer(modifier = GlanceModifier.width(4.dp))
            Text(
                text = t.label,
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 9.sp),
                maxLines = 1,
            )
        }
        Spacer(modifier = GlanceModifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = t.value,
                style = TextStyle(
                    color = ColorProvider(if (t.highlight) p.rain else if (t.quiet) p.muted else p.text),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (t.unit.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.width(3.dp))
                Text(
                    text = t.unit,
                    style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp),
                    maxLines = 1,
                )
            }
            if (arrow != null && t.trend != null) {
                Spacer(modifier = GlanceModifier.width(3.dp))
                Text(
                    text = arrow,
                    style = TextStyle(color = ColorProvider(trendColor(p, t.trend)), fontSize = 9.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Header(p: WidgetTheme, place: String?, status: Status) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = place?.takeIf { it.isNotBlank() } ?: "Ecowidget",
            style = TextStyle(color = ColorProvider(p.text), fontSize = 13.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (status.text.isNotEmpty()) {
            Text(
                text = status.text,
                style = TextStyle(color = ColorProvider(if (status.warn) p.warn else p.muted), fontSize = 10.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroRow(
    p: WidgetTheme,
    accent: Color,
    heroLabel: String,
    labelWarn: Boolean,
    badge: String?,
    heroValue: MetricValue,
    heroColor: Color,
    heroSp: Int,
    f: Forecast?,
    wxLine: Pair<String, Color>?,
) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = heroLabel,
                    style = TextStyle(
                        color = ColorProvider(if (labelWarn) p.warn else p.muted),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                if (badge != null) {
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = badge,
                        style = TextStyle(color = ColorProvider(p.accent), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier
                            .background(ImageProvider(R.drawable.badge_frost))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            HeroValue(p, heroValue, heroSp.sp, heroColor)
        }
        if (f != null) WeatherColumn(p, accent, f, wxLine, (heroSp * 0.7f).toInt().coerceIn(26, 48))
    }
}

/** Météo du moment (Open-Meteo) : icône, libellé et une ligne d'appoint. */
@Composable
private fun WeatherColumn(p: WidgetTheme, accent: Color, f: Forecast, line: Pair<String, Color>?, iconDp: Int) {
    val day = f.isDay ?: true
    Column(horizontalAlignment = Alignment.End) {
        Image(
            provider = ImageProvider(WeatherCodes.icon(f.code, day)),
            contentDescription = null,
            modifier = GlanceModifier.size(iconDp.dp),
            colorFilter = ColorFilter.tint(ColorProvider(accent)),
        )
        val alt = f.altitude?.takeIf { abs(it.offsetC) >= 0.1 }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (alt != null) {
                GlanceModifier.padding(vertical = 2.dp).clickable(actionRunCallback<ToggleAltitudeCallback>())
            } else {
                GlanceModifier
            },
        ) {
            Text(
                text = WeatherCodes.label(f.code, day),
                style = TextStyle(
                    color = ColorProvider(p.text),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
            if (alt != null) {
                // Flèche : accent = température corrigée de l'altitude, grisé = valeur du modèle.
                Spacer(modifier = GlanceModifier.width(4.dp))
                Image(
                    provider = ImageProvider(R.drawable.ic_swap),
                    contentDescription = "Basculer entre la température corrigée de l'altitude et celle du modèle",
                    modifier = GlanceModifier.size(12.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(if (alt.applied) accent else p.muted)),
                )
            }
        }
        if (line != null) {
            Text(
                text = line.first,
                style = TextStyle(color = ColorProvider(line.second), fontSize = 10.sp, textAlign = TextAlign.End),
                maxLines = 2,
            )
        }
    }
}

/** Courbe de température des 24 dernières heures, sous le héros, sur toute la largeur. */
@Composable
private fun TrendSparkline(p: WidgetTheme, accent: Color, derived: Derived?, innerW: Float) {
    val series = derived?.tempSeries
    if (series != null) {
        val density = LocalContext.current.resources.displayMetrics.density
        val bmp = Sparkline.render(
            series = series,
            widthPx = (innerW * density).toInt(),
            heightPx = (SPARK_DRAW_H * density).toInt(),
            density = density,
            lineColor = accent.toArgb(),
            gridColor = p.muted.copy(alpha = 0.55f).toArgb(),
        )
        if (bmp != null) {
            Box(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
                Image(
                    provider = ImageProvider(bmp),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxWidth().height(SPARK_DRAW_H.dp),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
}

/** Quand les prévisions manquent : une ligne qui dit pourquoi, au lieu d'un trou. */
@Composable
private fun ForecastNotice(p: WidgetTheme, info: ForecastInfo) {
    Text(
        text = when {
            !info.hasLocation -> "Prévisions : choisis une position dans l'app"
            info.error != null -> "Prévisions indisponibles · ${info.error}"
            else -> "Prévisions en cours de chargement…"
        },
        style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp),
        maxLines = 1,
        modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** Carte pluie : message + barres de 5 min sur l'heure à venir. */
@Composable
private fun RainCard(p: WidgetTheme, f: Forecast, v: MetricValue, widthDp: Float, barsH: Float, stationRate: Double? = null) {
    val density = LocalContext.current.resources.displayMetrics.density
    val barsW = widthDp - 2f * PAD_H - 20f
    val bars = RainBars.render(
        bins = rainBins(f, stationRate),
        widthPx = (barsW * density).toInt(),
        heightPx = (barsH * density).toInt(),
        density = density,
        barColor = p.rain.toArgb(),
        idleColor = p.muted.copy(alpha = 0.35f).toArgb(),
    )
    Box(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ImageProvider(p.card))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(R.drawable.ic_cloud_rain),
                    contentDescription = null,
                    modifier = GlanceModifier.size(14.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(if (v.highlight) p.rain else p.muted)),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = joinValue(v),
                    style = TextStyle(
                        color = ColorProvider(if (v.highlight) p.rain else p.text),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    text = f.nowcastSource,
                    style = TextStyle(color = ColorProvider(p.muted), fontSize = 9.sp),
                    maxLines = 1,
                )
            }
            if (bars != null) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                Image(
                    provider = ImageProvider(bars),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxWidth().height(barsH.dp),
                    contentScale = ContentScale.FillBounds,
                )
                Spacer(modifier = GlanceModifier.height(3.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    val axis = TextStyle(color = ColorProvider(p.muted), fontSize = 8.sp)
                    Text(text = "maintenant", style = axis, maxLines = 1)
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(text = "+30 min", style = axis, maxLines = 1)
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(text = "+1 h", style = axis, maxLines = 1)
                }
            }
        }
    }
}

/** Prochaines heures : icône et température, la courbe au-dessous, puis l'heure. */
@Composable
private fun HoursCurveCard(p: WidgetTheme, accent: Color, hours: List<HourPoint>, count: Int, widthDp: Float, curveH: Float) {
    val shown = hours.take(count)
    val density = LocalContext.current.resources.displayMetrics.density
    val temps = shown.mapNotNull { it.temp }
    val curve = if (temps.size == shown.size) {
        HoursCurve.render(
            temps = temps,
            widthPx = ((widthDp - 2f * PAD_H - 20f) * density).toInt(),
            heightPx = (curveH * density).toInt(),
            density = density,
            lineColor = accent.toArgb(),
        )
    } else {
        null
    }
    Box(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ImageProvider(p.card))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                shown.forEach { h ->
                    Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                provider = ImageProvider(WeatherCodes.icon(h.code, h.isDay)),
                                contentDescription = null,
                                modifier = GlanceModifier.size(16.dp),
                                colorFilter = ColorFilter.tint(ColorProvider(accent)),
                            )
                            Text(
                                text = h.temp?.let { Metric.fmt(it, 0) + "°" } ?: "—",
                                style = TextStyle(color = ColorProvider(p.text), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (curve != null) {
                Image(
                    provider = ImageProvider(curve),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxWidth().height(curveH.dp),
                    contentScale = ContentScale.FillBounds,
                )
            }
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                shown.forEach { h ->
                    Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                        Text(
                            text = HOUR_FORMAT.format(Instant.ofEpochSecond(h.tSec).atZone(ZoneId.systemDefault())),
                            style = TextStyle(color = ColorProvider(p.muted), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Une ligne de mesures de la station : étiquette au-dessus, valeur dessous, séparées par un filet. */
@Composable
private fun StationRow(p: WidgetTheme, metrics: List<Metric>, columns: Int, snap: WeatherSnapshot, derived: Derived?) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metrics.forEachIndexed { i, m ->
            if (i > 0) {
                Box(
                    modifier = GlanceModifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(ColorProvider(p.muted.copy(alpha = 0.25f))),
                ) {}
            }
            val v = m.read(snap, derived)
            val arrow = trendGlyph(v.trend)
            Column(modifier = GlanceModifier.defaultWeight().padding(start = if (i > 0) 8.dp else 0.dp)) {
                Text(
                    text = m.label.uppercase(),
                    style = TextStyle(color = ColorProvider(p.muted), fontSize = 8.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chipValue(m, v),
                        style = TextStyle(
                            color = ColorProvider(
                                when {
                                    v.highlight -> p.rain
                                    isQuiet(v) -> p.muted
                                    else -> p.text
                                },
                            ),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    if (arrow != null && v.trend != null) {
                        Spacer(modifier = GlanceModifier.width(3.dp))
                        Text(
                            text = arrow,
                            style = TextStyle(color = ColorProvider(trendColor(p, v.trend)), fontSize = 9.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        repeat(columns - metrics.size) {
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun RainBanner(p: WidgetTheme, accent: Color, v: MetricValue) {
    if (v.value != Metric.MISSING.value) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_cloud_rain),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(ColorProvider(if (v.highlight) p.rain else accent)),
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = joinValue(v),
                style = TextStyle(
                    color = ColorProvider(if (v.highlight) p.rain else p.text),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun <T> ChipGrid(columns: Int, items: List<T>, chip: @Composable (T) -> Unit) {
    items.chunked(columns).forEach { rowItems ->
        Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
            rowItems.forEach { item ->
                Box(modifier = GlanceModifier.defaultWeight()) { chip(item) }
            }
            repeat(columns - rowItems.size) {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun HoursStrip(p: WidgetTheme, accent: Color, hours: List<HourPoint>, count: Int, highlightMax: Boolean = false) {
    val hot = if (highlightMax) hours.take(count).mapNotNull { it.temp }.maxOrNull() else null
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        hours.take(count).forEach { h ->
            Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = HOUR_FORMAT.format(Instant.ofEpochSecond(h.tSec).atZone(ZoneId.systemDefault())),
                        style = TextStyle(color = ColorProvider(p.muted), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    Image(
                        provider = ImageProvider(WeatherCodes.icon(h.code, h.isDay)),
                        contentDescription = null,
                        modifier = GlanceModifier.size(16.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(accent)),
                    )
                    Text(
                        text = h.temp?.let { Metric.fmt(it, 0) + "°" } ?: "—",
                        style = TextStyle(
                            color = ColorProvider(if (hot != null && h.temp == hot) accent else p.text),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroValue(p: WidgetTheme, v: MetricValue, base: TextUnit, color: Color) {
    // Les valeurs textuelles (« En cours ») sont réduites pour tenir sur une ligne.
    val size = if (v.value.length > 6) (base.value * 0.6f).sp else base
    val unitSize = (size.value * 0.4f).coerceAtLeast(11f).sp
    val arrow = trendGlyph(v.trend)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = v.value,
            style = TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (v.unit.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.width(3.dp))
            Text(
                text = v.unit,
                style = TextStyle(color = ColorProvider(p.muted), fontSize = unitSize, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        if (arrow != null && v.trend != null) {
            Spacer(modifier = GlanceModifier.width(4.dp))
            Text(
                text = arrow,
                style = TextStyle(color = ColorProvider(trendColor(p, v.trend)), fontSize = unitSize),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun InfoChip(
    p: WidgetTheme,
    accent: Color,
    icon: Int,
    value: String,
    label: String,
    highlight: Boolean,
    trend: Int?,
    dim: Boolean = false,
) {
    val arrow = trendGlyph(trend)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(15.dp),
            colorFilter = ColorFilter.tint(ColorProvider(if (highlight) p.rain else if (dim) p.muted else accent)),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = TextStyle(
                        color = ColorProvider(if (highlight) p.rain else if (dim) p.muted else p.text),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                if (arrow != null && trend != null) {
                    Spacer(modifier = GlanceModifier.width(3.dp))
                    Text(
                        text = arrow,
                        style = TextStyle(color = ColorProvider(trendColor(p, trend)), fontSize = 9.sp),
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = label.uppercase(),
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 8.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}
