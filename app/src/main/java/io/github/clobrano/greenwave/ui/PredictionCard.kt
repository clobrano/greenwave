package io.github.clobrano.greenwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.TextUnit
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
 *
 * Made to be read at a glance while driving: few words, very large numbers, full-width
 * colored panels. [driving] makes everything larger still, for when the card has the whole
 * screen to itself.
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
    driving: Boolean,
    modifier: Modifier = Modifier,
) {
    val e = estimate?.estimate
    val plan = e?.plan?.takeIf { e.status == PlanStatus.RELIABLE }
    val advisor = remember { SpeedAdvisor() }
    val sizes = if (driving) Sizes.DRIVING else Sizes.STOPPED

    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                light.name + (distance?.let { " · ${it.roundToInt()} m" } ?: ""),
                fontSize = sizes.header,
                fontWeight = FontWeight.SemiBold,
            )
            if (plan == null) {
                Text(
                    "No prediction yet (${e?.status.label}): ${e?.greenStarts ?: 0} starts of green recorded " +
                        "in this time band, at least 3 needed with a consistent cycle.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                val t = toSecondsOfDay(now)
                LightState(plan.isGreen(t), plan.secondsToChange(t).toInt(), sizes)
                if (distance != null && distance >= AT_THE_LIGHT_M) {
                    val advice = advisor.advise(plan, distance, t, speed ?: 0.0, light.speedLimitKmh / 3.6)
                    Advice(advice, sizes) { formatTime(toEpochMillis(now, it)) }
                }
            }
        }
    }
}

/** Text sizes: while driving the card has the screen and everything is larger. */
private data class Sizes(
    val header: TextUnit,
    val state: TextUnit,
    val speed: TextUnit,
    val unit: TextUnit,
    val detail: TextUnit,
) {
    companion object {
        val DRIVING = Sizes(header = 24.sp, state = 52.sp, speed = 128.sp, unit = 36.sp, detail = 22.sp)
        val STOPPED = Sizes(header = 20.sp, state = 40.sp, speed = 72.sp, unit = 28.sp, detail = 18.sp)
    }
}

/** Full-width green or red panel: the color word and the seconds to the next change. */
@Composable
private fun LightState(green: Boolean, secondsToChange: Int, sizes: Sizes) {
    val textColor = if (green) Color.Black else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (green) Palette.green else Palette.red, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (green) "GREEN" else "RED",
            color = textColor,
            fontSize = sizes.state,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$secondsToChange s",
            color = textColor,
            fontSize = sizes.state,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Advice(advice: SpeedAdvice, sizes: Sizes, timeOf: (Double) -> String) {
    when (advice) {
        is SpeedAdvice.Go -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${kmh(advice.targetSpeed)}",
                    fontSize = sizes.speed,
                    fontWeight = FontWeight.Black,
                    lineHeight = sizes.speed,
                )
                Text(
                    " km/h",
                    fontSize = sizes.unit,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Text(
                "green between ${kmh(advice.minSpeed)} and ${kmh(advice.maxSpeed)} km/h",
                fontSize = sizes.detail,
            )
        }
        is SpeedAdvice.RedUnavoidable -> Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.amber, RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("SLOW DOWN", color = Color.Black, fontSize = sizes.state, fontWeight = FontWeight.Black)
            Text(
                "red on arrival · green at ${timeOf(advice.nextGreenStart)}",
                color = Color.Black,
                fontSize = sizes.detail,
            )
        }
        SpeedAdvice.NoPrediction -> Unit
    }
}

private fun kmh(metersPerSecond: Double): Int = (metersPerSecond * 3.6).roundToInt()
