package app.ecowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.ecowidget.R
import app.ecowidget.data.ChartMaker
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Drawing
import app.ecowidget.data.Gfx
import app.ecowidget.data.Hue
import app.ecowidget.data.Legend
import app.ecowidget.data.LegendKind
import app.ecowidget.data.Plot
import app.ecowidget.data.SH
import app.ecowidget.data.SheetCharts
import app.ecowidget.data.Tile
import app.ecowidget.data.WeatherCodes
import app.ecowidget.data.OmFormat
import app.ecowidget.data.Fmt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pages du détail dans le widget (mode « Tuiles + détail »), d'après les maquettes W3.
 * Chaque page est un dessin de 122 dp de haut (largeur libre) posé dans la carte du widget.
 */
internal object W3Pages {
    const val DESIGN_H = 122f

    private val LFT = Paint.Align.LEFT
    private val CTR = Paint.Align.CENTER
    private val RGT = Paint.Align.RIGHT

    // ───────────────────────────── Sujets, onglets ─────────────────────────────

    fun tabs(tile: Tile): List<String> = when (tile) {
        Tile.TEMP -> listOf("Jour", "Heures", "Demain")
        Tile.RAIN -> listOf("Cumuls", "60 min", "Heures", "7 jours")
        Tile.SNOW -> listOf("24 h")
        Tile.WIND -> listOf("Jour", "Heures")
        Tile.HUM -> listOf("Jour", "Rosée")
        Tile.PRESS -> listOf("48 h", "Tendance")
        Tile.FORECAST -> listOf("Heures", "7 jours")
        else -> emptyList()
    }

    /** Soleil et Air n'ont pas de détail sur place (comme dans les maquettes) : ils ouvrent la fiche plein écran. */
    fun has(tile: Tile): Boolean = tabs(tile).isNotEmpty()

    fun hue(tile: Tile): Hue = when (tile) {
        Tile.TEMP -> Hue.TEAL
        Tile.RAIN -> Hue.BLUE
        Tile.SNOW -> Hue.ICY
        Tile.WIND -> Hue.LAV
        Tile.HUM -> Hue.HUM
        Tile.PRESS -> Hue.PRES
        Tile.FORECAST -> Hue.TEAL
        Tile.SUN -> Hue.SUN
        Tile.AIR -> Hue.GREEN
    }

    fun icon(tile: Tile): Int = when (tile) {
        Tile.TEMP -> R.drawable.mk_thermo
        Tile.RAIN -> R.drawable.mk_rain
        Tile.SNOW -> R.drawable.mk_snow
        Tile.WIND -> R.drawable.mk_wind
        Tile.HUM -> R.drawable.mk_drop
        Tile.PRESS -> R.drawable.mk_gauge
        Tile.FORECAST -> R.drawable.mk_suncloud
        Tile.SUN -> R.drawable.mk_sun
        Tile.AIR -> R.drawable.mk_leaf
    }

    // ───────────────────────────── Bitmaps ─────────────────────────────

    /** Corps de la page, pour une zone de `wDp` × `hDp` ; le dessin est réduit d'un bloc si la zone est plus basse que 122 dp. */
    fun body(ctx: Context, tile: Tile, tab: Int, i: DetailInputs, wDp: Float, hDp: Float, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, (wDp * density).toInt()), max(1, (hDp * density).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val scale = min(1f, hDp / DESIGN_H)
        c.scale(density * scale, density * scale)
        G.k = density * scale
        val designW = wDp / scale
        // Plus haut que le dessin (grand widget) : on le centre verticalement.
        c.translate(0f, max(0f, (hDp / scale - DESIGN_H) / 2f))
        try {
            page(ctx, tile, tab, i).make(designW).draw(c)
        } catch (e: Exception) {
            G.text(c, "Données indisponibles", 4f, 16f, Hue.MUTED, 12f)
        }
        return bmp
    }

    /** Pastille d'onglet (26 dp de haut) suivie d'une marge de 4 dp. */
    fun chip(label: String, selected: Boolean, hue: Hue, density: Float, padDp: Float = 8f): Bitmap {
        val tp = G.paint(if (selected) Hue.TXT else Hue.MUTED, 11f * density, LFT, 1)
        val textW = tp.measureText(label) / density
        val w = textW + 2f * padDp + 2f
        val bmp = Bitmap.createBitmap(((w + 4f) * density).toInt() + 1, (26f * density).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(density, density)
        val r = RectF(0.5f, 0.5f, w - 0.5f, 25.5f)
        if (selected) {
            c.drawRoundRect(r, 13f, 13f, Gfx.fill(hue, 0.2f))
            c.drawRoundRect(r, 13f, 13f, Gfx.stroke(hue, 1f, 0.6f))
        } else {
            c.drawRoundRect(r, 13f, 13f, Gfx.stroke(Hue.LINE, 1f))
        }
        val p = G.paint(if (selected) Hue.TXT else Hue.MUTED, 11f, CTR, 1)
        c.drawText(label, w / 2f, 13f + 3.9f, p)
        return bmp
    }

    fun chipWidthDp(label: String, density: Float, padDp: Float = 8f): Float {
        val tp = G.paint(Hue.TXT, 11f * density, LFT, 1)
        return tp.measureText(label) / density + 2f * padDp + 2f + 4f
    }

    // ───────────────────────────── Outils de dessin ─────────────────────────────

    private class WCell(val hr: String, val icon: Int, val hue: Hue, val main: String, val sub: String, val subHue: Hue, val rot: Float = 0f)

    private object G {
        private val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        /** Pixels par dp du dessin en cours : sert à poser les icônes à la bonne résolution. */
        var k = 1f

        fun paint(h: Hue, size: Float, align: Paint.Align = Paint.Align.LEFT, weight: Int = 0, spacing: Float = 0f): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = h.c
                textSize = size
                textAlign = align
                typeface = when (weight) {
                    -1 -> LIGHT
                    1 -> MEDIUM
                    else -> Typeface.DEFAULT
                }
                letterSpacing = spacing
            }

        fun text(c: Canvas, s: String, x: Float, y: Float, h: Hue, size: Float, align: Paint.Align = Paint.Align.LEFT, weight: Int = 0) {
            c.drawText(s, x, y, paint(h, size, align, weight))
        }

        fun icon(c: Canvas, ctx: Context, res: Int, x: Float, y: Float, size: Float, h: Hue, rot: Float = 0f) {
            val d = ctx.getDrawable(res)?.mutate() ?: return
            d.setTint(h.c)
            c.save()
            c.scale(1f / k, 1f / k)
            val cx = (x + size / 2f) * k
            val cy = (y + size / 2f) * k
            if (rot != 0f) c.rotate(rot, cx, cy)
            val half = size * k / 2f
            d.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
            d.draw(c)
            c.restore()
        }

        /** Colonne de droite : trait, grand chiffre, puis lignes « libellé — valeur » en bas. */
        fun right(c: Canvas, lw: Float, w: Float, hero: String, hh: Hue, sub: String, size: Float, rows: List<Triple<String, String, Hue>>) {
            val bx = lw + 8f
            c.drawLine(bx + 0.5f, 0f, bx + 0.5f, DESIGN_H, Gfx.stroke(Hue.LINE, 1f).apply { strokeCap = Paint.Cap.BUTT })
            val x = bx + 10f
            val rw = w - x
            text(c, hero, x, 0.5f * (size + 4f) + 0.35f * size, hh, size, LFT, -1)
            text(c, sub, x, size + 4f + 10f, Hue.MUTED, 10f)
            val top = DESIGN_H - 18f * rows.size
            rows.forEachIndexed { n, (label, value, h) ->
                val y = top + 18f * n + 13f
                text(c, label, x, y, Hue.MUTED, 10f)
                text(c, value, x + rw, y, h, 13f, RGT, 1)
            }
        }

        /** Rangée de cellules d'heures (hauteur 68 dp). */
        fun cells(c: Canvas, ctx: Context, w: Float, cells: List<WCell>, y0: Float) {
            if (cells.isEmpty()) return
            val n = cells.size
            val cw = (w - 2f * (n - 1)) / n
            cells.forEachIndexed { k2, cell ->
                val cx = k2 * (cw + 2f) + cw / 2f
                text(c, cell.hr, cx, y0 + 10f, Hue.MUTED, 10f, CTR)
                icon(c, ctx, cell.icon, cx - 9f, y0 + 16f, 18f, cell.hue, cell.rot)
                text(c, cell.main, cx, y0 + 49.5f, Hue.TXT, 13f, CTR, 1)
                text(c, cell.sub, cx, y0 + 65f, cell.subHue, 9f, CTR)
            }
        }

        /** Ligne icône + phrase (+ valeur à droite), 16 dp de haut. */
        fun note(c: Canvas, ctx: Context, w: Float, res: Int, msg: String, h: Hue, y: Float, right: String? = null) {
            icon(c, ctx, res, 0f, y, 16f, h)
            text(c, msg, 24f, y + 11f, Hue.MUTED, 11f)
            if (right != null) text(c, right, w, y + 12f, Hue.TXT, 13f, RGT, 1)
        }

        fun legend(c: Canvas, items: List<Legend>, x0: Float, y: Float) {
            var x = x0
            val tp = paint(Hue.MUTED, 11f)
            for (item in items) {
                val narrow = item.kind == LegendKind.LINE || item.kind == LegendKind.DASH || item.kind == LegendKind.BAND
                val sw = if (narrow) 18f else 12f
                val sh = if (narrow) 10f else 12f
                c.save()
                c.translate(x, y + (14f - sh) / 2f)
                Gfx.swatch(c, item.kind, item.hue)
                c.restore()
                c.drawText(item.text, x + sw + 6f, y + 10.5f, tp)
                x += sw + 6f + tp.measureText(item.text) + 14f
            }
        }

        /** « valeur unité » avec libellé au-dessus et sous-ligne (statistiques `rs` des maquettes). */
        fun stat(c: Canvas, x: Float, y: Float, label: String, value: String, unit: String, sub: String?, h: Hue, size: Float) {
            c.drawText(label, x, y + 9f, paint(Hue.MUTED, 9f, LFT, 1, 0.055f))
            val vy = y + 13f + (size + 4f) / 2f + 0.35f * size
            val vp = paint(h, size, LFT, 1)
            c.drawText(value, x, vy, vp)
            if (unit.isNotEmpty()) c.drawText(unit, x + vp.measureText(value) + 2f, vy, paint(Hue.MUTED, 10f))
            if (sub != null) c.drawText(sub, x, y + 13f + size + 4f + 1f + 9.5f, paint(Hue.MUTED, 10f))
        }
    }

