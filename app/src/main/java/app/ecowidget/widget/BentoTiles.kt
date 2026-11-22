package app.ecowidget.widget

import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.ecowidget.R
import app.ecowidget.data.Metric
import app.ecowidget.data.RainColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Dessin de chaque tuile du widget « Bento sombre » (reprise fidèle des maquettes). */
internal object BentoTiles {

    private fun fr(v: Double, d: Int = 1): String = Metric.fmt(v, d).replace('-', '−')

    private fun rateCol(v: Double): Int = if (v > 0.0) RainColors.hue(RainColors.RATE, v).c else bAlpha(BPal.TXT, 0.16f)

    private fun frame(g: BGx, c: BCell, t: BTint, rad: Float = 22f) {
        g.round(c.x, c.y, c.w, c.h, min(rad, c.h * 0.34f), t.bg)
    }

    fun draw(g: BGx, c: BCell) {
        when (c.kind) {
            BKind.HERO -> (c.d as? TdHero)?.let { if (c.spanR >= 2 || c.h >= 100f) hero(g, c, it) else heroLite(g, c, it) }
            BKind.WIND -> (c.d as? TdWind)?.let { wind(g, c, it) }
            BKind.HUM -> (c.d as? TdHum)?.let { hum(g, c, it) }
            BKind.PRESS -> (c.d as? TdPress)?.let { press(g, c, it) }
            BKind.UV -> (c.d as? TdUv)?.let { if (c.spanC >= 2) uvWide(g, c, it) else uvSmall(g, c, it) }
            BKind.AIR -> (c.d as? TdAir)?.let { air(g, c, it) }
            BKind.POLLEN -> (c.d as? TdPollen)?.let { pollen(g, c, it) }
            BKind.HOME -> (c.d as? TdHome)?.let { home(g, c, it) }
            BKind.TOMORROW -> (c.d as? TdTom)?.let { tomorrow(g, c, it) }
            BKind.WEEK -> (c.d as? TdWeek)?.let { week(g, c, it) }
            BKind.RAIN60 -> (c.d as? TdRain60)?.let { rain60(g, c, it) }
            BKind.RAIN12 -> (c.d as? TdRain12)?.let { if (c.h >= 100f) rain12(g, c, it) else rain12Strip(g, c, it) }
            BKind.RATE -> (c.d as? TdRate)?.let { rate(g, c, it) }
            BKind.SNOW24 -> (c.d as? TdSnow24)?.let { snow24(g, c, it) }
            BKind.SNOW_ALT -> (c.d as? TdSnowAlt)?.let { snowAlt(g, c, it) }
            BKind.SNOW_LIM -> (c.d as? TdSnowAlt)?.let { snowLim(g, c, it) }
            BKind.FROST -> (c.d as? TdFrost)?.let { frost(g, c, it) }
            BKind.OFFLINE -> (c.d as? TdOffline)?.let { offline(g, c, it) }
            BKind.STORM -> (c.d as? TdStorm)?.let { storm(g, c, it) }
            BKind.GUSTS -> (c.d as? TdGusts)?.let { gusts(g, c, it) }
            BKind.HUM_MINI -> (c.d as? TdMiniHum)?.let { humMini(g, c, it) }
            BKind.PRESS_MINI -> (c.d as? TdMiniPress)?.let { pressMini(g, c, it) }
            BKind.SHORT -> {}
        }
    }

    // ───────────────────────────── Héros ─────────────────────────────

    private fun tempColor(d: TdHero, ink: Int): Int =
        if (d.colorTemp == null) 0xFFE8EEF5.toInt() else TempColors.forTemp(d.colorTemp, false, Color(ink)).toArgb()

    private fun hero(g: BGx, c: BCell, d: TdHero) {
        val ink = d.tint.ink
        val x = c.x
        val y = c.y
        val rad = min(28f, c.h * 0.26f)
        g.round(x, y, c.w, c.h, rad, d.tint.bg)

        // Libellé et ressenti.
        g.icon(if (d.forecastOnly) R.drawable.ic_wx_partly else R.drawable.ic_thermometer, x + 12f + 7f, y + 10f + 7f, 14f, ink)
        g.text(d.label, x + 12f + 14f + 6f, y + 17f, 12f, W_LABEL, ink)
        if (d.feel != null) {
            val right = x + c.w - 12f
            val vw = g.text(d.feel, right, y + 17f, 11.5f, W_STRONG, ink, Paint.Align.RIGHT)
            g.text("Ressenti", right - vw - 4f, y + 17f, 11.5f, W_SUB, bAlpha(ink, 0.78f), Paint.Align.RIGHT)
        }

        // Grande température.
        val big = if (c.h >= 135f) 48f else if (c.h >= 120f) 42f else 38f
        val ttop = 22f
        val tcol = tempColor(d, ink)
        val vw = g.textBase(d.value, x + 11f, y + ttop + 0.84f * big, big, W_HERO, tcol, spacing = -0.045f)
        val us = big * 0.5f
        val uw = g.textBase(d.unit, x + 12f + vw, y + ttop + 0.57f * big, us, W_HERO, tcol)
        var endX = x + 12f + vw + uw
        if (d.trendUp != null && d.trendText != null) {
            val col = if (d.trendUp) BPal.WARM_TREND else BPal.COOL_TREND
            val cy = y + ttop + 0.13f * big + 7f
            g.trend(endX + 7f + 6f, cy, 12f, d.trendUp, col, 2.3f)
            g.text(d.trendText, endX + 7f + 12f + 2f, cy, 11f, W_STRONG, col)
            endX += 7f + 12f + 2f
        }

        // Min / Max à droite.
        val sideTop = y + ttop + (if (d.feel != null) 9f else 3f)
        d.stats.forEachIndexed { n, (label, value) ->
            val cy = sideTop + 7.5f + n * 16f
            val vwid = g.text(value, x + c.w - 12f, cy, 11.5f, W_STRONG, ink, Paint.Align.RIGHT)
            g.text(label, x + c.w - 12f - vwid - 4f, cy, 11.5f, W_SUB, bAlpha(ink, 0.78f), Paint.Align.RIGHT)
        }

        // Courbe 24 h : mesures (trait plein), prévision (pointillés).
        val ch = (c.h - 20f - ttop - big - 8f).coerceIn(24f, 60f)
        val all = d.obs + d.fc
        if (all.size >= 2) {
            g.spark(
                x + 12f, y + c.h - 20f - ch, c.w - 24f, ch, all, d.curve,
                nowIdx = if (d.fc.isNotEmpty()) max(0, d.obs.size - 1) else null,
                zero = d.zero, below = d.below,
            )
            val ly = y + c.h - 7f - 5f
            g.text("−12 h", x + 12f, ly, 9f, W_SUB, bAlpha(ink, 0.62f))
            g.text("maintenant", x + c.w / 2f, ly, 9f, W_SUB, bAlpha(ink, 0.62f), Paint.Align.CENTER)
            g.text("+12 h", x + c.w - 12f, ly, 9f, W_SUB, bAlpha(ink, 0.62f), Paint.Align.RIGHT)
        }
    }

