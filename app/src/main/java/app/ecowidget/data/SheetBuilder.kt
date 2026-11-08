package app.ecowidget.data

import android.graphics.Paint
import app.ecowidget.R
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Construit les fiches détaillées (une par sujet) à l'image des maquettes « H ». */
object SheetBuilder {
    fun build(tile: Tile, i: DetailInputs): Sheet = when (tile) {
        Tile.TEMP -> SheetsA.temp(i)
        Tile.RAIN -> SheetsA.rain(i)
        Tile.SNOW -> SheetsA.snow(i)
        Tile.WIND -> SheetsB.wind(i)
        Tile.HUM -> SheetsB.hum(i)
        Tile.PRESS -> SheetsB.press(i)
        Tile.FORECAST -> SheetsC.forecast(i)
        Tile.SUN -> SheetsC.sun(i)
        Tile.AIR -> SheetsC.air(i)
    }
}

/** Outils communs aux fiches. */
internal object SH {
    const val H = 3600L
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val DAY_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)
    val DAY_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d", Locale.FRENCH)
    val DAY_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH)

    fun hhmm(i: DetailInputs, t: Long): String = HHMM.format(Instant.ofEpochSecond(t).atZone(i.zone))

    /** Heures écoulées depuis minuit (local). */
    fun hOf(i: DetailInputs, t: Long): Double = (t - DetailBuilder.midnight(i)) / 3600.0

    fun hourOf(i: DetailInputs, t: Long): Int = Instant.ofEpochSecond(t).atZone(i.zone).hour

    /** Début de l'heure courante. */
    fun hourStart(i: DetailInputs): Long =
        Instant.ofEpochSecond(i.nowSec).atZone(i.zone).truncatedTo(ChronoUnit.HOURS).toEpochSecond()

    fun obsTime(i: DetailInputs): String = hhmm(i, i.snap?.observedAtSec ?: i.nowSec)

    fun subStation(i: DetailInputs): String = "Station Ecowitt · mesuré à " + obsTime(i)

    /** Repères réguliers de `lo` à `hi` (exclus aux bords) par pas de `step`. */
    fun ticks(lo: Double, hi: Double, step: Double): List<Double> {
        val out = ArrayList<Double>()
        var v = ceil(lo / step) * step
        while (v < hi - 1e-9) {
            if (v > lo + 1e-9) out.add(v)
            v += step
        }
        return out
    }

    /** Abscisses 0, 6, 12… jusqu'à `xMax`. */
    fun sixHours(xMax: Double): List<Double> = (0..(xMax / 6.0).toInt()).map { it * 6.0 }

    fun dayLabel(i: DetailInputs, daySec: Long): String = DAY_SHORT.format(Instant.ofEpochSecond(daySec + 12 * H).atZone(i.zone))

    fun dayFull(i: DetailInputs, daySec: Long): String = DAY_FULL.format(Instant.ofEpochSecond(daySec + 12 * H).atZone(i.zone))

    /** Icône et teinte d'un code météo WMO. */
    fun codeIcon(code: Int?, day: Boolean = true): Pair<Int, Hue> = when (code) {
        0, 1 -> if (day) R.drawable.mk_sun to Hue.SUN else R.drawable.ic_wx_moon to Hue.MUTED
        2 -> R.drawable.mk_suncloud to Hue.TEAL
        56, 57, 66, 67, 77 -> R.drawable.mk_sleet to Hue.ICY
        71, 73, 75, 85, 86 -> R.drawable.mk_snowcloud to Hue.ICY
        51, 53, 55, 61, 63, 65, 80, 81, 82, 95, 96, 99 -> R.drawable.mk_rain to Hue.BLUE
        else -> R.drawable.mk_cloud to Hue.MUTED
    }

    /** 0 pluie, 1 pluie ou neige possible, 2 neige. */
    fun kind(t: Tone): Int = when (t) {
        Tone.SNOW -> 2
        Tone.ICE -> 1
        else -> 0
    }

    fun kindIcon(k: Int): Pair<Int, Hue> = when (k) {
        2 -> R.drawable.mk_snowcloud to Hue.ICY
        1 -> R.drawable.mk_sleet to Hue.ICY
        else -> R.drawable.mk_rain to Hue.BLUE
    }

    fun kindHue(k: Int): Hue = if (k == 0) Hue.BLUE else Hue.ICY

    fun cardinalWord(deg: Double): String = when (Metric.cardinal(deg)) {
        "N" -> "nord"
        "NNE" -> "nord-nord-est"
        "NE" -> "nord-est"
        "ENE" -> "est-nord-est"
        "E" -> "est"
        "ESE" -> "est-sud-est"
        "SE" -> "sud-est"
        "SSE" -> "sud-sud-est"
        "S" -> "sud"
        "SSO" -> "sud-sud-ouest"
        "SO" -> "sud-ouest"
        "OSO" -> "ouest-sud-ouest"
        "O" -> "ouest"
        "ONO" -> "ouest-nord-ouest"
        "NO" -> "nord-ouest"
        else -> "nord-nord-ouest"
    }

    /** Phrase « D'où vient le vent » : « Du sud-ouest », « De l'ouest ». */
    fun fromWord(deg: Double): String {
        val w = cardinalWord(deg)
        return if (w.startsWith("est") || w.startsWith("ouest")) "De l'$w" else "Du $w"
    }

    /** Heures rondes à venir, alignées sur les multiples de `step` (heure locale). */
    fun slots(i: DetailInputs, step: Int, count: Int): List<Long> {
        var z = Instant.ofEpochSecond(i.nowSec).atZone(i.zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        while (z.hour % step != 0) z = z.plusHours(1)
        return (0 until count).map { z.plusHours((step * it).toLong()).toEpochSecond() }
    }

    fun hm(i: DetailInputs, t: Long): String = DetailBuilder.hm(t, i.zone)

    /** « Auj. », « Lun. 9 ». */
    fun dayShort(i: DetailInputs, daySec: Long, today: Boolean): String {
        if (today) return "Auj."
        val t = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH).format(Instant.ofEpochSecond(daySec + 12 * H).atZone(i.zone))
        return t.replaceFirstChar { it.uppercase() }
    }

    val DASH4 = floatArrayOf(4f, 4f)
    val DASH3 = floatArrayOf(3f, 3f)
}

// ───────────────────────────── Température, pluie, neige ─────────────────────────────

internal object SheetsA {
    private val CTR = Paint.Align.CENTER
    private val RGT = Paint.Align.RIGHT

