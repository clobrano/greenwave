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
private val dateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss", Locale.ITALIAN)
    .withZone(ZoneId.systemDefault())

fun formatTime(epochMillis: Long): String = timeFormatter.format(Instant.ofEpochMilli(epochMillis))

fun formatDateTime(epochMillis: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis))

fun formatSeconds(seconds: Double): String = String.format(Locale.ITALIAN, "%.1f s", seconds)

val ObservationKind.label: String
    get() = when (this) {
        ObservationKind.GREEN_START -> "Inizio verde"
        ObservationKind.RED_START -> "Inizio rosso"
        ObservationKind.GREEN_SEEN -> "Visto verde"
        ObservationKind.RED_SEEN -> "Visto rosso"
    }

val PlanStatus?.label: String
    get() = when (this) {
        PlanStatus.RELIABLE -> "Prevedibile"
        PlanStatus.UNPREDICTABLE -> "Non prevedibile"
        PlanStatus.LEARNING -> "In apprendimento"
        null -> "Nessun dato"
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

/** Direzioni proposte quando si crea un semaforo. */
val compassDirections: List<Pair<String, Double?>> = listOf(
    "Qualsiasi" to null,
    "Nord" to 0.0,
    "Nord-est" to 45.0,
    "Est" to 90.0,
    "Sud-est" to 135.0,
    "Sud" to 180.0,
    "Sud-ovest" to 225.0,
    "Ovest" to 270.0,
    "Nord-ovest" to 315.0,
)

fun directionLabel(bearing: Double?): String =
    if (bearing == null) {
        "Qualsiasi"
    } else {
        compassDirections.drop(1).minBy { (_, b) -> Geo.angleDifference(bearing, b!!) }.first +
            " (${bearing.toInt()}°)"
    }
