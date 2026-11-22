package app.ecowidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import app.ecowidget.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fond et encre d'une tuile. */
internal class BTint(val bg: Int, val ink: Int)

/** Palette « Bento sombre » (identique aux maquettes). */
internal object BPal {
    private fun argb(v: Long): Int = v.toInt()

    val TXT = argb(0xFFEAF1F7)
    val MUT = argb(0xFFA3B4C5)
    val WARN = argb(0xFFFBBF24)
    val GREEN = argb(0xFF5FD08A)
    val RAIN_DEFAULT = argb(0xFF7C8CFF)
    val STORM_PURPLE = argb(0xFFB26BFF)
    val SNOW_ICE = argb(0xFF9ADBF5)
    val HEAT = argb(0xFFF2994A)

    val PREV = BTint(argb(0xFF17304A), argb(0xFFBBD9F5))
    val RAIN = BTint(argb(0xFF25295C), argb(0xFFCBD0FF))
    val WIND = BTint(argb(0xFF2F2A55), argb(0xFFD7D0FF))
    val HUM = BTint(argb(0xFF153A52), argb(0xFFBFE4F8))
    val PRESS = BTint(argb(0xFF3F3B15), argb(0xFFEEE2A0))
    val SNOW = BTint(argb(0xFF213645), argb(0xFFCFE5F2))
    val TOM = BTint(argb(0xFF143D44), argb(0xFFBDE8EF))
    val SUN = BTint(argb(0xFF4D3516), argb(0xFFFFD9A0))
    val AIR = BTint(argb(0xFF153D26), argb(0xFFC2F0D2))
    val HOME = BTint(argb(0xFF2A323C), argb(0xFFD0DAE4))
    val STORM = BTint(argb(0xFF2D2358), argb(0xFFDCD3FF))
    val WEEK = BTint(argb(0xFF1C2733), argb(0xFFD6E1EC))
    val POLLEN = BTint(argb(0xFF3A3514), argb(0xFFF5E7A0))
    val OFF = BTint(argb(0xFF262C35), argb(0xFFB8C2CE))

    // Héros selon la situation.
    val H_FAIR = BTint(argb(0xFF123A38), argb(0xFFBDEFE9))
    val H_NIGHT = BTint(argb(0xFF10223F), argb(0xFFBFD2F2))
    val H_RAIN = BTint(argb(0xFF173257), argb(0xFFC4DBF7))
    val H_SNOW = BTint(argb(0xFF1F3A4B), argb(0xFFD3E9F5))
    val H_HEAT = BTint(argb(0xFF4A2D17), argb(0xFFFFD9B0))
    val H_STORM = BTint(argb(0xFF2B2352), argb(0xFFD9D0FF))
    val H_OFF = BTint(argb(0xFF262C35), argb(0xFFB8C2CE))

    /** Échelle de 5 niveaux (pollens, indice d'air). */
    val SCALE5 = listOf(argb(0xFF3FBF7F), argb(0xFFA8D65C), argb(0xFFF2C94C), argb(0xFFF2994A), argb(0xFFEB5757))
    val UV = listOf(argb(0xFF3FBF7F), argb(0xFFA8D65C), argb(0xFFF2C94C), argb(0xFFF2994A), argb(0xFFB26BFF))
    val ICE_BLUE = argb(0xFF9FE3FF)
    val WARM_TREND = argb(0xFFFDBA74)
    val COOL_TREND = argb(0xFF7CC0FF)
    val DARK = argb(0xFF0E141C)
}

internal fun bAlpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a * 255f).roundToInt().coerceIn(0, 255) shl 24)

internal fun bMix(a: Int, b: Int, f: Float): Int {
    fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f)).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal fun bFill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.FILL
}