    fun temp(i: DetailInputs): Sheet {
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val f = i.forecast
        val mid = DetailBuilder.midnight(i)
        val nowH = (now - mid) / 3600.0
        val xMax = ceil((nowH + 12.0) / 6.0) * 6.0
        val today = d?.tempSeries.orEmpty().filter { it.tSec >= mid }
        val station = today.map { (it.tSec - mid) / 3600.0 to it.v }
        val ahead = DetailBuilder.hourly(i, "temperature_2m", now, mid + (xMax * 3600).toLong()).map { (it.first - mid) / 3600.0 to it.second }
        val fcLine = listOfNotNull(station.lastOrNull()) + ahead
        val blocks = ArrayList<Block>()

        // Héros
        val trend = d?.tempTrend1h
        val trendText = when {
            trend == null -> null
            abs(trend) < 0.2 -> "Stable sur 1 h"
            trend < 0 -> "En baisse : " + Fmt.signed(trend) + "° en 1 h"
            else -> "En hausse : " + Fmt.signed(trend) + "° en 1 h"
        }
        blocks.add(
            Hero(
                Fmt.deg(s?.outdoorTemp), "", Hue.TEAL,
                listOfNotNull(s?.feelsLike?.let { "Ressenti " + Fmt.deg(it) }, trendText),
            ),
        )

        // Pastille : température proche de 0 dans les 24 h
        val next24 = DetailBuilder.hourly(i, "temperature_2m", now, now + 24 * SH.H)
        val coldest = next24.minByOrNull { it.second }
        if (coldest != null && coldest.second <= 1.5) {
            val wet = DetailBuilder.hourly(i, "precipitation", coldest.first - 2 * SH.H, coldest.first + 2 * SH.H).any { it.second >= 0.1 }
            val base = (if (coldest.second <= 0.0) "Gel" else "Proche de 0°") + " vers " + DetailBuilder.hm(coldest.first, i.zone)
            val tail = if (wet) " : neige possible" else if (coldest.second <= 0.0) " : gel possible" else ""
            blocks.add(PillB(R.drawable.mk_snow, base + tail, Hue.ICY))
        }

        // Aujourd'hui
        val pMin = today.minByOrNull { it.v }
        val pMax = today.maxByOrNull { it.v }
        blocks.add(
            Card(
                "Aujourd'hui", "station",
                listOf(
                    StatGrid(
                        listOf(
                            Metric2("MIN", Fmt.deg(d?.dayMin), sub = pMin?.let { "à " + SH.hhmm(i, it.tSec) }, hue = Hue.COLD),
                            Metric2("MAX", Fmt.deg(d?.dayMax), sub = pMax?.let { "à " + SH.hhmm(i, it.tSec) }, hue = Hue.ORANGE),
                            Metric2(
                                "ÉCART",
                                if (d?.dayMin != null && d.dayMax != null) Fmt.deg(d.dayMax - d.dayMin) else "—",
                                sub = "sur la journée",
                            ),
                        ),
                        3,
                    ),
                ),
            ),
        )

        // Courbe depuis minuit
        val all = station + ahead
        if (all.size >= 2) {
            val lo = floor(min(all.minOf { it.second }, 0.0) - 1.5)
            val hi = ceil(all.maxOf { it.second } + 2.5)
            val step = if (hi - lo > 24) 10.0 else 5.0
            val zero = lo < 0.0 && hi > 0.0
            val applied = f?.altitude?.applied == true
            val chart = ChartMaker { w ->
                val p = Plot(w, 168f, 0.0, xMax, lo, hi, l = 28f, r = 6f, t = 16f, b = 20f)
                p.ygrid(SH.ticks(lo, hi, step)) { Fmt.fr(it, 0) + "°" }
                if (zero) p.hline(0.0, Hue.ORANGE, floatArrayOf(3f, 3f), opacity = 0.7f)
                p.area(station, Hue.TEAL, 0.14f, base = lo)
                p.line(station, Hue.TEAL, 2.4f)
                p.line(fcLine, Hue.TEAL, 2f, SH.DASH4)
                p.vline(nowH, Hue.MUTED, SH.hhmm(i, now))
                pMin?.let {
                    val x = (it.tSec - mid) / 3600.0
                    p.dot(x, it.v, Hue.COLD)
                    p.text(p.x(x), p.y(it.v) + 17f, Fmt.deg(it.v), Hue.COLD, 11f, CTR, true)
                }
                pMax?.let {
                    val x = (it.tSec - mid) / 3600.0
                    p.dot(x, it.v, Hue.ORANGE)
                    p.text(p.x(x), p.y(it.v) - 9f, Fmt.deg(it.v), Hue.ORANGE, 11f, CTR, true)
                }
                ahead.lastOrNull()?.let {
                    p.dot(it.first, it.second, Hue.ICY, 3f)
                    p.text(p.x(it.first) - 2f, p.y(it.second) - 9f, Fmt.deg(it.second), Hue.ICY, 11f, RGT, true)
                }
                p.xlabels(SH.sixHours(xMax), { v -> Fmt.hlab(v) })
                p.drawing()
            }
            val legend = ArrayList<Legend>()
            legend.add(Legend(LegendKind.LINE, Hue.TEAL, "Mesuré (station)"))
            legend.add(Legend(LegendKind.DASH, Hue.TEAL, if (applied) "Prévu, corrigé à " + (f?.altitude?.userM ?: 0.0).roundToInt() + " m" else "Prévu"))
            if (zero) legend.add(Legend(LegendKind.DASH, Hue.ORANGE, "0 °C"))
            val inner = ArrayList<Block>()
            inner.add(ChartB(chart))
            inner.add(LegendB(legend))
            if (f?.altitude != null && applied) {
                inner.add(
                    InfoChip(
                        R.drawable.mk_swap,
                        "Prévisions corrigées pour " + f.altitude.userM.roundToInt() + " m · modèle : " + f.altitude.modelM.roundToInt() + " m",
                        Hue.TEAL,
                    ),
                )
            }
            blocks.add(Card("Depuis minuit, puis 12 h prévues", null, inner))
        }

        // Prochaines heures (toutes les 2 h)
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
        val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        val codes = DetailBuilder.hourlyMap(i, "weather_code")
        var z = Instant.ofEpochSecond(now).atZone(i.zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        if (z.hour % 2 != 0) z = z.plusHours(1)
        val cells = ArrayList<Cell>()
        for (k in 0 until 6) {
            val t = z.plusHours(2L * k).toEpochSecond()
            val tp = temps[t] ?: continue
            val mm = prec[t + SH.H] ?: 0.0
            if (mm >= 0.1) {
                val kd = SH.kind(DetailBuilder.precipTone(i.altM, t + SH.H, temps, snowH, frz))
                val (ic, hue) = SH.kindIcon(kd)
                cells.add(Cell(Fmt.hlab(SH.hourOf(i, t).toDouble()), ic, hue, Fmt.deg(tp, 0), Fmt.fr(mm) + " mm", SH.kindHue(kd)))
            } else {
                val (ic, hue) = SH.codeIcon(codes[t]?.roundToInt(), true)
                cells.add(Cell(Fmt.hlab(SH.hourOf(i, t).toDouble()), ic, hue, Fmt.deg(tp, 0), "sec", Hue.MUTED))
            }
        }
        if (cells.size >= 3) blocks.add(Card("Prochaines heures", null, listOf(CellsB(cells))))

        // Demain
        val tmx = DetailBuilder.dayAt(DetailBuilder.daily(i, "temperature_2m_max"), i, 1)
        val tmn = DetailBuilder.dayAt(DetailBuilder.daily(i, "temperature_2m_min"), i, 1)
        if (tmx != null && tmn != null) {
            val code = DetailBuilder.dayAt(DetailBuilder.daily(i, "weather_code"), i, 1)?.roundToInt()
            val snow = DetailBuilder.dayAt(DetailBuilder.daily(i, "snowfall_sum"), i, 1) ?: 0.0
            val rain = DetailBuilder.dayAt(DetailBuilder.daily(i, "precipitation_sum"), i, 1) ?: 0.0
            var text = if (code != null) WeatherCodes.label(code) else "Prévision"
            var icon = SH.codeIcon(code)
            if (snow >= 0.5) {
                text += ", neige " + Fmt.fr(snow, 0) + " cm"
                icon = SH.kindIcon(2)
            } else if (rain >= 1.0) {
                text += ", pluie " + Fmt.fr(rain, 0) + " mm"
                icon = SH.kindIcon(0)
            }
            blocks.add(
                Card(
                    "Demain · " + SH.dayFull(i, mid + 24 * SH.H), null,
                    listOf(LineRow(icon.first, icon.second, text, Fmt.deg(tmx, 0) + " / " + Fmt.deg(tmn, 0))),
                ),
            )
        }
        return Sheet(Tile.TEMP, "Température extérieure", SH.subStation(i), R.drawable.mk_thermo, Hue.TEAL, blocks)
    }

    // ───────────────────────────── Pluie et neige ─────────────────────────────

    private fun rainPill(i: DetailInputs): PillB? {
        val f = i.forecast
        if (i.snap?.isRaining == true) {
            val end = f?.rainEndMin
            return PillB(R.drawable.mk_rain, if (end != null) "Il pleut · fin dans " + OmFormat.delay(end) else "Il pleut", Hue.BLUE)
        }
        val ep = DetailBuilder.episode(i) ?: return null
        val first = ep.first ?: return PillB(R.drawable.mk_check, "Pas de pluie prévue sur 24 h", Hue.GREEN)
        val nextMin = f?.nextRainMin
        val start = if (nextMin != null && nextMin in 1..120) i.nowSec + nextMin * 60L else first
        val prec = DetailBuilder.hourly(i, "precipitation", start, start + 3 * SH.H)
        val peak = prec.maxOfOrNull { it.second } ?: 0.0
        val word = when {
            peak < 1.0 -> "faible"
            peak < 4.0 -> "modérée"
            else -> "forte"
        }
        val day = if (DetailBuilder.sameDay(start, i.nowSec, i.zone)) "" else "demain "
        var text = if (ep.firstIsSnow) "Neige dès $day" else "Pluie $word dès $day"
        text += DetailBuilder.hm(max(start, i.nowSec), i.zone)
        val sn = ep.firstSnow
        if (!ep.firstIsSnow && sn != null) {
            text += ", neige possible vers " + (if (DetailBuilder.sameDay(sn, i.nowSec, i.zone)) "" else "demain ") + DetailBuilder.hm(sn, i.zone)
        }
        return PillB(R.drawable.mk_rain, text, Hue.BLUE)
    }

    fun rain(i: DetailInputs): Sheet {
        val now = i.nowSec
        val s = i.snap
        val f = i.forecast
        val d = i.derived
        val mid = DetailBuilder.midnight(i)
        val nowH = (now - mid) / 3600.0
        val blocks = ArrayList<Block>()

        rainPill(i)?.let { blocks.add(it) }

        // Quatre cartes de cumuls
        val today = RainOutlook.today(f?.raw, i.zone)
        val dayPrec = DetailBuilder.daily(i, "precipitation_sum")
        val daySnow = DetailBuilder.daily(i, "snowfall_sum")
        val todayIdx = dayPrec.indexOfLast { it.first <= now }
        val tomMm = DetailBuilder.dayAt(dayPrec, i, 1)
        val tomSnow = DetailBuilder.dayAt(daySnow, i, 1)
        val weekIdx = if (todayIdx >= 0) (todayIdx + 1)..minOf(todayIdx + 7, dayPrec.size - 1) else IntRange.EMPTY
        val weekMm = weekIdx.sumOf { dayPrec[it].second ?: 0.0 }
        val minis = ArrayList<Metric2>()
        minis.add(Metric2("TOMBÉ AUJOURD'HUI", s?.rainDaily?.let { Fmt.fr(it) } ?: "—", "mm", "Station, depuis minuit", Hue.TXT, 22))
        minis.add(Metric2("ENCORE PRÉVU", today?.let { Fmt.fr(it.totalMm) } ?: "—", "mm", "D'ici minuit", Hue.BLUE, 22))
        minis.add(
            Metric2(
                "DEMAIN", tomMm?.let { Fmt.fr(it) } ?: "—", "mm",
                if (tomSnow != null && tomSnow >= 0.5) "Dont ≈ " + Fmt.fr(tomSnow, 0) + " cm de neige" else "Pas de neige",
                Hue.BLUE, 22,
            ),
        )
        minis.add(
            Metric2(
                "7 PROCHAINS JOURS", if (weekIdx.isEmpty()) "—" else Fmt.fr(weekMm), "mm",
                if (weekIdx.isEmpty()) null else SH.DAY_NAME.format(Instant.ofEpochSecond(dayPrec[weekIdx.first].first + 12 * SH.H).atZone(i.zone)).replaceFirstChar { it.uppercase() } +
                    " → " + SH.DAY_NAME.format(Instant.ofEpochSecond(dayPrec[weekIdx.last].first + 12 * SH.H).atZone(i.zone)),
                Hue.BLUE, 22,
            ),
        )
        blocks.add(MiniCards(minis))

        // Cumul depuis minuit
        val cum = d?.rainDaySeries.orEmpty().filter { it.tSec >= mid }
        if (cum.size >= 2 && nowH >= 2.0) {
            val pts = cum.map { (it.tSec - mid) / 3600.0 to it.v }
            val ymax = max(2.0, ceil(pts.maxOf { it.second }))
            var best = 0.0
            var bestT = 0L
            for (k in 1 until cum.size) {
                var j = k - 1
                while (j > 0 && cum[k].tSec - cum[j - 1].tSec <= 900) j--
                val dt = cum[k].tSec - cum[j].tSec
                if (dt >= 300) {
                    val rate = (cum[k].v - cum[j].v) / (dt / 3600.0)
                    if (rate > best) {
                        best = rate
                        bestT = cum[k].tSec
                    }
                }
            }
            val right = if (best > 0.0) "max " + Fmt.fr(best) + " mm/h à " + DetailBuilder.hm(bestT, i.zone) else null
            val chart = ChartMaker { w ->
                val p = Plot(w, 80f, 0.0, nowH, 0.0, ymax, l = 30f, r = 4f, t = 8f, b = 18f)
                p.ygrid(listOf(0.0, ymax / 2.0, ymax)) { v -> if (v >= ymax) Fmt.fr(v, 0) + " mm" else Fmt.fr(v, if (v == Math.rint(v)) 0 else 1) }
                p.area(pts, Hue.BLUE, 0.2f)
                p.line(pts, Hue.BLUE, 2.2f)
                val ticks = SH.sixHours(nowH).filter { it < nowH - 2.0 } + nowH
                p.xlabels(ticks, { v -> if (v == nowH) SH.hhmm(i, now) else Fmt.hlab(v) })
                p.drawing()
            }
            blocks.add(Card("Depuis minuit · station", right, listOf(ChartB(chart))))
        }

        // Heure par heure
        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        if (prec.isNotEmpty()) {
            val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
            val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
            val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
            val h0 = SH.hourStart(i)
            val bars = (0 until 16).map { k ->
                val ts = h0 + k * SH.H
                val mm = prec[ts + SH.H] ?: 0.0
                val kd = SH.kind(DetailBuilder.precipTone(i.altM, ts + SH.H, temps, snowH, frz))
                val lim = snowH[ts] ?: frz[ts]?.let { it - 300.0 }
                RainBar(mm, kd, lim?.let { max(0.0, it) }, Fmt.hlab(SH.hourOf(i, ts).toDouble()))
            }
            val ymax = max(2.0, ceil(bars.maxOf { it.mm }))
            val altTxt = i.altM?.let { "chez toi (" + it.roundToInt() + " m)" } ?: "chez toi"
            blocks.add(
                Card(
                    "Prochaines heures · heure par heure", "mm",
                    listOf(
                        ChartB(SheetCharts.rainHours(bars, ymax)),
                        LegendB(
                            listOf(
                                Legend(LegendKind.BOX, Hue.BLUE, "Pluie"),
                                Legend(LegendKind.HATCH, Hue.ICY, "Pluie ou neige possible"),
                                Legend(LegendKind.SNOW, Hue.SNOW, "Neige qui tient"),
                            ),
                        ),
                        NoteB(
                            "Pluie ou neige possible : limite pluie-neige à moins de 300 m au-dessus de $altTxt. " +
                                "Neige qui tient : limite sous " + (i.altM?.roundToInt()?.toString() ?: "ton altitude") + " m et température ≤ 1 °C. " +
                                "« lim. » : limite pluie-neige en mètres.",
                        ),
                    ),
                ),
            )
        }

        // 60 minutes
        if (f != null && f.hasNowcast) {
            val bins = DetailBuilder.rainBins(f, s?.rainRate)
            blocks.add(
                Card(
                    "60 prochaines minutes · " + f.nowcastSource, "pas de 5 min",
                    listOf(ChartB(SheetCharts.nowcast(bins, "maint.", "+30 min", "+1 h"))),
                ),
            )
        }

        // 7 jours
        if (!weekIdx.isEmpty()) {
            val days = weekIdx.map { k ->
                val mm = dayPrec[k].second ?: 0.0
                val cm = daySnow.getOrNull(k)?.second ?: 0.0
                // Part de neige : 1 cm de neige ≈ 1 mm d'eau pour 10 cm.
                WeekDay(SH.dayLabel(i, dayPrec[k].first), mm, min(mm, cm / 10.0).let { if (it >= 0.3) it else 0.0 })
            }
            val ymax = max(5.0, ceil(days.maxOf { it.total }))
            blocks.add(Card("7 prochains jours", Fmt.fr(weekMm) + " mm", listOf(ChartB(SheetCharts.week(days, ymax)))))
        }
        return Sheet(Tile.RAIN, "Pluie et neige", "Station + Open-Meteo · " + SH.hhmm(i, now), R.drawable.mk_rain, Hue.BLUE, blocks)
    }

    // ───────────────────────────── Limite pluie / neige ─────────────────────────────

    fun snow(i: DetailInputs): Sheet {
        val now = i.nowSec
        val alt = i.altM
        val mid = DetailBuilder.midnight(i)
        val blocks = ArrayList<Block>()
        val lim = DetailBuilder.limitSeries(i, 24).filter { it.first >= now - 1800 }
        if (lim.isEmpty()) {
            blocks.add(NoteB("Limite pluie / neige indisponible : les prévisions n'ont pas encore été reçues pour ta position."))
            return Sheet(Tile.SNOW, "Limite pluie / neige", "Open-Meteo · " + SH.hhmm(i, now), R.drawable.mk_mountain, Hue.ICY, blocks)
        }
        val limNow = lim.first().second
        val future = lim.filter { it.first > now }
        val lowest = future.minByOrNull { it.second }
        val highest = future.maxByOrNull { it.second }
        val line2 = when {
            lowest != null && lowest.second < limNow - 100 -> "Descend à " + Fmt.thousands(DetailBuilder.hundreds(lowest.second).toDouble()) + " m vers " + DetailBuilder.hm(lowest.first, i.zone)
            highest != null && highest.second > limNow + 100 -> "Monte à " + Fmt.thousands(DetailBuilder.hundreds(highest.second).toDouble()) + " m vers " + DetailBuilder.hm(highest.first, i.zone)
            else -> "Stable sur 24 h"
        }
        blocks.add(Hero(Fmt.thousands(DetailBuilder.hundreds(limNow).toDouble()), "m", Hue.ICY, listOf("Limite pluie / neige maintenant", line2)))

        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
        val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        val h0 = SH.hourStart(i)

        // Pastille : quand la neige descend jusqu'à chez toi
        if (alt != null) {
            val low = future.filter { it.second <= alt }
            val altTxt = alt.roundToInt().toString()
            if (low.isEmpty()) {
                blocks.add(PillB(R.drawable.mk_check, "La limite reste au-dessus de chez toi ($altTxt m) sur 24 h", Hue.GREEN))
            } else {
                val from = low.first().first
                val to = low.last().first + SH.H
                val wet = low.any { (prec[it.first + SH.H] ?: 0.0) >= 0.1 }
                val range = " de " + DetailBuilder.hm(from, i.zone) + " à " + DetailBuilder.hm(to, i.zone)
                blocks.add(
                    PillB(
                        R.drawable.mk_snow,
                        if (wet) "Neige jusqu'à chez toi ($altTxt m)$range" else "La limite passe sous chez toi ($altTxt m)$range, sans précipitation prévue",
                        Hue.ICY,
                    ),
                )
            }
        }

        // Courbe 24 h
        val x0 = SH.hOf(i, h0)
        val x1 = x0 + 24.0
        val limPts = lim.map { SH.hOf(i, it.first) to it.second }
        val iso = DetailBuilder.hourly(i, "freezing_level_height", h0, h0 + 24 * SH.H).map { SH.hOf(i, it.first) to it.second }
        val top = max(max(limPts.maxOf { it.second }, iso.maxOfOrNull { it.second } ?: 0.0), alt ?: 0.0)
        val ymax = max(1000.0, ceil(top / 1000.0) * 1000.0)
        val wetHours = (0 until 24).filter { (prec[h0 + (it + 1) * SH.H] ?: 0.0) >= 0.1 }
        val chart = ChartMaker { w ->
            val p = Plot(w, 206f, x0, x1, 0.0, ymax, l = 38f, r = 4f, t = 14f, b = 32f)
            if (wetHours.isNotEmpty()) {
                val xa = p.x(x0 + wetHours.first())
                val xb = p.x(x0 + wetHours.last() + 1.0)
                p.raw { c ->
                    c.drawRect(xa, p.t, xb, p.bottom, Gfx.fill(Hue.BLUE, 0.07f))
                }
                p.text(xa + 4f, p.t + 11f, "précipitations", Hue.BLUE, 9f)
            }
            p.ygrid(SH.ticks(0.0, ymax, 1000.0) + ymax) { v -> Fmt.thousands(v) }
            p.ygrid(listOf(0.0)) { v -> Fmt.thousands(v) }
            if (iso.size >= 2) p.line(iso, Hue.ORANGE, 1.6f, floatArrayOf(4f, 3f))
            if (alt != null) {
                p.hline(alt, Hue.TEAL, floatArrayOf(5f, 3f), 1.4f)
                p.text(p.x(x1) - 2f, p.y(alt) - 6f, "chez toi · " + alt.roundToInt() + " m", Hue.TEAL, 10f, RGT, true)
            }
            p.line(limPts, Hue.ICY, 2.6f)
            val lowPt = limPts.drop(1).minByOrNull { it.second }
            if (lowPt != null && lowPt.second < limNow - 100) {
                p.dot(lowPt.first, lowPt.second, Hue.ICY, 4f)
                val label = Fmt.thousands(lowPt.second) + " m vers " + DetailBuilder.hm(mid + (lowPt.first * 3600).toLong(), i.zone)
                if (lowPt.first > x0 + 14) p.text(p.x(lowPt.first) - 7f, p.y(lowPt.second) + 15f, label, Hue.ICY, 10f, RGT, true)
                else p.text(p.x(lowPt.first) + 7f, p.y(lowPt.second) + 15f, label, Hue.ICY, 10f, Paint.Align.LEFT, true)
            }
            // Ce qui tombe chez toi, heure par heure
            val y0 = p.bottom + 5f
            for (k in 0 until 24) {
                val ts = h0 + k * SH.H
                val mm = prec[ts + SH.H] ?: 0.0
                if (mm < 0.1) continue
                val kd = SH.kind(DetailBuilder.precipTone(i.altM, ts + SH.H, temps, snowH, frz))
                val xa = p.x(x0 + k)
                val xb = p.x(x0 + k + 1.0)
                p.raw { c ->
                    when (kd) {
                        0 -> c.drawRoundRect(xa, y0, xb - 1f, y0 + 7f, 2f, 2f, Gfx.fill(Hue.BLUE))
                        1 -> Gfx.hatchRect(c, xa, y0, xb - xa - 1f, 7f, 2f)
                        else -> c.drawRoundRect(xa, y0, xb - 1f, y0 + 7f, 2f, 2f, Gfx.fill(Hue.SNOW))
                    }
                }
            }
            val ticks = SH.ticks(x0, x1, 4.0)
            p.xlabels(ticks, { v -> Fmt.hlab(v) })
            p.drawing()
        }
        val legend = ArrayList<Legend>()
        legend.add(Legend(LegendKind.LINE, Hue.ICY, "Limite pluie / neige"))
        if (iso.size >= 2) legend.add(Legend(LegendKind.DASH, Hue.ORANGE, "Isotherme 0 °C"))
        if (alt != null) legend.add(Legend(LegendKind.DASH, Hue.TEAL, "Ton altitude"))
        legend.add(Legend(LegendKind.BOX, Hue.BLUE, "Pluie chez toi"))
        legend.add(Legend(LegendKind.HATCH, Hue.ICY, "Pluie ou neige"))
        legend.add(Legend(LegendKind.SNOW, Hue.SNOW, "Neige qui tient"))
        blocks.add(Card("Prochaines 24 h", "altitude en m", listOf(ChartB(chart), LegendB(legend))))
        blocks.add(
            Card(
                "Comment c'est calculé", null,
                listOf(
                    NoteB(
                        "Limite pluie / neige : altitude où la neige fond en pluie (thermomètre mouillé au-dessus de 1,3 °C), " +
                            "modèles DWD / MétéoSuisse via Open-Meteo. La bande sous le graphique dit ce qui tombe chez toi, heure par heure.",
                    ),
                ),
            ),
        )
        return Sheet(Tile.SNOW, "Limite pluie / neige", "Open-Meteo · " + SH.hhmm(i, now), R.drawable.mk_mountain, Hue.ICY, blocks)
    }
}

// ───────────────────────────── Vent, humidité, pression ─────────────────────────────

internal object SheetsB {
    private val CTR = Paint.Align.CENTER

