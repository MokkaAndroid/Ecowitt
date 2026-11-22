package app.ecowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.ecowidget.R
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Tile
import app.ecowidget.data.TileSummary
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Widget « Bento sombre » : choisit les tuiles selon la situation (voir [BentoPlanner]), calcule la mise en page
 * en dp ([BentoPlan]) puis dessine le tout dans un bitmap ([render]).
 */
internal object Bento {
    const val PAD = 12f
    private const val HEADER_H = 32f
    private const val GAP = 8f
    private const val MIN_ROW = 58f
    private const val SHORT_HEADER_H = 24f
    private const val PILL_H = 28f
    private const val SHORT_PILL_H = 22f
    private const val MAX_PIXELS = 1_500_000f
    private const val BAND_MIN_H = 180f
    private const val BAND_HERO_H = 80f
    private val SHORT = setOf(Tile.TEMP, Tile.RAIN, Tile.SNOW, Tile.WIND, Tile.HUM, Tile.PRESS, Tile.FORECAST)

    // ───────────────────────────── Mise en page ─────────────────────────────

    /**
     * Grille (3 colonnes, 3 à 5 rangées), bandeau (héros bas + une rangée) ou rangée de petites tuiles, selon la hauteur.
     * `w` × `h` : zone intérieure du widget, en dp. `ageMin` : âge de la dernière mesure de la station.
     */
    fun plan(
        ctx: Context,
        i: DetailInputs,
        status: Status,
        placeName: String?,
        w: Float,
        h: Float,
        ageMin: Int?,
    ): BentoPlan? {
        if (w < 100f || h < 24f) return null
        val f = BFonts.get(ctx)
        val data = BData(i, ageMin)
        val scn = BentoState.resolve(ctx, data.wanted(), System.currentTimeMillis())
        val pill = data.pill(scn)
        val place = placeName?.substringBefore(',')?.trim().orEmpty()

        val gridH = h - HEADER_H - GAP
        val rows = min(5, ((gridH + GAP) / (MIN_ROW + GAP)).toInt())
        val colW = (w - 2f * GAP) / 3f
        val top = HEADER_H + GAP

        if (rows >= 3 && w >= 240f) {
            val rowH = (gridH - (rows - 1) * GAP) / rows
            val items = BentoPlanner.grid(data, scn, rows)
            if (items.isNotEmpty()) {
                val cells = items.flatMap { item ->
                    val x = item.c * (colW + GAP)
                    val y = top + item.r * (rowH + GAP)
                    placeItem(item, x, y, item.sc * colW + (item.sc - 1) * GAP, item.sr * rowH + (item.sr - 1) * GAP)
                }
                return build(BLayout.GRID, scn, rows, w, h, HEADER_H, cells, pill, f, place, status, 15f, PILL_H, HEADER_H)
            }
        }

        if (h >= BAND_MIN_H && w >= 240f) {
            val items = BentoPlanner.band(data, scn)
            if (items.isNotEmpty()) {
                val rowTop = top + BAND_HERO_H + GAP
                val rowH = h - rowTop
                val cells = items.flatMap { item ->
                    val x = item.c * (colW + GAP)
                    val cw = item.sc * colW + (item.sc - 1) * GAP
                    if (item.r == 0) placeItem(item, 0f, top, w, BAND_HERO_H) else placeItem(item, x, rowTop, cw, rowH)
                }
                return build(BLayout.BAND, scn, 2, w, h, HEADER_H, cells, pill, f, place, status, 15f, PILL_H, HEADER_H)
            }
        }

        // Format bas : une rangée de petites tuiles, avec un en-tête quand il y a la place.
        val showHeader = h >= 62f
        val headerH = if (showHeader) SHORT_HEADER_H else 0f
        val below = if (showHeader) 6f else 0f
        val tileH = min(56f, h - headerH - below)
        val shown = DetailBuilder.summaries(i)
            .filter { it.tab == 0 && it.label != "Intérieur" && it.tile in SHORT }
            .filter {
                when (it.tile) {
                    Tile.RAIN -> scn == Scn.RAIN || scn == Scn.ANNOUNCED || scn == Scn.STORM
                    Tile.SNOW -> scn == Scn.WINTER
                    else -> true
                }
            }
            .sortedBy { it.tile.ordinal }
        val n = min(shown.size, max(3, (w / 46f).toInt()))
        if (n == 0) return null
        val tw = (w - (n - 1) * 4f) / n
        val cells = shown.take(n).mapIndexed { k, s ->
            BCell(BKind.SHORT, k * (tw + 4f), headerH + below, tw, tileH, 1, 1, s.tile, s.tab, s.label + " : " + s.valueText(), null, s)
        }
        return build(
            BLayout.SHORT, scn, 1, w, h, headerH, cells, pill, f, place, status, 13f, SHORT_PILL_H,
            if (showHeader) SHORT_HEADER_H else 0f,
        )
    }