    // ───────────────────────────── Sélection de la page ─────────────────────────────

    fun page(ctx: Context, tile: Tile, tab: Int, i: DetailInputs): ChartMaker = when (tile) {
        Tile.TEMP -> when (tab) {
            1 -> tempHours(ctx, i)
            2 -> tempTomorrow(ctx, i)
            else -> tempDay(i)
        }
        Tile.RAIN -> when (tab) {
            1 -> rain60(ctx, i)
            2 -> rainHours(i)
            3 -> rainWeek(i)
            else -> rainTotals(i)
        }
        Tile.SNOW -> snow24(i)
        Tile.WIND -> if (tab == 1) windHours(ctx, i) else windDay(i)
        Tile.HUM -> if (tab == 1) humDew(i) else humDay(i)
        Tile.PRESS -> if (tab == 1) pressTrend(ctx, i) else press48(i)
        Tile.FORECAST -> if (tab == 1) forecastWeek(ctx, i) else forecastHours(ctx, i)
        else -> ChartMaker { Drawing(DESIGN_H) { } }
    }

    // ───────────────────────────── Température ─────────────────────────────

    private fun tempDay(i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val mid = DetailBuilder.midnight(i)
        val nowH = (now - mid) / 3600.0
        val xMax = ceil((nowH + 12.0) / 6.0) * 6.0
        val today = d?.tempSeries.orEmpty().filter { it.tSec >= mid }
        val station = today.map { (it.tSec - mid) / 3600.0 to it.v }
        val ahead = DetailBuilder.hourly(i, "temperature_2m", now, mid + (xMax * 3600).toLong()).map { (it.first - mid) / 3600.0 to it.second }
        val fcLine = listOfNotNull(station.lastOrNull()) + ahead
        val pMin = today.minByOrNull { it.v }
        val pMax = today.maxByOrNull { it.v }
        val in12 = DetailBuilder.hourly(i, "temperature_2m", now + 9 * SH.H, now + 15 * SH.H).minByOrNull { abs(it.first - (now + 12 * SH.H)) }
        val lw = w - 116f
        Drawing(DESIGN_H) { c ->
            val all = station + ahead
            if (all.size >= 3) {
                val lo = floor(min(all.minOf { it.second }, 0.0) - 1.0)
                val hi = ceil(all.maxOf { it.second } + 2.0)
                val step = if (hi - lo > 24.0) 10.0 else 5.0
                val p = Plot(lw, DESIGN_H, 0.0, xMax, lo, hi, l = 24f, r = 4f, t = 12f, b = 16f)
                p.ygrid(SH.ticks(lo, hi, step)) { Fmt.fr(it, 0) + "°" }
                if (lo < 0.0 && hi > 0.0) p.hline(0.0, Hue.ORANGE, floatArrayOf(3f, 3f), opacity = 0.7f)
                p.area(station, Hue.TEAL, 0.14f, base = lo)
                p.line(station, Hue.TEAL, 2.2f)
                p.line(fcLine, Hue.TEAL, 1.8f, SH.DASH4)
                p.vline(nowH)
                pMin?.let {
                    val x = (it.tSec - mid) / 3600.0
                    p.dot(x, it.v, Hue.COLD, 3f)
                    p.text(p.x(x), p.y(it.v) + 14f, Fmt.deg(it.v), Hue.COLD, 10f, CTR, true)
                }
                pMax?.let {
                    val x = (it.tSec - mid) / 3600.0
                    p.dot(x, it.v, Hue.ORANGE, 3f)
                    p.text(p.x(x), p.y(it.v) - 7f, Fmt.deg(it.v), Hue.ORANGE, 10f, CTR, true)
                }
                p.xlabels(SH.sixHours(xMax).filter { it < xMax }, { v -> Fmt.hlab(v) })
                p.drawing().draw(c)
            }
            G.right(
                c, lw, w, Fmt.deg(s?.outdoorTemp), Hue.TEAL, s?.feelsLike?.let { "ressenti " + Fmt.deg(it) } ?: "", 26f,
                listOf(
                    Triple("Max", Fmt.deg(d?.dayMax), Hue.ORANGE),
                    Triple("Min", Fmt.deg(d?.dayMin), Hue.COLD),
                    Triple("Dans 12 h", Fmt.deg(in12?.second), Hue.ICY),
                ),
            )
        }
    }

