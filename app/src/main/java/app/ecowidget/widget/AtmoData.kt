package app.ecowidget.widget

import android.content.Context
import app.ecowidget.R
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Metric
import app.ecowidget.data.OmFormat
import app.ecowidget.data.OmSection
import app.ecowidget.data.RainColors
import app.ecowidget.data.Tone
import app.ecowidget.data.WeatherCodes
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val H = 3600L

/** Palette des maquettes « Air et pollens ». */
internal object AP {
    val TX = 0xFFEEF3FA.toInt()
    val T2 = 0xFFAAB5C5.toInt()
    val BLUE = 0xFF8DB2FF.toInt()
    val TEAL = 0xFF5EE0CF.toInt()
    val SUN = 0xFFFFB36B.toInt()
    val SNOW = 0xFFBFE3FF.toInt()
    val AMB = 0xFFFFC857.toInt()
    val GR = 0xFFA9B3C2.toInt()
    val ICE = 0xFF9CD4FF.toInt()
    val RED = 0xFFF0566B.toInt()
    val ORANGE = 0xFFF2A24E.toInt()
    val YEL = 0xFFF2C14E.toInt()
    val GREEN = 0xFF7EE0A5.toInt()
    val IC = 0xFFEAF6FF.toInt()
    val RC = 0xFF4F9BFF.toInt()
    val LIMIT = 0xFFFFE08A.toInt()
    val DARK = 0xFF0B1220.toInt()
    val EAQ = intArrayOf(0xFF50F0E6.toInt(), 0xFF50CCAA.toInt(), 0xFFF0E641.toInt(), 0xFFFF5050.toInt(), 0xFF960032.toInt(), 0xFF7D2181.toInt())
    val EAQ_WORDS = listOf("Bon", "Correct", "Moyen", "Médiocre", "Mauvais", "Très mauvais")
    val LV = intArrayOf(0x22FFFFFF, 0xFF7EE0A5.toInt(), 0xFFF2C14E.toInt(), 0xFFF2894E.toInt(), 0xFFF0566B.toInt())

    fun rainColor(v: Double): Int = when {
        v < 1.0 -> 0xFF8EC9FF.toInt()
        v < 2.5 -> 0xFF4F9BFF.toInt()
        v < 5.0 -> 0xFF8B6CF2.toInt()
        else -> 0xFFF06BB0.toInt()
    }
}

// ───────────────────────────── Données du widget « Atmosphère » ─────────────────────────────

/** `icon` null : pastille verte « à jour ». */
internal class APill(val text: String, val icon: Int?, val tint: Int)

internal class AWind(val speed: String, val deg: Float?, val dir: String)

/** Seconde pastille sous la température : texte normal + valeur en gras (colorée si `boldColor`). */
internal class AChip2(val pre: String, val bold: String, val boldColor: Int?)

internal class AHour(val label: String, val icon: Int, val iconColor: Int, val temp: String, val sub: String, val subColor: Int)
internal class ADay(val label: String, val icon: Int, val iconColor: Int, val hi: String, val lo: String, val sub: String, val subColor: Int)
internal class APol(val name: String, val value: String, val frac: Float)
internal class AAir(val v: Int, val label: String, val color: Int, val pos: Float, val pols: List<APol>)
internal class APollen(val rows: List<Pair<String, Int>>, val chip: String, val chipColor: Int, val off: Boolean, val note: String)

/**
 * Un graphique à barres. `phases` : 0 pluie, 1 mêlée, 2 neige. `ticks` : position (0 à 1) + texte ;
 * `tickEdge` : le dernier repère est aligné à droite (minutes) ; sinon tous centrés (heures).
 */
internal class APanel(
    val title: String,
    val total: String,
    val totalColor: Int,
    val vals: List<Double>,
    val phases: List<Int>,
    val vmax: Double,
    val flip: Int?,
    val lim: List<Double>?,
    val ticks: List<Pair<Float, String>>,
    val tickEdge: Boolean,
)

