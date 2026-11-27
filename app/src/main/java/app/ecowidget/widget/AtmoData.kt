package app.ecowidget.widget

import android.content.Context
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Metric
import app.ecowidget.data.OmCatalog
import app.ecowidget.data.OmFormat
import app.ecowidget.data.OmSection
import app.ecowidget.data.RainColors
import app.ecowidget.data.Tile
import app.ecowidget.data.Tone
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val H = 3600L

// ───────────────────────────── Données du widget « Atmosphère » ─────────────────────────────

internal class AChip(val label: String, val value: String, val tile: Tile?)
internal class AHour(val label: String, val icon: Int, val iconColor: Int, val temp: String, val sub: String)
internal class ADay(val label: String, val icon: Int, val iconColor: Int, val hi: String, val lo: String, val sub: String, val today: Boolean)
internal class APol(val name: String, val value: String, val frac: Float, val band: Int)
internal class AAir(val aqi: Int, val word: String, val band: Int, val pols: List<APol>)

/** `rows` : nom + niveau 0 à 4 (nombre de points allumés). */
internal class APollen(val rows: List<Pair<String, Int>>, val summary: String, val summaryLevel: Int, val message: String?, val storm: Boolean)

/**
 * Un graphique à barres de pluie (ou neige). `phases` : 0 pluie, 1 mêlée, 2 neige. `ticks` : position (0 à 1) et texte.
 * `limit` : limite pluie / neige (m) pour chaque barre, ou null.
 */
internal class APanel(
    val title: String,
    val cumul: String,
    val vals: List<Double>,
    val phases: List<Int>,
    val ticks: List<Pair<Float, String>>,
    val limit: List<Double>?,
    val flake: Int?,
    val top: Double,
)

internal class AGauge(val limit: Double, val station: Double, val top: Double)

internal sealed class AMod {
    /** Courbe de température sur 24 h, avec titre, repères horaires, seuil, jauge UV ou bouton « Réessayer ». */
    class Curve(
        val title: String,
        val right: String,
        val obs: List<Double>,
        val fc: List<Double>,
        val ticks: List<Pair<Int, String>>,
        val thr: Double?,
        val zero: Boolean,
        val uv: Double?,
        val retry: Boolean,
    ) : AMod()

    /** Pluie ou neige : deux graphiques, et la jauge d'altitude en hiver. */
    class Rain(
        val title: String,
        val right: String,
        val minutes: APanel,
        val hours: APanel,
        val gauge: AGauge?,
        val chips: List<String>,
    ) : AMod()

    /** Orage : trois blocs de chiffres (libellé, valeur, précision) + les barres des 60 prochaines minutes. */
    class Storm(val stats: List<Triple<String, String, String>>, val bins: List<Double>) : AMod()
}

internal class AModel(
    val scn: Scn,
    val place: String,
    val alt: String,
    val tail: String,
    val warn: Boolean,
    val pill: BentoPill?,
    val heroValue: String,
    val feel: String?,
    val lo: Double?,
    val hi: Double?,
    val cur: Double?,
    val offline: Boolean,
    val chips: List<AChip>,
    val hours: List<AHour>,
    val days: List<ADay>,
    val air: AAir?,
    val pollen: APollen,
    val mod: AMod,
)

internal object AtmoData {

