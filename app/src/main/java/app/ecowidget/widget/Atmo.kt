package app.ecowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.width
import app.ecowidget.R
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.OmFormat
import app.ecowidget.data.Tile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Zone cliquable, en dp réels du widget. `tile` null : actualiser. */
internal class AZone(val x: Float, val y: Float, val w: Float, val h: Float, val desc: String, val tile: Tile?, val tab: Int)

/** Positions verticales (repère du dessin, largeur de base 360). */
internal class ALay(val modH: Float, val yHours: Float?, val yDays: Float?, val yBottom: Float)

internal class AtmoPlan(val w: Float, val h: Float, val scale: Float, val model: AModel, val lay: ALay, val zones: List<AZone>)

/**
 * Widget « Atmosphère » : reproduction des maquettes « Air et pollens » (largeur de base 360, marge 12, rayon 28).
 * Le dessin entier est réduit ou agrandi uniformément pour la largeur et la hauteur du widget. Quand la hauteur manque,
 * on retire d'abord les 5 jours, puis les heures ; en dessous de 78 % de réduction, le Bento reprend la main.
 */
internal object Atmo {
    private const val BASE_W = 360f
    private const val PX = 12f
    private const val MOD_Y = 114f
    private const val GAP = 6f
    private const val HOURS_H = 78f
    private const val DAYS_H = 84f
    private const val BOTTOM_H = 100f
    private const val MIN_SCALE = 0.78f
    private const val MIN_WSCALE = 0.70f
    private const val MAX_PIXELS = 1_500_000f

    private val TX = AP.TX
    private val T2 = AP.T2
    private val DARK = AP.DARK
    private const val WHITE = 0xFFFFFFFF.toInt()

    private class Th(val top: Int, val bot: Int, val glow: Int, val accent: Int)