    /** Échelle de Beaufort à partir de la vitesse moyenne (km/h). */
    fun beaufort(kmh: Double): Pair<Int, String> = when {
        kmh < 1 -> 0 to "calme"
        kmh < 6 -> 1 to "très légère brise"
        kmh < 12 -> 2 to "légère brise"
        kmh < 20 -> 3 to "petite brise"
        kmh < 29 -> 4 to "jolie brise"
        kmh < 39 -> 5 to "bonne brise"
        kmh < 50 -> 6 to "vent frais"
        kmh < 62 -> 7 to "grand frais"
        kmh < 75 -> 8 to "coup de vent"
        kmh < 89 -> 9 to "fort coup de vent"
        kmh < 103 -> 10 to "tempête"
        kmh < 118 -> 11 to "violente tempête"
        else -> 12 to "ouragan"
    }

    // ── Vent ──

    fun wind(i: DetailInputs): Sheet {
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
        val dirs = DetailBuilder.hourlyMap(i, "wind_direction_10m")
        val blocks = ArrayList<Block>()

        blocks.add(
            Hero(
                Fmt.fr(s?.windSpeed ?: Double.NaN, 0).let { if (s?.windSpeed == null) "—" else it }, "km/h", Hue.LAV,
                listOfNotNull(
                    s?.windGust?.let { "Rafales " + Fmt.fr(it, 0) + " km/h" },
                    s?.windDir?.let { SH.fromWord(it) },
                ),
                compassFrom = s?.windDir,
            ),
        )

        // Pastille : plus forte rafale des 12 prochaines heures
        val peak = DetailBuilder.hourly(i, "wind_gusts_10m", now, now + 12 * SH.H).maxByOrNull { it.second }
        if (peak != null) {
            blocks.add(PillB(R.drawable.mk_wind, "Rafales jusqu'à " + Fmt.fr(peak.second, 0) + " km/h vers " + SH.hm(i, peak.first), Hue.LAV))
        }

        // Aujourd'hui (station)
        val gMax = gs.maxByOrNull { it.v }
        val wMax = ws.maxByOrNull { it.v }
        val bft = s?.windSpeed?.let { beaufort(it) }
        blocks.add(
            Card(
                "Aujourd'hui", "station",
                listOf(
                    StatGrid(
                        listOf(
                            Metric2("RAFALE MAX", gMax?.let { Fmt.fr(it.v, 0) } ?: "—", "km/h", gMax?.let { "à " + SH.hhmm(i, it.tSec) }, Hue.ORANGE),
                            Metric2("VENT MOYEN MAX", wMax?.let { Fmt.fr(it.v, 0) } ?: "—", "km/h", wMax?.let { "à " + SH.hhmm(i, it.tSec) }),
                            Metric2("FORCE", bft?.first?.toString() ?: "—", "Bft", bft?.second),
                        ),
                        3,
                    ),
                ),
            ),
        )

        // Courbe : moyen + rafales, mesurés puis prévus, avec la direction en haut
        if (mSpd.size + fSpd.size >= 3) {
            val topG = (mGst.map { it.second } + fGst.map { it.second } + mSpd.map { it.second } + fSpd.map { it.second }).maxOrNull() ?: 20.0
            val ymax = max(30.0, ceil((topG + 2.0) / 10.0) * 10.0)
            val step = if (ymax >= 40.0) 20.0 else 10.0
            val yt = listOf(0.0) + SH.ticks(0.0, ymax, step)
            val fPeak = fGst.drop(if (mGst.isEmpty()) 0 else 1).maxByOrNull { it.second }
            val chart = ChartMaker { w ->
                val p = Plot(w, 186f, 0.0, xMax, 0.0, ymax, l = 28f, r = 6f, t = 34f, b = 20f)
                p.ygrid(yt) { Fmt.fr(it, 0) }
                p.band(mGst, mSpd, Hue.LAV, 0.12f)
                p.line(mGst, Hue.LAV, 1.4f, null, 0.6f)
                p.line(mSpd, Hue.LAV, 2.4f)
                p.band(fGst, fSpd, Hue.LAV, 0.07f)
                p.line(fGst, Hue.LAV, 1.3f, SH.DASH3, 0.6f)
                p.line(fSpd, Hue.LAV, 2f, SH.DASH4)
                p.vline(nowH)
                gMax?.let {
                    val x = SH.hOf(i, it.tSec)
                    p.dot(x, it.v, Hue.ORANGE)
                    p.text(p.x(x), p.y(it.v) - 8f, Fmt.fr(it.v, 0), Hue.ORANGE, 11f, CTR, true)
                }
                if (fPeak != null && fPeak.second > (gMax?.v ?: 0.0) * 0.9) {
                    p.dot(fPeak.first, fPeak.second, Hue.ORANGE, 3f)
                    p.text(p.x(fPeak.first), p.y(fPeak.second) - 8f, Fmt.fr(fPeak.second, 0), Hue.ORANGE, 11f, CTR, true)
                }
                if (dirs.isNotEmpty()) {
                    var hr = 0
                    while (hr <= xMax) {
                        val frm = dirs[mid + hr * 3600L]
                        if (frm != null) {
                            val ax = p.x(hr.toDouble())
                            val to = ((frm + 180.0) % 360.0).toFloat()
                            val hue = if (hr < nowH) Hue.MUTED else Hue.LAV
                            p.raw { c -> Gfx.arrow(c, ax, 9f, to, 12f, hue, 1.6f) }
                        }
                        hr += 3
                    }
                }
                p.xlabels(SH.sixHours(xMax), { v -> Fmt.hlab(v) })
                p.drawing()
            }
            blocks.add(
                Card(
                    "Depuis minuit, puis 12 h prévues", "km/h",
                    listOf(
                        ChartB(chart),
                        LegendB(listOf(Legend(LegendKind.LINE, Hue.LAV, "Vent moyen"), Legend(LegendKind.BAND, Hue.LAV, "Rafales"), Legend(LegendKind.DASH, Hue.LAV, "Prévu"))),
                    ),
                ),
            )
        }

        // Prochaines heures
        val sp = DetailBuilder.hourlyMap(i, "wind_speed_10m")
        val gu = DetailBuilder.hourlyMap(i, "wind_gusts_10m")
        val cells = SH.slots(i, 2, 6).mapNotNull { t ->
            val v = sp[t] ?: return@mapNotNull null
            val g = gu[t]
            val from = dirs[t]
            Cell(Fmt.hlab(SH.hourOf(i, t).toDouble()), R.drawable.mk_up, Hue.LAV, Fmt.fr(v, 0), if (g != null) "raf. " + Fmt.fr(g, 0) else "", Hue.MUTED, if (from != null) ((from + 180.0) % 360.0).toFloat() else 0f)
        }
        if (cells.size >= 3) blocks.add(Card("Prochaines heures", "km/h", listOf(CellsB(cells))))
        return Sheet(Tile.WIND, "Vent", SH.subStation(i), R.drawable.mk_wind, Hue.LAV, blocks)
    }

