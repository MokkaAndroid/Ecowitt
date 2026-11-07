package app.ecowidget.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CachedState(
    val snapshot: WeatherSnapshot?,
    val fetchedAtMs: Long,
    val error: String?,
)

object WeatherRepository {
    private val mutex = Mutex()

    /** Dernières données connues (lecture locale, instantanée). */
    fun cached(ctx: Context): CachedState {
        val snapshot = Storage.cachedRaw(ctx)?.let { raw ->
            runCatching { SnapshotParser.parse(JSONObject(raw)) }.getOrNull()
        }
        return CachedState(snapshot, Storage.cachedAt(ctx), Storage.lastError(ctx))
    }

    /** Interroge le cloud Ecowitt. Retourne null si OK, sinon un message d'erreur lisible. */
    suspend fun refresh(ctx: Context): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = ctx.applicationContext
            val creds = Storage.credentials(app)
            if (!creds.isComplete) {
                val msg = "Identifiants Ecowitt manquants"
                Storage.saveError(app, msg)
                return@withContext msg
            }
            try {
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
    }
}
