package io.github.clobrano.greenwave.model

import io.github.clobrano.greenwave.model.ObservationKind.GREEN_SEEN
import io.github.clobrano.greenwave.model.ObservationKind.GREEN_START
import io.github.clobrano.greenwave.model.ObservationKind.RED_SEEN
import io.github.clobrano.greenwave.model.ObservationKind.RED_START
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class PlanEstimatorTest {
    private val truth = SignalPlan(cycle = 92.5, green = 38.0, offset = 27.0)
    private val estimator = PlanEstimator()

    /** Arrival times at the light over two hours (7:00-9:00, in seconds since midnight). */
    private fun arrivals(count: Int, seed: Int): List<Double> {
        val random = Random(seed)
        return List(count) { 7 * 3600.0 + random.nextDouble() * 7200.0 }.sorted()
    }

    /**
     * What auto-recording would save driving through the light at [arrival]: a pass on
     * green, or a stop on red followed by a start of green inferred from GPS (a few seconds
     * off, so not precise).
     */
    private fun autoRecorded(arrival: Double, noise: Random, gpsNoise: Double = 3.0): List<Observation> =
        if (truth.isGreen(arrival)) {
            listOf(Observation(arrival, GREEN_SEEN, precise = false))
        } else {
            listOf(
                Observation(arrival + 1.0, RED_SEEN, precise = false),
                Observation(
                    truth.nextGreenStart(arrival) + noise.nextDouble(-gpsNoise, gpsNoise),
                    GREEN_START,
                    precise = false,
                ),
            )
        }

    /** Only the color at each pass, as if every pass and stop gave a single seen color. */
    private fun passesOnly(arrivalTimes: List<Double>): List<Observation> =
        arrivalTimes.map { Observation(it, if (truth.isGreen(it)) GREEN_SEEN else RED_SEEN, precise = false) }

    private fun assertSameTiming(expected: SignalPlan, actual: SignalPlan, tolerance: Double) {
        assertEquals(expected.cycle, actual.cycle, 0.05, "cycle")
        // Start of green compared at 8:00, in the middle of the data, where the plan is used:
        // comparing the offsets (at midnight) would multiply any cycle error by ~300 cycles.
        val eight = 8 * 3600.0
        val startError = abs(signedMod(actual.nextGreenStart(eight) - expected.nextGreenStart(eight), expected.cycle))
        assertTrue(startError <= tolerance, "start of green error $startError")
    }

    @Test
    fun `no data and too little data give no plan and say why`() {
        val none = estimator.estimate(emptyList())
        assertNull(none.plan)
        assertEquals(Blocker.NO_DATA, none.blocker)

        val few = estimator.estimate(passesOnly(arrivals(5, seed = 3)))
        assertNull(few.plan)
        assertEquals(PlanStatus.LEARNING, few.status)
        assertEquals(Blocker.TOO_FEW, few.blocker)
        assertEquals(5.0, few.evidence, 1e-9)
        assertEquals(12.0, few.evidenceNeeded, 1e-9)
    }

    /** Share of random instants in the 7:00-9:00 band where [plan] predicts the true color. */
    private fun agreement(plan: SignalPlan): Double {
        val random = Random(99)
        val times = List(2000) { 7 * 3600.0 + random.nextDouble() * 7200.0 }
        return times.count { plan.isGreen(it) == truth.isGreen(it) } / times.size.toDouble()
    }

    @Test
    fun `passes alone are enough to find the cycle`() {
        // Only "seen green" / "seen red" at 150 passes, no stop and no button.
        val estimate = estimator.estimate(passesOnly(arrivals(150, seed = 11)))

        val plan = estimate.plan!!
        assertEquals(truth.cycle, plan.cycle, 0.1)
        assertEquals(truth.green, plan.green, 4.0)
        val agreement = agreement(plan)
        assertTrue(agreement >= 0.95, "predicted color right only ${agreement * 100}% of the time")
        assertEquals(0.0, estimate.errorRate, 1e-9)
        assertEquals(PlanStatus.RELIABLE, estimate.status, "$estimate")
        assertNull(estimate.blocker)
    }

    @Test
    fun `automatically recorded stops and passes give a reliable plan`() {
        val noise = Random(1)
        val observations = arrivals(50, seed = 42).flatMap { autoRecorded(it, noise) }

        val estimate = estimator.estimate(observations)

        val plan = estimate.plan!!
        assertSameTiming(truth, plan, tolerance = 2.5)
        assertEquals(truth.green, plan.green, 4.0)
        assertTrue(agreement(plan) >= 0.95, "agreement ${agreement(plan)}")
        assertTrue(estimate.greenMeasured)
        assertEquals(PlanStatus.RELIABLE, estimate.status, "$estimate")
    }

    @Test
    fun `a cycle found from little data is not called reliable`() {
        // 30 passes: the cycle stands out, but its timing is still too loose to rely on.
        val estimate = estimator.estimate(passesOnly(arrivals(30, seed = 4)))

        assertTrue(estimate.plan != null)
        assertEquals(PlanStatus.LEARNING, estimate.status, "$estimate")
        assertTrue(estimate.blocker == Blocker.IMPRECISE || estimate.blocker == Blocker.AMBIGUOUS, "$estimate")
    }

    @Test
    fun `starts of green alone leave the half cycle ambiguous`() {
        val truth90 = SignalPlan(cycle = 90.0, green = 40.0, offset = 5.0)
        val observations = listOf(3, 7, 12, 20, 33, 41).map {
            Observation(truth90.offset + 7 * 3600.0 + it * truth90.cycle, GREEN_START)
        }

        val estimate = estimator.estimate(observations)

        assertEquals(90.0, estimate.plan!!.cycle, 0.05)
        assertTrue(estimate.alternativeCycles.any { abs(it - 45.0) < 0.1 }, "${estimate.alternativeCycles}")
        assertEquals(Blocker.AMBIGUOUS, estimate.blocker)
        assertFalse(estimate.greenMeasured)
        assertEquals(PlanStatus.LEARNING, estimate.status)
    }

    @Test
    fun `red button and waits at red resolve the ambiguity`() {
        // As in M1: GREEN NOW and RED NOW by hand, plus the instant of the stop from GPS.
        val truth90 = SignalPlan(cycle = 90.0, green = 40.0, offset = 5.0)
        val base = 7 * 3600.0 + truth90.offset
        val observations = mutableListOf<Observation>()
        // Arrivals at red (phase 55, 70, 60) and wait until the next green.
        listOf(3 to 55.0, 8 to 70.0, 21 to 60.0).forEach { (k, arrivalPhase) ->
            observations += Observation(base + k * 90.0 + arrivalPhase + 1.0, RED_SEEN, precise = false)
            observations += Observation(base + (k + 1) * 90.0, GREEN_START)
        }
        listOf(5, 12).forEach { k -> observations += Observation(base + k * 90.0 + truth90.green, RED_START) }

        val estimate = estimator.estimate(observations)

        val plan = estimate.plan!!
        assertEquals(90.0, plan.cycle, 0.05)
        assertEquals(40.0, plan.green, 0.5)
        assertTrue(estimate.alternativeCycles.isEmpty(), "${estimate.alternativeCycles}")
        assertEquals(PlanStatus.RELIABLE, estimate.status)
    }

    @Test
    fun `red button measures the green duration`() {
        val base = 7 * 3600.0
        val observations = listOf(0, 4, 9, 15).flatMap { k ->
            val start = truth.offset + base + k * truth.cycle
            listOf(Observation(start, GREEN_START), Observation(start + truth.green, RED_START))
        }

        val estimate = estimator.estimate(observations)

        assertEquals(truth.green, estimate.plan!!.green, 0.5)
        assertTrue(estimate.greenMeasured)
    }

    @Test
    fun `an adaptive light is unpredictable`() {
        // Colors that follow no fixed cycle.
        val random = Random(7)
        val observations = arrivals(60, seed = 5).map {
            Observation(it, if (random.nextBoolean()) GREEN_SEEN else RED_SEEN, precise = false)
        }

        val estimate = estimator.estimate(observations)

        assertEquals(PlanStatus.UNPREDICTABLE, estimate.status, "$estimate")
        assertEquals(Blocker.INCONSISTENT, estimate.blocker)
    }
}