    // ── Humidité et point de rosée ──

    private fun humWord(h: Double): String = when {
        h >= 95 -> "air saturé"
        h >= 80 -> "air très humide"
        h >= 65 -> "air humide"
        h >= 45 -> "air confortable"
        h >= 30 -> "air sec"
        else -> "air très sec"
    }

    fun hum(i: DetailInputs): Sheet {
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
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val dews = DetailBuilder.hourlyMap(i, "dew_point_2m")
        val blocks = ArrayList<Block>()

        val spread = if (s?.outdoorTemp != null && s.dewPoint != null) s.outdoorTemp - s.dewPoint else null
        blocks.add(
            Hero(
                s?.humidity?.let { Fmt.fr(it, 0) } ?: "—", "%", Hue.HUM,
                listOfNotNull(
                    s?.dewPoint?.let { "Point de rosée " + Fmt.deg(it) },
                    if (spread != null) "Écart " + Fmt.deg(spread) + (s?.humidity?.let { " · " + humWord(it) } ?: "") else null,
                ),
            ),
        )

        // Air saturé dans les 12 h ? (écart température - rosée ≤ 1°)
        val sat = (0..12).mapNotNull { k ->
            val t = SH.hourStart(i) + (k + 1) * SH.H
            val tp = temps[t]
            val dw = dews[t]
            if (tp != null && dw != null && tp - dw <= 1.0 && t <= now + 12 * SH.H) t to tp else null
        }
        val nowSat = spread != null && spread <= 1.0
        if (nowSat || sat.isNotEmpty()) {
            val frost = sat.any { it.second <= 1.0 } || (s?.outdoorTemp ?: 99.0) <= 1.0
            val whenTxt = if (nowSat) "" else " vers " + SH.hm(i, sat.first().first)
            blocks.add(PillB(R.drawable.mk_drop, "Air saturé$whenTxt : brouillard" + (if (frost) " ou givre" else "") + " possible", Hue.HUM))
        } else if (temps.isNotEmpty() && dews.isNotEmpty()) {
            blocks.add(PillB(R.drawable.mk_check, "Pas de brouillard attendu dans les 12 prochaines heures", Hue.GREEN))
        }

        // Aujourd'hui (station)
        val hMin = hs.minByOrNull { it.v }
        val hMax = hs.maxByOrNull { it.v }
        blocks.add(
            Card(
                "Aujourd'hui", "station",
                listOf(
                    StatGrid(
                        listOf(
                            Metric2("MIN", hMin?.let { Fmt.fr(it.v, 0) } ?: "—", "%", hMin?.let { "à " + SH.hhmm(i, it.tSec) }, Hue.ORANGE),
                            Metric2("MAX", hMax?.let { Fmt.fr(it.v, 0) } ?: "—", "%", hMax?.let { "à " + SH.hhmm(i, it.tSec) }, Hue.HUM),
                            Metric2(
                                "INTÉRIEUR", s?.indoorHumidity?.let { Fmt.fr(it, 0) } ?: "—", "%",
                                s?.indoorTemp?.let { Fmt.deg(it) + " · console" },
                            ),
                        ),
                        3,
                    ),
                ),
            ),
        )

        // Humidité depuis minuit
        if (meas.size + ahead.size >= 3) {
            val all = meas + ahead
            val lo = max(0.0, min(40.0, floor((all.minOf { it.second } - 5.0) / 10.0) * 10.0))
            val chart = ChartMaker { w ->
                val p = Plot(w, 156f, 0.0, xMax, lo, 100.0, l = 36f, r = 6f, t = 16f, b = 20f)
                p.ygrid(SH.ticks(lo, 100.5, 25.0)) { Fmt.fr(it, 0) + " %" }
                p.area(meas, Hue.HUM, 0.14f, base = lo)
                p.line(meas, Hue.HUM, 2.4f)
                p.line(fcLine, Hue.HUM, 2f, SH.DASH4)
                p.vline(nowH, Hue.MUTED, SH.hhmm(i, now))
                hMin?.let {
                    val x = SH.hOf(i, it.tSec)
                    p.dot(x, it.v, Hue.ORANGE)
                    p.text(p.x(x), p.y(it.v) + 17f, Fmt.fr(it.v, 0) + " %", Hue.ORANGE, 11f, CTR, true)
                }
                hMax?.let {
                    val x = SH.hOf(i, it.tSec)
                    p.dot(x, it.v, Hue.HUM)
                    p.text(p.x(x), p.y(it.v) - 9f, Fmt.fr(it.v, 0) + " %", Hue.HUM, 11f, CTR, true)
                }
                p.xlabels(SH.sixHours(xMax), { v -> Fmt.hlab(v) })
                p.drawing()
            }
            blocks.add(
                Card(
                    "Depuis minuit, puis 12 h prévues", null,
                    listOf(ChartB(chart), LegendB(listOf(Legend(LegendKind.LINE, Hue.HUM, "Mesurée"), Legend(LegendKind.DASH, Hue.HUM, "Prévue")))),
                ),
            )
        }

        // Température et point de rosée prévus
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
        if (tPts.size >= 3) {
            val lo = floor(min(dPts.minOf { it.second }, tPts.minOf { it.second }) - 1.0)
            val hi = ceil(max(dPts.maxOf { it.second }, tPts.maxOf { it.second }) + 1.0)
            val step = if (hi - lo <= 8.0) 2.0 else if (hi - lo <= 16.0) 5.0 else 10.0
            val fog = tPts.indices.filter { tPts[it].second - dPts[it].second <= 1.0 }
            val fogFrost = fog.any { tPts[it].second <= 1.0 }
            val chart = ChartMaker { w ->
                val p = Plot(w, 150f, nowH, x1, lo, hi, l = 28f, r = 6f, t = 18f, b = 20f)
                if (fog.isNotEmpty()) {
                    val xa = p.x(tPts[fog.first()].first)
                    val xb = max(p.x(tPts[fog.last()].first), xa + 8f)
                    p.raw { c -> c.drawRect(xa, p.t, min(xb, p.w - p.r), p.bottom, Gfx.fill(Hue.HUM, 0.10f)) }
                    p.text((xa + min(xb, p.w - p.r)) / 2f, p.t - 6f, if (fogFrost) "brouillard, givre" else "brouillard", Hue.HUM, 10f, CTR, true)
                }
                p.ygrid(SH.ticks(lo, hi, step)) { Fmt.fr(it, 0) + "°" }
                p.band(tPts, dPts, Hue.HUM, 0.12f)
                p.line(tPts, Hue.TEAL, 2f, SH.DASH4)
                p.line(dPts, Hue.HUM, 2f, SH.DASH4)
                p.xlabels(SH.ticks(nowH, x1, 3.0), { v -> Fmt.hlab(v) })
                p.drawing()
            }
            blocks.add(
                Card(
                    "Température et point de rosée", "prochaines heures",
                    listOf(
                        ChartB(chart),
                        LegendB(listOf(Legend(LegendKind.DASH, Hue.TEAL, "Température"), Legend(LegendKind.DASH, Hue.HUM, "Point de rosée"))),
                        NoteB("Quand l'écart passe sous 1°, l'air est saturé : brouillard, et givre si la température approche 0°."),
                    ),
                ),
            )
        }
        return Sheet(Tile.HUM, "Humidité et rosée", SH.subStation(i), R.drawable.mk_drop, Hue.HUM, blocks)
    }

