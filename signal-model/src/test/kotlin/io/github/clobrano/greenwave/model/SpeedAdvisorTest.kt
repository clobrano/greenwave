package io.github.clobrano.greenwave.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedAdvisorTest {
    // Verde da 0 a 30 s, rosso da 30 a 90 s, poi di nuovo verde.
    private val plan = SignalPlan(cycle = 90.0, green = 30.0, offset = 0.0)
    private val advisor = SpeedAdvisor()
    private val limit = 50.0 / 3.6

    @Test
    fun `senza piano nessun consiglio`() {
        assertEquals(SpeedAdvice.NoPrediction, advisor.advise(null, 300.0, 0.0, 10.0, limit))
    }

    @Test
    fun `verde raggiungibile alla velocita attuale`() {
        // 200 m a 12 m/s = 16,7 s: si arriva durante il verde in corso.
        val advice = advisor.advise(plan, 200.0, 0.0, 12.0, limit) as SpeedAdvice.Go
        assertEquals(12.0, advice.targetSpeed, 1e-9)
        assertEquals(TimeWindow(0.0, 30.0), advice.window)
    }

    @Test
    fun `rallentare per prendere il verde successivo`() {
        // A t = 40 è rosso fino a 90. A 500 m e 13 m/s si arriverebbe a t = 78,5 (rosso).
        val advice = advisor.advise(plan, 500.0, 40.0, 13.0, limit) as SpeedAdvice.Go
        assertEquals(TimeWindow(90.0, 120.0), advice.window)
        assertTrue(advice.targetSpeed < 13.0)
        val arrival = 40.0 + 500.0 / advice.targetSpeed
        assertTrue(arrival >= 92.0 - 1e-6 && arrival <= 118.0 + 1e-6, "arrival $arrival")
    }

    @Test
    fun `mai oltre il limite`() {
        // Per arrivare prima delle 30 s servirebbero 600 m / 28 s = 21 m/s, oltre i 13,9 del limite.
        val advice = advisor.advise(plan, 600.0, 0.0, 13.0, limit)
        if (advice is SpeedAdvice.Go) {
            assertTrue(advice.maxSpeed <= limit + 1e-9)
            assertTrue(advice.window.start >= 90.0)
        }
    }

    @Test
    fun `rosso inevitabile quando si e troppo vicini`() {
        // A 20 m dal semaforo, rosso per altri 50 s: anche a 25 km/h si arriva col rosso.
        val advice = advisor.advise(plan, 20.0, 40.0, 8.0, limit) as SpeedAdvice.RedUnavoidable
        assertEquals(90.0, advice.nextGreenStart, 1e-9)
    }
}
