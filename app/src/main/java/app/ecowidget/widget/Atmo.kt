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
import app.ecowidget.data.Tile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Zone cliquable, en dp, dans le repère du widget entier. `tile` null : actualiser. */
internal class AZone(val x: Float, val y: Float, val w: Float, val h: Float, val desc: String, val tile: Tile?, val tab: Int)

internal class ALay(
    val yHero: Float,
    val yMod: Float,
    val modH: Float,
    val yHours: Float?,
    val yDays: Float?,
    val yBottom: Float,
)

internal class AtmoPlan(val w: Float, val h: Float, val model: AModel, val lay: ALay, val zones: List<AZone>)

/**
 * Widget « Atmosphère » : une seule image dessinée (comme le Bento) avec des zones cliquables par-dessus.
 * Colonne unique : en-tête, température, module de la situation, heures, jours, air et pollens.
 * Sous la hauteur nécessaire, on retire d'abord les jours puis les heures ; en dessous, le Bento reprend la main.
 */
internal object Atmo {
    const val PAD = 12f
    private const val HEADER_H = 30f
    private const val HERO_H = 100f
    private const val GAP = 8f
    private const val HOURS_H = 72f
    private const val DAYS_H = 72f
    private const val BOTTOM_H = 100f
    private const val MIN_W = 250f
    private const val MAX_PIXELS = 1_500_000f

    private val TX = 0xFFEEF3FA.toInt()
    private val MUT = 0xFFAAB5C5.toInt()
    private val SNOWW = 0xFFEAF6FF.toInt()
    private val LIMIT = 0xFFFFE08A.toInt()
    private val AMBER = 0xFFF5B942.toInt()
    private val EAQ = intArrayOf(0xFF50F0E6.toInt(), 0xFF50CCAA.toInt(), 0xFFF0E641.toInt(), 0xFFFF5050.toInt(), 0xFF960032.toInt(), 0xFF7D2181.toInt())
    private val POLLEN = intArrayOf(0x22FFFFFF, 0xFF7EE0A5.toInt(), 0xFFF2C14E.toInt(), 0xFFF2894E.toInt(), 0xFFF0566B.toInt())
    private val RAIN_COLORS = intArrayOf(0xFF8EC9FF.toInt(), 0xFF4F9BFF.toInt(), 0xFF8B6CF2.toInt(), 0xFFF06BB0.toInt())

    private class Th(val top: Int, val bot: Int, val glow: Int, val accent: Int)

    private fun theme(s: Scn): Th = when (s) {
        Scn.DRY -> Th(0xFF0F1B2A.toInt(), 0xFF0A111D.toInt(), 0xFF38BDB0.toInt(), 0xFF5EE0CF.toInt())
        Scn.ANNOUNCED -> Th(0xFF111B36.toInt(), 0xFF0A1022.toInt(), 0xFF5A7BE0.toInt(), 0xFF8DB2FF.toInt())
        Scn.RAIN -> Th(0xFF141538.toInt(), 0xFF0A0D24.toInt(), 0xFF5A6CF0.toInt(), 0xFF7DBBFF.toInt())
        Scn.WINTER -> Th(0xFF142439.toInt(), 0xFF0B1524.toInt(), 0xFF7FB8E8.toInt(), 0xFFBFE3FF.toInt())
        Scn.HEAT -> Th(0xFF2B1710.toInt(), 0xFF150C0A.toInt(), 0xFFF28C3C.toInt(), 0xFFFFB36B.toInt())
        Scn.OFFLINE -> Th(0xFF1B1E24.toInt(), 0xFF101216.toInt(), 0xFF8890A0.toInt(), 0xFFA9B3C2.toInt())
        Scn.STORM -> Th(0xFF1F1239.toInt(), 0xFF0D0919.toInt(), 0xFF8B5CF6.toInt(), 0xFFFFC857.toInt())
    }

    private fun moduleBase(m: AMod): Float = when (m) {
        is AMod.Rain -> 152f
        else -> 100f
    }

    // ───────────────────────────── Mise en page ─────────────────────────────