internal fun bStroke(color: Int, w: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = w
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** DM Sans variable (axes graisse et taille optique), repli sur les 3 graisses fixes si le système ne sait pas la charger. */
internal class BFonts(ctx: Context, val regular: Typeface, val medium: Typeface, val bold: Typeface) {
    private val assets = ctx.applicationContext.assets
    private val cache = HashMap<Int, Typeface>()

    fun face(wght: Int, size: Float): Typeface {
        val opsz = size.roundToInt().coerceIn(9, 40)
        return synchronized(cache) { cache.getOrPut(wght * 100 + opsz) { build(wght, opsz) } }
    }

    private fun build(wght: Int, opsz: Int): Typeface {
        try {
            val t = Typeface.Builder(assets, "fonts/DMSans-Variable.ttf")
                .setFontVariationSettings("'wght' $wght, 'opsz' $opsz")
                .build()
            if (t != null) return t
        } catch (e: Exception) {
            // repli ci-dessous
        }
        return if (wght < 450) regular else if (wght < 600) medium else bold
    }

    companion object {
        @Volatile
        private var cached: BFonts? = null

        fun get(ctx: Context): BFonts {
            cached?.let { return it }
            val loaded = BFonts(
                ctx,
                load(ctx, R.font.dmsans_regular, Typeface.DEFAULT),
                load(ctx, R.font.dmsans_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL)),
                load(ctx, R.font.dmsans_bold, Typeface.DEFAULT_BOLD),
            )
            cached = loaded
            return loaded
        }

        private fun load(ctx: Context, res: Int, fallback: Typeface): Typeface =
            try {
                ctx.resources.getFont(res)
            } catch (e: Exception) {
                fallback
            }
    }
}

// Graisses (police variable) : légères, pour un rendu moderne sur fond sombre.
internal const val W_HERO = 340
internal const val W_VAL = 500
internal const val W_UNIT = 440
internal const val W_LABEL = 440
internal const val W_SUB = 400
internal const val W_PLACE = 560
internal const val W_STATUS = 420
internal const val W_PILL = 540
internal const val W_STRONG = 520

/** Peinture de texte dans la graisse demandée. */
internal fun bTw(f: BFonts, wght: Int, color: Int, size: Float, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = f.face(wght, size)
        this.color = color
        textSize = size
        textAlign = align
        letterSpacing = spacing
    }

private val dropPathData = "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z"
private val dropPath: Path by lazy { PathParser.createPathFromPathData(dropPathData) }

/** Surface de dessin d'un widget : canvas déjà mis à l'échelle (1 unité = 1 dp) + outils communs. */
internal class BGx(val ctx: Context, val f: BFonts, val c: Canvas, val k: Float) {

    /** Icône vectorielle teintée, centrée en (cx, cy). */
    fun icon(res: Int, cx: Float, cy: Float, size: Float, color: Int, rot: Float = 0f) {
        val d = ctx.getDrawable(res)?.mutate() ?: return
        d.setTint(color)
        c.save()
        c.scale(1f / k, 1f / k)
        val px = cx * k
        val py = cy * k
        if (rot != 0f) c.rotate(rot, px, py)
        val half = size * k / 2f
        d.setBounds((px - half).roundToInt(), (py - half).roundToInt(), (px + half).roundToInt(), (py + half).roundToInt())
        d.draw(c)
        c.restore()
    }

    /** Écrit `s` avec le milieu de la ligne en `cy`. Renvoie la largeur. */
    fun text(
        s: String, x: Float, cy: Float, size: Float, wght: Int, color: Int,
        align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f,
    ): Float {
        if (s.isEmpty()) return 0f
        val p = bTw(f, wght, color, size, align, spacing)
        c.drawText(s, x, cy + 0.345f * size, p)
        return p.measureText(s)
    }

    /** Écrit `s` sur la ligne de base `base`. */
    fun textBase(
        s: String, x: Float, base: Float, size: Float, wght: Int, color: Int,
        align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f,
    ): Float {
        if (s.isEmpty()) return 0f
        val p = bTw(f, wght, color, size, align, spacing)
        c.drawText(s, x, base, p)
        return p.measureText(s)
    }

    fun width(s: String, size: Float, wght: Int, spacing: Float = 0f): Float = bTw(f, wght, 0, size, spacing = spacing).measureText(s)

