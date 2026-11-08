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
    /** Libellé court à enregistrer (ex. la commune d'une adresse) ; null = [label]. */
    val shortLabel: String? = null,
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

    class OpenMeteoException(
        message: String,
        val httpCode: Int = 0,
        /** Explication renvoyée par Open-Meteo en cas de requête refusée (ex. variable inconnue). */
        val reason: String? = null,
    ) : Exception(message)

    /** Clé ajoutée à la réponse quand la requête complète a été refusée et qu'on est reparti sur les variables de base. */
    const val FALLBACK_KEY = "_fallback"

    private val BASE_CURRENT = setOf("weather_code", "is_day")
    private val BASE_HOURLY = setOf("temperature_2m", "weather_code", "is_day", "precipitation_probability")
    private val BASE_DAILY = setOf("temperature_2m_max", "temperature_2m_min", "sunrise", "sunset")

    private fun builder(host: String, path: String, loc: Location, withElevation: Boolean): Uri.Builder {
        val b = Uri.Builder()
            .scheme("https")
            .authority(host)
            .appendEncodedPath(path)
            // Open-Meteo ne reçoit qu'une position arrondie à 0,01° (~1 km) : la position exacte reste chiffrée sur le téléphone.
            .appendQueryParameter("latitude", "%.2f".format(java.util.Locale.ROOT, Math.round(loc.latitude * 100.0) / 100.0))
            .appendQueryParameter("longitude", "%.2f".format(java.util.Locale.ROOT, Math.round(loc.longitude * 100.0) / 100.0))
            .appendQueryParameter("timezone", "auto")
            .appendQueryParameter("timeformat", "unixtime")
            .appendQueryParameter("forecast_days", "2")
        if (withElevation) {
            loc.elevation?.let { b.appendQueryParameter("elevation", "%.0f".format(java.util.Locale.ROOT, it)) }
        }
        return b
    }

    private fun requestForecast(loc: Location, needs: Needs): JSONObject {
        // Sans altitude : valeurs du point de prévision ; la correction d'altitude est faite par [AltitudeCorrection].
        val b = builder("api.open-meteo.com", "v1/forecast", loc, false)
            .appendQueryParameter("current", (BASE_CURRENT + needs.current).sorted().joinToString(","))
            .appendQueryParameter("hourly", (BASE_HOURLY + needs.hourly).sorted().joinToString(","))
            .appendQueryParameter("daily", (BASE_DAILY + needs.daily).sorted().joinToString(","))
            .appendQueryParameter("minutely_15", "precipitation")
        return getJson(b.build().toString())
    }

    /**
     * Prévisions + toutes les variables choisies dans les widgets. Si Open-Meteo refuse la requête (variable
     * inconnue, par exemple une variable personnalisée mal orthographiée), on repart sur les variables de base :
     * le widget reste utilisable et le diagnostic explique le problème.
     */
    fun fetchForecast(loc: Location, needs: Needs): JSONObject {
        val json = try {
            requestForecast(loc, needs)
        } catch (e: OpenMeteoException) {
            if (e.httpCode != 400 || !needs.hasForecastExtras) throw e
            requestForecast(loc, Needs.EMPTY).also { it.put(FALLBACK_KEY, e.reason ?: "une variable est refusée") }
        }
        return json.also { it.put(AltitudeCorrection.RAW_KEY, true) }
    }

    /** Pluie à court terme : modèles AROME de Météo-France (pas de 15 min, horizon 6 h, mise à jour horaire). */
    fun fetchNowcast(loc: Location): JSONObject {
        val b = builder("api.open-meteo.com", "v1/meteofrance", loc, true)
            .appendQueryParameter("minutely_15", "precipitation")
        return getJson(b.build().toString())
    }

    /** Qualité de l'air et pollens (API air-quality, valeurs horaires). */
    fun fetchAir(loc: Location, variables: Set<String>): JSONObject {
        val b = builder("air-quality-api.open-meteo.com", "v1/air-quality", loc, false)
            .appendQueryParameter("hourly", variables.sorted().joinToString(","))
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
                val reason = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.let { JSONObject(it).optString("reason") }
                        ?.takeIf { it.isNotBlank() }
                        ?.take(160)
                }.getOrNull()
                val msg = when (http) {
                    400 -> "Open-Meteo a refusé la requête" + (reason?.let { " : $it" } ?: "")
                    429 -> "Open-Meteo : limite de requêtes atteinte"
                    else -> "Open-Meteo indisponible (HTTP $http)"
                }
                throw OpenMeteoException(msg, http, reason)
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
