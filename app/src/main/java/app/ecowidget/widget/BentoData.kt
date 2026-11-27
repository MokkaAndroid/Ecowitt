package app.ecowidget.widget

import app.ecowidget.R
import app.ecowidget.data.DetailBuilder
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Metric
import app.ecowidget.data.OmCatalog
import app.ecowidget.data.OmFormat
import app.ecowidget.data.OmSection
import app.ecowidget.data.RainColors
import app.ecowidget.data.Tile
import app.ecowidget.data.Tone
import app.ecowidget.data.WeatherCodes
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val H = 3600L

/** Une tuile prête à poser : fiche ouverte au toucher + données de dessin. */
internal class Spec(val kind: BKind, val tile: Tile?, val tab: Int, val desc: String, val d: Any?)

/**
 * Tout ce qu'il faut savoir de la situation (mesures de la station + prévisions) pour choisir
 * les tuiles et remplir leurs données. Chaque méthode renvoie null quand la donnée manque.
 */
internal class BData(val i: DetailInputs, val ageMin: Int?) {
    val snap = i.snap
    val f = i.forecast
    val d = i.derived
    val now: Long = i.nowSec
    val zone = i.zone

    fun fm(v: Double, dec: Int): String = Metric.fmt(v, dec)

    /** Signe moins typographique. */
    fun neg(s: String): String = s.replace('-', '−')

    private fun signed(v: Double, dec: Int, unit: String = ""): String {
        val t = fm(v, dec)
        val plus = v > 0 && t.trim('0', '.', ',').isNotEmpty()
        return neg((if (plus) "+" else "") + t + unit)
    }

    fun hourly(name: String, from: Long, to: Long): List<Pair<Long, Double>> = DetailBuilder.hourly(i, name, from, to)

    private fun hourLabel(sec: Long): String = Instant.ofEpochSecond(sec).atZone(zone).hour.toString() + " h"

    fun hh(sec: Long): String = String.format(Locale.ROOT, "%02d", Instant.ofEpochSecond(sec).atZone(zone).hour)

    private fun sample(v: List<Double>, n: Int): List<Double> {
        if (v.size <= n) return v
        return List(n) { k -> v[(k * (v.size - 1.0) / (n - 1)).roundToInt()] }
    }

    // ───────────────────────────── Situation ─────────────────────────────

    val temp: Double? = snap?.outdoorTemp
    val bins: List<Double> = DetailBuilder.rainBins(f, snap?.rainRate)
    val peak: Double = max(bins.maxOrNull() ?: 0.0, snap?.rainRate ?: 0.0)
    val rate: Double = max(bins.firstOrNull() ?: 0.0, snap?.rainRate ?: 0.0)
    val rainNow: Boolean = snap?.isRaining == true || rate >= 0.1
    val soon: Boolean = rainNow || bins.any { it >= 0.1 }

    // Les cumuls horaires d'Open-Meteo portent l'heure de FIN de la période : la barre de l'heure en cours est datée de la prochaine heure pleine.
    val prec12: List<Pair<Long, Double>> = hourly("precipitation", now + 1, now + 13 * H).take(12)
    val probMap: Map<Long, Double> = DetailBuilder.hourlyMap(i, "precipitation_probability")
    val mm12: Double = prec12.sumOf { it.second }
    val snowH: List<Pair<Long, Double>> = hourly("snowfall", now + 1, now + 25 * H)
    val snowCm12: Double = snowH.take(12).sumOf { it.second }
    private val snowLike12: Boolean = run {
        val temps = DetailBuilder.hourlyMap(i, "temperature_2m")
        val sh = DetailBuilder.hourlyMap(i, "snowfall_height")
        val fz = DetailBuilder.hourlyMap(i, "freezing_level_height")
        prec12.any { (t, mm) -> mm >= 0.1 && DetailBuilder.precipTone(i.altM, t, temps, sh, fz) == Tone.SNOW }
    }

    val tempSeries: List<Pair<Long, Double>> = hourly("temperature_2m", now - 3599, now + 14 * H)
    val fcTemps: List<Pair<Long, Double>> = tempSeries.filter { it.first > now }
    val frostMin: Double? = fcTemps.take(14).minOfOrNull { it.second }

