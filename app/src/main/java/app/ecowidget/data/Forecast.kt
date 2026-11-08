package app.ecowidget.data

import androidx.annotation.DrawableRes
import app.ecowidget.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class HourPoint(
    val tSec: Long,
    val temp: Double?,
    val code: Int?,
    val isDay: Boolean,
    val popPct: Double?,
)

/** Prévisions Open-Meteo utiles au widget. */
data class Forecast(
    val code: Int?,
    val isDay: Boolean?,
    val cloudCover: Double?,
    val dayMax: Double?,
    val dayMin: Double?,
    val popNow: Double?,
    val popDayMax: Double?,
    val precipSum: Double?,
    val snowSum: Double?,
    val uvMax: Double?,
    val sunriseSec: Long?,
    val sunsetSec: Long?,
    val windMax: Double?,
    val gustMax: Double?,
    val freezingLevel: Double?,
    val snowDepthM: Double?,
    /** Minutes avant la prochaine pluie (0 = en cours) ; null = rien dans les 3 prochaines heures. */
    val nextRainMin: Int?,
    /** false si le modèle ne fournit pas de pas de 15 min pour cette position. */
    val hasNowcast: Boolean,
    val hours: List<HourPoint>,
)

/** Correspondance des codes météo WMO utilisés par Open-Meteo. */
object WeatherCodes {
    fun label(code: Int?, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "Dégagé" else "Ciel clair"
        1 -> "Plutôt dégagé"
        2 -> "Éclaircies"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        51, 53, 55 -> "Bruine"
        56, 57 -> "Bruine verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie"
        65 -> "Forte pluie"
        66, 67 -> "Pluie verglaçante"
        71 -> "Neige faible"
        73 -> "Neige"
        75 -> "Forte neige"
        77 -> "Grésil"
        80, 81 -> "Averses"
        82 -> "Fortes averses"
        85, 86 -> "Averses de neige"
        95 -> "Orage"
        96, 99 -> "Orage et grêle"
        else -> "—"
    }

    @DrawableRes
    fun icon(code: Int?, isDay: Boolean = true): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.ic_sun else R.drawable.ic_wx_moon
        2 -> R.drawable.ic_wx_partly
        3 -> R.drawable.ic_wx_cloud
        45, 48 -> R.drawable.ic_wx_fog
        51, 53, 55, 56, 57 -> R.drawable.ic_wx_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_cloud_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_wx_snow
        95, 96, 99 -> R.drawable.ic_wx_storm
        else -> R.drawable.ic_wx_cloud
    }
}

/** Lit la réponse de /v1/forecast (timeformat=unixtime, timezone=auto). */
object ForecastParser {

    fun parse(json: JSONObject, nowSec: Long): Forecast {
        val current = json.optJSONObject("current")
        val hourly = json.optJSONObject("hourly")
        val daily = json.optJSONObject("daily")
        val minutely = json.optJSONObject("minutely_15")

        // --- Horaire : position de l'heure en cours ---
        val hTimes = hourly?.optJSONArray("time")
        var hi = -1
        if (hTimes != null) {
            for (i in 0 until hTimes.length()) if (hTimes.optLong(i) <= nowSec) hi = i else break
        }
        fun hAt(name: String, i: Int): Double? = hourly?.optJSONArray(name)?.dbl(i)

        val hours = ArrayList<HourPoint>()
        if (hTimes != null) {
            for (i in 0 until hTimes.length()) {
                val t = hTimes.optLong(i)
                if (t <= nowSec) continue
                if (hours.size >= 8) break
                hours.add(
                    HourPoint(
                        tSec = t,
                        temp = hAt("temperature_2m", i),
                        code = hAt("weather_code", i)?.roundToInt(),
                        isDay = (hAt("is_day", i) ?: 1.0) >= 0.5,
                        popPct = hAt("precipitation_probability", i),
                    ),
                )
            }
        }

        // --- Pluie à court terme (pas de 15 min) ---
        var nextRain: Int? = null
        val mTimes = minutely?.optJSONArray("time")
        val mPrecip = minutely?.optJSONArray("precipitation")
        val hasNowcast = mTimes != null && mPrecip != null && mTimes.length() > 0
        if (hasNowcast && mTimes != null && mPrecip != null) {
            var scanned = 0
            for (i in 0 until mTimes.length()) {
                val end = mTimes.optLong(i)
                if (end <= nowSec) continue // créneau déjà terminé
                if (scanned >= 12) break // 12 × 15 min = 3 h
                scanned++
                val mm = mPrecip.dbl(i) ?: 0.0
                if (mm >= 0.1) {
                    val startsIn = ((end - 900 - nowSec) / 60).toInt().coerceAtLeast(0)
                    nextRain = startsIn
                    break
                }
            }
        }

        // --- Journalier (jour 0 = aujourd'hui) ---
        fun dAt(name: String): Double? = daily?.optJSONArray(name)?.dbl(0)
        val sunrise = daily?.optJSONArray("sunrise")?.dbl(0)?.toLong()
        val sunset = daily?.optJSONArray("sunset")?.dbl(0)?.toLong()

        return Forecast(
            code = current?.optDoubleOrNull("weather_code")?.roundToInt(),
            isDay = current?.optDoubleOrNull("is_day")?.let { it >= 0.5 },
            cloudCover = current?.optDoubleOrNull("cloud_cover"),
            dayMax = dAt("temperature_2m_max"),
            dayMin = dAt("temperature_2m_min"),
            popNow = if (hi >= 0) hAt("precipitation_probability", hi) else null,
            popDayMax = dAt("precipitation_probability_max"),
            precipSum = dAt("precipitation_sum"),
            snowSum = dAt("snowfall_sum"),
            uvMax = dAt("uv_index_max"),
            sunriseSec = sunrise,
            sunsetSec = sunset,
            windMax = dAt("wind_speed_10m_max"),
            gustMax = dAt("wind_gusts_10m_max"),
            freezingLevel = if (hi >= 0) hAt("freezing_level_height", hi) else null,
            snowDepthM = if (hi >= 0) hAt("snow_depth", hi) else null,
            nextRainMin = nextRain,
            hasNowcast = hasNowcast,
            hours = hours,
        )
    }

