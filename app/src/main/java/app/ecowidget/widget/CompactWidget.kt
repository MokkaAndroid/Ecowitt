package app.ecowidget.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import app.ecowidget.data.Alert
import app.ecowidget.data.CachedState
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Stat
import app.ecowidget.data.Storage
import app.ecowidget.data.Tile
import app.ecowidget.data.TileSummary
import app.ecowidget.data.Tone
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
private const val GAP = 4f
private const val DETAIL_HEAD_H = 28f

/** Sous cette hauteur, le détail ne tient pas dans le widget : un appui ouvre la fiche plein écran. */
private const val INLINE_MIN_H = 100f

/** Ouvre la fiche plein écran sur un sujet et un onglet. */
@Composable
private fun detailAction(tile: String, tab: Int, appWidgetId: Int): Action {
    val ctx = LocalContext.current
    // L'adresse rend chaque intention unique : sinon Android pourrait confondre les PendingIntent de deux tuiles.
    val intent = Intent()
        .setComponent(ComponentName(ctx, DetailActivity::class.java))
        .setData(Uri.parse("ecowidget://detail/$tile/$tab/$appWidgetId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("tile", tile)
        .putExtra("tab", tab.toString())
        .putExtra("wid", appWidgetId.toString())
    return actionStartActivity(intent)
}

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

private const val HEADER_H = 18f
private const val HEADER_GAP = 8f
private const val TILE_GAP = 6f

/** Au moins cette hauteur de tuile (dp) pour la grille des maquettes ; en dessous, une seule rangée de petites tuiles. */
private const val FULL_MIN_TILE_H = 54f
private const val FULL_MAX_TILE_H = 96f

/** Les tuiles de la rangée basse, dans l'ordre. */
private val SHORT_TILES = listOf(Tile.TEMP, Tile.RAIN, Tile.SNOW, Tile.WIND, Tile.HUM, Tile.PRESS, Tile.FORECAST)

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
    val size = LocalSize.current
    val availW = size.width.value - 2f * PAD_X
    val availH = size.height.value - 2f * PAD_Y
    val raining = snap.isRaining == true
    val accent = if (raining) p.rain else TempColors.forTemp(snap.outdoorTemp, p.isLight, p.accent)
    val status = statusOf(state, config.showTime)
    val summaries = DetailBuilder.summaries(inputs)
    val alert = DetailBuilder.alert(inputs)

    // Hauteur disponible pour la grille, une fois l'en-tête posé.
    val gridH = availH - HEADER_H - HEADER_GAP
    val fullRows = if (gridH >= 3f * 56f + 2f * TILE_GAP) 3 else 2
    val fullTileH = ((gridH - (fullRows - 1) * TILE_GAP) / fullRows).coerceAtMost(FULL_MAX_TILE_H)
    val full = fullTileH >= FULL_MIN_TILE_H && availW >= 240f

    @Composable
    fun open(s: TileSummary): Action = if (inlineMode) {
        actionRunCallback<OpenTileCallback>(actionParametersOf(KEY_TILE to s.tile.id, KEY_TAB to s.tab.toString()))
    } else {
        detailAction(s.tile.id, s.tab, appWidgetId)
    }

    Column(modifier = GlanceModifier.fillMaxSize()) {
        val shortH = availH - HEADER_H - 4f
        if (full || shortH >= 40f) {
            WidgetHeader(p, place, status, alert)
            Spacer(modifier = GlanceModifier.height((if (full) HEADER_GAP else 4f).dp))
        }
        if (full) {
            val colW = kotlin.math.floor((availW - 3f * TILE_GAP) / 4f)
            val wideW = 2f * colW + TILE_GAP
            val used = if (fullRows == 3) summaries.take(11) else summaries.take(7)
            // 1re rangée : Extérieur (large) + 2 tuiles ; ensuite 4 tuiles par rangée.
            val lines = listOf(used.take(3), used.drop(3).take(4), used.drop(7).take(4)).filter { it.isNotEmpty() }
            lines.forEachIndexed { r, line ->
                if (r > 0) Spacer(modifier = GlanceModifier.height(TILE_GAP.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    line.forEachIndexed { k, s ->
                        if (k > 0) Spacer(modifier = GlanceModifier.width(TILE_GAP.dp))
                        if (s.wide) {
                            WideTile(p, s, accent, wideW, fullTileH, open(s))
                        } else {
                            RichTile(p, s, colW, fullTileH, open(s))
                        }
                    }
                }
            }
        } else {
            // Format bas : une seule rangée de petites tuiles (icône, valeur, libellé).
            val shown = summaries.filter { it.tab == 0 && it.tile in SHORT_TILES }.sortedBy { it.tile.ordinal }
            val tileH = minOf(56f, if (shortH >= 40f) shortH else availH)
            val perRow = minOf(shown.size, maxOf(3, (availW / 46f).toInt()))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                shown.take(perRow).forEach { s ->
                    ShortTile(p, s, accent, open(s), tileH, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/** En-tête : « Lieu · heure » à gauche, pastille d'alerte et actualisation à droite. */
@Composable
private fun WidgetHeader(p: WidgetTheme, place: String?, status: Status, alert: Alert?) {
    val left = listOfNotNull(
        place?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() },
        status.text.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(HEADER_H.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = left,
            style = TextStyle(color = ColorProvider(if (status.warn) p.warn else p.muted), fontSize = 11.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (alert != null) {
            val fg = toneColor(p, if (alert.rain) Tone.COLD else Tone.GOOD)
            Row(
                modifier = GlanceModifier
                    .background(ImageProvider(if (alert.rain) R.drawable.pill_rain else R.drawable.pill_ok))
                    .padding(start = 7.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(if (alert.rain) R.drawable.ic_cloud_rain else R.drawable.ic_check),
                    contentDescription = null,
                    modifier = GlanceModifier.size(12.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(fg)),
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                Text(
                    text = alert.text,
                    style = TextStyle(color = ColorProvider(fg), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
        }
        Box(modifier = GlanceModifier.size(20.dp).clickable(actionRunCallback<RefreshCallback>()), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(ColorProvider(p.muted)),
            )
        }
    }
}

/** Tuile des maquettes : libellé et icône en haut, valeur au milieu, sous-ligne en bas. */
@Composable
private fun RichTile(p: WidgetTheme, s: TileSummary, w: Float, h: Float, action: Action) {
    Box(
        modifier = GlanceModifier
            .width(w.dp).height(h.dp)
            .background(ImageProvider(if (s.tinted) p.cardIce else p.card))
            .clickable(action),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 7.dp)) {
            TileLabel(p, s)
            Spacer(modifier = GlanceModifier.defaultWeight())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.value,
                    style = TextStyle(color = ColorProvider(toneColor(p, s.valueTone)), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                if (s.unit.isNotEmpty()) {
                    Text(
                        text = " " + s.unit,
                        style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
                if (s.trend == 1 || s.trend == -1) {
                    Text(
                        text = if (s.trend == 1) " ▲" else " ▼",
                        style = TextStyle(color = ColorProvider(p.warn), fontSize = 9.sp),
                        maxLines = 1,
                    )
                }
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = s.sub.ifEmpty { " " },
                style = TextStyle(color = ColorProvider(toneColor(p, s.subTone)), fontSize = 10.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TileLabel(p: WidgetTheme, s: TileSummary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(s.icon),
            contentDescription = null,
            modifier = GlanceModifier.size(13.dp),
            colorFilter = ColorFilter.tint(ColorProvider(toneColor(p, s.iconTone))),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = s.label,
            style = TextStyle(color = ColorProvider(p.muted), fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

/** Grande tuile Extérieur : température colorée à gauche, max / min / ressenti à droite. */
@Composable
private fun WideTile(p: WidgetTheme, s: TileSummary, accent: Color, w: Float, h: Float, action: Action) {
    val tempColor = TempColors.forTemp(s.colorTemp, p.isLight, accent)
    val big = if (h >= 64f) 28 else 24
    Box(
        modifier = GlanceModifier.width(w.dp).height(h.dp).background(ImageProvider(p.card)).clickable(action),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                TileLabel(p, s)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = s.valueText(),
                        style = TextStyle(color = ColorProvider(tempColor), fontSize = big.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    if (s.trend == 1 || s.trend == -1) {
                        Text(
                            text = if (s.trend == 1) " ▲" else " ▼",
                            style = TextStyle(
                                color = ColorProvider(toneColor(p, if (s.trend == 1) Tone.WARM else Tone.COLD)),
                                fontSize = 10.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                s.side.forEach { (label, value) ->
                    Row {
                        Text(
                            text = "$label ",
                            style = TextStyle(color = ColorProvider(p.muted), fontSize = 11.sp),
                            maxLines = 1,
                        )
                        Text(
                            text = value,
                            style = TextStyle(
                                color = ColorProvider(if (label == "ress.") p.muted else p.text),
                                fontSize = 11.sp,
                                fontWeight = if (label == "ress.") FontWeight.Normal else FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Petite tuile du format bas : icône, valeur, libellé court. */
@Composable
private fun ShortTile(p: WidgetTheme, s: TileSummary, accent: Color, action: Action, heightDp: Float, modifier: GlanceModifier) {
    val valueColor = if (s.colorTemp != null) {
        TempColors.forTemp(s.colorTemp, p.isLight, p.text)
    } else {
        toneColor(p, s.valueTone)
    }
    val iconColor = if (s.iconTone == Tone.ACCENT) accent else toneColor(p, s.iconTone)
    val text = if (s.unit.startsWith("°") || s.unit == "%") s.value + s.unit else s.value
    Box(
        modifier = modifier.height(heightDp.dp).padding(horizontal = 2.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(ImageProvider(if (s.tinted) p.cardIce else p.card)).padding(vertical = 4.dp),
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
                text = text,
                style = TextStyle(color = ColorProvider(valueColor), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = s.short,
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
                detailAction(tile.id, tab, appWidgetId),
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
