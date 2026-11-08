package app.ecowidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import app.ecowidget.data.BarChart
import app.ecowidget.data.ChartSpec
import app.ecowidget.data.LineChart
import app.ecowidget.data.Metric
import app.ecowidget.data.Tone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Couleurs d'un graphique, déduites du thème du widget. */
class ChartColors(
    val text: Int,
    val muted: Int,
    val grid: Int,
    val accent: Int,
    val rain: Int,
    val snow: Int,
    val ice: Int,
    val warm: Int,
    val cold: Int,
    val warn: Int,
) {
    fun of(tone: Tone): Int = when (tone) {
        Tone.NORMAL -> text
        Tone.ACCENT -> accent
        Tone.RAIN -> rain
        Tone.SNOW -> snow
        Tone.ICE -> ice
        Tone.WARM -> warm
        Tone.COLD -> cold
        Tone.WARN -> warn
    }

    companion object {
        fun of(p: WidgetTheme): ChartColors = ChartColors(
            text = p.text.toArgb(),
            muted = p.muted.toArgb(),
            grid = p.muted.copy(alpha = 0.25f).toArgb(),
            accent = p.accent.toArgb(),
            rain = p.rain.toArgb(),
            snow = if (p.isLight) 0xFF0EA5E9.toInt() else 0xFFE0F2FE.toInt(),
            ice = p.rain.toArgb(),
            warm = if (p.isLight) 0xFFEA580C.toInt() else 0xFFFDBA74.toInt(),
            cold = if (p.isLight) 0xFF0284C7.toInt() else 0xFF7DD3FC.toInt(),
            warn = p.warn.toArgb(),
        )
    }
}

/**
 * Dessine les graphiques des fiches dans un bitmap (Glance n'a pas de Canvas). La même image sert dans le widget
 * et dans la fiche plein écran ; axes et repères sont dessinés dedans, ce qui évite d'empiler des éléments Glance.
 */
object DetailCharts {

    private const val MAX_W = 1000
    private const val MAX_H = 400

    fun render(spec: ChartSpec, widthPx: Int, heightPx: Int, density: Float, c: ChartColors): Bitmap? {
        if (widthPx < 80 || heightPx < 40) return null
        val w = min(widthPx, MAX_W)
        val h = min(heightPx, MAX_H)
        // Si le bitmap a dû être réduit, on réduit aussi les traits et les textes.
        val d = density * min(w.toFloat() / widthPx, h.toFloat() / heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        when (spec) {
            is LineChart -> drawLine(canvas, spec, w, h, d, c)
            is BarChart -> drawBars(canvas, spec, w, h, d, c)
        }
        return bmp
    }

    private fun textPaint(c: ChartColors, d: Float, align: Paint.Align): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c.muted
        textSize = 9f * d
        textAlign = align
    }

    private fun gridPaint(c: ChartColors, d: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c.grid
        strokeWidth = 1f * d
        style = Paint.Style.STROKE
    }

    // ───────────────────────────── Courbes ─────────────────────────────

    private fun drawLine(cv: Canvas, s: LineChart, w: Int, h: Int, d: Float, c: ChartColors) {
        val values = s.series.flatMap { it.pts }.map { it.second } + listOfNotNull(s.refY)
        if (values.isEmpty() || s.xMax <= s.xMin) return
        var lo = values.min()
        var hi = values.max()
        if (hi - lo < s.minSpan) {
            val mid = (hi + lo) / 2.0
            lo = mid - s.minSpan / 2.0
            hi = mid + s.minSpan / 2.0
        }
        val pad = (hi - lo) * 0.08
        lo -= pad
        hi += pad

        val (step, ticks) = niceTicks(lo, hi, 3)
        val decimals = if (abs(step - step.roundToInt()) > 1e-9) max(s.yDecimals, 1) else s.yDecimals
        val labels = ticks.map { Metric.fmt(it, decimals) }
        val labelPaint = textPaint(c, d, Paint.Align.RIGHT)
        val left = (labels.maxOfOrNull { labelPaint.measureText(it) } ?: 0f) + 5f * d
        val right = 4f * d
        val top = 5f * d
        val bottom = 14f * d
        val pw = w - left - right
        val ph = h - top - bottom
        if (pw < 20f || ph < 10f) return

        fun xOf(t: Long): Float = left + ((t - s.xMin).toFloat() / (s.xMax - s.xMin).toFloat()).coerceIn(0f, 1f) * pw
        fun yOf(v: Double): Float = top + (1f - ((v - lo) / (hi - lo)).toFloat()) * ph

        val grid = gridPaint(c, d)
        ticks.forEachIndexed { k, v ->
            val y = yOf(v)
            cv.drawLine(left, y, w - right, y, grid)
            cv.drawText(labels[k], left - 3f * d, y + 3f * d, labelPaint)
        }
        if (s.zeroLine && lo < 0.0 && hi > 0.0) {
            val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = c.cold
                alpha = 150
                strokeWidth = 1.2f * d
                style = Paint.Style.STROKE
            }
            cv.drawLine(left, yOf(0.0), w - right, yOf(0.0), zero)
        }

        val xp = textPaint(c, d, Paint.Align.CENTER)
        for ((t, label) in s.xTicks) {
            if (t < s.xMin || t > s.xMax) continue
            val x = xOf(t)
            cv.drawLine(x, top, x, top + ph, grid)
            val half = xp.measureText(label) / 2f
            cv.drawText(label, x.coerceIn(left + half, w - half), h - 3f * d, xp)
        }