    private fun heroLite(g: BGx, c: BCell, d: TdHero) {
        val ink = d.tint.ink
        val x = c.x
        val y = c.y
        g.round(x, y, c.w, c.h, 22f.coerceAtMost(c.h * 0.34f), d.tint.bg)
        g.text(d.label, x + 12f, y + 14f, 11f, W_LABEL, bAlpha(ink, 0.9f))
        val tcol = tempColor(d, ink)
        val vw = g.textBase(d.value, x + 11f, y + 29f + 0.84f * 38f, 38f, W_HERO, tcol, spacing = -0.045f)
        val uw = g.textBase(d.unit, x + 12f + vw, y + 29f + 21.5f, 19f, W_HERO, tcol)
        if (d.trendUp != null && d.trendText != null) {
            val col = if (d.trendUp) BPal.WARM_TREND else BPal.COOL_TREND
            val tx = x + 12f + vw + uw + 5f
            val cy = y + 29f + 6f + 5f
            g.trend(tx + 5f, cy, 10f, d.trendUp, col, 2.4f)
            g.text(d.trendText.removeSuffix("/h"), tx + 11f, cy, 10f, W_STRONG, col)
        }
        // Min / Max.
        val edge = min(x + 220f, x + c.w - 12f - 60f)
        d.stats.forEachIndexed { n, (label, value) ->
            val cy = y + 12f + 6.5f + n * 13f
            val vwid = g.text(value, edge, cy, 10.5f, W_STRONG, ink, Paint.Align.RIGHT)
            g.text(label, edge - vwid - 3f, cy, 10.5f, W_SUB, bAlpha(ink, 0.75f), Paint.Align.RIGHT)
        }
        val cw = c.w - 234f
        val all = d.obs + d.fc
        if (cw >= 50f && all.size >= 2) {
            g.spark(
                x + c.w - 12f - cw, y + 12f, cw, 44f, all, d.curve,
                nowIdx = if (d.fc.isNotEmpty()) max(0, d.obs.size - 1) else null,
                sw = 1.7f, zero = d.zero, below = d.below,
            )
        }
    }

    // ───────────────────────────── Petites tuiles ─────────────────────────────

    /** Tuile 1×1 : libellé, valeur, sous-ligne ; `visualW` réserve la place d'un visuel à droite. */
    private fun kpi(
        g: BGx, c: BCell, t: BTint, icon: Int, label: String, value: String, unit: String, sub: String,
        visualW: Float = 0f, vcolor: Int = t.ink,
    ) {
        frame(g, c, t)
        g.label(icon, label, t.ink, c.x, c.y)
        val inner = c.w - 20f - (if (visualW > 0f) visualW + 4f else 0f)
        val unitW = if (unit.isEmpty()) 0f else g.width(unit, 11.5f, W_UNIT) + 3f
        val vs = g.fitSize(value, if (c.h >= 64f) 19f else 17.5f, W_STRONG, inner - unitW, 14f, -0.02f)
        val gap = ((c.h - 16f - 13f - (vs + 2f) - 12f) / 2f).coerceAtLeast(1.5f)
        g.value(value, unit, c.x + 10f, c.y + 8f + 13f + gap + (vs + 2f) / 2f, vs, vcolor)
        if (sub.isNotEmpty()) {
            val ss = g.fitSize(sub, 10.5f, W_SUB, inner, 9f)
            g.text(g.ellipsize(sub, ss, W_SUB, inner), c.x + 10f, c.y + c.h - 14f, ss, W_SUB, bAlpha(t.ink, 0.86f))
        }
    }

    private fun wind(g: BGx, c: BCell, d: TdWind) {
        kpi(g, c, BPal.WIND, R.drawable.ic_wind, "Vent", fr(d.speed, 0), "km/h", d.gust?.let { "raf. " + fr(it, 0) } ?: "", visualW = 32f)
        d.dir?.let { g.compass(c.x + c.w - 9f - 16f, c.y + 7f + 16f, 32f, it, BPal.WIND.ink) }
    }

