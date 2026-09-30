package io.github.clobrano.greenwave.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.clobrano.greenwave.data.LightEstimate
import io.github.clobrano.greenwave.data.ObservationEntity
import io.github.clobrano.greenwave.data.TrafficLightEntity
import io.github.clobrano.greenwave.location.Fix
import io.github.clobrano.greenwave.model.ObservationKind

/** Above this speed (m/s) the buttons are disabled: record only while stopped. */
private const val MAX_SPEED_FOR_BUTTONS = 5.0 / 3.6

@Composable
fun RecordScreen(
    lights: List<TrafficLightEntity>,
    target: RecordTarget?,
    estimates: Map<Long, LightEstimate>,
    fix: Fix?,
    now: Long,
    clockSynced: Boolean,
    lastSaved: ObservationEntity?,
    onChooseTarget: (Long?) -> Unit,
    onRecord: (ObservationKind) -> Unit,
    onUndo: () -> Unit,
    toSecondsOfDay: (Long) -> Double,
) {
    // Keep the screen on while recording.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val moving = (fix?.speed ?: 0.0) > MAX_SPEED_FOR_BUTTONS
    val enabled = target != null && !moving

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TargetSelector(lights, target, onChooseTarget)

        val plan = target?.let { estimates[it.light.id]?.estimate?.plan }
        if (plan != null) {
            val t = toSecondsOfDay(now)
            val green = plan.isGreen(t)
            Text(
                "Predicted: ${if (green) "GREEN" else "RED"}, changes in ${plan.secondsToChange(t).toInt()} s",
                color = if (green) Palette.green else Palette.red,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Text(statusLine(fix, clockSynced), style = MaterialTheme.typography.bodySmall)
        if (moving) {
            Text("Moving: the buttons work only when stopped.", color = Palette.amber)
        }

        Button(
            onClick = { onRecord(ObservationKind.GREEN_START) },
            enabled = enabled,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.green, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().weight(2f),
        ) { Text("GREEN NOW", fontSize = 48.sp, fontWeight = FontWeight.Black) }

        Button(
            onClick = { onRecord(ObservationKind.RED_START) },
            enabled = enabled,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.red, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { Text("RED NOW", fontSize = 32.sp, fontWeight = FontWeight.Bold) }

        Box(Modifier.fillMaxWidth().height(56.dp)) {
            if (lastSaved != null) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    val name = lights.firstOrNull { it.id == lastSaved.lightId }?.name.orEmpty()
                    Text(
                        "Saved: ${lastSaved.kind.label} at ${formatTime(lastSaved.epochMillis)} – $name",
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = onUndo) { Text("Undo") }
                }
            }
        }
    }
}

@Composable
private fun TargetSelector(
    lights: List<TrafficLightEntity>,
    target: RecordTarget?,
    onChooseTarget: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                target?.light?.name ?: "No light nearby",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                when {
                    target == null -> "Get closer to a light or pick one by hand"
                    target.automatic -> "Picked automatically (the nearest)"
                    else -> "Picked by hand"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box {
            OutlinedButton(onClick = { open = true }) { Text("Change") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Automatic (the nearest)") },
                    onClick = { onChooseTarget(null); open = false },
                )
                lights.forEach { light ->
                    DropdownMenuItem(
                        text = { Text("${light.routeOrder + 1}. ${light.name}") },
                        onClick = { onChooseTarget(light.id); open = false },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

private fun statusLine(fix: Fix?, clockSynced: Boolean): String {
    val gps = when {
        fix == null -> "GPS: waiting"
        fix.accuracyMeters != null -> "GPS: ±${fix.accuracyMeters.toInt()} m"
        else -> "GPS: on"
    }
    val speed = fix?.speed?.let { " · ${(it * 3.6).toInt()} km/h" }.orEmpty()
    val clock = if (clockSynced) " · satellite time" else " · phone clock"
    return gps + speed + clock
}
