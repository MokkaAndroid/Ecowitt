package app.ecowidget.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Client du portail d'API Météo-France (portail-api.meteofrance.fr).
 *
 * Pour l'instant : obtention d'un jeton OAuth2 à partir de l'« Application ID » et lecture des
 * catalogues WCS (GetCapabilities) pour vérifier quels services (AROME-PI, PIAF…) sont accessibles.
 *
 * Sécurité : l'Application ID et le jeton ne partent que vers portail-api.meteofrance.fr (jeton)
 * et public-api.meteofrance.fr (données), en HTTPS, sans redirection. Ils ne sont jamais journalisés
 * ni inclus dans un message d'erreur.
 */
object MeteoFranceClient {

    class MfException(message: String) : Exception(message)

    /** Une ligne du compte rendu de test. */
    data class TestLine(val text: String, val ok: Boolean)

    private const val TOKEN_URL = "https://portail-api.meteofrance.fr/token"
    // Hôtes autorisés pour les données (le jeton n'est jamais envoyé ailleurs).
    private val API_HOSTS = setOf("public-api.meteofrance.fr", "api.meteofrance.fr")
    private const val CAPS_QUERY = "service=WCS&version=2.0.1&language=eng"

    // PIAF : serveur « pro » affiché par le portail ; confirmé fonctionnel avec un compte gratuit (08/11/2026).
    private const val PIAF_PRO_BASE = "https://api.meteofrance.fr/pro/piaf/1.0/wcs/MF-NWP-HIGHRES-PIAF-001-FRANCE-WCS/GetCapabilities"

    private const val MAX_CAPS_BYTES = 12 * 1024 * 1024
    private val APP_ID_FORMAT = Regex("^[A-Za-z0-9+/=_-]{20,512}$")
    private val JWT_FORMAT = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
    // Commande curl copiée depuis le portail : on en extrait la chaîne qui suit « Basic ».
    private val BASIC_IN_TEXT = Regex("Basic\\s+([A-Za-z0-9+/=_-]{20,})")
    private val COVERAGE_ID = Regex("<(?:\\w+:)?CoverageId>([^<]+)</(?:\\w+:)?CoverageId>")
    // Identifiant d'une couche : PARAMETRE___AAAA-MM-JJTHH.MM.SSZ (run) + éventuellement _PT15M (durée de cumul).
    private val ID_PARTS = Regex("^(.+)___(\\d{4}-\\d{2}-\\d{2}T\\d{2}\\.\\d{2}\\.\\d{2}Z)(?:_(PT[0-9HMS]+))?$")
    private val DURATION = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$")
    private val COEFFICIENTS = Regex("<(?:\\w+:)?coefficients[^>]*>([^<]+)</(?:\\w+:)?coefficients>")
    private val BEGIN_POS = Regex("<(?:\\w+:)?beginPosition[^>]*>([^<]+)<")
    private val END_POS = Regex("<(?:\\w+:)?endPosition[^>]*>([^<]+)<")
    private val AXIS_LABELS = Regex("axisLabels=\"([^\"]+)\"")
    private val EXCEPTION_TEXT = Regex("<(?:\\w+:)?ExceptionText>([^<]{1,200})")
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    /** Accepte aussi la commande curl complète, « Basic xxx » / « Bearer xxx » ou un texte entouré de guillemets. */
    fun normalizeAppId(raw: String): String {
        val t = raw.trim()
        BASIC_IN_TEXT.find(t)?.let { return it.groupValues[1] }
        return t.trim('"', '\'').removePrefix("Bearer ").removePrefix("bearer ").trim()
    }

    fun isValidAppId(raw: String): Boolean = APP_ID_FORMAT.matches(normalizeAppId(raw))

    /** Jeton déjà généré sur le portail (valable 1 h) : utilisable tel quel pour un test rapide. */
    fun isJwt(raw: String): Boolean = JWT_FORMAT.matches(normalizeAppId(raw))

    // ───────────────────────────── Jeton ─────────────────────────────

    data class Token(val value: String, val expiresAtMs: Long)

