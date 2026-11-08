package app.ecowidget.data

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modèle des fiches détaillées : exactement les briques des maquettes « H » (héros, pastille, cartes, légendes, cellules d'heures…).
 * Les graphiques sont des dessins en dp (voir [Drawing]) : la même description sert dans la fiche plein écran (Compose)
 * et dans le détail du widget (bitmap).
 */

/** Palette fixe des maquettes. */
enum class Hue(argb: Long) {
    TXT(0xFFEAF1F7), MUTED(0xFF9DB0C2), DIM(0xFF8EA2B5), TEAL(0xFF52D1C4), BLUE(0xFF5BB0FF), ORANGE(0xFFF2B66D),
    GREEN(0xFF5FD08A), ICY(0xFFBFE3F2), SNOW(0xFF3A63D8), COLD(0xFF7CC0FF), LAV(0xFFB3A4FF), HUM(0xFF6CC4F5),
    PRES(0xFFE7C46B), SUN(0xFFFFD166), LINE(0xFF2A3B4D), CARD(0xFF1A2632), CARD2(0xFF223244), WHITE(0xFFFFFFFF), HATCH(0xFF7FA6BD),
    LIME(0xFFA6D86B), YEL(0xFFF2D16D), RED(0xFFF28A6D), PINK(0xFFD36DB0);

    val c: Int = argb.toInt()
}

/** Un dessin : hauteur en dp et fonction qui dessine dans un canvas déjà mis à l'échelle (1 unité = 1 dp). */
class Drawing(val heightDp: Float, val draw: (Canvas) -> Unit)

/** Fabrique de dessin : la largeur disponible n'est connue qu'à l'affichage. */
fun interface ChartMaker {
    fun make(widthDp: Float): Drawing
}

sealed interface Block

class Hero(
    val value: String,
    val unit: String,
    val hue: Hue,
    val lines: List<String>,
    /** Direction d'où vient le vent (°) : affiche la boussole. */
    val compassFrom: Double? = null,
) : Block

class IconHero(val icon: Int, val hue: Hue, val title: String, val line: String) : Block

class PillB(val icon: Int, val text: String, val hue: Hue) : Block

class Metric2(
    val label: String,
    val value: String,
    val unit: String = "",
    val sub: String? = null,
    val hue: Hue = Hue.TXT,
    val size: Int = 20,
)

class StatGrid(val items: List<Metric2>, val cols: Int, val gap: Int = 12) : Block

/** Petites cartes 2 × 2 de la fiche pluie. */
class MiniCards(val items: List<Metric2>) : Block

class Card(val title: String?, val right: String?, val content: List<Block>, val gap: Int = 10) : Block

class ChartB(val maker: ChartMaker) : Block

enum class LegendKind { LINE, DASH, BOX, HATCH, SNOW, BAND }

class Legend(val kind: LegendKind, val hue: Hue, val text: String)

class LegendB(val items: List<Legend>) : Block

class NoteB(val text: String) : Block

class Cell(
    val hr: String,
    val icon: Int,
    val iconHue: Hue,
    val main: String,
    val sub: String,
    val subHue: Hue,
    val rotate: Float = 0f,
)

class CellsB(val cells: List<Cell>, val big: Boolean = true) : Block

/** Bouton d'information (« Prévisions corrigées pour 685 m »). */
class InfoChip(val icon: Int, val text: String, val hue: Hue) : Block

/** Icône + phrase + valeur à droite (« Demain »). */
class LineRow(val icon: Int, val iconHue: Hue, val text: String, val right: String?, val rightMulti: Boolean = false) : Block

class DayRow(
    val day: String,
    val icon: Int,
    val iconHue: Hue,
    val mm: String,
    val lo: String,
    val hi: String,
    /** Position (0..1) du début et de la fin de la barre de température. */
    val from: Float,
    val to: Float,
)

class DayRows(val rows: List<DayRow>) : Block

/** Échelle de l'indice de qualité de l'air, repère à `pos` (0..1). */
class ScaleB(val pos: Float) : Block

class Seg(val name: String, val range: String, val on: Boolean)

class Segments(val items: List<Seg>) : Block

class PollenGrid(val items: List<Pair<String, String>>) : Block

class TextPair(val left: String, val right: String) : Block

class Sheet(
    val tile: Tile,
    val title: String,
    val subtitle: String,
    val icon: Int,
    val hue: Hue,
    val blocks: List<Block>,
)

// ───────────────────────────── Mise en forme ─────────────────────────────

internal object Fmt {
    /** Espace fine insécable des milliers. */
    const val NB = " "

    fun fr(v: Double, d: Int = 1): String = Metric.fmt(v, d).replace('-', '−')

    fun deg(v: Double?, d: Int = 1): String = if (v == null) "—" else fr(v, d) + "°"

    fun signed(v: Double, d: Int = 1): String {
        val t = fr(v, d)
        val plus = v > 0 && t.trim('0', '.', ',').isNotEmpty()
        return (if (plus) "+" else "") + t
    }

    fun thousands(v: Double): String {
        val n = v.roundToInt()
        return if (n >= 1000) "${n / 1000}$NB${(n % 1000).toString().padStart(3, '0')}" else n.toString()
    }

    fun hlab(h: Double): String = "${((h.roundToInt() % 24) + 24) % 24}h"
}

// ───────────────────────────── Outils de dessin ─────────────────────────────

internal object Gfx {
    private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun fill(h: Hue, a: Float = 1f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = h.c
        if (a < 1f) alpha = (a * 255f).roundToInt()
    }

    fun stroke(h: Hue, w: Float, a: Float = 1f, dash: FloatArray? = null): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = h.c
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (a < 1f) alpha = (a * 255f).roundToInt()
        if (dash != null) pathEffect = DashPathEffect(dash, 0f)
    }

    fun text(h: Hue, size: Float, align: Paint.Align = Paint.Align.LEFT, medium: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = h.c
            textSize = size
            textAlign = align
            if (medium) typeface = MEDIUM
        }

    fun flake(c: Canvas, cx: Float, cy: Float, r: Float, h: Hue, sw: Float) {
        val p = stroke(h, sw)
        for (a in intArrayOf(90, 30, 150)) {
            val dx = (r * cos(a * PI / 180.0)).toFloat()
            val dy = (r * sin(a * PI / 180.0)).toFloat()
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, p)
        }
    }

    /** Rectangle arrondi hachuré : pluie ou neige possible. */
    fun hatchRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, rx: Float) {
        val clip = Path().apply { addRoundRect(RectF(x, y, x + w, y + h), rx, rx, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        c.drawRect(x, y, x + w, y + h, fill(Hue.ICY))
        val p = stroke(Hue.HATCH, 2f)
        p.strokeCap = Paint.Cap.BUTT
        val step = 5f * sqrt(2f)
        var d = -h
        while (d < w + h) {
            c.drawLine(x + d, y + h, x + d + h, y, p)
            d += step
        }
        c.restore()
    }

    fun arrow(c: Canvas, cx: Float, cy: Float, toDeg: Float, size: Float, h: Hue, sw: Float) {
        val s = size / 2f
        c.save()
        c.translate(cx, cy)
        c.rotate(toDeg)
        val path = Path().apply {
            moveTo(0f, s); lineTo(0f, -s)
            moveTo(-s * 0.55f, -s * 0.35f); lineTo(0f, -s); lineTo(s * 0.55f, -s * 0.35f)
        }
        c.drawPath(path, stroke(h, sw))
        c.restore()
    }

    /** Pastille de légende ; dessinée à partir de (0, 0). Largeur 18 (traits) ou 12 (carrés). */
    fun swatch(c: Canvas, kind: LegendKind, h: Hue) {
        when (kind) {
            LegendKind.LINE -> c.drawLine(1f, 5f, 17f, 5f, stroke(h, 2.2f))
            LegendKind.DASH -> c.drawLine(1f, 5f, 17f, 5f, stroke(h, 2f, dash = floatArrayOf(4f, 3f)))
            LegendKind.BOX -> c.drawRoundRect(0.5f, 0.5f, 11.5f, 11.5f, 3f, 3f, fill(h))
            LegendKind.HATCH -> hatchRect(c, 0.5f, 0.5f, 11f, 11f, 3f)
            LegendKind.SNOW -> {
                c.drawRoundRect(0.5f, 0.5f, 11.5f, 11.5f, 3f, 3f, fill(Hue.SNOW))
                flake(c, 6f, 6f, 3.6f, Hue.WHITE, 1.1f)
            }
            LegendKind.BAND -> c.drawRoundRect(0f, 1f, 18f, 9f, 2f, 2f, fill(h, 0.3f))
        }
    }
}

/** Même mécanique que le `Plot` des maquettes : coordonnées de données → dessin. */
class Plot(
    val w: Float,
    val h: Float,
    private val x0: Double,
    private val x1: Double,
    private val y0: Double,
    private val y1: Double,
    val l: Float = 30f,
    val r: Float = 6f,
    val t: Float = 12f,
    val b: Float = 20f,
) {
    private val ops = ArrayList<(Canvas) -> Unit>()

    fun x(v: Double): Float = l + (w - l - r) * ((v - x0) / (x1 - x0)).toFloat()
    fun y(v: Double): Float = t + (h - t - b) * (1f - ((v - y0) / (y1 - y0)).toFloat())
    val bottom: Float get() = h - b

    fun raw(op: (Canvas) -> Unit) {
        ops.add(op)
    }

    fun text(px: Float, py: Float, s: String, hue: Hue = Hue.MUTED, size: Float = 10f, align: Paint.Align = Paint.Align.LEFT, medium: Boolean = false) {
        ops.add { c -> c.drawText(s, px, py, Gfx.text(hue, size, align, medium)) }
    }

    fun ygrid(ticks: List<Double>, fmt: (Double) -> String) {
        for (v in ticks) {
            val py = y(v)
            ops.add { c -> c.drawLine(l, py, w - r, py, Gfx.stroke(Hue.LINE, 1f)) }
            text(l - 6f, py + 3.5f, fmt(v), Hue.MUTED, 10f, Paint.Align.RIGHT)
        }
    }

    fun xlabels(ticks: List<Double>, fmt: (Double) -> String, py: Float = h - 4f) {
        for (v in ticks) {
            val px = x(v)
            val align = when {
                px - l < 8f -> Paint.Align.LEFT
                w - px < 10f -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            text(px, py, fmt(v), Hue.MUTED, 10f, align)
        }
    }

    private fun path(pts: List<Pair<Double, Double>>): Path = Path().apply {
        pts.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.first), y(p.second)) else lineTo(x(p.first), y(p.second)) }
    }

    fun line(pts: List<Pair<Double, Double>>, hue: Hue, width: Float = 2.2f, dash: FloatArray? = null, opacity: Float = 1f) {
        if (pts.size < 2) return
        ops.add { c -> c.drawPath(path(pts), Gfx.stroke(hue, width, opacity, dash)) }
    }

    fun area(pts: List<Pair<Double, Double>>, hue: Hue, opacity: Float = 0.16f, base: Double? = null) {
        if (pts.size < 2) return
        ops.add { c ->
            val by = y(base ?: y0)
            val p = path(pts)
            p.lineTo(x(pts.last().first), by)
            p.lineTo(x(pts.first().first), by)
            p.close()
            c.drawPath(p, Gfx.fill(hue, opacity))
        }
    }

    fun band(upper: List<Pair<Double, Double>>, lower: List<Pair<Double, Double>>, hue: Hue, opacity: Float = 0.14f) {
        if (upper.size < 2 || lower.size < 2) return
        ops.add { c ->
            val p = path(upper)
            for (q in lower.reversed()) p.lineTo(x(q.first), y(q.second))
            p.close()
            c.drawPath(p, Gfx.fill(hue, opacity))
        }
    }

    fun vline(v: Double, hue: Hue = Hue.MUTED, label: String? = null) {
        val px = x(v)
        ops.add { c -> c.drawLine(px, t - 6f, px, bottom, Gfx.stroke(hue, 1f, dash = floatArrayOf(3f, 3f)).apply { strokeCap = Paint.Cap.BUTT }) }
        if (label != null) text(px + 4f, t - 2f, label, hue, 10f)
    }

    fun hline(v: Double, hue: Hue, dash: FloatArray = floatArrayOf(4f, 3f), width: Float = 1.2f, opacity: Float = 1f) {
        val py = y(v)
        ops.add { c -> c.drawLine(l, py, w - r, py, Gfx.stroke(hue, width, opacity, dash).apply { strokeCap = Paint.Cap.BUTT }) }
    }

    fun dot(vx: Double, vy: Double, hue: Hue, radius: Float = 3.5f) {
        val px = x(vx)
        val py = y(vy)
        ops.add { c ->
            c.drawCircle(px, py, radius, Gfx.fill(hue))
            c.drawCircle(px, py, radius, Gfx.stroke(Hue.CARD, 2f))
        }
    }

    fun drawing(): Drawing = Drawing(h) { c -> ops.forEach { it(c) } }
}

