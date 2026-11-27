package app.ecowidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import app.ecowidget.data.Tile

/**
 * Widget « Bento sombre » : le dessin (bitmap) est posé en fond, des zones transparentes cliquables
 * sont posées par-dessus aux mêmes coordonnées (même plan `BentoPlan`).
 *
 * Chaque tuile a sa propre couche (colonne + rangée d'espaceurs + zone) : les couches ne captent pas les appuis,
 * seules les zones le font. Les couches sont regroupées par 8 pour rester sous la limite de 10 enfants par conteneur.
 */
@Composable
internal fun BentoView(appWidgetId: Int, inlineMode: Boolean, plan: BentoPlan, bitmap: Bitmap) {
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        HeaderZones(appWidgetId, inlineMode, plan)
        val cells = if (plan.short) plan.cells.mapIndexed { k, c -> if (k < plan.cells.size - 1) widen(c, 4f) else c } else plan.cells
        cells.chunked(8).forEach { chunk ->
            Box(modifier = GlanceModifier.fillMaxSize()) {
                chunk.forEach { cell -> CellLayer(appWidgetId, inlineMode, cell) }
            }
        }
    }
}

/** Zone de la tuile élargie de `extra` dp à droite (pour que l'appui ne tombe jamais dans l'écart qui la suit). */
private fun widen(c: BCell, extra: Float): BCell =
    BCell(c.kind, c.x, c.y, c.w + extra, c.h, c.spanC, c.spanR, c.tile, c.tab, c.desc, c.d, c.s)

/** Ouvre le détail d'un sujet : dans le widget (mode « Tuiles + détail ») ou en fiche plein écran. */
@Composable
internal fun tileAction(appWidgetId: Int, inlineMode: Boolean, tile: Tile, tab: Int): Action =
    if (inlineMode && W3Pages.has(tile)) {
        actionRunCallback<OpenTileCallback>(actionParametersOf(KEY_TILE to tile.id, KEY_TAB to tab.toString()))
    } else {
        detailAction(tile.id, tab, appWidgetId)
    }

/** Zone transparente de `w` × `h` dp. */
@Composable
internal fun Zone(w: Float, h: Float, desc: String, action: Action?) {
    var m = GlanceModifier.width(w.dp).height(h.dp)
    if (action != null) m = m.clickable(action)
    Box(modifier = m.semantics { contentDescription = desc }) {}
}

@Composable
private fun CellLayer(appWidgetId: Int, inlineMode: Boolean, cell: BCell) {
    val tile = cell.tile
    val action: Action = if (tile != null) tileAction(appWidgetId, inlineMode, tile, cell.tab) else actionRunCallback<RefreshCallback>()
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (cell.y > 0.5f) Spacer(modifier = GlanceModifier.height(cell.y.dp))
        Row {
            if (cell.x > 0.5f) Spacer(modifier = GlanceModifier.width(cell.x.dp))
            Zone(cell.w, cell.h, cell.desc, action)
        }
    }
}

/** En-tête : zone de la pastille (ouvre la fiche Pluie) et bouton Actualiser. */
@Composable
private fun HeaderZones(appWidgetId: Int, inlineMode: Boolean, plan: BentoPlan) {
    if (plan.headerH <= 0f) return
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(plan.headerH.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = GlanceModifier.defaultWeight())
        val pr = plan.pillRect
        val pill = plan.pill
        if (pr != null && pill != null) {
            Zone(pr.width(), pr.height(), pill.text, tileAction(appWidgetId, inlineMode, Tile.RAIN, 1))
            Spacer(modifier = GlanceModifier.width((plan.refresh.left - pr.right).coerceAtLeast(0f).dp))
        }
        Zone(plan.refresh.width(), plan.refresh.height(), "Actualiser", actionRunCallback<RefreshCallback>())
    }
}
