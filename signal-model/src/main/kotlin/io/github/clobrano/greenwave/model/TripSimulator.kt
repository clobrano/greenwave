package io.github.clobrano.greenwave.model

/** Un semaforo del percorso; [plan] è null se il semaforo non è (ancora) prevedibile. */
data class RouteSignal(
    val name: String,
    /** Distanza dalla partenza lungo la strada, in metri. */
    val distanceFromStart: Double,
    val plan: SignalPlan?,
)

data class TripSimulation(
    val departure: Double,
    /** Istante di passaggio all'ultimo semaforo del percorso. */
    val arrival: Double,
    val stops: Int,
    val waitSeconds: Double,
    val stoppedAt: List<String>,
) {
    val duration: Double get() = arrival - departure
}

/**
 * Simula un tragitto a velocità di crociera costante: a ogni semaforo rosso ci si ferma
 * fino al verde più un ritardo di ripartenza. Accelerazioni e code sono ignorate:
 * serve a confrontare orari di partenza tra loro, non a prevedere l'arrivo al secondo.
 */
class TripSimulator(
    /** Velocità di crociera in m/s. */
    private val cruiseSpeed: Double,
    /** Secondi persi alla ripartenza dopo una fermata. */
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

    /** Simula una partenza ogni [step] secondi in [from, to]. */
    fun scan(route: List<RouteSignal>, from: Double, to: Double, step: Double = 15.0): List<TripSimulation> {
        require(step > 0.0) { "step must be positive: $step" }
        return generateSequence(from) { it + step }.takeWhile { it <= to }.map { simulate(route, it) }.toList()
    }
}