    private fun argb(a: Float, r: Int, g: Int, b: Int): Int = ((a * 255f).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b

    private fun theme(s: Scn): Th = when (s) {
        Scn.DRY -> Th(0xFF0F1B2A.toInt(), 0xFF0A111D.toInt(), argb(.30f, 56, 189, 176), 0xFF5EE0CF.toInt())
        Scn.ANNOUNCED -> Th(0xFF111B36.toInt(), 0xFF0A1022.toInt(), argb(.38f, 96, 140, 255), 0xFF8DB2FF.toInt())
        Scn.RAIN -> Th(0xFF141538.toInt(), 0xFF0A0D24.toInt(), argb(.36f, 120, 110, 255), 0xFF7DBBFF.toInt())
        Scn.WINTER -> Th(0xFF142439.toInt(), 0xFF0B1524.toInt(), argb(.32f, 160, 215, 255), 0xFFBFE3FF.toInt())
        Scn.HEAT -> Th(0xFF2B1710.toInt(), 0xFF150C0A.toInt(), argb(.42f, 255, 140, 60), 0xFFFFB36B.toInt())
        Scn.OFFLINE -> Th(0xFF1B1E24.toInt(), 0xFF101216.toInt(), argb(.20f, 150, 160, 175), 0xFFA9B3C2.toInt())
        Scn.STORM -> Th(0xFF1F1239.toInt(), 0xFF0D0919.toInt(), argb(.28f, 255, 196, 70), 0xFFFFC857.toInt())
    }

    private fun moduleBase(m: AMod): Float = when (m) {
        is AMod.Rain -> 146f
        is AMod.Snow -> 154f
        is AMod.Storm -> 104f
        else -> 100f
    }

    // ───────────────────────────── Mise en page ─────────────────────────────

    /** `w` × `h` : taille réelle du widget, en dp. Null si trop petit : le Bento prend alors le relais. */
    fun plan(ctx: Context, i: DetailInputs, placeName: String?, w: Float, h: Float, ageMin: Int?): AtmoPlan? {
        if (w / BASE_W < MIN_WSCALE) return null
        val m = AtmoData.build(ctx, i, ageMin, placeName) ?: return null
        val base = moduleBase(m.mod)
        var withDays = m.days.size >= 5
        var withHours = m.hours.size >= 5
        fun need(): Float = MOD_Y + base + (if (withHours) GAP + HOURS_H else 0f) + (if (withDays) GAP + DAYS_H else 0f) + GAP + BOTTOM_H + PX
        if (h / need() < MIN_SCALE && withDays) withDays = false
        if (h / need() < MIN_SCALE && withHours) withHours = false
        if (h / need() < MIN_SCALE) return null
        val s = min(w / BASE_W, h / need())
        val vw = w / s
        val vh = h / s
        val modH = base + min(40f, vh - need()).coerceAtLeast(0f)

        var y = MOD_Y + modH + GAP
        val yHours = if (withHours) y.also { y += HOURS_H + GAP } else null
        val yDays = if (withDays) y.also { y += DAYS_H + GAP } else null
        val lay = ALay(modH, yHours, yDays, y)

        val z = ArrayList<AZone>()
        fun zone(x: Float, yy: Float, ww: Float, hh: Float, desc: String, tile: Tile?, tab: Int) {
            z.add(AZone(x * s, yy * s, ww * s, hh * s, desc, tile, tab))
        }
        zone(vw - PX - 36f, 4f, 36f, 40f, "Actualiser", null, 0)
        zone(PX, 42f, vw * 0.5f - PX, 66f, "Température : détail", Tile.TEMP, 0)
        zone(vw * 0.5f, 42f, vw * 0.5f - PX, 66f, "Vent : détail", Tile.WIND, 0)
        val modTile: Tile? = when (m.mod) {
            is AMod.Snow -> Tile.SNOW
            is AMod.Rain, is AMod.Storm -> Tile.RAIN
            is AMod.Offline -> null
            else -> Tile.FORECAST
        }
        zone(PX, MOD_Y, vw - 2 * PX, modH, "Situation : détail", modTile, if (modTile == Tile.RAIN) 1 else 0)
        if (yHours != null) zone(PX, yHours, vw - 2 * PX, HOURS_H, "Prochaines heures", Tile.FORECAST, 0)
        if (yDays != null) zone(PX, yDays, vw - 2 * PX, DAYS_H, "Prochains jours", Tile.FORECAST, 1)
        zone(PX, lay.yBottom, 150f, BOTTOM_H, "Qualité de l'air : détail", Tile.AIR, 0)
        zone(PX + 156f, lay.yBottom, vw - 2 * PX - 156f, BOTTOM_H, "Pollens : détail", Tile.AIR, 0)
        return AtmoPlan(w, h, s, m, lay, z)
    }

    // ───────────────────────────── Dessin ─────────────────────────────

    fun render(ctx: Context, plan: AtmoPlan, density: Float): Bitmap {
        val k = min(density, sqrt(MAX_PIXELS / (plan.w * plan.h)))
        val bmp = Bitmap.createBitmap(max(1, (plan.w * k).roundToInt()), max(1, (plan.h * k).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(k, k)
        val m = plan.model
        val th = theme(m.scn)
        val s = plan.scale
        background(c, plan.w, plan.h, th, 28f * s)
        c.scale(s, s)
        val g = BGx(ctx, BFonts.get(ctx), c, k * s)
        val vw = plan.w / s
        guard { header(g, m, vw) }
        guard { hero(g, m, vw, th) }
        guard { module(g, m, vw, plan.lay.modH, th) }
        plan.lay.yHours?.let { y -> guard { hours(g, m, vw, y) } }
        plan.lay.yDays?.let { y -> guard { days(g, m, vw, y) } }
        guard { airCard(g, m, PX, plan.lay.yBottom) }
        guard { pollenCard(g, m, PX + 156f, plan.lay.yBottom, vw - 2 * PX - 156f) }
        return bmp
    }

    /** Un bloc défaillant ne doit pas vider tout le widget. */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // ignoré
        }
    }

    private fun background(c: Canvas, w: Float, h: Float, th: Th, rad: Float) {
        val path = Path().apply { addRoundRect(RectF(0f, 0f, w, h), rad, rad, Path.Direction.CW) }
        c.save()
        c.clipPath(path)
        // linear-gradient(165deg, haut, bas)
        val a = Math.toRadians(165.0)
        val dx = Math.sin(a).toFloat()
        val dy = (-Math.cos(a)).toFloat()
        val len = w * dx + h * dy
        val cx = w / 2f
        val cy = h / 2f
        c.drawRect(
            0f, 0f, w, h,
            Paint().apply { shader = LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f, th.top, th.bot, Shader.TileMode.CLAMP) },
        )
        // radial-gradient(120% 80% at 0% 0%, lueur 0%, transparent 62%)
        c.save()
        c.scale(1.2f * w, 0.8f * h)
        c.drawRect(
            0f, 0f, 1f / 1.2f, 1f / 0.8f,
            Paint().apply {
                shader = RadialGradient(0f, 0f, 1f, intArrayOf(th.glow, bAlpha(th.glow, 0f), bAlpha(th.glow, 0f)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
            },
        )
        c.restore()
        c.restore()
    }

    // ── Petits outils

    private fun fill(g: BGx, x: Float, y: Float, w: Float, h: Float, rad: Float, color: Int) {
        g.c.drawRoundRect(RectF(x, y, x + w, y + h), rad, rad, bFill(color))
    }

    private fun card(g: BGx, x: Float, y: Float, w: Float, h: Float, rad: Float = 20f) {
        fill(g, x, y, w, h, rad, bAlpha(WHITE, 0.065f))
        g.c.drawRoundRect(RectF(x + .5f, y + .5f, x + w - .5f, y + h - .5f), rad, rad, bStroke(bAlpha(WHITE, 0.07f), 1f))
    }

    private fun dashed(col: Int, w: Float, a: Float, b: Float, round: Boolean = false): Paint =
        bStroke(col, w).apply {
            pathEffect = DashPathEffect(floatArrayOf(a, b), 0f)
            if (!round) strokeCap = Paint.Cap.BUTT
        }

    private class Run(val t: String, val size: Float, val w: Int, val col: Int, val sp: Float = 0f)

    private fun runsW(g: BGx, runs: List<Run>): Float = runs.sumOf { g.width(it.t, it.size, it.w, it.sp).toDouble() }.toFloat()

    /** Segments côte à côte sur la même ligne de base. `right` : `x` est le bord droit. Renvoie la largeur. */
    private fun runs(g: BGx, x: Float, base: Float, list: List<Run>, right: Boolean = false): Float {
        val total = runsW(g, list)
        var xx = if (right) x - total else x
        list.forEach { r ->
            if (r.t.isNotEmpty()) xx += g.textBase(r.t, xx, base, r.size, r.w, r.col, spacing = r.sp)
        }
        return total
    }

    /** Ligne de base d'un texte dont le milieu est en `cy`. */
    private fun bl(cy: Float, size: Float): Float = cy + 0.345f * size

    private fun ng(v: Double): String = Math.round(v).toString().replace('-', '−') + "°"

    /** Chemin lissé (Catmull-Rom → Bézier), comme les maquettes. */
    private fun smooth(xs: FloatArray, ys: FloatArray): Path {
        val p = Path()
        val n = xs.size
        p.moveTo(xs[0], ys[0])
        for (k in 0 until n - 1) {
            val i0 = max(0, k - 1)
            val i3 = min(n - 1, k + 2)
            p.cubicTo(
                xs[k] + (xs[k + 1] - xs[i0]) / 6f, ys[k] + (ys[k + 1] - ys[i0]) / 6f,
                xs[k + 1] - (xs[i3] - xs[k]) / 6f, ys[k + 1] - (ys[i3] - ys[k]) / 6f,
                xs[k + 1], ys[k + 1],
            )
        }
        return p
    }

    // ───────────────────────────── En-tête ─────────────────────────────

    private fun header(g: BGx, m: AModel, vw: Float) {
        val c = g.c
        val cy = 24f
        val pw = g.text(m.place, PX, cy, 13f, 600, TX)
        if (m.alt.isNotEmpty()) g.textBase(m.alt, PX + pw + 6f, bl(cy, 13f), 11f, 500, T2)
        // Actualiser
        c.drawCircle(vw - PX - 14f, cy, 14f, bFill(bAlpha(WHITE, 0.08f)))
        g.icon(R.drawable.ic_refresh, vw - PX - 14f, cy, 14f, T2)
        // Pastille
        val p = m.pill
        val ic = p.icon
        val maxW = vw - 2 * PX - 28f - 6f - pw - 14f - 40f
        val txt = g.ellipsize(p.text, 11f, 600, maxW - 8f - 12f - 6f - 10f)
        val tw = g.width(txt, 11f, 600)
        val iw = if (ic != null) 12f else 7f
        val cw = 8f + iw + 6f + tw + 10f
        val right = vw - PX - 28f - 6f
        val left = right - cw
        val bg = if (ic != null) bAlpha(p.tint, 0.17f) else bAlpha(WHITE, 0.08f)
        fill(g, left, cy - 11f, cw, 22f, 11f, bg)
        if (ic != null) {
            g.icon(ic, left + 8f + 6f, cy, 12f, th(m).accent)
        } else {
            c.drawCircle(left + 8f + 3.5f, cy, 6.5f, bFill(bAlpha(p.tint, 0.2f)))
            c.drawCircle(left + 8f + 3.5f, cy, 3.5f, bFill(p.tint))
        }
        g.text(txt, left + 8f + iw + 6f, cy, 11f, 600, TX)
    }

    private fun th(m: AModel): Th = theme(m.scn)

    // ───────────────────────────── Température ─────────────────────────────

    private fun hero(g: BGx, m: AModel, vw: Float, th: Th) {
        val c = g.c
        val tcol = if (m.offline) T2 else TX
        val tw = g.textBase(m.heroValue, PX, 99.7f, 52f, 600, tcol, spacing = -2.5f / 52f)
        g.textBase("°", PX + tw, 83.5f, 28f, 500, th.accent)
        if (m.offline) {
            val bw = g.width("DERNIÈRE VALEUR", 10.5f, 700) + 14f
            fill(g, PX, 43f, bw, 15f, 6f, AP.GR)
            g.text("DERNIÈRE VALEUR", PX + 7f, 50.5f, 10.5f, 700, 0xFF101216.toInt())
        }
        val xr = vw - PX
        // Ressenti
        m.feel?.let { f -> runs(g, xr, bl(55f, 12f), listOf(Run("Ressenti ", 12f, 400, T2), Run(f, 12f, 600, TX)), right = true) }
        // Min et max prévus du jour, juste à droite de la température
        val lo = m.lo
        val hi = m.hi
        if (lo != null && hi != null) {
            val tx = PX + tw + g.width("°", 28f, 500) + 8f
            runs(g, tx, 79f, listOf(Run("max ", 10.5f, 400, T2), Run(ng(hi), 12f, 700, 0xFFFFB36B.toInt())))
            runs(g, tx, 95f, listOf(Run("min ", 10.5f, 400, T2), Run(ng(lo), 12f, 700, 0xFF8EC9FF.toInt())))
            // Barre : où se situe la température actuelle entre le min et le max
            val bx = xr - 92f
            val cy = 75.5f
            val gradient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(bx, 0f, bx + 92f, 0f, intArrayOf(0xFF6CB6FF.toInt(), 0xFFF2C14E.toInt(), 0xFFFF8A4C.toInt()), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(RectF(bx, cy - 3f, bx + 92f, cy + 3f), 3f, 3f, gradient)
            val cur = m.cur
            val pos = if (cur != null && hi > lo) ((cur - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) else 0.5f
            val kx = bx + 92f * pos
            c.drawCircle(kx, cy, 6f, bFill(DARK))
            c.drawCircle(kx, cy, 4f, bFill(WHITE))
        }
        // Pastilles : vent et information de la situation
        val cy = 97.2f
        val ch = 17.7f
        val c2 = m.chip2
        val runs2 = if (c2.bold.isEmpty()) {
            listOf(Run(c2.pre, 10.5f, 600, TX))
        } else {
            listOf(Run(c2.pre, 10.5f, 600, TX), Run(c2.bold, 10.5f, 700, c2.boldColor ?: TX))
        }
        val w2 = runsW(g, runs2) + 14f
        fill(g, xr - w2, cy - ch / 2f, w2, ch, 8f, bAlpha(WHITE, 0.09f))
        runs(g, xr - w2 + 7f, bl(cy, 10.5f), runs2)

        val wd = m.wind
        val windRuns = listOf(Run(wd.speed, 10.5f, 600, TX), Run(if (wd.dir.isNotEmpty()) " " + wd.dir else "", 10.5f, 500, T2))
        val w1 = 7f + 10f + 4f + runsW(g, windRuns) + 7f
        val x1 = xr - w2 - 5f - w1
        fill(g, x1, cy - ch / 2f, w1, ch, 8f, bAlpha(WHITE, 0.09f))
        arrow(g, x1 + 7f + 5f, cy, 10f, wd.deg ?: 0f, T2)
        runs(g, x1 + 7f + 10f + 4f, bl(cy, 10.5f), windRuns)
    }

    /** Flèche vers le haut (icône des maquettes), tournée de `deg`. */
    private fun arrow(g: BGx, cx: Float, cy: Float, size: Float, deg: Float, col: Int) {
        val c = g.c
        c.save()
        c.translate(cx, cy)
        c.rotate(deg)
        val k = size / 24f
        c.scale(k, k)
        val p = Path().apply {
            moveTo(0f, 8f)
            lineTo(0f, -7f)
            moveTo(-6f, -1f)
            lineTo(0f, -7f)
            lineTo(6f, -1f)
        }
        c.drawPath(p, bStroke(col, 2.4f))
        c.restore()
    }

    // ───────────────────────────── Module central ─────────────────────────────

    private fun module(g: BGx, m: AModel, vw: Float, modH: Float, th: Th) {
        val x = PX
        val w = vw - 2 * PX
        card(g, x, MOD_Y, w, modH)
        when (val md = m.mod) {
            is AMod.Curve -> curveModule(g, md, x, MOD_Y, w, modH, th)
            is AMod.Heat -> heatModule(g, md, x, MOD_Y, w, modH, th)
            is AMod.Offline -> offlineModule(g, md, x, MOD_Y, w, modH)
            is AMod.Rain -> rainModule(g, md, x, MOD_Y, w, th)
            is AMod.Snow -> snowModule(g, md, x, MOD_Y, w)
            is AMod.Storm -> stormModule(g, md, x, MOD_Y, w, modH)
        }
    }

    /** Courbe des maquettes : aire dégradée, trait plein jusqu'à maintenant puis pointillés, repère « maintenant », point de pic creux. */
    private fun curve(
        g: BGx, x: Float, y: Float, w: Float, h: Float, vals: List<Double>, now: Int, col: Int,
        vmin: Double, vmax: Double, pad: Float, thr: Double? = null, mark: Int? = null, zero: Boolean = false,
    ) {
        val c = g.c
        val n = vals.size
        if (n < 2) return
        val span = if (vmax - vmin < 1e-9) 1.0 else vmax - vmin
        fun yOf(v: Double): Float = y + pad + (1f - ((v - vmin) / span).toFloat()) * (h - 2 * pad)
        val xs = FloatArray(n) { x + pad + it * (w - 2 * pad) / (n - 1) }
        val ys = FloatArray(n) { yOf(vals[it]) }
        val ni = now.coerceIn(0, n - 1)
        val nx = xs[ni]
        if (zero && vmin < 0.0 && vmax > 0.0) {
            val y0 = yOf(0.0)
            c.drawRect(x, y0, x + w, y + h, bFill(bAlpha(AP.ICE, 0.13f)))
            c.drawLine(x, y0, x + w, y0, dashed(bAlpha(WHITE, 0.85f), 1f, 3f, 3f))
        }
        if (thr != null) {
            val ty = yOf(thr)
            c.drawLine(x, ty, x + w, ty, dashed(bAlpha(0xFFFF6B5A.toInt(), 0.85f), 1f, 3f, 3f))
        }
        val area = smooth(xs, ys)
        area.lineTo(xs[n - 1], y + h)
        area.lineTo(xs[0], y + h)
        area.close()
        c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, y, 0f, y + h, bAlpha(col, 0.32f), bAlpha(col, 0f), Shader.TileMode.CLAMP) })
        val line = smooth(xs, ys)
        c.save()
        c.clipRect(x - 4f, y - 6f, nx, y + h + 6f)
        c.drawPath(line, bStroke(col, 2.2f))
        c.restore()
        c.save()
        c.clipRect(nx, y - 6f, x + w + 4f, y + h + 6f)
        c.drawPath(line, dashed(col, 2f, 0.1f, 5f, round = true))
        c.restore()
        c.drawLine(nx, y + 2f, nx, y + h - 2f, bStroke(bAlpha(WHITE, 0.25f), 1f))
        c.drawCircle(nx, ys[ni], 4.2f, bFill(col))
        c.drawCircle(nx, ys[ni], 4.2f, bStroke(DARK, 2f))
        if (mark != null && mark in 0 until n) {
            c.drawCircle(xs[mark], ys[mark], 3f, bFill(DARK))
            c.drawCircle(xs[mark], ys[mark], 3f, bStroke(col, 1.6f))
        }
    }

    /** Étiquettes réparties de bord à bord. */
    private fun axis(g: BGx, x: Float, cy: Float, w: Float, labels: List<String>, size: Float = 10f) {
        if (labels.isEmpty()) return
        val ws = labels.map { g.width(it, size, 400) }
        val gap = if (labels.size > 1) (w - ws.sum()) / (labels.size - 1) else 0f
        var xx = x
        labels.forEachIndexed { k, s ->
            g.textBase(s, xx, bl(cy, size), size, 400, T2)
            xx += ws[k] + gap
        }
    }

    private fun curveModule(g: BGx, md: AMod.Curve, x: Float, y: Float, w: Float, h: Float, th: Th) {
        val ix = x + 9f
        val iw = w - 18f
        val top = y + 9f
        g.icon(md.icon, ix + 6.5f, top + 8f, 13f, th.accent)
        g.text(g.ellipsize(md.title, 12f, 600, iw * 0.62f), ix + 13f + 6f, top + 8f, 12f, 600, TX)
        if (md.rightBold.isNotEmpty() || md.rightPre.isNotEmpty()) {
            val boldCol = if (md.rightPre.isEmpty()) T2 else TX
            runs(g, ix + iw, bl(top + 8f, 11f), listOf(Run(md.rightPre, 11f, 400, T2), Run(md.rightBold, 11f, 600, boldCol), Run(md.rightPost, 11f, 400, T2)), right = true)
        }
        val ch = 46f + (h - 100f)
        val all = md.obs + md.fc
        if (all.size < 2) return
        curve(g, ix, top + 19f, iw, ch, all, md.obs.size - 1, th.accent, all.min(), all.max(), 5f, mark = md.mark, zero = md.zero)
        axis(g, ix, top + 19f + ch + 2f + 6.5f, iw, md.axis)
    }

    private fun heatModule(g: BGx, md: AMod.Heat, x: Float, y: Float, w: Float, h: Float, th: Th) {
        val c = g.c
        val ix = x + 9f
        val iw = w - 18f
        val k = 96f / 112f
        val gy = y + (h - 68f) / 2f
        val cx = ix + 56f * k
        val cy = gy + 64f * k
        val r = 44f * k
        val sw = 9f * k
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, 180f, 180f, false, bStroke(bAlpha(WHITE, 0.12f), sw))
        val frac = (md.uv / 11.0).toFloat().coerceIn(0f, 1f)
        val arcPaint = bStroke(0xFF7EE0A5.toInt(), sw).apply {
            shader = LinearGradient(cx - r, 0f, cx + r, 0f, intArrayOf(0xFF7EE0A5.toInt(), 0xFFF2C14E.toInt(), 0xFFF0566B.toInt()), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        }
        if (frac > 0f) c.drawArc(rect, 180f, 180f * frac, false, arcPaint)
        g.textBase(Math.round(md.uv).toString(), cx, gy + 58f * k, 26f * k, 700, TX, Paint.Align.CENTER)
        g.textBase(md.uvWord, cx, gy + 75f * k, 11f * k, 400, T2, Paint.Align.CENTER)

        val rx = ix + 96f + 8f
        val rw = ix + iw - rx
        val t0 = y + (h - 76f) / 2f
        g.textBase("Pic prévu", rx, bl(t0 + 7f, 11f), 11f, 400, T2)
        g.textBase(md.peak, rx + rw, bl(t0 + 7f, 11f), 11f, 600, th.accent, Paint.Align.RIGHT)
        val all = md.obs + md.fc
        if (all.size < 2) return
        val ch = 46f
        curve(g, rx, t0 + 15f, rw, ch, all, md.obs.size - 1, th.accent, min(all.min(), 26.0), max(all.max(), 34.0), 4f, thr = 30.0, mark = md.mark)
        axis(g, rx, t0 + 15f + ch + 2f + 6.5f, rw, md.axis)
    }

    private fun offlineModule(g: BGx, md: AMod.Offline, x: Float, y: Float, w: Float, h: Float) {
        val ix = x + 9f
        val iw = w - 18f
        val top = y + 9f
        fill(g, ix, top, 30f, 30f, 10f, bAlpha(WHITE, 0.09f))
        g.icon(R.drawable.ic_wifi_off, ix + 15f, top + 15f, 16f, TX)
        g.text("Station hors ligne", ix + 39f, top + 9f, 13f, 700, TX)
        // Bouton « Réessayer »
        val bw = g.width("Réessayer", 11.5f, 700) + 10f + 12f + 5f + 10f
        g.text(g.ellipsize(md.sub, 11f, 400, iw - 39f - bw - 8f), ix + 39f, top + 24f, 11f, 400, T2)
        fill(g, ix + iw - bw, top + 3f, bw, 25f, 12.5f, TX)
        g.icon(R.drawable.ic_refresh, ix + iw - bw + 10f + 6f, top + 15.5f, 12f, 0xFF101216.toInt())
        g.text("Réessayer", ix + iw - bw + 10f + 12f + 5f, top + 15.5f, 11.5f, 700, 0xFF101216.toInt())
        g.text("Prévision Open-Meteo en attendant", ix, top + 40f, 10.5f, 400, T2)
        val all = md.obs + md.fc
        if (all.size < 2) return
        curve(g, ix, top + 48f, iw, max(20f, h - 9f - 48f - 9f), all, md.obs.size - 1, AP.GR, all.min(), all.max(), 4f)
    }

    private fun stormModule(g: BGx, md: AMod.Storm, x: Float, y: Float, w: Float, h: Float) {
        val c = g.c
        val ix = x + 9f
        val iw = w - 18f
        val k = 84f / 116f
        val ry = y + (h - 84f) / 2f
        val rcx = ix + 42f
        val rcy = ry + 42f
        c.drawCircle(rcx, rcy, 54f * k, bFill(bAlpha(WHITE, 0.04f)))
        c.drawCircle(rcx, rcy, 54f * k, bStroke(bAlpha(WHITE, 0.14f), 1f))
        c.drawCircle(rcx, rcy, 36f * k, dashed(bAlpha(WHITE, 0.14f), 1f, 2f * k, 4f * k))
        c.drawCircle(rcx, rcy, 18f * k, dashed(bAlpha(WHITE, 0.14f), 1f, 2f * k, 4f * k))
        // Halo de l'orage attendu : plus fort à l'approche.
        val glow = if (md.minutes <= 0) 1f else (1f - md.minutes / 120f).coerceIn(0.25f, 1f)
        val gr = 30f * k * glow
        c.drawCircle(rcx, rcy, gr, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(rcx, rcy, gr, bAlpha(AP.AMB, 0.55f), bAlpha(AP.RED, 0f), Shader.TileMode.CLAMP) })
        c.drawCircle(rcx, rcy, 5f, bFill(WHITE))
        c.drawCircle(rcx, rcy, 5f, bStroke(0xFF0D0919.toInt(), 2f))

        val rx = ix + 84f + 12f
        val rw = ix + iw - rx
        val rowsH = md.stats.size * 17.5f
        var yy = y + (h - (21f + 4f + rowsH)) / 2f
        g.icon(R.drawable.ic_wx_storm, rx + 7.5f, yy + 10.5f, 15f, AP.AMB)
        val ow = g.text("Orage", rx + 15f + 6f, yy + 10.5f, 16f, 700, AP.AMB)
        val whenTxt = if (md.minutes <= 0) "· en cours" else "· dans " + OmFormat.delay(md.minutes)
        g.text(whenTxt, rx + 15f + 6f + ow + 6f, yy + 10.5f, 11f, 500, T2)
        yy += 21f + 4f
        md.stats.forEach { (l, v, col) ->
            c.drawLine(rx, yy, rx + rw, yy, bStroke(bAlpha(WHITE, 0.08f), 1f))
            g.textBase(l, rx, bl(yy + 9f, 11.5f), 11.5f, 400, T2)
            g.textBase(v, rx + rw, bl(yy + 9f, 11.5f), 11.5f, 600, col, Paint.Align.RIGHT)
            yy += 17.5f
        }
    }

