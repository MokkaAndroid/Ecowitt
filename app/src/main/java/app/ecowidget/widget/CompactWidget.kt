package app.ecowidget.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import app.ecowidget.data.CachedState
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Hue
import app.ecowidget.data.Storage
import app.ecowidget.data.Tile
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

// Détail intégré (maquettes W3) : en-tête 28 dp, 6 dp d'écart, carte à 8 dp de marge intérieure.
private const val W3_HEAD_H = 28f
private const val W3_GAP = 6f
private const val W3_PAD = 8f

/** Sous cette hauteur, le détail (en-tête 28 + corps 122 d'après les maquettes) ne tient pas : un appui ouvre la fiche plein écran. */
private const val INLINE_MIN_H = 150f

/** Ouvre la fiche plein écran sur un sujet et un onglet. */
@Composable
internal fun detailAction(tile: String, tab: Int, appWidgetId: Int): Action {
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
    // Modes à tuiles : palette sombre fixe (Bento), quel que soit le thème choisi pour le mode Complet.
    val p = WidgetTheme.NIGHT
    val snap = state.snapshot
    val raining = snap?.isRaining == true
    val size = LocalSize.current
    val ctx = LocalContext.current
    val inlineOk = mode == WidgetMode.TILES_INLINE && size.height.value >= INLINE_MIN_H
    val openTile = if (inlineOk) Tile.fromId(openDetail?.tile)?.takeIf { W3Pages.has(it) } else null

    // Détail ouvert : un appui sur le fond prolonge seulement le délai (pas de rechargement réseau).
    val rootAction: Action = if (openTile != null) {
        actionRunCallback<OpenTileCallback>(actionParametersOf(KEY_TILE to openTile.id, KEY_TAB to (openDetail?.tab ?: 0).toString()))
    } else {
        actionRunCallback<RefreshCallback>()
    }

    // Grille Bento : calculée ici, car sa marge (12 dp) n'est pas celle du détail.
    val padX = if (openTile == null) Bento.PAD else PAD_X.toFloat()
    val padY = if (openTile == null) Bento.PAD else PAD_Y.toFloat()
    val ageMin: Int? = if (snap != null) {
        val obsMs = snap.observedAtSec?.times(1000) ?: state.fetchedAtMs
        if (obsMs > 0) ((System.currentTimeMillis() - obsMs) / 60_000).toInt().coerceAtLeast(0) else null
    } else {
        null
    }
    // Style « Atmosphère » (par défaut) : une seule colonne ; si le widget est trop petit, le Bento prend le relais.
    val atmo = if (snap != null && openTile == null && Storage.widgetStyle(ctx) == Storage.STYLE_ATMO) {
        Atmo.plan(ctx, inputs, place, size.width.value, size.height.value, ageMin)
    } else {
        null
    }
    val plan = if (atmo == null && snap != null && openTile == null) {
        Bento.plan(
            ctx, inputs, statusOf(state, config.showTime), place,
            size.width.value - 2f * Bento.PAD, size.height.value - 2f * Bento.PAD, ageMin,
        )
    } else {
        null
    }

    if (atmo != null) {
        Box(modifier = GlanceModifier.fillMaxSize().clickable(rootAction)) {
            AtmoView(appWidgetId, inlineOk, atmo, Atmo.render(ctx, atmo, ctx.resources.displayMetrics.density))
        }
        return
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(if (raining) R.drawable.widget_bg_bento_rain else R.drawable.widget_bg_bento))
            .clickable(rootAction)
            .padding(horizontal = padX.dp, vertical = padY.dp),
    ) {
        if (snap == null) {
            EmptyState(p, state)
        } else if (openTile != null) {
            DetailView(appWidgetId, p, inputs, openTile, openDetail?.tab ?: 0)
        } else if (plan != null) {
            BentoView(appWidgetId, inlineOk, plan, Bento.render(ctx, plan, ctx.resources.displayMetrics.density))
        } else {
            EmptyState(p, state)
        }
    }
}

// ───────────────────────────── Détail dans le widget (mode 3) ─────────────────────────────

@Composable
private fun DetailView(appWidgetId: Int, p: WidgetTheme, inputs: DetailInputs, tile: Tile, tabIndex: Int) {
    val size = LocalSize.current
    val ctx = LocalContext.current
    val density = ctx.resources.displayMetrics.density
    val hue = W3Pages.hue(tile)
    val labels = W3Pages.tabs(tile)
    val tab = tabIndex.coerceIn(0, labels.size - 1)

    val availW = size.width.value - 2f * PAD_X
    val availH = size.height.value - 2f * PAD_Y
    val bodyH = availH - W3_HEAD_H - W3_GAP
    val innerW = availW - 2f * W3_PAD
    val innerH = bodyH - 2f * W3_PAD

    // En-tête : retour (28) · icône (18) · onglets · agrandir (28). Si ça déborde, on serre les onglets puis on retire l'icône.
    var chipPad = 8f
    var showIcon = true
    fun need(): Float =
        28f + 5f + (if (showIcon) 18f + 5f else 0f) + labels.sumOf { W3Pages.chipWidthDp(it, density, chipPad).toDouble() }.toFloat() + 5f + 28f
    if (need() > availW) chipPad = 5f
    if (need() > availW) showIcon = false

    val body = W3Pages.body(ctx, tile, tab, inputs, innerW, innerH, density)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth().height(W3_HEAD_H.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(R.drawable.mk_back, 16f, Hue.TXT, actionRunCallback<CloseTileCallback>())
            Spacer(modifier = GlanceModifier.width(5.dp))
            if (showIcon) {
                Image(
                    provider = ImageProvider(W3Pages.icon(tile)),
                    contentDescription = null,
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(Color(hue.c))),
                )
                Spacer(modifier = GlanceModifier.width(5.dp))
            }
            labels.forEachIndexed { k, label ->
                Image(
                    provider = ImageProvider(W3Pages.chip(label, k == tab, hue, density, chipPad)),
                    contentDescription = label,
                    modifier = GlanceModifier
                        .width(W3Pages.chipWidthDp(label, density, chipPad).dp)
                        .height(26.dp)
                        .clickable(actionRunCallback<OpenTileCallback>(actionParametersOf(KEY_TILE to tile.id, KEY_TAB to k.toString()))),
                    contentScale = ContentScale.FillBounds,
                )
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            RoundButton(R.drawable.mk_expand, 14f, Hue.MUTED, detailAction(tile.id, tab, appWidgetId))
        }
        Spacer(modifier = GlanceModifier.height(W3_GAP.dp))
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(bodyH.dp)
                .background(ImageProvider(R.drawable.w3_card))
                .padding(W3_PAD.dp),
        ) {
            Image(
                provider = ImageProvider(body),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

/** Bouton rond de 28 dp des maquettes (fond de carte, icône teintée). */
@Composable
private fun RoundButton(icon: Int, iconDp: Float, tint: Hue, action: Action) {
    Box(
        modifier = GlanceModifier.size(28.dp).background(ImageProvider(R.drawable.w3_circle)).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(iconDp.dp),
            colorFilter = ColorFilter.tint(ColorProvider(Color(tint.c))),
        )
    }
}

