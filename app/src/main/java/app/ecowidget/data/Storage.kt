package app.ecowidget.data

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.ecowidget.widget.WeatherWidgetReceiver
import app.ecowidget.widget.WidgetTheme

/** Configuration d'un widget (une par instance posée sur l'écran d'accueil). */
data class WidgetConfig(
    val theme: WidgetTheme = WidgetTheme.NIGHT,
    /** Mesures de la station (bas du widget). La première est affichée en grand. */
    val metrics: List<Metric> = listOf(Metric.OUTDOOR_TEMP, Metric.RAIN_NOW, Metric.HUMIDITY),
    val showTime: Boolean = true,
    /** Bloc Open-Meteo (haut du widget). */
    val forecastEnabled: Boolean = true,
    /** Identifiants du catalogue Open-Meteo (voir [OmCatalog]). */
    val forecastMetrics: List<String> = listOf("DAY_RANGE", OmCatalog.RAIN_MESSAGE, "PRECIP_PROB"),
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

    // ---- Météo-France (portail-api.meteofrance.fr) : identifiant d'application et jeton, chiffrés ----

    fun meteoFranceAppId(ctx: Context): String = try {
        securePrefs(ctx).getString("mf_app_id", "").orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun saveMeteoFranceAppId(ctx: Context, appId: String) {
        val id = appId.trim()
        val e = securePrefs(ctx).edit().putString("mf_app_id", id)
        // Nouvel identifiant : l'ancien jeton n'est plus valable.
        if (id != meteoFranceAppId(ctx)) e.remove("mf_token").remove("mf_token_exp")
        e.apply()
    }

    /** Jeton et heure d'expiration (ms), ou null. */
    fun meteoFranceToken(ctx: Context): Pair<String, Long>? = try {
        val p = securePrefs(ctx)
        val t = p.getString("mf_token", null)
        val exp = p.getLong("mf_token_exp", 0L)
        if (t.isNullOrBlank() || exp <= 0L) null else t to exp
    } catch (e: Exception) {
        null
    }

    fun saveMeteoFranceToken(ctx: Context, token: String, expiresAtMs: Long) {
        securePrefs(ctx).edit().putString("mf_token", token).putLong("mf_token_exp", expiresAtMs).apply()
    }

    fun clearMeteoFrance(ctx: Context) {
        securePrefs(ctx).edit().remove("mf_app_id").remove("mf_token").remove("mf_token_exp").apply()
    }

    fun saveLocation(ctx: Context, loc: Location) {
        // Position exacte, chiffrée sur le téléphone. Les services tiers n'en reçoivent qu'une version
        // arrondie à ~1 km (Open-Meteo) ou la maille de 0,01° (Météo-France).
        securePrefs(ctx).edit()
            .putString("loc_lat", loc.latitude.toString())
            .putString("loc_lon", loc.longitude.toString())
            .putString("loc_label", loc.label)
            .putString("loc_source", loc.source)
            .apply {
                if (loc.elevation == null) remove("loc_elev") else putString("loc_elev", loc.elevation.toString())
            }
            .apply()
        // Nouvelle position : les prévisions en cache ne sont plus valables.
        plain(ctx).edit()
            .remove("forecast_raw").remove("forecast_at").remove("forecast_sig")
            .remove("nowcast_raw").remove("nowcast_at")
            .remove("air_raw").remove("air_at").remove("air_sig")
            .apply()
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
                .filter { OmCatalog.byId(it) != null }
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
            .putString("w$appWidgetId.om_metrics", c.forecastMetrics.joinToString(","))
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

    /** Configurations de tous les widgets posés : sert à demander à Open-Meteo l'union des infos choisies. */
    fun allWidgetConfigs(ctx: Context): List<WidgetConfig> {
        val app = ctx.applicationContext
        val ids = runCatching {
            AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, WeatherWidgetReceiver::class.java))
        }.getOrDefault(IntArray(0))
        return ids.map { widgetConfig(app, it) }
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

    /** Empreinte des variables demandées lors de la dernière requête de prévisions (pour redemander si la liste change). */
    fun forecastSig(ctx: Context): String? = plain(ctx).getString("forecast_sig", null)

    fun saveForecast(ctx: Context, raw: String, atMs: Long, sig: String) {
        plain(ctx).edit().putString("forecast_raw", raw).putLong("forecast_at", atMs).putString("forecast_sig", sig).apply()
    }

    fun saveForecastError(ctx: Context, message: String?) = putOrRemove(ctx, "forecast_error", message)

    fun nowcastRaw(ctx: Context): String? = plain(ctx).getString("nowcast_raw", null)
    fun nowcastAt(ctx: Context): Long = plain(ctx).getLong("nowcast_at", 0L)
    fun nowcastError(ctx: Context): String? = plain(ctx).getString("nowcast_error", null)

    fun saveNowcast(ctx: Context, raw: String, atMs: Long) {
        plain(ctx).edit().putString("nowcast_raw", raw).putLong("nowcast_at", atMs).apply()
    }

    fun saveNowcastError(ctx: Context, message: String?) = putOrRemove(ctx, "nowcast_error", message)

    fun airRaw(ctx: Context): String? = plain(ctx).getString("air_raw", null)
    fun airAt(ctx: Context): Long = plain(ctx).getLong("air_at", 0L)
    fun airSig(ctx: Context): String? = plain(ctx).getString("air_sig", null)
    fun airError(ctx: Context): String? = plain(ctx).getString("air_error", null)

    fun saveAir(ctx: Context, raw: String, atMs: Long, sig: String) {
        plain(ctx).edit().putString("air_raw", raw).putLong("air_at", atMs).putString("air_sig", sig).apply()
    }

    fun saveAirError(ctx: Context, message: String?) = putOrRemove(ctx, "air_error", message)

    private fun putOrRemove(ctx: Context, key: String, value: String?) {
        plain(ctx).edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