    private fun tempHours(ctx: Context, i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
        val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        val codes = DetailBuilder.hourlyMap(i, "weather_code")
        val cells = SH.slots(i, 2, 8).mapNotNull { t ->
            val tp = temps[t] ?: return@mapNotNull null
            val mm = prec[t + SH.H] ?: 0.0
            if (mm >= 0.1) {
                val kd = SH.kind(DetailBuilder.precipTone(i.altM, t + SH.H, temps, snowH, frz))
                val ic = SH.kindIcon(kd)
                WCell(Fmt.hlab(SH.hourOf(i, t).toDouble()), ic.first, ic.second, Fmt.deg(tp, 0), Fmt.fr(mm) + " mm", SH.kindHue(kd))
            } else {
                val ic = SH.codeIcon(codes[t]?.roundToInt(), true)
                WCell(Fmt.hlab(SH.hourOf(i, t).toDouble()), ic.first, ic.second, Fmt.deg(tp, 0), "sec", Hue.MUTED)
            }
        }
        val coldest = DetailBuilder.hourly(i, "temperature_2m", now, now + 24 * SH.H).minByOrNull { it.second }
        Drawing(DESIGN_H) { c ->
            G.cells(c, ctx, w, cells, 0f)
            if (coldest != null && coldest.second <= 1.5) {
                val wet = DetailBuilder.hourly(i, "precipitation", coldest.first - 2 * SH.H, coldest.first + 2 * SH.H).any { it.second >= 0.1 }
                val base = (if (coldest.second <= 0.0) "Gel" else "Proche de 0°") + " vers " + SH.hm(i, coldest.first)
                G.note(c, ctx, w, R.drawable.mk_snow, base + (if (wet) " : neige possible" else if (coldest.second <= 0.0) " : gel possible" else ""), Hue.ICY, 80f)
            } else if (coldest != null) {
                G.note(c, ctx, w, R.drawable.mk_check, "Pas de gel prévu dans les 24 prochaines heures", Hue.GREEN, 80f)
            }
        }
    }

    private fun tempTomorrow(ctx: Context, i: DetailInputs) = ChartMaker { w ->
        val mid = DetailBuilder.midnight(i)
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
        val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        val codes = DetailBuilder.hourlyMap(i, "weather_code")
        val hi = DetailBuilder.dayAt(DetailBuilder.daily(i, "temperature_2m_max"), i, 1)
        val lo = DetailBuilder.dayAt(DetailBuilder.daily(i, "temperature_2m_min"), i, 1)
        class Part(val name: String, val icon: Int, val hue: Hue, val temp: String, val desc: String)
        val parts = listOf("Matin" to 9, "Après-midi" to 15, "Soir" to 21).map { (name, hour) ->
            val t = mid + 24 * SH.H + hour * SH.H
            val tp = temps[t]
            val mm = prec[t + SH.H] ?: 0.0
            val code = codes[t]?.roundToInt()
            if (mm >= 0.1) {
                val kd = SH.kind(DetailBuilder.precipTone(i.altM, t + SH.H, temps, snowH, frz))
                val ic = SH.kindIcon(kd)
                Part(name, ic.first, ic.second, Fmt.deg(tp, 0), when (kd) { 2 -> "neige"; 1 -> "pluie / neige"; else -> "pluie" })
            } else {
                val ic = SH.codeIcon(code, hour in 7..18)
                Part(name, ic.first, ic.second, Fmt.deg(tp, 0), WeatherCodes.label(code).lowercase())
            }
        }
        Drawing(DESIGN_H) { c ->
            val first = parts[0]
            val ic = parts.firstOrNull { it.desc.contains("neige") || it.desc.contains("pluie") } ?: first
            G.icon(c, ctx, ic.icon, 0f, 3f, 24f, ic.hue)
            val where = if (i.altM != null) " à " + i.altM.roundToInt() + " m" else ""
            val sentence = (first.desc + " le matin" + where + ", " + parts[1].desc + " l’après-midi").replaceFirstChar { ch -> ch.uppercase() }
            G.text(c, sentence, 34f, 19f, Hue.MUTED, 12f)
            if (hi != null && lo != null) G.text(c, Fmt.deg(hi, 0) + " / " + Fmt.deg(lo, 0), w, 20.5f, Hue.TXT, 16f, RGT, 1)
            val bw = (w - 12f) / 3f
            parts.forEachIndexed { k2, p ->
                val x = k2 * (bw + 6f)
                val y0 = 38f
                c.drawRoundRect(x, y0, x + bw, y0 + 80f, 12f, 12f, Gfx.fill(Hue.CARD2))
                val cx = x + bw / 2f
                G.text(c, p.name, cx, y0 + 16f, Hue.MUTED, 10f, CTR)
                G.icon(c, ctx, p.icon, cx - 9f, y0 + 21f, 18f, p.hue)
                G.text(c, p.temp, cx, y0 + 54.9f, Hue.TXT, 14f, CTR, 1)
                G.text(c, p.desc, cx, y0 + 71f, Hue.MUTED, 10f, CTR)
            }
        }
    }

    // ───────────────────────────── Pluie ─────────────────────────────

    private fun rainTotals(i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val f = i.forecast
        val mid = DetailBuilder.midnight(i)
        val nowH = (now - mid) / 3600.0
        val today = app.ecowidget.data.RainOutlook.today(f?.raw, i.zone)
        val dayPrec = DetailBuilder.daily(i, "precipitation_sum")
        val daySnow = DetailBuilder.daily(i, "snowfall_sum")
        val todayIdx = dayPrec.indexOfLast { it.first <= now }
        val tomMm = DetailBuilder.dayAt(dayPrec, i, 1)
        val tomSnow = DetailBuilder.dayAt(daySnow, i, 1)
        val weekIdx = if (todayIdx >= 0) (todayIdx + 1)..minOf(todayIdx + 7, dayPrec.size - 1) else IntRange.EMPTY
        val weekMm = weekIdx.sumOf { dayPrec[it].second ?: 0.0 }
        val cum = d?.rainDaySeries.orEmpty().filter { it.tSec >= mid }.map { (it.tSec - mid) / 3600.0 to it.v }
        val lw = w - 116f
        Drawing(DESIGN_H) { c ->
            val cw = (lw - 8f) / 2f
            val xs = listOf(0f, cw + 8f)
            G.stat(c, xs[0], 4f, "TOMBÉ AUJ.", s?.rainDaily?.let { Fmt.fr(it) } ?: "—", "mm", "depuis minuit", Hue.TXT, 18f)
            G.stat(c, xs[1], 4f, "ENCORE PRÉVU", today?.let { Fmt.fr(it.totalMm) } ?: "—", "mm", "d’ici minuit", Hue.BLUE, 18f)
            G.stat(
                c, xs[0], 66f, "DEMAIN", tomMm?.let { Fmt.fr(it) } ?: "—", "mm",
                if (tomSnow != null && tomSnow >= 0.5) "≈ " + Fmt.fr(tomSnow, 0) + " cm de neige" else "pas de neige", Hue.BLUE, 18f,
            )
            G.stat(
                c, xs[1], 66f, "7 JOURS", if (weekIdx.isEmpty()) "—" else Fmt.fr(weekMm), "mm",
                if (weekIdx.isEmpty()) null else SH.dayLabel(i, dayPrec[weekIdx.first].first) + " → " + SH.dayLabel(i, dayPrec[weekIdx.last].first),
                Hue.BLUE, 18f,
            )
            // Mini-courbe du cumul, dans la colonne de droite
            val bx = lw + 8f
            c.drawLine(bx + 0.5f, 0f, bx + 0.5f, DESIGN_H, Gfx.stroke(Hue.LINE, 1f).apply { strokeCap = Paint.Cap.BUTT })
            if (cum.size >= 2 && nowH >= 1.0) {
                val x = bx + 10f
                val rw = w - x
                val ymax = max(2.0, ceil(cum.maxOf { it.second }))
                c.save()
                c.translate(x, 0f)
                val p = Plot(rw, 108f, 0.0, nowH, 0.0, ymax, l = 2f, r = 2f, t = 22f, b = 14f)
                p.text(2f, 9f, "DEPUIS MINUIT", Hue.MUTED, 8f, LFT, true)
                p.raw { cv -> cv.drawLine(p.l, p.y(0.0), p.w - p.r, p.y(0.0), Gfx.stroke(Hue.LINE, 1f)) }
                p.area(cum, Hue.BLUE, 0.22f)
                p.line(cum, Hue.BLUE, 2f)
                p.dot(nowH, cum.last().second, Hue.BLUE, 3f)
                p.text(p.x(nowH) - 6f, p.y(cum.last().second) - 6f, Fmt.fr(cum.last().second) + " mm", Hue.BLUE, 10f, RGT, true)
                p.text(2f, 105f, "0h", Hue.MUTED, 9f)
                p.text(rw - 2f, 105f, Fmt.hlab(floor(nowH)), Hue.MUTED, 9f, RGT)
                p.drawing().draw(c)
                c.restore()
            }
        }
    }

