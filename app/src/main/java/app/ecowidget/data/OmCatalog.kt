package app.ecowidget.data

import androidx.annotation.DrawableRes
import app.ecowidget.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────────── Modèle du catalogue ─────────────────────────────

enum class OmCategory(val label: String, @DrawableRes val icon: Int) {
    SPECIAL("Messages et raccourcis", R.drawable.ic_cloud_rain),
    TEMP("Température et humidité", R.drawable.ic_thermometer),
    WIND("Vent", R.drawable.ic_wind),
    PRECIP("Précipitations et neige", R.drawable.ic_droplet),
    SKY("Ciel et visibilité", R.drawable.ic_wx_partly),
    PRESSURE("Pression", R.drawable.ic_gauge),
    SUN("Soleil et UV", R.drawable.ic_sun),
    STORM("Orage et stabilité", R.drawable.ic_wx_storm),
    SOIL("Sol et évapotranspiration", R.drawable.ic_home),
    ASTRO("Lever, coucher, durée du jour", R.drawable.ic_sunrise),
    AIR("Qualité de l'air", R.drawable.ic_wx_fog),
    POLLEN("Pollens (Europe, saisonnier)", R.drawable.ic_wx_cloud),
}

enum class OmApi { FORECAST, AIR }

enum class OmSection(val key: String, val code: String) {
    CURRENT("current", "c"),
    HOURLY("hourly", "h"),
    DAILY("daily", "d"),
}

/** Façon de présenter la valeur brute renvoyée par l'API. */
enum class OmKind {
    /** Nombre avec l'unité fournie par l'API. */
    NUM,

    /** Mètres → km. */
    KM,

    /** Mètres → cm (hauteur de neige). */
    CM,

    /** Secondes → « 8 h 30 ». */
    DURATION,

    /** Fraction (0–1) → pourcentage. */
    FRACTION_PCT,

    /** Degrés → point cardinal. */
    DIRECTION,

    /** Horodatage unix → HH:mm. */
    CLOCK,

    /** Code météo WMO → texte. */
    WEATHER,

    /** Mètres avec séparateur de milliers. */
    ALTITUDE,

    /** Indice européen de qualité de l'air → valeur + niveau. */
    EAQI,

    /** Détection automatique (variables personnalisées). */
    AUTO,
}

class OmItem(
    val id: String,
    val category: OmCategory,
    val label: String,
    val shortLabel: String,
    @DrawableRes val icon: Int,
    val api: OmApi = OmApi.FORECAST,
    val section: OmSection = OmSection.HOURLY,
    val variable: String = "",
    val kind: OmKind = OmKind.NUM,
    val decimals: Int = 1,
    /** Infos calculées (pas une variable brute de l'API). */
    val computed: ((Forecast, WeatherSnapshot?) -> MetricValue)? = null,
) {
    fun read(f: Forecast, snap: WeatherSnapshot? = null): MetricValue {
        computed?.let { return it(f, snap) }
        val src = (if (api == OmApi.AIR) f.air else f.raw) ?: return Metric.MISSING
        val x = src.number(section, variable) ?: return Metric.MISSING
        val apiUnit = src.unit(section, variable).orEmpty()
        val unit = if (apiUnit in IGNORED_UNITS) "" else apiUnit
        return when (kind) {
            OmKind.NUM -> Metric.number(x, decimals, unit)
            OmKind.KM -> Metric.number(x / 1000.0, if (x >= 10_000) 0 else 1, "km")
            OmKind.CM -> Metric.number(x * 100.0, 0, "cm")
            OmKind.DURATION -> MetricValue(OmFormat.duration(x), "")
            OmKind.FRACTION_PCT -> Metric.number(x * 100.0, 0, "%")
            OmKind.DIRECTION -> MetricValue(Metric.cardinal(x), "${x.roundToInt()}°")
            OmKind.CLOCK -> MetricValue(OmFormat.hhmm(x.toLong()), "")
            OmKind.WEATHER -> MetricValue(WeatherCodes.label(x.roundToInt(), f.isDay ?: true), "")
            OmKind.ALTITUDE -> MetricValue(String.format(Locale.getDefault(), "%,.0f", x), "m")
            OmKind.EAQI -> OmFormat.eaqi(x)
            OmKind.AUTO -> when (apiUnit) {
                "unixtime" -> MetricValue(OmFormat.hhmm(x.toLong()), "")
                "wmo code" -> MetricValue(WeatherCodes.label(x.roundToInt(), f.isDay ?: true), "")
                "s" -> MetricValue(OmFormat.duration(x), "")
                else -> Metric.number(x, OmFormat.autoDecimals(x), unit)
            }
        }
    }

    private companion object {
        val IGNORED_UNITS = setOf("wmo code", "iso8601", "unixtime", "EAQI", "USAQI")
    }
}

