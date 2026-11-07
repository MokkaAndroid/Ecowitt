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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ecowidget.data.Metric
import app.ecowidget.data.Storage
import app.ecowidget.data.WidgetConfig
import app.ecowidget.update.UpdateScheduler
import app.ecowidget.widget.WidgetTheme

/** Écran de personnalisation d'un widget : thème, mesures affichées, ordre. */
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
    val selected = remember { mutableStateListOf<Metric>().apply { addAll(initial.metrics) } }
    val available = Metric.entries.filter { it !in selected }
    val hasCredentials = remember(tick) { Storage.credentials(ctx).isComplete }

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
            }

            Section("Affiché sur le widget") {
                Text(
                    "La première mesure est affichée en grand. Les suivantes apparaissent selon la place disponible : " +
                        "agrandis le widget pour en voir plus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selected.forEachIndexed { index, metric ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = painterResource(metric.icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(metric.label, style = MaterialTheme.typography.bodyLarge)
                            if (index == 0) {
                                Text(
                                    "PRINCIPALE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                val m = selected.removeAt(index)
                                selected.add(index - 1, m)
                            },
                        ) { Text("▲") }
                        IconButton(
                            enabled = index < selected.lastIndex,
                            onClick = {
                                val m = selected.removeAt(index)
                                selected.add(index + 1, m)
                            },
                        ) { Text("▼") }
                        IconButton(
                            enabled = selected.size > 1,
                            onClick = { selected.removeAt(index) },
                        ) { Text("✕") }
                    }
                }

                if (available.isNotEmpty()) {
                    Text(
                        "Ajouter",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    available.forEach { metric ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selected.add(metric) }
                                .padding(vertical = 10.dp),
                        ) {
                            Icon(
                                painter = painterResource(metric.icon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                metric.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            Text("＋", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
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
                onClick = { onSave(WidgetConfig(theme, selected.toList(), showTime)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer") }
        }
    }
}
