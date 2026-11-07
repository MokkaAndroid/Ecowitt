package app.ecowidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.ecowidget.data.Credentials
import app.ecowidget.data.EcowittClient
import app.ecowidget.data.Metric
import app.ecowidget.data.SnapshotParser
import app.ecowidget.data.Storage
import app.ecowidget.data.WeatherRepository
import app.ecowidget.update.UpdateScheduler
import app.ecowidget.widget.WeatherWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : ComponentActivity() {

    // Incrémenté à chaque retour sur l'écran (ex. après avoir accordé la permission d'alarmes).
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EcoTheme { SettingsScreen(resumeTick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@Composable
private fun SettingsScreen(tick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val stored = remember { Storage.credentials(ctx) }
    var appKey by remember { mutableStateOf(stored.applicationKey) }
    var apiKey by remember { mutableStateOf(stored.apiKey) }
    var mac by remember { mutableStateOf(stored.mac) }
    var reveal by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var interval by remember { mutableIntStateOf(Storage.intervalMinutes(ctx)) }

    val exactOk = remember(tick) { UpdateScheduler.canScheduleExact(ctx) }
    val cached = remember(tick, testResult) { WeatherRepository.cached(ctx) }
    val macValid = mac.isBlank() || Credentials.isValidMac(mac)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ecowidget", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Les mesures de ta station Ecowitt sur l'écran d'accueil.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Connexion Ecowitt") {
                Text(
                    "Sur ecowitt.net : Profil → clés API. Crée une Application Key et une API Key. " +
                        "L'adresse MAC de la console est dans WS View Plus ou sur ecowitt.net.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = appKey,
                    onValueChange = { appKey = it.trim() },
                    label = { Text("Application Key") },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it.trim() },
                    label = { Text("Adresse MAC (AA:BB:CC:DD:EE:FF)") },
                    singleLine = true,
                    isError = !macValid,
                    supportingText = if (!macValid) ({ Text("Format attendu : 6 paires hexadécimales") }) else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { reveal = !reveal }) {
                        Text(if (reveal) "Masquer les clés" else "Afficher les clés")
                    }
                }
                Button(
                    enabled = !testing && appKey.isNotBlank() && apiKey.isNotBlank() && Credentials.isValidMac(mac),
                    onClick = {
                        val creds = Credentials(appKey, apiKey, Credentials.normalizeMac(mac))
                        Storage.saveCredentials(ctx, creds)
                        mac = creds.mac
                        testing = true
                        testResult = null
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching { SnapshotParser.parse(EcowittClient.fetchData(creds)) }
                            }
                            outcome.onSuccess { snap ->
                                val t = Metric.OUTDOOR_TEMP.read(snap)
                                testOk = true
                                testResult = "Connexion réussie · extérieur ${t.value}${t.unit}"
                                UpdateScheduler.kick(ctx)
                            }.onFailure { e ->
                                testOk = false
                                testResult = "Échec : " + EcowittClient.describe(e)
                            }
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (testing) "Test en cours…" else "Enregistrer et tester")
                }
                testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }

            Section("Actualisation") {
                Text(
                    "Fréquence de mise à jour des widgets :",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Storage.INTERVAL_CHOICES.forEach { m ->
                        FilterChip(
                            selected = interval == m,
                            onClick = {
                                interval = m
                                Storage.setIntervalMinutes(ctx, m)
                                UpdateScheduler.scheduleNext(ctx)
                            },
                            label = { Text("$m min") },
                        )
                    }
                }
                if (exactOk) {
                    Text(
                        "Alarmes exactes autorisées : actualisation régulière.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Pour tenir 2 minutes, Android demande d'autoriser « Alarmes et rappels ». " +
                            "Sans cela, l'actualisation reste approximative.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${ctx.packageName}"),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Autoriser les alarmes exactes") }
                }
                Text(
                    "Android peut espacer les mises à jour quand le téléphone reste immobile écran éteint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Widget") {
                val awm = remember { AppWidgetManager.getInstance(ctx) }
                if (awm.isRequestPinAppWidgetSupported) {
                    Button(
                        onClick = {
                            awm.requestPinAppWidget(
                                ComponentName(ctx, WeatherWidgetReceiver::class.java),
                                null,
                                null,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ajouter à l'écran d'accueil") }
                }
                Text(
                    "Ou : appui long sur l'écran d'accueil → Widgets → Ecowidget. " +
                        "Appui long sur un widget → Reconfigurer pour changer son contenu. " +
                        "Un simple appui sur le widget force l'actualisation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cached.fetchedAtMs > 0) {
                    val t = DateTimeFormatter.ofPattern("HH:mm:ss")
                        .format(Instant.ofEpochMilli(cached.fetchedAtMs).atZone(ZoneId.systemDefault()))
                    Text(
                        "Dernière actualisation réussie : $t",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