    private fun rain60(ctx: Context, i: DetailInputs) = ChartMaker { w ->
        val f = i.forecast
        val s = i.snap
        val bins = DetailBuilder.rainBins(f, s?.rainRate)
        val peak = bins.maxOrNull() ?: 0.0
        val word = when {
            peak < 1.0 -> "faible"
            peak < 4.0 -> "modérée"
            else -> "forte"
        }
        val title: String
        val icon: Int
        val hue: Hue
        when {
            f == null || !f.hasNowcast -> {
                title = "Prévision de pluie indisponible"; icon = R.drawable.mk_cloud; hue = Hue.MUTED
            }
            s?.isRaining == true -> {
                title = "Il pleut" + (f.rainEndMin?.let { " · fin dans " + OmFormat.delay(it) } ?: ""); icon = R.drawable.mk_rain; hue = Hue.BLUE
            }
            f.nextRainMin != null && f.nextRainMin <= 60 -> {
                title = "Pluie $word dans " + OmFormat.delay(f.nextRainMin); icon = R.drawable.mk_rain; hue = Hue.BLUE
            }
            else -> {
                title = "Pas de pluie dans l’heure"; icon = R.drawable.mk_check; hue = Hue.GREEN
            }
        }
        Drawing(DESIGN_H) { c ->
            G.icon(c, ctx, icon, 0f, 3f, 22f, hue)
            G.text(c, title, 30f, 19f, Hue.TXT, 15f, LFT, 1)
            if (peak > 0.0) G.text(c, "max " + Fmt.fr(peak) + " mm/h", w, 18f, Hue.MUTED, 11f, RGT)
            if (f != null && f.hasNowcast) {
                c.save()
                c.translate(0f, 36f)
                SheetCharts.nowcast(bins, "maint.", "+30 min", "+1 h").make(w).draw(c)
                c.restore()
                G.text(c, f.nowcastSource + " · pas de 5 minutes", 0f, 112f, Hue.MUTED, 10f)
            }
        }
    }

    private fun rainHours(i: DetailInputs) = ChartMaker { w ->
        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
        val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        val h0 = SH.hourStart(i)
        val n = 16
        class Bar(val mm: Double, val kind: Int, val lim: Double?, val label: String)
        val bars = (0 until n).map { k ->
            val ts = h0 + k * SH.H
            val mm = prec[ts + SH.H] ?: 0.0
            val lim = snowH[ts] ?: frz[ts]?.let { it - 300.0 }
            Bar(mm, SH.kind(DetailBuilder.precipTone(i.altM, ts + SH.H, temps, snowH, frz)), lim?.let { max(0.0, it) }, Fmt.hlab(SH.hourOf(i, ts).toDouble()))
        }
        Drawing(DESIGN_H) { c ->
            val h = 96f
            val l = 24f
            val r = 2f
            val t = 14f
            val b = 24f
            val ymax = max(2.0, ceil(bars.maxOf { it.mm }))
            val slot = (w - l - r) / n
            val bw = 12f
            val ph = h - t - b
            for (v in listOf(0.0, ymax / 2.0, ymax)) {
                val y = t + ph * (1f - (v / ymax).toFloat())
                c.drawLine(l, y, w - r, y, Gfx.stroke(Hue.LINE, 1f))
                G.text(c, Fmt.fr(v, if (v == Math.rint(v)) 0 else 1), l - 5f, y + 3.5f, Hue.MUTED, 9f, RGT)
            }
            bars.forEachIndexed { k2, bar ->
                val x = l + slot * k2 + (slot - bw) / 2f
                val cx = x + bw / 2f
                val hp = if (bar.mm > 0.0) max(4f, ph * (bar.mm / ymax).toFloat()) else 2f
                val y = t + ph - hp
                if (bar.mm <= 0.0) {
                    c.drawRoundRect(x, y, x + bw, y + hp, 3f, 3f, Gfx.fill(Hue.LINE))
                } else when (bar.kind) {
                    0 -> c.drawRoundRect(x, y, x + bw, y + hp, 3f, 3f, Gfx.fill(Hue.BLUE))
                    1 -> Gfx.hatchRect(c, x, y, bw, hp, 3f)
                    else -> c.drawRoundRect(x, y, x + bw, y + hp, 3f, 3f, Gfx.fill(Hue.SNOW))
                }
                if (bar.kind == 2 && bar.mm > 0.0) Gfx.flake(c, cx, y - 7f, 3.8f, Hue.WHITE, 1.3f)
                if (k2 % 2 == 1) {
                    G.text(c, bar.label, cx, h - b + 11f, Hue.MUTED, 9f, CTR)
                    bar.lim?.let { G.text(c, Fmt.thousands(it), cx, h - b + 22f, Hue.ICY, 8f, CTR) }
                }
            }
            G.text(c, "lim.", l - 5f, h - b + 22f, Hue.ICY, 8f, RGT)
            G.legend(
                c,
                listOf(Legend(LegendKind.BOX, Hue.BLUE, "Pluie"), Legend(LegendKind.HATCH, Hue.ICY, "Pluie ou neige"), Legend(LegendKind.SNOW, Hue.SNOW, "Neige qui tient")),
                0f, 102f,
            )
        }
    }

