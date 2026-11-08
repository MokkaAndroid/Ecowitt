package app.ecowidget.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.ecowidget.R
import app.ecowidget.data.DetailInputs
import app.ecowidget.data.Hue
import app.ecowidget.data.NoteB
import app.ecowidget.data.Sheet
import app.ecowidget.data.SheetBuilder
import app.ecowidget.data.Storage
import app.ecowidget.data.Tile
import app.ecowidget.data.WeatherRepository
import app.ecowidget.widget.WidgetRefresh

/**
 * Fiche détaillée en plein écran, ouverte depuis une tuile du widget.
 * Le contenu (blocs) est construit par [SheetBuilder] et dessiné par [SheetScreen], à l'image des maquettes.
 */
class DetailActivity : ComponentActivity() {

    private val tile = mutableStateOf(Tile.TEMP)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Palette fixe (maquettes) : icônes de la barre d'état claires sur fond sombre, quel que soit le thème du téléphone.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        readIntent(intent)
        setContent {
            EcoTheme {
                SheetHost(tile = tile.value, onTile = { tile.value = it }, onClose = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        Tile.fromId(i?.getStringExtra("tile"))?.let { tile.value = it }
    }
}

private fun loadInputs(ctx: Context): DetailInputs {
    val state = WeatherRepository.cached(ctx)
    return DetailInputs(
        snap = state.snapshot,
        derived = WeatherRepository.derived(ctx, state.snapshot),
        forecast = WeatherRepository.forecast(ctx),
        altM = Storage.location(ctx)?.elevation,
    )
}

@Composable
private fun SheetHost(tile: Tile, onTile: (Tile) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val version by WidgetRefresh.version.collectAsState()
    // Recalculé à chaque actualisation du widget : la fiche reste à jour quand elle est ouverte.
    val inputs = remember(version) { loadInputs(ctx) }
    val sheet = remember(tile, inputs) {
        runCatching { SheetBuilder.build(tile, inputs) }.getOrElse { errorSheet(tile, it) }
    }
    // Une fiche différente repart en haut de page.
    key(sheet.tile) { SheetScreen(sheet, onTile, onClose) }
}

private fun errorSheet(tile: Tile, e: Throwable): Sheet = Sheet(
    tile, tile.label, "Erreur", R.drawable.mk_cloud, Hue.ORANGE,
    listOf(NoteB("Impossible d'afficher cette fiche : " + (e.message ?: e.javaClass.simpleName))),
)
