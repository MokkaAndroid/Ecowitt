package app.ecowidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import app.ecowidget.data.SeriesPoint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Dessine la courbe de température des dernières 24 h dans un bitmap (Glance n'a pas de Canvas). */
object Sparkline {

    private const val MAX_POINTS = 72
    private const val MAX_W = 900
    private const val MAX_H = 300

    fun render(
        series: List<SeriesPoint>,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        lineColor: Int,
        gridColor: Int,
    ): Bitmap? {
        if (series.size < 3 || widthPx < 40 || heightPx < 20) return null
        val w = min(widthPx, MAX_W)
        val h = min(heightPx, MAX_H)

        val pts = downsample(series)
        val t0 = pts.first().tSec
        val t1 = max(pts.last().tSec, t0 + 1)
        var vMin = pts.minOf { it.v }
        var vMax = pts.maxOf { it.v }
        // Évite une courbe écrasée quand la température est stable.
        if (vMax - vMin < 2.0) {
            val mid = (vMax + vMin) / 2.0
            vMin = mid - 1.0
            vMax = mid + 1.0
        }

        val padX = 4f * density
        val padTop = 6f * density
        val padBottom = 6f * density
        val plotW = w - 2 * padX
        val plotH = h - padTop - padBottom

        fun px(t: Long): Float = padX + ((t - t0).toFloat() / (t1 - t0).toFloat()) * plotW
        fun py(v: Double): Float = padTop + (1f - ((v - vMin) / (vMax - vMin)).toFloat()) * plotH

        val xs = FloatArray(pts.size) { px(pts[it].tSec) }
        val ys = FloatArray(pts.size) { py(pts[it].v) }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Ligne de gel (0 °C) en pointillés quand elle est dans la plage.
        if (vMin < 0.0 && vMax > 0.0) {
            val y0 = py(0.0)
            val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gridColor
                style = Paint.Style.STROKE
                strokeWidth = 1f * density
                pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
            }
            canvas.drawLine(padX, y0, w - padX, y0, grid)
        }

        val line = smoothPath(xs, ys)

        // Dégradé sous la courbe.
        val fill = Path(line).apply {
            lineTo(xs.last(), h.toFloat())
            lineTo(xs.first(), h.toFloat())
            close()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, padTop, 0f, h.toFloat(),
                withAlpha(lineColor, 0x70), withAlpha(lineColor, 0x00),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(fill, fillPaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(line, linePaint)

        // Point « maintenant » avec un halo.
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(lineColor, 0x55) }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor }
        canvas.drawCircle(xs.last(), ys.last(), 6f * density, halo)
        canvas.drawCircle(xs.last(), ys.last(), 3f * density, dot)
        return bmp
    }

    private fun downsample(series: List<SeriesPoint>): List<SeriesPoint> {
        if (series.size <= MAX_POINTS) return series
        val step = ceil(series.size / MAX_POINTS.toDouble()).toInt()
        val out = ArrayList<SeriesPoint>(MAX_POINTS + 1)
        for (i in series.indices step step) out.add(series[i])
        if (out.last() !== series.last()) out.add(series.last())
        return out
    }

    /** Courbe de Catmull-Rom convertie en Bézier cubique : lisse sans dépasser exagérément. */
    internal fun smoothPath(xs: FloatArray, ys: FloatArray): Path {
        val path = Path()
        path.moveTo(xs[0], ys[0])
        for (i in 0 until xs.size - 1) {
            val p0x = xs[max(i - 1, 0)]
            val p0y = ys[max(i - 1, 0)]
            val p1x = xs[i]
            val p1y = ys[i]
            val p2x = xs[i + 1]
            val p2y = ys[i + 1]
            val p3x = xs[min(i + 2, xs.size - 1)]
            val p3y = ys[min(i + 2, xs.size - 1)]
            val c1x = p1x + (p2x - p0x) / 6f
            val c1y = p1y + (p2y - p0y) / 6f
            val c2x = p2x - (p3x - p1x) / 6f
            val c2y = p2y - (p3y - p1y) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
        }
        return path
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)
}
