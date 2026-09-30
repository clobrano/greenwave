package io.github.clobrano.greenwave.model

import kotlin.math.floor

/** Time interval [start, end), in seconds. */
data class TimeWindow(val start: Double, val end: Double) {
    val duration: Double get() = end - start
    operator fun contains(t: Double): Boolean = t >= start && t < end
}

/**
 * Plan of a fixed-time traffic light.
 *
 * Green starts at `offset + k * cycle` (k integer) and lasts `green` seconds;
 * the rest of the cycle is red (amber counts as red).
 *
 * All times are in seconds on a scale chosen by the caller: the app uses seconds since
 * local midnight, so a plan learned on one day also applies to the following days.
 */
data class SignalPlan(val cycle: Double, val green: Double, val offset: Double) {
    init {
        require(cycle > 0.0) { "cycle must be positive: $cycle" }
        require(green in 0.0..cycle) { "green must be in [0, cycle]: $green" }
    }

    /** Seconds since the last start of green, in [0, cycle). */
    fun phaseAt(t: Double): Double = positiveMod(t - offset, cycle)

    fun isGreen(t: Double): Boolean = phaseAt(t) < green

    /** First start of green at or after [t]. */
    fun nextGreenStart(t: Double): Double {
        val phase = phaseAt(t)
        return if (phase == 0.0) t else t + cycle - phase
    }

    /** Seconds until the next color change. */
    fun secondsToChange(t: Double): Double {
        val phase = phaseAt(t)
        return if (phase < green) green - phase else cycle - phase
    }

    /** Green windows overlapping the interval [from, until). */
    fun greenWindows(from: Double, until: Double): List<TimeWindow> {
        if (green <= 0.0) return emptyList()
        val windows = mutableListOf<TimeWindow>()
        var start = offset + floor((from - offset) / cycle) * cycle
        while (start < until) {
            val end = start + green
            if (end > from) windows += TimeWindow(start, end)
            start += cycle
        }
        return windows
    }
}

/** Always-positive modulo, in [0, m). */
internal fun positiveMod(a: Double, m: Double): Double {
    val r = a % m
    return if (r < 0.0) r + m else r
}

/** Signed distance from the nearest multiple of [m], in [-m/2, m/2). */
internal fun signedMod(a: Double, m: Double): Double = positiveMod(a + m / 2.0, m) - m / 2.0