    fun build(ctx: Context, i: DetailInputs, status: Status, placeName: String?, ageMin: Int?): AModel? {
        val d = BData(i, ageMin)
        val scn = BentoState.resolve(ctx, d.wanted(), System.currentTimeMillis())
        val hero = d.hero(scn) ?: return null
        val offline = scn == Scn.OFFLINE
        val now = d.now
        val zone = d.zone
        fun fm(v: Double, n: Int) = d.fm(v, n)
        fun neg(s: String) = d.neg(s)
        fun clock(sec: Long): Int = Instant.ofEpochSecond(sec).atZone(zone).hour

        // ── Température du héros
        val lo = if (offline) d.f?.dayMin else (d.d?.dayMin ?: d.f?.dayMin)
        val hi = d.dayMax
        val cur: Double? = if (offline) hero.value.replace('−', '-').replace(',', '.').toDoubleOrNull() else d.temp

        // ── Pastilles sous la température
        val windKmh: Double?
        val windDir: Double?
        if (offline) {
            windKmh = d.hourly("wind_speed_10m", now - 3599, now).lastOrNull()?.second
            windDir = d.hourly("wind_direction_10m", now - 3599, now).lastOrNull()?.second
        } else {
            windKmh = d.snap?.windSpeed
            windDir = d.snap?.windDir
        }
        val windTxt = windKmh?.let { fm(it, 0) + " km/h" + (windDir?.let { dd -> " " + Metric.cardinal(dd) } ?: "") } ?: "—"
        val gust = max(d.gustNow, d.hourly("wind_gusts_10m", now - 3599, now + 2 * H).maxOfOrNull { it.second } ?: 0.0)
        val chip2: AChip = when (scn) {
            Scn.DRY -> AChip("UV", d.uvNow?.let { fm(it, 0) } ?: "—", Tile.SUN)
            Scn.ANNOUNCED -> AChip("Cumul 12 h", fm(d.mm12, 1) + " mm", Tile.RAIN)
            Scn.RAIN -> AChip("Rafale", if (gust > 0.0) fm(gust, 0) + " km/h" else "—", Tile.WIND)
            Scn.WINTER -> AChip("Neige", fm(max(d.snowCm12, 0.0), 0) + " cm", Tile.SNOW)
            Scn.HEAT -> AChip("Nuit min", d.frostMin?.let { neg(fm(it, 0)) + "°" } ?: "—", Tile.TEMP)
            Scn.OFFLINE -> AChip("Cumul 12 h", fm(d.mm12, 1) + " mm", Tile.FORECAST)
            Scn.STORM -> AChip("Rafale", if (gust > 0.0) fm(gust, 0) + " km/h" else "—", Tile.WIND)
        }
        val chips = listOf(AChip("Vent", windTxt, Tile.WIND), chip2)

        // ── Prochaines heures
        val precMap = DetailBuilder.hourlyMap(i, "precipitation")
        val snowMap = DetailBuilder.hourlyMap(i, "snowfall")
        val windMap = DetailBuilder.hourlyMap(i, "wind_speed_10m")
        val gustMap = DetailBuilder.hourlyMap(i, "wind_gusts_10m")
        val uvMap = DetailBuilder.hourlyMap(i, "uv_index")
        val hours = d.f?.hours.orEmpty().filter { it.tSec > now }.take(7).map { hp ->
            val t = hp.tSec
            val pr = precMap[t + H]
            val sn = snowMap[t + H]
            val pop = hp.popPct
            val popTxt = if (pop != null && pop >= 20.0) fm(pop, 0) + " %" else ""
            val sub = when (scn) {
                Scn.DRY -> windMap[t]?.let { fm(it, 0) + " km/h" } ?: ""
                Scn.ANNOUNCED, Scn.RAIN -> if (pr != null && pr >= 0.05) fm(pr, 1) + " mm" else popTxt
                Scn.WINTER -> when {
                    sn != null && sn >= 0.1 -> fm(sn, 1) + " cm"
                    pr != null && pr >= 0.05 -> fm(pr, 1) + " mm"
                    else -> ""
                }
                Scn.HEAT -> uvMap[t]?.takeIf { it >= 1.0 }?.let { "UV " + fm(it, 0) } ?: ""
                Scn.STORM -> gustMap[t]?.let { fm(it, 0) + " km/h" } ?: ""
                Scn.OFFLINE -> popTxt
            }
            val (ic, col) = d.wxIcon(hp.code)
            AHour(clock(t).toString() + " h", ic, col, hp.temp?.let { neg(fm(it, 0)) + "°" } ?: "—", sub)
        }

        // ── Cinq jours
        val snowSum = DetailBuilder.daily(i, "snowfall_sum")
        val rainSum = DetailBuilder.daily(i, "precipitation_sum")
        val uvMax = DetailBuilder.daily(i, "uv_index_max")
        val days = d.week()?.days.orEmpty().take(5).mapIndexed { k, w ->
            val sn = DetailBuilder.dayAt(snowSum, i, k)
            val rn = DetailBuilder.dayAt(rainSum, i, k)
            val uv = DetailBuilder.dayAt(uvMax, i, k)
            val sub = when {
                sn != null && sn >= 0.5 -> fm(sn, 0) + " cm"
                rn != null && rn >= 0.2 -> fm(rn, if (rn < 10.0) 1 else 0) + " mm"
                scn == Scn.HEAT && uv != null && uv >= 1.0 -> "UV " + fm(uv, 0)
                else -> ""
            }
            ADay(w.label, w.icon, w.iconColor, neg(w.hi.toString()) + "°", neg(w.lo.toString()) + "°", sub, w.today)
        }

        // ── Qualité de l'air et pollens
        val a = d.f?.air
        val aqiV = a?.number(OmSection.HOURLY, "european_aqi")
        val air = aqiV?.let { v ->
            val word = OmFormat.eaqi(v).unit.removePrefix("·").trim()
            fun pol(name: String, key: String, scale: Double): APol? = a?.number(OmSection.HOURLY, key)?.let { x ->
                val fr = (x / scale).toFloat().coerceIn(0.04f, 1f)
                APol(name, fm(x, 0), fr, (fr * 5.99f).toInt().coerceIn(0, 5))
            }
            AAir(
                v.roundToInt(), word, eaqiBand(v),
                listOfNotNull(pol("PM2.5", "pm2_5", 25.0), pol("PM10", "pm10", 50.0), pol("O₃", "ozone", 120.0), pol("NO₂", "nitrogen_dioxide", 40.0)),
            )
        }
        val pollenNames = mapOf(
            "alder_pollen" to "Aulne", "birch_pollen" to "Bouleau", "grass_pollen" to "Graminées",
            "mugwort_pollen" to "Armoise", "olive_pollen" to "Olivier", "ragweed_pollen" to "Ambroisie",
        )
        val pRows = OmCatalog.POLLEN_VARIABLES.mapNotNull { v ->
            a?.number(OmSection.HOURLY, v)?.let { x -> (pollenNames[v] ?: v) to min(4, d.pollenLevel(x)) }
        }.sortedByDescending { it.second }
        val pMax = pRows.firstOrNull()?.second ?: 0
        val stormPollen = scn == Scn.STORM && pMax >= 2
        val pollen = APollen(
            rows = pRows.take(6),
            summary = when {
                stormPollen -> "Asthme d'orage"
                pMax >= 4 -> "Très élevé"
                pMax == 3 -> "Élevé"
                pMax == 2 -> "Modéré"
                pMax == 1 -> "Faible"
                else -> ""
            },
            summaryLevel = pMax,
            message = when {
                pRows.isEmpty() -> "Hors saison"
                pMax == 0 -> "Aucun pollen"
                else -> null
            },
            storm = stormPollen,
        )

        // ── Module central
        val mod = module(d, i, scn, hero, offline)

        val place = placeName?.substringBefore(',')?.trim().orEmpty()
        val alt = i.altM?.let { Math.round(it).toString() + " m" }.orEmpty()
        return AModel(
            scn, place, alt, status.text, status.warn, d.pill(scn),
            hero.value, hero.feel, lo, hi, cur, offline, chips, hours, days, air, pollen, mod,
        )
    }