    /** Réduit la taille jusqu'à ce que `s` tienne dans `maxW` (sans passer sous `minSize`). */
    fun fitSize(s: String, size: Float, wght: Int, maxW: Float, minSize: Float, spacing: Float = 0f): Float {
        var sz = size
        while (sz > minSize && width(s, sz, wght, spacing) > maxW) sz -= 0.5f
        return sz
    }

    /** Coupe `s` avec une ellipse si elle dépasse `maxW`. */
    fun ellipsize(s: String, size: Float, wght: Int, maxW: Float): String {
        val p = bTw(f, wght, 0, size)
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, max(10f, maxW - p.measureText("…")), null)
        return s.take(n).trimEnd() + "…"
    }

    fun round(r: RectF, rad: Float, color: Int) = c.drawRoundRect(r, rad, rad, bFill(color))

    fun round(x: Float, y: Float, w: Float, h: Float, rad: Float, color: Int) = round(RectF(x, y, x + w, y + h), rad, color)

    /** Libellé d'une tuile : icône + texte, à (x + 10, y + 8). Renvoie l'abscisse de la fin du texte. */
    fun label(res: Int, text: String, ink: Int, x: Float, y: Float, padL: Float = 10f, padT: Float = 8f, size: Float = 11.5f): Float {
        icon(res, x + padL + 6.5f, y + padT + 6.5f, 13f, ink)
        val tx = x + padL + 13f + 5f
        val w = text(text, tx, y + padT + 6.5f, size, W_LABEL, ink)
        return tx + w
    }

    /** Valeur + unité (ligne dont le milieu est en `cy`). Renvoie la fin. */
    fun value(v: String, unit: String, x: Float, cy: Float, size: Float, color: Int, unitSize: Float = 11.5f): Float {
        val w = text(v, x, cy, size, W_STRONG, color, spacing = -0.02f)
        if (unit.isEmpty()) return x + w
        val uw = textBase(unit, x + w + 2f, cy + 0.345f * size, unitSize, W_UNIT, color)
        return x + w + 2f + uw
    }

    // ───────────────────────────── Formes ─────────────────────────────

    /** Anneau de progression : arc depuis le haut, texte au centre (et sous-texte éventuel). */
    fun ring(
        cx: Float, cy: Float, size: Float, frac: Float, color: Int, sw: Float,
        center: String = "", sub: String = "", tsize: Float = 13f, tcolor: Int = BPal.TXT,
    ) {
        val r = (size - sw) / 2f
        c.drawCircle(cx, cy, r, bStroke(bAlpha(BPal.TXT, 0.13f), sw))
        val fr = frac.coerceIn(0f, 1f)
        if (fr > 0f) c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * fr, false, bStroke(color, sw))
        if (center.isEmpty()) return
        if (sub.isEmpty()) {
            val sz = fitSize(center, tsize, W_STRONG, size - sw * 2f - 2f, 8f, -0.02f)
            text(center, cx, cy, sz, W_STRONG, tcolor, Paint.Align.CENTER, -0.02f)
        } else {
            val ssz = max(8f, tsize * 0.62f)
            val top = cy - (tsize + 10f) / 2f
            text(center, cx, top + tsize / 2f, tsize, W_STRONG, tcolor, Paint.Align.CENTER, -0.02f)
            text(sub, cx, top + tsize + 1f + 4.5f, ssz, W_LABEL, bAlpha(tcolor, 0.8f), Paint.Align.CENTER)
        }
    }

    /** Barres alignées en bas : valeur nulle = trait gris, sinon au moins `minH`. */
    fun bars(
        x: Float, y: Float, w: Float, h: Float, vals: List<Double>, colors: List<Int>,
        gap: Float, rad: Float, top: Double, minH: Float = 2.5f,
    ) {
        val n = vals.size
        if (n == 0) return
        val bw = (w - gap * (n - 1)) / n
        val mx = if (top > 1e-9) top else 1e-9
        vals.forEachIndexed { i, v ->
            val bh = if (v > 0.0) max(minH, (h * v / mx).toFloat()).coerceAtMost(h) else minH
            val col = if (v > 0.0) colors[i] else bAlpha(BPal.TXT, 0.14f)
            val bx = x + i * (bw + gap)
            c.drawRoundRect(RectF(bx, y + h - bh, bx + bw, y + h), min(rad, bw / 2f), min(rad, bw / 2f), bFill(col))
        }
    }

    /** Échelle graduée (UV, pollens…) avec un repère triangulaire sous la valeur. */
    fun scaleBar(x: Float, y: Float, w: Float, cols: List<Int>, frac: Float, h: Float = 6f, gap: Float = 2f) {
        val n = cols.size
        val sw = (w - gap * (n - 1)) / n
        cols.forEachIndexed { i, col ->
            val sx = x + i * (sw + gap)
            c.drawRoundRect(RectF(sx, y, sx + sw, y + h), 3f, 3f, bFill(col))
        }
        val mx = max(4f, min(w - 4f, w * frac))
        val tri = Path().apply {
            moveTo(x + mx, y + h + 1.5f)
            lineTo(x + mx - 3.6f, y + h + 7.1f)
            lineTo(x + mx + 3.6f, y + h + 7.1f)
            close()
        }
        c.drawPath(tri, bFill(BPal.TXT))
    }

    /** Goutte d'eau remplie à `pct` %. */
    fun drop(cx: Float, cy: Float, size: Float, pct: Double, trackAlpha: Float = 0.12f) {
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(size / 24f, size / 24f)
        c.save()
        c.clipPath(dropPath)
        c.drawRect(0f, 0f, 24f, 24f, bFill(bAlpha(BPal.TXT, trackAlpha)))
        val level = (pct / 100.0).coerceIn(0.0, 1.0).toFloat()
        c.drawRect(0f, 22f - 20f * level, 24f, 24f, bFill(0xFF4FB3EE.toInt()))
        c.restore()
        c.drawPath(dropPath, bStroke(0xFFBFE4F8.toInt(), 1.5f))
        c.restore()
    }

    /** Boussole : la flèche indique où va le vent (direction d'où il vient + 180°). */
    fun compass(cx: Float, cy: Float, size: Float, fromDeg: Double, ink: Int) {
        val r = size / 2f
        c.drawCircle(cx, cy, r - 1f, bFill(bAlpha(BPal.TXT, 0.06f)))
        c.drawCircle(cx, cy, r - 1f, bStroke(bAlpha(ink, 0.35f), 1.2f))
        c.save()
        c.translate(cx, cy)
        c.rotate(((fromDeg + 180.0) % 360.0).toFloat())
        val p = bStroke(ink, 2f)
        c.drawLine(0f, r * 0.42f, 0f, -r * 0.42f, p)
        val head = Path().apply {
            moveTo(-r * 0.2f, -r * 0.16f)
            lineTo(0f, -r * 0.46f)
            lineTo(r * 0.2f, -r * 0.16f)
        }
        c.drawPath(head, p)
        c.restore()
    }

    /** Flèche de tendance (hausse ou baisse), centrée en (cx, cy). */
    fun trend(cx: Float, cy: Float, size: Float, up: Boolean, color: Int, sw: Float = 2.4f) {
        c.save()
        c.translate(cx, cy)
        val s = size / 24f
        c.scale(s, if (up) s else -s)
        c.translate(-12f, -12f)
        val path = Path().apply {
            moveTo(22f, 7f); lineTo(13.5f, 15.5f); lineTo(8.5f, 10.5f); lineTo(2f, 17f)
            moveTo(16f, 7f); lineTo(22f, 7f); lineTo(22f, 13f)
        }
        c.drawPath(path, bStroke(color, sw))
        c.restore()
    }

    /**
     * Courbe lissée dans (x, y, w, h). Les points jusqu'à `nowIdx` sont des mesures (trait plein, remplissage),
     * les suivants une prévision (pointillés). `zero` trace la ligne 0 ° et `below` teinte la zone sous zéro.
     */
    fun spark(
        x: Float, y: Float, w: Float, h: Float, vals: List<Double>, color: Int,
        nowIdx: Int? = null, lo: Double? = null, hi: Double? = null, pad: Float = 3f,
        fill: Boolean = true, sw: Float = 1.8f, dot: Boolean = true, zero: Boolean = false, below: Int? = null,
    ) {
        val n = vals.size
        if (n < 2) return
        var vLo = lo ?: vals.min()
        var vHi = hi ?: vals.max()
        if (vHi - vLo < 1e-9) vHi = vLo + 1.0
        val xs = FloatArray(n) { x + pad + it * (w - 2f * pad) / (n - 1) }
        val ys = FloatArray(n) { y + h - pad - ((vals[it] - vLo) / (vHi - vLo)).toFloat() * (h - 2f * pad) }
        val k = nowIdx?.coerceIn(0, n - 1) ?: (n - 1)

        c.save()
        c.clipRect(x - 8f, y - 8f, x + w + 8f, y + h + 8f)
        if (zero && vLo < 0.0 && vHi > 0.0) {
            val zy = y + h - pad - ((0.0 - vLo) / (vHi - vLo)).toFloat() * (h - 2f * pad)
            val dash = bStroke(bAlpha(BPal.TXT, 0.28f), 1f).apply { pathEffect = DashPathEffect(floatArrayOf(2f, 3f), 0f) }
            c.drawLine(x + pad, zy, x + w - pad, zy, dash)
            if (below != null) {
                c.save()
                c.clipRect(x - 8f, zy, x + w + 8f, y + h + 8f)
                val area = smooth(xs, ys, 0, n - 1)
                area.lineTo(xs[n - 1], zy)
                area.lineTo(xs[0], zy)
                area.close()
                c.drawPath(area, bFill(below))
                c.restore()
            }
        }
        if (fill && k >= 1) {
            val area = smooth(xs, ys, 0, k)
            area.lineTo(xs[k], y + h - pad)
            area.lineTo(xs[0], y + h - pad)
            area.close()
            val shader = LinearGradient(0f, y, 0f, y + h, bAlpha(color, 0.28f), bAlpha(color, 0f), Shader.TileMode.CLAMP)
            c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        }
        if (k >= 1) c.drawPath(smooth(xs, ys, 0, k), bStroke(color, sw))
        if (nowIdx != null && k < n - 1) {
            val dashed = bStroke(bAlpha(color, 0.75f), sw).apply { pathEffect = DashPathEffect(floatArrayOf(1f, 4.5f), 0f) }
            c.drawPath(smooth(xs, ys, k, n - 1), dashed)
        }
        if (dot) {
            c.drawCircle(xs[k], ys[k], 6f, bFill(bAlpha(color, 0.22f)))
            c.drawCircle(xs[k], ys[k], 3f, bFill(color))
            c.drawCircle(xs[k], ys[k], 3f, bStroke(BPal.DARK, 1.2f))
        }
        c.restore()
    }

    /** Courbe de Catmull-Rom (équivalent des Bézier cubiques des maquettes) entre les points `from` et `to`. */
    private fun smooth(xs: FloatArray, ys: FloatArray, from: Int, to: Int): Path {
        val p = Path()
        p.moveTo(xs[from], ys[from])
        if (to - from < 1) return p
        if (to - from < 2) {
            p.lineTo(xs[to], ys[to])
            return p
        }
        for (i in from until to) {
            val i0 = max(from, i - 1)
            val i3 = min(to, i + 2)
            val c1x = xs[i] + (xs[i + 1] - xs[i0]) / 6f
            val c1y = ys[i] + (ys[i + 1] - ys[i0]) / 6f
            val c2x = xs[i + 1] - (xs[i3] - xs[i]) / 6f
            val c2y = ys[i + 1] - (ys[i3] - ys[i]) / 6f
            p.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
        }
        return p
    }
}
