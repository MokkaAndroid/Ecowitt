package app.ecowidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ecowidget.R
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Stat
import app.ecowidget.data.Storage
import app.ecowidget.data.Tile
import app.ecowidget.data.WeatherRepository
import app.ecowidget.widget.ChartColors
import app.ecowidget.widget.DetailCharts
import app.ecowidget.widget.WeatherWidgetReceiver
import app.ecowidget.widget.WidgetRefresh
import app.ecowidget.widget.WidgetTheme
import app.ecowidget.widget.toneColor
import kotlinx.coroutines.launch

/**
 * Fiche détaillée en plein écran (mode « Tuiles + page ») : le même contenu que le détail dans le widget,
 * avec un grand graphique, toutes les valeurs et un bandeau pour passer d'un sujet à l'autre.
 */
class DetailActivity : ComponentActivity() {

    private val tile = mutableStateOf(Tile.TEMP)
    private val tab = mutableIntStateOf(0)
    private val widgetId = mutableIntStateOf(AppWidgetManager.INVALID_APPWIDGET_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        setContent {
            EcoTheme {
                DetailScreen(
                    tile = tile.value,
                    tab = tab.intValue,
                    widgetId = widgetId.intValue,
                    onTile = { tile.value = it; tab.intValue = 0 },
                    onTab = { tab.intValue = it },
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        Tile.fromId(i?.getStringExtra("tile"))?.let { tile.value = it }
        tab.intValue = i?.getStringExtra("tab")?.toIntOrNull() ?: 0
        widgetId.intValue = i?.getStringExtra("wid")?.toIntOrNull() ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }
}

private fun loadInputs(ctx: Context): DetailInputs {
    val state = WeatherRepository.cached(ctx)
    return DetailInputs(
        snap = state.snapshot,
        derived = WeatherRepository.derived(ctx, state.snapshot),
        forecast = WeatherRepository.forecast(ctx),
        altM = Storage.location(ctx)?.elevation,
    )
}

/** Thème du widget qui a ouvert la fiche (sinon celui du premier widget posé). */
private fun themeFor(ctx: Context, widgetId: Int): WidgetTheme {
    val ids = runCatching {
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WeatherWidgetReceiver::class.java))
    }.getOrDefault(IntArray(0))
    val id = if (widgetId in ids) widgetId else ids.firstOrNull()
    return if (id != null) Storage.widgetConfig(ctx, id).theme else WidgetTheme.NIGHT
}

@Composable
private fun DetailScreen(
    tile: Tile,
    tab: Int,
    widgetId: Int,
    onTile: (Tile) -> Unit,
    onTab: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by WidgetRefresh.version.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    // Recalculé à chaque actualisation du widget : la fiche reste à jour quand elle est ouverte.
    val inputs = remember(version) { loadInputs(ctx) }
    val theme = remember(widgetId) { themeFor(ctx, widgetId) }
    val colors = remember(theme) { ChartColors.of(theme) }
    val fiche = remember(tile, inputs) { DetailBuilder.fiche(tile, inputs) }
    val page = fiche.pages[tab.coerceIn(0, fiche.pages.size - 1)]
    val summaries = remember(inputs) { DetailBuilder.summaries(inputs).distinctBy { it.tile } }

    val density = LocalDensity.current.density
    val screenW = LocalConfiguration.current.screenWidthDp
    val chartWDp = (screenW - 2 * 16 - 2 * 12).coerceAtLeast(200)
    val chartHDp = 240
    val chart = remember(page, theme, chartWDp, density) {
        page.chart?.let {
            DetailCharts.render(it, (chartWDp * density).toInt(), (chartHDp * density).toInt(), density, colors)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(theme.previewStart, theme.previewEnd)))
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Barre du haut
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTap(R.drawable.ic_back, theme.text, onBack)
                Text(
                    text = tile.label,
                    color = theme.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconTap(R.drawable.ic_refresh, if (refreshing) theme.muted else theme.accent) {
                    if (!refreshing) {
                        refreshing = true
                        scope.launch {
                            WeatherRepository.refresh(ctx, force = true)
                            WidgetRefresh.all(ctx)
                            refreshing = false
                        }
                    }
                }
            }

            // Passage d'un sujet à l'autre
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                summaries.forEach { s ->
                    val selected = s.tile == tile
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) theme.text.copy(alpha = 0.16f) else theme.text.copy(alpha = 0.06f))
                            .clickable { onTile(s.tile) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(s.icon),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            colorFilter = ColorFilter.tint(if (selected) theme.accent else theme.muted),
                        )
                        Text(
                            text = s.valueText(),
                            color = if (selected) theme.text else theme.muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Onglets
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fiche.pages.forEachIndexed { k, pg ->
                    val selected = k == tab.coerceIn(0, fiche.pages.size - 1)
                    Text(
                        text = pg.tab,
                        color = if (selected) theme.accent else theme.muted,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) theme.text.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { onTab(k) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            // Valeurs
            page.stats.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { st -> StatCard(theme, st, Modifier.weight(1f)) }
                    if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                }
            }

            // Graphique
            if (chart != null) {
                Image(
                    bitmap = chart.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHDp.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(theme.text.copy(alpha = 0.06f))
                        .padding(12.dp),
                )
            }
            page.note?.let {
                Text(text = it, color = theme.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StatCard(theme: WidgetTheme, st: Stat, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(theme.text.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = st.label, color = theme.muted, fontSize = 12.sp)
        Text(
            text = st.value,
            color = toneColor(theme, st.tone),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun IconTap(icon: Int, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}