    private fun rainWeek(i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val dayPrec = DetailBuilder.daily(i, "precipitation_sum")
        val daySnow = DetailBuilder.daily(i, "snowfall_sum")
        val todayIdx = dayPrec.indexOfLast { it.first <= now }
        val idx = if (todayIdx >= 0) ((todayIdx + 1)..minOf(todayIdx + 7, dayPrec.size - 1)).toList() else emptyList()
        Drawing(DESIGN_H) { c ->
            val t = 22f
            val b = 16f
            val ph = DESIGN_H - t - b
            val tops = idx.map { dayPrec[it].second ?: 0.0 }
            val ymax = max(5.0, ceil(tops.maxOrNull() ?: 0.0))
            val slot = w / max(1, idx.size)
            val bw = 24f
            idx.forEachIndexed { k2, di ->
                val tot = dayPrec[di].second ?: 0.0
                val cm = daySnow.getOrNull(di)?.second ?: 0.0
                val snow = min(tot, cm / 10.0).let { if (it >= 0.3) it else 0.0 }
                val x = slot * k2 + (slot - bw) / 2f
                val cx = x + bw / 2f
                if (tot > 0.0) {
                    val hp = max(4f, ph * (tot / ymax).toFloat())
                    val top = t + ph - hp
                    c.drawRoundRect(x, top, x + bw, top + hp, 4f, 4f, Gfx.fill(Hue.BLUE))
                    if (snow > 0.0) {
                        val sh = (hp * (snow / tot)).toFloat().coerceAtLeast(8f).coerceAtMost(hp)
                        c.drawRoundRect(x, top, x + bw, top + sh, 4f, 4f, Gfx.fill(Hue.SNOW))
                        Gfx.flake(c, cx, top + sh / 2f, 4.5f, Hue.WHITE, 1.3f)
                    }
                    G.text(c, Fmt.fr(tot), cx, top - 5f, Hue.TXT, 10f, CTR, 1)
                } else {
                    c.drawRoundRect(x, t + ph - 2f, x + bw, t + ph, 1f, 1f, Gfx.fill(Hue.LINE))
                    G.text(c, "0", cx, t + ph - 7f, Hue.DIM, 10f, CTR)
                }
                G.text(c, SH.dayLabel(i, dayPrec[di].first), cx, DESIGN_H - 2f, Hue.MUTED, 10f, CTR)
            }
        }
    }

    // ───────────────────────────── Limite neige ─────────────────────────────

    private fun snow24(i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val alt = i.altM
        val lim = DetailBuilder.limitSeries(i, 24).filter { it.first >= now - 1800 }
        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
        val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        val h0 = SH.hourStart(i)
        val x0 = SH.hOf(i, h0)
        val x1 = x0 + 24.0
        val lw = w - 116f
        Drawing(DESIGN_H) { c ->
            if (lim.size < 2) {
                G.text(c, "Limite pluie / neige indisponible pour le moment.", 0f, 16f, Hue.MUTED, 12f)
                return@Drawing
            }
            val limNow = lim.first().second
            val future = lim.filter { it.first > now }
            val low = future.minByOrNull { it.second }
            val pts = lim.map { SH.hOf(i, it.first) to it.second }
            val top = max(lim.maxOf { it.second }, alt ?: 0.0)
            val ymax = max(1000.0, ceil(top / 1000.0) * 1000.0)
            val wet = (0 until 24).filter { (prec[h0 + (it + 1) * SH.H] ?: 0.0) >= 0.1 }
            val p = Plot(lw, DESIGN_H, x0, x1, 0.0, ymax, l = 30f, r = 2f, t = 8f, b = 24f)
            if (wet.isNotEmpty()) {
                val xa = p.x(x0 + wet.first())
                val xb = p.x(x0 + wet.last() + 1.0)
                p.raw { cv -> cv.drawRect(xa, p.t, xb, p.bottom, Gfx.fill(Hue.BLUE, 0.08f)) }
            }
            p.ygrid(listOf(0.0) + SH.ticks(0.0, ymax, 1000.0) + ymax) { Fmt.thousands(it) }
            if (alt != null) p.hline(alt, Hue.TEAL, floatArrayOf(5f, 3f), 1.3f)
            p.line(pts, Hue.ICY, 2.4f)
            if (low != null && low.second < limNow - 100.0) p.dot(SH.hOf(i, low.first), low.second, Hue.ICY, 3f)
            val y0 = p.bottom + 3f
            for (k in 0 until 24) {
                val ts = h0 + k * SH.H
                if ((prec[ts + SH.H] ?: 0.0) < 0.1) continue
                val kd = SH.kind(DetailBuilder.precipTone(i.altM, ts + SH.H, temps, snowH, frz))
                val xa = p.x(x0 + k)
                val xb = p.x(x0 + k + 1.0)
                p.raw { cv ->
                    when (kd) {
                        0 -> cv.drawRoundRect(xa, y0, xb - 1f, y0 + 5f, 2f, 2f, Gfx.fill(Hue.BLUE))
                        1 -> Gfx.hatchRect(cv, xa, y0, xb - xa - 1f, 5f, 2f)
                        else -> cv.drawRoundRect(xa, y0, xb - 1f, y0 + 5f, 2f, 2f, Gfx.fill(Hue.SNOW))
                    }
                }
            }
            p.xlabels(SH.ticks(x0, x1, 6.0), { v -> Fmt.hlab(v) }, py = 119f)
            p.drawing().draw(c)
            G.right(
                c, lw, w, Fmt.thousands(DetailBuilder.hundreds(limNow).toDouble()) + " m", Hue.ICY, "limite maintenant", 19f,
                listOfNotNull(
                    if (low != null) Triple("Mini", Fmt.thousands(DetailBuilder.hundreds(low.second).toDouble()) + " m", Hue.ICY) else null,
                    if (low != null) Triple("Vers", SH.hm(i, low.first), Hue.TXT) else null,
                    if (alt != null) Triple("Chez toi", alt.roundToInt().toString() + " m", Hue.TEAL) else null,
                ),
            )
        }
    }

    // ───────────────────────────── Vent ─────────────────────────────

