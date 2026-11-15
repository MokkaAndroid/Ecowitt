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
import app.ecowidget.data.TileSummary

/**
 * Widget « Bento sombre » : le dessin (bitmap) est posé en fond, des zones transparentes cliquables
 * sont posées par-dessus aux mêmes coordonnées (même plan `BentoPlan`).
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
        if (plan.short) ShortZones(appWidgetId, inlineMode, plan) else GridZones(appWidgetId, inlineMode, plan)
    }
}

/** Ouvre le détail d'un sujet : dans le widget (mode « Tuiles + détail ») ou en fiche plein écran. */
@Composable
private fun tileAction(appWidgetId: Int, inlineMode: Boolean, tile: Tile, tab: Int): Action =
    if (inlineMode && W3Pages.has(tile)) {
        actionRunCallback<OpenTileCallback>(actionParametersOf(KEY_TILE to tile.id, KEY_TAB to tab.toString()))
    } else {
        detailAction(tile.id, tab, appWidgetId)
    }

private fun describe(s: TileSummary): String {
    val sub = if (s.sub.isNotEmpty()) ", " + s.sub else ""
    return s.label + " : " + s.valueText() + sub
}

/** Zone transparente de `w` × `h` dp. */
@Composable
private fun Zone(w: Float, h: Float, desc: String, action: Action?) {
    var m = GlanceModifier.width(w.dp).height(h.dp)
    if (action != null) m = m.clickable(action)
    Box(modifier = m.semantics { contentDescription = desc }) {}
}

@Composable
private fun CellZone(appWidgetId: Int, inlineMode: Boolean, cell: BentoCell?, w: Float, h: Float) {
    if (cell == null) {
        Spacer(modifier = GlanceModifier.width(w.dp).height(h.dp))
    } else {
        Zone(w, h, describe(cell.s), tileAction(appWidgetId, inlineMode, cell.s.tile, cell.s.tab))
    }
}

@Composable
private fun GridZones(appWidgetId: Int, inlineMode: Boolean, plan: BentoPlan) {
    val gap = plan.gap
    val hero = plan.cell(BentoKey.HERO)
    val colW = (plan.w - 2f * gap) / 3f
    val rowH = if (hero != null) (hero.h - gap) / 2f else (plan.h - plan.headerH - gap - (plan.rows - 1) * gap) / plan.rows
    fun cell(k: BentoKey) = plan.cell(k)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth().height(plan.headerH.dp)) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            Zone(plan.refresh.width(), plan.refresh.height(), "Actualiser", actionRunCallback<RefreshCallback>())
        }
        Spacer(modifier = GlanceModifier.height(gap.dp))

        // Héros (2 × 2) et, à droite, Prévision et Pluie.
        Row(modifier = GlanceModifier.fillMaxWidth().height((2f * rowH + gap).dp)) {
            if (hero != null) {
                Box(modifier = GlanceModifier.width(hero.w.dp).height(hero.h.dp)) {
                    Zone(hero.w, hero.h, describe(hero.s), tileAction(appWidgetId, inlineMode, hero.s.tile, hero.s.tab))
                    val pr = plan.pillRect
                    val pill = plan.pill
                    if (pr != null && pill != null) {
                        Column(modifier = GlanceModifier.fillMaxSize()) {
                            Spacer(modifier = GlanceModifier.height((pr.top - hero.y).dp))
                            Row {
                                Spacer(modifier = GlanceModifier.width((pr.left - hero.x).dp))
                                Zone(pr.width(), pr.height(), pill.text, tileAction(appWidgetId, inlineMode, Tile.RAIN, 1))
                            }
                        }
                    }
                }
                Spacer(modifier = GlanceModifier.width(gap.dp))
            } else {
                Spacer(modifier = GlanceModifier.width((2f * colW + 2f * gap).dp))
            }
            Column {
                CellZone(appWidgetId, inlineMode, cell(BentoKey.PREV), colW, rowH)
                Spacer(modifier = GlanceModifier.height(gap.dp))
                CellZone(appWidgetId, inlineMode, cell(BentoKey.RAIN), colW, rowH)
            }
        }

        // Vent, Humidité, Pression.
        Spacer(modifier = GlanceModifier.height(gap.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            CellZone(appWidgetId, inlineMode, cell(BentoKey.WIND), colW, rowH)
            Spacer(modifier = GlanceModifier.width(gap.dp))
            CellZone(appWidgetId, inlineMode, cell(BentoKey.HUM), colW, rowH)
            Spacer(modifier = GlanceModifier.width(gap.dp))
            CellZone(appWidgetId, inlineMode, cell(BentoKey.PRESS), colW, rowH)
        }

        // Limite neige, Demain, Soleil.
        if (plan.rows >= 4) {
            Spacer(modifier = GlanceModifier.height(gap.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                CellZone(appWidgetId, inlineMode, cell(BentoKey.SNOW), colW, rowH)
                Spacer(modifier = GlanceModifier.width(gap.dp))
                CellZone(appWidgetId, inlineMode, cell(BentoKey.TOM), colW, rowH)
                Spacer(modifier = GlanceModifier.width(gap.dp))
                CellZone(appWidgetId, inlineMode, cell(BentoKey.SUN), colW, rowH)
            }
        }

        // Air (double largeur) et Intérieur.
        if (plan.rows >= 5) {
            Spacer(modifier = GlanceModifier.height(gap.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                CellZone(appWidgetId, inlineMode, cell(BentoKey.AIR), 2f * colW + gap, rowH)
                Spacer(modifier = GlanceModifier.width(gap.dp))
                CellZone(appWidgetId, inlineMode, cell(BentoKey.HOME), colW, rowH)
            }
        }
    }
}

@Composable
private fun ShortZones(appWidgetId: Int, inlineMode: Boolean, plan: BentoPlan) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (plan.headerH > 0f) {
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
            Spacer(modifier = GlanceModifier.height(plan.gap.dp))
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            plan.cells.forEachIndexed { k, cell ->
                // Chaque zone englobe l'écart de 4 dp qui la suit, pour que l'appui ne tombe jamais dans un trou.
                val w = if (k < plan.cells.size - 1) cell.w + 4f else cell.w
                Zone(w, cell.h, describe(cell.s), tileAction(appWidgetId, inlineMode, cell.s.tile, cell.s.tab))
            }
        }
    }
}
