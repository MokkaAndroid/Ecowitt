package app.ecowidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Barres de pluie de l'heure à venir : un bitmap, car Glance n'a pas de Canvas. */
object RainBars {

    private const val MAX_W = 900
    private const val MAX_H = 300

    /** `bins` : intensité (mm/h) de chaque barre, de maintenant vers +1 h. */
    fun render(
        bins: List<Double>,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        barColor: Int,
        idleColor: Int,
    ): Bitmap? {
        if (bins.isEmpty() || widthPx < 40 || heightPx < 12) return null
        val w = min(widthPx, MAX_W)
        val h = min(heightPx, MAX_H)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val n = bins.size
        val gap = 3f * density
        val barW = (w - gap * (n - 1)) / n
        val radius = min(barW / 2f, 3f * density)
        val idleH = 3f * density
        // Échelle : 2 mm/h remplit la hauteur ; au-delà, la barre la plus forte la remplit.
        val scaleMax = max(2.0, bins.max())

        bins.forEachIndexed { i, v ->
            val wet = v >= 0.05
            val barH = if (wet) {
                // Racine carrée : une pluie faible reste visible à côté d'une averse.
                max(idleH * 2f, (sqrt((v / scaleMax).coerceIn(0.0, 1.0)) * h).toFloat())
            } else {
                idleH
            }
            paint.color = if (wet) barColor else idleColor
            val x = i * (barW + gap)
            canvas.drawRoundRect(RectF(x, h - barH, x + barW, h.toFloat()), radius, radius, paint)
        }
        return bmp
    }
}

/** Courbe des températures horaires : un point au centre de chaque colonne de l'affichage. */
object HoursCurve {

    private const val MAX_W = 900
    private const val MAX_H = 200

    fun render(
        temps: List<Double>,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        lineColor: Int,
    ): Bitmap? {
        if (temps.size < 2 || widthPx < 60 || heightPx < 16) return null
        val w = min(widthPx, MAX_W)
        val h = min(heightPx, MAX_H)

        var lo = temps.min()
        var hi = temps.max()
        // Évite une courbe collée en haut quand la température est stable.
        if (hi - lo < 2.0) {
            val mid = (hi + lo) / 2.0
            lo = mid - 1.0
            hi = mid + 1.0
        }
        val padY = 5f * density
        val plotH = h - 2f * padY
        val n = temps.size
        val xs = FloatArray(n) { (it + 0.5f) / n * w }
        val ys = FloatArray(n) { padY + (1f - ((temps[it] - lo) / (hi - lo)).toFloat()) * plotH }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(Sparkline.smoothPath(xs, ys), line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor }
        for (i in 0 until n) canvas.drawCircle(xs[i], ys[i], 3f * density, dot)
        return bmp
    }
}
