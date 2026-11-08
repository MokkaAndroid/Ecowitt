package app.ecowidget.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId

data class CachedState(
    val snapshot: WeatherSnapshot?,
    val fetchedAtMs: Long,
    val error: String?,
)

object WeatherRepository {
    private val mutex = Mutex()

    private const val HISTORY_TTL_MS = 15 * 60_000L
    private const val FORECAST_TTL_MS = 10 * 60_000L
    private const val NOWCAST_TTL_MS = 10 * 60_000L
    private const val AIR_TTL_MS = 60 * 60_000L
    private const val NOWCAST_MAX_AGE_MS = 60 * 60_000L
    private const val AIR_MAX_AGE_MS = 6 * 3600_000L
    private const val FORCE_MIN_GAP_MS = 60_000L
    private const val FORECAST_MAX_AGE_MS = 3 * 3600_000L

    // ---------- Lecture locale (instantanée) ----------

    fun cached(ctx: Context): CachedState {
        val snapshot = Storage.cachedRaw(ctx)?.let { raw ->
            runCatching { SnapshotParser.parse(JSONObject(raw)) }.getOrNull()
        }
        return CachedState(snapshot, Storage.cachedAt(ctx), Storage.lastError(ctx))
    }

    fun derived(ctx: Context, snap: WeatherSnapshot?): Derived? {
        val raw = Storage.historyRaw(ctx) ?: return null
        return runCatching {
            HistoryParser.parse(JSONObject(raw), snap, System.currentTimeMillis() / 1000, ZoneId.systemDefault())
        }.getOrNull()
    }

    fun forecast(ctx: Context): Forecast? {
        val raw = Storage.forecastRaw(ctx) ?: return null
        val nowMs = System.currentTimeMillis()
        if (nowMs - Storage.forecastAt(ctx) > FORECAST_MAX_AGE_MS) return null
        // Pluie à court terme (AROME) et air : ignorés s'ils sont trop anciens, la prévision reste affichée.
        val nowcast = Storage.nowcastRaw(ctx)
            ?.takeIf { nowMs - Storage.nowcastAt(ctx) <= NOWCAST_MAX_AGE_MS }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val air = Storage.airRaw(ctx)
            ?.takeIf { nowMs - Storage.airAt(ctx) <= AIR_MAX_AGE_MS }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        return runCatching {
            val json = JSONObject(raw)
            // Températures décalées à l'altitude de l'utilisateur, sauf si la flèche du widget est sur « modèle ».
            val altitude = AltitudeCorrection.apply(json, Storage.location(ctx)?.elevation, Storage.altitudeCorrected(ctx))
            ForecastParser.parse(json, nowMs / 1000, nowcast, air).copy(altitude = altitude)
        }.getOrNull()
    }

    // ---------- Actualisation réseau ----------

    /**
     * Met à jour les trois sources si nécessaire :
     * - Ecowitt en direct : à chaque appel ;
     * - Ecowitt historique (courbe, tendances) : toutes les 15 min ;
     * - Open-Meteo : prévisions et pluie à court terme (AROME) toutes les 10 min, qualité de l'air toutes les heures ;
     *   dès que la liste des infos choisies change, on redemande immédiatement.
     * `force` (appui sur le widget) contourne ces délais, avec un minimum d'une minute entre deux appels.
     * Retourne null si les mesures en direct sont OK, sinon un message d'erreur lisible.
     */
    suspend fun refresh(ctx: Context, force: Boolean = false): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = ctx.applicationContext
            val creds = Storage.credentials(app)
            val now = System.currentTimeMillis()

