package io.github.clobrano.greenwave.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedAdvisorTest {
    // Green from 0 to 30 s, red from 30 to 90 s, then green again.
    private val plan = SignalPlan(cycle = 90.0, green = 30.0, offset = 0.0)
    private val advisor = SpeedAdvisor()
    private val limit = 50.0 / 3.6

    @Test
    fun `no plan gives no advice`() {
        assertEquals(SpeedAdvice.NoPrediction, advisor.advise(null, 300.0, 0.0, 10.0, limit))
    }

    @Test
    fun `green reachable at the current speed`() {
        // 200 m at 12 m/s = 16.7 s: arrives during the current green.
        val advice = advisor.advise(plan, 200.0, 0.0, 12.0, limit) as SpeedAdvice.Go
        assertEquals(12.0, advice.targetSpeed, 1e-9)
        assertEquals(TimeWindow(0.0, 30.0), advice.window)
    }

    @Test
    fun `slow down to catch the next green`() {
        // At t = 40 it is red until 90. At 500 m and 13 m/s the arrival would be t = 78.5 (red).
        val advice = advisor.advise(plan, 500.0, 40.0, 13.0, limit) as SpeedAdvice.Go
        assertEquals(TimeWindow(90.0, 120.0), advice.window)
        assertTrue(advice.targetSpeed < 13.0)
        val arrival = 40.0 + 500.0 / advice.targetSpeed
        assertTrue(arrival >= 92.0 - 1e-6 && arrival <= 118.0 + 1e-6, "arrival $arrival")
    }

    @Test
    fun `never above the speed limit`() {
        // Arriving before 30 s would need 600 m / 28 s = 21 m/s, above the 13.9 m/s limit.
        val advice = advisor.advise(plan, 600.0, 0.0, 13.0, limit)
        if (advice is SpeedAdvice.Go) {
            assertTrue(advice.maxSpeed <= limit + 1e-9)
            assertTrue(advice.window.start >= 90.0)
        }
    }

    @Test
    fun `red is unavoidable when too close`() {
        // 20 m from the light, red for 50 more seconds: even at 25 km/h it arrives on red.
        val advice = advisor.advise(plan, 20.0, 40.0, 8.0, limit) as SpeedAdvice.RedUnavoidable
        assertEquals(90.0, advice.nextGreenStart, 1e-9)
    }
}