    private fun hum(g: BGx, c: BCell, d: TdHum) {
        kpi(g, c, BPal.HUM, R.drawable.ic_droplet, "Humidité", fr(d.hum, 0), "%", d.dew?.let { "rosée " + fr(it, 1) + "°" } ?: "", visualW = 30f)
        g.drop(c.x + c.w - 9f - 15f, c.y + c.h - 12f - 15f, 30f, d.hum)
    }

    private fun home(g: BGx, c: BCell, d: TdHome) {
        kpi(g, c, BPal.HOME, R.drawable.ic_home, "Intérieur", fr(d.temp, 1) + "°", "", if (d.hum != null) "humidité" else "", visualW = if (d.hum != null) 36f else 0f)
        if (d.hum != null) {
            g.ring(
                c.x + c.w - 9f - 18f, c.y + c.h - 8f - 18f, 36f, (d.hum / 100.0).toFloat(), 0xFF9BE8E0.toInt(), 4.5f,
                center = fr(d.hum, 0), tsize = 12f,
            )
        }
    }

    private fun thousands(v: Double): String {
        val n = v.roundToInt()
        return if (n >= 1000) "${n / 1000} ${String.format(java.util.Locale.ROOT, "%03d", n % 1000)}" else n.toString()
    }

    private fun press(g: BGx, c: BCell, d: TdPress) {
        val t = BPal.PRESS
        frame(g, c, t)
        g.label(R.drawable.ic_gauge, "Pression", t.ink, c.x, c.y)
        val delta = d.delta
        val r = delta?.roundToInt() ?: 0
        val flat = delta == null || r == 0
        val up = (delta ?: 0.0) > 0.0
        val col = if (flat) BPal.MUT else if (up) BPal.WARM_TREND else BPal.COOL_TREND
        if (delta != null) {
            val txt = (if (flat) "±0" else (if (up) "+" else "−") + kotlin.math.abs(r))
            val right = c.x + c.w - 10f
            val tw = g.text(txt, right, c.y + 8f + 6.5f, 10.5f, W_STRONG, col, Paint.Align.RIGHT)
            if (!flat) g.trend(right - tw - 2f - 5.5f, c.y + 8f + 6.5f, 11f, up, col, 2.4f)
        }
        val hasSpark = d.series.size >= 3
        val vcy = if (hasSpark) c.y + 23f + 10.5f else c.y + c.h / 2f + 4f
        g.value(thousands(d.p), "hPa", c.x + 10f, vcy, 19f, t.ink)
        if (hasSpark) g.spark(c.x + 10f, c.y + c.h - 6f - 15f, c.w - 20f, 15f, d.series, col, nowIdx = d.series.size - 1, fill = false, sw = 1.6f)
    }

    private fun uvIdx(uv: Double): Int = if (uv < 3) 0 else if (uv < 6) 1 else if (uv < 8) 2 else if (uv < 11) 3 else 4

    private val UV_WORDS = listOf("faible", "modéré", "élevé", "très élevé", "extrême")

    private fun uvSmall(g: BGx, c: BCell, d: TdUv) {
        val t = BPal.SUN
        frame(g, c, t)
        g.label(R.drawable.ic_sun, "UV", t.ink, c.x, c.y)
        val idx = uvIdx(d.uv)
        val col = BPal.UV[idx]
        val rs = 42f
        g.ring(c.x + c.w - 9f - rs / 2f, c.y + 18f + rs / 2f, rs, min(1f, (d.uv / 12.0).toFloat()), col, 5f, center = fr(d.uv, 1), tsize = 13f)
        val room = c.w - 10f - 9f - rs - 6f
        val ws = g.fitSize(UV_WORDS[idx], 15f, W_STRONG, room, 11f)
        g.text(UV_WORDS[idx], c.x + 10f, c.y + 26f + 8.5f, ws, W_STRONG, col)
        if (d.peak.isNotEmpty()) g.text(d.peak, c.x + 10f, c.y + c.h - 14f, 10.5f, W_SUB, bAlpha(t.ink, 0.86f))
    }

    private fun uvWide(g: BGx, c: BCell, d: TdUv) {
        val t = BPal.SUN
        frame(g, c, t)
        g.label(R.drawable.ic_sun, "UV · Soleil", t.ink, c.x, c.y)
        if (d.solar != null) g.text(fr(d.solar, 0) + " W/m²", c.x + c.w - 10f, c.y + 14.5f, 10.5f, W_SUB, bAlpha(t.ink, 0.8f), Paint.Align.RIGHT)
        val idx = uvIdx(d.uv)
        val col = BPal.UV[idx]
        val cy = c.y + 22f + 10.5f
        val vw = g.text(fr(d.uv, 1), c.x + 10f, cy, 19f, W_STRONG, t.ink, spacing = -0.02f)
        g.text(UV_WORDS[idx], c.x + 10f + vw + 7f, cy + 1f, 12.5f, W_STRONG, col)
        if (d.peak.isNotEmpty()) g.text(d.peak, c.x + c.w - 10f, cy + 1f, 10.5f, W_SUB, bAlpha(t.ink, 0.8f), Paint.Align.RIGHT)
        g.scaleBar(c.x + 10f, c.y + c.h - 21f, c.w - 20f, BPal.UV, min(1f, (d.uv / 12.0).toFloat()))
    }

    private fun air(g: BGx, c: BCell, d: TdAir) {
        val t = BPal.AIR
        frame(g, c, t)
        g.label(R.drawable.ic_leaf, d.label, t.ink, c.x, c.y)
        val rs = 42f
        g.ring(c.x + c.w - 9f - rs / 2f, c.y + 18f + rs / 2f, rs, min(1f, d.frac), d.color, 5f, center = d.center, tsize = 13f)
        val room = c.w - 10f - 9f - rs - 6f
        val ws = g.fitSize(d.word, 15f, W_STRONG, room, 11f)
        g.text(d.word, c.x + 10f, c.y + 26f + 8.5f, ws, W_STRONG, t.ink)
        val ss = g.fitSize(d.sub, 10.5f, W_SUB, c.w - 20f, 9f)
        g.text(d.sub, c.x + 10f, c.y + c.h - 14f, ss, W_SUB, bAlpha(t.ink, 0.86f))
    }