    val uvNow: Double? = run {
        val fc = hourly("uv_index", now - 3599, now).lastOrNull()?.second
        val st = snap?.uvi
        if (fc == null) st else if (st == null) fc else max(fc, st)
    }
    private val uvToday: List<Pair<Long, Double>> = DetailBuilder.midnight(i).let { m -> hourly("uv_index", m, m + 24 * H - 1) }
    val uvPeakSec: Long? = uvToday.maxByOrNull { it.second }?.takeIf { it.second >= 1.0 }?.first
    val uvDay: Boolean = (uvNow ?: 0.0) >= 0.5 || hourly("uv_index", now, now + 3 * H).any { it.second >= 1.5 }

    private fun stormCode(c: Int?): Boolean = c == 95 || c == 96 || c == 99
    val stormMin: Int? = if (stormCode(f?.code)) {
        0
    } else {
        f?.hours?.firstOrNull { stormCode(it.code) }?.let { max(0, ((it.tSec - now) / 60).toInt()) }
    }
    val gustNow: Double = snap?.windGust ?: 0.0
    val isStorm: Boolean = (stormMin != null && stormMin <= 120) || (rate >= 7.5 && gustNow >= 40.0)

    /** Minutes avant l'orage / la pluie d'orage (0 : sur place). */
    val stormMinutes: Int = if (rainNow) 0 else (f?.nextRainMin?.takeIf { it <= 90 } ?: stormMin ?: 0)

    val dayMax: Double? = d?.dayMax ?: f?.dayMax

    /** Le scénario que dicte la situation, avant tout lissage dans le temps. */
    fun wanted(): Scn {
        if (ageMin != null && ageMin >= 30) return Scn.OFFLINE
        if (isStorm) return Scn.STORM
        val t = temp
        val coldPrecip = t != null && t <= 1.0 && (soon || mm12 >= 0.3)
        val snowy = (snowLike12 || snowCm12 >= 0.3) && (t == null || t <= 4.0)
        if (coldPrecip || snowy) return Scn.WINTER
        if (soon) return Scn.RAIN
        if (mm12 >= 0.5) return Scn.ANNOUNCED
        val frost = frostMin != null && frostMin <= 0.5 && (t == null || t <= 6.0)
        if (frost) return Scn.WINTER
        if ((t != null && t >= 28.0) || (uvNow ?: 0.0) >= 6.0) return Scn.HEAT
        return Scn.DRY
    }

    // ───────────────────────────── Héros ─────────────────────────────