    // ── Pression ──

    fun press(i: DetailInputs): Sheet {
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val blocks = ArrayList<Block>()
        val trend = d?.pressTrend3h
        val kind = when {
            trend == null -> null
            trend < -3.0 -> 0
            trend < -1.0 -> 1
            trend <= 1.0 -> 2
            trend <= 3.0 -> 3
            else -> 4
        }
        val kindWord = kind?.let { listOf("Baisse rapide", "Baisse", "Stable", "Hausse", "Hausse rapide")[it] }

        // Mesure de la station sur les dernières 24 h, prévision recalée sur la mesure d'aujourd'hui
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

        blocks.add(
            Hero(
                cur?.let { Fmt.fr(it, 0) } ?: "—", "hPa", Hue.PRES,
                listOfNotNull(trend?.let { Fmt.signed(it) + " hPa en 3 h" }, kindWord),
            ),
        )
        val fcMin = fc.drop(1).minByOrNull { it.second }
        val pillText = when {
            kind != null && kind <= 1 -> "Perturbation en approche : le temps se dégrade"
            kind != null && kind >= 3 -> "Pression en hausse : le temps s'améliore"
            fcMin != null && cur != null && cur - fcMin.second >= 3.0 -> "Baisse attendue d'ici " + SH.hm(i, now + (fcMin.first * 3600).toLong()) + " : le temps peut se dégrader"
            kind != null -> "Pression stable : pas de changement marqué"
            else -> null
        }
        if (pillText != null) blocks.add(PillB(R.drawable.mk_gauge, pillText, Hue.PRES))

        // Courbe -24 h … +24 h
        if (meas.size + fc.size >= 4) {
            val vals = meas.map { it.second } + fc.map { it.second }
            val lo = floor((vals.min() - 3.0) / 5.0) * 5.0
            val hi = ceil((vals.max() + 3.0) / 5.0) * 5.0
            val step = if (hi - lo > 40.0) 10.0 else 5.0
            val chart = ChartMaker { w ->
                val p = Plot(w, 176f, -24.0, 24.0, lo, hi, l = 36f, r = 6f, t = 16f, b = 20f)
                p.ygrid(SH.ticks(lo, hi, step)) { Fmt.fr(it, 0) }
                p.area(meas, Hue.PRES, 0.12f, base = lo)
                p.line(meas, Hue.PRES, 2.4f)
                p.line(fc, Hue.PRES, 2f, SH.DASH4)
                p.vline(0.0, Hue.MUTED, SH.hhmm(i, now))
                if (fcMin != null) {
                    p.dot(fcMin.first, fcMin.second, Hue.ORANGE)
                    p.text(p.x(fcMin.first), p.y(fcMin.second) + 17f, Fmt.fr(fcMin.second, 0) + " vers " + SH.hm(i, now + (fcMin.first * 3600).toLong()), Hue.ORANGE, 11f, CTR, true)
                }
                p.xlabels(listOf(-24.0, -12.0, 0.0, 12.0, 24.0), { v ->
                    val hr = Fmt.hlab(SH.hourOf(i, now + (v * 3600).toLong()).toDouble())
                    when (v.toInt()) {
                        -24 -> "hier $hr"
                        0 -> ""
                        24 -> "dem. $hr"
                        else -> hr
                    }
                })
                p.drawing()
            }
            blocks.add(
                Card(
                    "Hier, aujourd'hui, demain", "hPa",
                    listOf(ChartB(chart), LegendB(listOf(Legend(LegendKind.LINE, Hue.PRES, "Mesurée"), Legend(LegendKind.DASH, Hue.PRES, "Prévue")))),
                ),
            )
        }

        // Tendance
        val first = meas.firstOrNull()
        val d24 = if (first != null && cur != null && first.first <= -6.0) cur - first.second else null
        val rise = if (fcMin != null && fc.isNotEmpty()) fc.last().second - fcMin.second else null
        val endT = now + 24 * SH.H
        blocks.add(
            Card(
                "Tendance", null,
                listOf(
                    StatGrid(
                        listOf(
                            Metric2(
                                "SUR 24 H", d24?.let { Fmt.signed(it) } ?: "—", "hPa",
                                first?.let { "depuis " + (if (DetailBuilder.sameDay(now + (it.first * 3600).toLong(), now, i.zone)) "" else "hier ") + SH.hm(i, now + (it.first * 3600).toLong()) },
                            ),
                            Metric2("MIN PRÉVU", fcMin?.let { Fmt.fr(it.second, 0) } ?: "—", "hPa", fcMin?.let { "vers " + SH.hm(i, now + (it.first * 3600).toLong()) }, Hue.ORANGE),
                            Metric2(
                                "REMONTÉE", if (rise != null && rise >= 1.0) Fmt.signed(rise, 0) else "—", "hPa",
                                if (rise != null && rise >= 1.0) "d'ici " + (if (DetailBuilder.sameDay(endT, now, i.zone)) "" else "demain ") + SH.hm(i, endT) else null,
                            ),
                        ),
                        3,
                    ),
                ),
            ),
        )

        blocks.add(
            Card(
                "Lire la tendance", null,
                listOf(
                    Segments(
                        listOf(
                            Seg("Baisse rapide", "< −3", kind == 0),
                            Seg("Baisse", "−3 à −1", kind == 1),
                            Seg("Stable", "± 1", kind == 2),
                            Seg("Hausse", "+1 à +3", kind == 3),
                            Seg("Hausse rapide", "> +3", kind == 4),
                        ),
                    ),
                    NoteB("Variation sur 3 h, en hPa. Une baisse rapide annonce souvent pluie et vent."),
                ),
            ),
        )
        return Sheet(Tile.PRESS, "Pression", SH.subStation(i), R.drawable.mk_gauge, Hue.PRES, blocks)
    }
}

// ───────────────────────────── Prévisions, soleil, air ─────────────────────────────

internal object SheetsC {

