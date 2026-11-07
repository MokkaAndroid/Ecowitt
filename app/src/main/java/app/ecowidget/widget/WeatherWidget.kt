package app.ecowidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import app.ecowidget.data.CachedState
import app.ecowidget.data.Metric
import app.ecowidget.data.MetricValue
import app.ecowidget.data.Storage
import app.ecowidget.data.WeatherRepository
import app.ecowidget.data.WeatherSnapshot
import app.ecowidget.data.WidgetConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class WeatherWidget : GlanceAppWidget() {

    // Exact : on connaît la taille réelle du widget et on adapte la mise en page.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = Storage.widgetConfig(context, appWidgetId)
        val state = WeatherRepository.cached(context)
        provideContent { WidgetBody(config, state) }
    }
}

/** Appui sur le widget = actualisation immédiate. */
class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WeatherRepository.refresh(context)
        WeatherWidget().updateAll(context)
    }
}

// ───────────────────────────── Mise en page ─────────────────────────────

private data class Status(val text: String, val warn: Boolean)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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

@Composable
private fun WidgetBody(config: WidgetConfig, state: CachedState) {
    val p = config.theme
    val size = LocalSize.current
    val snap = state.snapshot

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(p.background))
            .clickable(actionRunCallback<RefreshCallback>())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (snap == null) {
            EmptyState(p, state)
        } else {
            val status = statusOf(state, config.showTime)
            if (size.height < 100.dp) {
                Compact(p, config, snap, status, size.width)
            } else {
                Full(p, config, snap, status, size)
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

/** Format court (hauteur < 100 dp) : valeur principale à gauche, 0 à 3 mesures à droite. */
@Composable
private fun Compact(p: WidgetTheme, config: WidgetConfig, snap: WeatherSnapshot, status: Status, width: Dp) {
    val hero = config.metrics.first()
    val heroValue = hero.read(snap)
    val chipCount = ((width.value - 32f - 100f) / 72f).toInt().coerceIn(0, 3)

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
            HeroValue(p, heroValue, 30.sp)
        }
        config.metrics.drop(1).take(chipCount).forEach { m ->
            Spacer(modifier = GlanceModifier.width(12.dp))
            Chip(p, m, snap)
        }
    }
}

/** Format large : en-tête, valeur principale, puis grille de mesures selon la place disponible. */
@Composable
private fun Full(p: WidgetTheme, config: WidgetConfig, snap: WeatherSnapshot, status: Status, size: DpSize) {
    val hero = config.metrics.first()
    val heroValue = hero.read(snap)
    val heroSize = if (size.height >= 150.dp) 48.sp else 38.sp
    val columns = if (size.width >= 190.dp) 2 else 1
    val gridHeight = size.height.value - 24f - 16f - heroSize.value * 1.3f - 8f
    val rows = (gridHeight / 38f).toInt().coerceAtLeast(0)
    val shown = config.metrics.drop(1).take(rows * columns)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hero.label.uppercase(),
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp, fontWeight = FontWeight.Medium),
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
        Spacer(modifier = GlanceModifier.height(2.dp))
        HeroValue(p, heroValue, heroSize)

        if (shown.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            shown.chunked(columns).forEach { rowItems ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
                    rowItems.forEach { m ->
                        Box(modifier = GlanceModifier.defaultWeight()) { Chip(p, m, snap) }
                    }
                    if (rowItems.size < columns) {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroValue(p: WidgetTheme, v: MetricValue, base: TextUnit) {
    // Les valeurs textuelles (« En cours ») sont réduites pour tenir sur une ligne.
    val size = if (v.value.length > 6) (base.value * 0.6f).sp else base
    val unitSize = (size.value * 0.4f).coerceAtLeast(11f).sp
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = v.value,
            style = TextStyle(
                color = ColorProvider(if (v.highlight) p.rain else p.text),
                fontSize = size,
                fontWeight = FontWeight.Bold,
            ),
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
    }
}

@Composable
private fun Chip(p: WidgetTheme, m: Metric, snap: WeatherSnapshot) {
    val v = m.read(snap)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(m.icon),
            contentDescription = null,
            modifier = GlanceModifier.size(15.dp),
            colorFilter = ColorFilter.tint(ColorProvider(if (v.highlight) p.rain else p.accent)),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Column {
            Text(
                text = joinValue(v),
                style = TextStyle(
                    color = ColorProvider(if (v.highlight) p.rain else p.text),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                text = m.label.uppercase(),
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 8.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

private fun joinValue(v: MetricValue): String = when {
    v.unit.isEmpty() -> v.value
    v.unit.startsWith("°") || v.unit == "%" -> v.value + v.unit
    else -> v.value + " " + v.unit
}
