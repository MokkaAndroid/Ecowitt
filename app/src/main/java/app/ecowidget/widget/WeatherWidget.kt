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
import app.ecowidget.data.Storage
import app.ecowidget.data.WeatherCodes
import app.ecowidget.data.WeatherRepository
import app.ecowidget.data.WeatherSnapshot
import app.ecowidget.data.WidgetConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.min

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

// Hauteurs estimées (dp) des blocs, pour décider de ce qui rentre.
private const val PAD_H = 14
private const val PAD_V = 10
private const val OM_HEADER = 30f
private const val DIVIDER = 10f
private const val CHIP_ROW = 38f
private const val HOURS_ROW = 54f
private const val BANNER_ROW = 24f
private const val MIN_HERO_BLOCK = 49f

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
            if (size.height < 100.dp) {
                Compact(p, accent, config, snap, derived, info, status, size.width)
            } else {
                Full(p, accent, config, snap, derived, info, status, size)
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
            InfoChip(p, accent, m.icon, chipValue(m, v), m.label, v.highlight, v.trend)
        }
    }
}

/** Format large : prévisions Open-Meteo en haut, station Ecowitt en bas. */
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
    val showOm = config.forecastEnabled
    val f = info.forecast

    val items = if (showOm && f != null) config.forecastMetrics.mapNotNull { OmCatalog.byId(it) } else emptyList()
    val wantHours = f != null && f.hours.isNotEmpty() && items.any { it.id == OmCatalog.NEXT_HOURS }

    // ---- Budget de hauteur ----
    var rem = size.height.value - 2f * PAD_V
    if (showOm) rem -= OM_HEADER + DIVIDER

    // Le message pluie a sa propre ligne s'il reste de la place pour une valeur principale réduite ;
    // sinon il passe en tête des infos à droite de l'en-tête.
    val bannerCandidate = items.firstOrNull { it.id == OmCatalog.RAIN_MESSAGE }
    val bannerFits = bannerCandidate != null && rem >= BANNER_ROW + MIN_HERO_BLOCK
    val banner = if (bannerFits) bannerCandidate else null
    if (bannerFits) rem -= BANNER_ROW
    val plain = listOfNotNull(if (bannerFits) null else bannerCandidate) +
        items.filter { it.id != OmCatalog.NEXT_HOURS && it.id != OmCatalog.RAIN_MESSAGE }

    val heroSp = when {
        rem >= 150f -> 48
        rem >= 95f -> 40
        rem >= 70f -> 32
        else -> 26
    }
    rem -= 16f + heroSp * 1.25f

    val columns = when {
        w >= 300f -> 3
        w >= 190f -> 2
        else -> 1
    }
    val ecoChips = config.metrics.drop(1)
    val omChips = plain.drop(1)

    val ecoRowsMax = ceil(ecoChips.size / columns.toDouble()).toInt()
    val omRowsMax = ceil(omChips.size / columns.toDouble()).toInt()
    var ecoRows = 0
    var omRows = 0
    var hours = false
    // Priorité : 1re ligne station, ligne prévisions, bandeau horaire, puis le reste des mesures station.
    if (ecoRowsMax > 0 && rem >= CHIP_ROW) { ecoRows = 1; rem -= CHIP_ROW }
    if (omRowsMax > 0 && rem >= CHIP_ROW) { omRows = 1; rem -= CHIP_ROW }
    if (wantHours && rem >= HOURS_ROW) { hours = true; rem -= HOURS_ROW }
    while (ecoRows < ecoRowsMax && rem >= CHIP_ROW) { ecoRows++; rem -= CHIP_ROW }
    while (omRows < omRowsMax && rem >= CHIP_ROW) { omRows++; rem -= CHIP_ROW }

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // ───── Haut : Open-Meteo ─────
        if (showOm) {
            OmHeader(p, accent, plain.firstOrNull(), info, snap)
            if (banner != null && f != null) RainBanner(p, accent, banner.read(f, snap))
            if (omRows > 0) ChipGrid(columns, omChips.take(omRows * columns)) { m ->
                if (f != null) {
                    val v = m.read(f, snap)
                    InfoChip(p, accent, m.icon, joinValue(v), m.shortLabel, v.highlight, null)
                }
            }
            if (hours && f != null) {
                HoursStrip(p, accent, f.hours, min(f.hours.size, ((w - 2f * PAD_H) / 44f).toInt().coerceIn(1, 7)))
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ColorProvider(p.muted.copy(alpha = 0.25f))),
            ) {}
            Spacer(modifier = GlanceModifier.height(((DIVIDER - 1f) / 2f).dp))
        }

        // ───── Bas : station Ecowitt ─────
        val hero = config.metrics.first()
        val heroValue = hero.read(snap, derived)
        val heroColor = heroColor(p, hero, heroValue, snap, derived)
        val badge = if (hero == Metric.OUTDOOR_TEMP) frostBadge(snap.outdoorTemp) else null

        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hero.label.uppercase(),
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (badge != null && w >= 220f) {
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
        Spacer(modifier = GlanceModifier.height(2.dp))

        val sparkBitmap = sparkline(hero, derived, accent, p, w, heroSp, heroValue)
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeroValue(p, heroValue, heroSp.sp, heroColor)
            if (sparkBitmap != null) {
                Spacer(modifier = GlanceModifier.width(10.dp))
                Image(
                    provider = ImageProvider(sparkBitmap.first),
                    contentDescription = null,
                    modifier = GlanceModifier.defaultWeight().height(sparkBitmap.second.dp),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }

        if (ecoRows > 0) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            ChipGrid(columns, ecoChips.take(ecoRows * columns)) { m ->
                val v = m.read(snap, derived)
                InfoChip(p, accent, m.icon, chipValue(m, v), m.label, v.highlight, v.trend)
            }
        }
    }
}