    // ── Prévisions ──

    fun forecast(i: DetailInputs): Sheet {
        val now = i.nowSec
        val s = i.snap
        val f = i.forecast
        val mid = DetailBuilder.midnight(i)
        val blocks = ArrayList<Block>()
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val prec = DetailBuilder.hourlyMap(i, "precipitation")
        val snowH = DetailBuilder.hourlyMap(i, "snowfall_height")
        val frz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        val codes = DetailBuilder.hourlyMap(i, "weather_code")
        val isDay = DetailBuilder.hourlyMap(i, "is_day")
        val pops = DetailBuilder.hourlyMap(i, "precipitation_probability")

        // En-tête : ciel du moment et pluie à venir
        val ep = DetailBuilder.episode(i)
        val raining = s?.isRaining == true
        var title = if (f != null) WeatherCodes.label(f.code, f.isDay ?: true) else "Prévisions"
        var icon = SH.codeIcon(f?.code, f?.isDay ?: true)
        val first = ep?.first
        val word = if (ep?.firstIsSnow == true) "neige" else "pluie"
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
            title += ", $word $day"
            icon = SH.kindIcon(if (ep?.firstIsSnow == true) 2 else 0)
        }
        val hiTom = DetailBuilder.dayAt(DetailBuilder.daily(i, "temperature_2m_max"), i, 1)
        val loTom = DetailBuilder.dayAt(DetailBuilder.daily(i, "temperature_2m_min"), i, 1)
        val line = listOfNotNull(
            if (f?.dayMax != null && f.dayMin != null) Fmt.fr(f.dayMax, 0) + "° / " + Fmt.fr(f.dayMin, 0) + "° aujourd'hui" else null,
            if (hiTom != null && loTom != null) Fmt.fr(hiTom, 0) + "° / " + Fmt.fr(loTom, 0) + "° demain" else null,
        ).joinToString(" · ")
        blocks.add(IconHero(icon.first, icon.second, title, line))