    /** Une tuile posée : une cellule entière, ou la moitié haute / basse d'un duo. */
    private fun placeItem(item: Item, x: Float, y: Float, w: Float, h: Float): List<BCell> {
        val s = item.spec
        return if (item.half == 0) {
            listOf(BCell(s.kind, x, y, w, h, item.sc, item.sr, s.tile, s.tab, s.desc, s.d))
        } else {
            val hh = (h - 6f) / 2f
            val yy = if (item.half == 1) y else y + hh + 6f
            listOf(BCell(s.kind, x, yy, w, hh, 1, 1, s.tile, s.tab, s.desc, s.d))
        }
    }

    private fun build(
        layout: BLayout, scn: Scn, rows: Int, w: Float, h: Float, headerH: Float, cells: List<BCell>, pill: BentoPill?,
        f: BFonts, place: String, status: Status, headerSize: Float, pillH: Float, refreshSize: Float,
    ): BentoPlan {
        var pl: PillLayout? = null
        var pr: RectF? = null
        if (pill != null && headerH > 0f) {
            val left = place + (if (status.text.isNotEmpty()) (if (place.isNotEmpty()) " · " else "") + status.text else "")
            val leftW = bTw(f, W_PLACE, 0, headerSize).measureText(left)
            val maxW = w - refreshSize - 8f - leftW - 14f
            if (maxW >= 90f) {
                pl = pillLayout(f, pill.text, pillH, maxW)
                val right = w - refreshSize - 8f
                pr = RectF(right - pl.width, (headerH - pillH) / 2f, right, (headerH + pillH) / 2f)
            }
        }
        return BentoPlan(
            layout = layout, scn = scn, rows = rows, w = w, h = h, headerH = headerH, cells = cells,
            pill = pill, pillLayout = pl, pillRect = pr,
            refresh = if (headerH > 0f) RectF(w - refreshSize, 0f, w, refreshSize) else RectF(),
            place = place, status = status.text, warn = status.warn,
        )
    }

    private fun pillLayout(f: BFonts, text: String, h: Float, maxW: Float): PillLayout {
        val big = h >= 28f
        val padL = if (big) 9f else 7f
        val padR = if (big) 10f else 8f
        val ic = if (big) 14f else 12f
        var size = if (big) 11.5f else 10.5f
        fun width(t: String, s: Float) = padL + ic + 5f + bTw(f, W_PILL, 0, s).measureText(t) + padR
        var t = text
        while (width(t, size) > maxW && size > 9.5f) size -= 0.5f
        if (width(t, size) > maxW) {
            val avail = maxW - (padL + ic + 5f + padR) - 8f
            val n = bTw(f, W_PILL, 0, size).breakText(t, true, max(10f, avail), null)
            t = t.take(n).trimEnd() + "…"
        }
        return PillLayout(t, size, width(t, size), h)
    }

    // ───────────────────────────── Dessin ─────────────────────────────