    private val POLLEN_WORDS = listOf("", "très faible", "faible", "moyen", "élevé", "très élevé")

    private fun pollen(g: BGx, c: BCell, d: TdPollen) {
        val t = BPal.POLLEN
        frame(g, c, t)
        g.label(R.drawable.ic_leaf, "Pollens", t.ink, c.x, c.y)
        val top = d.rows.maxByOrNull { it.second } ?: return
        g.text(top.first, c.x + 10f, c.y + 26f + 8.5f, 15f, W_STRONG, t.ink)
        val lvl = top.second.coerceIn(1, 5)
        g.text(POLLEN_WORDS[lvl], c.x + 10f, c.y + 26f + 17f + 1f + 6f, 10.5f, W_STRONG, BPal.SCALE5[lvl - 1])
        val bw = 20f
        val gap = 9f
        val total = d.rows.size * bw + (d.rows.size - 1) * gap
        var bx = c.x + c.w - 14f - total
        val bottom = c.y + c.h - 6f
        d.rows.forEach { (name, l) ->
            val bh = 6f + 26f * l / 5f
            val col = if (l > 0) BPal.SCALE5[(l - 1).coerceIn(0, 4)] else bAlpha(BPal.TXT, 0.14f)
            g.round(bx, bottom - 13f - bh, bw, bh, 6f, col)
            g.text(name.take(3), bx + bw / 2f, bottom - 5f, 9f, W_SUB, bAlpha(t.ink, 0.75f), Paint.Align.CENTER)
            bx += bw + gap
        }
    }

    private fun tomorrow(g: BGx, c: BCell, d: TdTom) {
        val t = BPal.TOM
        frame(g, c, t)
        g.label(R.drawable.ic_calendar, "Demain", t.ink, c.x, c.y)
        val descCy = c.y + c.h - 14f
        val valCy = descCy - 6f - 11f
        g.icon(d.icon, c.x + 10f + 13f, (valCy + descCy) / 2f, 26f, d.iconColor)
        val tx = c.x + 10f + 26f + 8f
        val right = if (d.l1.isNotEmpty() || d.l2.isNotEmpty()) {
            max(g.width(d.l1, 10.5f, W_STRONG), g.width(d.l2, 10.5f, W_STRONG)) + 16f
        } else {
            10f
        }
        val room = c.w - (tx - c.x) - right
        val vs = g.fitSize(fr(d.hi, 0) + "°/" + fr(d.lo, 0) + "°", 19f, W_STRONG, room, 14f, -0.02f)
        g.text(fr(d.hi, 0) + "°/" + fr(d.lo, 0) + "°", tx, valCy, vs, W_STRONG, t.ink, spacing = -0.02f)
        val ds = g.fitSize(d.desc, 10.5f, W_SUB, room, 9f)
        g.text(g.ellipsize(d.desc, ds, W_SUB, room), tx, descCy, ds, W_SUB, bAlpha(t.ink, 0.86f))
        if (d.l1.isNotEmpty()) g.text(d.l1, c.x + c.w - 12f, c.y + c.h / 2f - 1f, 10.5f, W_STRONG, bAlpha(t.ink, 0.9f), Paint.Align.RIGHT)
        if (d.l2.isNotEmpty()) g.text(d.l2, c.x + c.w - 12f, c.y + c.h / 2f + 14f, 10.5f, W_STRONG, bAlpha(t.ink, 0.9f), Paint.Align.RIGHT)
    }

    private fun week(g: BGx, c: BCell, d: TdWeek) {
        val t = BPal.WEEK
        frame(g, c, t)
        val n = d.days.size
        val cw = (c.w - 12f) / n
        val block = 11f + 18f + 14f + 11f + 6f
        val top = c.y + 5f + max(0f, (c.h - 10f - block) / 2f)
        d.days.forEachIndexed { k, day ->
            val cx = c.x + 6f + k * cw + cw / 2f
            if (day.today) g.round(c.x + 6f + k * cw, c.y + 5f, cw, c.h - 10f, 14f, bAlpha(BPal.TXT, 0.07f))
            g.text(day.label, cx, top + 5.5f, 10.5f, W_LABEL, bAlpha(t.ink, 0.72f), Paint.Align.CENTER)
            g.icon(day.icon, cx, top + 11f + 2f + 9f, 18f, day.iconColor)
            g.text(day.hi.toString() + "°", cx, top + 11f + 2f + 18f + 2f + 7f, 12.5f, W_STRONG, t.ink, Paint.Align.CENTER)
            g.text(day.lo.toString() + "°", cx, top + 11f + 2f + 18f + 2f + 14f + 2f + 5.5f, 10.5f, W_SUB, bAlpha(t.ink, 0.6f), Paint.Align.CENTER)
        }
    }

    // ───────────────────────────── Pluie ─────────────────────────────