internal class AGauge(val limit: Double, val station: Double)

internal sealed class AMod {
    /** Courbe 24 h : « Aucune pluie avant X h » ou, en hiver sans précipitation, le gel prévu. */
    class Curve(
        val icon: Int,
        val title: String,
        val rightPre: String,
        val rightBold: String,
        val rightPost: String,
        val obs: List<Double>,
        val fc: List<Double>,
        val axis: List<String>,
        val mark: Int?,
        val zero: Boolean,
    ) : AMod()

    class Heat(
        val uv: Double,
        val uvWord: String,
        val peak: String,
        val obs: List<Double>,
        val fc: List<Double>,
        val axis: List<String>,
        val mark: Int?,
    ) : AMod()

    class Offline(val sub: String, val obs: List<Double>, val fc: List<Double>) : AMod()

    class Rain(
        val title: String,
        val sub: String,
        val bigRight: String,
        val bigUnit: String,
        val smallTop: String,
        val minutes: APanel,
        val hours: APanel,
    ) : AMod()

    class Snow(
        val title: String,
        val sub: String,
        val chips: List<String>,
        val gauge: AGauge?,
        val minutes: APanel,
        val hours: APanel,
    ) : AMod()

    class Storm(val minutes: Int, val stats: List<Triple<String, String, Int>>) : AMod()
}

internal class AModel(
    val scn: Scn,
    val place: String,
    val alt: String,
    val pill: APill,
    val heroValue: String,
    val feel: String?,
    val lo: Double?,
    val hi: Double?,
    val cur: Double?,
    val offline: Boolean,
    val wind: AWind,
    val chip2: AChip2,
    val hours: List<AHour>,
    val days: List<ADay>,
    val air: AAir?,
    val pollen: APollen,
    val mod: AMod,
)

internal object AtmoData {

