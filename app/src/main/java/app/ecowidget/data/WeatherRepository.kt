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
        if (System.currentTimeMillis() - Storage.forecastAt(ctx) > FORECAST_MAX_AGE_MS) return null
        return runCatching { ForecastParser.parse(JSONObject(raw), System.currentTimeMillis() / 1000) }.getOrNull()
    }

    // ---------- Actualisation réseau ----------

    /**
     * Met à jour les trois sources si nécessaire :
     * - Ecowitt en direct : à chaque appel ;
     * - Ecowitt historique (courbe, tendances) : toutes les 15 min ;
     * - Open-Meteo (prévisions) : toutes les 10 min.
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
                    if (err == null && due(Storage.historyAt(app), HISTORY_TTL_MS, force, now)) {
                        refreshHistory(app, creds, now)
                    }
                    err
                }
                val forecast = async {
                    if (due(Storage.forecastAt(app), FORECAST_TTL_MS, force, now)) refreshForecast(app, now)
                }
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

    private fun refreshHistory(app: Context, creds: Credentials, now: Long) {
        try {
            val data = EcowittClient.fetchHistory(creds, now / 1000)
            val points = HistoryParser.series(data, "outdoor", "temperature").size
            if (points == 0) {
                Storage.saveHistoryError(app, "Historique reçu mais sans température (structure inattendue)")
            } else {
                Storage.saveHistory(app, data.toString(), now)
                Storage.saveHistoryError(app, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Storage.saveHistoryError(app, EcowittClient.describe(e))
        }
    }

    private fun refreshForecast(app: Context, now: Long) {
        val loc = Storage.location(app) ?: return
        try {
            val json = OpenMeteoClient.fetchForecast(loc)
            Storage.saveForecast(app, json.toString(), now)
            Storage.saveForecastError(app, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Storage.saveForecastError(app, OpenMeteoClient.describe(e))
        }
    }
}