        val now = s.nowSec
        if (now != null && now in s.xMin..s.xMax) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = c.text
                alpha = 120
                strokeWidth = 1f * d
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f)
            }
            val x = xOf(now)
            cv.drawLine(x, top, x, top + ph, p)
        }

        val ref = s.refY
        if (ref != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = c.warn
                strokeWidth = 1.2f * d
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(5f * d, 3f * d), 0f)
            }
            val y = yOf(ref)
            cv.drawLine(left, y, w - right, y, p)
            s.refLabel?.let { label ->
                val lp = textPaint(c, d, Paint.Align.RIGHT).apply { color = c.warn }
                cv.drawText(label, w - right - 2f * d, y - 3f * d, lp)
            }
        }

        for (ser in s.series) {
            if (ser.pts.size < 2) continue
            val lc = c.of(ser.tone)
            val path = Path()
            ser.pts.forEachIndexed { k, pt ->
                val x = xOf(pt.first)
                val y = yOf(pt.second)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (ser.fill) {
                val area = Path(path)
                area.lineTo(xOf(ser.pts.last().first), top + ph)
                area.lineTo(xOf(ser.pts.first().first), top + ph)
                area.close()
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = LinearGradient(
                        0f, top, 0f, top + ph,
                        (lc and 0x00FFFFFF) or (0x55 shl 24), (lc and 0x00FFFFFF) or (0x04 shl 24),
                        Shader.TileMode.CLAMP,
                    )
                }
                cv.drawPath(area, fill)
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = lc
                style = Paint.Style.STROKE
                strokeWidth = 2f * d
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (ser.dashed) pathEffect = DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
            }
            cv.drawPath(path, stroke)
        }
    }

    // ───────────────────────────── Barres ─────────────────────────────

    private fun drawBars(cv: Canvas, s: BarChart, w: Int, h: Int, d: Float, c: ChartColors) {
        if (s.bars.isEmpty()) return
        val yMax = s.yMax ?: max(s.minMax, s.bars.maxOf { it.v })
        val labelPaint = textPaint(c, d, Paint.Align.RIGHT)
        val topLabel = Metric.fmt(yMax, s.yDecimals)
        val left = max(labelPaint.measureText(topLabel), labelPaint.measureText("0")) + 5f * d
        val right = 4f * d
        val top = 5f * d
        val bottom = 14f * d
        val pw = w - left - right
        val ph = h - top - bottom
        if (pw < 20f || ph < 10f) return

        val grid = gridPaint(c, d)
        cv.drawLine(left, top, w - right, top, grid)
        cv.drawLine(left, top + ph, w - right, top + ph, grid)
        cv.drawText(topLabel, left - 3f * d, top + 8f * d, labelPaint)
        cv.drawText("0", left - 3f * d, top + ph, labelPaint)

        val n = s.bars.size
        val gap = if (n > 16) 1.5f * d else 3f * d
        val bw = (pw - gap * (n - 1)) / n
        val radius = min(bw / 2f, 2.5f * d)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.ice
            strokeWidth = 1.2f * d
            style = Paint.Style.STROKE
        }
        s.bars.forEachIndexed { idx, b ->
            val wet = b.v >= 0.05
            val frac = (b.v / yMax).coerceIn(0.0, 1.0)
            val scaled = if (s.sqrt) sqrt(frac) else frac
            val bh = if (wet) max(2f * d, (scaled * ph).toFloat()) else 1.5f * d
            val x = left + idx * (bw + gap)
            val rect = RectF(x, top + ph - bh, x + bw, top + ph)
            if (wet && b.tone == Tone.ICE) {
                paint.color = c.ice
                paint.alpha = 90
                cv.drawRoundRect(rect, radius, radius, paint)
                cv.save()
                cv.clipRect(rect)
                var x0 = rect.left - bh
                while (x0 < rect.right) {
                    cv.drawLine(x0, rect.bottom, x0 + bh, rect.top, hatch)
                    x0 += 4f * d
                }
                cv.restore()
            } else {
                paint.color = if (wet) c.of(b.tone) else c.grid
                cv.drawRoundRect(rect, radius, radius, paint)
            }
        }

        val xp = textPaint(c, d, Paint.Align.CENTER)
        for ((idx, label) in s.xTicks) {
            if (idx < 0 || idx >= n) continue
            val cx = left + idx * (bw + gap) + bw / 2f
            val half = xp.measureText(label) / 2f
            cv.drawText(label, cx.coerceIn(left + half, w - half), h - 3f * d, xp)
        }
    }

    /** Repères « ronds » (1, 2, 2,5, 5 × 10ⁿ) : retourne le pas et les valeurs comprises dans [lo, hi]. */
    private fun niceTicks(lo: Double, hi: Double, maxCount: Int): Pair<Double, List<Double>> {
        val span = hi - lo
        if (span <= 0.0) return 1.0 to emptyList()
        val raw = span / maxCount
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.firstOrNull { it >= raw } ?: (10.0 * mag)
        val out = ArrayList<Double>()
        var v = ceil(lo / step) * step
        while (v <= hi + 1e-9 && out.size < 6) {
            out.add(v)
            v += step
        }
        return step to out
    }
}