    private fun rain60(g: BGx, c: BCell, d: TdRain60) {
        val t = if (d.storm) BPal.STORM else BPal.RAIN
        frame(g, c, t)
        val labelEnd = g.label(R.drawable.ic_cloud_rain, "Pluie · 60 min", t.ink, c.x, c.y)
        val col = rateCol(d.rate)
        // En-tête à droite : débit, intensité, fin ou début.
        val cy = c.y + 14.5f
        val parts = ArrayList<Triple<String, Int, Int>>() // texte, graisse, couleur
        parts.add(Triple(fr(d.rate, 1) + " mm/h", W_STRONG, col))
        if (d.word.isNotEmpty() && c.w >= 300f) parts.add(Triple(d.word, W_LABEL, col))
        if (d.endTxt != null && c.w >= 300f) parts.add(Triple("· " + d.endTxt, W_SUB, bAlpha(t.ink, 0.85f)))
        val size = { k: Int -> if (k == 0) 12.5f else 10.5f }
        var total = 0f
        parts.forEachIndexed { k, p -> total += g.width(p.first, size(k), p.second) + (if (k > 0) 6f else 0f) }
        val avail = c.x + c.w - 10f - (labelEnd + 8f)
        while (parts.size > 1 && total > avail) {
            val last = parts.removeAt(parts.size - 1)
            total -= g.width(last.first, size(parts.size), last.second) + 6f
        }
        var x = c.x + c.w - 10f - total
        parts.forEachIndexed { k, p ->
            val w = g.text(p.first, x, cy + (if (k == 0) 0f else 1f), size(k), p.second, p.third)
            x += w + 6f
        }
        val bh = (c.h - 26f - 17f).coerceIn(12f, 24f)
        val cols = d.bins.map { rateCol(it) }
        g.bars(c.x + 10f, c.y + 26f, c.w - 20f, bh, d.bins, cols, 4f, 4f, max(1.0, d.bins.maxOrNull() ?: 0.0))
        val ly = c.y + c.h - 5f - 5f
        g.text("maintenant", c.x + 10f, ly, 9f, W_SUB, bAlpha(t.ink, 0.65f))
        g.text("+30 min", c.x + c.w / 2f, ly, 9f, W_SUB, bAlpha(t.ink, 0.65f), Paint.Align.CENTER)
        g.text("+60 min", c.x + c.w - 10f, ly, 9f, W_SUB, bAlpha(t.ink, 0.65f), Paint.Align.RIGHT)
    }

    private fun rain12(g: BGx, c: BCell, d: TdRain12) {
        val t = BPal.RAIN
        g.round(c.x, c.y, c.w, c.h, 26f.coerceAtMost(c.h * 0.34f), t.bg)
        g.label(R.drawable.ic_cloud_rain, "Pluie · 12 h", t.ink, c.x, c.y, padL = 12f, padT = 9f)
        // Pastille de risque.
        val rt = "risque " + d.risk
        val rw = g.width(rt, 10.5f, W_STRONG)
        val chip = RectF(c.x + c.w - 12f - rw - 16f, c.y + 9f + 6.5f - 9.5f, c.x + c.w - 12f, c.y + 9f + 6.5f + 9.5f)
        g.c.drawRoundRect(chip, 9.5f, 9.5f, bFill(bAlpha(BPal.TXT, 0.1f)))
        g.text(rt, chip.centerX(), chip.centerY(), 10.5f, W_STRONG, t.ink, Paint.Align.CENTER)

        val cw = c.w - 24f
        val ch = (c.h - 30f - 12f - 29f - 9f - 4f).coerceIn(28f, 64f)
        val n = d.mm.size
        val gap = 5f
        val bw = (cw - gap * (n - 1)) / n
        g.bars(c.x + 12f, c.y + 30f, cw, ch, d.mm, d.mm.map { rateCol(it) }, gap, 4f, max(1.6, d.mm.maxOrNull() ?: 0.0))
        // Probabilité de pluie, en pointillés par-dessus.
        if (d.prob.size == n && n >= 3) {
            val xs = FloatArray(n) { c.x + 12f + it * (bw + gap) + bw / 2f }
            val ys = FloatArray(n) { c.y + 30f + ch - 4f - (d.prob[it] / 100.0).toFloat() * (ch - 8f) }
            val path = Path().apply {
                moveTo(xs[0], ys[0])
                for (i in 0 until n - 1) {
                    val i0 = max(0, i - 1)
                    val i3 = min(n - 1, i + 2)
                    cubicTo(
                        xs[i] + (xs[i + 1] - xs[i0]) / 6f, ys[i] + (ys[i + 1] - ys[i0]) / 6f,
                        xs[i + 1] - (xs[i3] - xs[i]) / 6f, ys[i + 1] - (ys[i3] - ys[i]) / 6f,
                        xs[i + 1], ys[i + 1],
                    )
                }
            }
            val dash = bStroke(bAlpha(BPal.TXT, 0.75f), 1.5f).apply { pathEffect = DashPathEffect(floatArrayOf(1f, 3.5f), 0f) }
            g.c.drawPath(path, dash)
        }
        // Heures sous les barres (une sur trois quand la place manque).
        val step = if (bw >= 22f) 1 else 3
        d.hours.forEachIndexed { k, h ->
            if (k % step == 0) g.text(h, c.x + 12f + k * (bw + gap) + bw / 2f, c.y + 30f + ch + 3f + 5f, 9f, W_SUB, bAlpha(t.ink, 0.62f), Paint.Align.CENTER)
        }
        // Début, cumul, fin.
        val items = listOf("Début" to d.start, "Cumul" to d.total, "Fin" to d.end)
        val widths = items.map { max(g.width(it.first, 10f, W_SUB), g.width(it.second, 15f, W_STRONG)) }
        val free = (c.w - 28f) - widths.sum()
        val g2 = max(4f, free / 2f)
        val top = c.y + c.h - 9f - 29f
        var x = c.x + 14f
        items.forEachIndexed { k, (key, v) ->
            g.text(key, x, top + 5.5f, 10f, W_SUB, bAlpha(t.ink, 0.7f))
            g.text(v, x, top + 20.5f, 15f, W_STRONG, if (k == 1) BPal.SNOW_ICE else t.ink, spacing = -0.01f)
            x += widths[k] + g2
        }
    }

