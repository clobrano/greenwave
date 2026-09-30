package io.github.clobrano.greenwave.model

/** Speed advice toward the next traffic light. Speeds in m/s, times in seconds. */
sealed interface SpeedAdvice {
    /** Arriving at a speed in [minSpeed, maxSpeed] finds green; [targetSpeed] is the closest to the current one. */
    data class Go(
        val targetSpeed: Double,
        val minSpeed: Double,
        val maxSpeed: Double,
        val window: TimeWindow,
    ) : SpeedAdvice

    /** No allowed speed arrives on green: slow down gently. */
    data class RedUnavoidable(
        /** Start of the first reachable green. */
        val nextGreenStart: Double,
        /** Speed (possibly below the minimum) to arrive right at the start of that green. */
        val speedToCatchIt: Double,
    ) : SpeedAdvice

    /** No reliable plan for this light. */
    data object NoPrediction : SpeedAdvice
}

/**
 * GLOSA (Green Light Optimal Speed Advisory): which speed to keep to reach the next
 * traffic light during a green window.
 *
 * The arrival at `now + d / v` must fall in a window [a, b] shrunk by a safety margin at
 * both edges, so `v ∈ [d / (b - margin - now), d / (a + margin - now)]`, intersected with
 * [minimum speed, limit]. It never advises going over the speed limit.
 */
class SpeedAdvisor(private val config: Config = Config()) {

    data class Config(
        /** Below this speed you hold up traffic (default 25 km/h). */
        val minSpeed: Double = 25.0 / 3.6,
        /** Margin (s) from the edges of the green window. */
        val safetyMargin: Double = 2.0,
        /** How far ahead (s) to look for green windows. */
        val horizon: Double = 300.0,
    )

    fun advise(
        plan: SignalPlan?,
        distance: Double,
        now: Double,
        currentSpeed: Double,
        speedLimit: Double,
    ): SpeedAdvice {
        if (plan == null || plan.green <= 2 * config.safetyMargin) return SpeedAdvice.NoPrediction
        val vMax = speedLimit
        val vMin = minOf(config.minSpeed, vMax)
        val d = maxOf(distance, 0.0)

        for (window in plan.greenWindows(now, now + config.horizon)) {
            // A window that has already started needs no margin at its start.
            val earliest = if (window.start > now) window.start + config.safetyMargin else now
            val latest = window.end - config.safetyMargin
            if (latest <= earliest || latest <= now) continue

            val lo = maxOf(d / (latest - now), vMin)
            val hi = if (earliest <= now) vMax else minOf(d / (earliest - now), vMax)
            if (lo <= hi) {
                return SpeedAdvice.Go(currentSpeed.coerceIn(lo, hi), lo, hi, window)
            }
        }

        val earliestArrival = now + d / vMax
        val nextGreen = plan.nextGreenStart(earliestArrival)
        val catchTime = nextGreen + config.safetyMargin - now
        return SpeedAdvice.RedUnavoidable(nextGreen, if (catchTime > 0) d / catchTime else vMin)
    }
}