/** Variables à demander à l'API pour couvrir toutes les infos choisies. */
data class Needs(
    val current: Set<String>,
    val hourly: Set<String>,
    val daily: Set<String>,
    val air: Set<String>,
) {
    fun forecastSignature(): String =
        "c:${current.sorted().joinToString(",")}|h:${hourly.sorted().joinToString(",")}|d:${daily.sorted().joinToString(",")}"

    fun airSignature(): String = air.sorted().joinToString(",")

    val hasForecastExtras: Boolean get() = current.isNotEmpty() || hourly.isNotEmpty() || daily.isNotEmpty()

    companion object {
        val EMPTY = Needs(emptySet(), emptySet(), emptySet(), emptySet())
    }
}

// ───────────────────────────── Lecture générique d'une réponse ─────────────────────────────

/** Accès aux valeurs d'une réponse Open-Meteo (timeformat=unixtime) à l'instant présent. */
class RawData(private val json: JSONObject, nowSec: Long) {
    private val hourIdx: Int = lastIndexAtOrBefore(json.optJSONObject("hourly")?.optJSONArray("time"), nowSec)
    private val dayIdx: Int = lastIndexAtOrBefore(json.optJSONObject("daily")?.optJSONArray("time"), nowSec).coerceAtLeast(0)

    fun number(section: OmSection, variable: String): Double? = when (section) {
        OmSection.CURRENT -> json.optJSONObject("current")?.let { c ->
            if (!c.has(variable) || c.isNull(variable)) null else c.optDouble(variable).takeIf { !it.isNaN() }
        }
        OmSection.HOURLY -> json.optJSONObject("hourly")?.optJSONArray(variable)?.dbl(hourIdx)
        OmSection.DAILY -> json.optJSONObject("daily")?.optJSONArray(variable)?.dbl(dayIdx)
    }

    fun unit(section: OmSection, variable: String): String? =
        json.optJSONObject(section.key + "_units")?.optString(variable)?.takeIf { it.isNotBlank() }
}

private fun lastIndexAtOrBefore(times: JSONArray?, nowSec: Long): Int {
    if (times == null) return -1
    var idx = -1
    for (i in 0 until times.length()) {
        if (times.optLong(i) <= nowSec) idx = i else break
    }
    return idx
}

internal object OmFormat {
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun hhmm(sec: Long): String = HHMM.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))

    fun duration(seconds: Double): String {
        val m = (seconds / 60.0).roundToInt()
        return if (m < 60) "$m min" else "${m / 60} h ${String.format(Locale.ROOT, "%02d", m % 60)}"
    }

    /** « 5 min », « 1 h 05 » : délai avant un événement. */
    fun delay(min: Int): String =
        if (min < 60) "$min min" else "${min / 60} h ${String.format(Locale.ROOT, "%02d", min % 60)}"

    /** « 3 h », « 6 h », « 90 min » : durée d'un horizon d'analyse. */
    fun horizon(min: Int): String = when {
        min >= 60 && min % 60 == 0 -> "${min / 60} h"
        min >= 60 -> "${min / 60} h ${min % 60}"
        else -> "$min min"
    }

    fun autoDecimals(x: Double): Int = when {
        abs(x - x.roundToInt()) < 0.005 -> 0
        abs(x) >= 10 -> 1
        else -> 2
    }

    fun eaqi(x: Double): MetricValue {
        val band = when {
            x <= 20 -> "Bon"
            x <= 40 -> "Correct"
            x <= 60 -> "Moyen"
            x <= 80 -> "Médiocre"
            x <= 100 -> "Mauvais"
            else -> "Très mauvais"
        }
        return MetricValue(Metric.fmt(x, 0), "· $band", highlight = x > 60)
    }
}

// ───────────────────────────── Le catalogue ─────────────────────────────