    private fun rain12Strip(g: BGx, c: BCell, d: TdRain12) {
        val t = BPal.RAIN
        frame(g, c, t)
        val labelEnd = g.label(R.drawable.ic_cloud_rain, "Pluie · 12 h", t.ink, c.x, c.y)
        val cy = c.y + 14.5f
        val sub = "· " + d.startShort
        val wTotal = g.width(d.total, 12.5f, W_STRONG)
        val wSub = g.width(sub, 10.5f, W_SUB)
        val avail = c.w - 10f - (labelEnd + 8f)
        var x = c.x + c.w - 10f - wTotal
        if (wTotal + 6f + wSub <= avail) {
            x = c.x + c.w - 10f - wTotal - 6f - wSub
            g.text(d.total, x, cy, 12.5f, W_STRONG, BPal.SNOW_ICE)
            g.text(sub, x + wTotal + 6f, cy + 1f, 10.5f, W_SUB, bAlpha(t.ink, 0.85f))
        } else {
            g.text(d.total, x, cy, 12.5f, W_STRONG, BPal.SNOW_ICE)
        }
        val bh = (c.h - 26f - 17f).coerceIn(12f, 24f)
        g.bars(c.x + 10f, c.y + 26f, c.w - 20f, bh, d.mm, d.mm.map { rateCol(it) }, 4f, 4f, max(2.0, d.mm.maxOrNull() ?: 0.0))
        val ly = c.y + c.h - 5f - 5f
        val a = d.hours.getOrNull(0)
        val b = d.hours.getOrNull(d.hours.size / 2)
        val z = d.hours.lastOrNull()
        if (a != null) g.text("$a h", c.x + 10f, ly, 9f, W_SUB, bAlpha(t.ink, 0.65f))
        if (b != null) g.text("$b h", c.x + c.w / 2f, ly, 9f, W_SUB, bAlpha(t.ink, 0.65f), Paint.Align.CENTER)
        if (z != null) g.text("$z h", c.x + c.w - 10f, ly, 9f, W_SUB, bAlpha(t.ink, 0.65f), Paint.Align.RIGHT)
    }

    private fun rate(g: BGx, c: BCell, d: TdRate) {
        val t = BPal.RAIN
        frame(g, c, t)
        g.label(R.drawable.ic_cloud_rain, "Intensité", t.ink, c.x, c.y)
        val col = rateCol(d.rate)
        val rs = (c.h - 27f - 6f - 42f - 8f).coerceIn(44f, 62f)
        g.ring(
            c.x + c.w / 2f, c.y + 27f + rs / 2f, rs, min(1f, (d.rate / 10.0).toFloat()), col, 6f,
            center = fr(d.rate, 1), sub = "mm/h", tsize = if (rs >= 56f) 18f else 15f, tcolor = 0xFFFFFFFF.toInt(),
        )
        val top = c.y + 27f + rs + 6f
        g.text(d.word, c.x + c.w / 2f, top + 8f, 14f, W_STRONG, col, Paint.Align.CENTER)
        g.text("cumul " + d.cumul, c.x + c.w / 2f, top + 16f + 1f + 6f, 10.5f, W_SUB, bAlpha(t.ink, 0.85f), Paint.Align.CENTER)
        g.text(d.since, c.x + c.w / 2f, top + 16f + 1f + 12f + 1f + 6f, 10.5f, W_SUB, bAlpha(t.ink, 0.7f), Paint.Align.CENTER)
    }

    // ───────────────────────────── Neige et gel ─────────────────────────────

    private fun snow24(g: BGx, c: BCell, d: TdSnow24) {
        val t = BPal.SNOW
        frame(g, c, t)
        g.label(R.drawable.ic_wx_snow, "Neige · 24 h", t.ink, c.x, c.y)
        val cw = min(112f, c.w * 0.42f)
        val ch = min(38f, c.h - 10f - 16f)
        val room = c.w - 10f - cw - 12f - 8f
        val subCy = c.y + c.h - 14f
        val valCy = subCy - 6f - 12f
        val totalTxt = if (d.total >= 10.0) fr(d.total, 0) else fr(d.total, 1)
        g.value(totalTxt, "cm", c.x + 10f, valCy, 22f, 0xFFEAF6FD.toInt())
        val ss = g.fitSize(d.sub, 10.5f, W_SUB, room, 9f)
        g.text(g.ellipsize(d.sub, ss, W_SUB, room), c.x + 10f, subCy, ss, W_SUB, bAlpha(t.ink, 0.86f))
        val top = d.vals.maxOrNull() ?: 0.0
        val cols = d.vals.map { if (it >= 1.5) 0xFFEAF6FD.toInt() else 0xFF9ADBF5.toInt() }
        val cx = c.x + c.w - 12f - cw
        g.bars(cx, c.y + 10f, cw, ch, d.vals, cols, 4f, 3.5f, top)
        val ly = c.y + c.h - 4f - 5f
        val n = d.axis.size
        d.axis.forEachIndexed { k, s ->
            val align = if (k == 0) Paint.Align.LEFT else if (k == n - 1) Paint.Align.RIGHT else Paint.Align.CENTER
            val px = if (k == 0) cx else if (k == n - 1) cx + cw else cx + cw * k / (n - 1)
            g.text(s, px, ly, 9f, W_SUB, bAlpha(t.ink, 0.6f), align)
        }
    }

