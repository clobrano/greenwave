package io.github.clobrano.greenwave.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** One GPS sample while driving. */
data class DriveSample(
    /** GNSS time, epoch milliseconds. */
    val timeMillis: Long,
    val position: GeoPoint,
    /** Speed in m/s, null if unknown. */
    val speed: Double?,
    /** Driving direction in degrees, null if unknown (e.g. while stopped). */
    val heading: Double?,
)

/** An observation inferred from the trajectory, for the light with id [lightId]. */
data class DetectedObservation(val lightId: Long, val timeMillis: Long, val kind: ObservationKind)

/**
 * Infers traffic light colors from the car's trajectory, without any button.
 *
 * Only lights with a driving direction are tracked, and only while the car drives in that
 * direction: at a crossroads, crossing on the other road passes close to the lights of the
 * main road but must not count for them. While approaching a light (ahead of the car, on the
 * same road, matching its driving direction) it records the stops; turning off into another
 * road before the light drops it. When the car passes the light it decides:
 * - no stop near the light: it was green when the car crossed it (GREEN_SEEN);
 * - stopped near the light: it was red from the first stop (RED_SEEN), and it turned
 *   green shortly before the last restart (GREEN_START), minus the reaction delay and
 *   the time the cars queued ahead took to move.
 *
 * Feed it with [onSample] in time order; it returns the observations decided by each sample.
 */