    private fun eaqiBand(x: Double): Int = when {
        x <= 20 -> 0
        x <= 40 -> 1
        x <= 60 -> 2
        x <= 80 -> 3
        x <= 100 -> 4
        else -> 5
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
        if (precip) return rainModule(d, i, scn)

        if (scn == Scn.STORM) {
            val s = d.storm()
            val whenTxt = if (d.stormMinutes <= 0) "en cours" else OmFormat.delay(d.stormMinutes)
            val stats = listOf(
                Triple("Impact", whenTxt, s?.whenTxt ?: ""),
                Triple("Pluie", (s?.rate ?: "—") + " mm/h", s?.word ?: ""),
                Triple("Rafales", s?.gust?.removePrefix("raf. ≤ ")?.takeIf { it.isNotBlank() }?.let { "$it km/h" } ?: "—", "prochaines 3 h"),
            )
            return AMod.Storm(stats, d.bins)
        }

        // Courbe de température sur 24 h.
        val n = hero.obs.size + hero.fc.size
        val nowIdx = hero.obs.size - 1
        val nowHour = clock(now)
        val ticks = ArrayList<Pair<Int, String>>()
        for (k in -2..2) {
            val idx = nowIdx + 6 * k
            if (nowIdx >= 0 && idx in 0 until n) ticks.add(idx to ((((nowHour + 6 * k) % 24) + 24) % 24).toString() + " h")
        }
        val maxTxt = d.dayMax?.let { "Max " + neg(fm(it, 0)) + "°" } ?: ""
        return when {
            offline -> AMod.Curve("Prévisions Open-Meteo", "", hero.obs, hero.fc, ticks, null, false, null, true)
            scn == Scn.HEAT -> {
                val uv = d.uv()
                val title = uv?.let { "UV " + fm(it.uv, 0) + (if (it.peak.isNotEmpty()) " · " + it.peak else "") } ?: "Forte chaleur"
                AMod.Curve(title, maxTxt, hero.obs, hero.fc, ticks, 30.0, false, d.uvNow, false)
            }
            scn == Scn.WINTER -> {
                val fr = d.frost()
                val title = if (fr != null) fr.title + " · " + fr.value else "Températures sur 24 h"
                AMod.Curve(title, fr?.sub ?: "", hero.obs, hero.fc, ticks, null, true, null, false)
            }
            else -> {
                val next = DetailBuilder.hourly(i, "precipitation", now + 1, now + 49 * H).firstOrNull { it.second >= 0.1 }
                val title = if (next != null) "Aucune pluie avant " + clock(next.first - H) + " h" else "Aucune pluie sur 48 h"
                AMod.Curve(title, maxTxt, hero.obs, hero.fc, ticks, null, false, null, false)
            }
        }
    }