    fun build(ctx: Context, i: DetailInputs, ageMin: Int?, placeName: String?): AModel? {
        val d = BData(i, ageMin)
        val scn = BentoState.resolve(ctx, d.wanted(), System.currentTimeMillis())
        val hero = d.hero(scn) ?: return null
        val offline = scn == Scn.OFFLINE
        val now = d.now
        val zone = d.zone
        fun fm(v: Double, n: Int) = d.fm(v, n)
        fun neg(s: String) = d.neg(s)
        fun clock(sec: Long): Int = Instant.ofEpochSecond(sec).atZone(zone).hour

        val lo = if (offline) d.f?.dayMin else (d.d?.dayMin ?: d.f?.dayMin)
        val hi = d.dayMax
        val cur: Double? = if (offline) hero.value.replace('−', '-').replace(',', '.').toDoubleOrNull() else d.temp

        // ── Vent et seconde pastille
        val windKmh: Double?
        val windDir: Double?
        if (offline) {
            windKmh = d.hourly("wind_speed_10m", now - 3599, now).lastOrNull()?.second
            windDir = d.hourly("wind_direction_10m", now - 3599, now).lastOrNull()?.second
        } else {
            windKmh = d.snap?.windSpeed
            windDir = d.snap?.windDir
        }
        val wind = AWind(windKmh?.let { fm(it, 0) + " km/h" } ?: "— km/h", windDir?.toFloat(), windDir?.let { Metric.cardinal(it) } ?: "")
        val gust = max(d.gustNow, d.hourly("wind_gusts_10m", now - 3599, now + 2 * H).maxOfOrNull { it.second } ?: 0.0)
        val nextTemp = d.hourly("temperature_2m", now + 1, now + 2 * H).firstOrNull()
        val chip2 = when (scn) {
            Scn.DRY -> AChip2("UV " + (d.uvNow?.let { fm(it, 0) } ?: "—"), "", null)
            Scn.ANNOUNCED -> AChip2("Cumul 12 h ", fm(d.mm12, 1) + " mm", null)
            Scn.RAIN -> AChip2("Rafale ", if (gust > 0.0) fm(gust, 0) else "—", null)
            Scn.WINTER -> AChip2("Neige ", fm(max(d.snowCm12, 0.0), 0) + " cm", null)
            Scn.HEAT -> AChip2("Nuit min ", d.frostMin?.let { neg(fm(it, 0)) + "°" } ?: "—", null)
            Scn.OFFLINE -> AChip2(
                "Prévu " + (nextTemp?.let { clock(it.first).toString() } ?: "—") + " h ",
                nextTemp?.let { neg(fm(it.second, 1)) + "°" } ?: "—", null,
            )
            Scn.STORM -> AChip2("Rafale ", if (gust > 0.0) fm(gust, 0) else "—", AP.AMB)
        }

        // ── Pastille d'en-tête
        val pillData = if (scn == Scn.DRY) null else d.pill(scn)
        val ageTxt = ageMin?.let { if (it >= 60) "il y a " + it / 60 + " h" else "il y a " + max(1, it) + " min" } ?: "à jour"
        val pill = if (pillData != null) APill(pillData.text, pillData.icon, pillData.tint) else APill(ageTxt, null, AP.GREEN)

        // ── Prochaines heures : l'heure en cours (« Maint. ») puis les six suivantes
        val precMap = DetailBuilder.hourlyMap(i, "precipitation")
        val snowMap = DetailBuilder.hourlyMap(i, "snowfall")
        val gustMap = DetailBuilder.hourlyMap(i, "wind_gusts_10m")
        val windMap = DetailBuilder.hourlyMap(i, "wind_speed_10m")
        val uvMap = DetailBuilder.hourlyMap(i, "uv_index")
        val allH = d.f?.hours.orEmpty()
        val i0 = allH.indexOfLast { it.tSec <= now }.coerceAtLeast(0)
        val hours = allH.drop(i0).take(7).mapIndexed { k, hp ->
            val t = hp.tSec
            val pr = precMap[t + H]
            val sn = snowMap[t + H]
            var sub: String
            var sc = AP.T2
            when (scn) {
                Scn.DRY -> sub = windMap[t]?.let { fm(it, 0) + " km/h" } ?: ""
                Scn.ANNOUNCED, Scn.RAIN -> {
                    val v = pr ?: 0.0
                    sub = if (v >= 0.05) fm(v, 1) + " mm" else "0 mm"
                    if (v >= 0.05) sc = if (v < 1.0) AP.BLUE else AP.rainColor(v)
                }
                Scn.WINTER -> {
                    val frost = (hp.temp ?: 1.0) <= -2.0
                    if (sn != null && sn >= 0.1) {
                        sub = fm(sn, 0) + " cm"
                        sc = AP.SNOW
                    } else if (frost) {
                        sub = "gel"
                        sc = AP.ICE
                    } else {
                        sub = "0 cm"
                    }
                }
                Scn.HEAT -> {
                    val u = uvMap[t] ?: 0.0
                    sub = "UV " + fm(u, 0)
                    sc = when {
                        u >= 8 -> AP.RED
                        u >= 6 -> AP.ORANGE
                        u >= 4 -> AP.YEL
                        u >= 1 -> AP.GREEN
                        else -> AP.T2
                    }
                }
                Scn.STORM -> {
                    val gv = gustMap[t] ?: 0.0
                    sub = fm(gv, 0) + " km/h"
                    sc = if (gv >= 55) AP.RED else if (gv >= 35) AP.AMB else AP.T2
                }
                Scn.OFFLINE -> {
                    sub = "prévu"
                    sc = AP.GR
                }
            }
            val night = !hp.isDay && (hp.code == 0 || hp.code == 1)
            val ic = if (night) R.drawable.ic_wx_moon else WeatherCodes.icon(hp.code, hp.isDay)
            val col = if (offline) AP.GR else wxColor(hp.code, scn, night)
            AHour(if (k == 0) "Maint." else clock(t).toString() + " h", ic, col, hp.temp?.let { neg(fm(it, 0)) + "°" } ?: "—", sub, sc)
        }

        // ── Cinq jours, à partir de demain
        val tmx = DetailBuilder.daily(i, "temperature_2m_max")
        val tmn = DetailBuilder.daily(i, "temperature_2m_min")
        val codes = DetailBuilder.daily(i, "weather_code")
        val snowSum = DetailBuilder.daily(i, "snowfall_sum")
        val rainSum = DetailBuilder.daily(i, "precipitation_sum")
        val uvMax = DetailBuilder.daily(i, "uv_index_max")
        val today = tmx.indexOfLast { it.first <= now }
        val days = ArrayList<ADay>()
        if (today >= 0) {
            for (k in 1..5) {
                val t = tmx.getOrNull(today + k) ?: break
                val hiD = t.second ?: break
                val loD = tmn.getOrNull(today + k)?.second ?: break
                val code = codes.getOrNull(today + k)?.second?.roundToInt()
                val sn = DetailBuilder.dayAt(snowSum, i, k)
                val rn = DetailBuilder.dayAt(rainSum, i, k)
                val uv = DetailBuilder.dayAt(uvMax, i, k)
                val storm = code == 95 || code == 96 || code == 99
                var sub: String
                var sc = AP.T2
                val mm = if (rn != null && rn >= 0.05) fm(rn, if (rn < 10.0) 1 else 0) + " mm" else "0 mm"
                val mmCol = if (rn != null && rn >= 0.05) AP.BLUE else AP.T2
                when {
                    scn == Scn.WINTER && sn != null && sn >= 0.5 -> {
                        sub = fm(sn, 0) + " cm"
                        sc = AP.SNOW
                    }
                    scn == Scn.WINTER && loD <= -1.0 && (rn ?: 0.0) < 0.2 -> {
                        sub = "gel"
                        sc = AP.ICE
                    }
                    scn == Scn.WINTER -> sub = "0 cm"
                    scn == Scn.HEAT && storm -> {
                        sub = "orage"
                        sc = AP.AMB
                    }
                    scn == Scn.HEAT && uv != null && uv >= 1.0 -> {
                        sub = "UV " + fm(uv, 0)
                        sc = if (uv >= 8) AP.RED else if (uv >= 6) AP.ORANGE else if (uv >= 4) AP.YEL else AP.GREEN
                    }
                    else -> {
                        sub = mm
                        sc = mmCol
                    }
                }
                if (offline) sc = AP.GR
                val label = if (k == 1) {
                    "Dem."
                } else {
                    Instant.ofEpochSecond(t.first + 12 * H).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRENCH)
                        .trimEnd('.').replaceFirstChar { it.uppercase() } + "."
                }
                val col = if (offline) AP.GR else wxColor(code, scn, false)
                days.add(
                    ADay(
                        label, WeatherCodes.icon(code, true), col,
                        neg(hiD.roundToInt().toString()) + "°", neg(loD.roundToInt().toString()) + "°", sub, sc,
                    ),
                )
            }
        }

