package app.ecowidget.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Liste ordonnable d'éléments choisis + liste d'éléments à ajouter.
 * `mainFirst` : le premier élément est présenté comme « principal » (affiché en grand).
 */
@Composable
fun <T> ReorderPicker(
    selected: SnapshotStateList<T>,
    all: List<T>,
    label: (T) -> String,
    icon: (T) -> Int,
    mainFirst: Boolean,
    minSelected: Int,
) {
    val available = all.filter { it !in selected }

    selected.forEachIndexed { index, item ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                painter = painterResource(icon(item)),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(label(item), style = MaterialTheme.typography.bodyLarge)
                if (mainFirst && index == 0) {
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
                enabled = selected.size > minSelected,
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
        available.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { selected.add(item) }
                    .padding(vertical = 10.dp),
            ) {
                Icon(
                    painter = painterResource(icon(item)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    label(item),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("＋", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