class PassDetector(
    lights: List<LightPosition>,
    private val config: Config = Config(),
) {
    data class Config(
        /** A light starts being tracked when it is this close ahead (m). */
        val approachDistance: Double = 150.0,
        /** Maximum sideways distance (m) of a light from the driving line to be "on my road". */
        val lateralTolerance: Double = 25.0,
        /** Maximum difference (degrees) between my direction and the light's driving direction. */
        val maxAngle: Double = 45.0,
        /**
         * Turning away from the light's direction farther than this (m) from the light means
         * I left its road; closer than this it is the turn at the intersection itself, after
         * crossing the stop line, so the pass still counts.
         */
        val turnOffDistance: Double = 20.0,
        /** Only stops this close to the light (m) count as stops at that light. */
        val stopZone: Double = 60.0,
        /** Beyond this distance (m) the queue is too long to estimate the start of green. */
        val maxQueueForGreen: Double = 40.0,
        /** Distance (m) from the light marker where the first car in line usually stops. */
        val firstCarDistance: Double = 12.0,
        /** Space (m) taken by each car queued ahead. */
        val carSpacing: Double = 7.5,
        /** Seconds each queued car ahead delays my restart. */
        val perCarDelay: Double = 2.0,
        /** Seconds between green and the first car moving. */
        val reactionDelay: Double = 2.0,
        val stoppedSpeed: Double = 1.0,
        val movingSpeed: Double = 2.5,
        /** Shorter stops (s) are ignored. */
        val minStopSeconds: Double = 3.0,
        /** A tracked light is dropped when the car gets this far from it (m). */
        val abandonDistance: Double = 250.0,
    )

    private data class Stop(val startMillis: Long, val distance: Double, var restartMillis: Long? = null)

    private class Approach(val light: LightPosition) {
        val stops = mutableListOf<Stop>()
        var lastAlong: Double? = null
        var lastTimeMillis: Long = 0
    }

    private var lights: List<LightPosition> = lights
    private var approach: Approach? = null
    private var lastHeading: Double? = null

    /** Replaces the known lights (e.g. after the user adds one); the light being tracked is kept. */
    fun updateLights(lights: List<LightPosition>) {
        this.lights = lights
    }

    fun onSample(sample: DriveSample): List<DetectedObservation> {
        sample.heading?.let { lastHeading = it }
        val current = approach ?: findApproach(sample.position, lastHeading)?.also { approach = it } ?: return emptyList()
        val bearing = checkNotNull(current.light.approachBearing)

        // Distance before the light measured along the light's own road (negative = past it).
        // Unlike the car's heading, this does not change when the car turns.
        val along = alongTrack(sample.position, current.light.position, bearing)

        // Driving off the light's direction well before it means I turned into another road.
        if (sample.heading != null && along > config.turnOffDistance &&
            Geo.angleDifference(sample.heading, bearing) > config.maxAngle
        ) {
            approach = null
            return emptyList()
        }

        trackStops(current, sample, along)

        val result = when {
            along <= 0.0 && (current.lastAlong ?: 1.0) > 0.0 -> finish(current, crossingTime(current, sample, along))
            Geo.distance(sample.position, current.light.position) > config.abandonDistance -> {
                approach = null
                emptyList()
            }
            else -> emptyList()
        }
        current.lastAlong = along
        current.lastTimeMillis = sample.timeMillis
        return result
    }

    /** Nearest light ahead of the car, on its road and in its driving direction. */
    private fun findApproach(me: GeoPoint, heading: Double?): Approach? {
        if (heading == null) return null
        return lights
            .filter { light ->
                val distance = Geo.distance(me, light.position)
                val along = alongTrack(me, light.position, heading)
                val lateral = abs(crossTrack(me, light.position, heading))
                // A light without a direction could be any of the lights at a crossroads: skip it.
                light.approachBearing != null &&
                    distance <= config.approachDistance && along > 0.0 && lateral <= config.lateralTolerance &&
                    Geo.angleDifference(heading, light.approachBearing) <= config.maxAngle
            }
            .minByOrNull { Geo.distance(me, it.position) }
            ?.let { Approach(it) }
    }

    private fun trackStops(current: Approach, sample: DriveSample, distanceAhead: Double) {
        val speed = sample.speed ?: return
        val open = current.stops.lastOrNull()?.takeIf { it.restartMillis == null }
        when {
            speed < config.stoppedSpeed && open == null ->
                current.stops += Stop(sample.timeMillis, max(distanceAhead, 0.0))
            speed > config.movingSpeed && open != null -> {
                if ((sample.timeMillis - open.startMillis) / 1000.0 >= config.minStopSeconds) {
                    open.restartMillis = sample.timeMillis
                } else {
                    current.stops.remove(open)
                }
            }
        }
    }

    private fun finish(current: Approach, crossingMillis: Long): List<DetectedObservation> {
        approach = null
        val id = current.light.id
        val stops = current.stops.filter { it.restartMillis != null && it.distance <= config.stopZone }
        if (stops.isEmpty()) return listOf(DetectedObservation(id, crossingMillis, ObservationKind.GREEN_SEEN))

        val result = mutableListOf(DetectedObservation(id, stops.first().startMillis + 1_000, ObservationKind.RED_SEEN))
        val last = stops.last()
        if (last.distance <= config.maxQueueForGreen) {
            val carsAhead = max(0.0, (last.distance - config.firstCarDistance) / config.carSpacing)
            val delaySeconds = config.reactionDelay + carsAhead * config.perCarDelay
            result += DetectedObservation(id, last.restartMillis!! - (delaySeconds * 1000).toLong(), ObservationKind.GREEN_START)
        }
        return result
    }

    /** Time the car crossed the light, interpolated between the last two samples. */
    private fun crossingTime(current: Approach, sample: DriveSample, along: Double): Long {
        val previous = current.lastAlong ?: return sample.timeMillis
        if (previous <= along) return sample.timeMillis
        val fraction = previous / (previous - along)
        return current.lastTimeMillis + ((sample.timeMillis - current.lastTimeMillis) * fraction).toLong()
    }

    /** Distance of [target] ahead of [me] along [heading] (negative = behind). */
    private fun alongTrack(me: GeoPoint, target: GeoPoint, heading: Double): Double =
        Geo.distance(me, target) * cos(Math.toRadians(Geo.bearing(me, target) - heading))

    /** Sideways distance of [target] from the line through [me] along [heading]. */
    private fun crossTrack(me: GeoPoint, target: GeoPoint, heading: Double): Double =
        Geo.distance(me, target) * sin(Math.toRadians(Geo.bearing(me, target) - heading))
}