        // ── Qualité de l'air
        val a = d.f?.air
        val aqiV = a?.number(OmSection.HOURLY, "european_aqi")
        val air = aqiV?.let { v ->
            val idx = if (v < 100) min(5, (v / 20).toInt()) else 5
            fun pol(name: String, key: String, scale: Double): APol? = a?.number(OmSection.HOURLY, key)?.let { x ->
                APol(name, fm(x, 0), (x / scale).toFloat().coerceIn(0.03f, 1f))
            }
            AAir(
                v.roundToInt(), AP.EAQ_WORDS[idx], AP.EAQ[idx], min(1f, (v / 120.0).toFloat()),
                listOfNotNull(pol("PM2.5", "pm2_5", 25.0), pol("PM10", "pm10", 50.0), pol("O₃", "ozone", 180.0), pol("NO₂", "nitrogen_dioxide", 100.0)),
            )
        }

        // ── Pollens : six espèces, dans l'ordre des maquettes
        val keys = listOf(
            "Bouleau" to "birch_pollen", "Graminées" to "grass_pollen", "Aulne" to "alder_pollen",
            "Armoise" to "mugwort_pollen", "Olivier" to "olive_pollen", "Ambroisie" to "ragweed_pollen",
        )
        val rows = keys.map { (n, key) -> n to (a?.number(OmSection.HOURLY, key)?.let { x -> min(4, d.pollenLevel(x)) } ?: 0) }
        val pMax = rows.maxOf { it.second }
        val month = Instant.ofEpochSecond(now).atZone(zone).monthValue
        val pollen = when {
            pMax == 0 -> APollen(
                rows, "Hors saison", AP.T2, true,
                if (month == 11 || month == 12 || month == 1) "Reprise en février : aulne, noisetier, puis bouleau en mars" else "Aucun pollen mesuré en ce moment",
            )
            scn == Scn.STORM && pMax >= 2 -> APollen(rows, "Asthme d'orage", AP.AMB, false, "")
            else -> APollen(
                rows,
                when (pMax) {
                    1 -> "Faible"
                    2 -> "Modéré"
                    3 -> "Élevé"
                    else -> "Très élevé"
                },
                AP.LV[pMax], false, "",
            )
        }

