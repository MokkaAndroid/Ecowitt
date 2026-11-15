package app.ecowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser
import app.ecowidget.R
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.RainColors
import app.ecowidget.data.Tile
import app.ecowidget.data.TileSummary
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Les tuiles de la grille « Bento », avec leur place. */
internal enum class BentoKey { HERO, PREV, RAIN, WIND, HUM, PRESS, SNOW, TOM, SUN, AIR, HOME, SHORT }

/** Une tuile posée sur la grille : position et taille en dp, dans le dessin. */
internal class BentoCell(val key: BentoKey, val s: TileSummary, val x: Float, val y: Float, val w: Float, val h: Float)

/** Pastille d'alerte : texte, pluie ou non, teinte. */
internal class BentoPill(val text: String, val rain: Boolean, val tint: Int)

/** Texte de la pastille tel qu'il tient dans la place disponible. */
internal class PillLayout(val text: String, val size: Float, val width: Float, val height: Float)

/**
 * Mise en page du widget « Bento sombre », en dp : calculée une fois, puis servant au dessin (bitmap)
 * et aux zones cliquables posées par-dessus, pour que les deux restent alignés.
 */
internal class BentoPlan(
    val short: Boolean,
    val rows: Int,
    val w: Float,
    val h: Float,
    val headerH: Float,
    val gap: Float,
    val cells: List<BentoCell>,
    val pill: BentoPill?,
    val pillLayout: PillLayout?,
    /** Zone de la pastille : dans le héros (grille) ou dans l'en-tête (format bas). */
    val pillRect: RectF?,
    val refresh: RectF,
    val bins: List<Double>,
    val windDir: Double?,
    val humidity: Double?,
    val place: String,
    val status: String,
    val warn: Boolean,
) {
    fun cell(key: BentoKey): BentoCell? = cells.firstOrNull { it.key == key }
}

internal object Bento {
    const val PAD = 12f
    private const val HEADER_H = 32f
    private const val GAP = 8f
    private const val MIN_ROW = 50f
    private const val SHORT_HEADER_H = 24f
    private const val PILL_H = 30f
    private const val SHORT_PILL_H = 22f
    private const val MAX_PIXELS = 1_500_000f
    private const val DROP = "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z"

    // ───────────────────────────── Palette (fond, encre) ─────────────────────────────

    private class Tint(val bg: Int, val ink: Int)

    private fun argb(v: Long): Int = v.toInt()

    private val T_HERO = Tint(argb(0xFF123A38), argb(0xFFBDEFE9))
    private val T_PREV = Tint(argb(0xFF17304A), argb(0xFFBBD9F5))
    private val T_RAIN = Tint(argb(0xFF25295C), argb(0xFFCBD0FF))
    private val T_WIND = Tint(argb(0xFF2F2A55), argb(0xFFD7D0FF))
    private val T_HUM = Tint(argb(0xFF153A52), argb(0xFFBFE4F8))
    private val T_PRESS = Tint(argb(0xFF3F3B15), argb(0xFFEEE2A0))
    private val T_SNOW = Tint(argb(0xFF213645), argb(0xFFCFE5F2))
    private val T_TOM = Tint(argb(0xFF143D44), argb(0xFFBDE8EF))
    private val T_SUN = Tint(argb(0xFF4D3516), argb(0xFFFFD9A0))
    private val T_AIR = Tint(argb(0xFF153D26), argb(0xFFC2F0D2))
    private val T_HOME = Tint(argb(0xFF2A323C), argb(0xFFD0DAE4))

    private val TXT = argb(0xFFEAF1F7)
    private val MUT = argb(0xFFA3B4C5)
    private val WARN = argb(0xFFFBBF24)
    private val GREEN = argb(0xFF5FD08A)
    private val RAIN_DEFAULT = argb(0xFF7C8CFF)
    private val AQI = listOf(argb(0xFF3FBF7F), argb(0xFFA8D65C), argb(0xFFF2C94C), argb(0xFFF2994A), argb(0xFFEB5757))