    fun hero(scn: Scn): TdHero? {
        val s = snap ?: return null
        val tint = when (scn) {
            Scn.OFFLINE -> BPal.H_OFF
            Scn.STORM -> BPal.H_STORM
            Scn.HEAT -> BPal.H_HEAT
            Scn.WINTER -> BPal.H_SNOW
            Scn.RAIN -> BPal.H_RAIN
            else -> if (f?.isDay == false) BPal.H_NIGHT else BPal.H_FAIR
        }
        val curve = when (scn) {
            Scn.OFFLINE -> 0xFFB8C2CE
            Scn.STORM -> 0xFFC8B8FF
            Scn.HEAT -> 0xFFFFC48A
            Scn.WINTER -> 0xFFBFE4F8
            Scn.RAIN -> 0xFF9DB8FF
            else -> if (f?.isDay == false) 0xFF9DB8FF else 0xFF7FE0D2
        }.toInt()
        if (scn == Scn.OFFLINE) {
            val raw = hourly("temperature_2m", now - 12 * H, now + 12 * H)
            val cur = raw.lastOrNull { it.first <= now }?.second
            val obs = raw.filter { it.first <= now }.map { it.second }
            val fc = raw.filter { it.first > now }.map { it.second }
            val feel = hourly("apparent_temperature", now - 3599, now).lastOrNull()?.second
            return TdHero(
                label = "Prévision · Open-Meteo",
                value = cur?.let { neg(fm(it, 1)) } ?: "—", unit = "°", colorTemp = null,
                feel = feel?.let { neg(fm(it, 1)) + "°" },
                stats = listOf("Min" to degOrDash(f?.dayMin), "Max" to degOrDash(f?.dayMax)),
                trendUp = null, trendText = null, obs = obs, fc = fc, tint = tint, curve = curve,
                zero = false, below = null, forecastOnly = true,
            )
        }

        val obsRaw = d?.tempSeries.orEmpty().filter { it.tSec >= now - 12 * H }.map { it.v }
        var obs = sample(obsRaw, 13)
        if (obs.isEmpty() && s.outdoorTemp != null) obs = listOf(s.outdoorTemp)
        val fcNow = tempSeries.lastOrNull { it.first <= now }?.second
        val bias = if (s.outdoorTemp != null && fcNow != null) (s.outdoorTemp - fcNow).coerceIn(-6.0, 6.0) else 0.0
        val fc = fcTemps.take(12).map { it.second + bias }
        val all = obs + fc
        val zero = all.isNotEmpty() && all.min() < 0.0 && all.max() > 0.0
        val tr = Metric.trendOf(d?.tempTrend1h, 0.2)
        return TdHero(
            label = "Extérieur",
            value = s.outdoorTemp?.let { neg(fm(it, 1)) } ?: "—", unit = "°", colorTemp = s.outdoorTemp,
            feel = s.feelsLike?.let { neg(fm(it, 1)) + "°" },
            stats = listOf("Min" to degOrDash(d?.dayMin), "Max" to degOrDash(d?.dayMax)),
            trendUp = if (tr == 1) true else if (tr == -1) false else null,
            trendText = if (tr == 1 || tr == -1) d?.tempTrend1h?.let { signed(it, 1, "°/h") } else null,
            obs = obs, fc = fc, tint = tint, curve = curve,
            zero = zero, below = if (zero && scn == Scn.WINTER) bAlpha(BPal.ICE_BLUE, 0.2f) else null,
            forecastOnly = false,
        )
    }

    private fun degOrDash(v: Double?): String = if (v == null) "—" else neg(fm(v, 1)) + "°"

    // ───────────────────────────── Tuiles 1×1 ─────────────────────────────

    fun wind(): TdWind? {
        val s = snap ?: return null
        val v = s.windSpeed ?: return null
        return TdWind(v, s.windGust, s.windDir)
    }

    fun hum(): TdHum? {
        val s = snap ?: return null
        val h = s.humidity ?: return null
        return TdHum(h, s.dewPoint)
    }

    private fun pressSeries(): List<Double> =
        sample(d?.pressSeries.orEmpty().filter { it.tSec >= now - 24 * H }.map { it.v }, 13)

    fun press(): TdPress? {
        val s = snap ?: return null
        val p = s.pressure ?: return null
        return TdPress(p, d?.pressTrend3h, pressSeries())
    }

    fun uv(): TdUv? {
        if (!uvDay) return null
        val u = uvNow ?: return null
        return TdUv(u, uvPeakSec?.let { "pic " + DetailBuilder.hm(it, zone) } ?: "", snap?.solar)
    }

    fun air(ozoneFirst: Boolean): TdAir? {
        val a = f?.air ?: return null
        val aqi = a.number(OmSection.HOURLY, "european_aqi")
        val o3 = a.number(OmSection.HOURLY, "ozone")
        fun bandIdx(x: Double): Int = when {
            x <= 20 -> 0
            x <= 40 -> 1
            x <= 60 -> 2
            x <= 80 -> 3
            else -> 4
        }
        if (ozoneFirst && o3 != null && o3 >= 100.0) {
            val x = aqi ?: (o3 / 2.0)
            val word = OmFormat.eaqi(x).unit.removePrefix("·").trim()
            return TdAir("Ozone", fm(x, 0), word, "O₃ " + fm(o3, 0) + " µg", (x / 100.0).toFloat(), BPal.SCALE5[bandIdx(x)])
        }
        if (aqi == null) return null
        val word = OmFormat.eaqi(aqi).unit.removePrefix("·").trim()
        return TdAir("Air", fm(aqi, 0), word, "indice AQI", (aqi / 100.0).toFloat(), BPal.SCALE5[bandIdx(aqi)])
    }

