package app.ecowidget.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ecowidget.data.Metric
import app.ecowidget.data.Storage
import app.ecowidget.data.WidgetConfig
import app.ecowidget.update.UpdateScheduler
import app.ecowidget.widget.WidgetTheme

/** Écran de personnalisation d'un widget : thème, mesures de la station, prévisions Open-Meteo. */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Si l'utilisateur quitte sans enregistrer, Android annule l'ajout du widget.
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            EcoTheme {
                ConfigScreen(
                    initial = Storage.widgetConfig(this, appWidgetId),
                    tick = resumeTick.intValue,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onSave = ::save,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun save(config: WidgetConfig) {
        Storage.saveWidgetConfig(this, appWidgetId, config)
        // Redessine tout de suite (avec les données en cache) puis actualise.
        UpdateScheduler.kick(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@Composable
private fun ConfigScreen(
    initial: WidgetConfig,
    tick: Int,
    onOpenSettings: () -> Unit,
    onSave: (WidgetConfig) -> Unit,
) {
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(initial.theme) }
    var showTime by remember { mutableStateOf(initial.showTime) }
    var omEnabled by remember { mutableStateOf(initial.forecastEnabled) }
    val selected = remember { mutableStateListOf<Metric>().apply { addAll(initial.metrics) } }
    val omSelected = remember { mutableStateListOf<String>().apply { addAll(initial.forecastMetrics) } }
    val hasCredentials = remember(tick) { Storage.credentials(ctx).isComplete }
    val location = remember(tick) { Storage.location(ctx) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Personnaliser le widget", style = MaterialTheme.typography.headlineSmall)

            if (!hasCredentials) {
                Section("Connexion requise") {
                    Text(
                        "Renseigne d'abord tes clés Ecowitt pour que le widget affiche des mesures.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Configurer la connexion")
                    }
                }
            }

            Section("Thème") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    WidgetTheme.entries.forEach { t ->
                        val isSelected = theme == t
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { theme = t },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Brush.linearGradient(listOf(t.previewStart, t.previewEnd)))
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline,
                                        ),
                                        RoundedCornerShape(16.dp),
                                    ),
                            )
                            Text(
                                t.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Le fond bascule tout seul sur une teinte bleutée quand il pleut, " +
                        "et la couleur de la température suit le thermomètre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Prévisions · Open-Meteo (haut)") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Afficher les prévisions",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = omEnabled, onCheckedChange = { omEnabled = it })
                }
                if (omEnabled) {
                    if (location == null) {
                        Text(
                            "Aucune position définie : choisis-la dans les réglages de l'application " +
                                "(position de la station ou recherche d'une ville).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Définir la position")
                        }
                    } else {
                        Text(
                            "Position : ${location.label.ifBlank { "%.2f, %.2f".format(location.latitude, location.longitude) }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "La condition du moment s'affiche toujours. Le « message pluie » apparaît en bandeau, " +
                            "la première autre info à droite de l'en-tête, les suivantes en pastilles selon la place disponible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OmPicker(selected = omSelected)
                }
            }

            Section("Station · Ecowitt (bas)") {
                Text(
                    "La première mesure est affichée en grand. Les suivantes apparaissent selon la place disponible : " +
                        "agrandis le widget pour en voir plus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReorderPicker(
                    selected = selected,
                    all = Metric.entries,
                    label = { it.label },
                    icon = { it.icon },
                    mainFirst = true,
                    minSelected = 1,
                )
                Text(
                    "La courbe des 24 h apparaît à côté de la température extérieure quand elle est en première position " +
                        "et que le widget est assez large.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Options") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Afficher l'heure de la mesure",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = showTime, onCheckedChange = { showTime = it })
                }
            }

            Button(
                enabled = selected.isNotEmpty(),
                onClick = { onSave(WidgetConfig(theme, selected.toList(), showTime, omEnabled, omSelected.toList())) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer") }
        }
    }
}
