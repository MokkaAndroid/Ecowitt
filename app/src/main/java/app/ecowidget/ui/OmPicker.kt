package app.ecowidget.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ecowidget.R
import app.ecowidget.data.OmApi
import app.ecowidget.data.OmCatalog
import app.ecowidget.data.OmCategory
import app.ecowidget.data.OmItem
import app.ecowidget.data.OmSection

/**
 * Sélecteur des infos Open-Meteo : liste ordonnable des infos choisies, puis ajout par catégories,
 * par recherche, ou via une variable personnalisée.
 */
@Composable
fun OmPicker(selected: SnapshotStateList<String>) {
    var query by remember { mutableStateOf("") }
    var openCategory by remember { mutableStateOf<OmCategory?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customAir by remember { mutableStateOf(false) }
    var customSection by remember { mutableStateOf(OmSection.HOURLY) }

    ReorderPicker(
        selected = selected,
        all = emptyList<String>(),
        label = { OmCatalog.byId(it)?.label ?: it },
        icon = { OmCatalog.byId(it)?.icon ?: R.drawable.ic_cloud_rain },
        mainFirst = false,
        minSelected = 0,
    )
    if (selected.isEmpty()) {
        Text(
            "Aucune info choisie : seule la condition du moment est affichée.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(
        "Ajouter une info",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Rechercher (UV, sol, pollen, vent…)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (query.isNotBlank()) {
        val results = OmCatalog.search(query, selected)
        if (results.isEmpty()) {
            Text(
                "Aucun résultat. Tu peux saisir directement un nom de variable Open-Meteo plus bas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        results.take(30).forEach { item ->
            AddRow(item, showCategory = true) { selected.add(item.id) }
        }
    } else {
        OmCategory.entries.forEach { category ->
            val available = OmCatalog.byCategory[category].orEmpty().filter { it.id !in selected }
            if (available.isNotEmpty()) {
                val expanded = openCategory == category
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { openCategory = if (expanded) null else category }
                        .padding(vertical = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(category.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        category.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(
                        "${available.size}  ${if (expanded) "▾" else "▸"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    available.forEach { item ->
                        AddRow(item, showCategory = false) { selected.add(item.id) }
                    }
                }
            }
        }
    }

    // ── Variable personnalisée : accès à tout le reste de l'API ──
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { customOpen = !customOpen }
            .padding(vertical = 10.dp),
    ) {
        Text(
            "Variable personnalisée",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(if (customOpen) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (customOpen) {
        Text(
            "Saisis le nom exact d'une variable de la documentation Open-Meteo (ex. soil_temperature_6cm, " +
                "temperature_850hPa). L'unité est lue dans la réponse. Si le nom est refusé, " +
                "la section Diagnostic des réglages l'indique.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it.trim() },
            label = { Text("Nom de la variable") },
            singleLine = true,
            isError = customName.isNotEmpty() && !OmCatalog.isValidVariable(customName),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !customAir, onClick = { customAir = false }, label = { Text("Prévisions") })
            FilterChip(selected = customAir, onClick = { customAir = true }, label = { Text("Air / pollens") })
        }
        if (!customAir) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OmSection.entries.forEach { section ->
                    FilterChip(
                        selected = customSection == section,
                        onClick = { customSection = section },
                        label = {
                            Text(
                                when (section) {
                                    OmSection.CURRENT -> "Actuel"
                                    OmSection.HOURLY -> "Horaire"
                                    OmSection.DAILY -> "Quotidien"
                                },
                            )
                        },
                    )
                }
            }
        }
        Button(
            enabled = OmCatalog.isValidVariable(customName),
            onClick = {
                val api = if (customAir) OmApi.AIR else OmApi.FORECAST
                val id = OmCatalog.customId(api, customSection, customName)
                if (id !in selected) selected.add(id)
                customName = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Ajouter cette variable") }
    }
}

@Composable
private fun AddRow(item: OmItem, showCategory: Boolean, onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onAdd)
            .padding(vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(item.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium)
            if (showCategory) {
                Text(
                    item.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("＋", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}
