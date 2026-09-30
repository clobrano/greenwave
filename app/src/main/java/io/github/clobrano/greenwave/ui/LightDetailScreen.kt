package io.github.clobrano.greenwave.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.clobrano.greenwave.data.LightEstimate
import io.github.clobrano.greenwave.data.ObservationEntity
import io.github.clobrano.greenwave.data.ObservationSource
import io.github.clobrano.greenwave.data.TrafficLightEntity
import io.github.clobrano.greenwave.model.SignalPlan

@Composable
fun LightDetailScreen(
    light: TrafficLightEntity,
    estimate: LightEstimate?,
    observations: List<ObservationEntity>,
    now: Long,
    currentHeading: Double?,
    toSecondsOfDay: (Long) -> Double,
    toEpochMillis: (Long, Double) -> Long,
    onSave: (TrafficLightEntity) -> Unit,
    onDelete: (TrafficLightEntity) -> Unit,
    onDeleteObservation: (ObservationEntity) -> Unit,
    onBack: () -> Unit,
) {
    var form by remember(light) { mutableStateOf(LightForm(light.name, light.speedLimitKmh, light.approachBearing)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val changed = form != LightForm(light.name, light.speedLimitKmh, light.approachBearing)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(light.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
        }
        item { EstimateCard(estimate, now, toSecondsOfDay, toEpochMillis) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    LightFormFields(form, currentHeading) { form = it }
                    Row(Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = {
                                onSave(
                                    light.copy(
                                        name = form.name,
                                        speedLimitKmh = form.speedLimitKmh,
                                        approachBearing = form.approachBearing,
                                    ),
                                )
                            },
                            enabled = changed && form.name.isNotBlank(),
                        ) { Text("Save") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete light", color = Palette.red) }
                    }
                }
            }
        }
        item { Text("Observations (${observations.size})", style = MaterialTheme.typography.titleMedium) }
        items(observations, key = { it.id }) { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.kind.label)
                    Text(
                        "${formatDateTime(o.epochMillis)} · ${if (o.source == ObservationSource.GPS) "GPS" else "button"}" +
                            if (o.gnssTime) "" else " · phone clock",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onDeleteObservation(o) }) { Text("✕") }
            }
            HorizontalDivider()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${light.name}?") },
            text = { Text("Its ${observations.size} observations will be deleted too.") },
            confirmButton = {
                Button(onClick = { confirmDelete = false; onDelete(light) }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EstimateCard(
    estimate: LightEstimate?,
    now: Long,
    toSecondsOfDay: (Long) -> Double,
    toEpochMillis: (Long, Double) -> Long,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val e = estimate?.estimate
            Text("Estimated plan", style = MaterialTheme.typography.titleMedium)
            estimate?.let { Text("Time band: ${it.slot.label}", style = MaterialTheme.typography.bodySmall) }
            Text(e?.status.label, color = e?.status.color)
            val plan = e?.plan
            if (e == null || plan == null) {
                Text("Needs at least 2 starts of green in this time band, in different cycles.")
                return@Column
            }
            Text("Cycle: ${formatSeconds(plan.cycle)}")
            Text("Green: ${formatSeconds(plan.green)}" + if (e.greenMeasured) "" else " (guess: record red too)")
            Text("Mean error: ${formatSeconds(e.rmsError)} over ${e.greenStarts} starts of green")
            if (e.contradictions > 0) Text("Contradicting observations: ${e.contradictions}", color = Palette.amber)
            if (e.alternativeCycles.isNotEmpty()) {
                Text(
                    "Other possible cycles: " + e.alternativeCycles.joinToString { formatSeconds(it) } +
                        ". Also record red lights and wait at red with GPS on to tell them apart.",
                    color = Palette.amber,
                )
            }
            LiveState(plan, now, toSecondsOfDay, toEpochMillis)
        }
    }
}

@Composable
private fun LiveState(
    plan: SignalPlan,
    now: Long,
    toSecondsOfDay: (Long) -> Double,
    toEpochMillis: (Long, Double) -> Long,
) {
    val t = toSecondsOfDay(now)
    val green = plan.isGreen(t)
    Text(
        "Now: ${if (green) "GREEN" else "RED"}, changes in ${plan.secondsToChange(t).toInt()} s",
        color = if (green) Palette.green else Palette.red,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    val next = plan.greenWindows(t, t + 10 * 60).filter { it.start > t }.take(5)
    Text(
        "Next greens: " + next.joinToString { formatTime(toEpochMillis(now, it.start)) },
        style = MaterialTheme.typography.bodySmall,
    )
}
