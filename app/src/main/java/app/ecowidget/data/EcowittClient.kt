package app.ecowidget.data

import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Client minimal du cloud Ecowitt (API v3, HTTPS uniquement).
 *
 * Sécurité : l'API impose les clés dans l'URL. On ne les journalise jamais et on n'expose
 * jamais le message brut des exceptions réseau (certaines contiennent l'URL complète).
 */
object EcowittClient {

    class ApiException(message: String) : Exception(message)

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Mesures en direct (toutes les sondes). */
    fun fetchData(c: Credentials): JSONObject = get(
        path = "api/v3/device/real_time",
        creds = c,
        withMac = true,
        params = mapOf(
            "call_back" to "all",
            // Unités : °C, hPa, km/h, mm, W/m²
            "temp_unitid" to "1",
            "pressure_unitid" to "3",
            "wind_speed_unitid" to "7",
            "rainfall_unitid" to "12",
            "solar_irradiance_unitid" to "16",
        ),
    )

    /**
     * Historique des ~30 dernières heures (température extérieure + pression relative).
     * Résolution 30 min ; si l'API la refuse, on retombe sur la résolution automatique.
     */
    fun fetchHistory(c: Credentials, nowSec: Long): JSONObject {
        val zone = ZoneId.systemDefault()
        // Fenêtre large (-30 h / +2 h) : l'API peut interpréter les dates dans un autre fuseau,
        // les timestamps de la réponse sont absolus et filtrés ensuite.
        val start = DATE_FORMAT.format(Instant.ofEpochSecond(nowSec - 30 * 3600).atZone(zone))
        val end = DATE_FORMAT.format(Instant.ofEpochSecond(nowSec + 2 * 3600).atZone(zone))
        fun call(cycle: String) = get(
            path = "api/v3/device/history",
            creds = c,
            withMac = true,
            params = mapOf(
                "start_date" to start,
                "end_date" to end,
                "cycle_type" to cycle,
                "call_back" to "outdoor,pressure",
                "temp_unitid" to "1",
                "pressure_unitid" to "3",
            ),
        )
        return try {
            call("30min")
        } catch (e: ApiException) {
            call("auto")
        }
    }

    /** Cherche la station dans le compte pour en tirer sa position (latitude / longitude). */
    fun fetchStationLocation(c: Credentials): Location? {
        val data = get(path = "api/v3/device/list", creds = c, withMac = false, params = emptyMap())
        val list = data.optJSONArray("list") ?: return null
        val wanted = Credentials.normalizeMac(c.mac)
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            if (Credentials.normalizeMac(d.optString("mac")) != wanted) continue
            val lat = d.optDouble("latitude", Double.NaN)
            val lon = d.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return null
            val name = d.optString("name").ifBlank { "Ma station" }
            return Location(lat, lon, name, elevation = null, source = "station")
        }
        return null
    }

    private fun get(path: String, creds: Credentials, withMac: Boolean, params: Map<String, String>): JSONObject {
        val b = Uri.Builder()
            .scheme("https")
            .authority("api.ecowitt.net")
            .appendEncodedPath(path)
            .appendQueryParameter("application_key", creds.applicationKey.trim())
            .appendQueryParameter("api_key", creds.apiKey.trim())
        if (withMac) b.appendQueryParameter("mac", Credentials.normalizeMac(creds.mac))
        params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        val url = b.build().toString()

        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 4_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")

            val http = conn.responseCode
            if (http != HttpsURLConnection.HTTP_OK) {
                throw ApiException("Serveur Ecowitt indisponible (HTTP $http)")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val code = json.optInt("code", -1)
            if (code != 0) {
                val msg = json.optString("msg").ifBlank { "erreur inconnue" }
                throw ApiException("Ecowitt : $msg (code $code)")
            }
            return json.optJSONObject("data")?.takeIf { it.length() > 0 }
                ?: throw ApiException("Aucune donnée reçue : vérifie l'adresse MAC")
        } finally {
            conn.disconnect()
        }
    }

    /** Message lisible, sans jamais exposer l'URL ni les clés. */
    fun describe(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Erreur Ecowitt"
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "Réseau indisponible"
        is SSLException -> "Connexion sécurisée impossible"
        is JSONException -> "Réponse Ecowitt illisible"
        else -> "Erreur inattendue"
    }
}
