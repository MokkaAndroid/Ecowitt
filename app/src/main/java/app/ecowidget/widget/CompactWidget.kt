package app.ecowidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Metric
import app.ecowidget.data.Stat
import app.ecowidget.data.Storage
import app.ecowidget.data.Tile
import app.ecowidget.data.TileSummary
import app.ecowidget.data.Tone
import app.ecowidget.data.WeatherCodes
import app.ecowidget.data.WidgetConfig
import app.ecowidget.data.WidgetMode
import app.ecowidget.ui.DetailActivity
import app.ecowidget.update.DetailTimeout
import app.ecowidget.R

// Paramètres échangés avec les actions (les noms sont aussi ceux des extras de DetailActivity).
internal val KEY_TILE = ActionParameters.Key<String>("tile")
internal val KEY_TAB = ActionParameters.Key<String>("tab")
internal val KEY_WID = ActionParameters.Key<String>("wid")

/** Ouvre (ou change d'onglet, ou prolonge) le détail d'une tuile dans le widget : mode « Tuiles + détail ». */
class OpenTileCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val tile = parameters[KEY_TILE] ?: return
        val tab = parameters[KEY_TAB]?.toIntOrNull() ?: 0
        Storage.setOpenDetail(context, id, tile, tab)
        DetailTimeout.arm(context)
        WidgetRefresh.all(context)
    }
}

/** Flèche de retour : le widget revient aux tuiles. */
class CloseTileCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        Storage.setOpenDetail(context, id, null)
        WidgetRefresh.all(context)
    }
}

// Hauteurs (dp) servant à décider de la mise en page.
private const val PAD_X = 10
private const val PAD_Y = 8
private const val HEAD_H = 30f
private const val TILE_H = 52f
private const val GAP = 4f
private const val DETAIL_HEAD_H = 28f

/** Sous cette hauteur, le détail ne tient pas dans le widget : un appui ouvre la fiche plein écran. */
private const val INLINE_MIN_H = 100f

/** Couleur d'une valeur selon son ton, pour le thème du widget. */
internal fun toneColor(p: WidgetTheme, tone: Tone): Color = Color(ChartColors.of(p).of(tone))

@Composable
internal fun CompactBody(
    appWidgetId: Int,
    mode: WidgetMode,
    config: WidgetConfig,
    state: CachedState,
    inputs: DetailInputs,
    openDetail: Storage.OpenDetail?,
    place: String?,
) {
    val p = config.theme
    val snap = state.snapshot
    val raining = snap?.isRaining == true
    val size = LocalSize.current
    val inlineOk = mode == WidgetMode.TILES_INLINE && size.height.value >= INLINE_MIN_H
    val openTile = if (inlineOk) Tile.fromId(openDetail?.tile) else null

    // Détail ouvert : un appui sur le fond prolonge seulement le délai (pas de rechargement réseau).
    val rootAction: Action = if (openTile != null) {
        actionRunCallback<OpenTileCallback>(actionParametersOf(KEY_TILE to openTile.id, KEY_TAB to (openDetail?.tab ?: 0).toString()))
    } else {
        actionRunCallback<RefreshCallback>()
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(if (raining) p.backgroundRain else p.background))
            .clickable(rootAction)
            .padding(horizontal = PAD_X.dp, vertical = PAD_Y.dp),
    ) {
        if (snap == null) {
            EmptyState(p, state)
        } else if (openTile != null) {
            DetailView(appWidgetId, p, inputs, openTile, openDetail?.tab ?: 0)
        } else {
            TilesView(appWidgetId, inlineOk, config, state, inputs, place)
        }
    }
}

// ───────────────────────────── Tuiles ─────────────────────────────