    private fun JSONArray.dbl(i: Int): Double? =
        if (i < 0 || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }
}

/** Mesures de prévision affichables dans le bloc du haut du widget. */
enum class OmMetric(val label: String, @DrawableRes val icon: Int) {
    DAY_RANGE("Min / max prévus", R.drawable.ic_thermometer),
    NEXT_RAIN("Prochaine pluie", R.drawable.ic_cloud_rain),
    PRECIP_PROB("Risque de pluie (heure)", R.drawable.ic_droplet),
    PRECIP_PROB_DAY("Risque de pluie (jour)", R.drawable.ic_droplet),
    PRECIP_SUM("Pluie prévue (jour)", R.drawable.ic_cloud_rain),
    SNOW_SUM("Neige prévue (jour)", R.drawable.ic_wx_snow),
    UV_MAX("UV maxi", R.drawable.ic_sun),
    SUN_TIMES("Lever · coucher", R.drawable.ic_sunrise),
    WIND_MAX("Vent maxi prévu", R.drawable.ic_wind),
    GUST_MAX("Rafales maxi prévues", R.drawable.ic_wind),
    CLOUD("Nébulosité", R.drawable.ic_wx_cloud),
    FREEZING_LEVEL("Isotherme 0 °C", R.drawable.ic_gauge),
    SNOW_DEPTH("Neige au sol (modèle)", R.drawable.ic_wx_snow),
    /** Bandeau horizontal des prochaines heures (affiché si la hauteur le permet). */
    NEXT_HOURS("Prochaines heures", R.drawable.ic_wx_partly);

    fun read(f: Forecast): MetricValue = when (this) {
        DAY_RANGE -> if (f.dayMin == null || f.dayMax == null) Metric.MISSING
        else MetricValue("${Metric.fmt(f.dayMin, 0)}° / ${Metric.fmt(f.dayMax, 0)}°", "")
        NEXT_RAIN -> when {
            !f.hasNowcast -> Metric.MISSING
            f.nextRainMin == null -> MetricValue("Aucune", "3 h")
            f.nextRainMin <= 5 -> MetricValue("En cours", "", highlight = true)
            f.nextRainMin < 60 -> MetricValue("dans ${f.nextRainMin} min", "", highlight = true)
            else -> MetricValue("dans ${f.nextRainMin / 60} h ${"%02d".format(f.nextRainMin % 60)}", "")
        }
        PRECIP_PROB -> Metric.number(f.popNow, 0, "%")
        PRECIP_PROB_DAY -> Metric.number(f.popDayMax, 0, "%")
        PRECIP_SUM -> Metric.number(f.precipSum, 1, "mm")
        SNOW_SUM -> Metric.number(f.snowSum, 1, "cm")
        UV_MAX -> Metric.number(f.uvMax, 1, "")
        SUN_TIMES -> if (f.sunriseSec == null || f.sunsetSec == null) Metric.MISSING
        else MetricValue("${hhmm(f.sunriseSec)} · ${hhmm(f.sunsetSec)}", "")
        WIND_MAX -> Metric.number(f.windMax, 0, "km/h")
        GUST_MAX -> Metric.number(f.gustMax, 0, "km/h")
        CLOUD -> Metric.number(f.cloudCover, 0, "%")
        FREEZING_LEVEL -> f.freezingLevel?.let {
            MetricValue(String.format(java.util.Locale.getDefault(), "%,.0f", it), "m")
        } ?: Metric.MISSING
        SNOW_DEPTH -> f.snowDepthM?.let { Metric.number(it * 100.0, 0, "cm") } ?: Metric.MISSING
        NEXT_HOURS -> Metric.MISSING
    }

    /** Libellé court affiché sous la valeur dans le widget. */
    val shortLabel: String
        get() = when (this) {
            DAY_RANGE -> "Min / max prévus"
            NEXT_RAIN -> "Prochaine pluie"
            PRECIP_PROB -> "Risque de pluie"
            PRECIP_PROB_DAY -> "Risque pluie jour"
            PRECIP_SUM -> "Pluie prévue"
            SNOW_SUM -> "Neige prévue"
            UV_MAX -> "UV maxi"
            SUN_TIMES -> "Lever · coucher"
            WIND_MAX -> "Vent maxi"
            GUST_MAX -> "Rafales maxi"
            CLOUD -> "Nébulosité"
            FREEZING_LEVEL -> "Isotherme 0 °C"
            SNOW_DEPTH -> "Neige au sol"
            NEXT_HOURS -> "Prochaines heures"
        }

    companion object {
        fun fromName(name: String): OmMetric? = entries.firstOrNull { it.name == name }

        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        fun hhmm(sec: Long): String =
            HHMM.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))
    }
}
