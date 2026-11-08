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

    // AROME-PI : adresse confirmée par des projets tiers.
    private const val AROME_PI_BASE = "https://public-api.meteofrance.fr/public/aromepi/1.0/wcs/MF-NWP-HIGHRES-AROMEPI-001-FRANCE-WCS/GetCapabilities"
    // PIAF : le nom du service (MF-NWP-HIGHRES-PIAF-001-FRANCE-WCS) est confirmé par le portail ; le serveur
    // affiché par le portail est « api.meteofrance.fr/pro/piaf/… » (version supposée 1.0), l'autre est une supposition.
    private const val PIAF_PUBLIC_BASE = "https://public-api.meteofrance.fr/public/piaf/1.0/wcs/MF-NWP-HIGHRES-PIAF-001-FRANCE-WCS/GetCapabilities"
    private const val PIAF_PRO_BASE = "https://api.meteofrance.fr/pro/piaf/1.0/wcs/MF-NWP-HIGHRES-PIAF-001-FRANCE-WCS/GetCapabilities"

    private const val MAX_CAPS_BYTES = 12 * 1024 * 1024
    private val APP_ID_FORMAT = Regex("^[A-Za-z0-9+/=_-]{20,512}$")
    private val JWT_FORMAT = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
    // Commande curl copiée depuis le portail : on en extrait la chaîne qui suit « Basic ».
    private val BASIC_IN_TEXT = Regex("Basic\\s+([A-Za-z0-9+/=_-]{20,})")
    private val COVERAGE_ID = Regex("<(?:\\w+:)?CoverageId>([^<]+)</(?:\\w+:)?CoverageId>")
    private val RUN_SUFFIX = Regex("^(.+)___(\\d{4}-\\d{2}-\\d{2}T\\d{2}\\.\\d{2}\\.\\d{2}Z)$")

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

    data class CapsSummary(
        val coverages: Int,
        val parameters: Int,
        val latestRun: String?,
        val precipParameters: List<String>,
        val sampleIds: List<String>,
    )

    /** Seules les adresses https://public-api.meteofrance.fr/… ou https://api.meteofrance.fr/… sont acceptées. */
    fun checkedCapsUrl(raw: String): String {
        var s = raw.trim()
        if (!s.contains("GetCapabilities", ignoreCase = true)) s = s.trimEnd('/') + "/GetCapabilities"
        if (!s.contains("?")) s += "?$CAPS_QUERY"
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
        val params = LinkedHashSet<String>()
        var latest: String? = null
        for (id in ids) {
            val m = RUN_SUFFIX.matchEntire(id)
            if (m != null) {
                params += m.groupValues[1]
                val run = m.groupValues[2]
                if (latest == null || run > latest) latest = run
            } else {
                params += id
            }
        }
        val precip = params.filter { it.contains("PRECIP", ignoreCase = true) || it.contains("RAIN", ignoreCase = true) }
        return CapsSummary(
            coverages = ids.size,
            parameters = params.size,
            latestRun = latest?.let { it.replace('.', ':') },
            precipParameters = precip.take(4),
            sampleIds = ids.take(3),
        )
    }

    private fun httpMessage(http: Int, body: String?): String {
        // Message de la passerelle (jamais de secret dedans) : utile pour distinguer « pas abonné » de « jeton expiré ».
        val gateway = body?.let {
            runCatching {
                val fault = JSONObject(it).optJSONObject("fault")
                (fault?.optString("description")?.takeIf { d -> d.isNotBlank() } ?: fault?.optString("message"))
            }.getOrNull()
        }?.take(140)
        return when (http) {
            401, 403 -> "accès refusé (HTTP $http) : abonnement manquant à cette API, ou identifiant invalide" +
                (gateway?.let { " · $it" } ?: "")
            404 -> "adresse introuvable (HTTP 404)"
            429 -> "limite de requêtes atteinte (HTTP 429)"
            else -> "serveur Météo-France indisponible (HTTP $http)"
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

        val candidates = ArrayList<Pair<String, String>>()
        candidates += "AROME-PI" to "$AROME_PI_BASE?$CAPS_QUERY"
        candidates += "PIAF (serveur pro, version supposée)" to "$PIAF_PRO_BASE?$CAPS_QUERY"
        candidates += "PIAF (serveur public, supposé)" to "$PIAF_PUBLIC_BASE?$CAPS_QUERY"
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
