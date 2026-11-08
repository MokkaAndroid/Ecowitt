package app.ecowidget.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.ecowidget.widget.WidgetTheme

/** Configuration d'un widget (une par instance posée sur l'écran d'accueil). */
data class WidgetConfig(
    val theme: WidgetTheme = WidgetTheme.NIGHT,
    /** Mesures de la station (bas du widget). La première est affichée en grand. */
    val metrics: List<Metric> = listOf(Metric.OUTDOOR_TEMP, Metric.RAIN_NOW, Metric.HUMIDITY),
    val showTime: Boolean = true,
    /** Bloc Open-Meteo (haut du widget). */
    val forecastEnabled: Boolean = true,
    val forecastMetrics: List<OmMetric> = listOf(OmMetric.DAY_RANGE, OmMetric.NEXT_RAIN, OmMetric.PRECIP_PROB),
)

object Storage {
    private const val SECURE_FILE = "ecowidget_secure"
    private const val PLAIN_FILE = "ecowidget_prefs"

    const val DEFAULT_INTERVAL_MIN = 2
    val INTERVAL_CHOICES = listOf(2, 5, 10, 15)

    @Volatile private var secure: SharedPreferences? = null

    private fun plain(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

    // ---- Identifiants et position : chiffrés (AES-256, clé protégée par l'Android Keystore) ----

    private fun securePrefs(ctx: Context): SharedPreferences {
        secure?.let { return it }
        return synchronized(this) {
            secure ?: createSecure(ctx.applicationContext).also { secure = it }
        }
    }

    private fun createSecure(app: Context): SharedPreferences {
        fun build(): SharedPreferences {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                app,
                SECURE_FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            build()
        } catch (e: Exception) {
            // Fichier chiffré illisible (clé Keystore perdue…) : on repart de zéro.
            app.deleteSharedPreferences(SECURE_FILE)
            build()
        }
    }

    fun credentials(ctx: Context): Credentials = try {
        val p = securePrefs(ctx)
        Credentials(
            applicationKey = p.getString("application_key", "").orEmpty(),
            apiKey = p.getString("api_key", "").orEmpty(),
            mac = p.getString("mac", "").orEmpty(),
        )
    } catch (e: Exception) {
        Credentials.EMPTY
    }

    fun saveCredentials(ctx: Context, c: Credentials) {
        securePrefs(ctx).edit()
            .putString("application_key", c.applicationKey.trim())
            .putString("api_key", c.apiKey.trim())
            .putString("mac", Credentials.normalizeMac(c.mac))
            .apply()
    }

    fun location(ctx: Context): Location? = try {
        val p = securePrefs(ctx)
        val lat = p.getString("loc_lat", null)?.toDoubleOrNull()
        val lon = p.getString("loc_lon", null)?.toDoubleOrNull()
        if (lat == null || lon == null) {
            null
        } else {
            Location(
                latitude = lat,
                longitude = lon,
                label = p.getString("loc_label", "").orEmpty(),
                elevation = p.getString("loc_elev", null)?.toDoubleOrNull(),
                source = p.getString("loc_source", "manuel").orEmpty(),
            )
        }
    } catch (e: Exception) {
        null
    }

    fun saveLocation(ctx: Context, loc: Location) {
        // Coordonnées arrondies à 0,01° (~1 km) : suffisant pour un modèle météo, et on ne
        // transmet pas la position exacte de la maison à un service tiers.
        securePrefs(ctx).edit()
            .putString("loc_lat", (Math.round(loc.latitude * 100.0) / 100.0).toString())
            .putString("loc_lon", (Math.round(loc.longitude * 100.0) / 100.0).toString())
            .putString("loc_label", loc.label)
            .putString("loc_source", loc.source)
            .apply {
                if (loc.elevation == null) remove("loc_elev") else putString("loc_elev", loc.elevation.toString())
            }
            .apply()
        // Nouvelle position : les prévisions en cache ne sont plus valables.
        plain(ctx).edit().remove("forecast_raw").remove("forecast_at").apply()
    }

    // ---- Réglages généraux ----

    fun intervalMinutes(ctx: Context): Int =
        plain(ctx).getInt("interval_min", DEFAULT_INTERVAL_MIN).coerceAtLeast(2)

    fun setIntervalMinutes(ctx: Context, minutes: Int) {
        plain(ctx).edit().putInt("interval_min", minutes.coerceAtLeast(2)).apply()
    }

    // ---- Configuration par widget ----

    fun widgetConfig(ctx: Context, appWidgetId: Int): WidgetConfig {
        val p = plain(ctx)
        val default = WidgetConfig()
        val theme = WidgetTheme.fromName(p.getString("w$appWidgetId.theme", null)) ?: default.theme
        val metrics = p.getString("w$appWidgetId.metrics", null)
            ?.split(',')
            ?.mapNotNull { Metric.fromName(it) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: default.metrics
        val showTime = p.getBoolean("w$appWidgetId.show_time", default.showTime)
        val omEnabled = p.getBoolean("w$appWidgetId.om_enabled", default.forecastEnabled)
        val omMetrics = if (p.contains("w$appWidgetId.om_metrics")) {
            p.getString("w$appWidgetId.om_metrics", "").orEmpty()
                .split(',')
                .mapNotNull { OmMetric.fromName(it) }
                .distinct()
        } else {
            default.forecastMetrics
        }
        return WidgetConfig(theme, metrics, showTime, omEnabled, omMetrics)
    }

    fun saveWidgetConfig(ctx: Context, appWidgetId: Int, c: WidgetConfig) {
        plain(ctx).edit()
            .putString("w$appWidgetId.theme", c.theme.name)
            .putString("w$appWidgetId.metrics", c.metrics.joinToString(",") { it.name })
            .putBoolean("w$appWidgetId.show_time", c.showTime)
            .putBoolean("w$appWidgetId.om_enabled", c.forecastEnabled)
            .putString("w$appWidgetId.om_metrics", c.forecastMetrics.joinToString(",") { it.name })
            .apply()
    }

    fun deleteWidgetConfig(ctx: Context, appWidgetId: Int) {
        plain(ctx).edit()
            .remove("w$appWidgetId.theme")
            .remove("w$appWidgetId.metrics")
            .remove("w$appWidgetId.show_time")
            .remove("w$appWidgetId.om_enabled")
            .remove("w$appWidgetId.om_metrics")
            .apply()
    }

    // ---- Caches des réponses (données météo : non sensibles) ----

    fun cachedRaw(ctx: Context): String? = plain(ctx).getString("cache_raw", null)
    fun cachedAt(ctx: Context): Long = plain(ctx).getLong("cache_at", 0L)
    fun lastError(ctx: Context): String? = plain(ctx).getString("last_error", null)

    fun saveCache(ctx: Context, raw: String, atMs: Long) {
        plain(ctx).edit().putString("cache_raw", raw).putLong("cache_at", atMs).apply()
    }

    fun saveError(ctx: Context, message: String?) = putOrRemove(ctx, "last_error", message)

    fun historyRaw(ctx: Context): String? = plain(ctx).getString("history_raw", null)
    fun historyAt(ctx: Context): Long = plain(ctx).getLong("history_at", 0L)
    fun historyError(ctx: Context): String? = plain(ctx).getString("history_error", null)

    fun saveHistory(ctx: Context, raw: String, atMs: Long) {
        plain(ctx).edit().putString("history_raw", raw).putLong("history_at", atMs).apply()
    }

    fun saveHistoryError(ctx: Context, message: String?) = putOrRemove(ctx, "history_error", message)

    fun forecastRaw(ctx: Context): String? = plain(ctx).getString("forecast_raw", null)
    fun forecastAt(ctx: Context): Long = plain(ctx).getLong("forecast_at", 0L)
    fun forecastError(ctx: Context): String? = plain(ctx).getString("forecast_error", null)

    fun saveForecast(ctx: Context, raw: String, atMs: Long) {
        plain(ctx).edit().putString("forecast_raw", raw).putLong("forecast_at", atMs).apply()
    }

    fun saveForecastError(ctx: Context, message: String?) = putOrRemove(ctx, "forecast_error", message)

    private fun putOrRemove(ctx: Context, key: String, value: String?) {
        plain(ctx).edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
