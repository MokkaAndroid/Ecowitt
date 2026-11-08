package app.ecowidget.data

import org.json.JSONObject

/**
 * Lit l'objet `data` de GET /api/v3/device/real_time (call_back=all).
 * Chaque mesure a la forme { "time": "...", "unit": "...", "value": "12.3" }.
 * Tout est tolérant : un capteur absent donne simplement null.
 */
object SnapshotParser {

    private const val CONFIRM_RATE_MM_H = 0.5
    private const val CONFIRM_HUMIDITY = 65.0

    fun parse(data: JSONObject): WeatherSnapshot {
        val rainRate = data.num("rainfall", "rain_rate") ?: data.num("rainfall_piezo", "rain_rate")
        val piezoState = data.num("rainfall_piezo", "state")
        val humidity = data.num("outdoor", "humidity")
        val detected: Boolean? = when {
            rainRate == null && piezoState == null -> null
            else -> (rainRate ?: 0.0) > 0.0 || piezoState == 1.0
        }
        // Le capteur piézo se déclenche parfois à tort (vent, poussières, insectes) : une détection n'est
        // retenue que si le débit est net ou si l'air est assez humide pour qu'il pleuve vraiment.
        val raining: Boolean? = when (detected) {
            true -> (rainRate ?: 0.0) >= CONFIRM_RATE_MM_H || (humidity ?: 100.0) >= CONFIRM_HUMIDITY
            else -> detected
        }

        return WeatherSnapshot(
            observedAtSec = data.time("outdoor", "temperature") ?: data.time("indoor", "temperature"),
            outdoorTemp = data.num("outdoor", "temperature"),
            feelsLike = data.num("outdoor", "feels_like"),
            dewPoint = data.num("outdoor", "dew_point"),
            humidity = data.num("outdoor", "humidity"),
            indoorTemp = data.num("indoor", "temperature"),
            indoorHumidity = data.num("indoor", "humidity"),
            rainRate = rainRate,
            rainDaily = data.num("rainfall", "daily") ?: data.num("rainfall_piezo", "daily"),
            isRaining = raining,
            rainDetected = detected,
            windSpeed = data.num("wind", "wind_speed"),
            windGust = data.num("wind", "wind_gust"),
            windDir = data.num("wind", "wind_direction"),
            pressure = data.num("pressure", "relative"),
            uvi = data.num("solar_and_uvi", "uvi"),
            solar = data.num("solar_and_uvi", "solar"),
        )
    }

    private fun JSONObject.leaf(vararg path: String): JSONObject? {
        var cur: JSONObject = this
        for (i in 0 until path.size - 1) cur = cur.optJSONObject(path[i]) ?: return null
        return cur.optJSONObject(path.last())
    }

    private fun JSONObject.num(vararg path: String): Double? {
        val v = leaf(*path)?.opt("value")
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.time(vararg path: String): Long? {
        val t = leaf(*path)?.opt("time")
        return when (t) {
            is Number -> t.toLong()
            is String -> t.trim().toLongOrNull()
            else -> null
        }
    }
}
