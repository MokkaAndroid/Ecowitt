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

    /** Clé ajoutée à la réponse quand elle a dû être demandée en plusieurs requêtes plus petites (information, pas une erreur). */
    const val NOTE_KEY = "_note"

    /** Refus qui visent la requête (taille, poids, filtre du serveur) plutôt qu'une variable : on la découpe en requêtes plus petites. */
    private val SPLIT_CODES = setOf(403, 413, 414, 431)

    /** Après un refus, on reste en requêtes découpées pendant 1 h avant de retenter la requête complète. */
    private const val SPLIT_COOLDOWN_MS = 3_600_000L
    private const val CHUNK = 6

    @Volatile
    private var splitUntil = 0L

    @Volatile
    private var splitNote = ""

    /** Variables refusées individuellement par le serveur (« h:nom » / « d:nom ») → fin de l'exclusion (ms). */
    private val refused = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REFUSED_TTL_MS = 3_600_000L

    private fun isRefused(kind: String, name: String, now: Long): Boolean = (refused["$kind:$name"] ?: 0L) > now

    private val BASE_CURRENT = setOf("weather_code", "is_day")
    private val BASE_HOURLY = setOf("temperature_2m", "weather_code", "is_day", "precipitation_probability")
    private val BASE_DAILY = setOf("temperature_2m_max", "temperature_2m_min", "sunrise", "sunset")

    private fun builder(host: String, path: String, loc: Location, withElevation: Boolean, days: Int = 2): Uri.Builder {
        val b = Uri.Builder()
            .scheme("https")
            .authority(host)
            .appendEncodedPath(path)
            // Open-Meteo ne reçoit qu'une position arrondie à 0,01° (~1 km) : la position exacte reste chiffrée sur le téléphone.
            .appendQueryParameter("latitude", "%.2f".format(java.util.Locale.ROOT, Math.round(loc.latitude * 100.0) / 100.0))
            .appendQueryParameter("longitude", "%.2f".format(java.util.Locale.ROOT, Math.round(loc.longitude * 100.0) / 100.0))
            .appendQueryParameter("timezone", "auto")
            .appendQueryParameter("timeformat", "unixtime")
        if (days > 0) b.appendQueryParameter("forecast_days", days.toString())
        if (withElevation) {
            loc.elevation?.let { b.appendQueryParameter("elevation", "%.0f".format(java.util.Locale.ROOT, it)) }
        }
        return b
    }

    private fun requestForecast(loc: Location, needs: Needs): JSONObject {
        // Sans altitude : valeurs du point de prévision ; la correction d'altitude est faite par [AltitudeCorrection].
        val b = builder("api.open-meteo.com", "v1/forecast", loc, false, needs.days)
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
        val now = System.currentTimeMillis()
        val json = if (needs.hasForecastExtras && now < splitUntil) {
            requestForecastSplit(loc, needs)
        } else {
            try {
                requestForecast(loc, needs)
            } catch (e: OpenMeteoException) {
                when {
                    needs.hasForecastExtras && e.httpCode in SPLIT_CODES -> {
                        splitUntil = now + SPLIT_COOLDOWN_MS
                        splitNote = "requête complète refusée (HTTP ${e.httpCode}), prévisions demandées en plusieurs fois"
                        requestForecastSplit(loc, needs)
                    }
                    e.httpCode == 400 && needs.hasForecastExtras ->
                        requestForecast(loc, Needs.EMPTY.copy(days = needs.days)).also {
                            it.put(FALLBACK_KEY, "Variable refusée par Open-Meteo, infos de base seulement : " + (e.reason ?: "une variable est refusée"))
                        }
                    else -> throw e
                }
            }
        }
        return json.also { it.put(AltitudeCorrection.RAW_KEY, true) }
    }

    /**
     * Mêmes prévisions, en requêtes plus petites : d'abord les variables de base (indispensables), puis les
     * variables supplémentaires par lots de 6. Si un lot est refusé par le serveur (403 / 400), ses variables sont
     * essayées une par une : celles qui sont refusées sont écartées pendant 1 h et nommées dans la note du diagnostic.
     */
    private fun requestForecastSplit(loc: Location, needs: Needs): JSONObject {
        val now = System.currentTimeMillis()
        val hourly = (needs.hourly - BASE_HOURLY).filter { !isRefused("h", it, now) }.sorted().chunked(CHUNK)
        val daily = (needs.daily - BASE_DAILY).filter { !isRefused("d", it, now) }.sorted().chunked(CHUNK)

        val first = builder("api.open-meteo.com", "v1/forecast", loc, false, needs.days)
            .appendQueryParameter("current", (BASE_CURRENT + needs.current).sorted().joinToString(","))
            .appendQueryParameter("hourly", BASE_HOURLY.sorted().joinToString(","))
            .appendQueryParameter("daily", BASE_DAILY.sorted().joinToString(","))
            .appendQueryParameter("minutely_15", "precipitation")
        val merged = getJson(first.build().toString())

        var failed = 0
        var lastError: OpenMeteoException? = null
        val total = maxOf(hourly.size, daily.size)
        for (i in 0 until total) {
            val h = hourly.getOrElse(i) { emptyList() }
            val d = daily.getOrElse(i) { emptyList() }
            try {
                mergeSections(merged, getJson(extraUrl(loc, needs.days, h, d)))
            } catch (e: OpenMeteoException) {
                if (e.httpCode in SPLIT_CODES || e.httpCode == 400) {
                    for (v in h) probe(loc, needs.days, merged, "h", v, now)
                    for (v in d) probe(loc, needs.days, merged, "d", v, now)
                } else {
                    failed++
                    lastError = e
                }
            }
        }

        val active = (needs.hourly.filter { isRefused("h", it, now) } + needs.daily.filter { isRefused("d", it, now) }).distinct().sorted()
        val notes = ArrayList<String>()
        if (splitNote.isNotEmpty()) notes += splitNote
        if (active.isNotEmpty()) notes += "variables refusées par le serveur, ignorées : " + active.joinToString(", ")
        if (notes.isNotEmpty()) merged.put(NOTE_KEY, notes.joinToString(" · "))
        if (failed > 0) {
            merged.put(FALLBACK_KEY, "Prévisions partielles ($failed requête(s) sur $total refusée(s)) : " + (lastError?.message ?: ""))
        }
        return merged
    }

    private fun extraUrl(loc: Location, days: Int, hourly: List<String>, daily: List<String>): String {
        val b = builder("api.open-meteo.com", "v1/forecast", loc, false, days)
        if (hourly.isNotEmpty()) b.appendQueryParameter("hourly", hourly.joinToString(","))
        if (daily.isNotEmpty()) b.appendQueryParameter("daily", daily.joinToString(","))
        return b.build().toString()
    }

    /** Une seule variable : fusionnée si le serveur l'accepte, écartée 1 h s'il la refuse. */
    private fun probe(loc: Location, days: Int, into: JSONObject, kind: String, name: String, now: Long) {
        try {
            val url = extraUrl(loc, days, if (kind == "h") listOf(name) else emptyList(), if (kind == "d") listOf(name) else emptyList())
            mergeSections(into, getJson(url))
        } catch (e: OpenMeteoException) {
            if (e.httpCode in SPLIT_CODES || e.httpCode == 400) refused["$kind:$name"] = now + REFUSED_TTL_MS
        }
    }

    /** Ajoute à `into` les variables de `part` (séries et unités des sections current / hourly / daily). */
    private fun mergeSections(into: JSONObject, part: JSONObject) {
        for (section in listOf("current", "hourly", "daily", "minutely_15")) {
            for (key in listOf(section, section + "_units")) {
                val src = part.optJSONObject(key) ?: continue
                val dst = into.optJSONObject(key) ?: JSONObject().also { into.put(key, it) }
                val names = src.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    if (!dst.has(name)) dst.put(name, src.get(name))
                }
            }
        }
    }

    /**
     * Diagnostic : limite pluie / neige annoncée par plusieurs modèles pour la position, sur les 24 prochaines heures.
     * Une requête par modèle, pour qu'un modèle qui ne fournit pas la variable n'empêche pas les autres.
     */
    fun probeSnowLimit(loc: Location): List<String> {
        val models = listOf(
            "icon_d2" to "ICON-D2 (DWD, 2 km)",
            "icon_eu" to "ICON-EU (DWD, 7 km)",
            "meteoswiss_icon_ch1" to "ICON-CH1 (MétéoSuisse, 1 km)",
            "meteoswiss_icon_ch2" to "ICON-CH2 (MétéoSuisse, 2 km)",
            "best_match" to "Automatique Open-Meteo",
        )
        val now = System.currentTimeMillis() / 1000
        return models.map { (id, label) ->
            try {
                val b = builder("api.open-meteo.com", "v1/forecast", loc, false)
                    .appendQueryParameter("hourly", "snowfall_height,freezing_level_height")
                    .appendQueryParameter("models", id)
                val hourly = getJson(b.build().toString()).optJSONObject("hourly")
                val times = hourly?.optJSONArray("time")
                fun span(name: String): String? {
                    val arr = hourly?.optJSONArray(name) ?: return null
                    val xs = ArrayList<Double>()
                    for (i in 0 until (times?.length() ?: 0)) {
                        val t = times?.optLong(i) ?: continue
                        if (t < now || t > now + 24 * 3600 || arr.isNull(i)) continue
                        xs.add(arr.optDouble(i))
                    }
                    if (xs.isEmpty()) return null
                    val lo = xs.min().toInt()
                    val hi = xs.max().toInt()
                    return if (lo == hi) "$lo m" else "$lo–$hi m"
                }
                val snow = span("snowfall_height")
                val iso = span("freezing_level_height")
                if (snow == null && iso == null) "$label : aucune valeur à ta position"
                else "$label : neige ${snow ?: "indisponible"} · iso 0 °C ${iso ?: "indisponible"}"
            } catch (e: Exception) {
                "$label : ${describe(e)}"
            }
        }
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

    /** Altitude (m) du point de grille d'un modèle pour la position, sans correction d'altitude demandée. */
    fun fetchGridElevation(loc: Location, model: String): Double? {
        val b = builder("api.open-meteo.com", "v1/forecast", loc, false, 1)
            .appendQueryParameter("hourly", "temperature_2m")
            .appendQueryParameter("models", model)
        return getJson(b.build().toString()).optDouble("elevation", Double.NaN).takeIf { !it.isNaN() }
    }

    /** Requête avec une nouvelle tentative après un « trop de requêtes » (429). */
    private fun getJsonRetry(url: String, readMs: Int): JSONObject {
        var attempt = 0
        while (true) {
            try {
                return getJson(url, readMs)
            } catch (e: OpenMeteoException) {
                if (e.httpCode != 429 || attempt >= 2) throw e
                attempt++
                Thread.sleep(5_000L * attempt)
            }
        }
    }

    /** Température et pluie horaires prévues `day` jour(s) à l'avance (API « Previous Runs »), sur `pastDays` jours. */
    fun fetchPreviousDay(loc: Location, model: String, day: Int, pastDays: Int): JSONObject {
        val b = builder("previous-runs-api.open-meteo.com", "v1/forecast", loc, true, 1)
            .appendQueryParameter("past_days", pastDays.toString())
            .appendQueryParameter("hourly", "temperature_2m_previous_day$day,precipitation_previous_day$day")
            .appendQueryParameter("models", model)
        return getJsonRetry(b.build().toString(), 15_000)
    }

    private val RUN_FORMAT: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(java.time.ZoneOffset.UTC)

    /** Température et pluie horaires d'un run précis d'un modèle (API « Single Runs », `runSec` = heure du run, UTC). */
    fun fetchSingleRun(loc: Location, model: String, runSec: Long): JSONObject {
        val b = builder("single-runs-api.open-meteo.com", "v1/forecast", loc, true, 0)
            .appendQueryParameter("run", RUN_FORMAT.format(java.time.Instant.ofEpochSecond(runSec)))
            .appendQueryParameter("hourly", "temperature_2m,precipitation")
            .appendQueryParameter("models", model)
        return getJsonRetry(b.build().toString(), 20_000)
    }

    /**
     * Prévisions passées d'un modèle (API « Previous Runs ») : valeur du dernier run et valeur prévue la veille,
     * pour la température et la pluie horaire, sur les `pastDays` derniers jours. Une requête par modèle.
     */
    fun fetchPreviousRuns(loc: Location, model: String, pastDays: Int): JSONObject {
        val b = builder("previous-runs-api.open-meteo.com", "v1/forecast", loc, true, 1)
            .appendQueryParameter("past_days", pastDays.toString())
            .appendQueryParameter("hourly", "temperature_2m,precipitation,temperature_2m_previous_day1,precipitation_previous_day1")
            .appendQueryParameter("models", model)
        return getJson(b.build().toString())
    }

    private fun getJson(url: String, readMs: Int = 6_000): JSONObject {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 4_000
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            val http = conn.responseCode
            if (http != HttpsURLConnection.HTTP_OK) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                val reason = body
                    ?.let { runCatching { JSONObject(it).optString("reason") }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                    ?.take(160)
                // Réponse qui n'est pas du JSON d'Open-Meteo (page d'un pare-feu, par exemple) : un extrait aide à comprendre.
                val excerpt = if (reason != null) null else body
                    ?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
                    ?.take(90)?.takeIf { it.isNotBlank() }
                val msg = when (http) {
                    400 -> "Open-Meteo a refusé la requête" + (reason?.let { " : $it" } ?: "")
                    429 -> "Open-Meteo : limite de requêtes atteinte"
                    else -> "Open-Meteo indisponible (HTTP $http" + (if (http == 403 || http == 414) ", requête ${url.length} car." else "") + ")" +
                        ((reason ?: excerpt)?.let { " · $it" } ?: "")
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