    // ── Pluie

    private fun rainModule(g: BGx, md: AMod.Rain, x: Float, y: Float, w: Float, th: Th) {
        val ix = x + 9f
        val iw = w - 18f
        val top = y + 9f
        g.icon(R.drawable.ic_droplet, ix + 8f, top + 11f, 16f, th.accent)
        g.text(g.ellipsize(md.title, 17f, 700, iw * 0.78f), ix + 16f + 6f, top + 11f, 17f, 700, TX, spacing = -0.3f / 17f)
        g.text(g.ellipsize(md.sub, 11f, 400, iw * 0.74f), ix, top + 30f, 11f, 400, T2)
        if (md.bigRight.isNotEmpty()) {
            runs(g, ix + iw, bl(top + 12f, 19f), listOf(Run(md.bigRight, 19f, 700, th.accent), Run(md.bigUnit, 10f, 500, T2)), right = true)
        } else if (md.smallTop.isNotEmpty()) {
            g.textBase("Aujourd'hui", ix + iw, bl(top + 6f, 10f), 10f, 400, T2, Paint.Align.RIGHT)
            g.textBase(md.smallTop, ix + iw, bl(top + 21f, 15f), 15f, 700, th.accent, Paint.Align.RIGHT)
        }
        val py = top + 42f
        val pw = (iw - 6f) / 2f
        panel(g, md.minutes, ix, py, pw, 86f, 32f, 8f, 10f)
        panel(g, md.hours, ix + pw + 6f, py, pw, 86f, 32f, 8f, 10f)
    }