    private fun tint(key: BentoKey, s: TileSummary): Tint = when (key) {
        BentoKey.HERO -> T_HERO
        BentoKey.PREV -> T_PREV
        BentoKey.RAIN -> T_RAIN
        BentoKey.WIND -> T_WIND
        BentoKey.HUM -> T_HUM
        BentoKey.PRESS -> T_PRESS
        BentoKey.SNOW -> T_SNOW
        BentoKey.TOM -> T_TOM
        BentoKey.SUN -> T_SUN
        BentoKey.AIR -> T_AIR
        BentoKey.HOME -> T_HOME
        BentoKey.SHORT -> when (s.tile) {
            Tile.TEMP -> T_HERO
            Tile.RAIN -> T_RAIN
            Tile.SNOW -> T_SNOW
            Tile.WIND -> T_WIND
            Tile.HUM -> T_HUM
            Tile.PRESS -> T_PRESS
            Tile.FORECAST -> T_PREV
            Tile.SUN -> T_SUN
            Tile.AIR -> T_AIR
        }
    }

    // ───────────────────────────── Polices ─────────────────────────────

    private class Fonts(val regular: Typeface, val medium: Typeface, val bold: Typeface)

    @Volatile
    private var cache: Fonts? = null

    private fun fonts(ctx: Context): Fonts {
        cache?.let { return it }
        val loaded = Fonts(
            load(ctx, R.font.dmsans_regular, Typeface.DEFAULT),
            load(ctx, R.font.dmsans_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL)),
            load(ctx, R.font.dmsans_bold, Typeface.DEFAULT_BOLD),
        )
        cache = loaded
        return loaded
    }

    private fun load(ctx: Context, res: Int, fallback: Typeface): Typeface =
        try {
            ctx.resources.getFont(res)
        } catch (e: Exception) {
            fallback
        }

    // ───────────────────────────── Mise en page ─────────────────────────────

    /**
     * Grille Bento (3 colonnes) quand la hauteur le permet, sinon une rangée de petites tuiles.
     * `w` × `h` : zone intérieure du widget, en dp.
     */
    fun plan(
        ctx: Context,
        i: DetailInputs,
        summaries: List<TileSummary>,
        status: Status,
        placeName: String?,
        w: Float,
        h: Float,
    ): BentoPlan? {
        if (summaries.isEmpty() || w < 100f || h < 24f) return null
        val f = fonts(ctx)
        val bins = DetailBuilder.rainBins(i.forecast, i.snap?.rainRate)
        val peak = max(bins.maxOrNull() ?: 0.0, i.snap?.rainRate ?: 0.0)
        val alert = DetailBuilder.alert(i)
        val pill = alert?.let { a ->
            if (a.rain) {
                BentoPill(a.text, true, if (peak > 0.0) RainColors.hue(RainColors.RATE, peak).c else RAIN_DEFAULT)
            } else {
                BentoPill(a.text, false, GREEN)
            }
        }
        val place = placeName?.substringBefore(',')?.trim().orEmpty()

        val ext = summaries.firstOrNull { it.tile == Tile.TEMP }
        val prev = summaries.firstOrNull { it.tile == Tile.FORECAST && it.tab == 0 }
        val rain = summaries.firstOrNull { it.tile == Tile.RAIN }
        val wind = summaries.firstOrNull { it.tile == Tile.WIND }
        val hum = summaries.firstOrNull { it.tile == Tile.HUM && it.label != "Intérieur" }
        val press = summaries.firstOrNull { it.tile == Tile.PRESS }
        val snow = summaries.firstOrNull { it.tile == Tile.SNOW }
        val tom = summaries.firstOrNull { it.tile == Tile.FORECAST && it.tab == 1 }
        val sun = summaries.firstOrNull { it.tile == Tile.SUN }
        val air = summaries.firstOrNull { it.tile == Tile.AIR }
        val home = summaries.firstOrNull { it.tile == Tile.HUM && it.label == "Intérieur" }

        val gridH = h - HEADER_H - GAP
        val rows = min(5, ((gridH + GAP) / (MIN_ROW + GAP)).toInt())
        if (rows >= 3 && w >= 240f) {
            val colW = (w - 2f * GAP) / 3f
            val rowH = (gridH - (rows - 1) * GAP) / rows
            val top = HEADER_H + GAP
            val cells = ArrayList<BentoCell>()
            fun add(key: BentoKey, s: TileSummary?, col: Int, row: Int, spanC: Int = 1, spanR: Int = 1) {
                if (s == null) return
                cells.add(
                    BentoCell(
                        key, s, col * (colW + GAP), top + row * (rowH + GAP),
                        spanC * colW + (spanC - 1) * GAP, spanR * rowH + (spanR - 1) * GAP,
                    ),
                )
            }
            add(BentoKey.HERO, ext, 0, 0, 2, 2)
            add(BentoKey.PREV, prev, 2, 0)
            add(BentoKey.RAIN, rain, 2, 1)
            add(BentoKey.WIND, wind, 0, 2)
            add(BentoKey.HUM, hum, 1, 2)
            add(BentoKey.PRESS, press, 2, 2)
            if (rows >= 4) {
                add(BentoKey.SNOW, snow, 0, 3)
                add(BentoKey.TOM, tom, 1, 3)
                add(BentoKey.SUN, sun, 2, 3)
            }
            if (rows >= 5) {
                add(BentoKey.AIR, air, 0, 4, 2, 1)
                add(BentoKey.HOME, home, 2, 4)
            }
            val hero = cells.firstOrNull { it.key == BentoKey.HERO }
            var layout: PillLayout? = null
            var rect: RectF? = null
            if (pill != null && hero != null) {
                layout = pillLayout(f, pill.text, PILL_H, hero.w - 24f)
                rect = RectF(hero.x + 12f, hero.y + hero.h - 11f - PILL_H, hero.x + 12f + layout.width, hero.y + hero.h - 11f)
            }
            return BentoPlan(
                short = false, rows = rows, w = w, h = h, headerH = HEADER_H, gap = GAP, cells = cells,
                pill = pill, pillLayout = layout, pillRect = rect,
                refresh = RectF(w - 32f, 0f, w, 32f),
                bins = bins, windDir = i.snap?.windDir, humidity = i.snap?.humidity,
                place = place, status = status.text, warn = status.warn,
            )
        }

        // Format bas : une rangée de petites tuiles, avec un en-tête quand il y a la place.
        val showHeader = h >= 62f
        val headerH = if (showHeader) SHORT_HEADER_H else 0f
        val below = if (showHeader) 6f else 0f
        val tileH = min(56f, h - headerH - below)
        val shown = summaries
            .filter { it.tab == 0 && it.label != "Intérieur" && it.tile in SHORT }
            .sortedBy { it.tile.ordinal }
        val n = min(shown.size, max(3, (w / 46f).toInt()))
        if (n == 0) return null
        val tw = (w - (n - 1) * 4f) / n
        val cells = shown.take(n).mapIndexed { k, s -> BentoCell(BentoKey.SHORT, s, k * (tw + 4f), headerH + below, tw, tileH) }
        var layout: PillLayout? = null
        var rect: RectF? = null
        if (showHeader && pill != null) {
            val placeW = tp(f.bold, 0, 13f).measureText(place + (if (status.text.isNotEmpty()) " · " + status.text else ""))
            val maxW = w - SHORT_HEADER_H - 8f - placeW - 14f
            if (maxW >= 90f) {
                layout = pillLayout(f, pill.text, SHORT_PILL_H, maxW)
                val right = w - SHORT_HEADER_H - 8f
                rect = RectF(right - layout.width, (SHORT_HEADER_H - SHORT_PILL_H) / 2f, right, (SHORT_HEADER_H + SHORT_PILL_H) / 2f)
            }
        }
        return BentoPlan(
            short = true, rows = 1, w = w, h = h, headerH = headerH, gap = below, cells = cells,
            pill = pill, pillLayout = layout, pillRect = rect,
            refresh = if (showHeader) RectF(w - SHORT_HEADER_H, 0f, w, SHORT_HEADER_H) else RectF(),
            bins = bins, windDir = i.snap?.windDir, humidity = i.snap?.humidity,
            place = place, status = status.text, warn = status.warn,
        )
    }

    private val SHORT = setOf(Tile.TEMP, Tile.RAIN, Tile.SNOW, Tile.WIND, Tile.HUM, Tile.PRESS, Tile.FORECAST)

    private fun pillLayout(f: Fonts, text: String, h: Float, maxW: Float): PillLayout {
        val big = h >= 28f
        val padL = if (big) 9f else 7f
        val padR = if (big) 10f else 8f
        val ic = if (big) 14f else 12f
        var size = if (big) 11.5f else 10.5f
        fun width(t: String, s: Float) = padL + ic + 5f + tp(f.bold, 0, s).measureText(t) + padR
        var t = text
        while (width(t, size) > maxW && size > 9.5f) size -= 0.5f
        if (width(t, size) > maxW) {
            val avail = maxW - (padL + ic + 5f + padR) - 8f
            val n = tp(f.bold, 0, size).breakText(t, true, max(10f, avail), null)
            t = t.take(n).trimEnd() + "…"
        }
        return PillLayout(t, size, width(t, size), h)
    }

    // ───────────────────────────── Dessin ─────────────────────────────

    private fun tp(tf: Typeface, color: Int, size: Float, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            this.color = color
            textSize = size
            textAlign = align
            letterSpacing = spacing
        }

    private fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int, w: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a * 255f).roundToInt().coerceIn(0, 255) shl 24)

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val dropPath: Path by lazy { PathParser.createPathFromPathData(DROP) }

    private class Gx(val ctx: Context, val f: Fonts, val c: Canvas, val k: Float) {
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
    }

    fun render(ctx: Context, plan: BentoPlan, density: Float): Bitmap {
        // Le système limite la mémoire des images d'un widget : au-delà de ~1,5 million de pixels, on réduit un peu l'échelle.
        val k = min(density, kotlin.math.sqrt(MAX_PIXELS / (plan.w * plan.h)))
        val bmp = Bitmap.createBitmap(
            max(1, (plan.w * k).roundToInt()), max(1, (plan.h * k).roundToInt()), Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        c.scale(k, k)
        val g = Gx(ctx, fonts(ctx), c, k)
        try {
            header(g, plan)
            plan.cells.forEach { cell ->
                when (cell.key) {
                    BentoKey.HERO -> hero(g, plan, cell)
                    BentoKey.SHORT -> shortTile(g, cell)
                    else -> tile(g, plan, cell)
                }
            }
        } catch (e: Exception) {
            c.drawText("Données indisponibles", 4f, 16f, tp(g.f.regular, MUT, 12f))
        }
        return bmp
    }

    private fun header(g: Gx, plan: BentoPlan) {
        val c = g.c
        val f = g.f
        if (plan.short && plan.headerH <= 0f) return
        val hh = if (plan.short) plan.headerH else HEADER_H
        val size = if (plan.short) 13f else 15f
        val base = hh / 2f + 0.36f * size
        var x = 4f
        if (plan.place.isNotEmpty()) {
            val pp = tp(f.bold, TXT, size)
            c.drawText(plan.place, x, base, pp)
            x += pp.measureText(plan.place)
        }
        val tail = if (plan.place.isNotEmpty() && plan.status.isNotEmpty()) " · " + plan.status else plan.status
        if (tail.isNotEmpty()) c.drawText(tail, x, base, tp(f.medium, if (plan.warn) WARN else MUT, size))

        // Pastille d'alerte dans l'en-tête (format bas seulement : dans la grille, elle est dans le héros).
        if (plan.short && plan.pill != null && plan.pillLayout != null && plan.pillRect != null) {
            pill(g, plan.pill, plan.pillLayout, plan.pillRect)
        }
        // Actualiser.
        val r = plan.refresh
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, fill(alpha(TXT, 0.09f)))
        g.icon(R.drawable.ic_refresh, r.centerX(), r.centerY(), if (plan.short) 13f else 15f, TXT)
    }

    private fun pill(g: Gx, p: BentoPill, l: PillLayout, r: RectF) {
        val c = g.c
        val ink = if (p.rain) mix(p.tint, TXT, 0.6f) else mix(p.tint, TXT, 0.45f)
        val rad = r.height() / 2f
        c.drawRoundRect(r, rad, rad, fill(alpha(p.tint, 0.24f)))
        val inset = RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f)
        c.drawRoundRect(inset, rad, rad, stroke(alpha(p.tint, 0.5f), 1f))
        val big = r.height() >= 28f
        val ic = if (big) 14f else 12f
        val padL = if (big) 9f else 7f
        g.icon(if (p.rain) R.drawable.ic_cloud_rain else R.drawable.ic_check, r.left + padL + ic / 2f, r.centerY(), ic, ink)
        c.drawText(l.text, r.left + padL + ic + 5f, r.centerY() + 0.36f * l.size, tp(g.f.bold, ink, l.size))
    }

    private fun hero(g: Gx, plan: BentoPlan, cell: BentoCell) {
        val c = g.c
        val f = g.f
        val s = cell.s
        val ink = T_HERO.ink
        val x = cell.x
        val y = cell.y
        val rad = min(28f, cell.h * 0.26f)
        c.drawRoundRect(RectF(x, y, x + cell.w, y + cell.h), rad, rad, fill(T_HERO.bg))

        // Libellé.
        g.icon(s.icon, x + 12f + 7f, y + 11f + 7f, 14f, ink)
        c.drawText(s.label, x + 12f + 14f + 6f, y + 11f + 7f + 0.36f * 12f, tp(f.medium, ink, 12f))

        // Grande température.
        val hasPill = plan.pillRect != null
        val avail = cell.h - 31f - (if (hasPill) PILL_H + 19f else 11f)
        val cap = min(46f, max(20f, avail))
        val ts = cap / 0.7f
        val tempColor = TempColors.forTemp(s.colorTemp, false, androidx.compose.ui.graphics.Color(ink)).toArgb()
        val vp = tp(f.bold, tempColor, ts, spacing = -0.05f)
        val top = y + 31f
        c.drawText(s.value, x + 12f, top + cap, vp)
        val vw = vp.measureText(s.value)
        var endX = x + 12f + vw
        if (s.unit.isNotEmpty()) {
            val ds = ts * 0.5f
            c.drawText(s.unit, endX + 1f, top + 0.7f * ds, tp(f.bold, tempColor, ds))
            endX += 1f + tp(f.bold, tempColor, ds).measureText(s.unit)
        }
        if (s.trend == 1 || s.trend == -1) {
            val col = if (s.trend == 1) argb(0xFFFDBA74) else argb(0xFF7CC0FF)
            c.drawText(if (s.trend == 1) "▲" else "▼", endX + 4f, top + 12f, tp(f.medium, col, 11f))
        }

        // Max / min / ressenti à droite.
        val sideTop = if (cell.h < 125f) y + 11f else y + 30f
        val right = x + cell.w - 12f
        s.side.take(3).forEachIndexed { n, (label, value) ->
            val base = sideTop + 12.5f + n * 16f
            val valuePaint = tp(f.bold, ink, 12f, Paint.Align.RIGHT)
            c.drawText(value, right, base, valuePaint)
            c.drawText(label, right - valuePaint.measureText(value) - 4f, base, tp(f.regular, alpha(ink, 0.85f), 12f, Paint.Align.RIGHT))
        }

        // Pastille d'alerte en bas du héros.
        if (plan.pill != null && plan.pillLayout != null && plan.pillRect != null) {
            pill(g, plan.pill, plan.pillLayout, plan.pillRect)
        }
    }

    private fun tile(g: Gx, plan: BentoPlan, cell: BentoCell) {
        val c = g.c
        val f = g.f
        val s = cell.s
        val t = tint(cell.key, s)
        val ink = t.ink
        val x = cell.x
        val y = cell.y
        val w = cell.w
        val h = cell.h
        val padH = 10f
        val padV = if (h < 58f) 5.5f else 7f
        val rad = min(22f, h * 0.34f)
        val rect = RectF(x, y, x + w, y + h)
        c.drawRoundRect(rect, rad, rad, fill(if (s.tinted) mix(t.bg, argb(0xFF4A7392), 0.45f) else t.bg))
        if (s.tinted) c.drawRoundRect(RectF(x + 0.75f, y + 0.75f, x + w - 0.75f, y + h - 0.75f), rad, rad, stroke(argb(0xFFBFE3F2), 1.5f))

        // Libellé et icône.
        val cy = y + padV + 6.5f
        g.icon(s.icon, x + padH + 6.5f, cy, 13f, ink)
        val labelX = x + padH + 13f + 5f
        c.drawText(s.label, labelX, cy + 0.36f * 11.5f, tp(f.medium, ink, 11.5f))
        val labelEnd = labelX + tp(f.medium, ink, 11.5f).measureText(s.label)
        val rightX = x + w - padH

        // Repère à droite du libellé.
        when (cell.key) {
            BentoKey.RAIN -> miniBars(g, plan.bins, rightX, cy, labelEnd + 6f)
            BentoKey.WIND -> plan.windDir?.let { windChip(g, rightX - 10f, cy, it, ink) }
            BentoKey.HUM -> plan.humidity?.let { dropChip(g, rightX - 10f, cy, it) }
            BentoKey.PRESS -> if (s.trend == 1 || s.trend == -1) trendChip(g, rightX - 10f, cy, s.trend == 1, ink)
            else -> {}
        }

        // Valeur et sous-ligne.
        val vsBase = (h * 0.31f).coerceIn(16.5f, 21.5f)
        var vs = vsBase
        val vp = tp(f.bold, ink, vs, spacing = -0.03f)
        val up = tp(f.bold, ink, 12f)
        val inner = w - 2f * padH
        fun total() = vp.measureText(s.value) + if (s.unit.isNotEmpty()) 3f + up.measureText(s.unit) else 0f
        while (total() > inner && vs > 14f) {
            vs -= 0.5f
            vp.textSize = vs
            up.textSize = max(10f, vs * 0.57f)
        }
        val cap = vs * 0.72f
        val subCap = 8.5f
        val free = max(0f, h - 2f * padV - 13f - cap - subCap)
        val gap = free / 2f
        val vBase = y + padV + 13f + gap + cap
        c.drawText(s.value, x + padH, vBase, vp)
        if (s.unit.isNotEmpty()) c.drawText(s.unit, x + padH + vp.measureText(s.value) + 3f, vBase, up)
        val subBase = min(vBase + gap + subCap, y + h - 3f)
        if (s.sub.isNotEmpty()) {
            val sp = tp(f.medium, alpha(ink, 0.88f), 11f)
            fit(sp, s.sub, inner, 9.5f)
            c.drawText(ellipsize(sp, s.sub, inner), x + padH, subBase, sp)
        }

        // Échelle d'indice d'air.
        if (cell.key == BentoKey.AIR) s.value.toIntOrNull()?.let { aqi(g, x + w - padH - 98f, y + h - 23f, it, ink) }
    }

    private fun fit(p: Paint, text: String, maxW: Float, minSize: Float) {
        while (p.measureText(text) > maxW && p.textSize > minSize) p.textSize -= 0.5f
    }

    private fun ellipsize(p: Paint, text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        val n = p.breakText(text, true, max(10f, maxW - p.measureText("…")), null)
        return text.take(n).trimEnd() + "…"
    }

    private fun miniBars(g: Gx, bins: List<Double>, rightX: Float, cy: Float, minX: Float) {
        val peak = bins.maxOrNull() ?: 0.0
        if (peak <= 0.0) return
        var bw = 2.5f
        var gap = 1.5f
        var total = bins.size * bw + (bins.size - 1) * gap
        if (rightX - total < minX) {
            bw = 2f
            gap = 1f
            total = bins.size * bw + (bins.size - 1) * gap
        }
        if (rightX - total < minX) return
        val top = max(1.0, peak)
        val h = 14f
        bins.forEachIndexed { n, v ->
            val bh = if (v > 0.0) max(2f, h * (v / top).toFloat()) else 2f
            val col = if (v > 0.0) RainColors.hue(RainColors.RATE, v).c else alpha(TXT, 0.16f)
            val x = rightX - total + n * (bw + gap)
            g.c.drawRoundRect(RectF(x, cy + h / 2f - bh, x + bw, cy + h / 2f), 1f, 1f, fill(col))
        }
    }

    private fun chip(g: Gx, cx: Float, cy: Float) {
        g.c.drawCircle(cx, cy, 10f, fill(alpha(TXT, 0.12f)))
    }

    private fun windChip(g: Gx, cx: Float, cy: Float, fromDeg: Double, ink: Int) {
        val c = g.c
        chip(g, cx, cy)
        // La flèche indique où va le vent : à l'opposé d'où il vient.
        c.save()
        c.translate(cx, cy)
        c.rotate(((fromDeg + 180.0) % 360.0).toFloat())
        val s = 12f / 24f
        c.scale(s, s)
        c.translate(-12f, -12f)
        val p = stroke(ink, 2.4f)
        c.drawLine(12f, 19f, 12f, 5f, p)
        c.drawLine(5f, 12f, 12f, 5f, p)
        c.drawLine(19f, 12f, 12f, 5f, p)
        c.restore()
    }

    private fun trendChip(g: Gx, cx: Float, cy: Float, up: Boolean, ink: Int) {
        val c = g.c
        chip(g, cx, cy)
        c.save()
        c.translate(cx, cy)
        val s = 12f / 24f
        c.scale(s, if (up) s else -s)
        c.translate(-12f, -12f)
        val path = Path().apply {
            moveTo(22f, 7f); lineTo(13.5f, 15.5f); lineTo(8.5f, 10.5f); lineTo(2f, 17f)
            moveTo(16f, 7f); lineTo(22f, 7f); lineTo(22f, 13f)
        }
        c.drawPath(path, stroke(ink, 2.4f))
        c.restore()
    }

    private fun dropChip(g: Gx, cx: Float, cy: Float, humidity: Double) {
        val c = g.c
        c.save()
        c.translate(cx - 10f, cy - 10f)
        c.scale(20f / 24f, 20f / 24f)
        c.save()
        c.clipPath(dropPath)
        c.drawRect(0f, 0f, 24f, 24f, fill(alpha(TXT, 0.12f)))
        val level = (humidity / 100.0).coerceIn(0.0, 1.0).toFloat()
        c.drawRect(0f, 22f - 20f * level, 24f, 24f, fill(argb(0xFF4FB3EE)))
        c.restore()
        c.drawPath(dropPath, stroke(argb(0xFFBFE4F8), 1.6f))
        c.restore()
    }

    /** Échelle européenne de l'indice d'air : 5 bandes de 20, un repère sous la valeur. */
    private fun aqi(g: Gx, x: Float, y: Float, value: Int, ink: Int) {
        val c = g.c
        val seg = (98f - 4f * 2f) / 5f
        AQI.forEachIndexed { n, col ->
            val sx = x + n * (seg + 2f)
            c.drawRoundRect(RectF(sx, y, sx + seg, y + 6f), 3f, 3f, fill(col))
        }
        val mx = x + 98f * (value / 100f).coerceIn(0f, 1f)
        val tri = Path().apply {
            moveTo(mx, y + 7.5f); lineTo(mx - 4f, y + 13.5f); lineTo(mx + 4f, y + 13.5f); close()
        }
        c.drawPath(tri, fill(ink))
    }

    private fun shortTile(g: Gx, cell: BentoCell) {
        val c = g.c
        val f = g.f
        val s = cell.s
        val t = tint(cell.key, s)
        val rad = min(16f, cell.h * 0.3f)
        c.drawRoundRect(RectF(cell.x, cell.y, cell.x + cell.w, cell.y + cell.h), rad, rad, fill(if (s.tinted) mix(t.bg, argb(0xFF4A7392), 0.45f) else t.bg))
        val cx = cell.x + cell.w / 2f
        val text = if (s.unit.startsWith("°") || s.unit == "%") s.value + s.unit else s.value
        val withIcon = cell.h >= 40f
        val block = (if (withIcon) 14f + 3f else 0f) + 9f + 2f + 7f
        var y = cell.y + max(2f, (cell.h - block) / 2f)
        if (withIcon) {
            g.icon(s.icon, cx, y + 7f, 14f, t.ink)
            y += 17f
        }
        val vp = tp(f.bold, t.ink, 12.5f, Paint.Align.CENTER)
        fit(vp, text, cell.w - 6f, 9f)
        c.drawText(text, cx, y + 9f, vp)
        val lp = tp(f.regular, alpha(t.ink, 0.85f), 8.5f, Paint.Align.CENTER)
        fit(lp, s.short, cell.w - 4f, 7f)
        c.drawText(s.short, cx, y + 9f + 2f + 7f, lp)
    }
}