    private fun windDay(i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val mid = DetailBuilder.midnight(i)
        val nowH = (now - mid) / 3600.0
        val xMax = ceil((nowH + 12.0) / 6.0) * 6.0
        val end = mid + (xMax * 3600).toLong()
        val ws = d?.windSeries.orEmpty().filter { it.tSec >= mid }
        val gs = d?.gustSeries.orEmpty().filter { it.tSec >= mid }
        val mSpd = ws.map { SH.hOf(i, it.tSec) to it.v }
        val mGst = gs.map { SH.hOf(i, it.tSec) to it.v }
        val fSpd = listOfNotNull(mSpd.lastOrNull()) + DetailBuilder.hourly(i, "wind_speed_10m", now, end).map { SH.hOf(i, it.first) to it.second }
        val fGst = listOfNotNull(mGst.lastOrNull()) + DetailBuilder.hourly(i, "wind_gusts_10m", now, end).map { SH.hOf(i, it.first) to it.second }
        val gMax = gs.maxByOrNull { it.v }
        val fPeak = DetailBuilder.hourly(i, "wind_gusts_10m", now, end).maxByOrNull { it.second }
        val lw = w - 116f
        Drawing(DESIGN_H) { c ->
            if (mSpd.size + fSpd.size >= 3) {
                val topG = (mGst + fGst + mSpd + fSpd).maxOfOrNull { it.second } ?: 20.0
                val ymax = max(30.0, ceil((topG + 2.0) / 10.0) * 10.0)
                val step = if (ymax >= 40.0) 20.0 else 10.0
                val p = Plot(lw, DESIGN_H, 0.0, xMax, 0.0, ymax, l = 22f, r = 4f, t = 14f, b = 16f)
                p.ygrid(listOf(0.0) + SH.ticks(0.0, ymax, step)) { Fmt.fr(it, 0) }
                p.band(mGst, mSpd, Hue.LAV, 0.12f)
                p.line(mSpd, Hue.LAV, 2.2f)
                p.band(fGst, fSpd, Hue.LAV, 0.07f)
                p.line(fSpd, Hue.LAV, 1.8f, SH.DASH4)
                p.vline(nowH)
                gMax?.let {
                    val x = SH.hOf(i, it.tSec)
                    p.dot(x, it.v, Hue.ORANGE, 3f)
                    p.text(p.x(x), p.y(it.v) - 7f, Fmt.fr(it.v, 0), Hue.ORANGE, 10f, CTR, true)
                }
                if (fPeak != null && fPeak.second > (gMax?.v ?: 0.0) * 0.9) {
                    val x = SH.hOf(i, fPeak.first)
                    p.dot(x, fPeak.second, Hue.ORANGE, 2.5f)
                    p.text(p.x(x), p.y(fPeak.second) - 7f, Fmt.fr(fPeak.second, 0), Hue.ORANGE, 10f, CTR, true)
                }
                p.xlabels(SH.sixHours(xMax).filter { it < xMax }, { v -> Fmt.hlab(v) })
                p.drawing().draw(c)
            }
            val bft = s?.windSpeed?.let { app.ecowidget.data.SheetsB.beaufort(it).first }
            G.right(
                c, lw, w, s?.windSpeed?.let { Fmt.fr(it, 0) } ?: "—", Hue.LAV,
                "km/h" + (s?.windDir?.let { " · " + SH.fromWord(it).replaceFirstChar { ch -> ch.lowercase() } } ?: ""), 26f,
                listOf(
                    Triple("Rafales", s?.windGust?.let { Fmt.fr(it, 0) } ?: "—", Hue.LAV),
                    Triple("Rafale max", gMax?.let { Fmt.fr(it.v, 0) } ?: "—", Hue.ORANGE),
                    Triple("Force", if (bft != null) "$bft Bft" else "—", Hue.TXT),
                ),
            )
        }
    }

    private fun windHours(ctx: Context, i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val sp = DetailBuilder.hourlyMap(i, "wind_speed_10m")
        val gu = DetailBuilder.hourlyMap(i, "wind_gusts_10m")
        val dirs = DetailBuilder.hourlyMap(i, "wind_direction_10m")
        val cells = SH.slots(i, 2, 8).mapNotNull { t ->
            val v = sp[t] ?: return@mapNotNull null
            val from = dirs[t]
            WCell(
                Fmt.hlab(SH.hourOf(i, t).toDouble()), R.drawable.mk_up, Hue.LAV, Fmt.fr(v, 0),
                gu[t]?.let { "raf. " + Fmt.fr(it, 0) } ?: "", Hue.MUTED, if (from != null) ((from + 180.0) % 360.0).toFloat() else 0f,
            )
        }
        val peak = DetailBuilder.hourly(i, "wind_gusts_10m", now, now + 12 * SH.H).maxByOrNull { it.second }
        Drawing(DESIGN_H) { c ->
            G.cells(c, ctx, w, cells, 0f)
            if (peak != null) G.note(c, ctx, w, R.drawable.mk_wind, "Rafales jusqu’à " + Fmt.fr(peak.second, 0) + " km/h vers " + SH.hm(i, peak.first), Hue.LAV, 80f)
        }
    }

    // ───────────────────────────── Humidité ─────────────────────────────

    private fun humDay(i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val mid = DetailBuilder.midnight(i)
        val nowH = (now - mid) / 3600.0
        val xMax = ceil((nowH + 12.0) / 6.0) * 6.0
        val end = mid + (xMax * 3600).toLong()
        val hs = d?.humSeries.orEmpty().filter { it.tSec >= mid }
        val meas = hs.map { SH.hOf(i, it.tSec) to it.v }
        val ahead = DetailBuilder.hourly(i, "relative_humidity_2m", now, end).map { SH.hOf(i, it.first) to it.second }
        val fcLine = listOfNotNull(meas.lastOrNull()) + ahead
        val hMin = hs.minByOrNull { it.v }
        val hMax = hs.maxByOrNull { it.v }
        val lw = w - 116f
        Drawing(DESIGN_H) { c ->
            val all = meas + ahead
            if (all.size >= 3) {
                val lo = max(0.0, min(40.0, floor((all.minOf { it.second } - 5.0) / 10.0) * 10.0))
                val p = Plot(lw, DESIGN_H, 0.0, xMax, lo, 100.0, l = 26f, r = 4f, t = 14f, b = 16f)
                p.ygrid(SH.ticks(lo, 100.5, 25.0)) { Fmt.fr(it, 0) }
                p.area(meas, Hue.HUM, 0.14f, base = lo)
                p.line(meas, Hue.HUM, 2.2f)
                p.line(fcLine, Hue.HUM, 1.8f, SH.DASH4)
                p.vline(nowH)
                hMin?.let {
                    val x = SH.hOf(i, it.tSec)
                    p.dot(x, it.v, Hue.ORANGE, 3f)
                    p.text(p.x(x), p.y(it.v) + 14f, Fmt.fr(it.v, 0), Hue.ORANGE, 10f, CTR, true)
                }
                hMax?.let {
                    val x = SH.hOf(i, it.tSec)
                    p.dot(x, it.v, Hue.HUM, 3f)
                    p.text(p.x(x), p.y(it.v) - 7f, Fmt.fr(it.v, 0), Hue.HUM, 10f, CTR, true)
                }
                p.xlabels(SH.sixHours(xMax).filter { it < xMax }, { v -> Fmt.hlab(v) })
                p.drawing().draw(c)
            }
            G.right(
                c, lw, w, (s?.humidity?.let { Fmt.fr(it, 0) } ?: "—") + " %", Hue.HUM, s?.dewPoint?.let { "rosée " + Fmt.deg(it) } ?: "", 24f,
                listOf(
                    Triple("Min", hMin?.let { Fmt.fr(it.v, 0) + " %" } ?: "—", Hue.ORANGE),
                    Triple("Max", hMax?.let { Fmt.fr(it.v, 0) + " %" } ?: "—", Hue.HUM),
                    Triple("Intérieur", s?.indoorHumidity?.let { Fmt.fr(it, 0) + " %" } ?: "—", Hue.TXT),
                ),
            )
        }
    }

