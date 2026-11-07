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

/**
 * Client minimal du cloud Ecowitt (API v3, HTTPS uniquement).
 *
 * Sécurité : l'API impose les clés dans l'URL. On ne les journalise jamais et on n'expose
 * jamais le message brut des exceptions réseau (certaines contiennent l'URL complète).
 */
object EcowittClient {

    class ApiException(message: String) : Exception(message)

    fun fetchData(c: Credentials): JSONObject {
        val url = Uri.Builder()
            .scheme("https")
            .authority("api.ecowitt.net")
            .appendEncodedPath("api/v3/device/real_time")
            .appendQueryParameter("application_key", c.applicationKey.trim())
            .appendQueryParameter("api_key", c.apiKey.trim())
            .appendQueryParameter("mac", Credentials.normalizeMac(c.mac))
            .appendQueryParameter("call_back", "all")
            // Unités : °C, hPa, km/h, mm, W/m²
            .appendQueryParameter("temp_unitid", "1")
            .appendQueryParameter("pressure_unitid", "3")
            .appendQueryParameter("wind_speed_unitid", "7")
            .appendQueryParameter("rainfall_unitid", "12")
            .appendQueryParameter("solar_irradiance_unitid", "16")
            .build()
            .toString()

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