// ───────────────────────────── Graphiques particuliers ─────────────────────────────

class RainBar(val mm: Double, /** 0 pluie, 1 mélange, 2 neige */ val kind: Int, val limitM: Double?, val label: String)

class WeekDay(val label: String, val total: Double, val snow: Double)

object SheetCharts {

    /** Pluie heure par heure avec la limite pluie-neige dessous. */
    fun rainHours(bars: List<RainBar>, ymax: Double): ChartMaker = ChartMaker { w ->
        val h = 182f
        val l = 30f
        val r = 2f
        val t = 24f
        val b = 42f
        Drawing(h) { c ->
            val n = max(1, bars.size)
            val slot = (w - l - r) / n
            val bw = 12f
            val ph = h - t - b
            val ticks = listOf(0.0, ymax / 2.0, ymax)
            for ((k, v) in ticks.withIndex()) {
                val py = t + ph * (1f - (v / ymax).toFloat())
                c.drawLine(l, py, w - r, py, Gfx.stroke(Hue.LINE, 1f))
                val s = Fmt.fr(v, if (v == Math.rint(v)) 0 else 1) + if (k == 2) " mm" else ""
                c.drawText(s, l - 6f, py + 3.5f, Gfx.text(Hue.MUTED, 10f, Paint.Align.RIGHT))
            }
            bars.forEachIndexed { i, bar ->
                val x = l + slot * i + (slot - bw) / 2f
                val cx = x + bw / 2f
                val hp = if (bar.mm > 0.0) max(4f, ph * (bar.mm / ymax).toFloat()) else 2f
                val py = t + ph - hp
                if (bar.mm <= 0.0) {
                    c.drawRoundRect(x, py, x + bw, py + hp, 3f, 3f, Gfx.fill(Hue.LINE))
                } else when (bar.kind) {
                    0 -> c.drawRoundRect(x, py, x + bw, py + hp, 3f, 3f, Gfx.fill(Hue.BLUE))
                    1 -> Gfx.hatchRect(c, x, py, bw, hp, 3f)
                    else -> c.drawRoundRect(x, py, x + bw, py + hp, 3f, 3f, Gfx.fill(Hue.SNOW))
                }
                if (bar.kind == 2 && bar.mm > 0.0) Gfx.flake(c, cx, py - 9f, 4.5f, Hue.WHITE, 1.4f)
                if (i % 2 == 1) {
                    c.drawText(bar.label, cx, h - b + 15f, Gfx.text(Hue.MUTED, 10f, Paint.Align.CENTER))
                    bar.limitM?.let { c.drawText(Fmt.thousands(it), cx, h - b + 32f, Gfx.text(Hue.ICY, 9f, Paint.Align.CENTER)) }
                }
            }
            c.drawText("lim.", l - 6f, h - b + 32f, Gfx.text(Hue.ICY, 9f, Paint.Align.RIGHT))
        }
    }

