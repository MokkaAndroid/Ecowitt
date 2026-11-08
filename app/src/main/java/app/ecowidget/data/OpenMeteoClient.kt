package app.ecowidget.data

import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** Un résultat de recherche de ville (API de géocodage Open-Meteo). */
data class Place(
    val name: String,
    val admin1: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double?,
) {
    val label: String
        get() = listOfNotNull(name, admin1, country).distinct().joinToString(", ")
}

/**
 * Client Open-Meteo (gratuit, sans clé, usage non commercial).
 * Données sous licence CC BY 4.0 : « Weather data by Open-Meteo.com ».
 * Seules les coordonnées (et l'altitude) sont envoyées.
 */
object OpenMeteoClient {

    class OpenMeteoException(message: String) : Exception(message)

    fun fetchForecast(loc: Location): JSONObject {
        val b = Uri.Builder()
            .scheme("https")
            .authority("api.open-meteo.com")
            .appendEncodedPath("v1/forecast")
            .appendQueryParameter("latitude", "%.4f".format(java.util.Locale.ROOT, loc.latitude))
            .appendQueryParameter("longitude", "%.4f".format(java.util.Locale.ROOT, loc.longitude))
            .appendQueryParameter("current", "weather_code,is_day,cloud_cover")
            .appendQueryParameter(
                "hourly",
                "temperature_2m,weather_code,is_day,precipitation_probability,freezing_level_height,snow_depth",
            )
            .appendQueryParameter("minutely_15", "precipitation")
            .appendQueryParameter(
                "daily",
                "temperature_2m_max,temperature_2m_min,precipitation_sum,snowfall_sum," +
                    "precipitation_probability_max,uv_index_max,sunrise,sunset," +
                    "wind_speed_10m_max,wind_gusts_10m_max",
            )
            .appendQueryParameter("timezone", "auto")
            .appendQueryParameter("timeformat", "unixtime")
            .appendQueryParameter("forecast_days", "2")
        loc.elevation?.let { b.appendQueryParameter("elevation", "%.0f".format(java.util.Locale.ROOT, it)) }
        return getJson(b.build().toString())
    }

    fun search(query: String): List<Place> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val url = Uri.Builder()
            .scheme("https")
            .authority("geocoding-api.open-meteo.com")
            .appendEncodedPath("v1/search")
            .appendQueryParameter("name", q)
            .appendQueryParameter("count", "6")
            .appendQueryParameter("language", "fr")
            .appendQueryParameter("format", "json")
            .build()
            .toString()
        val results = getJson(url).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val lat = r.optDouble("latitude", Double.NaN)
            val lon = r.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            out.add(
                Place(
                    name = r.optString("name"),
                    admin1 = r.optString("admin1").ifBlank { null },
                    country = r.optString("country").ifBlank { null },
                    latitude = lat,
                    longitude = lon,
                    elevation = r.optDouble("elevation", Double.NaN).takeIf { !it.isNaN() },
                ),
            )
        }
        return out
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            val http = conn.responseCode
            if (http != HttpsURLConnection.HTTP_OK) {
                throw OpenMeteoException("Open-Meteo indisponible (HTTP $http)")
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    fun describe(e: Throwable): String = when (e) {
        is OpenMeteoException -> e.message ?: "Erreur Open-Meteo"
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "Réseau indisponible"
        is SSLException -> "Connexion sécurisée impossible"
        is JSONException -> "Réponse Open-Meteo illisible"
        else -> "Erreur inattendue"
    }
}
