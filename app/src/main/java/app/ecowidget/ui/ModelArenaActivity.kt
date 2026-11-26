package app.ecowidget.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * Vue graphique de la comparaison des modèles : page locale (assets/arena.html) alimentée par le fichier
 * produit par « Comparer par échéance ». Aucun accès réseau : tout est calculé sur l'appareil.
 */
class ModelArenaActivity : ComponentActivity() {

    private class Bridge(val ctx: android.content.Context) {
        @JavascriptInterface
        fun getData(): String = runCatching { File(ctx.filesDir, DATA_FILE).readText() }.getOrDefault("")

        @JavascriptInterface
        fun getChoice(): String = ctx.getSharedPreferences(PREFS, 0).getString(KEY_CHOICE, "null") ?: "null"

        @JavascriptInterface
        fun saveChoice(json: String) {
            ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_CHOICE, json).apply()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.setBackgroundColor(0xFF0D1117.toInt())
        web.addJavascriptInterface(Bridge(applicationContext), "Android")
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/arena.html")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    companion object {
        const val DATA_FILE = "arena.json"
        const val PREFS = "arena"
        const val KEY_CHOICE = "choice"
    }
}
