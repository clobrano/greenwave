package io.github.clobrano.greenwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.clobrano.greenwave.data.LightEstimate
import io.github.clobrano.greenwave.data.TrafficLightEntity
import io.github.clobrano.greenwave.model.PlanStatus
import io.github.clobrano.greenwave.model.SpeedAdvice
import io.github.clobrano.greenwave.model.SpeedAdvisor
import kotlin.math.roundToInt

/** Closer than this (m) I am at the light: only its state matters, not a speed. */
private const val AT_THE_LIGHT_M = 15.0

/**
 * Expected state of the light I am driving toward and the speed to reach it on green.
 * Shown only once the light is predictable; until then it says what is still missing.
 */
@Composable
fun PredictionCard(
    light: TrafficLightEntity,
    distance: Double?,
    estimate: LightEstimate?,
    now: Long,
    speed: Double?,
    toSecondsOfDay: (Long) -> Double,
    toEpochMillis: (Long, Double) -> Long,
) {
    val e = estimate?.estimate
    val plan = e?.plan?.takeIf { e.status == PlanStatus.RELIABLE }
    val advisor = remember { SpeedAdvisor() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                light.name + (distance?.let { " · ${it.roundToInt()} m ahead" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            if (plan == null) {
                Text(
                    "No prediction yet (${e?.status.label}): ${e?.greenStarts ?: 0} starts of green recorded " +
                        "in this time band, at least 3 needed with a consistent cycle.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val t = toSecondsOfDay(now)
                LightState(plan.isGreen(t), plan.secondsToChange(t).toInt())
                if (distance != null && distance >= AT_THE_LIGHT_M) {
                    val advice = advisor.advise(plan, distance, t, speed ?: 0.0, light.speedLimitKmh / 3.6)
                    Advice(advice) { formatTime(toEpochMillis(now, it)) }
                }
            }
        }
    }
}

@Composable
private fun LightState(green: Boolean, secondsToChange: Int) {
    val textColor = if (green) Color.Black else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(if (green) Palette.green else Palette.red, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (green) "GREEN" else "RED",
            color = textColor,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${if (green) "red" else "green"} in $secondsToChange s",
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Advice(advice: SpeedAdvice, timeOf: (Double) -> String) {
    when (advice) {
        is SpeedAdvice.Go -> {
            Text(
                "Keep ${kmh(advice.targetSpeed)} km/h",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Between ${kmh(advice.minSpeed)} and ${kmh(advice.maxSpeed)} km/h you arrive on green",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is SpeedAdvice.RedUnavoidable -> {
            Text(
                "Red on arrival: slow down",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Palette.red,
            )
            Text("Next green at ${timeOf(advice.nextGreenStart)}", style = MaterialTheme.typography.bodySmall)
        }
        SpeedAdvice.NoPrediction -> Unit
    }
}

private fun kmh(metersPerSecond: Double): Int = (metersPerSecond * 3.6).roundToInt()
