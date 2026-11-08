package app.ecowidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.glance.appwidget.updateAll
import app.ecowidget.data.Credentials
import app.ecowidget.data.EcowittClient
import app.ecowidget.data.HistoryParser
import app.ecowidget.data.Location
import app.ecowidget.data.Metric
import app.ecowidget.data.MeteoFranceClient
import app.ecowidget.data.OpenMeteoClient
import app.ecowidget.data.Place
import app.ecowidget.data.SnapshotParser
import app.ecowidget.data.Storage
import app.ecowidget.data.WeatherRepository
import app.ecowidget.update.UpdateScheduler
import app.ecowidget.widget.WeatherWidget
import app.ecowidget.widget.WeatherWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun clock(ms: Long): String =
    CLOCK.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Lignes de diagnostic : permettent de voir quelle source fonctionne et pourquoi une autre échoue. */
private fun diagnostics(ctx: Context): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()

    val liveAt = Storage.cachedAt(ctx)
    val liveErr = Storage.lastError(ctx)
    out += when {
        liveErr != null -> "Mesures en direct : échec · $liveErr" to false
        liveAt > 0 -> "Mesures en direct : OK à ${clock(liveAt)}" to true
        else -> "Mesures en direct : pas encore chargées" to false
    }

    val histAt = Storage.historyAt(ctx)
    val histErr = Storage.historyError(ctx)
    val points = runCatching {
        Storage.historyRaw(ctx)?.let { HistoryParser.series(JSONObject(it), "outdoor", "temperature").size }
    }.getOrNull() ?: 0
    out += when {
        histErr != null -> "Historique 24 h : échec · $histErr" to false
        histAt > 0 -> "Historique 24 h : $points points · ${clock(histAt)}" to true
        else -> "Historique 24 h : pas encore chargé" to false
    }

    val fcAt = Storage.forecastAt(ctx)
    val fcErr = Storage.forecastError(ctx)
    out += when {
        Storage.location(ctx) == null -> "Prévisions Open-Meteo : position non définie" to false
        fcErr != null -> "Prévisions Open-Meteo : échec · $fcErr" to false
        fcAt > 0 -> "Prévisions Open-Meteo : OK à ${clock(fcAt)}" to true
        else -> "Prévisions Open-Meteo : pas encore chargées" to false
    }

    // Pluie à court terme (AROME France 2,5 km via Open-Meteo) : seulement si une position est définie.
    if (Storage.location(ctx) != null) {
        val ncAt = Storage.nowcastAt(ctx)
        val ncErr = Storage.nowcastError(ctx)
        out += when {
            ncErr != null && ncAt <= 0 -> "Pluie à court terme (AROME) : indisponible · $ncErr" to false
            ncErr != null -> "Pluie à court terme (AROME) : échec · $ncErr" to false
            ncAt > 0 -> "Pluie à court terme (AROME) : OK à ${clock(ncAt)}" to true
            else -> "Pluie à court terme (AROME) : pas encore chargée" to false
        }

        // Air et pollens : uniquement quand au moins une info de cette catégorie est utilisée.
        val airUsed = WeatherRepository.currentNeeds(ctx).air.isNotEmpty()
        if (airUsed) {
            val airAt = Storage.airAt(ctx)
            val airErr = Storage.airError(ctx)
            out += when {
                airErr != null -> "Air et pollens : échec · $airErr" to false
                airAt > 0 -> "Air et pollens : OK à ${clock(airAt)}" to true
                else -> "Air et pollens : pas encore chargés" to false
            }
        }
    }
    return out
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

    // Météo-France (pluie imminente) : identifiant d'application chiffré, jamais affiché en clair par défaut.
    var mfAppId by remember { mutableStateOf(Storage.meteoFranceAppId(ctx)) }
    var mfUrl by remember { mutableStateOf("") }
    var mfReveal by remember { mutableStateOf(false) }
    var mfBusy by remember { mutableStateOf(false) }
    var mfLines by remember { mutableStateOf<List<MeteoFranceClient.TestLine>>(emptyList()) }
    var interval by remember { mutableIntStateOf(Storage.intervalMinutes(ctx)) }

    var location by remember { mutableStateOf(Storage.location(ctx)) }
    var query by remember { mutableStateOf("") }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var locBusy by remember { mutableStateOf(false) }
    var locMessage by remember { mutableStateOf<String?>(null) }
    var altText by remember { mutableStateOf(location?.elevation?.roundToInt()?.toString() ?: "") }

    var diagTick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    val exactOk = remember(tick) { UpdateScheduler.canScheduleExact(ctx) }
    val cached = remember(tick, testResult, diagTick) { WeatherRepository.cached(ctx) }
    val diag = remember(tick, testResult, diagTick, location) { diagnostics(ctx) }
    val macValid = mac.isBlank() || Credentials.isValidMac(mac)
    val credsOk = appKey.isNotBlank() && apiKey.isNotBlank() && Credentials.isValidMac(mac)

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
                    "Ta station Ecowitt et les prévisions Open-Meteo sur l'écran d'accueil.",
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
                    enabled = !testing && credsOk,
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
                            val snap = outcome.getOrNull()
                            if (snap != null) {
                                val t = Metric.OUTDOOR_TEMP.read(snap)
                                testOk = true
                                testResult = "Connexion réussie · extérieur ${t.value}${t.unit}"
                                // Première connexion : on récupère aussi la position de la station pour Open-Meteo.
                                if (Storage.location(ctx) == null) {
                                    val loc = withContext(Dispatchers.IO) {
                                        runCatching { EcowittClient.fetchStationLocation(creds) }.getOrNull()
                                    }
                                    if (loc != null) {
                                        Storage.saveLocation(ctx, loc)
                                        location = Storage.location(ctx)
                                        altText = location?.elevation?.roundToInt()?.toString() ?: ""
                                        testResult += " · position de la station détectée"
                                    }
                                }
                                UpdateScheduler.kick(ctx)
                            } else {
                                testOk = false
                                testResult = "Échec : " + EcowittClient.describe(outcome.exceptionOrNull() ?: Exception())
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

            Section("Position pour les prévisions") {
                Text(
                    location?.let {
                        val where = it.label.ifBlank { "%.2f, %.2f".format(it.latitude, it.longitude) }
                        val alt = it.elevation?.let { e -> " · ${e.roundToInt()} m" } ?: ""
                        "Actuelle : $where$alt"
                    } ?: "Aucune position définie : les prévisions sont désactivées.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Seules des coordonnées arrondies à ~1 km sont envoyées à Open-Meteo, jamais tes clés Ecowitt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    enabled = !locBusy && credsOk,
                    onClick = {
                        val creds = Credentials(appKey, apiKey, Credentials.normalizeMac(mac))
                        locBusy = true
                        locMessage = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { EcowittClient.fetchStationLocation(creds) } }
                            val loc = result.getOrNull()
                            if (loc != null) {
                                Storage.saveLocation(ctx, loc)
                                location = Storage.location(ctx)
                                altText = location?.elevation?.roundToInt()?.toString() ?: ""
                                locMessage = "Position de la station enregistrée."
                                UpdateScheduler.kick(ctx)
                            } else {
                                val err = result.exceptionOrNull()
                                locMessage = if (err != null) {
                                    "Échec : " + EcowittClient.describe(err)
                                } else {
                                    "Ecowitt ne fournit pas de position pour cette station : cherche ta ville ci-dessous."
                                }
                            }
                            locBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (locBusy) "Recherche…" else "Utiliser la position de ma station") }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Ou chercher une ville") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !searching && query.trim().length >= 2,
                    onClick = {
                        searching = true
                        locMessage = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { OpenMeteoClient.search(query) } }
                            places = result.getOrDefault(emptyList())
                            val err = result.exceptionOrNull()
                            if (err != null) {
                                locMessage = "Échec : " + OpenMeteoClient.describe(err)
                            } else if (places.isEmpty()) {
                                locMessage = "Aucun résultat."
                            }
                            searching = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "Recherche…" else "Rechercher") }

                places.forEach { place ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Storage.saveLocation(
                                    ctx,
                                    Location(place.latitude, place.longitude, place.label, place.elevation, "manuel"),
                                )
                                location = Storage.location(ctx)
                                altText = location?.elevation?.roundToInt()?.toString() ?: ""
                                places = emptyList()
                                locMessage = "Position enregistrée : ${place.label}"
                                UpdateScheduler.kick(ctx)
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(place.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            place.elevation?.let { "${it.roundToInt()} m" } ?: "altitude inconnue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (location != null) {
                    OutlinedTextField(
                        value = altText,
                        onValueChange = { altText = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("Altitude de la station (m)") },
                        supportingText = { Text("Corrige la température prévue en montagne. Laisse vide pour l'altitude du modèle.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            location?.let { cur ->
                                Storage.saveLocation(ctx, cur.copy(elevation = altText.toDoubleOrNull()))
                                location = Storage.location(ctx)
                                locMessage = "Altitude enregistrée."
                                UpdateScheduler.kick(ctx)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Appliquer l'altitude") }
                }

                locMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            Section("Météo-France · pluie imminente (test)") {
                Text(
                    "Sur portail-api.meteofrance.fr → « Générer token » (type OAuth2). Pour un test rapide, colle le jeton généré (valable 1 h). " +
                        "Pour un usage durable, colle l'Application ID : la longue chaîne qui suit « Basic » dans la commande curl. Garde-les secrets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = mfAppId,
                    onValueChange = { mfAppId = it.trim() },
                    label = { Text("Application ID (ou jeton de 1 h)") },
                    singleLine = true,
                    isError = mfAppId.isNotBlank() &&
                        !MeteoFranceClient.isValidAppId(mfAppId) && !MeteoFranceClient.isJwt(mfAppId),
                    visualTransformation = if (mfReveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mfUrl,
                    onValueChange = { mfUrl = it.trim() },
                    label = { Text("Adresse GetCapabilities à tester (optionnel)") },
                    supportingText = {
                        Text("Commence par https://public-api.meteofrance.fr/… ou https://api.meteofrance.fr/… (sans jeton). Utile si une adresse supposée est introuvable.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { mfReveal = !mfReveal }) {
                        Text(if (mfReveal) "Masquer l'identifiant" else "Afficher l'identifiant")
                    }
                    TextButton(
                        enabled = !mfBusy && (mfAppId.isNotBlank() || Storage.meteoFranceAppId(ctx).isNotBlank()),
                        onClick = {
                            Storage.clearMeteoFrance(ctx)
                            mfAppId = ""
                            mfLines = emptyList()
                        },
                    ) { Text("Effacer") }
                }
                Button(
                    enabled = !mfBusy && (MeteoFranceClient.isValidAppId(mfAppId) || MeteoFranceClient.isJwt(mfAppId)),
                    onClick = {
                        val id = MeteoFranceClient.normalizeAppId(mfAppId)
                        val pastedToken = MeteoFranceClient.isJwt(id)
                        if (pastedToken) {
                            // Jeton déjà généré (valable 1 h) : test rapide, sans identifiant d'application.
                            Storage.saveMeteoFranceToken(ctx, id, System.currentTimeMillis() + 55 * 60_000L)
                            mfAppId = Storage.meteoFranceAppId(ctx)
                        } else {
                            Storage.saveMeteoFranceAppId(ctx, id)
                            mfAppId = id
                        }
                        mfBusy = true
                        mfLines = emptyList()
                        val url = mfUrl
                        scope.launch {
                            mfLines = withContext(Dispatchers.IO) {
                                runCatching { MeteoFranceClient.test(ctx, url, useStoredToken = pastedToken) }.getOrElse {
                                    listOf(MeteoFranceClient.TestLine("Test impossible · " + MeteoFranceClient.describe(it), false))
                                }
                            }
                            mfBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (mfBusy) "Test en cours…" else "Enregistrer et tester Météo-France") }
                mfLines.forEach { line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (line.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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
                    "Les mesures en direct sont rechargées à chaque cycle ; l'historique 24 h toutes les 15 min " +
                        "et les prévisions toutes les 10 min. Android peut espacer les mises à jour quand le " +
                        "téléphone reste immobile écran éteint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Diagnostic") {
                diag.forEach { (line, ok) ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(
                    enabled = !refreshing,
                    onClick = {
                        refreshing = true
                        scope.launch {
                            WeatherRepository.refresh(ctx, force = true)
                            WeatherWidget().updateAll(ctx)
                            diagTick++
                            refreshing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (refreshing) "Actualisation…" else "Tout actualiser maintenant") }
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
                    Text(
                        "Dernière actualisation réussie : ${clock(cached.fetchedAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("À propos") {
                Text(
                    "Prévisions : Weather data by Open-Meteo.com (licence CC BY 4.0, usage non commercial). " +
                        "Mesures : cloud Ecowitt avec tes propres clés, stockées chiffrées sur ce téléphone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
