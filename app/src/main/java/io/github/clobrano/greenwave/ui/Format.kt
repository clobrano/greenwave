package io.github.clobrano.greenwave.ui

import androidx.compose.ui.graphics.Color
import io.github.clobrano.greenwave.data.LightEstimate
import io.github.clobrano.greenwave.model.Blocker
import io.github.clobrano.greenwave.model.Geo
import io.github.clobrano.greenwave.model.ObservationKind
import io.github.clobrano.greenwave.model.PlanStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

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

/**
 * A light without a direction could be any of the lights at a crossroads, so automatic
 * recording skips it: it can only be recorded with the buttons.
 */
const val ANY_DIRECTION = "Any (manual only)"

/** Shown on lights that automatic recording skips. */
const val NO_DIRECTION_WARNING = "No direction set: not recorded automatically"

/** Directions offered when creating a traffic light. */
val compassDirections: List<Pair<String, Double?>> = listOf(
    ANY_DIRECTION to null,
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
        ANY_DIRECTION
    } else {
        compassDirections.drop(1).minBy { (_, b) -> Geo.angleDifference(bearing, b!!) }.first +
            " (${bearing.toInt()}°)"
    }

/** Why a light has no reliable prediction, in a sentence or two; null when it is predictable. */
fun noPredictionReason(estimate: LightEstimate?): String? {
    val e = estimate?.estimate ?: return "No observations for this light yet."
    val elsewhere = estimate.otherBandObservations.takeIf { it > 0 }
        ?.let { " $it more observations are from other time bands and are not used now." }
        .orEmpty()
    val percent = { rate: Double -> "${(rate * 100).roundToInt()}%" }
    return when (e.blocker) {
        null -> null
        Blocker.NO_DATA -> "No observations in this time band (${estimate.slot.label}).$elsewhere"
        Blocker.TOO_FEW -> if (e.evidence >= e.evidenceNeeded) {
            // Enough observations, but all within one cycle: the cycle length cannot show yet.
            "All observations in this time band are from the same moment: pass this light again " +
                "on other trips.$elsewhere"
        } else {
            val missing = ceil(e.evidenceNeeded - e.evidence).toInt().coerceAtLeast(1)
            "Not enough data yet in this time band: about $missing more pass${if (missing == 1) "" else "es"} " +
                "needed (a stop at red or a GREEN NOW press counts about 4).$elsewhere"
        }
        Blocker.AMBIGUOUS ->
            "The cycle could be ${formatSeconds(e.plan!!.cycle)} or " +
                e.alternativeCycles.joinToString(" or ") { formatSeconds(it) } +
                ": more passes, especially when the light is red, will tell them apart."
        Blocker.IMPRECISE ->
            "A cycle of ${formatSeconds(e.plan!!.cycle)} stands out, but its timing is only known within " +
                "±${e.uncertainty.roundToInt()} s: a few more passes will sharpen it."
        Blocker.INCONSISTENT ->
            if (e.status == PlanStatus.UNPREDICTABLE) {
                "The observations fit no fixed cycle (${percent(e.errorRate)} contradict the best one): " +
                    "this light probably adapts to traffic."
            } else {
                "${percent(e.errorRate)} of the observations contradict the best cycle: keep recording, " +
                    "or delete observations that look wrong."
            }
    }
}