/** Courbe 24 h : seulement quand la mesure principale est la température extérieure et que la place le permet. */
@Composable
private fun sparkline(
    hero: Metric,
    derived: Derived?,
    accent: Color,
    p: WidgetTheme,
    widthDp: Float,
    heroSp: Int,
    heroValue: MetricValue,
): Pair<android.graphics.Bitmap, Float>? {
    if (hero != Metric.OUTDOOR_TEMP) return null
    val series = derived?.tempSeries ?: return null
    if (series.size < 3) return null
    // Largeur estimée de la valeur principale (chiffres + unité + flèche).
    val heroWidth = heroValue.value.length * 0.58f * heroSp + 46f
    val sparkW = widthDp - 2f * PAD_H - heroWidth - 10f
    if (sparkW < 70f) return null
    val sparkH = min(heroSp * 1.2f, 56f)
    val density = LocalContext.current.resources.displayMetrics.density
    val bmp = Sparkline.render(
        series = series,
        widthPx = (sparkW * density).toInt(),
        heightPx = (sparkH * density).toInt(),
        density = density,
        lineColor = accent.toArgb(),
        gridColor = p.muted.copy(alpha = 0.55f).toArgb(),
    ) ?: return null
    return bmp to sparkH
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

@Composable
private fun OmHeader(p: WidgetTheme, accent: Color, first: OmItem?, info: ForecastInfo, snap: WeatherSnapshot?) {
    val f = info.forecast
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (f != null) {
            val day = f.isDay ?: true
            Image(
                provider = ImageProvider(WeatherCodes.icon(f.code, day)),
                contentDescription = null,
                modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(ColorProvider(accent)),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = WeatherCodes.label(f.code, day),
                    style = TextStyle(color = ColorProvider(p.text), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    // Attribution Open-Meteo (CC BY 4.0).
                    text = "Open-Meteo" + (info.place?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = TextStyle(color = ColorProvider(p.muted), fontSize = 9.sp),
                    maxLines = 1,
                )
            }
            if (first != null) {
                val v = first.read(f, snap)
                Spacer(modifier = GlanceModifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = joinValue(v),
                        style = TextStyle(
                            color = ColorProvider(if (v.highlight) p.rain else p.text),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = first.shortLabel.uppercase(),
                        style = TextStyle(color = ColorProvider(p.muted), fontSize = 8.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
            }
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_wx_cloud),
                contentDescription = null,
                modifier = GlanceModifier.size(22.dp),
                colorFilter = ColorFilter.tint(ColorProvider(p.muted)),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = when {
                    !info.hasLocation -> "Prévisions : choisis une position dans l'app"
                    info.error != null -> "Prévisions indisponibles · ${info.error}"
                    else -> "Prévisions en cours de chargement…"
                },
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 11.sp),
                maxLines = 2,
                modifier = GlanceModifier.defaultWeight(),
            )
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
private fun HoursStrip(p: WidgetTheme, accent: Color, hours: List<HourPoint>, count: Int) {
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
                        style = TextStyle(color = ColorProvider(p.text), fontSize = 11.sp, fontWeight = FontWeight.Bold),
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
) {
    val arrow = trendGlyph(trend)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(15.dp),
            colorFilter = ColorFilter.tint(ColorProvider(if (highlight) p.rain else accent)),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = TextStyle(
                        color = ColorProvider(if (highlight) p.rain else p.text),
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