    /** 60 prochaines minutes, pas de 5 min (valeur 1 mm/h = pleine hauteur). */
    fun nowcast(vals: List<Double>, left: String, mid: String, right: String): ChartMaker = ChartMaker { w ->
        val h = 62f
        val t = 6f
        val b = 18f
        Drawing(h) { c ->
            val n = max(1, vals.size)
            val slot = w / n
            val bw = 18f
            val ph = h - t - b
            vals.forEachIndexed { i, v ->
                val hp = if (v > 0.0) max(3f, ph * v.toFloat().coerceAtMost(1f)) else 2f
                val x = slot * i + (slot - bw) / 2f
                val py = t + ph - hp
                c.drawRoundRect(x, py, x + bw, py + hp, 3f, 3f, Gfx.fill(if (v > 0.0) Hue.BLUE else Hue.LINE))
            }
            c.drawText(left, 2f, h - 3f, Gfx.text(Hue.MUTED, 10f))
            c.drawText(mid, slot * (n / 2) + slot / 2f, h - 3f, Gfx.text(Hue.MUTED, 10f, Paint.Align.CENTER))
            c.drawText(right, w - 2f, h - 3f, Gfx.text(Hue.MUTED, 10f, Paint.Align.RIGHT))
        }
    }

    /** Précipitations des 7 prochains jours ; la part de neige est en bleu foncé avec un flocon. */
    fun week(days: List<WeekDay>, ymax: Double): ChartMaker = ChartMaker { w ->
        val h = 140f
        val t = 30f
        val b = 22f
        Drawing(h) { c ->
            val n = max(1, days.size)
            val slot = w / n
            val bw = 24f
            val ph = h - t - b
            days.forEachIndexed { i, d ->
                val x = slot * i + (slot - bw) / 2f
                val cx = x + bw / 2f
                if (d.total > 0.0) {
                    val hp = max(4f, ph * (d.total / ymax).toFloat())
                    val top = t + ph - hp
                    c.drawRoundRect(x, top, x + bw, top + hp, 4f, 4f, Gfx.fill(Hue.BLUE))
                    if (d.snow > 0.0) {
                        val sh = (hp * (d.snow / d.total)).toFloat().coerceAtLeast(8f).coerceAtMost(hp)
                        c.drawRoundRect(x, top, x + bw, top + sh, 4f, 4f, Gfx.fill(Hue.SNOW))
                        Gfx.flake(c, cx, top + sh / 2f, 5f, Hue.WHITE, 1.4f)
                    }
                    c.drawText(Fmt.fr(d.total), cx, top - 6f, Gfx.text(Hue.TXT, 11f, Paint.Align.CENTER, true))
                } else {
                    c.drawRoundRect(x, t + ph - 2f, x + bw, t + ph, 1f, 1f, Gfx.fill(Hue.LINE))
                    c.drawText("0", cx, t + ph - 8f, Gfx.text(Hue.DIM, 11f, Paint.Align.CENTER))
                }
                c.drawText(d.label, cx, h - 4f, Gfx.text(Hue.MUTED, 10f, Paint.Align.CENTER))
            }
        }
    }

