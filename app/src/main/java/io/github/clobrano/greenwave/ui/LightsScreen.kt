package io.github.clobrano.greenwave.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.clobrano.greenwave.data.LightEstimate
import io.github.clobrano.greenwave.data.ObservationEntity
import io.github.clobrano.greenwave.data.TrafficLightEntity

@Composable
fun LightsScreen(
    lights: List<TrafficLightEntity>,
    observations: List<ObservationEntity>,
    estimates: Map<Long, LightEstimate>,
    onOpenLight: (Long) -> Unit,
    onMove: (TrafficLightEntity, Int) -> Unit,
    onExport: () -> Unit,
) {
    val counts = observations.groupingBy { it.lightId }.eachCount()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Percorso", style = MaterialTheme.typography.headlineSmall)
                Text("${lights.size} semafori · ${observations.size} osservazioni", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onExport, enabled = observations.isNotEmpty()) { Text("Esporta CSV") }
        }
        if (lights.isEmpty()) {
            Text("Nessun semaforo: aggiungili dalla mappa tenendo premuto sul punto.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(lights, key = { _, light -> light.id }) { index, light ->
                val estimate = estimates[light.id]?.estimate
                Card(Modifier.fillMaxWidth().clickable { onOpenLight(light.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${light.name}", style = MaterialTheme.typography.titleMedium)
                            Text(estimate?.status.label, color = estimate?.status.color)
                            Text(
                                buildString {
                                    append("${counts[light.id] ?: 0} osservazioni")
                                    estimate?.plan?.let { append(" · ciclo ${formatSeconds(it.cycle)}") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onMove(light, -1) }, enabled = index > 0) { Text("▲") }
                        TextButton(onClick = { onMove(light, +1) }, enabled = index < lights.lastIndex) { Text("▼") }
                    }
                }
            }
        }
    }
}