    fun fetchToken(appId: String): Token {
        val id = normalizeAppId(appId)
        if (!APP_ID_FORMAT.matches(id)) throw MfException("Identifiant d'application invalide")
        val conn = URL(TOKEN_URL).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Basic $id")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray(Charsets.UTF_8)) }

            val http = conn.responseCode
            if (http != HttpsURLConnection.HTTP_OK) {
                throw MfException(
                    when (http) {
                        400, 401 -> "Identifiant d'application refusé par le portail (HTTP $http)"
                        429 -> "Limite de requêtes atteinte, réessaie dans une minute"
                        else -> "Portail Météo-France indisponible (HTTP $http)"
                    },
                )
            }
            val json = JSONObject(readLimited(conn.inputStream, 64 * 1024))
            val token = json.optString("access_token")
            if (token.isBlank()) throw MfException("Le portail n'a pas fourni de jeton")
            val ttlSec = json.optLong("expires_in", 3600L).coerceIn(60L, 24 * 3600L)
            return Token(token, System.currentTimeMillis() + ttlSec * 1000L)
        } finally {
            conn.disconnect()
        }
    }

    /** Jeton en cache s'il reste au moins 2 min de validité, sinon nouveau jeton. */
    fun token(ctx: Context, forceNew: Boolean = false): Token {
        val now = System.currentTimeMillis()
        if (!forceNew) {
            Storage.meteoFranceToken(ctx)?.let { (value, exp) ->
                if (exp - now > 120_000L) return Token(value, exp)
            }
        }
        val appId = Storage.meteoFranceAppId(ctx)
        if (appId.isBlank()) throw MfException("Identifiant d'application Météo-France non renseigné")
        val fresh = fetchToken(appId)
        Storage.saveMeteoFranceToken(ctx, fresh.value, fresh.expiresAtMs)
        return fresh
    }

    // ───────────────────────────── Catalogue WCS ─────────────────────────────

    /** Une couche WCS, décomposée : paramètre, run (heure du modèle, « HH.MM.SS »), durée de cumul éventuelle. */
    data class CoverageRef(val param: String, val run: String, val duration: String?, val id: String)

    data class CapsSummary(
        val coverages: Int,
        val parameters: Int,
        val latestRun: String?,
        val precipParameters: List<String>,
        val sampleIds: List<String>,
        val refs: List<CoverageRef>,
    )

    /** Seules les adresses https://public-api.meteofrance.fr/… ou https://api.meteofrance.fr/… sont acceptées. */
    fun checkedCapsUrl(raw: String): String {
        var s = raw.trim()
        if (!s.contains("GetCapabilities", ignoreCase = true)) s = s.trimEnd('/') + "/GetCapabilities"
        if (!s.contains("?")) s += "?$CAPS_QUERY"
        return checkedUrl(s)
    }

    /** Vérifie schéma, hôte et port : le jeton n'est envoyé qu'à Météo-France, en HTTPS. */
    private fun checkedUrl(s: String): String {
        val uri = try {
            URI(s)
        } catch (e: Exception) {
            throw MfException("Adresse illisible")
        }
        val ok = uri.scheme.equals("https", ignoreCase = true) &&
            (uri.host?.lowercase() in API_HOSTS) &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
        if (!ok) throw MfException("Adresse attendue : https://public-api.meteofrance.fr/… ou https://api.meteofrance.fr/…")
        return s
    }

    fun fetchCapabilities(url: String, token: String): CapsSummary {
        val conn = URL(checkedCapsUrl(url)).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/xml")

            val http = conn.responseCode
            if (http != HttpsURLConnection.HTTP_OK) {
                val detail = runCatching { conn.errorStream?.let { readLimited(it, 16 * 1024) } }.getOrNull()
                throw MfException(httpMessage(http, detail))
            }
            return summarize(readLimited(conn.inputStream, MAX_CAPS_BYTES))
        } finally {
            conn.disconnect()
        }
    }

    internal fun summarize(xml: String): CapsSummary {
        val ids = COVERAGE_ID.findAll(xml).map { it.groupValues[1].trim() }.toList()
        val refs = ArrayList<CoverageRef>(ids.size)
        val params = LinkedHashSet<String>()
        var latest: String? = null
        for (id in ids) {
            val m = ID_PARTS.matchEntire(id)
            if (m != null) {
                val ref = CoverageRef(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifEmpty { null }, id)
                refs += ref
                params += ref.param
                if (latest == null || ref.run > latest) latest = ref.run
            } else {
                params += id
            }
        }
        val precip = params.filter { it.contains("PRECIP", ignoreCase = true) || it.contains("RAIN", ignoreCase = true) }
        return CapsSummary(
            coverages = ids.size,
            parameters = params.size,
            latestRun = latest?.let { it.replace('.', ':') },
            precipParameters = precip.take(3),
            sampleIds = ids.take(3),
            refs = refs,
        )
    }

    private fun durationSec(d: String?): Long {
        val m = d?.let { DURATION.matchEntire(it) } ?: return 0L
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val sec = m.groupValues[3].toLongOrNull() ?: 0L
        return h * 3600L + min * 60L + sec
    }

    private fun parseRun(run: String): Instant? = runCatching { Instant.parse(run.replace('.', ':')) }.getOrNull()

    /** Couche de précipitations du run le plus récent, avec la plus courte durée de cumul disponible. */
    internal fun pickPrecip(refs: List<CoverageRef>): Pair<CoverageRef, List<String>>? {
        val precip = refs.filter { it.param.contains("PRECIP", ignoreCase = true) || it.param.contains("RAIN", ignoreCase = true) }
        if (precip.isEmpty()) return null
        val preferred = precip.filter { it.param.startsWith("TOTAL_PRECIPITATION_RATE") }.ifEmpty { precip }
        val latest = preferred.maxOf { it.run }
        val sameRun = preferred.filter { it.run == latest }
        val chosen = sameRun.minByOrNull { durationSec(it.duration) } ?: return null
        val durations = sameRun.mapNotNull { it.duration }.distinct().sortedBy { durationSec(it) }
        return chosen to durations
    }

    // ───────────────────────────── DescribeCoverage / GetCoverage ─────────────────────────────

    data class CoverageInfo(val steps: Int, val stepSec: Long?, val begin: String?, val end: String?, val axes: String?)

    internal fun parseDescribe(xml: String): CoverageInfo {
        val series = COEFFICIENTS.findAll(xml)
            .map { m -> m.groupValues[1].trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() } }
            .maxByOrNull { it.size }
            .orEmpty()
        val step = if (series.size >= 2) (series[1] - series[0]).toLong() else null
        return CoverageInfo(
            steps = series.size,
            stepSec = step,
            begin = BEGIN_POS.find(xml)?.groupValues?.get(1)?.trim(),
            end = END_POS.find(xml)?.groupValues?.get(1)?.trim(),
            axes = AXIS_LABELS.find(xml)?.groupValues?.get(1),
        )
    }

    private fun getBytes(url: String, token: String, accept: String, maxBytes: Int): ByteArray {
        val conn = URL(checkedUrl(url)).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", accept)
            val http = conn.responseCode
            if (http != HttpsURLConnection.HTTP_OK) {
                val detail = runCatching { conn.errorStream?.let { readLimited(it, 16 * 1024) } }.getOrNull()
                throw MfException(httpMessage(http, detail))
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw MfException("Réponse trop volumineuse")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun fmtValue(v: Float): String = String.format(Locale.US, "%.4g", v)

    /**
     * Lecture de PIAF au point de la station : choisit la couche de précipitations du dernier run, lit ses échéances
     * (DescribeCoverage) puis extrait un petit GeoTIFF autour de la position pour 3 échéances.
     */
    private fun probePiaf(ctx: Context, token: String, capsUrl: String, caps: CapsSummary, out: MutableList<TestLine>) {
        val pick = pickPrecip(caps.refs)
        if (pick == null) {
            out += TestLine("PIAF · aucune couche de précipitations trouvée dans le catalogue", false)
            return
        }
        val (ref, durations) = pick
        val runAt = parseRun(ref.run)
        out += TestLine(
            "PIAF · couche : ${ref.param} · dernier run ${runAt?.let { CLOCK.format(it) } ?: ref.run} · " +
                "durées : ${durations.joinToString().ifEmpty { "—" }} · utilisée : ${ref.duration ?: "—"}",
            true,
        )

        val base = capsUrl.substringBefore("?").substringBeforeLast("/GetCapabilities")
        val info = try {
            parseDescribe(String(getBytes("$base/DescribeCoverage?service=WCS&version=2.0.1&coverageid=${ref.id}", token, "application/xml", 2 * 1024 * 1024), Charsets.UTF_8))
        } catch (e: Exception) {
            out += TestLine("PIAF · DescribeCoverage : échec · ${describe(e)}", false)
            return
        }
        val begin = info.begin?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val end = info.end?.let { runCatching { Instant.parse(it) }.getOrNull() }
        out += TestLine(
            "PIAF · DescribeCoverage : OK · ${info.steps} échéances" +
                (info.stepSec?.let { " (pas ${it / 60} min)" } ?: "") +
                (if (begin != null && end != null) " · de ${CLOCK.format(begin)} à ${CLOCK.format(end)}" else " · ${info.begin ?: "?"} → ${info.end ?: "?"}") +
                (info.axes?.let { " · axes $it" } ?: ""),
            true,
        )

        val loc = Storage.location(ctx)
        if (loc == null) {
            out += TestLine("PIAF · position de la station non définie : lecture ponctuelle non testée", false)
            return
        }
        val times: List<Instant> = if (begin != null && end != null && !end.isBefore(begin)) {
            val mid = Instant.ofEpochSecond((begin.epochSecond + end.epochSecond) / 2 / 300L * 300L)
            listOf(begin, mid, end).distinct()
        } else if (runAt != null) {
            listOf(runAt.plusSeconds(300), runAt.plusSeconds(1800), runAt.plusSeconds(10800))
        } else {
            out += TestLine("PIAF · échéances inconnues : lecture ponctuelle non testée", false)
            return
        }

        // La zone demandée est alignée sur la maille de 0,01° (le serveur ne voit que la maille, pas la position exacte) ;
        // le pixel de la station est choisi ensuite localement avec la position exacte.
        val half = 0.03
        val cellLat = Math.round(loc.latitude * 100.0) / 100.0
        val cellLon = Math.round(loc.longitude * 100.0) / 100.0
        fun deg(v: Double) = String.format(Locale.US, "%.4f", v)
        for (t in times) {
            val label = "PIAF · ${CLOCK.format(t)}" + (runAt?.let { " (+${(t.epochSecond - it.epochSecond) / 60} min)" } ?: "")
            var raw: ByteArray? = null
            try {
                val url = "$base/GetCoverage?service=WCS&version=2.0.1&coverageid=${ref.id}" +
                    "&subset=time(${DateTimeFormatter.ISO_INSTANT.format(t)})" +
                    "&subset=lat(${deg(cellLat - half)},${deg(cellLat + half)})" +
                    "&subset=long(${deg(cellLon - half)},${deg(cellLon + half)})" +
                    "&format=image/tiff"
                val bytes = getBytes(url, token, "image/tiff", 2 * 1024 * 1024)
                raw = bytes
                val raster = try {
                    GeoTiff.decode(bytes)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalArgumentException(e.javaClass.simpleName)
                }
                val v = raster.valueAt(loc.latitude, loc.longitude)
                val r = raster.range()
                out += TestLine(
                    "$label : ${v?.let { fmtValue(it) } ?: "pas de donnée"} · grille ${raster.width}×${raster.height}" +
                        (r?.let { " · min ${fmtValue(it.first)} · max ${fmtValue(it.second)}" } ?: "") +
                        if (raster.georeferenced) "" else " · sans géoréférencement",
                    v != null,
                )
            } catch (e: Exception) {
                val hint = raw?.let { b -> " · ${b.size} o · début " + b.take(4).joinToString("") { "%02x".format(it) } } ?: ""
                val why = if (e is IllegalArgumentException) "lecture GeoTIFF impossible (${e.message})" else describe(e)
                out += TestLine("$label : échec · $why$hint", false)
            }
        }
    }

    private fun httpMessage(http: Int, body: String?): String {
        // Message de la passerelle ou du service WCS (jamais de secret dedans) : distingue « pas abonné », « jeton expiré »,
        // « paramètre invalide »…
        val gateway = body?.let {
            runCatching {
                val fault = JSONObject(it).optJSONObject("fault")
                (fault?.optString("description")?.takeIf { d -> d.isNotBlank() } ?: fault?.optString("message"))
            }.getOrNull()
        }
        val wcs = body?.let { EXCEPTION_TEXT.find(it)?.groupValues?.get(1)?.trim() }
        val detail = (wcs ?: gateway)?.take(160)?.let { " · $it" }.orEmpty()
        return when (http) {
            401, 403 -> "accès refusé (HTTP $http) : abonnement manquant à cette API, ou identifiant invalide$detail"
            400 -> "requête refusée (HTTP 400)$detail"
            404 -> "adresse introuvable (HTTP 404)$detail"
            429 -> "limite de requêtes atteinte (HTTP 429)"
            else -> "serveur Météo-France indisponible (HTTP $http)$detail"
        }
    }

    // ───────────────────────────── Test complet ─────────────────────────────

    /** Vérifie l'identifiant, puis lit le catalogue de chaque service candidat. Bloquant : à lancer hors du thread UI. */
    fun test(ctx: Context, customUrl: String?, useStoredToken: Boolean = false): List<TestLine> {
        val out = ArrayList<TestLine>()
        val tok = try {
            val t = token(ctx, forceNew = !useStoredToken)
            out += TestLine("Jeton : OK (valable ~${((t.expiresAtMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)} min)", true)
            t
        } catch (e: Exception) {
            out += TestLine("Jeton : échec · ${describe(e)}", false)
            return out
        }

        // AROME-PI et le serveur public PIAF n'ont pas donné de résultat utile (abonnement / adresse) : seul PIAF « pro » est testé.
        val candidates = ArrayList<Pair<String, String>>()
        candidates += "PIAF" to "$PIAF_PRO_BASE?$CAPS_QUERY"
        customUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates += "Adresse saisie" to it }

        for ((name, url) in candidates) {
            try {
                val s = fetchCapabilities(url, tok.value)
                val parts = ArrayList<String>()
                parts += "${s.coverages} couches"
                if (s.parameters > 0 && s.parameters != s.coverages) parts += "${s.parameters} paramètres"
                s.latestRun?.let { parts += "dernier run $it" }
                if (s.precipParameters.isNotEmpty()) {
                    parts += "précipitations : " + s.precipParameters.joinToString(", ")
                } else if (s.sampleIds.isNotEmpty()) {
                    parts += "ex. " + s.sampleIds.joinToString(", ")
                }
                out += TestLine("$name : OK · " + parts.joinToString(" · "), true)
                if (url.contains("PIAF", ignoreCase = true)) {
                    try {
                        probePiaf(ctx, tok.value, checkedCapsUrl(url), s, out)
                    } catch (e: Exception) {
                        out += TestLine("PIAF · lecture ponctuelle : échec · ${describe(e)}", false)
                    }
                }
            } catch (e: Exception) {
                out += TestLine("$name : échec · ${describe(e)}", false)
            }
        }
        return out
    }

    /** Message lisible, sans jamais exposer l'adresse complète, l'identifiant ni le jeton. */
    fun describe(e: Throwable): String = when (e) {
        is MfException -> e.message ?: "Erreur Météo-France"
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "réseau indisponible ou trop lent"
        is SSLException -> "connexion sécurisée impossible"
        is JSONException -> "réponse illisible"
        else -> "erreur inattendue"
    }

    private fun readLimited(stream: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        stream.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw MfException("Réponse trop volumineuse")
                out.write(buf, 0, n)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