    private fun snowAlt(g: BGx, c: BCell, d: TdSnowAlt) {
        val t = BPal.SNOW
        frame(g, c, t)
        g.label(R.drawable.ic_wx_snow, "Lim. neige", t.ink, c.x, c.y)
        val svgH = c.h - 36f
        val gh = svgH - 12f
        val topY = c.y + 28f + 6f
        val botY = topY + gh
        fun yOf(m: Double): Float = (botY - gh * (m / d.topM).toFloat()).coerceIn(topY, botY)
        val bx = c.x + 10f + 4f
        val bw = 16f
        val ly = yOf(d.limit)
        val sy = yOf(d.station)
        val cv = g.c
        g.round(bx, topY, bw, gh, 8f, bAlpha(BPal.TXT, 0.1f))
        cv.save()
        cv.clipPath(Path().apply { addRoundRect(RectF(bx, topY, bx + bw, botY), 8f, 8f, Path.Direction.CW) })
        val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, topY, 0f, max(ly, topY + 1f), 0xFFF4FAFE.toInt(), 0xFFB9E1F5.toInt(), Shader.TileMode.CLAMP)
        }
        cv.drawRect(bx, topY, bx + bw, ly, snowPaint)
        cv.drawRect(bx, ly, bx + bw, botY, bFill(bAlpha(0xFF5BB0FF.toInt(), 0.85f)))
        cv.restore()
        cv.drawLine(bx - 3f, ly, bx + bw + 3f, ly, bStroke(0xFFFFFFFF.toInt(), 2f))
        cv.drawCircle(bx + bw / 2f, sy, 4.2f, bFill(0xFFFFD9A0.toInt()))
        cv.drawCircle(bx + bw / 2f, sy, 4.2f, bStroke(BPal.DARK, 1.4f))

        val tx = bx + bw + 9f
        val room = c.x + c.w - 8f - tx
        val limTxt = thousands(d.limit)
        val vs = g.fitSize("$limTxt m", 20f, W_STRONG, room, 14f, -0.02f)
        g.value(limTxt, "m", tx, c.y + 29f + 11f, vs, t.ink)
        g.text(g.ellipsize(d.sub, 10.5f, W_SUB, room), tx, c.y + 29f + 22f + 2f + 6f, 10.5f, W_SUB, bAlpha(t.ink, 0.85f))
        val stationCy = max(c.y + 80f + 6.5f, sy)
        g.icon(R.drawable.ic_home, tx + 5.5f, stationCy, 11f, 0xFFFFD9A0.toInt())
        g.text(thousands(d.station) + " m", tx + 15f, stationCy, 10.5f, W_STRONG, 0xFFFFD9A0.toInt())
        if (d.iso != null) g.text(g.ellipsize("iso 0° " + thousands(d.iso) + " m", 10f, W_SUB, room), tx, c.y + c.h - 16f, 10f, W_SUB, bAlpha(t.ink, 0.7f))
    }

    private fun snowLim(g: BGx, c: BCell, d: TdSnowAlt) {
        kpi(g, c, BPal.SNOW, R.drawable.ic_wx_snow, "Lim. neige", thousands(d.limit), "m", d.sub)
    }

    private fun frost(g: BGx, c: BCell, d: TdFrost) {
        val t = BTint(0xFF1B3850.toInt(), 0xFFC6E7F8.toInt())
        frame(g, c, t)
        g.label(R.drawable.ic_flake, d.title, t.ink, c.x, c.y)
        val cw = min(104f, c.w * 0.45f)
        val ch = min(44f, c.h - 14f - 16f)
        val room = c.w - 10f - cw - 12f - 8f
        val subCy = c.y + c.h - 14f
        val valCy = subCy - 6f - 12f
        g.text(d.value, c.x + 10f, valCy, 22f, W_STRONG, BPal.ICE_BLUE, spacing = -0.02f)
        val ss = g.fitSize(d.sub, 10.5f, W_SUB, room, 8.5f)
        g.text(g.ellipsize(d.sub, ss, W_SUB, room), c.x + 10f, subCy, ss, W_SUB, bAlpha(t.ink, 0.86f))
        val cx = c.x + c.w - 12f - cw
        g.spark(
            cx, c.y + 14f, cw, ch, d.temps, BPal.ICE_BLUE, nowIdx = 0, fill = false, sw = 1.8f,
            zero = true, below = bAlpha(BPal.ICE_BLUE, 0.22f),
        )
        val ly = c.y + c.h - 4f - 5f
        d.xl.forEachIndexed { k, s ->
            val align = if (k == 0) Paint.Align.LEFT else if (k == d.xl.size - 1) Paint.Align.RIGHT else Paint.Align.CENTER
            val px = if (k == 0) cx else if (k == d.xl.size - 1) cx + cw else cx + cw / 2f
            g.text(s, px, ly, 9f, W_SUB, bAlpha(t.ink, 0.6f), align)
        }
    }

    // ───────────────────────────── Hors ligne, orage ─────────────────────────────

    private fun offline(g: BGx, c: BCell, d: TdOffline) {
        val t = BPal.OFF
        frame(g, c, t)
        val big = c.w >= 300f
        val circle = if (big) 40f else 34f
        val cy = c.y + c.h / 2f
        val ccx = c.x + (if (big) 12f else 10f) + circle / 2f
        g.c.drawCircle(ccx, cy, circle / 2f, bFill(bAlpha(BPal.WARN, 0.16f)))
        g.icon(R.drawable.ic_wifi_off, ccx, cy, if (big) 20f else 18f, BPal.WARN)
        val tx = c.x + (if (big) 62f else 52f)
        val room = c.x + c.w - 8f - tx
        val l1 = if (big) "Dernière mesure ${d.last} · il y a ${d.since}" else "Mesure de ${d.last}"
        val l2 = if (big) "Nouvel essai automatique · touche pour actualiser" else "il y a ${d.since} · nouvel essai auto"
        val s1 = g.fitSize(l1, if (big) 12.5f else 12f, W_STRONG, room, 10f)
        val s2 = g.fitSize(l2, 10.5f, W_SUB, room, 8.5f)
        g.text(l1, tx, cy - 8f, s1, W_STRONG, t.ink)
        g.text(g.ellipsize(l2, s2, W_SUB, room), tx, cy + 8f, s2, W_SUB, bAlpha(t.ink, 0.8f))
    }

    private fun storm(g: BGx, c: BCell, d: TdStorm) {
        val t = BPal.STORM
        frame(g, c, t)
        g.label(R.drawable.ic_wx_storm, "Orage", t.ink, c.x, c.y)
        val withGust = c.h >= 125f && d.gust.isNotEmpty()
        val textBlock = 16f + 1f + 12f + 1f + 12f + (if (withGust) 13f else 0f)
        val rs = (c.h - 28f - 6f - textBlock - 8f).coerceIn(40f, 66f)
        val frac = max(0.08f, 1f - d.minutes / 60f)
        val center = if (d.minutes <= 0) "ici" else d.minutes.toString()
        g.ring(
            c.x + c.w / 2f, c.y + 28f + rs / 2f, rs, frac, BPal.STORM_PURPLE, 5.5f,
            center = center, sub = if (d.minutes <= 0) "" else "min", tsize = if (rs >= 56f) 22f else 18f, tcolor = 0xFFFFFFFF.toInt(),
        )
        val top = c.y + 28f + rs + 6f
        val mid = c.x + c.w / 2f
        g.text(d.whenTxt, mid, top + 8f, 14f, W_STRONG, t.ink, Paint.Align.CENTER)
        g.text(d.word, mid, top + 16f + 1f + 6f, 10.5f, W_STRONG, 0xFFF25C9B.toInt(), Paint.Align.CENTER)
        if (d.rate != "—") g.text("≈ " + d.rate + " mm/h", mid, top + 16f + 1f + 12f + 1f + 6f, 10.5f, W_SUB, bAlpha(t.ink, 0.85f), Paint.Align.CENTER)
        if (withGust) g.text(d.gust, mid, top + 16f + 1f + 12f + 1f + 12f + 1f + 6f, 10.5f, W_SUB, bAlpha(t.ink, 0.85f), Paint.Align.CENTER)
    }

    private fun gusts(g: BGx, c: BCell, d: TdGusts) {
        val t = BPal.WIND
        frame(g, c, t)
        g.label(R.drawable.ic_wind, "Rafales · 12 h", t.ink, c.x, c.y)
        g.text(d.peak, c.x + c.w - 10f, c.y + 14.5f, 10.5f, W_STRONG, 0xFFFF9CC0.toInt(), Paint.Align.RIGHT)
        val cols = d.vals.map { if (it >= 60.0) 0xFFFF8DB6.toInt() else if (it >= 40.0) 0xFFFFC857.toInt() else 0xFFB9AEFF.toInt() }
        val bh = (c.h - 25f - 17f).coerceIn(12f, 24f)
        g.bars(c.x + 10f, c.y + 25f, c.w - 20f, bh, d.vals, cols, 4f, 4f, max(1.0, d.vals.maxOrNull() ?: 1.0))
        val n = d.vals.size
        val bw = (c.w - 20f - 4f * (n - 1)) / n
        val step = if (bw >= 22f) 1 else 2
        d.hours.forEachIndexed { k, h ->
            if (k % step == 0) g.text(h, c.x + 10f + k * (bw + 4f) + bw / 2f, c.y + c.h - 5f - 5f, 9f, W_SUB, bAlpha(t.ink, 0.65f), Paint.Align.CENTER)
        }
    }

    // ───────────────────────────── Duo humidité / pression ─────────────────────────────

    private fun humMini(g: BGx, c: BCell, d: TdMiniHum) {
        val t = BPal.HUM
        g.round(c.x, c.y, c.w, c.h, 14f.coerceAtMost(c.h / 2f), t.bg)
        val cy = c.y + c.h / 2f
        g.icon(R.drawable.ic_droplet, c.x + 9f + 6.5f, cy, 13f, t.ink)
        val vw = g.text(fr(d.hum, 0), c.x + 9f + 13f + 5f, cy, 15f, W_STRONG, t.ink, spacing = -0.015f)
        g.text("%", c.x + 9f + 13f + 5f + vw - 1f, cy + 1f, 10f, W_UNIT, t.ink)
    }

    private fun pressMini(g: BGx, c: BCell, d: TdMiniPress) {
        val t = BPal.PRESS
        g.round(c.x, c.y, c.w, c.h, 14f.coerceAtMost(c.h / 2f), t.bg)
        val cy = c.y + c.h / 2f
        g.icon(R.drawable.ic_gauge, c.x + 9f + 6.5f, cy, 13f, t.ink)
        val vw = g.text(thousands(d.p), c.x + 9f + 13f + 5f, cy, 15f, W_STRONG, t.ink, spacing = -0.015f)
        g.text("hPa", c.x + 9f + 13f + 5f + vw + 1f, cy + 1f, 10f, W_UNIT, t.ink)
        if (d.up != null) g.trend(c.x + c.w - 9f - 5.5f, cy, 11f, d.up, if (d.up) BPal.WARM_TREND else BPal.COOL_TREND, 2.4f)
    }
}
