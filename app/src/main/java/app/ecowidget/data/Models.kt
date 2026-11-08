package app.ecowidget.data

import androidx.annotation.DrawableRes
import app.ecowidget.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class Credentials(
    val applicationKey: String,
    val apiKey: String,
    val mac: String,
) {
    val isComplete: Boolean
        get() = applicationKey.isNotBlank() && apiKey.isNotBlank() && mac.isNotBlank()

    companion object {
        val EMPTY = Credentials("", "", "")
        private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun normalizeMac(raw: String): String = raw.trim().replace('-', ':').uppercase(Locale.ROOT)
        fun isValidMac(raw: String): Boolean = MAC_REGEX.matches(normalizeMac(raw))
    }
}

/** Position utilisée pour les prévisions Open-Meteo. */
data class Location(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val elevation: Double? = null,
    /** « station » (détectée via Ecowitt) ou « manuel » (recherche d'une ville). */
    val source: String = "manuel",
)

/** Photo des mesures renvoyées par le cloud Ecowitt (unités métriques demandées à l'API). */
data class WeatherSnapshot(
    val observedAtSec: Long?,
    val outdoorTemp: Double?,
    val feelsLike: Double?,
    val dewPoint: Double?,
    val humidity: Double?,
    val indoorTemp: Double?,
    val indoorHumidity: Double?,
    val rainRate: Double?,
    val rainDaily: Double?,
    /** Pluie retenue : détection du capteur, corroborée (débit net ou air humide). */
    val isRaining: Boolean?,
    /** Détection brute du capteur, avant corroboration (peut être un faux positif). */
    val rainDetected: Boolean? = null,
    val windSpeed: Double?,
    val windGust: Double?,
    val windDir: Double?,
    val pressure: Double?,
    val uvi: Double?,
    val solar: Double?,
)

data class SeriesPoint(val tSec: Long, val v: Double)

/** Valeurs calculées à partir de l'historique Ecowitt (24 h). */
data class Derived(
    val tempSeries: List<SeriesPoint>,
    /** Variation de température sur ~1 h (°C). */
    val tempTrend1h: Double?,
    /** Variation de pression sur ~3 h (hPa). */
    val pressTrend3h: Double?,
    val dayMin: Double?,
    val dayMax: Double?,
    /** Pression relative mesurée (jusqu'à ~48 h selon la dernière requête). */
    val pressSeries: List<SeriesPoint> = emptyList(),
    /** Séries supplémentaires de la station (modes à tuiles) : vide si l'API ne les a pas renvoyées. */
    val windSeries: List<SeriesPoint> = emptyList(),
    val gustSeries: List<SeriesPoint> = emptyList(),
    val humSeries: List<SeriesPoint> = emptyList(),
    val dewSeries: List<SeriesPoint> = emptyList(),
    /** Cumul de pluie du jour mesuré par la station (remis à zéro à minuit). */
    val rainDaySeries: List<SeriesPoint> = emptyList(),
    val indoorTempSeries: List<SeriesPoint> = emptyList(),
)

data class MetricValue(
    val value: String,
    val unit: String,
    /** Mis en avant (ex. il pleut). */
    val highlight: Boolean = false,
    /** -1 en baisse, 0 stable, 1 en hausse ; null = pas de tendance connue. */
    val trend: Int? = null,
)

enum class Metric(val label: String, @DrawableRes val icon: Int) {
    OUTDOOR_TEMP("Température", R.drawable.ic_thermometer),
    RAIN_NOW("Pluie en cours", R.drawable.ic_cloud_rain),
    HUMIDITY("Humidité", R.drawable.ic_droplet),
    FEELS_LIKE("Ressenti", R.drawable.ic_thermometer),
    DEW_POINT("Point de rosée", R.drawable.ic_droplet),
    DAY_MIN("Mini du jour", R.drawable.ic_thermometer),
    DAY_MAX("Maxi du jour", R.drawable.ic_thermometer),
    WIND_SPEED("Vent", R.drawable.ic_wind),
    WIND_GUST("Rafales", R.drawable.ic_wind),
    WIND_DIR("Direction du vent", R.drawable.ic_compass),
    PRESSURE("Pression", R.drawable.ic_gauge),
    RAIN_RATE("Intensité de pluie", R.drawable.ic_cloud_rain),
    RAIN_DAILY("Pluie du jour", R.drawable.ic_cloud_rain),
    UV("Indice UV", R.drawable.ic_sun),
    SOLAR("Ensoleillement", R.drawable.ic_sun),
    INDOOR_TEMP("Température intérieure", R.drawable.ic_home),
    INDOOR_HUMIDITY("Humidité intérieure", R.drawable.ic_home);

    fun read(s: WeatherSnapshot, d: Derived? = null): MetricValue = when (this) {
        OUTDOOR_TEMP -> number(s.outdoorTemp, 1, "°C").copy(trend = trendOf(d?.tempTrend1h, 0.2))
        FEELS_LIKE -> number(s.feelsLike, 1, "°C")
        DEW_POINT -> number(s.dewPoint, 1, "°C")
        HUMIDITY -> number(s.humidity, 0, "%")
        DAY_MIN -> number(d?.dayMin, 1, "°C")
        DAY_MAX -> number(d?.dayMax, 1, "°C")
        INDOOR_TEMP -> number(s.indoorTemp, 1, "°C")
        INDOOR_HUMIDITY -> number(s.indoorHumidity, 0, "%")
        WIND_SPEED -> number(s.windSpeed, 0, "km/h")
        WIND_GUST -> number(s.windGust, 0, "km/h")
        WIND_DIR -> s.windDir?.let { MetricValue(cardinal(it), "${it.roundToInt()}°") } ?: MISSING
        PRESSURE -> number(s.pressure, 0, "hPa").copy(trend = trendOf(d?.pressTrend3h, 1.0))
        RAIN_RATE -> number(s.rainRate, 1, "mm/h")
        RAIN_DAILY -> number(s.rainDaily, 1, "mm")
        UV -> number(s.uvi, 1, "")
        SOLAR -> number(s.solar, 0, "W/m²")
        RAIN_NOW -> when (s.isRaining) {
            true -> MetricValue("En cours", s.rainRate?.let { fmt(it, 1) + " mm/h" } ?: "", highlight = true)
            false -> MetricValue("Aucune", "")
            null -> MISSING
        }
    }

    companion object {
        val MISSING = MetricValue("—", "")

        fun fromName(name: String): Metric? = entries.firstOrNull { it.name == name }

        /** Formate un nombre avec la locale du téléphone (virgule en français), sans « -0,0 ». */
        fun fmt(v: Double, decimals: Int): String {
            val threshold = 0.5 * 10.0.pow(-decimals)
            val x = if (abs(v) < threshold) 0.0 else v
            return String.format(Locale.getDefault(), "%.${decimals}f", x)
        }

        fun number(v: Double?, decimals: Int, unit: String): MetricValue =
            if (v == null) MISSING else MetricValue(fmt(v, decimals), unit)

        fun trendOf(delta: Double?, threshold: Double): Int? = when {
            delta == null -> null
            abs(delta) < threshold -> 0
            delta > 0 -> 1
            else -> -1
        }

        private val POINTS = listOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO",
        )

        fun cardinal(deg: Double): String {
            val idx = (((deg % 360) + 360) % 360 / 22.5).roundToInt() % 16
            return POINTS[idx]
        }
    }
}
