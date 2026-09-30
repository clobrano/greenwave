package io.github.clobrano.greenwave.model

import io.github.clobrano.greenwave.model.ObservationKind.GREEN_SEEN
import io.github.clobrano.greenwave.model.ObservationKind.GREEN_START
import io.github.clobrano.greenwave.model.ObservationKind.RED_SEEN
import io.github.clobrano.greenwave.model.ObservationKind.RED_START
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class PlanEstimatorTest {
    private val truth = SignalPlan(cycle = 92.5, green = 38.0, offset = 27.0)
    private val estimator = PlanEstimator()

    /** Passaggi simulati nell'arco di due ore (7:00-9:00 in secondi dalla mezzanotte). */
    private fun passes(count: Int, seed: Int): List<Double> {
        val random = Random(seed)
        return List(count) { 7 * 3600.0 + random.nextDouble() * 7200.0 }.sorted()
    }

    /** Ciò che l'app registrerebbe passando al semaforo a [arrival]: fermata + ripartenza, oppure passaggio al verde. */
    private fun observe(arrival: Double, noise: Random): List<Observation> =
        if (truth.isGreen(arrival)) {
            listOf(Observation(arrival, GREEN_SEEN))
        } else {
            listOf(
                Observation(arrival, RED_SEEN),
                Observation(truth.nextGreenStart(arrival) + noise.nextDouble(-1.0, 1.0), GREEN_START),
            )
        }

    private fun assertSameTiming(expected: SignalPlan, actual: SignalPlan, tolerance: Double) {
        assertEquals(expected.cycle, actual.cycle, 0.05, "cycle")
        // Offset confrontato in fase: può differire di un multiplo del ciclo.
        val offsetError = abs(signedMod(actual.offset - expected.offset, expected.cycle))
        assertTrue(offsetError <= tolerance, "offset error $offsetError")
    }

    @Test
    fun `pochi dati non danno un piano`() {
        val estimate = estimator.estimate(listOf(Observation(1000.0, GREEN_START)))
        assertNull(estimate.plan)
        assertEquals(PlanStatus.LEARNING, estimate.status)
    }

    @Test
    fun `ricostruisce il piano da fermate e passaggi rumorosi`() {
        val noise = Random(1)
        val observations = passes(25, seed = 42).flatMap { observe(it, noise) }

        val estimate = estimator.estimate(observations)

        val plan = estimate.plan
        assertNotNull(plan)
        plan!!
        assertSameTiming(truth, plan, tolerance = 1.5)
        assertEquals(truth.green, plan.green, 4.0)
        assertTrue(estimate.greenMeasured)
        assertEquals(0, estimate.contradictions)
        assertEquals(PlanStatus.RELIABLE, estimate.status)
    }

    @Test
    fun `i soli inizi verde lasciano ambiguo il ciclo dimezzato`() {
        val truth90 = SignalPlan(cycle = 90.0, green = 40.0, offset = 5.0)
        val observations = listOf(3, 7, 12, 20, 33, 41).map {
            Observation(truth90.offset + 7 * 3600.0 + it * truth90.cycle, GREEN_START)
        }

        val estimate = estimator.estimate(observations)

        assertEquals(90.0, estimate.plan!!.cycle, 0.05)
        assertTrue(estimate.alternativeCycles.any { abs(it - 45.0) < 0.1 }, "${estimate.alternativeCycles}")
        assertFalse(estimate.greenMeasured)
        assertEquals(PlanStatus.LEARNING, estimate.status)
    }

    @Test
    fun `tasto rosso e attese al rosso risolvono l'ambiguita`() {
        // Come in M1: VERDE ORA e ROSSO ORA a mano, più l'istante della fermata dal GPS.
        val truth90 = SignalPlan(cycle = 90.0, green = 40.0, offset = 5.0)
        val base = 7 * 3600.0 + truth90.offset
        val observations = mutableListOf<Observation>()
        // Arrivi al rosso (fase 55, 70, 60) e attesa fino al verde successivo.
        listOf(3 to 55.0, 8 to 70.0, 21 to 60.0).forEach { (k, arrivalPhase) ->
            observations += Observation(base + k * 90.0 + arrivalPhase + 1.0, RED_SEEN)
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
    fun `il tasto rosso misura la durata del verde`() {
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
    fun `un semaforo adattivo risulta imprevedibile`() {
        val random = Random(7)
        var t = 7 * 3600.0
        val observations = List(20) {
            t += random.nextDouble(50.0, 140.0) * random.nextInt(1, 4)
            Observation(t, GREEN_START)
        }

        val estimate = estimator.estimate(observations)

        assertEquals(PlanStatus.UNPREDICTABLE, estimate.status)
    }
}