    /**
     * Graphique de pluie des maquettes : titre + cumul, barres (hauteur et couleur selon l'intensité, blanc pour la neige,
     * calotte blanche pour la mêlée), limite pluie-neige en pointillés, repères. Les deux graphiques gardent la même marge haute.
     */
    private fun panel(g: BGx, p: APanel, x: Float, y: Float, w: Float, h: Float, chartH: Float, pad: Float, tickFs: Float) {
        val c = g.c
        fill(g, x, y, w, h, 12f, bAlpha(WHITE, 0.05f))
        g.textBase(p.title, x + pad, bl(y + 12f, 10f), 10f, 400, T2)
        g.textBase(p.total, x + w - pad, bl(y + 12f, 12f), 12f, 700, p.totalColor, Paint.Align.RIGHT)
        val cx = x + pad
        val cw = w - 2 * pad
        val top = y + 23f + 11f
        val base = top + chartH
        val n = p.vals.size
        if (n == 0) return
        val gap = 3f
        val bw = (cw - gap * (n - 1)) / n
        p.vals.forEachIndexed { k, v ->
            val bx = cx + k * (bw + gap)
            if (v < 0.005) {
                fill(g, bx, base - 3f, bw, 3f, 1.5f, bAlpha(WHITE, 0.14f))
            } else {
                val bh = max(6f, (v / p.vmax).toFloat() * (chartH - 2f)).coerceAtMost(chartH)
                val col = AP.rainColor(v)
                val r = RectF(bx, base - bh, bx + bw, base)
                val rad = min(3f, bw / 2f)
                when (p.phases.getOrElse(k) { 0 }) {
                    2 -> c.drawRoundRect(r, rad, rad, bFill(AP.IC))
                    1 -> {
                        c.drawRoundRect(r, rad, rad, bFill(col))
                        c.drawRoundRect(RectF(r.left, r.top, r.right, r.top + bh * 0.55f), rad, rad, bFill(AP.IC))
                    }
                    else -> c.drawRoundRect(r, rad, rad, bFill(col))
                }
            }
        }
        // Limite pluie / neige
        p.lim?.takeIf { it.size == n }?.let { lim ->
            val lo = lim.min()
            val hi = lim.max()
            fun ly(v: Double): Float = top + (if (hi == lo) chartH * 0.30f else 6f + ((hi - v) / (hi - lo)).toFloat() * (chartH * 0.5f))
            val xs = FloatArray(n) { cx + it * (bw + gap) + bw / 2f }
            val ys = FloatArray(n) { ly(lim[it]) }
            if (n >= 2) c.drawPath(smooth(xs, ys), dashed(AP.LIMIT, 1.6f, 0.1f, 3.6f, round = true))
            fun lab(idx: Int, v: Double) {
                val s = fmtM(Math.round(v / 50.0) * 50L)
                val anc = if (idx < 2) Paint.Align.LEFT else if (idx > n - 3) Paint.Align.RIGHT else Paint.Align.CENTER
                val lx = xs[idx]
                val halo = bTw(g.f, 600, DARK, 8.5f, anc).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.6f
                    strokeJoin = Paint.Join.ROUND
                }
                c.drawText(s, lx, ly(v) - 4f, halo)
                c.drawText(s, lx, ly(v) - 4f, bTw(g.f, 600, AP.LIMIT, 8.5f, anc))
            }
            if (hi == lo) {
                lab(0, hi)
            } else {
                lab(lim.indexOf(hi), hi)
                lab(lim.indexOf(lo), lo)
            }
        }
        // Bascule en mêlée / neige
        p.flip?.let { k ->
            val fx = cx + k * (bw + gap) - gap / 2f
            c.drawLine(fx, y + 23f + 10f, fx, base, dashed(bAlpha(AP.IC, 0.8f), 1f, 2f, 2f))
            g.icon(R.drawable.ic_flake, fx, y + 23f + 4f, 10f, AP.IC)
        }
        // Repères
        val tcy = base + 2f + 6.5f
        p.ticks.forEachIndexed { k, (fr, t) ->
            val tx = cx + cw * fr
            val last = p.tickEdge && k == p.ticks.size - 1
            val tw = g.width(t, tickFs, 400)
            val px = if (last) tx else tx.coerceIn(x + 2f + tw / 2f, x + w - 2f - tw / 2f)
            g.textBase(t, px, bl(tcy, tickFs), tickFs, 400, T2, if (last) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
    }

    private fun fmtM(v: Long): String {
        val s = v.toString()
        val out = if (s.length > 3) s.dropLast(3) + " " + s.takeLast(3) else s
        return out + " m"
    }

    // ── Neige

    private fun snowModule(g: BGx, md: AMod.Snow, x: Float, y: Float, w: Float) {
        val ix = x + 9f
        val iw = w - 18f
        val top = y + 9f
        g.icon(R.drawable.ic_flake, ix + 8f, top + 11f, 16f, AP.SNOW)
        g.text(g.ellipsize(md.title, 17f, 700, iw * 0.6f), ix + 16f + 6f, top + 11f, 17f, 700, TX, spacing = -0.3f / 17f)
        g.text(g.ellipsize(md.sub, 11f, 400, iw * 0.62f), ix, top + 30f, 11f, 400, T2)
        md.chips.take(2).forEachIndexed { k, t ->
            val wg = if (k == 0) 700 else 600
            val cw = g.width(t, 10.5f, wg) + 14f
            val cy = top + 8f + k * 18.6f
            fill(g, ix + iw - cw, cy - 7.8f, cw, 15.6f, 8f, if (k == 0) bAlpha(0xFFBFE3FF.toInt(), 0.16f) else bAlpha(WHITE, 0.08f))
            g.text(t, ix + iw - cw + 7f, cy, 10.5f, wg, TX)
        }
        val py = top + 42f
        var px = ix
        var avail = iw
        md.gauge?.let { gg ->
            gauge(g, gg, ix, py)
            px += 80f
            avail -= 80f
        }
        val pw = (avail - 6f) / 2f
        panel(g, md.minutes, px, py, pw, 92f, 34f, 7f, 9.5f)
        panel(g, md.hours, px + pw + 6f, py, pw, 92f, 34f, 7f, 9.5f)
    }

    /** Jauge d'altitude : neige (blanc) au-dessus de la limite, pluie (bleu) en dessous ; pastille ambre = la station. Échelle 0 à 1 200 m. */
    private fun gauge(g: BGx, gg: AGauge, x: Float, y: Float) {
        val c = g.c
        val by = y + 2f
        val bh = 88f
        val gz = (1.0 - gg.limit / 1200.0).toFloat().coerceIn(0f, 1f)
        val gs = (1.0 - gg.station / 1200.0).toFloat().coerceIn(0f, 1f)
        val rect = RectF(x, by, x + 12f, by + bh)
        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, 6f, 6f, Path.Direction.CW) })
        c.drawRect(x, by, x + 12f, by + bh * gz, bFill(AP.IC))
        c.drawRect(x, by + bh * gz, x + 12f, by + bh, bFill(AP.RC))
        c.restore()
        c.drawRect(x - 4f, by + bh * gz - 1f, x + 16f, by + bh * gz + 1f, bFill(WHITE))
        c.drawCircle(x + 6f, by + bh * gs, 6f, bFill(DARK))
        c.drawCircle(x + 6f, by + bh * gs, 4f, bFill(0xFFFFB36B.toInt()))
        val lx = x + 20f
        val ys = by + bh * gs - 22f
        g.textBase("Station", lx, ys + 9f, 10f, 700, 0xFFFFB36B.toInt())
        g.textBase(Math.round(gg.station).toString() + " m", lx, ys + 20f, 10f, 700, 0xFFFFB36B.toInt())
        val yl = by + bh * gz + 3f
        g.textBase(Math.round(gg.limit / 50.0 * 50.0).toString() + " m", lx, yl + 10f, 11f, 700, TX)
        g.textBase("limite", lx, yl + 21f, 10f, 400, T2)
    }

    // ───────────────────────────── Heures et jours ─────────────────────────────

    private fun hours(g: BGx, m: AModel, vw: Float, y: Float) {
        val x = PX
        val w = vw - 2 * PX
        val dim = if (m.offline) 0.6f else 1f
        card(g, x, y, w, HOURS_H, 18f)
        val n = min(7, m.hours.size)
        if (n == 0) return
        val iw = (w - 6f - 2f * (n - 1)) / n
        m.hours.take(n).forEachIndexed { k, hp ->
            val cx0 = x + 3f + k * (iw + 2f)
            val cx = cx0 + iw / 2f
            if (k == 0) fill(g, cx0, y + 3f, iw, HOURS_H - 6f, 12f, bAlpha(WHITE, 0.11f))
            g.text(hp.label, cx, y + 14f, 10f, if (k == 0) 600 else 500, bAlpha(if (k == 0) TX else T2, dim), Paint.Align.CENTER)
            g.icon(hp.icon, cx, y + 32f, 16f, bAlpha(hp.iconColor, dim))
            g.text(hp.temp, cx, y + 50f, 13f, 700, bAlpha(TX, dim), Paint.Align.CENTER)
            if (hp.sub.isNotEmpty()) {
                val s = g.fitSize(hp.sub, 9.5f, 600, iw - 2f, 7f)
                g.text(hp.sub, cx, y + 65f, s, 600, bAlpha(hp.subColor, dim), Paint.Align.CENTER)
            }
        }
    }

    private fun days(g: BGx, m: AModel, vw: Float, y: Float) {
        val x = PX
        val w = vw - 2 * PX
        val dim = if (m.offline) 0.6f else 1f
        card(g, x, y, w, DAYS_H, 18f)
        val n = min(5, m.days.size)
        if (n == 0) return
        val iw = (w - 8f - 2f * (n - 1)) / n
        m.days.take(n).forEachIndexed { k, dd ->
            val cx = x + 4f + k * (iw + 2f) + iw / 2f
            g.text(dd.label, cx, y + 14f, 10.5f, 600, bAlpha(T2, dim), Paint.Align.CENTER)
            g.icon(dd.icon, cx, y + 35f, 18f, bAlpha(dd.iconColor, dim))
            val hw = g.width(dd.hi, 12.5f, 700)
            val lw = g.width(dd.lo, 12.5f, 400)
            val sx = cx - (hw + 4f + lw) / 2f
            g.text(dd.hi, sx, y + 55f, 12.5f, 700, bAlpha(TX, dim))
            g.text(dd.lo, sx + hw + 4f, y + 55f, 12.5f, 400, bAlpha(T2, dim))
            if (dd.sub.isNotEmpty()) {
                val s = g.fitSize(dd.sub, 9.5f, 700, iw - 10f, 7f)
                val pw = g.width(dd.sub, s, 700) + 12f
                fill(g, cx - pw / 2f, y + 66f, pw, 14f, 6f, bAlpha(WHITE, 0.07f * dim))
                g.text(dd.sub, cx, y + 73f, s, 700, bAlpha(dd.subColor, dim), Paint.Align.CENTER)
            }
        }
    }

    // ───────────────────────────── Air et pollens ─────────────────────────────

    private fun airCard(g: BGx, m: AModel, x: Float, y: Float) {
        val w = 150f
        card(g, x, y, w, BOTTOM_H)
        g.textBase("Qualité de l'air", x + 10f, bl(y + 15f, 10.5f), 10.5f, 500, T2)
        val a = m.air
        if (a == null) {
            g.text("Indisponible", x + w / 2f, y + 55f, 11f, 400, T2, Paint.Align.CENTER)
            return
        }
        g.textBase(a.label, x + w - 10f, bl(y + 15f, 10.5f), 10.5f, 700, a.color, Paint.Align.RIGHT)
        // Indice + échelle
        val nw = g.width(a.v.toString(), 26f, 700, -1f / 26f)
        val base = y + 49f
        g.textBase(a.v.toString(), x + 10f, base, 26f, 700, a.color, spacing = -1f / 26f)
        g.textBase("IQA", x + 10f + nw + 3f, base, 9.5f, 400, T2)
        val sx = x + 10f + nw + 3f + g.width("IQA", 9.5f, 400) + 7f
        val sw = x + w - 10f - sx
        val seg = (sw - 2f * 5) / 6f
        val sy = y + 40f
        for (k in 0 until 6) fill(g, sx + k * (seg + 2f), sy - 2.5f, seg, 5f, 3f, AP.EAQ[k])
        fill(g, sx + sw * a.pos - 1f, sy - 8.5f, 2f, 13f, 1f, WHITE)
        // Polluants
        val cw = (w - 20f - 6f * 3) / 4f
        a.pols.take(4).forEachIndexed { k, p ->
            val px = x + 10f + k * (cw + 6f)
            g.textBase(p.name, px, bl(y + 63f, 9f), 9f, 400, T2)
            g.textBase(p.value, px, bl(y + 77f, 12f), 12f, 700, TX)
            fill(g, px, y + 85f, cw, 3f, 1.5f, bAlpha(WHITE, 0.12f))
            fill(g, px, y + 85f, cw * p.frac, 3f, 1.5f, a.color)
        }
    }

    private fun pollenCard(g: BGx, m: AModel, x: Float, y: Float, w: Float) {
        card(g, x, y, w, BOTTOM_H)
        val p = m.pollen
        g.textBase("Pollens", x + 10f, bl(y + 15f, 10.5f), 10.5f, 500, T2)
        g.textBase(p.chip, x + w - 10f, bl(y + 15f, 10.5f), 10.5f, 700, p.chipColor, Paint.Align.RIGHT)
        if (p.off) {
            g.icon(R.drawable.ic_check, x + 10f + 6.5f, y + 38f, 13f, AP.GREEN)
            g.text("Aucun pollen", x + 10f + 13f + 6f, y + 38f, 12f, 600, TX)
            // Note sur trois lignes au plus.
            val maxW = w - 20f
            val lines = ArrayList<String>()
            var cur = ""
            p.note.split(" ").forEach { wd ->
                val t = if (cur.isEmpty()) wd else "$cur $wd"
                if (g.width(t, 10.5f, 400) <= maxW) {
                    cur = t
                } else {
                    lines.add(cur)
                    cur = wd
                }
            }
            if (cur.isNotEmpty()) lines.add(cur)
            lines.take(3).forEachIndexed { k, l -> g.text(l, x + 10f, y + 57f + k * 13.7f, 10.5f, 400, T2) }
            return
        }
        // Grille de 2 colonnes, 3 lignes de 19
        val colW = (w - 20f - 12f) / 2f
        val gy = y + 29f
        p.rows.take(6).forEachIndexed { k, (name, l) ->
            val cx = x + 10f + (k % 2) * (colW + 12f)
            val cy = gy + (k / 2) * 21f + 9.5f
            val wg = if (l > 0) 600 else 500
            g.text(g.ellipsize(name, 10.5f, wg, colW - 31f), cx, cy, 10.5f, wg, if (l > 0) TX else T2)
            for (d in 0 until 4) {
                g.c.drawCircle(cx + colW - 26f + d * 7f + 2.5f, cy, 2.5f, bFill(if (d < l) AP.LV[l] else AP.LV[0]))
            }
        }
    }
}

// ───────────────────────────── Vue Glance ─────────────────────────────

/** L'image dessinée en fond, les zones transparentes cliquables par-dessus (même principe que le Bento). */
@Composable
internal fun AtmoView(appWidgetId: Int, inlineMode: Boolean, plan: AtmoPlan, bitmap: Bitmap) {
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        plan.zones.chunked(8).forEach { chunk ->
            Box(modifier = GlanceModifier.fillMaxSize()) {
                chunk.forEach { z -> AtmoZoneLayer(appWidgetId, inlineMode, z) }
            }
        }
    }
}

@Composable
private fun AtmoZoneLayer(appWidgetId: Int, inlineMode: Boolean, z: AZone) {
    val action: Action = if (z.tile != null) tileAction(appWidgetId, inlineMode, z.tile, z.tab) else actionRunCallback<RefreshCallback>()
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (z.y > 0.5f) Spacer(modifier = GlanceModifier.height(z.y.dp))
        Row {
            if (z.x > 0.5f) Spacer(modifier = GlanceModifier.width(z.x.dp))
            Zone(z.w, z.h, z.desc, action)
        }
    }
}