    private fun humDew(i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val s = i.snap
        val mid = DetailBuilder.midnight(i)
        val nowH = (now - mid) / 3600.0
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val dews = DetailBuilder.hourlyMap(i, "dew_point_2m")
        val x1 = nowH + 13.0
        val tPts = ArrayList<Pair<Double, Double>>()
        val dPts = ArrayList<Pair<Double, Double>>()
        if (s?.outdoorTemp != null && s.dewPoint != null) {
            tPts.add(nowH to s.outdoorTemp)
            dPts.add(nowH to s.dewPoint)
        }
        for (k in 1..13) {
            val t = SH.hourStart(i) + k * SH.H
            val tp = temps[t]
            val dw = dews[t]
            if (tp != null && dw != null && t > now) {
                tPts.add(SH.hOf(i, t) to tp)
                dPts.add(SH.hOf(i, t) to dw)
            }
        }
        Drawing(DESIGN_H) { c ->
            if (tPts.size < 3) {
                G.text(c, "Prévisions de rosée indisponibles pour le moment.", 0f, 16f, Hue.MUTED, 12f)
                return@Drawing
            }
            val lo = floor(min(dPts.minOf { it.second }, tPts.minOf { it.second }) - 1.0)
            val hi = ceil(max(dPts.maxOf { it.second }, tPts.maxOf { it.second }) + 1.0)
            val step = if (hi - lo <= 8.0) 2.0 else if (hi - lo <= 16.0) 5.0 else 10.0
            val fog = tPts.indices.filter { tPts[it].second - dPts[it].second <= 1.0 }
            val fogFrost = fog.any { tPts[it].second <= 1.0 }
            val q = Plot(w, 98f, nowH, x1, lo, hi, l = 26f, r = 4f, t = 14f, b = 16f)
            if (fog.isNotEmpty()) {
                val xa = q.x(tPts[fog.first()].first)
                val xb = min(max(q.x(tPts[fog.last()].first), xa + 8f), q.w - q.r)
                q.raw { cv -> cv.drawRect(xa, q.t, xb, q.bottom, Gfx.fill(Hue.HUM, 0.10f)) }
                q.text((xa + xb) / 2f, q.t - 4f, if (fogFrost) "brouillard, givre" else "brouillard", Hue.HUM, 9f, CTR, true)
            }
            q.ygrid(SH.ticks(lo, hi, step)) { Fmt.fr(it, 0) + "°" }
            q.band(tPts, dPts, Hue.HUM, 0.12f)
            q.line(tPts, Hue.TEAL, 1.8f, SH.DASH4)
            q.line(dPts, Hue.HUM, 1.8f, SH.DASH4)
            q.xlabels(SH.ticks(nowH, x1, 3.0), { v -> Fmt.hlab(v) })
            q.drawing().draw(c)
            G.legend(c, listOf(Legend(LegendKind.DASH, Hue.TEAL, "Température"), Legend(LegendKind.DASH, Hue.HUM, "Point de rosée")), 0f, 104f)
            G.text(c, "écart < 1° : air saturé", w, 114.5f, Hue.MUTED, 10f, RGT)
        }
    }

    // ───────────────────────────── Pression ─────────────────────────────

    private class PressModel(
        val meas: List<Pair<Double, Double>>,
        val fc: List<Pair<Double, Double>>,
        val cur: Double?,
        val trend: Double?,
        val kind: Int?,
        val fcMin: Pair<Double, Double>?,
    )

    private fun pressModel(i: DetailInputs): PressModel {
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val trend = d?.pressTrend3h
        val kind = when {
            trend == null -> null
            trend < -3.0 -> 0
            trend < -1.0 -> 1
            trend <= 1.0 -> 2
            trend <= 3.0 -> 3
            else -> 4
        }
        val meas = d?.pressSeries.orEmpty().filter { it.tSec >= now - 24 * SH.H }.map { (it.tSec - now) / 3600.0 to it.v }
        val pm = DetailBuilder.hourlyMap(i, "pressure_msl")
        val cur = s?.pressure ?: meas.lastOrNull()?.second
        val before = pm.entries.filter { it.key <= now }.maxByOrNull { it.key }
        val after = pm.entries.filter { it.key > now }.minByOrNull { it.key }
        val atNow = when {
            before != null && after != null -> before.value + (after.value - before.value) * ((now - before.key).toDouble() / (after.key - before.key))
            before != null -> before.value
            else -> after?.value
        }
        val offset = if (cur != null && atNow != null) cur - atNow else 0.0
        val fc = ArrayList<Pair<Double, Double>>()
        if (cur != null) fc.add(0.0 to cur)
        for ((t, v) in pm.entries.sortedBy { it.key }) {
            if (t > now && t <= now + 24 * SH.H) fc.add((t - now) / 3600.0 to v + offset)
        }
        return PressModel(meas, fc, cur, trend, kind, fc.drop(1).minByOrNull { it.second })
    }

    private fun press48(i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val m = pressModel(i)
        val lw = w - 116f
        val first = m.meas.firstOrNull()
        val d24 = if (first != null && m.cur != null && first.first <= -6.0) m.cur - first.second else null
        val rise = if (m.fcMin != null && m.fc.isNotEmpty()) m.fc.last().second - m.fcMin.second else null
        Drawing(DESIGN_H) { c ->
            if (m.meas.size + m.fc.size >= 4) {
                val vals = m.meas.map { it.second } + m.fc.map { it.second }
                val lo = floor((vals.min() - 3.0) / 5.0) * 5.0
                val hi = ceil((vals.max() + 3.0) / 5.0) * 5.0
                val step = if (hi - lo > 40.0) 10.0 else 5.0
                val p = Plot(lw, DESIGN_H, -24.0, 24.0, lo, hi, l = 30f, r = 4f, t = 12f, b = 16f)
                p.ygrid(SH.ticks(lo, hi, step)) { Fmt.fr(it, 0) }
                p.area(m.meas, Hue.PRES, 0.12f, base = lo)
                p.line(m.meas, Hue.PRES, 2.2f)
                p.line(m.fc, Hue.PRES, 1.8f, SH.DASH4)
                p.vline(0.0)
                m.fcMin?.let {
                    p.dot(it.first, it.second, Hue.ORANGE, 3f)
                    p.text(p.x(it.first), p.y(it.second) - 7f, Fmt.fr(it.second, 0), Hue.ORANGE, 10f, CTR, true)
                }
                p.xlabels(listOf(-24.0, -12.0, 0.0, 12.0, 24.0), { v ->
                    val hr = Fmt.hlab(SH.hourOf(i, now + (v * 3600).toLong()).toDouble())
                    when (v.toInt()) {
                        -24 -> "hier"
                        0 -> ""
                        24 -> "dem."
                        else -> hr
                    }
                })
                p.drawing().draw(c)
            }
            G.right(
                c, lw, w, m.cur?.let { Fmt.fr(it, 0) } ?: "—", Hue.PRES,
                "hPa" + (m.trend?.let { " · " + Fmt.signed(it) + " en 3 h" } ?: ""), 24f,
                listOf(
                    Triple("24 h", d24?.let { Fmt.signed(it) } ?: "—", Hue.TXT),
                    Triple("Mini prévu", m.fcMin?.let { Fmt.fr(it.second, 0) } ?: "—", Hue.ORANGE),
                    Triple("Remontée", if (rise != null && rise >= 1.0) Fmt.signed(rise, 0) else "—", Hue.TXT),
                ),
            )
        }
    }