        // 24 prochaines heures, toutes les 3 h
        val cells = SH.slots(i, 3, 8).mapNotNull { t ->
            val tp = temps[t] ?: return@mapNotNull null
            val mm = prec[t + SH.H] ?: 0.0
            val pop = pops[t]
            val ic: Pair<Int, Hue>
            var subHue = Hue.MUTED
            if (mm >= 0.1) {
                val kd = SH.kind(DetailBuilder.precipTone(i.altM, t + SH.H, temps, snowH, frz))
                ic = SH.kindIcon(kd)
                subHue = SH.kindHue(kd)
            } else {
                ic = SH.codeIcon(codes[t]?.roundToInt(), (isDay[t] ?: 1.0) >= 0.5)
            }
            Cell(Fmt.hlab(SH.hourOf(i, t).toDouble()), ic.first, ic.second, Fmt.deg(tp, 0), if (pop != null) Fmt.fr(pop, 0) + " %" else "—", subHue)
        }
        if (cells.size >= 4) blocks.add(Card("24 prochaines heures", "risque de pluie", listOf(CellsB(cells, big = false))))

        // 7 jours
        val tmx = DetailBuilder.daily(i, "temperature_2m_max")
        val tmn = DetailBuilder.daily(i, "temperature_2m_min")
        val dcode = DetailBuilder.daily(i, "weather_code")
        val dprec = DetailBuilder.daily(i, "precipitation_sum")
        val dsnow = DetailBuilder.daily(i, "snowfall_sum")
        val t0 = tmx.indexOfLast { it.first <= now }
        if (t0 >= 0) {
            val idx = (t0 until min(t0 + 7, tmx.size)).filter { tmx[it].second != null && tmn.getOrNull(it)?.second != null }
            if (idx.isNotEmpty()) {
                val loS = floor(idx.minOf { tmn[it].second!! }) - 1.0
                val hiS = ceil(idx.maxOf { tmx[it].second!! }) + 1.0
                val rows = idx.map { k ->
                    val lo = tmn[k].second!!
                    val hi = tmx[k].second!!
                    val mm = dprec.getOrNull(k)?.second ?: 0.0
                    val cm = dsnow.getOrNull(k)?.second ?: 0.0
                    val ic = when {
                        cm >= 1.0 -> SH.kindIcon(2)
                        mm >= 1.0 -> SH.kindIcon(0)
                        else -> SH.codeIcon(dcode.getOrNull(k)?.second?.roundToInt())
                    }
                    DayRow(
                        SH.dayShort(i, tmx[k].first, k == t0), ic.first, ic.second,
                        if (mm >= 0.1) Fmt.fr(mm) + " mm" else "", Fmt.fr(lo, 0) + "°", Fmt.fr(hi, 0) + "°",
                        ((lo - loS) / (hiS - loS)).toFloat(), ((hi - loS) / (hiS - loS)).toFloat(),
                    )
                }
                blocks.add(Card("7 jours", "min · max", listOf(DayRows(rows))))
            }
        }
        val alt = f?.altitude
        blocks.add(
            NoteB(
                "Prévisions Open-Meteo (modèles AROME et ICON)" +
                    (if (alt != null && alt.applied) ", températures corrigées pour " + alt.userM.roundToInt() + " m" else "") + ".",
            ),
        )
        return Sheet(Tile.FORECAST, "Prévisions", "Open-Meteo · " + SH.hhmm(i, now), R.drawable.mk_suncloud, Hue.TEAL, blocks)
    }

    // ── Soleil et UV ──

    fun sun(i: DetailInputs): Sheet {
        val now = i.nowSec
        val s = i.snap
        val f = i.forecast
        val mid = DetailBuilder.midnight(i)
        val blocks = ArrayList<Block>()
        val rise = f?.sunriseSec
        val set = f?.sunsetSec
        val riseTom = DetailBuilder.dayAt(DetailBuilder.daily(i, "sunrise"), i, 1)?.toLong()
        val setTom = DetailBuilder.dayAt(DetailBuilder.daily(i, "sunset"), i, 1)?.toLong()

        if (rise != null && set != null && set > rise) {
            val len = set - rise
            val progress = (now - rise).toDouble() / len
            val left = when {
                now < rise -> "Lever dans " + OmFormat.delay(((rise - now) / 60).toInt())
                now > set -> "Soleil couché depuis " + OmFormat.delay(((now - set) / 60).toInt())
                else -> "Coucher dans " + OmFormat.delay(((set - now) / 60).toInt())
            }
            val delta = if (riseTom != null && setTom != null) ((setTom - riseTom) - len) / 60.0 else null
            val dayTxt = "Jour : " + OmFormat.duration(len.toDouble()) +
                (delta?.let { dm ->
                    val n = dm.roundToInt()
                    " · " + (if (n == 0) "stable" else (if (n > 0) "+" else "−") + abs(n) + " min / jour")
                } ?: "")
            blocks.add(
                Card(
                    null, null,
                    listOf(
                        ChartB(SheetCharts.sunArc(SH.hhmm(i, rise), SH.hhmm(i, set), SH.hhmm(i, (rise + set) / 2), progress)),
                        TextPair(left, dayTxt),
                    ),
                    gap = 8,
                ),
            )
        }

        // Aujourd'hui : UV, rayonnement, ensoleillement (modèle Open-Meteo)
        val uv = DetailBuilder.hourly(i, "uv_index", mid, mid + 24 * SH.H)
        val rad = DetailBuilder.hourly(i, "shortwave_radiation", mid, mid + 24 * SH.H)
        val uvMax = uv.maxByOrNull { it.second }
        val radMax = rad.maxByOrNull { it.second }
        val sunshine = DetailBuilder.dayAt(DetailBuilder.daily(i, "sunshine_duration"), i, 0)
        blocks.add(
            Card(
                "Aujourd'hui", "modèle",
                listOf(
                    StatGrid(
                        listOf(
                            Metric2("UV MAX", uvMax?.let { Fmt.fr(it.second) } ?: "—", "", uvMax?.let { "à " + SH.hhmm(i, it.first) }, Hue.SUN),
                            Metric2("RAYONNEMENT MAX", radMax?.let { Fmt.fr(it.second, 0) } ?: "—", "W/m²", radMax?.let { "à " + SH.hhmm(i, it.first) }),
                            Metric2("SOLEIL", sunshine?.let { OmFormat.duration(it) } ?: "—", "", if (sunshine != null) "estimé" else null),
                        ),
                        3,
                    ),
                ),
            ),
        )

        // Rayonnement de la journée
        if (rise != null && set != null && rad.size >= 4 && radMax != null) {
            val x0 = floor(SH.hOf(i, rise))
            val x1 = ceil(SH.hOf(i, set))
            val ymax = if (radMax.second <= 400.0) 500.0 else ceil(radMax.second / 250.0) * 250.0
            val pts = rad.filter { it.first >= rise - SH.H && it.first <= set + SH.H }.map { SH.hOf(i, it.first) to it.second }
            val solar = s?.solar
            val chart = ChartMaker { w ->
                val p = Plot(w, 150f, x0, x1, 0.0, ymax, l = 30f, r = 6f, t = 14f, b = 20f)
                p.ygrid(listOf(0.0, ymax / 2.0, ymax)) { Fmt.fr(it, 0) }
                p.area(pts, Hue.SUN, 0.18f, base = 0.0)
                p.line(pts, Hue.SUN, 2.2f)
                val x = SH.hOf(i, radMax.first)
                p.dot(x, radMax.second, Hue.SUN)
                p.text(p.x(x), p.y(radMax.second) - 9f, Fmt.fr(radMax.second, 0), Hue.SUN, 11f, Paint.Align.CENTER, true)
                p.xlabels(SH.ticks(x0, x1, 2.0), { v -> Fmt.hlab(v) })
                p.drawing()
            }
            blocks.add(
                Card(
                    "Rayonnement", if (solar != null && now in rise..set) "station : " + Fmt.fr(solar, 0) + " W/m² maintenant" else "W/m²",
                    listOf(ChartB(chart), LegendB(listOf(Legend(LegendKind.LINE, Hue.SUN, "Rayonnement prévu (modèle)")))),
                ),
            )
        }

        // Demain
        val codeTom = DetailBuilder.dayAt(DetailBuilder.daily(i, "weather_code"), i, 1)?.roundToInt()
        val uvTom = DetailBuilder.dayAt(DetailBuilder.daily(i, "uv_index_max"), i, 1)
        val sunTom = DetailBuilder.dayAt(DetailBuilder.daily(i, "sunshine_duration"), i, 1)
        if (riseTom != null && setTom != null) {
            val ic = SH.codeIcon(codeTom)
            val text = listOfNotNull(
                if (codeTom != null) WeatherCodes.label(codeTom) else null,
                sunTom?.takeIf { it >= 600.0 }?.let { OmFormat.duration(it) + " de soleil" },
                uvTom?.let { "UV max " + Fmt.fr(it, 0) },
            ).joinToString(" · ")
            blocks.add(
                Card(
                    "Demain · " + SH.dayFull(i, mid + 24 * SH.H), null,
                    listOf(LineRow(ic.first, if (codeTom == null || codeTom <= 2) Hue.SUN else ic.second, text, SH.hhmm(i, riseTom) + "\n" + SH.hhmm(i, setTom), rightMulti = true)),
                ),
            )
        }
        return Sheet(Tile.SUN, "Soleil et UV", "Station Ecowitt + Open-Meteo · " + SH.hhmm(i, now), R.drawable.mk_sun, Hue.SUN, blocks)
    }