    /** `w` × `h` : zone intérieure (sans marge), en dp. Renvoie null si le widget est trop petit : le Bento prend alors le relais. */
    fun plan(ctx: Context, i: DetailInputs, status: Status, place: String?, w: Float, h: Float, ageMin: Int?): AtmoPlan? {
        if (w < MIN_W) return null
        val m = AtmoData.build(ctx, i, status, place, ageMin) ?: return null
        val base = moduleBase(m.mod)
        val fixed = HEADER_H + GAP + HERO_H + GAP + GAP + BOTTOM_H
        var withDays = m.days.size >= 5
        var withHours = m.hours.size >= 5
        fun need(): Float = fixed + base + (if (withHours) GAP + HOURS_H else 0f) + (if (withDays) GAP + DAYS_H else 0f)
        if (need() > h && withDays) withDays = false
        if (need() > h && withHours) withHours = false
        if (need() > h) return null
        val modH = base + min(40f, h - need()).coerceAtLeast(0f)

        val yHero = HEADER_H + GAP
        val yMod = yHero + HERO_H + GAP
        var y = yMod + modH + GAP
        val yHours = if (withHours) y.also { y += HOURS_H + GAP } else null
        val yDays = if (withDays) y.also { y += DAYS_H + GAP } else null
        val lay = ALay(yHero, yMod, modH, yHours, yDays, y)

        // Zones, dans le repère du widget (marge comprise).
        val z = ArrayList<AZone>()
        val chipW = if (w >= 300f) 122f else 106f
        z.add(AZone(PAD + w - 30f, PAD, 30f, HEADER_H, "Actualiser", null, 0))
        z.add(AZone(PAD, PAD + yHero, w - chipW - GAP, HERO_H, "Température : détail", Tile.TEMP, 0))
        val chipH = (HERO_H - GAP) / 2f
        m.chips.forEachIndexed { k, c ->
            z.add(AZone(PAD + w - chipW, PAD + yHero + k * (chipH + GAP), chipW, chipH, c.label + " : détail", c.tile, 0))
        }
        val modTile: Tile? = when {
            m.mod is AMod.Rain && m.scn == Scn.WINTER -> Tile.SNOW
            m.mod is AMod.Rain || m.mod is AMod.Storm -> Tile.RAIN
            m.mod is AMod.Curve && m.mod.retry -> null
            else -> Tile.FORECAST
        }
        z.add(AZone(PAD, PAD + yMod, w, modH, "Situation : détail", modTile, if (modTile == Tile.RAIN) 1 else 0))
        if (yHours != null) z.add(AZone(PAD, PAD + yHours, w, HOURS_H, "Prochaines heures", Tile.FORECAST, 0))
        if (yDays != null) z.add(AZone(PAD, PAD + yDays, w, DAYS_H, "Prochains jours", Tile.FORECAST, 1))
        val airW = ((w - GAP) * 0.55f)
        z.add(AZone(PAD, PAD + lay.yBottom, airW, BOTTOM_H, "Qualité de l'air : détail", Tile.AIR, 0))
        z.add(AZone(PAD + airW + GAP, PAD + lay.yBottom, w - airW - GAP, BOTTOM_H, "Pollens : détail", Tile.AIR, 0))
        return AtmoPlan(w, h, m, lay, z)
    }

    // ───────────────────────────── Dessin ─────────────────────────────

    fun render(ctx: Context, plan: AtmoPlan, density: Float): Bitmap {
        val fw = plan.w + 2f * PAD
        val fh = plan.h + 2f * PAD
        val k = min(density, kotlin.math.sqrt(MAX_PIXELS / (fw * fh)))
        val bmp = Bitmap.createBitmap(max(1, (fw * k).roundToInt()), max(1, (fh * k).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(k, k)
        val g = BGx(ctx, BFonts.get(ctx), c, k)
        val m = plan.model
        val th = theme(m.scn)
        background(c, fw, fh, th)
        c.translate(PAD, PAD)
        val w = plan.w
        guard { header(g, m, w) }
        guard { hero(g, m, w, plan.lay.yHero, th) }
        guard { module(g, m, w, plan.lay.yMod, plan.lay.modH, th) }
        plan.lay.yHours?.let { y -> guard { hours(g, m, w, y, th) } }
        plan.lay.yDays?.let { y -> guard { days(g, m, w, y) } }
        val airW = (w - GAP) * 0.55f
        guard { airCard(g, m, 0f, plan.lay.yBottom, airW, BOTTOM_H) }
        guard { pollenCard(g, m, airW + GAP, plan.lay.yBottom, w - airW - GAP, BOTTOM_H) }
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

    private fun background(c: Canvas, w: Float, h: Float, th: Th) {
        val path = Path().apply { addRoundRect(RectF(0f, 0f, w, h), 26f, 26f, Path.Direction.CW) }
        c.save()
        c.clipPath(path)
        c.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h, th.top, th.bot, Shader.TileMode.CLAMP) })
        c.drawRect(
            0f, 0f, w, h,
            Paint().apply { shader = RadialGradient(w * 0.15f, 0f, w * 0.95f, bAlpha(th.glow, 0.30f), bAlpha(th.glow, 0f), Shader.TileMode.CLAMP) },
        )
        c.restore()
    }