    private fun pressTrend(ctx: Context, i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val m = pressModel(i)
        val first = m.meas.firstOrNull()
        val d24 = if (first != null && m.cur != null && first.first <= -6.0) m.cur - first.second else null
        val rise = if (m.fcMin != null && m.fc.isNotEmpty()) m.fc.last().second - m.fcMin.second else null
        val endT = now + 24 * SH.H
        val text = when {
            m.kind != null && m.kind <= 1 -> "Perturbation en approche : le temps se dégrade"
            m.kind != null && m.kind >= 3 -> "Pression en hausse : le temps s’améliore"
            m.kind != null -> "Pression stable : pas de changement marqué"
            else -> null
        }
        Drawing(DESIGN_H) { c ->
            val names = listOf("Baisse rapide" to "< −3", "Baisse" to "−3 à −1", "Stable" to "± 1", "Hausse" to "+1 à +3", "Hausse rapide" to "> +3")
            val sw = (w - 4f * 5f) / 5f
            names.forEachIndexed { k2, (name, range) ->
                val x = k2 * (sw + 5f)
                val on = m.kind == k2
                c.drawRoundRect(x, 0f, x + sw, 8f, 4f, 4f, Gfx.fill(if (on) Hue.PRES else Hue.LINE))
                G.text(c, name, x, 8f + 4f + 10f, if (on) Hue.TXT else Hue.MUTED, 10f, LFT, if (on) 1 else 0)
                G.text(c, range, x, 8f + 4f + 13f + 4f + 9f, Hue.MUTED, 9f)
            }
            if (text != null) G.note(c, ctx, w, R.drawable.mk_gauge, text, Hue.PRES, 49f + 8f)
            val cw = (w - 16f) / 3f
            G.stat(c, 0f, 81f, "SUR 24 H", d24?.let { Fmt.signed(it) } ?: "—", "hPa", null, Hue.TXT, 16f)
            G.stat(
                c, cw + 8f, 81f, "MINI VERS " + (m.fcMin?.let { SH.hm(i, now + (it.first * 3600).toLong()) } ?: "—").uppercase(),
                m.fcMin?.let { Fmt.fr(it.second, 0) } ?: "—", "hPa", null, Hue.ORANGE, 16f,
            )
            G.stat(
                c, 2f * (cw + 8f), 81f, "REMONTÉE" + (if (rise != null && rise >= 1.0 && !DetailBuilder.sameDay(endT, now, i.zone)) " DEM." else ""),
                if (rise != null && rise >= 1.0) Fmt.signed(rise, 0) else "—", "hPa", null, Hue.TXT, 16f,
            )
        }
    }

    // ───────────────────────────── Prévisions ─────────────────────────────

    private fun forecastHours(ctx: Context, i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val f = i.forecast
        val s = i.snap
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
        val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        val codes = DetailBuilder.hourlyMap(i, "weather_code")
        val isDay = DetailBuilder.hourlyMap(i, "is_day")
        val pops = DetailBuilder.hourlyMap(i, "precipitation_probability")
        val cells = SH.slots(i, 3, 8).mapNotNull { t ->
            val tp = temps[t] ?: return@mapNotNull null
            val mm = prec[t + SH.H] ?: 0.0
            val pop = pops[t]
            var subHue = Hue.MUTED
            val ic: Pair<Int, Hue>
            if (mm >= 0.1) {
                val kd = SH.kind(DetailBuilder.precipTone(i.altM, t + SH.H, temps, snowH, frz))
                ic = SH.kindIcon(kd)
                subHue = SH.kindHue(kd)
            } else {
                ic = SH.codeIcon(codes[t]?.roundToInt(), (isDay[t] ?: 1.0) >= 0.5)
            }
            WCell(Fmt.hlab(SH.hourOf(i, t).toDouble()), ic.first, ic.second, Fmt.deg(tp, 0), if (pop != null) Fmt.fr(pop, 0) + " %" else "—", subHue)
        }
        val ep = DetailBuilder.episode(i)
        val raining = s?.isRaining == true
        var title = if (f != null) WeatherCodes.label(f.code, f.isDay ?: true) else "Prévisions"
        var icon = SH.codeIcon(f?.code, f?.isDay ?: true)
        val first = ep?.first
        if (raining) {
            title += ", il pleut"
            icon = SH.kindIcon(0)
        } else if (first != null) {
            val at = max(first, now)
            val hr = SH.hourOf(i, at)
            val day = if (DetailBuilder.sameDay(at, now, i.zone)) {
                when {
                    hr < 12 -> "ce matin"
                    hr < 18 -> "cet après-midi"
                    else -> "ce soir"
                }
            } else "demain"
            title += ", " + (if (ep?.firstIsSnow == true) "neige " else "pluie ") + day
            icon = SH.kindIcon(if (ep?.firstIsSnow == true) 2 else 0)
        }
        Drawing(DESIGN_H) { c ->
            G.cells(c, ctx, w, cells, 0f)
            G.note(
                c, ctx, w, icon.first, title, icon.second, 80f,
                if (f?.dayMax != null && f.dayMin != null) Fmt.fr(f.dayMax, 0) + "° / " + Fmt.fr(f.dayMin, 0) + "°" else null,
            )
        }
    }

    private fun forecastWeek(ctx: Context, i: DetailInputs) = ChartMaker { w ->
        val now = i.nowSec
        val tmx = DetailBuilder.daily(i, "temperature_2m_max")
        val tmn = DetailBuilder.daily(i, "temperature_2m_min")
        val dcode = DetailBuilder.daily(i, "weather_code")
        val dprec = DetailBuilder.daily(i, "precipitation_sum")
        val dsnow = DetailBuilder.daily(i, "snowfall_sum")
        val t0 = tmx.indexOfLast { it.first <= now }
        val idx = if (t0 >= 0) (t0 until min(t0 + 7, tmx.size)).filter { tmx[it].second != null && tmn.getOrNull(it)?.second != null } else emptyList()
        Drawing(DESIGN_H) { c ->
            if (idx.isEmpty()) {
                G.text(c, "Prévisions sur 7 jours indisponibles.", 0f, 16f, Hue.MUTED, 12f)
                return@Drawing
            }
            val n = idx.size
            val cw = (w - 4f * (n - 1)) / n
            idx.forEachIndexed { k2, di ->
                val lo = tmn[di].second!!
                val hi = tmx[di].second!!
                val mm = dprec.getOrNull(di)?.second ?: 0.0
                val cm = dsnow.getOrNull(di)?.second ?: 0.0
                val ic = when {
                    cm >= 1.0 -> SH.kindIcon(2)
                    mm >= 1.0 -> SH.kindIcon(0)
                    else -> SH.codeIcon(dcode.getOrNull(di)?.second?.roundToInt())
                }
                val x = k2 * (cw + 4f)
                val cx = x + cw / 2f
                c.drawRoundRect(x, 0f, x + cw, 110f, 12f, 12f, Gfx.fill(Hue.CARD2))
                val label = if (di == t0) "Auj." else SH.dayLabel(i, tmx[di].first).replaceFirstChar { it.uppercase() }
                G.text(c, label, cx, 8f + 10f, Hue.MUTED, 10f, CTR, 1)
                G.icon(c, ctx, ic.first, cx - 10f, 8f + 13f + 4f, 20f, ic.second)
                G.text(c, Fmt.fr(hi, 0) + "°", cx, 8f + 13f + 4f + 20f + 4f + 13.9f, Hue.TXT, 14f, CTR, 1)
                G.text(c, Fmt.fr(lo, 0) + "°", cx, 8f + 13f + 4f + 20f + 4f + 18f + 4f + 11.2f, Hue.MUTED, 12f, CTR)
                if (mm >= 0.1) G.text(c, Fmt.fr(mm) + " mm", cx, 8f + 13f + 4f + 20f + 4f + 18f + 4f + 15f + 4f + 9f, Hue.BLUE, 9f, CTR)
            }
        }
    }
}