    fun pollenLevel(g: Double): Int = when {
        g < 1.0 -> 0
        g < 10.0 -> 1
        g < 30.0 -> 2
        g < 80.0 -> 3
        g < 200.0 -> 4
        else -> 5
    }

    fun pollen(): TdPollen? {
        val a = f?.air ?: return null
        val names = mapOf(
            "alder_pollen" to "Aulne", "birch_pollen" to "Bouleau", "grass_pollen" to "Graminées",
            "mugwort_pollen" to "Armoise", "olive_pollen" to "Olivier", "ragweed_pollen" to "Ambroisie",
        )
        val rows = OmCatalog.POLLEN_VARIABLES.mapNotNull { v ->
            a.number(OmSection.HOURLY, v)?.let { (names[v] ?: v) to pollenLevel(it) }
        }.sortedByDescending { it.second }.take(4)
        if (rows.isEmpty() || rows.first().second < 1) return null
        return TdPollen(rows)
    }

    fun home(): TdHome? {
        val s = snap ?: return null
        val t = s.indoorTemp ?: return null
        return TdHome(t, s.indoorHumidity)
    }

    // ───────────────────────────── Prévisions ─────────────────────────────

    fun wxIcon(code: Int?): Pair<Int, Int> {
        val col = when (code) {
            0, 1 -> 0xFFFFD166
            2 -> 0xFFFFD9A0
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> 0xFF7FB8FF
            71, 73, 75, 77, 85, 86 -> 0xFFCFE5F2
            95, 96, 99 -> 0xFFC8B8FF
            else -> 0xFFB9C6D4
        }.toInt()
        return WeatherCodes.icon(code, true) to col
    }

    fun tomorrow(): TdTom? {
        val hi = DetailBuilder.dayAt(DetailBuilder.daily(i, "temperature_2m_max"), i, 1) ?: return null
        val lo = DetailBuilder.dayAt(DetailBuilder.daily(i, "temperature_2m_min"), i, 1) ?: return null
        val code = DetailBuilder.dayAt(DetailBuilder.daily(i, "weather_code"), i, 1)?.roundToInt()
        val uv = DetailBuilder.dayAt(DetailBuilder.daily(i, "uv_index_max"), i, 1)
        val rain = DetailBuilder.dayAt(DetailBuilder.daily(i, "precipitation_sum"), i, 1)
        val snow = DetailBuilder.dayAt(DetailBuilder.daily(i, "snowfall_sum"), i, 1)
        val m = DetailBuilder.midnight(i) + 24 * H
        val wind = hourly("wind_speed_10m", m, m + 24 * H - 1).maxOfOrNull { it.second }
        val l1 = listOfNotNull(uv?.takeIf { it >= 1.0 }?.let { "UV " + fm(it, 0) }, wind?.let { "vent " + fm(it, 0) }).joinToString(" · ")
        val l2 = when {
            snow != null && snow >= 0.5 -> "neige " + fm(snow, 0) + " cm"
            rain != null -> fm(rain, if (rain < 10.0) 1 else 0) + " mm"
            else -> ""
        }
        val (ic, col) = wxIcon(code)
        return TdTom(ic, col, hi, lo, WeatherCodes.label(code, true), l1, l2)
    }