    // ── Air et pollens ──

    private val WORDS = listOf("bon", "correct", "moyen", "médiocre", "mauvais", "très mauvais")
    private val EAQI_HUES = listOf(Hue.GREEN, Hue.LIME, Hue.YEL, Hue.ORANGE, Hue.RED, Hue.PINK)

    /** Rang (0 = bon … 5 = très mauvais) d'un indice européen. */
    private fun eaqiBand(x: Double): Int = when {
        x <= 20 -> 0
        x <= 40 -> 1
        x <= 60 -> 2
        x <= 80 -> 3
        x <= 100 -> 4
        else -> 5
    }

    /** Seuils de l'indice européen (µg/m³) tels que publiés par Open-Meteo. */
    private fun pollutantBand(v: Double, limits: DoubleArray): Int {
        val k = limits.indexOfFirst { v <= it }
        return if (k < 0) 5 else k
    }

    fun air(i: DetailInputs): Sheet {
        val now = i.nowSec
        val a = i.forecast?.air
        val blocks = ArrayList<Block>()
        val sub = "Open-Meteo (CAMS) · " + SH.hhmm(i, now)
        val aqi = a?.number(OmSection.HOURLY, "european_aqi")
        if (a == null || aqi == null) {
            blocks.add(NoteB("Qualité de l'air indisponible pour ta position : les données Open-Meteo (CAMS) n'ont pas encore été reçues."))
            return Sheet(Tile.AIR, "Air et pollens", sub, R.drawable.mk_leaf, Hue.GREEN, blocks)
        }
        val band = eaqiBand(aqi)
        val hue = EAQI_HUES[band]
        val advice = listOf(
            "idéal pour aérer", "aérer sans souci", "limiter les efforts intenses", "éviter les efforts prolongés",
            "limiter les activités dehors", "éviter de sortir",
        )[band]
        blocks.add(Hero(Fmt.fr(aqi, 0), "", hue, listOf("Indice européen (EAQI)", WORDS[band].replaceFirstChar { it.uppercase() } + " · " + advice)))
        blocks.add(Card(null, null, listOf(ScaleB((aqi / 120.0).toFloat().coerceIn(0f, 1f))), gap = 8))

        // Polluants
        fun pol(name: String, v: String, limits: DoubleArray): Metric2 {
            val x = a.number(OmSection.HOURLY, v)
            return Metric2(name, x?.let { Fmt.fr(it, 0) } ?: "—", "µg/m³", x?.let { WORDS[pollutantBand(it, limits)] })
        }
        val pollutants = listOf(
            pol("PM2,5", "pm2_5", doubleArrayOf(10.0, 20.0, 25.0, 50.0, 75.0)),
            pol("PM10", "pm10", doubleArrayOf(20.0, 40.0, 50.0, 100.0, 150.0)),
            pol("OZONE", "ozone", doubleArrayOf(50.0, 100.0, 130.0, 240.0, 380.0)),
            pol("NO₂", "nitrogen_dioxide", doubleArrayOf(40.0, 90.0, 120.0, 230.0, 340.0)),
        )
        if (pollutants.any { it.value != "—" }) blocks.add(Card("Polluants", "maintenant", listOf(StatGrid(pollutants, 2))))

        // 24 prochaines heures
        val mid = DetailBuilder.midnight(i)
        val nowH = (now - mid) / 3600.0
        val pts = a.hourlySeries("european_aqi").mapNotNull { p ->
            val v = p.second
            if (v != null && p.first >= now - 1800 && p.first <= now + 24 * SH.H) SH.hOf(i, p.first) to v else null
        }
        if (pts.size >= 4) {
            val top = pts.maxByOrNull { it.second }!!
            val ymax = max(60.0, ceil((top.second + 5.0) / 20.0) * 20.0)
            val x1 = nowH + 24.0
            val chart = ChartMaker { w ->
                val p = Plot(w, 140f, nowH, x1, 0.0, ymax, l = 26f, r = 6f, t = 10f, b = 20f)
                var lo = 0.0
                for (k in 0 until (ymax / 20.0).toInt()) {
                    val hi = lo + 20.0
                    val ya = p.y(hi)
                    val yb = p.y(lo)
                    p.raw { c -> c.drawRect(p.l, ya, p.w - p.r, yb, Gfx.fill(EAQI_HUES[min(k, 5)], 0.07f)) }
                    lo = hi
                }
                p.ygrid(SH.ticks(0.0, ymax + 1.0, 20.0)) { Fmt.fr(it, 0) }
                p.line(pts, Hue.GREEN, 2.2f)
                p.dot(top.first, top.second, Hue.GREEN)
                val tx = min(max(p.x(top.first), p.l + 40f), p.w - p.r - 40f)
                p.text(tx, p.y(top.second) - 9f, Fmt.fr(top.second, 0) + " · " + WORDS[eaqiBand(top.second)], Hue.GREEN, 11f, Paint.Align.CENTER, true)
                p.xlabels(SH.ticks(nowH, x1, 4.0), { v -> Fmt.hlab(v) })
                p.drawing()
            }
            blocks.add(Card("24 prochaines heures", "indice", listOf(ChartB(chart))))
        }

        // Pollens
        val species = listOf(
            "Aulne" to "alder_pollen", "Bouleau" to "birch_pollen", "Graminées" to "grass_pollen",
            "Armoise" to "mugwort_pollen", "Olivier" to "olive_pollen", "Ambroisie" to "ragweed_pollen",
        )
        val vals = species.map { it.first to a.number(OmSection.HOURLY, it.second) }
        if (vals.any { it.second != null }) {
            val active = vals.count { (it.second ?: 0.0) >= 1.0 }
            blocks.add(
                Card(
                    "Pollens", "grains/m³",
                    listOf(
                        PollenGrid(vals.map { it.first to (it.second?.let { v -> Fmt.fr(v, 0) } ?: "—") }),
                        NoteB(if (active == 0) "Aucune des six espèces suivies n'est active aujourd'hui." else "$active espèce" + (if (active > 1) "s" else "") + " active" + (if (active > 1) "s" else "") + " sur les six suivies."),
                    ),
                ),
            )
        }
        return Sheet(Tile.AIR, "Air et pollens", sub, R.drawable.mk_leaf, Hue.GREEN, blocks)
    }
}