@Composable
private fun TilesView(
    appWidgetId: Int,
    inlineMode: Boolean,
    config: WidgetConfig,
    state: CachedState,
    inputs: DetailInputs,
    place: String?,
) {
    val p = config.theme
    val snap = inputs.snap ?: return
    val f = inputs.forecast
    val size = LocalSize.current
    val availW = size.width.value - 2f * PAD_X
    val availH = size.height.value - 2f * PAD_Y
    val raining = snap.isRaining == true
    val accent = if (raining) p.rain else TempColors.forTemp(snap.outdoorTemp, p.isLight, p.accent)
    val status = statusOf(state, config.showTime)

    // Très bas (1 case de haut) : seulement la rangée de tuiles, sans l'en-tête.
    val showHeader = availH >= HEAD_H + GAP + TILE_H
    val tileH = if (showHeader) TILE_H else minOf(TILE_H, availH)
    val rows = if (availH >= HEAD_H + GAP + TILE_H + GAP + TILE_H) 2 else 1
    val summaries = DetailBuilder.summaries(inputs)
    val perRow = if (rows == 2) (summaries.size + 1) / 2 else (availW / 46f).toInt().coerceIn(3, summaries.size)
    val shown = summaries.take(rows * perRow)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // En-tête : météo du moment, température, lieu et heure.
        if (showHeader) Row(modifier = GlanceModifier.fillMaxWidth().height(HEAD_H.dp), verticalAlignment = Alignment.CenterVertically) {
            if (f != null) {
                Image(
                    provider = ImageProvider(WeatherCodes.icon(f.code, f.isDay ?: true)),
                    contentDescription = null,
                    modifier = GlanceModifier.size(22.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(accent)),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            Text(
                text = snap.outdoorTemp?.let { Metric.fmt(it, 1) + "°" } ?: "—",
                style = TextStyle(color = ColorProvider(accent), fontSize = 24.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = if (f != null) WeatherCodes.label(f.code, f.isDay ?: true) else "Ecowidget",
                    style = TextStyle(color = ColorProvider(p.text), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                val sub = listOfNotNull(place?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }, status.text.takeIf { it.isNotEmpty() })
                    .joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        style = TextStyle(color = ColorProvider(if (status.warn) p.warn else p.muted), fontSize = 9.sp),
                        maxLines = 1,
                    )
                }
            }
        }
        if (showHeader) Spacer(modifier = GlanceModifier.height(GAP.dp))
        shown.chunked(perRow).forEachIndexed { r, line ->
            if (r > 0) Spacer(modifier = GlanceModifier.height(GAP.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                line.forEach { s ->
                    val action: Action = if (inlineMode) {
                        actionRunCallback<OpenTileCallback>(actionParametersOf(KEY_TILE to s.tile.id, KEY_TAB to "0"))
                    } else {
                        actionStartActivity<DetailActivity>(
                            actionParametersOf(KEY_TILE to s.tile.id, KEY_TAB to "0", KEY_WID to appWidgetId.toString()),
                        )
                    }
                    TileCell(p, s, accent, action, tileH, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun TileCell(p: WidgetTheme, s: TileSummary, accent: Color, action: Action, heightDp: Float, modifier: GlanceModifier) {
    val valueColor = if (s.colorTemp != null) {
        TempColors.forTemp(s.colorTemp, p.isLight, p.text)
    } else {
        toneColor(p, s.tone)
    }
    val iconColor = if (s.tone == Tone.NORMAL) accent else toneColor(p, s.tone)
    Box(
        modifier = modifier.height(heightDp.dp).padding(horizontal = 2.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(ImageProvider(p.card)).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(s.icon),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(ColorProvider(iconColor)),
            )
            Text(
                text = s.value,
                style = TextStyle(color = ColorProvider(valueColor), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = s.label,
                style = TextStyle(color = ColorProvider(p.muted), fontSize = 8.sp),
                maxLines = 1,
            )
        }
    }
}

// ───────────────────────────── Détail dans le widget (mode 3) ─────────────────────────────

@Composable
private fun DetailView(appWidgetId: Int, p: WidgetTheme, inputs: DetailInputs, tile: Tile, tabIndex: Int) {
    val size = LocalSize.current
    val density = LocalContext.current.resources.displayMetrics.density
    val fiche = DetailBuilder.fiche(tile, inputs)
    val tab = tabIndex.coerceIn(0, fiche.pages.size - 1)
    val page = fiche.pages[tab]

    val availW = size.width.value - 2f * PAD_X
    val availH = size.height.value - 2f * PAD_Y
    val bodyH = availH - DETAIL_HEAD_H - GAP
    val statsW = if (availW < 200f) 62f else 84f
    val pad = 6f
    val chartW = availW - 2f * pad - statsW - 6f
    val chartH = bodyH - 2f * pad
    val statCount = ((bodyH - 2f * pad) / 24f).toInt().coerceIn(1, 4)

    val chart = page.chart?.let { spec ->
        DetailCharts.render(
            spec = spec,
            widthPx = (chartW * density).toInt(),
            heightPx = (chartH * density).toInt(),
            density = density,
            c = ChartColors.of(p),
        )
    }

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // En-tête : retour, sujet, onglets, agrandir.
        Row(modifier = GlanceModifier.fillMaxWidth().height(DETAIL_HEAD_H.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(R.drawable.ic_back, p.text, actionRunCallback<CloseTileCallback>())
            if (availW >= 215f) {
                Image(
                    provider = ImageProvider(tile.icon),
                    contentDescription = null,
                    modifier = GlanceModifier.size(14.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(p.accent)),
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
            fiche.pages.forEachIndexed { k, pg ->
                TabChip(
                    p = p,
                    label = pg.tab,
                    selected = k == tab,
                    action = actionRunCallback<OpenTileCallback>(actionParametersOf(KEY_TILE to tile.id, KEY_TAB to k.toString())),
                )
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            IconButton(
                R.drawable.ic_expand,
                p.muted,
                actionStartActivity<DetailActivity>(
                    actionParametersOf(KEY_TILE to tile.id, KEY_TAB to tab.toString(), KEY_WID to appWidgetId.toString()),
                ),
            )
        }
        Spacer(modifier = GlanceModifier.height(GAP.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(bodyH.dp).background(ImageProvider(p.card)).padding(pad.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.width(statsW.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                page.stats.take(statCount).forEach { st -> StatBlock(p, st) }
            }
            Spacer(modifier = GlanceModifier.width(6.dp))
            if (chart != null) {
                Image(
                    provider = ImageProvider(chart),
                    contentDescription = null,
                    modifier = GlanceModifier.defaultWeight().height(chartH.dp),
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                Text(
                    text = page.note ?: "Données indisponibles",
                    style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp),
                    maxLines = 4,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
    }
}

@Composable
private fun StatBlock(p: WidgetTheme, st: Stat) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Text(
            text = st.label,
            style = TextStyle(color = ColorProvider(p.muted), fontSize = 8.sp),
            maxLines = 1,
        )
        Text(
            text = st.value,
            style = TextStyle(color = ColorProvider(toneColor(p, st.tone)), fontSize = 12.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun TabChip(p: WidgetTheme, label: String, selected: Boolean, action: Action) {
    val base = if (selected) GlanceModifier.background(ImageProvider(p.card)) else GlanceModifier
    Box(
        modifier = base.padding(horizontal = 5.dp, vertical = 4.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(if (selected) p.accent else p.muted),
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun IconButton(icon: Int, tint: Color, action: Action) {
    Box(
        modifier = GlanceModifier.size(26.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(16.dp),
            colorFilter = ColorFilter.tint(ColorProvider(tint)),
        )
    }
}