    fun render(ctx: Context, plan: BentoPlan, density: Float): Bitmap {
        // Le système limite la mémoire des images d'un widget : au-delà de ~1,5 million de pixels, on réduit un peu l'échelle.
        val k = min(density, kotlin.math.sqrt(MAX_PIXELS / (plan.w * plan.h)))
        val bmp = Bitmap.createBitmap(
            max(1, (plan.w * k).roundToInt()), max(1, (plan.h * k).roundToInt()), Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        c.scale(k, k)
        val g = BGx(ctx, BFonts.get(ctx), c, k)
        try {
            header(g, plan)
        } catch (e: Exception) {
            // l'en-tête n'est pas indispensable
        }
        plan.cells.forEach { cell ->
            try {
                if (cell.kind == BKind.SHORT) cell.s?.let { shortTile(g, cell, it) } else BentoTiles.draw(g, cell)
            } catch (e: Exception) {
                // une tuile défaillante ne doit pas vider tout le widget
            }
        }
        return bmp
    }

    private fun header(g: BGx, plan: BentoPlan) {
        if (plan.headerH <= 0f) return
        val c = g.c
        val short = plan.layout == BLayout.SHORT
        val size = if (short) 13f else 15f
        val base = plan.headerH / 2f + 0.345f * size
        var x = 4f
        if (plan.place.isNotEmpty()) {
            val pp = bTw(g.f, W_PLACE, BPal.TXT, size)
            c.drawText(plan.place, x, base, pp)
            x += pp.measureText(plan.place)
        }
        val tail = if (plan.place.isNotEmpty() && plan.status.isNotEmpty()) " · " + plan.status else plan.status
        if (tail.isNotEmpty()) c.drawText(tail, x, base, bTw(g.f, W_STATUS, if (plan.warn) BPal.WARN else BPal.MUT, size))

        // Pastille de situation.
        val p = plan.pill
        val pl = plan.pillLayout
        val pr = plan.pillRect
        if (p != null && pl != null && pr != null) pill(g, p, pl, pr)

        // Actualiser.
        val r = plan.refresh
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, bFill(bAlpha(BPal.TXT, 0.09f)))
        g.icon(R.drawable.ic_refresh, r.centerX(), r.centerY(), if (short) 13f else 15f, BPal.TXT)
    }

    private fun pill(g: BGx, p: BentoPill, l: PillLayout, r: RectF) {
        val c = g.c
        val ink = bMix(p.tint, BPal.TXT, 0.55f)
        val rad = r.height() / 2f
        c.drawRoundRect(r, rad, rad, bFill(bAlpha(p.tint, 0.22f)))
        val inset = RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f)
        c.drawRoundRect(inset, rad, rad, bStroke(bAlpha(p.tint, 0.5f), 1f))
        val big = r.height() >= 28f
        val ic = if (big) 14f else 12f
        val padL = if (big) 9f else 7f
        g.icon(p.icon, r.left + padL + ic / 2f, r.centerY(), ic, ink)
        c.drawText(l.text, r.left + padL + ic + 5f, r.centerY() + 0.345f * l.size, bTw(g.f, W_PILL, ink, l.size))
    }

    private fun shortTint(t: Tile): BTint = when (t) {
        Tile.TEMP -> BPal.H_FAIR
        Tile.RAIN -> BPal.RAIN
        Tile.SNOW -> BPal.SNOW
        Tile.WIND -> BPal.WIND
        Tile.HUM -> BPal.HUM
        Tile.PRESS -> BPal.PRESS
        Tile.FORECAST -> BPal.PREV
        Tile.SUN -> BPal.SUN
        Tile.AIR -> BPal.AIR
    }

    /** Petite tuile du format bas : icône, valeur, libellé court. */
    private fun shortTile(g: BGx, cell: BCell, s: TileSummary) {
        val c = g.c
        val t = shortTint(s.tile)
        val rad = min(16f, cell.h * 0.3f)
        val bg = if (s.tinted) bMix(t.bg, 0xFF4A7392.toInt(), 0.45f) else t.bg
        c.drawRoundRect(RectF(cell.x, cell.y, cell.x + cell.w, cell.y + cell.h), rad, rad, bFill(bg))
        val cx = cell.x + cell.w / 2f
        val text = if (s.unit.startsWith("°") || s.unit == "%") s.value + s.unit else s.value
        val withIcon = cell.h >= 40f
        val block = (if (withIcon) 14f + 3f else 0f) + 9f + 2f + 7f
        var y = cell.y + max(2f, (cell.h - block) / 2f)
        if (withIcon) {
            g.icon(s.icon, cx, y + 7f, 14f, t.ink)
            y += 17f
        }
        val vs = g.fitSize(text, 12.5f, W_VAL, cell.w - 6f, 9f)
        c.drawText(text, cx, y + 9f, bTw(g.f, W_VAL, t.ink, vs, Paint.Align.CENTER))
        val ls = g.fitSize(s.short, 8.5f, W_SUB, cell.w - 4f, 7f)
        c.drawText(s.short, cx, y + 9f + 2f + 7f, bTw(g.f, W_SUB, bAlpha(t.ink, 0.85f), ls, Paint.Align.CENTER))
    }
}