    private fun rainModule(d: BData, i: DetailInputs, scn: Scn): AMod {
        val now = d.now
        val zone = d.zone
        fun fm(v: Double, n: Int) = d.fm(v, n)
        fun neg(s: String) = d.neg(s)
        fun clock(sec: Long): Int = Instant.ofEpochSecond(sec).atZone(zone).hour
        val winter = scn == Scn.WINTER

        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val sh = DetailBuilder.hourlyMap(i, "snowfall_height")
        val fz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        fun phaseAt(tStart: Long): Int = when (DetailBuilder.precipTone(i.altM, tStart, temps, sh, fz)) {
            Tone.SNOW -> 2
            Tone.ICE -> 1
            else -> 0
        }

        // Limite pluie / neige, interpolée dans le temps.
        val limS = DetailBuilder.limitSeries(i, 14)
        val showLimit = limS.size >= 2 && limS.minOf { it.second } <= (i.altM ?: 800.0) + 1200.0
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

        // Dans l'heure : 12 pas de 5 minutes (mm/h). En hiver, converti en cm de neige d'après le rapport de la première heure.
        val firstPrec = d.prec12.firstOrNull()?.second ?: 0.0
        val firstSnow = d.snowH.firstOrNull()?.second ?: 0.0
        val ratio = if (firstPrec >= 0.1) (firstSnow / firstPrec).coerceIn(0.7, 1.6) else 1.0
        val mPhases = List(12) { phaseAt(((now + it * 300L) / H) * H) }
        val mVals = d.bins.take(12).let { b -> if (winter) b.mapIndexed { k, v -> if (mPhases[k] == 2) v * ratio else v } else b }
        val mCum = d.bins.take(12).sum() / 12.0
        val mCumTxt = if (winter && mPhases.any { it == 2 }) fm(mCum * ratio, 1) + " cm" else fm(mCum, 1) + " mm"
        val minutes = APanel(
            "Dans l'heure", mCumTxt, mVals, mPhases,
            listOf(0.25f to "+15", 0.5f to "+30", 0.75f to "+45", 1f to "+60"),
            if (showLimit) List(12) { limAt(now + it * 300L + 150L) } else null,
            flakeOf(mPhases), max(if (winter) 1.0 else 3.0, mVals.maxOrNull() ?: 0.0),
        )

        // D'ici 12 h : 12 cumuls horaires.
        val snowByEnd = d.snowH.toMap()
        val hPhases = d.prec12.map { phaseAt(it.first - H) }
        val hVals = d.prec12.mapIndexed { k, p -> if (winter && hPhases[k] == 2) (snowByEnd[p.first] ?: p.second) else p.second }
        val hSnowTotal = d.prec12.indices.sumOf { k -> if (hPhases[k] == 2) (snowByEnd[d.prec12[k].first] ?: 0.0) else 0.0 }
        val hCumTxt = if (winter && hSnowTotal >= 0.1) fm(hSnowTotal, 1) + " cm" else fm(d.mm12, 1) + " mm"
        val hTicks = ArrayList<Pair<Float, String>>()
        d.prec12.forEachIndexed { k, p ->
            val h = clock(p.first - H)
            if (h % 3 == 0) hTicks.add(((k + 0.5f) / d.prec12.size) to "$h h")
        }
        val hours = APanel(
            "D'ici 12 h", hCumTxt, hVals, hPhases, hTicks,
            if (showLimit) d.prec12.map { limAt(it.first - H / 2) } else null,
            flakeOf(hPhases), max(if (winter) 1.0 else 3.0, hVals.maxOrNull() ?: 0.0),
        )

        // Titre et information de droite.
        val pop = if (d.probMap.isEmpty() || d.prec12.isEmpty()) null else d.prec12.maxOf { d.probMap[it.first] ?: 0.0 }
        val popTxt = pop?.let { fm(it, 0) + " %" } ?: ""
        val firstWet = d.prec12.firstOrNull { it.second >= 0.1 }
        val title: String
        var right: String
        if (winter) {
            val sn = d.snowH.firstOrNull { it.second >= 0.1 }
            title = when {
                sn != null && (d.rainNow && mPhases[0] == 2 || sn.first - H <= now + 600) -> "Neige en cours"
                sn != null -> "Neige dès " + clock(sn.first - H) + " h"
                firstWet != null -> "Pluie · limite " + (if (showLimit) Math.round(limAt(now) / 50.0) * 50 else 0) + " m"
                else -> "Précipitations"
            }
            right = ""
        } else if (d.rainNow) {
            val word = RainColors.word(RainColors.RATE, max(d.rate, 0.1)).replaceFirstChar { it.uppercase() }
            title = word + " · " + fm(d.rate, 1) + " mm/h"
            right = d.f?.rainEndMin?.takeIf { it in 1..180 }?.let { "fin dans " + OmFormat.delay(it) } ?: popTxt
        } else {
            val nm = d.f?.nextRainMin
            title = when {
                nm != null && nm in 1..90 -> "Pluie dans " + OmFormat.delay(nm)
                firstWet != null -> "Pluie vers " + clock(firstWet.first - H) + " h"
                else -> "Pluie annoncée"
            }
            right = popTxt
        }

        // Pastilles d'hiver et jauge d'altitude.
        var gauge: AGauge? = null
        val chips = ArrayList<String>()
        if (winter) {
            d.snow24()?.let { chips.add(fm(it.total, 0) + " cm / 24 h") }
            d.frost()?.takeIf { it.value.startsWith("−") || it.value.startsWith("-") || it.value.startsWith("0") }
                ?.let { chips.add("Gel " + it.value + " " + it.sub.substringBefore(" ·")) }
            d.snowAlt()?.let {
                gauge = AGauge(it.limit, it.station, max(1200.0, ceil((max(it.limit, it.station) + 300.0) / 300.0) * 300.0))
            }
        }
        return AMod.Rain(title, if (winter) "" else right, minutes, hours, gauge, chips)
    }

    /** Première barre où la phase passe de la pluie à la mêlée ou à la neige ; null s'il pleut déjà en mêlée / neige, ou si rien ne change. */
    private fun flakeOf(ph: List<Int>): Int? {
        if (ph.isEmpty() || ph[0] != 0) return null
        val k = ph.indexOfFirst { it > 0 }
        return if (k > 0) k else null
    }
}