    /**
     * Course du soleil. `progress` : 0 au lever, 1 au coucher ; négatif avant le lever, supérieur à 1 après le coucher.
     */
    fun sunArc(sunrise: String, sunset: String, noon: String, progress: Double): ChartMaker = ChartMaker { w ->
        val h = 112f
        Drawing(h) { c ->
            val xl = 22f
            val xr = w - 22f
            val cx = w / 2f
            c.drawLine(0f, 88f, w, 88f, Gfx.stroke(Hue.LINE, 1.5f))
            val arc = Path().apply {
                moveTo(xl, 88f)
                quadTo(cx, -52f, xr, 88f)
            }
            c.drawPath(arc, Gfx.stroke(Hue.SUN, 2.4f, 0.55f))
            when {
                progress < 0.0 -> c.drawCircle(xl - 6f, 95f, 6f, Gfx.fill(Hue.SUN, 0.45f))
                progress > 1.0 -> c.drawCircle(xr + 6f, 95f, 6f, Gfx.fill(Hue.SUN, 0.45f))
                else -> {
                    val p = progress.toFloat()
                    val px = (1 - p) * (1 - p) * xl + 2 * (1 - p) * p * cx + p * p * xr
                    val py = (1 - p) * (1 - p) * 88f + 2 * (1 - p) * p * -52f + p * p * 88f
                    c.drawCircle(px, py, 6f, Gfx.fill(Hue.SUN))
                }
            }
            c.drawLine(cx, 18f, cx, 88f, Gfx.stroke(Hue.LINE, 1f, dash = floatArrayOf(3f, 3f)).apply { strokeCap = Paint.Cap.BUTT })
            c.drawText("midi solaire $noon", cx, 11f, Gfx.text(Hue.MUTED, 10f, Paint.Align.CENTER))
            c.drawText("Lever $sunrise", xl, 106f, Gfx.text(Hue.TXT, 11f, Paint.Align.LEFT, true))
            c.drawText("Coucher $sunset", xr - 4f, 106f, Gfx.text(Hue.TXT, 11f, Paint.Align.RIGHT, true))
        }
    }

    /** Boussole de la fiche vent : la flèche indique où va le vent. */
    fun compass(fromDeg: Double): Drawing = Drawing(76f) { c ->
        c.drawCircle(38f, 38f, 31f, Gfx.stroke(Hue.LINE, 1.5f))
        val tp = Gfx.text(Hue.MUTED, 9f, Paint.Align.CENTER, true)
        c.drawText("N", 38f, 15f, tp)
        c.drawText("E", 63f, 41.5f, tp)
        c.drawText("S", 38f, 68f, tp)
        c.drawText("O", 13f, 41.5f, tp)
        Gfx.arrow(c, 38f, 38f, (((fromDeg + 180.0) % 360.0).toFloat()), 34f, Hue.LAV, 2.6f)
    }
}
