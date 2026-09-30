package io.github.clobrano.greenwave.ui

import androidx.compose.ui.graphics.Color
import io.github.clobrano.greenwave.model.Geo
import io.github.clobrano.greenwave.model.ObservationKind
import io.github.clobrano.greenwave.model.PlanStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val dateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss", Locale.ENGLISH)
    .withZone(ZoneId.systemDefault())

fun formatTime(epochMillis: Long): String = timeFormatter.format(Instant.ofEpochMilli(epochMillis))

fun formatDateTime(epochMillis: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis))

fun formatSeconds(seconds: Double): String = String.format(Locale.ENGLISH, "%.1f s", seconds)

val ObservationKind.label: String
    get() = when (this) {
        ObservationKind.GREEN_START -> "Green start"
        ObservationKind.RED_START -> "Red start"
        ObservationKind.GREEN_SEEN -> "Seen green"
        ObservationKind.RED_SEEN -> "Seen red"
    }

val PlanStatus?.label: String
    get() = when (this) {
        PlanStatus.RELIABLE -> "Predictable"
        PlanStatus.UNPREDICTABLE -> "Unpredictable"
        PlanStatus.LEARNING -> "Learning"
        null -> "No data"
    }

object Palette {
    val green = Color(0xFF00C853)
    val red = Color(0xFFD50000)
    val gray = Color(0xFF9E9E9E)
    val amber = Color(0xFFFFAB00)
}

val PlanStatus?.color: Color
    get() = when (this) {
        PlanStatus.RELIABLE -> Palette.green
        PlanStatus.UNPREDICTABLE -> Palette.red
        PlanStatus.LEARNING -> Palette.amber
        null -> Palette.gray
    }

/** Directions offered when creating a traffic light. */
val compassDirections: List<Pair<String, Double?>> = listOf(
    "Any" to null,
    "North" to 0.0,
    "Northeast" to 45.0,
    "East" to 90.0,
    "Southeast" to 135.0,
    "South" to 180.0,
    "Southwest" to 225.0,
    "West" to 270.0,
    "Northwest" to 315.0,
)

fun directionLabel(bearing: Double?): String =
    if (bearing == null) {
        "Any"
    } else {
        compassDirections.drop(1).minBy { (_, b) -> Geo.angleDifference(bearing, b!!) }.first +
            " (${bearing.toInt()}°)"
    }