        val mod = module(d, i, scn, hero, offline)
        val place = placeName?.substringBefore(',')?.trim().orEmpty()
        val alt = i.altM?.let { Math.round(it).toString() + " m" }.orEmpty()
        return AModel(scn, place, alt, pill, hero.value, hero.feel, lo, hi, cur, offline, wind, chip2, hours, days, air, pollen, mod)
    }

    /** Couleur d'icône des maquettes : soleil orangé, éclaircies turquoise (orangé en chaleur), nuage gris, pluie bleue, neige claire, orage ambre. */
    private fun wxColor(code: Int?, scn: Scn, night: Boolean): Int = when {
        night -> AP.T2
        code == 0 || code == 1 -> AP.SUN
        code == 2 -> if (scn == Scn.HEAT) AP.SUN else AP.TEAL
        code == 95 || code == 96 || code == 99 -> AP.AMB
        code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82) -> AP.BLUE
        code in listOf(71, 73, 75, 77, 85, 86) -> AP.SNOW
        else -> AP.T2
    }

    // ───────────────────────────── Module central ─────────────────────────────

    private fun module(d: BData, i: DetailInputs, scn: Scn, hero: TdHero, offline: Boolean): AMod {
        val now = d.now
        val zone = d.zone
        fun fm(v: Double, n: Int) = d.fm(v, n)
        fun neg(s: String) = d.neg(s)
        fun clock(sec: Long): Int = Instant.ofEpochSecond(sec).atZone(zone).hour

        val precip = when (scn) {
            Scn.RAIN, Scn.ANNOUNCED -> true
            Scn.WINTER -> d.soon || d.mm12 >= 0.3 || d.snowCm12 >= 0.3
            else -> false
        }
        if (precip) return if (scn == Scn.WINTER) snowModule(d, i) else rainModule(d, i, scn)

        if (scn == Scn.STORM) {
            val s = d.storm()
            val gustTxt = s?.gust?.removePrefix("raf. ≤ ")?.takeIf { it.isNotBlank() }?.let { "≤ $it km/h" } ?: "—"
            val stats = listOf(
                Triple("Pluie prévue", (s?.rate ?: "—") + " mm/h", AP.TX),
                Triple("Rafales (3 h)", gustTxt, AP.TX),
                Triple("Cumul 12 h", fm(d.mm12, 1) + " mm", AP.AMB),
            )
            return AMod.Storm(d.stormMinutes, stats)
        }

        val nowH = clock(now)
        fun h(off: Int) = ((((nowH + off) % 24) + 24) % 24).toString() + " h"
        val axis = listOf(h(-12), h(-6), "Maint.", h(6), h(12))
        val markIdx: Int? = if (hero.fc.isNotEmpty()) hero.obs.size + hero.fc.indices.maxByOrNull { hero.fc[it] }!! else null

        if (offline) {
            val m = d.ageMin ?: 0
            val at = d.snap?.observedAtSec?.let { OmFormat.hhmm(it).replace(":", " h ") } ?: "—"
            val since = if (m < 60) "$m min" else "${m / 60} h " + String.format(Locale.ROOT, "%02d", m % 60)
            return AMod.Offline("Dernière mesure $at · il y a $since", hero.obs, hero.fc)
        }
        if (scn == Scn.HEAT) {
            val uv = d.uvNow ?: 0.0
            val word = when {
                uv < 3 -> "UV faible"
                uv < 6 -> "UV modéré"
                uv < 8 -> "UV fort"
                uv < 11 -> "UV très fort"
                else -> "UV extrême"
            }
            val pk = d.fcTemps.take(24).maxByOrNull { it.second }
            val peak = pk?.let { neg(fm(it.second, 0)) + "° à " + clock(it.first) + " h" } ?: (d.dayMax?.let { neg(fm(it, 0)) + "°" } ?: "—")
            val ax = listOf(h(-12), h(-6), h(0), h(6), h(12))
            return AMod.Heat(uv, word, peak, hero.obs, hero.fc, ax, markIdx)
        }
        if (scn == Scn.WINTER) {
            val fr = d.frost()
            val title = if (fr != null) fr.title + " · " + fr.value else "Températures sur 24 h"
            return AMod.Curve(R.drawable.ic_flake, title, "", fr?.sub ?: "", "", hero.obs, hero.fc, axis, markIdx, true)
        }
        // Sec
        val next = DetailBuilder.hourly(i, "precipitation", now + 1, now + 49 * H).firstOrNull { it.second >= 0.1 }
        val title = if (next != null) {
            "Aucune pluie avant " + max(1, ceil((next.first - H - now) / 3600.0).toInt()) + " h"
        } else {
            "Aucune pluie sur 48 h"
        }
        val midnight = DetailBuilder.midnight(i)
        val todayMax = DetailBuilder.hourly(i, "temperature_2m", midnight, midnight + 24 * H - 1).maxByOrNull { it.second }
        val mx = d.dayMax ?: todayMax?.second
        return AMod.Curve(
            R.drawable.ic_check, title, "Max ", mx?.let { neg(fm(it, 0)) + "°" } ?: "—",
            if (todayMax != null) " à " + clock(todayMax.first) + " h" else "", hero.obs, hero.fc, axis, markIdx, false,
        )
    }

    // ───────────────────────────── Pluie ─────────────────────────────

    private class Series(val phaseM: List<Int>, val phaseH: List<Int>, val limM: List<Double>?, val limH: List<Double>?)

    private fun series(d: BData, i: DetailInputs): Series {
        val now = d.now
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val sh = DetailBuilder.hourlyMap(i, "snowfall_height")
        val fz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        fun phaseAt(tStart: Long): Int = when (DetailBuilder.precipTone(i.altM, tStart, temps, sh, fz)) {
            Tone.SNOW -> 2
            Tone.ICE -> 1
            else -> 0
        }
        val limS = DetailBuilder.limitSeries(i, 14)
        fun limAt(t: Long): Double {
            if (t <= limS.first().first) return limS.first().second
            for (k in 1 until limS.size) {
                val (t1, v1) = limS[k]
                if (t <= t1) {
                    val (t0, v0) = limS[k - 1]
                    val fr = if (t1 == t0) 0.0 else (t - t0).toDouble() / (t1 - t0)
                    return v0 + (v1 - v0) * fr
                }
            }
            return limS.last().second
        }
        val has = limS.size >= 2
        return Series(
            List(12) { phaseAt(((now + it * 300L) / H) * H) },
            d.prec12.map { phaseAt(it.first - H) },
            if (has) List(12) { limAt(now + it * 300L + 150L) } else null,
            if (has) d.prec12.map { limAt(it.first - H / 2) } else null,
        )
    }

    private fun flakeOf(ph: List<Int>): Int? {
        if (ph.isEmpty() || ph[0] != 0) return null
        val k = ph.indexOfFirst { it > 0 }
        return if (k > 0) k else null
    }

    private fun hms(d: BData, sec: Long): String {
        val z = Instant.ofEpochSecond(sec).atZone(d.zone)
        return if (z.minute == 0) "${z.hour} h" else "${z.hour} h " + String.format(Locale.ROOT, "%02d", z.minute)
    }

    private fun rainModule(d: BData, i: DetailInputs, scn: Scn): AMod {
        val now = d.now
        fun fm(v: Double, n: Int) = d.fm(v, n)
        val s = series(d, i)
        val acc = if (scn == Scn.ANNOUNCED) AP.BLUE else 0xFF7DBBFF.toInt()
        val bins = d.bins.take(12)
        val mCum = bins.sum() / 12.0

        val minutes = APanel(
            "Dans l'heure", fm(mCum, 1) + " mm", acc, bins, s.phaseM, max(3.2, bins.maxOrNull() ?: 0.0), null, s.limM,
            listOf(0.25f to "+15", 0.5f to "+30", 0.75f to "+45", 1f to "+60"), true,
        )
        val hVals = d.prec12.map { it.second }
        val hTicks = ArrayList<Pair<Float, String>>()
        d.prec12.forEachIndexed { k, p ->
            val h = Instant.ofEpochSecond(p.first - H).atZone(d.zone).hour
            if (h % 3 == 0) hTicks.add((k / 12f) to "$h h")
        }
        val hours = APanel(
            "D'ici 12 h", fm(d.mm12, 1) + " mm", acc, hVals, s.phaseH, max(2.5, hVals.maxOrNull() ?: 0.0), flakeOf(s.phaseH), s.limH, hTicks, false,
        )

        val firstWet = d.prec12.firstOrNull { it.second >= 0.1 }
        val lastWet = d.prec12.lastOrNull { it.second >= 0.1 }
        val pop = if (d.probMap.isEmpty() || d.prec12.isEmpty()) null else d.prec12.maxOf { d.probMap[it.first] ?: 0.0 }
        fun word(v: Double) = RainColors.word(RainColors.RATE, max(v, 0.1))
        val rates = (bins + d.prec12.map { it.second }).filter { it >= 0.1 }
        val w1 = rates.firstOrNull()?.let { word(it) }
        val w2 = rates.maxOrNull()?.let { word(it) }

        if (d.rainNow) {
            val title = word(d.rate).replaceFirstChar { it.uppercase() } + " · " + fm(d.rate, 1) + " mm/h"
            val end = d.f?.rainEndMin?.takeIf { it in 1..240 }
            // Reprise : une heure humide après une heure sèche, dans les 12 h.
            var resume: String? = null
            var dry = false
            for (p in d.prec12) {
                if (p.second < 0.1) {
                    dry = true
                } else if (dry) {
                    resume = hms(d, p.first - H)
                    break
                }
            }
            val sub = (if (end != null) "Fin dans ~" + OmFormat.delay(end) else "Pluie en cours") + (if (resume != null) " · reprise vers $resume" else "")
            val day = d.snap?.rainDaily
            return AMod.Rain(title, sub, "", "", day?.let { fm(it, 1) + " mm" } ?: "", minutes, hours)
        }
        val nm = d.f?.nextRainMin
        val title = when {
            nm != null && nm in 1..90 -> "Pluie dans " + OmFormat.delay(nm)
            firstWet != null -> "Pluie vers " + hms(d, max(firstWet.first - H, now))
            else -> "Pluie annoncée"
        }
        val seq = if (w1 != null && w2 != null && w1 != w2) {
            w1.replaceFirstChar { it.uppercase() } + " puis " + w2
        } else {
            (w1 ?: "Pluie").replaceFirstChar { it.uppercase() }
        }
        val sub = seq + (if (lastWet != null) " · fin vers " + hms(d, lastWet.first) else "")
        return AMod.Rain(title, sub, pop?.let { fm(it, 0) } ?: "", " %", "", minutes, hours)
    }

    // ───────────────────────────── Neige ─────────────────────────────

    private fun snowModule(d: BData, i: DetailInputs): AMod {
        val now = d.now
        fun fm(v: Double, n: Int) = d.fm(v, n)
        val s = series(d, i)
        val snowByEnd = d.snowH.toMap()
        val firstPrec = d.prec12.firstOrNull()?.second ?: 0.0
        val firstSnow = d.snowH.firstOrNull()?.second ?: 0.0
        val ratio = if (firstPrec >= 0.1) (firstSnow / firstPrec).coerceIn(0.7, 1.6) else 1.0
        val bins = d.bins.take(12)
        // 5 minutes à x mm/h : x / 12 mm ; en cm de neige : × rapport neige / eau.
        val mVals = bins.mapIndexed { k, v -> if (s.phaseM[k] == 2) v / 12.0 * ratio else v / 12.0 }
        val mSnow = mVals.indices.sumOf { k -> if (s.phaseM[k] == 2) mVals[k] else 0.0 }
        val mTotal = if (mSnow >= 0.05) fm(mSnow, 1) + " cm" else fm(mVals.sum(), 1) + " mm"
        val hVals = d.prec12.mapIndexed { k, p -> if (s.phaseH[k] == 2) (snowByEnd[p.first] ?: p.second) else p.second }
        val hSnow = d.prec12.indices.sumOf { k -> if (s.phaseH[k] == 2) (snowByEnd[d.prec12[k].first] ?: 0.0) else 0.0 }
        val hTotal = if (hSnow >= 0.1) fm(hSnow, 0) + " cm" else fm(d.mm12, 1) + " mm"
        val hTicks = ArrayList<Pair<Float, String>>()
        d.prec12.forEachIndexed { k, p ->
            val h = Instant.ofEpochSecond(p.first - H).atZone(d.zone).hour
            if (h % 3 == 0) hTicks.add((k / 12f) to "$h h")
        }
        val minutes = APanel(
            "Dans l'heure", mTotal, AP.SNOW, mVals, s.phaseM, max(1.6, mVals.maxOrNull() ?: 0.0), null, s.limM,
            listOf(0.25f to "15", 0.5f to "30", 0.75f to "45", 1f to "60"), true,
        )
        val hours = APanel("D'ici 12 h", hTotal, AP.SNOW, hVals, s.phaseH, max(2.4, hVals.maxOrNull() ?: 0.0), flakeOf(s.phaseH), s.limH, hTicks, false)

        val sn = d.snowH.firstOrNull { it.second >= 0.1 }
        val snowNow = sn != null && (sn.first - H) <= now + 600
        val title = when {
            sn == null -> "Précipitations"
            snowNow -> "Neige en cours"
            else -> "Neige dès " + hms(d, sn.first - H)
        }
        val alt = d.snowAlt()
        val lastSnow = d.snowH.lastOrNull { it.second >= 0.1 }
        val subA = when {
            alt == null -> ""
            alt.limit <= alt.station -> "Limite sous la station"
            else -> "Limite au-dessus de la station"
        }
        val sub = subA + (if (lastSnow != null) (if (subA.isNotEmpty()) " · " else "") + "fin vers " + hms(d, lastSnow.first) else "")
        val chips = ArrayList<String>()
        d.snow24()?.let { chips.add(fm(it.total, 0) + " cm / 24 h") }
        d.frost()?.takeIf { it.value.startsWith("−") || it.value.startsWith("-") }?.let {
            chips.add("Gel " + it.value + " à " + it.sub.substringAfter("à ").substringBefore(" ·"))
        }
        val gauge = alt?.let { AGauge(it.limit, it.station) }
        return AMod.Snow(title, sub, chips, gauge, minutes, hours)
    }
}