    private fun card(g: BGx, x: Float, y: Float, w: Float, h: Float, rad: Float = 16f) {
        g.c.drawRoundRect(RectF(x, y, x + w, y + h), rad, rad, bFill(bAlpha(TX, 0.06f)))
        g.c.drawRoundRect(RectF(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f), rad, rad, bStroke(bAlpha(TX, 0.08f), 1f))
    }

    // ── En-tête

    private fun header(g: BGx, m: AModel, w: Float) {
        val c = g.c
        val cy = HEADER_H / 2f
        val left = m.place + (if (m.alt.isNotEmpty()) (if (m.place.isNotEmpty()) " · " else "") + m.alt else "")
        val refreshW = 28f
        var pillW = 0f
        val p = m.pill
        val maxPill = w * 0.52f
        var pillText = ""
        if (p != null) {
            pillText = g.ellipsize(p.text, 11f, W_PILL, maxPill - 30f)
            pillW = 9f + 13f + 5f + g.width(pillText, 11f, W_PILL) + 10f
        }
        val leftMax = w - refreshW - 8f - (if (pillW > 0f) pillW + 8f else 0f)
        val lt = g.ellipsize(left, 14.5f, W_PLACE, leftMax)
        val lw = g.text(lt, 2f, cy, 14.5f, W_PLACE, TX)
        if (m.warn && m.tail.isNotEmpty() && 2f + lw + 8f + g.width(m.tail, 11f, W_STATUS) < leftMax) {
            g.text(m.tail, 2f + lw + 8f, cy, 11f, W_STATUS, BPal.WARN)
        }
        if (p != null) {
            val r = RectF(w - refreshW - 8f - pillW, cy - 12f, w - refreshW - 8f, cy + 12f)
            val ink = bMix(p.tint, TX, 0.55f)
            c.drawRoundRect(r, 12f, 12f, bFill(bAlpha(p.tint, 0.22f)))
            c.drawRoundRect(RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f), 12f, 12f, bStroke(bAlpha(p.tint, 0.5f), 1f))
            g.icon(p.icon, r.left + 9f + 6.5f, cy, 13f, ink)
            g.text(pillText, r.left + 9f + 13f + 5f, cy, 11f, W_PILL, ink)
        }
        c.drawCircle(w - refreshW / 2f, cy, refreshW / 2f, bFill(bAlpha(TX, 0.09f)))
        g.icon(R.drawable.ic_refresh, w - refreshW / 2f, cy, 15f, TX)
    }

    // ── Température

    private fun hero(g: BGx, m: AModel, w: Float, y: Float, th: Th) {
        val c = g.c
        val dim = if (m.offline) 0.55f else 1f
        if (m.offline) g.text("DERNIÈRE VALEUR", 2f, y + 7f, 9f, W_LABEL, bAlpha(MUT, 0.9f), spacing = 0.06f)
        val base = y + 58f
        val size = g.fitSize(m.heroValue, 60f, W_HERO, 150f, 40f, -0.03f)
        val vw = g.textBase(m.heroValue, 0f, base, size, W_HERO, bAlpha(TX, dim), spacing = -0.03f)
        g.textBase("°", vw + 1f, base - size * 0.30f, size * 0.55f, W_HERO, bAlpha(TX, 0.75f * dim))
        val fy = y + 74f
        if (m.feel != null) {
            val fw = g.text("Ressenti ", 2f, fy, 11.5f, W_LABEL, MUT)
            g.text(m.feel, 2f + fw, fy, 11.5f, W_VAL, bAlpha(TX, dim))
        }
        // Barre min / max du jour.
        val ry = y + 92f
        val lo = m.lo
        val hi = m.hi
        if (lo != null && hi != null) {
            val chipW = if (w >= 300f) 122f else 106f
            val avail = w - chipW - 16f
            val ls = ng(lo)
            val hs = ng(hi)
            val lw = g.width(ls, 11f, W_VAL)
            val hw = g.width(hs, 11f, W_VAL)
            g.text(ls, 2f, ry, 11f, W_VAL, bAlpha(MUT, 1f))
            g.text(hs, avail, ry, 11f, W_VAL, bAlpha(TX, dim), android.graphics.Paint.Align.RIGHT)
            val x0 = 2f + lw + 7f
            val x1 = avail - hw - 7f
            if (x1 - x0 > 20f) {
                c.drawRoundRect(RectF(x0, ry - 2f, x1, ry + 2f), 2f, 2f, bFill(bAlpha(TX, 0.16f)))
                val cur = m.cur
                if (cur != null && hi > lo) {
                    val fr = ((cur - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                    val px = x0 + (x1 - x0) * fr
                    c.drawRoundRect(RectF(x0, ry - 2f, px, ry + 2f), 2f, 2f, bFill(bAlpha(th.accent, 0.85f * dim)))
                    c.drawCircle(px, ry, 4.5f, bFill(bAlpha(TX, dim)))
                    c.drawCircle(px, ry, 4.5f, bStroke(bAlpha(0xFF0A111D.toInt(), 0.9f), 1.2f))
                }
            }
        }
        // Pastilles : vent et information propre à la situation.
        val chipW = if (w >= 300f) 122f else 106f
        val chipH = (HERO_H - GAP) / 2f
        m.chips.forEachIndexed { k, ch ->
            val cx = w - chipW
            val cyy = y + k * (chipH + GAP)
            card(g, cx, cyy, chipW, chipH, 14f)
            g.text(ch.label.uppercase(), cx + 10f, cyy + 13f, 8.5f, W_LABEL, MUT, spacing = 0.06f)
            val vs = g.fitSize(ch.value, 15f, W_VAL, chipW - 20f, 10f)
            g.text(ch.value, cx + 10f, cyy + chipH - 15f, vs, W_VAL, TX)
        }
    }

    private fun ng(v: Double): String = Math.round(v).toString().replace('-', '−') + "°"

    // ── Module central

    private fun module(g: BGx, m: AModel, w: Float, y: Float, h: Float, th: Th) {
        card(g, 0f, y, w, h, 18f)
        when (val md = m.mod) {
            is AMod.Curve -> curve(g, md, 0f, y, w, h, th)
            is AMod.Rain -> rain(g, md, m, 0f, y, w, h, th)
            is AMod.Storm -> storm(g, md, 0f, y, w, h, th)
        }
    }

    private fun curve(g: BGx, md: AMod.Curve, x: Float, y: Float, w: Float, h: Float, th: Th) {
        val c = g.c
        g.text(g.ellipsize(md.title, 14f, W_VAL, w * 0.62f), x + 12f, y + 16f, 14f, W_VAL, TX)
        if (md.right.isNotEmpty()) g.text(g.ellipsize(md.right, 10.5f, W_LABEL, w * 0.34f), x + w - 12f, y + 16f, 10.5f, W_LABEL, MUT, Paint.Align.RIGHT)
        if (md.retry) {
            val bw = 62f
            val r = RectF(x + w - 12f - bw, y + 6f, x + w - 12f, y + 26f)
            c.drawRoundRect(r, 10f, 10f, bFill(bAlpha(th.accent, 0.22f)))
            c.drawRoundRect(RectF(r.left + .5f, r.top + .5f, r.right - .5f, r.bottom - .5f), 10f, 10f, bStroke(bAlpha(th.accent, 0.5f), 1f))
            g.text("Réessayer", r.centerX(), r.centerY(), 10.5f, W_PILL, TX, Paint.Align.CENTER)
        }
        var cx0 = x + 8f
        var cw = w - 16f
        val top = y + 34f
        val ch = h - 34f - 18f
        if (md.uv != null) {
            val ring = 54f
            val col = BPal.UV[uvBand(md.uv)]
            g.ring(x + 12f + ring / 2f, top + ch / 2f, ring, (md.uv / 11.0).toFloat(), col, 5f, center = Math.round(md.uv).toString(), sub = "UV", tsize = 16f)
            cx0 = x + 12f + ring + 6f
            cw = x + w - 8f - cx0
        }
        val all = md.obs + md.fc
        if (all.size < 2) return
        var lo = all.min() - 1.0
        var hi = all.max() + 1.0
        if (md.thr != null) {
            lo = min(lo, md.thr - 1.0)
            hi = max(hi, md.thr + 1.0)
        }
        val pad = 4f
        fun yOf(v: Double): Float = top + ch - pad - ((v - lo) / (hi - lo)).toFloat() * (ch - 2f * pad)
        fun xOf(idx: Int): Float = cx0 + pad + idx * (cw - 2f * pad) / (all.size - 1)
        g.spark(
            cx0, top, cw, ch, all, th.accent, nowIdx = if (md.obs.isEmpty()) null else md.obs.size - 1, lo = lo, hi = hi, pad = pad,
            zero = md.zero, below = if (md.zero) bAlpha(BPal.ICE_BLUE, 0.2f) else null,
        )
        if (md.thr != null) {
            val ty = yOf(md.thr)
            c.drawLine(cx0 + pad, ty, cx0 + cw - pad, ty, bStroke(bAlpha(BPal.HEAT, 0.7f), 1f).apply { pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f) })
            g.text(Math.round(md.thr).toString() + "°", cx0 + cw - pad, ty - 6f, 8.5f, W_LABEL, bAlpha(BPal.HEAT, 0.95f), Paint.Align.RIGHT)
            val pk = all.indices.maxByOrNull { all[it] } ?: 0
            if (all[pk] > md.thr - 3.0) {
                val px = xOf(pk).coerceIn(cx0 + 14f, cx0 + cw - 14f)
                g.text(Math.round(all[pk]).toString() + "°", px, yOf(all[pk]) - 8f, 9.5f, W_VAL, TX, Paint.Align.CENTER)
            }
        }
        md.ticks.forEach { (idx, label) ->
            val tx = xOf(idx)
            g.text(label, tx.coerceIn(cx0 + 10f, cx0 + cw - 10f), y + h - 9f, 8f, W_SUB, MUT, Paint.Align.CENTER)
        }
    }

    private fun uvBand(uv: Double): Int = when {
        uv < 3 -> 0
        uv < 6 -> 1
        uv < 8 -> 2
        uv < 11 -> 3
        else -> 4
    }

    private fun storm(g: BGx, md: AMod.Storm, x: Float, y: Float, w: Float, h: Float, th: Th) {
        val colW = (w - 24f) / md.stats.size
        md.stats.forEachIndexed { k, s ->
            val cx = x + 12f + k * colW
            g.text(s.first.uppercase(), cx, y + 15f, 8.5f, W_LABEL, MUT, spacing = 0.06f)
            val vs = g.fitSize(s.second, 18f, W_VAL, colW - 6f, 11f)
            g.text(s.second, cx, y + 35f, vs, W_VAL, if (k == 0) th.accent else TX)
            if (s.third.isNotEmpty()) g.text(g.ellipsize(s.third, 9f, W_SUB, colW - 6f), cx, y + 53f, 9f, W_SUB, MUT)
        }
        val by = y + 62f
        val bh = h - 62f - 10f
        val cols = md.bins.map { rainColor(it) }
        g.bars(x + 12f, by, w - 24f, bh, md.bins, cols, 3f, 3f, max(3.0, md.bins.maxOrNull() ?: 0.0))
    }

    private fun rainColor(v: Double): Int = when {
        v < 1.0 -> RAIN_COLORS[0]
        v < 2.5 -> RAIN_COLORS[1]
        v < 5.0 -> RAIN_COLORS[2]
        else -> RAIN_COLORS[3]
    }

    // ── Pluie et neige

    private fun rain(g: BGx, md: AMod.Rain, m: AModel, x: Float, y: Float, w: Float, h: Float, th: Th) {
        g.text(g.ellipsize(md.title, 14.5f, W_VAL, w * 0.5f), x + 12f, y + 16f, 14.5f, W_VAL, TX)
        val winter = m.scn == Scn.WINTER
        if (!winter && md.right.isNotEmpty()) {
            g.text(g.ellipsize(md.right, 10.5f, W_LABEL, w * 0.38f), x + w - 12f, y + 16f, 10.5f, W_LABEL, MUT, Paint.Align.RIGHT)
        }
        if (winter) {
            var rx = x + w - 10f
            md.chips.reversed().forEach { t ->
                val tw = g.width(t, 9.5f, W_PILL) + 14f
                val r = RectF(rx - tw, y + 6f, rx, y + 26f)
                g.c.drawRoundRect(r, 10f, 10f, bFill(bAlpha(BPal.SNOW_ICE, 0.18f)))
                g.text(t, r.centerX(), r.centerY(), 9.5f, W_PILL, bMix(BPal.SNOW_ICE, TX, 0.5f), Paint.Align.CENTER)
                rx -= tw + 5f
            }
        }
        val py = y + 32f
        val ph = h - 32f - 8f
        var px = x + 8f
        var avail = w - 16f
        val gg = md.gauge
        if (gg != null) {
            val gw = 70f
            gauge(g, gg, px, py, gw, ph)
            px += gw + 4f
            avail -= gw + 4f
        }
        val pw = (avail - 6f) / 2f
        panel(g, md.minutes, px, py, pw, ph, th)
        panel(g, md.hours, px + pw + 6f, py, pw, ph, th)
    }

    private fun gauge(g: BGx, gg: AGauge, x: Float, y: Float, w: Float, h: Float) {
        val c = g.c
        val top = y + 6f
        val bot = y + h - 6f
        fun yOf(mv: Double): Float = bot - (bot - top) * (mv / gg.top).toFloat().coerceIn(0f, 1f)
        val bx = x + 6f
        val bw = 9f
        val yl = yOf(gg.limit)
        c.drawRoundRect(RectF(bx, top, bx + bw, bot), 4f, 4f, bFill(bAlpha(TX, 0.08f)))
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(bx, top, bx + bw, bot), 4f, 4f, Path.Direction.CW) })
        c.drawRect(bx, top, bx + bw, yl, bFill(bAlpha(SNOWW, 0.9f)))
        c.drawRect(bx, yl, bx + bw, bot, bFill(bAlpha(RAIN_COLORS[1], 0.65f)))
        c.restore()
        val ys = yOf(gg.station)
        c.drawLine(bx - 3f, ys, bx + bw + 3f, ys, bStroke(AMBER, 2f))
        c.drawCircle(bx + bw / 2f, ys, 3.2f, bFill(AMBER))
        // Étiquettes à droite, décalées si elles se touchent.
        var yLab = yl
        var ySt = ys
        if (kotlin.math.abs(ySt - yLab) < 22f) {
            if (ySt >= yLab) ySt = yLab + 22f else ySt = yLab - 22f
        }
        yLab = yLab.coerceIn(top + 8f, bot - 8f)
        ySt = ySt.coerceIn(top + 8f, bot - 8f)
        val lx = bx + bw + 7f
        g.text(Math.round(gg.limit).toString() + " m", lx, yLab - 4.5f, 9f, W_VAL, TX)
        g.text("limite", lx, yLab + 4.5f, 7.5f, W_SUB, MUT)
        g.text(Math.round(gg.station).toString() + " m", lx, ySt - 4.5f, 9f, W_VAL, AMBER)
        g.text("station", lx, ySt + 4.5f, 7.5f, W_SUB, MUT)
    }

    /** Un graphique de pluie : intensité en hauteur et en couleur ; phase en blanc ; limite pluie / neige en pointillés. */
    private fun panel(g: BGx, p: APanel, x: Float, y: Float, w: Float, h: Float, th: Th) {
        val c = g.c
        c.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, bFill(bAlpha(TX, 0.05f)))
        g.text(p.title, x + 8f, y + 11f, 9.5f, W_LABEL, MUT)
        g.text(p.cumul, x + w - 8f, y + 11f, 9.5f, W_VAL, TX, Paint.Align.RIGHT)
        // Même marge haute pour les deux graphiques (11 dp) : les axes restent alignés.
        val cx = x + 8f
        val cw = w - 16f
        val top = y + 22f + 11f
        val chartH = h - (22f + 11f) - 16f
        val base = top + chartH
        val n = p.vals.size
        if (n == 0) return
        val gap = if (n >= 12 && cw < 130f) 1.5f else 2f
        val bw = (cw - gap * (n - 1)) / n
        p.vals.forEachIndexed { k, v ->
            val bx = cx + k * (bw + gap)
            if (v <= 0.0) {
                c.drawRoundRect(RectF(bx, base - 2f, bx + bw, base), 1f, 1f, bFill(bAlpha(TX, 0.14f)))
            } else {
                val bh = max(3f, chartH * (v / p.top).toFloat()).coerceAtMost(chartH)
                bar(g, bx, bw, base, bh, rainColor(v), p.phases.getOrElse(k) { 0 })
            }
        }
        // Bascule possible en mêlée / neige : ligne verticale pointillée + flocon.
        p.flake?.let { k ->
            val fx = cx + k * (bw + gap) - gap / 2f
            c.drawLine(fx, top - 2f, fx, base, bStroke(bAlpha(SNOWW, 0.7f), 1f).apply { pathEffect = DashPathEffect(floatArrayOf(2f, 2.5f), 0f) })
            g.icon(R.drawable.ic_flake, fx, top - 7f, 9f, SNOWW)
        }
        // Limite pluie / neige : sa propre échelle, seules les valeurs min et max sont écrites.
        p.limit?.let { lim ->
            if (lim.size == n) {
                val lo = lim.min()
                val hi = lim.max()
                val span = max(hi - lo, 100.0)
                val zone = chartH * 0.5f
                fun ly(v: Double): Float = top + 3f + zone * (1f - ((v - lo) / span).toFloat())
                val path = Path()
                lim.forEachIndexed { k, v ->
                    val lx = cx + k * (bw + gap) + bw / 2f
                    if (k == 0) path.moveTo(lx, ly(v)) else path.lineTo(lx, ly(v))
                }
                c.drawPath(path, bStroke(LIMIT, 1.4f).apply { pathEffect = DashPathEffect(floatArrayOf(1.5f, 3f), 0f) })
                val iMin = lim.indexOf(lo)
                val iMax = lim.indexOf(hi)
                fun lab(idx: Int, v: Double, above: Boolean) {
                    val lx = (cx + idx * (bw + gap) + bw / 2f).coerceIn(cx + 14f, cx + cw - 14f)
                    val s = Math.round(v / 50.0).times(50).toString()
                    g.text(s, lx, ly(v) + (if (above) -6f else 7f), 7.5f, W_VAL, LIMIT, Paint.Align.CENTER)
                }
                if (hi - lo >= 50.0 && iMin != iMax) {
                    lab(iMax, hi, true)
                    lab(iMin, lo, false)
                } else {
                    lab(n / 2, lo, true)
                }
            }
        }
        p.ticks.forEach { (fr, t) ->
            val tx = cx + cw * fr
            val align = if (fr > 0.97f) Paint.Align.RIGHT else Paint.Align.CENTER
            g.text(t, tx, base + 10f, 8f, W_SUB, MUT, align)
        }
    }

    private fun bar(g: BGx, bx: Float, bw: Float, base: Float, bh: Float, col: Int, phase: Int) {
        val c = g.c
        val r = RectF(bx, base - bh, bx + bw, base)
        val rad = min(3f, bw / 2f)
        when (phase) {
            2 -> c.drawRoundRect(r, rad, rad, bFill(SNOWW))
            1 -> {
                c.save()
                c.clipPath(Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) })
                c.drawRect(r, bFill(col))
                c.drawRect(RectF(r.left, r.top, r.right, r.top + bh * 0.55f), bFill(SNOWW))
                c.restore()
            }
            else -> c.drawRoundRect(r, rad, rad, bFill(col))
        }
    }

    // ── Prochaines heures et prochains jours

    private fun hours(g: BGx, m: AModel, w: Float, y: Float, th: Th) {
        card(g, 0f, y, w, HOURS_H, 18f)
        val n = min(7, m.hours.size)
        val cw = (w - 12f) / n
        m.hours.take(n).forEachIndexed { k, hp ->
            val cx = 6f + cw * (k + 0.5f)
            g.text(hp.label, cx, y + 12f, 9.5f, W_LABEL, MUT, Paint.Align.CENTER)
            g.icon(hp.icon, cx, y + 29f, 17f, hp.iconColor)
            g.text(hp.temp, cx, y + 47f, 12.5f, W_VAL, TX, Paint.Align.CENTER)
            if (hp.sub.isNotEmpty()) {
                val s = g.fitSize(hp.sub, 8.5f, W_LABEL, cw - 2f, 6.5f)
                g.text(hp.sub, cx, y + 61f, s, W_LABEL, bMix(th.accent, TX, 0.25f), Paint.Align.CENTER)
            }
        }
    }

    private fun days(g: BGx, m: AModel, w: Float, y: Float) {
        card(g, 0f, y, w, DAYS_H, 18f)
        val n = min(5, m.days.size)
        val cw = (w - 12f) / n
        m.days.take(n).forEachIndexed { k, dd ->
            val cx = 6f + cw * (k + 0.5f)
            g.text(dd.label, cx, y + 12f, 10f, if (dd.today) W_PILL else W_LABEL, if (dd.today) TX else MUT, Paint.Align.CENTER)
            g.icon(dd.icon, cx, y + 29f, 17f, dd.iconColor)
            val hw = g.width(dd.hi, 12f, W_VAL)
            val lw = g.width(dd.lo, 10.5f, W_LABEL)
            val sx = cx - (hw + 5f + lw) / 2f
            g.text(dd.hi, sx, y + 47f, 12f, W_VAL, TX)
            g.text(dd.lo, sx + hw + 5f, y + 47f, 10.5f, W_LABEL, MUT)
            if (dd.sub.isNotEmpty()) g.text(dd.sub, cx, y + 61f, 8.5f, W_LABEL, bMix(0xFF8DB2FF.toInt(), TX, 0.3f), Paint.Align.CENTER)
        }
    }

    // ── Air et pollens

    private fun airCard(g: BGx, m: AModel, x: Float, y: Float, w: Float, h: Float) {
        card(g, x, y, w, h, 18f)
        g.icon(R.drawable.ic_leaf, x + 15f, y + 14f, 12f, MUT)
        g.text("Qualité de l'air", x + 24f, y + 14f, 10f, W_LABEL, MUT)
        val a = m.air
        if (a == null) {
            g.text("Indisponible", x + w / 2f, y + h / 2f + 6f, 11f, W_LABEL, MUT, Paint.Align.CENTER)
            return
        }
        val nw = g.text(a.aqi.toString(), x + 12f, y + 36f, 25f, W_STRONG, TX, spacing = -0.02f)
        g.text(a.word, x + 12f + nw + 7f, y + 38f, 11.5f, W_PILL, EAQ[a.band.coerceIn(0, 5)])
        // Échelle européenne en 6 segments + repère.
        val sx = x + 12f
        val sw = w - 24f
        val seg = (sw - 2f * 5) / 6f
        for (k in 0 until 6) {
            val bx = sx + k * (seg + 2f)
            g.c.drawRoundRect(RectF(bx, y + 51f, bx + seg, y + 55f), 2f, 2f, bFill(bAlpha(EAQ[k], if (k == a.band) 1f else 0.55f)))
        }
        val mk = sx + (a.band + 0.5f) * (seg + 2f) - 1f
        g.c.drawCircle(mk, y + 53f, 3.2f, bFill(TX))
        // Polluants : 2 colonnes × 2 lignes, avec une petite jauge.
        val cw = sw / 2f
        a.pols.take(4).forEachIndexed { k, p ->
            val px = sx + (k % 2) * cw
            val py = y + 69f + (k / 2) * 17f
            g.text(p.name, px, py, 9f, W_LABEL, MUT)
            g.text(p.value, px + 33f, py, 9.5f, W_VAL, TX)
            val bx = px + 33f + g.width(p.value, 9.5f, W_VAL) + 4f
            val bwMax = px + cw - 6f - bx
            if (bwMax > 6f) {
                g.c.drawRoundRect(RectF(bx, py - 1.5f, bx + bwMax, py + 1.5f), 1.5f, 1.5f, bFill(bAlpha(TX, 0.12f)))
                g.c.drawRoundRect(RectF(bx, py - 1.5f, bx + bwMax * p.frac, py + 1.5f), 1.5f, 1.5f, bFill(EAQ[p.band.coerceIn(0, 5)]))
            }
        }
    }

    private fun pollenCard(g: BGx, m: AModel, x: Float, y: Float, w: Float, h: Float) {
        card(g, x, y, w, h, 18f)
        val p = m.pollen
        g.icon(R.drawable.ic_leaf, x + 15f, y + 14f, 12f, MUT)
        g.text("Pollens", x + 24f, y + 14f, 10f, W_LABEL, MUT)
        if (p.summary.isNotEmpty()) {
            val col = if (p.storm) POLLEN[3] else POLLEN[p.summaryLevel.coerceIn(1, 4)]
            val s = g.ellipsize(p.summary, 9.5f, W_PILL, w * 0.5f)
            g.text(s, x + w - 10f, y + 14f, 9.5f, W_PILL, col, Paint.Align.RIGHT)
        }
        if (p.message != null) {
            g.text(p.message, x + w / 2f, y + h / 2f + 8f, 12f, W_VAL, MUT, Paint.Align.CENTER)
            return
        }
        val rows = p.rows.take(6)
        val rh = (h - 28f - 6f) / max(1, rows.size).coerceAtLeast(4)
        rows.forEachIndexed { k, r ->
            val cy = y + 28f + rh * (k + 0.5f)
            g.text(g.ellipsize(r.first, 9f, W_LABEL, w - 70f), x + 12f, cy, 9f, W_LABEL, TX)
            for (d in 0 until 4) {
                val dx = x + w - 12f - 4f - (3 - d) * 9f
                val on = d < r.second
                g.c.drawCircle(dx, cy, 3f, bFill(if (on) POLLEN[min(4, r.second)] else POLLEN[0]))
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
