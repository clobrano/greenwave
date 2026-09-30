package io.github.clobrano.greenwave.model

/** A traffic light on the route; [plan] is null if the light is not (yet) predictable. */
data class RouteSignal(
    val name: String,
    /** Distance from the start along the road, in meters. */
    val distanceFromStart: Double,
    val plan: SignalPlan?,
)

data class TripSimulation(
    val departure: Double,
    /** Instant the last light on the route is passed. */
    val arrival: Double,
    val stops: Int,
    val waitSeconds: Double,
    val stoppedAt: List<String>,
) {
    val duration: Double get() = arrival - departure
}

/**
 * Simulates a trip at a constant cruise speed: at each red light it stops until green
 * plus a restart delay. Acceleration and queues are ignored: this is for comparing
 * departure times with each other, not for predicting the arrival to the second.
 */
class TripSimulator(
    /** Cruise speed in m/s. */
    private val cruiseSpeed: Double,
    /** Seconds lost when restarting after a stop. */
    private val startupDelay: Double = 2.0,
) {
    init {
        require(cruiseSpeed > 0.0) { "cruiseSpeed must be positive: $cruiseSpeed" }
    }

    fun simulate(route: List<RouteSignal>, departure: Double): TripSimulation {
        var t = departure
        var position = 0.0
        var wait = 0.0
        val stoppedAt = mutableListOf<String>()
        for (signal in route.sortedBy { it.distanceFromStart }) {
            t += (signal.distanceFromStart - position) / cruiseSpeed
            position = signal.distanceFromStart
            val plan = signal.plan ?: continue
            if (!plan.isGreen(t)) {
                val waited = plan.nextGreenStart(t) - t + startupDelay
                t += waited
                wait += waited
                stoppedAt += signal.name
            }
        }
        return TripSimulation(departure, t, stoppedAt.size, wait, stoppedAt)
    }

    /** Simulates one departure every [step] seconds in [from, to]. */
    fun scan(route: List<RouteSignal>, from: Double, to: Double, step: Double = 15.0): List<TripSimulation> {
        require(step > 0.0) { "step must be positive: $step" }
        return generateSequence(from) { it + step }.takeWhile { it <= to }.map { simulate(route, it) }.toList()
    }
}
