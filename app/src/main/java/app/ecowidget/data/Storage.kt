package app.ecowidget.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.ecowidget.widget.WidgetTheme

/** Configuration d'un widget (une par instance posée sur l'écran d'accueil). */
data class WidgetConfig(
    val theme: WidgetTheme = WidgetTheme.NIGHT,
    /** Le premier élément est affiché en grand. */
    val metrics: List<Metric> = listOf(Metric.OUTDOOR_TEMP, Metric.RAIN_NOW, Metric.HUMIDITY),
    val showTime: Boolean = true,
)

object Storage {
    private const val SECURE_FILE = "ecowidget_secure"
    private const val PLAIN_FILE = "ecowidget_prefs"

    const val DEFAULT_INTERVAL_MIN = 2
    val INTERVAL_CHOICES = listOf(2, 5, 10, 15)

    @Volatile private var secure: SharedPreferences? = null

    private fun plain(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

    // ---- Identifiants : chiffrés (AES-256, clé protégée par l'Android Keystore) ----

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
        return WidgetConfig(theme, metrics, showTime)
    }

    fun saveWidgetConfig(ctx: Context, appWidgetId: Int, c: WidgetConfig) {
        plain(ctx).edit()
            .putString("w$appWidgetId.theme", c.theme.name)
            .putString("w$appWidgetId.metrics", c.metrics.joinToString(",") { it.name })
            .putBoolean("w$appWidgetId.show_time", c.showTime)
            .apply()
    }

    fun deleteWidgetConfig(ctx: Context, appWidgetId: Int) {
        plain(ctx).edit()
            .remove("w$appWidgetId.theme")
            .remove("w$appWidgetId.metrics")
            .remove("w$appWidgetId.show_time")
            .apply()
    }

    // ---- Cache de la dernière réponse (données météo : non sensibles) ----

    fun cachedRaw(ctx: Context): String? = plain(ctx).getString("cache_raw", null)
    fun cachedAt(ctx: Context): Long = plain(ctx).getLong("cache_at", 0L)
    fun lastError(ctx: Context): String? = plain(ctx).getString("last_error", null)

    fun saveCache(ctx: Context, raw: String, atMs: Long) {
        plain(ctx).edit().putString("cache_raw", raw).putLong("cache_at", atMs).apply()
    }

    fun saveError(ctx: Context, message: String?) {
        plain(ctx).edit().apply {
            if (message == null) remove("last_error") else putString("last_error", message)
        }.apply()
    }
}