    fun week(): TdWeek? {
        val tmx = DetailBuilder.daily(i, "temperature_2m_max")
        val tmn = DetailBuilder.daily(i, "temperature_2m_min")
        val codes = DetailBuilder.daily(i, "weather_code")
        val today = tmx.indexOfLast { it.first <= now }
        if (today < 0) return null
        val days = ArrayList<WeekDay>()
        for (k in 0 until 7) {
            val t = tmx.getOrNull(today + k) ?: break
            val hi = t.second ?: break
            val lo = tmn.getOrNull(today + k)?.second ?: break
            val (ic, col) = wxIcon(codes.getOrNull(today + k)?.second?.roundToInt())
            val label = if (k == 0) {
                "Auj."
            } else {
                Instant.ofEpochSecond(t.first + 12 * H).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRENCH)
                    .trimEnd('.').replaceFirstChar { it.uppercase() }
            }
            days.add(WeekDay(label, ic, col, hi.roundToInt(), lo.roundToInt(), k == 0))
        }
        return if (days.size >= 5) TdWeek(days) else null
    }

    // ───────────────────────────── Pluie, neige, gel ─────────────────────────────

    fun rain60(storm: Boolean): TdRain60? {
        if (bins.isEmpty()) return null
        val shown = if (rate >= 0.05) rate else peak
        val word = if (shown >= 0.1) RainColors.word(RainColors.RATE, shown) else ""
        val endTxt = if (rainNow) {
            f?.rainEndMin?.takeIf { it in 1..60 }?.let { "fin dans $it min" }
        } else {
            f?.nextRainMin?.takeIf { it in 1..60 }?.let { "débute dans $it min" }
        }
        return TdRain60(bins, shown, word, endTxt, storm)
    }

    fun rain12(): TdRain12? {
        if (prec12.size < 6) return null
        val mm = prec12.map { it.second }
        val prob = prec12.map { probMap[it.first] ?: 0.0 }
        val hours = prec12.map { hh(it.first - H) }
        val firstWet = prec12.firstOrNull { it.second >= 0.1 }
        val lastWet = prec12.lastOrNull { it.second >= 0.1 }
        val startShort = when {
            rainNow -> "en cours"
            firstWet != null -> "vers " + DetailBuilder.hm(max(firstWet.first - H, now), zone)
            else -> "—"
        }
        val end = if (lastWet != null) "≈ " + DetailBuilder.hm(lastWet.first, zone) else "—"
        val risk = if (probMap.isEmpty()) "—" else fm(prob.max(), 0) + " %"
        return TdRain12(mm, prob, hours, startShort, fm(mm12, 1) + " mm", end, risk, mm12, startShort)
    }

    fun rate(): TdRate? {
        val s = snap ?: return null
        if (!rainNow) return null
        val word = RainColors.word(RainColors.RATE, max(rate, 0.1))
        val day = s.rainDaily
        val since = d?.rainDaySeries.orEmpty()
            .firstOrNull { it.v > 0.05 && DetailBuilder.sameDay(it.tSec, now, zone) }
            ?.let { "depuis " + DetailBuilder.hm(it.tSec, zone) } ?: "aujourd'hui"
        return TdRate(max(rate, 0.1), word, if (day != null) fm(day, 1) + " mm" else "—", since)
    }

    fun snow24(): TdSnow24? {
        if (snowH.isEmpty()) return null
        val buckets = DoubleArray(8)
        snowH.forEachIndexed { k, p -> if (k < 24) buckets[k / 3] += p.second }
        val total = buckets.sum()
        if (total < 0.1) return null
        var best = 0
        for (k in buckets.indices) if (buckets[k] > buckets[best]) best = k
        val start = snowH.first().first - H
        val sub = "pic vers " + hourLabel(start + best * 3 * H + 3600)
        val axis = listOf(0L, 8L, 16L, 24L).map { hourLabel(now + it * H) }
        return TdSnow24(buckets.toList(), total, sub, axis)
    }

    fun snowAlt(): TdSnowAlt? {
        val alt = i.altM ?: return null
        val lim = DetailBuilder.limitSeries(i, 24).firstOrNull()?.second ?: return null
        val iso = hourly("freezing_level_height", now - 3599, now).lastOrNull()?.second
        val limit = Math.round(lim / 50.0) * 50.0
        val top = max(2500.0, ceil((maxOf(limit, alt, iso ?: 0.0) + 200.0) / 500.0) * 500.0)
        return TdSnowAlt(limit, alt, iso, if (limit <= alt) "neige ici" else "pluie ici", top)
    }

    fun frost(): TdFrost? {
        val cur = tempSeries.lastOrNull { it.first <= now }?.second ?: return null
        val bias = if (temp != null) (temp - cur).coerceIn(-6.0, 6.0) else 0.0
        val future = fcTemps.take(12).map { it.first to it.second + bias }
        if (future.isEmpty()) return null
        val min = future.minByOrNull { it.second } ?: return null
        if (min.second > 3.0) return null
        val temps = listOf(temp ?: (cur + bias)) + future.map { it.second }
        val below = future.count { it.second < 0.0 }
        val at = hourLabel(min.first)
        return TdFrost(
            temps = temps,
            title = if (min.second < 0.0) "Gel prévu" else "Minimum 12 h",
            value = neg(fm(min.second, 0)) + "°",
            sub = if (below > 0) "à $at · $below h sous 0°" else "à $at",
            xl = listOf("maint.", "+6 h", "+12 h"),
        )
    }

    // ───────────────────────────── Hors ligne, orage ─────────────────────────────

    fun sinceText(): String {
        val m = ageMin ?: 0
        return if (m < 60) "$m min" else "${m / 60} h " + String.format(Locale.ROOT, "%02d", m % 60)
    }

    fun offline(): TdOffline? {
        val s = snap ?: return null
        val obs = s.observedAtSec ?: return null
        return TdOffline(OmFormat.hhmm(obs), sinceText())
    }

    fun storm(): TdStorm? {
        val pk = max(peak, rate)
        val gust = max(gustNow, hourly("wind_gusts_10m", now + 1, now + 3 * H).maxOfOrNull { it.second } ?: 0.0)
        val whenTxt = if (stormMinutes <= 0) "en cours" else "vers " + DetailBuilder.hm(now + stormMinutes * 60L, zone)
        return TdStorm(
            minutes = stormMinutes,
            whenTxt = whenTxt,
            word = if (pk >= 0.1) RainColors.word(RainColors.RATE, pk) else "orage",
            rate = if (pk >= 0.1) fm(pk, 1) else "—",
            gust = if (gust > 0.0) "raf. ≤ " + fm(gust, 0) else "",
        )
    }

    fun gusts(): TdGusts? {
        val g = hourly("wind_gusts_10m", now + 1, now + 13 * H).take(12)
        if (g.size < 6) return null
        val vals = g.map { it.second }
        return TdGusts(vals, g.map { hh(it.first - H) }, "≤ " + fm(vals.max(), 0) + " km/h")
    }

    // ───────────────────────────── Pastille d'en-tête ─────────────────────────────

    fun pill(scn: Scn): BentoPill? {
        fun rainTint(): Int = if (peak > 0.0) RainColors.hue(RainColors.RATE, peak).c else BPal.RAIN_DEFAULT
        fun cap(s: String): String = s.replaceFirstChar { it.uppercase() }
        return when (scn) {
            Scn.OFFLINE -> BentoPill("Hors ligne · " + sinceText(), R.drawable.ic_wifi_off, BPal.WARN)
            Scn.STORM -> BentoPill(
                if (stormMinutes <= 0) "Orage en cours" else "Orage · dans " + OmFormat.delay(stormMinutes),
                R.drawable.ic_wx_storm, BPal.STORM_PURPLE,
            )
            Scn.WINTER -> {
                val a = DetailBuilder.alert(i)
                if (a != null && a.rain) {
                    BentoPill(a.text, if (a.text.startsWith("Neige")) R.drawable.ic_wx_snow else R.drawable.ic_cloud_rain, BPal.SNOW_ICE)
                } else {
                    val m = frostMin
                    if (m != null) BentoPill("Gel · " + neg(fm(m, 0)) + "° prévu", R.drawable.ic_flake, BPal.SNOW_ICE) else null
                }
            }
            Scn.RAIN -> if (rainNow) {
                BentoPill(cap(RainColors.word(RainColors.RATE, max(rate, 0.1))) + " · " + fm(rate, 1) + " mm/h", R.drawable.ic_cloud_rain, rainTint())
            } else {
                DetailBuilder.alert(i)?.let { BentoPill(it.text, R.drawable.ic_cloud_rain, rainTint()) }
            }
            Scn.ANNOUNCED -> DetailBuilder.alert(i)?.let { BentoPill(it.text, R.drawable.ic_cloud_rain, rainTint()) }
            Scn.HEAT -> {
                val t = temp
                val mx = dayMax
                if (t != null && t >= 28.0 && mx != null) {
                    BentoPill("Chaleur · " + fm(mx, 0) + "° ce jour", R.drawable.ic_sun, BPal.HEAT)
                } else {
                    uvNow?.let { BentoPill("UV élevé · " + fm(it, 0), R.drawable.ic_sun, BPal.HEAT) }
                }
            }
            Scn.DRY -> DetailBuilder.alert(i)?.let {
                if (it.rain) BentoPill(it.text, R.drawable.ic_cloud_rain, BPal.RAIN_DEFAULT) else BentoPill(it.text, R.drawable.ic_check, BPal.GREEN)
            }
        }
    }

    // ───────────────────────────── Fabrique de tuiles ─────────────────────────────

    fun spec(kind: BKind, sh: Shape, scn: Scn): Spec? = when (kind) {
        BKind.HERO -> hero(scn)?.let { Spec(kind, Tile.TEMP, 0, "Température extérieure : détail", it) }
        BKind.WIND -> wind()?.let { Spec(kind, Tile.WIND, 0, "Vent : détail", it) }
        BKind.HUM -> hum()?.let { Spec(kind, Tile.HUM, 0, "Humidité : détail", it) }
        BKind.PRESS -> press()?.let { Spec(kind, Tile.PRESS, 0, "Pression : détail", it) }
        BKind.UV -> uv()?.let { Spec(kind, Tile.SUN, 0, "Soleil et UV : détail", it) }
        BKind.AIR -> air(scn == Scn.HEAT)?.let { Spec(kind, Tile.AIR, 0, "Qualité de l'air : détail", it) }
        BKind.POLLEN -> pollen()?.let { Spec(kind, Tile.AIR, 0, "Pollens : détail", it) }
        BKind.HOME -> home()?.let { Spec(kind, Tile.HUM, 0, "Intérieur : détail", it) }
        BKind.TOMORROW -> tomorrow()?.let { Spec(kind, Tile.FORECAST, 1, "Demain : prévisions", it) }
        BKind.WEEK -> week()?.let { Spec(kind, Tile.FORECAST, 1, "Prévisions sur 7 jours", it) }
        BKind.RAIN60 -> rain60(scn == Scn.STORM)?.let { Spec(kind, Tile.RAIN, 1, "Pluie : prochaines 60 minutes", it) }
        BKind.RAIN12 -> rain12()?.let { Spec(kind, Tile.RAIN, 2, "Pluie : prochaines 12 heures", it) }
        BKind.RATE -> rate()?.let { Spec(kind, Tile.RAIN, 0, "Intensité de la pluie : détail", it) }
        BKind.SNOW24 -> snow24()?.let { Spec(kind, Tile.SNOW, 0, "Neige : prochaines 24 heures", it) }
        BKind.SNOW_ALT -> snowAlt()?.let {
            if (sh.r >= 2) Spec(BKind.SNOW_ALT, Tile.SNOW, 0, "Limite pluie neige : détail", it)
            else Spec(BKind.SNOW_LIM, Tile.SNOW, 0, "Limite pluie neige : détail", it)
        }
        BKind.SNOW_LIM -> snowAlt()?.let { Spec(kind, Tile.SNOW, 0, "Limite pluie neige : détail", it) }
        BKind.FROST -> frost()?.let { Spec(kind, Tile.FORECAST, 0, "Gel : prévisions", it) }
        BKind.OFFLINE -> offline()?.let { Spec(kind, null, 0, "Station hors ligne", it) }
        BKind.STORM -> storm()?.let { Spec(kind, Tile.RAIN, 1, "Orage : détail", it) }
        BKind.GUSTS -> gusts()?.let { Spec(kind, Tile.WIND, 0, "Rafales : prochaines 12 heures", it) }
        BKind.HUM_MINI -> hum()?.let { Spec(kind, Tile.HUM, 0, "Humidité : détail", TdMiniHum(it.hum)) }
        BKind.PRESS_MINI -> press()?.let {
            val dl = it.delta
            Spec(kind, Tile.PRESS, 0, "Pression : détail", TdMiniPress(it.p, if (dl == null || abs(dl) < 0.5) null else dl > 0))
        }
        BKind.SHORT -> null
    }
}