            coroutineScope {
                // Les appels Ecowitt sont enchaînés (pas en parallèle) pour ménager leur limite de débit.
                val ecowitt = async {
                    val err = refreshLive(app, creds)
                    val extended = Storage.widgetMode(app) != WidgetMode.CLASSIC
                    // Passage à un mode à tuiles : l'historique en cache n'a ni vent, ni pluie, ni humidité, on le redemande.
                    val needsExt = extended && !Storage.historyExtended(app)
                    if (err == null && (needsExt || due(Storage.historyAt(app), HISTORY_TTL_MS, force, now))) {
                        refreshHistory(app, creds, now, extended)
                    }
                    err
                }
                val forecast = async { refreshOpenMeteo(app, now, force) }
                forecast.await()
                ecowitt.await()
            }
        }
    }

    private fun due(lastAt: Long, ttl: Long, force: Boolean, now: Long): Boolean =
        now - lastAt > (if (force) FORCE_MIN_GAP_MS else ttl)

    private fun refreshLive(app: Context, creds: Credentials): String? {
        if (!creds.isComplete) {
            val msg = "Identifiants Ecowitt manquants"
            Storage.saveError(app, msg)
            return msg
        }
        return try {
            val data = EcowittClient.fetchData(creds)
            Storage.saveCache(app, data.toString(), System.currentTimeMillis())
            Storage.saveError(app, null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = EcowittClient.describe(e)
            Storage.saveError(app, msg)
            msg
        }
    }

    private fun refreshHistory(app: Context, creds: Credentials, now: Long, extended: Boolean) {
        try {
            val data = EcowittClient.fetchHistory(creds, now / 1000, extended)
            val points = HistoryParser.series(data, "outdoor", "temperature").size
            if (points == 0) {
                Storage.saveHistoryError(app, "Historique reçu mais sans température (structure inattendue)")
            } else {
                Storage.saveHistory(app, data.toString(), now, extended)
                Storage.saveHistoryError(app, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Storage.saveHistoryError(app, EcowittClient.describe(e))
        }
    }

    /** Toutes les infos Open-Meteo demandées par les widgets posés (union). */
    fun currentNeeds(app: Context): Needs {
        val ids = Storage.allWidgetConfigs(app).filter { it.forecastEnabled }.flatMap { it.forecastMetrics }
        val base = OmCatalog.needs(ids)
        // Modes à tuiles : les fiches détaillées ont besoin de 8 jours et de séries horaires supplémentaires.
        return if (Storage.widgetMode(app) != WidgetMode.CLASSIC) base + DetailData.NEEDS else base
    }

    private fun refreshOpenMeteo(app: Context, now: Long, force: Boolean) {
        val loc = Storage.location(app) ?: return
        val needs = currentNeeds(app)

        // 1) Prévisions + variables choisies.
        // « |alt1 » : les prévisions sont demandées sans altitude, la correction se fait sur le téléphone.
        val sig = needs.forecastSignature() + "|alt1"
        if (Storage.forecastSig(app) != sig || due(Storage.forecastAt(app), FORECAST_TTL_MS, force, now)) {
            try {
                val json = OpenMeteoClient.fetchForecast(loc, needs)
                Storage.saveForecast(app, json.toString(), now, sig)
                val fallback = json.optString(OpenMeteoClient.FALLBACK_KEY).takeIf { it.isNotBlank() }
                Storage.saveForecastError(app, fallback?.let { "Variable refusée par Open-Meteo, infos de base seulement : $it" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Storage.saveForecastError(app, OpenMeteoClient.describe(e))
            }
        }

        // 2) Pluie à court terme : endpoint Météo-France (AROME, pas de 15 min).
        if (due(Storage.nowcastAt(app), NOWCAST_TTL_MS, force, now)) {
            try {
                val json = OpenMeteoClient.fetchNowcast(loc)
                Storage.saveNowcast(app, json.toString(), now)
                Storage.saveNowcastError(app, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Storage.saveNowcastError(app, OpenMeteoClient.describe(e))
            }
        }

        // 3) Qualité de l'air / pollens : seulement si une info de ce type est choisie.
        if (needs.air.isNotEmpty()) {
            val airSig = needs.airSignature()
            if (Storage.airSig(app) != airSig || due(Storage.airAt(app), AIR_TTL_MS, force, now)) {
                try {
                    val json = OpenMeteoClient.fetchAir(loc, needs.air)
                    Storage.saveAir(app, json.toString(), now, airSig)
                    Storage.saveAirError(app, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Storage.saveAirError(app, OpenMeteoClient.describe(e))
                }
            }
        }
    }
}