object OmCatalog {

    const val NEXT_HOURS = "NEXT_HOURS"
    const val RAIN_MESSAGE = "RAIN_MESSAGE"
    const val POLLEN_MAX = "POLLEN_MAX"

    /** Espèces de pollens de l'API air-quality : « Pollens » affiche la plus élevée. */
    val POLLEN_VARIABLES = listOf(
        "alder_pollen", "birch_pollen", "grass_pollen", "mugwort_pollen", "olive_pollen", "ragweed_pollen",
    )

    private val VAR_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]{1,63}$")

    fun isValidVariable(name: String): Boolean = VAR_REGEX.matches(name)

    private fun fc(
        section: OmSection,
        variable: String,
        cat: OmCategory,
        label: String,
        short: String,
        @DrawableRes icon: Int,
        kind: OmKind = OmKind.NUM,
        dec: Int = 1,
        id: String = "${section.key}.$variable",
    ) = OmItem(id, cat, label, short, icon, OmApi.FORECAST, section, variable, kind, dec)

    private fun aq(
        variable: String,
        cat: OmCategory,
        label: String,
        short: String,
        @DrawableRes icon: Int,
        kind: OmKind = OmKind.NUM,
        dec: Int = 1,
    ) = OmItem("air.$variable", cat, label, short, icon, OmApi.AIR, OmSection.HOURLY, variable, kind, dec)

    private fun special(
        id: String,
        label: String,
        short: String,
        @DrawableRes icon: Int,
        reader: (Forecast, WeatherSnapshot?) -> MetricValue,
    ) = OmItem(id, OmCategory.SPECIAL, label, short, icon, computed = reader)

    val items: List<OmItem> = buildList {
        val cur = OmSection.CURRENT
        val hr = OmSection.HOURLY
        val day = OmSection.DAILY

        // ── Messages et raccourcis (calculés) ──
        add(
            special(RAIN_MESSAGE, "Message pluie (station + AROME)", "Pluie", R.drawable.ic_cloud_rain) { f, snap ->
                val delay = f.nextRainMin
                when {
                    snap?.isRaining == true -> {
                        val end = f.rainEndMin
                        val text = if (f.hasNowcast && end != null) "Il pleut, fin dans ~${OmFormat.delay(end)}" else "Il pleut"
                        MetricValue(text, "", highlight = true)
                    }
                    !f.hasNowcast -> Metric.MISSING
                    delay == null -> MetricValue("Pas de pluie dans les ${OmFormat.horizon(f.nowcastHorizonMin)}", "")
                    delay <= 5 -> MetricValue("Pluie imminente", "", highlight = true)
                    else -> MetricValue("Pluie dans ${OmFormat.delay(delay)}", "", highlight = delay < 60)
                }
            },
        )
        add(
            special("NEXT_RAIN", "Prochaine pluie (pas de 15 min)", "Prochaine pluie", R.drawable.ic_cloud_rain) { f, _ ->
                val delay = f.nextRainMin
                when {
                    !f.hasNowcast -> Metric.MISSING
                    delay == null -> MetricValue("Aucune", "≤ " + OmFormat.horizon(f.nowcastHorizonMin))
                    delay <= 5 -> MetricValue("En cours", "", highlight = true)
                    else -> MetricValue("dans ${OmFormat.delay(delay)}", "", highlight = delay < 60)
                }
            },
        )
        add(
            special("DAY_RANGE", "Min / max prévus", "Min / max prévus", R.drawable.ic_thermometer) { f, _ ->
                val lo = f.dayMin
                val hi = f.dayMax
                if (lo == null || hi == null) Metric.MISSING
                else MetricValue("${Metric.fmt(lo, 0)}° / ${Metric.fmt(hi, 0)}°", "")
            },
        )
        add(
            special("SUN_TIMES", "Lever · coucher", "Lever · coucher", R.drawable.ic_sunrise) { f, _ ->
                val up = f.sunriseSec
                val down = f.sunsetSec
                if (up == null || down == null) Metric.MISSING
                else MetricValue("${OmFormat.hhmm(up)} · ${OmFormat.hhmm(down)}", "")
            },
        )
        add(special(NEXT_HOURS, "Bandeau des prochaines heures", "Prochaines heures", R.drawable.ic_wx_partly) { _, _ -> Metric.MISSING })

        // ── Température et humidité ──
        val t = OmCategory.TEMP
        add(fc(cur, "temperature_2m", t, "Température prévue (maintenant)", "Température modèle", R.drawable.ic_thermometer))
        add(fc(cur, "apparent_temperature", t, "Ressenti (modèle)", "Ressenti modèle", R.drawable.ic_thermometer))
        add(fc(cur, "relative_humidity_2m", t, "Humidité (modèle)", "Humidité modèle", R.drawable.ic_droplet, dec = 0))
        add(fc(hr, "dew_point_2m", t, "Point de rosée (modèle)", "Point de rosée modèle", R.drawable.ic_droplet))
        add(fc(hr, "wet_bulb_temperature_2m", t, "Température du thermomètre mouillé", "Therm. mouillé", R.drawable.ic_thermometer))
        add(fc(day, "temperature_2m_max", t, "Température maxi du jour (prévue)", "Maxi prévue", R.drawable.ic_thermometer))
        add(fc(day, "temperature_2m_min", t, "Température mini du jour (prévue)", "Mini prévue", R.drawable.ic_thermometer))
        add(fc(day, "apparent_temperature_max", t, "Ressenti maxi du jour", "Ressenti maxi", R.drawable.ic_thermometer))
        add(fc(day, "apparent_temperature_min", t, "Ressenti mini du jour", "Ressenti mini", R.drawable.ic_thermometer))
        add(fc(hr, "vapour_pressure_deficit", t, "Déficit de pression de vapeur", "Déficit vapeur", R.drawable.ic_droplet, dec = 2))

        // ── Vent ──
        val w = OmCategory.WIND
        add(fc(cur, "wind_speed_10m", w, "Vent à 10 m (modèle)", "Vent modèle", R.drawable.ic_wind, dec = 0))
        add(fc(cur, "wind_gusts_10m", w, "Rafales (modèle)", "Rafales modèle", R.drawable.ic_wind, dec = 0))
        add(fc(cur, "wind_direction_10m", w, "Direction du vent (modèle)", "Direction modèle", R.drawable.ic_compass, OmKind.DIRECTION))
        add(fc(day, "wind_speed_10m_max", w, "Vent maxi prévu", "Vent maxi", R.drawable.ic_wind, dec = 0, id = "WIND_MAX"))
        add(fc(day, "wind_gusts_10m_max", w, "Rafales maxi prévues", "Rafales maxi", R.drawable.ic_wind, dec = 0, id = "GUST_MAX"))
        add(fc(day, "wind_direction_10m_dominant", w, "Direction dominante du jour", "Direction dominante", R.drawable.ic_compass, OmKind.DIRECTION))
        add(fc(hr, "wind_speed_80m", w, "Vent à 80 m", "Vent 80 m", R.drawable.ic_wind, dec = 0))

        // ── Précipitations et neige ──
        val p = OmCategory.PRECIP
        add(fc(hr, "precipitation_probability", p, "Risque de pluie (heure)", "Risque de pluie", R.drawable.ic_droplet, dec = 0, id = "PRECIP_PROB"))
        add(fc(day, "precipitation_probability_max", p, "Risque de pluie (jour)", "Risque pluie jour", R.drawable.ic_droplet, dec = 0, id = "PRECIP_PROB_DAY"))
        add(fc(day, "precipitation_sum", p, "Précipitations prévues (jour)", "Pluie prévue", R.drawable.ic_cloud_rain, id = "PRECIP_SUM"))
        add(fc(day, "rain_sum", p, "Pluie prévue (jour, hors averses)", "Pluie (jour)", R.drawable.ic_cloud_rain))
        add(fc(day, "showers_sum", p, "Averses prévues (jour)", "Averses (jour)", R.drawable.ic_cloud_rain))
        add(fc(day, "snowfall_sum", p, "Neige prévue (jour)", "Neige prévue", R.drawable.ic_wx_snow, id = "SNOW_SUM"))
        add(fc(day, "precipitation_hours", p, "Heures de précipitations (jour)", "Heures de pluie", R.drawable.ic_cloud_rain, dec = 0))
        add(fc(hr, "precipitation", p, "Précipitations de l'heure", "Précip. heure", R.drawable.ic_cloud_rain))
        add(fc(cur, "precipitation", p, "Précipitations actuelles (modèle)", "Précip. modèle", R.drawable.ic_cloud_rain))
        add(fc(hr, "snowfall", p, "Neige de l'heure", "Neige heure", R.drawable.ic_wx_snow))
        add(fc(cur, "snowfall", p, "Neige actuelle (modèle)", "Neige modèle", R.drawable.ic_wx_snow))
        add(fc(hr, "snow_depth", p, "Neige au sol (modèle)", "Neige au sol", R.drawable.ic_wx_snow, OmKind.CM, id = "SNOW_DEPTH"))

        // ── Ciel et visibilité ──
        val s = OmCategory.SKY
        add(fc(cur, "weather_code", s, "Condition actuelle (texte)", "Condition", R.drawable.ic_wx_partly, OmKind.WEATHER))
        add(fc(cur, "cloud_cover", s, "Nébulosité totale", "Nébulosité", R.drawable.ic_wx_cloud, dec = 0, id = "CLOUD"))
        add(fc(hr, "cloud_cover_low", s, "Nuages bas", "Nuages bas", R.drawable.ic_wx_cloud, dec = 0))
        add(fc(hr, "cloud_cover_mid", s, "Nuages moyens", "Nuages moyens", R.drawable.ic_wx_cloud, dec = 0))
        add(fc(hr, "cloud_cover_high", s, "Nuages hauts", "Nuages hauts", R.drawable.ic_wx_cloud, dec = 0))
        add(fc(hr, "visibility", s, "Visibilité", "Visibilité", R.drawable.ic_wx_fog, OmKind.KM))

        // ── Pression ──
        val pr = OmCategory.PRESSURE
        add(fc(cur, "pressure_msl", pr, "Pression au niveau de la mer (modèle)", "Pression mer", R.drawable.ic_gauge, dec = 0))
        add(fc(cur, "surface_pressure", pr, "Pression à la station (modèle)", "Pression station", R.drawable.ic_gauge, dec = 0))

        // ── Soleil et UV ──
        val su = OmCategory.SUN
        add(fc(day, "uv_index_max", su, "UV maxi du jour", "UV maxi", R.drawable.ic_sun, id = "UV_MAX"))
        add(fc(hr, "uv_index", su, "Indice UV (heure)", "UV heure", R.drawable.ic_sun))
        add(fc(day, "uv_index_clear_sky_max", su, "UV maxi par ciel dégagé", "UV maxi dégagé", R.drawable.ic_sun))
        add(fc(hr, "shortwave_radiation", su, "Rayonnement solaire global", "Rayonnement", R.drawable.ic_sun, dec = 0))
        add(fc(hr, "direct_radiation", su, "Rayonnement direct", "Rayonnement direct", R.drawable.ic_sun, dec = 0))
        add(fc(hr, "diffuse_radiation", su, "Rayonnement diffus", "Rayonnement diffus", R.drawable.ic_sun, dec = 0))
        add(fc(hr, "direct_normal_irradiance", su, "Irradiance directe normale (DNI)", "DNI", R.drawable.ic_sun, dec = 0))
        add(fc(day, "shortwave_radiation_sum", su, "Énergie solaire du jour", "Énergie solaire", R.drawable.ic_sun))
        add(fc(hr, "sunshine_duration", su, "Ensoleillement de l'heure", "Soleil (heure)", R.drawable.ic_sun, OmKind.DURATION))
        add(fc(day, "sunshine_duration", su, "Ensoleillement du jour", "Soleil (jour)", R.drawable.ic_sun, OmKind.DURATION))

        // ── Orage et stabilité ──
        val st = OmCategory.STORM
        add(fc(hr, "cape", st, "CAPE (énergie convective)", "CAPE", R.drawable.ic_wx_storm, dec = 0))
        add(fc(hr, "lifted_index", st, "Indice de soulèvement", "Lifted index", R.drawable.ic_wx_storm))
        add(fc(hr, "convective_inhibition", st, "CIN (inhibition convective)", "CIN", R.drawable.ic_wx_storm, dec = 0))
        add(fc(hr, "lightning_potential", st, "Potentiel de foudre", "Foudre", R.drawable.ic_wx_storm, dec = 0))
        add(fc(hr, "freezing_level_height", st, "Isotherme 0 °C", "Isotherme 0 °C", R.drawable.ic_gauge, OmKind.ALTITUDE, id = "FREEZING_LEVEL"))
        add(fc(hr, "boundary_layer_height", st, "Hauteur de couche limite", "Couche limite", R.drawable.ic_gauge, OmKind.ALTITUDE))

        // ── Sol et évapotranspiration ──
        val so = OmCategory.SOIL
        add(fc(hr, "soil_temperature_0cm", so, "Température du sol (surface)", "Sol surface", R.drawable.ic_home))
        add(fc(hr, "soil_temperature_6cm", so, "Température du sol (6 cm)", "Sol 6 cm", R.drawable.ic_home))
        add(fc(hr, "soil_temperature_18cm", so, "Température du sol (18 cm)", "Sol 18 cm", R.drawable.ic_home))
        add(fc(hr, "soil_temperature_54cm", so, "Température du sol (54 cm)", "Sol 54 cm", R.drawable.ic_home))
        add(fc(hr, "soil_moisture_0_to_1cm", so, "Humidité du sol (0–1 cm)", "Humid. sol 0–1", R.drawable.ic_droplet, OmKind.FRACTION_PCT))
        add(fc(hr, "soil_moisture_1_to_3cm", so, "Humidité du sol (1–3 cm)", "Humid. sol 1–3", R.drawable.ic_droplet, OmKind.FRACTION_PCT))
        add(fc(hr, "soil_moisture_3_to_9cm", so, "Humidité du sol (3–9 cm)", "Humid. sol 3–9", R.drawable.ic_droplet, OmKind.FRACTION_PCT))
        add(fc(hr, "soil_moisture_9_to_27cm", so, "Humidité du sol (9–27 cm)", "Humid. sol 9–27", R.drawable.ic_droplet, OmKind.FRACTION_PCT))
        add(fc(hr, "soil_moisture_27_to_81cm", so, "Humidité du sol (27–81 cm)", "Humid. sol 27–81", R.drawable.ic_droplet, OmKind.FRACTION_PCT))
        add(fc(day, "et0_fao_evapotranspiration", so, "Évapotranspiration de référence (jour)", "ET0 jour", R.drawable.ic_droplet))
        add(fc(hr, "evapotranspiration", so, "Évapotranspiration (heure)", "Évapotransp.", R.drawable.ic_droplet, dec = 2))

        // ── Lever, coucher, durée du jour ──
        val a = OmCategory.ASTRO
        add(fc(day, "sunrise", a, "Lever du soleil", "Lever", R.drawable.ic_sunrise, OmKind.CLOCK))
        add(fc(day, "sunset", a, "Coucher du soleil", "Coucher", R.drawable.ic_sunrise, OmKind.CLOCK))
        add(fc(day, "daylight_duration", a, "Durée du jour", "Durée du jour", R.drawable.ic_sun, OmKind.DURATION))

        // ── Qualité de l'air (API air-quality) ──
        val q = OmCategory.AIR
        add(aq("european_aqi", q, "Qualité de l'air (indice européen)", "Qualité de l'air", R.drawable.ic_wx_fog, OmKind.EAQI))
        add(aq("us_aqi", q, "Qualité de l'air (indice US)", "Indice US", R.drawable.ic_wx_fog, dec = 0))
        add(aq("pm2_5", q, "Particules fines PM2,5", "PM2,5", R.drawable.ic_wx_fog))
        add(aq("pm10", q, "Particules PM10", "PM10", R.drawable.ic_wx_fog))
        add(aq("ozone", q, "Ozone (O₃)", "Ozone", R.drawable.ic_wx_fog, dec = 0))
        add(aq("nitrogen_dioxide", q, "Dioxyde d'azote (NO₂)", "NO₂", R.drawable.ic_wx_fog))
        add(aq("sulphur_dioxide", q, "Dioxyde de soufre (SO₂)", "SO₂", R.drawable.ic_wx_fog))
        add(aq("carbon_monoxide", q, "Monoxyde de carbone (CO)", "CO", R.drawable.ic_wx_fog, dec = 0))
        add(aq("dust", q, "Poussières désertiques", "Poussières", R.drawable.ic_wx_fog, dec = 0))
        add(aq("aerosol_optical_depth", q, "Épaisseur optique des aérosols", "Aérosols", R.drawable.ic_wx_fog, dec = 2))

        // ── Pollens (API air-quality) ──
        val po = OmCategory.POLLEN
        add(aq("alder_pollen", po, "Pollen d'aulne", "Aulne", R.drawable.ic_wx_cloud, dec = 0))
        add(aq("birch_pollen", po, "Pollen de bouleau", "Bouleau", R.drawable.ic_wx_cloud, dec = 0))
        add(aq("grass_pollen", po, "Pollen de graminées", "Graminées", R.drawable.ic_wx_cloud, dec = 0))
        add(aq("mugwort_pollen", po, "Pollen d'armoise", "Armoise", R.drawable.ic_wx_cloud, dec = 0))
        add(aq("olive_pollen", po, "Pollen d'olivier", "Olivier", R.drawable.ic_wx_cloud, dec = 0))
        add(aq("ragweed_pollen", po, "Pollen d'ambroisie", "Ambroisie", R.drawable.ic_wx_cloud, dec = 0))
        add(
            OmItem(
                POLLEN_MAX, po, "Pollens (le plus élevé des six)", "Pollens", R.drawable.ic_wx_cloud,
                computed = { f, _ ->
                    val levels = POLLEN_VARIABLES.mapNotNull { f.air?.number(OmSection.HOURLY, it) }
                    if (levels.isEmpty()) Metric.MISSING else Metric.number(levels.max(), 0, "gr/m³")
                },
            ),
        )
    }

    private val index: Map<String, OmItem> = items.associateBy { it.id }

    val byCategory: Map<OmCategory, List<OmItem>> = OmCategory.entries.associateWith { c -> items.filter { it.category == c } }

    fun byId(id: String): OmItem? = index[id] ?: parseCustom(id)

    // ── Variables personnalisées : « custom:<f|a>:<c|h|d>:<variable> » ──

    fun customId(api: OmApi, section: OmSection, variable: String): String {
        val sec = if (api == OmApi.AIR) OmSection.HOURLY else section
        return "custom:${if (api == OmApi.AIR) "a" else "f"}:${sec.code}:${variable.trim()}"
    }

    private fun parseCustom(id: String): OmItem? {
        val p = id.split(':')
        if (p.size != 4 || p[0] != "custom") return null
        val api = when (p[1]) {
            "f" -> OmApi.FORECAST
            "a" -> OmApi.AIR
            else -> return null
        }
        val section = OmSection.entries.firstOrNull { it.code == p[2] } ?: return null
        val variable = p[3]
        if (!isValidVariable(variable)) return null
        if (api == OmApi.AIR && section != OmSection.HOURLY) return null
        val pretty = variable.replace('_', ' ')
        return OmItem(
            id = id,
            category = OmCategory.SPECIAL,
            label = "$pretty (perso)",
            shortLabel = pretty,
            icon = R.drawable.ic_gauge,
            api = api,
            section = section,
            variable = variable,
            kind = OmKind.AUTO,
        )
    }

    /** Variables brutes à demander pour la liste d'identifiants donnée (les infos calculées n'en demandent pas). */
    fun needs(ids: Collection<String>): Needs {
        val c = HashSet<String>()
        val h = HashSet<String>()
        val d = HashSet<String>()
        val air = HashSet<String>()
        for (id in ids) {
            val item = byId(id) ?: continue
            if (id == POLLEN_MAX) {
                air.addAll(POLLEN_VARIABLES)
                continue
            }
            if (item.computed != null) continue
            when (item.api) {
                OmApi.AIR -> air.add(item.variable)
                OmApi.FORECAST -> when (item.section) {
                    OmSection.CURRENT -> c.add(item.variable)
                    OmSection.HOURLY -> h.add(item.variable)
                    OmSection.DAILY -> d.add(item.variable)
                }
            }
        }
        return Needs(c, h, d, air)
    }

    /** Recherche insensible aux accents et à la casse dans le libellé, la catégorie et le nom de variable. */
    fun search(query: String, exclude: Collection<String>): List<OmItem> {
        val terms = fold(query).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return items.filter { item ->
            item.id !in exclude && fold(item.label + " " + item.category.label + " " + item.variable).let { hay ->
                terms.all { hay.contains(it) }
            }
        }
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace('_', ' ')
}
