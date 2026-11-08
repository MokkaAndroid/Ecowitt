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
import kotlin.math.abs

/**
 * Recherche d'adresse (France) par le service public de géocodage de l'IGN (Géoplateforme, base adresse nationale).
 * Sans clé, HTTPS uniquement. L'adresse saisie est envoyée à ce service ; seules les coordonnées sont conservées.
 */
object AddressClient {

    class AddressException(message: String) : Exception(message)

    private val COORD = Regex("-?\\d{1,3}[.,]\\d{3,}")

    fun search(query: String): List<Place> {
        val q = query.trim()
        if (q.length < 5) return emptyList()
        val url = Uri.Builder()
            .scheme("https")
            .authority("data.geopf.fr")
            .appendEncodedPath("geocodage/search")
            .appendQueryParameter("q", q)
            .appendQueryParameter("limit", "5")
            .build()
            .toString()

        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            val http = conn.responseCode
            if (http != HttpsURLConnection.HTTP_OK) {
                throw AddressException(
                    if (http == 504) "Recherche trop vague : précise le numéro, la rue et la commune" else "Service d'adresses indisponible (HTTP $http)",
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<Place>()
            for (i in 0 until features.length()) {
                val f = features.optJSONObject(i) ?: continue
                val props = f.optJSONObject("properties") ?: continue
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val label = props.optString("label")
                if (label.isBlank()) continue
                out += Place(
                    name = label,
                    admin1 = null,
                    country = null,
                    latitude = lat,
                    longitude = lon,
                    elevation = null,
                    // Sur le widget et dans les réglages, on n'affiche que la commune, pas l'adresse complète.
                    shortLabel = props.optString("city").ifBlank { null },
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** « 45.5012, 6.3045 », « 45,5012 6,3045 » ou un lien Google Maps : les deux premiers nombres décimaux (latitude, longitude). */
    fun parseCoordinates(text: String): Pair<Double, Double>? {
        val nums = COORD.findAll(text).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()
        if (nums.size < 2) return null
        val lat = nums[0]
        val lon = nums[1]
        return if (abs(lat) <= 90.0 && abs(lon) <= 180.0) lat to lon else null
    }

    fun describe(e: Throwable): String = when (e) {
        is AddressException -> e.message ?: "Erreur du service d'adresses"
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "Réseau indisponible"
        is SSLException -> "Connexion sécurisée impossible"
        is JSONException -> "Réponse illisible"
        else -> "Erreur inattendue"
    }
}
