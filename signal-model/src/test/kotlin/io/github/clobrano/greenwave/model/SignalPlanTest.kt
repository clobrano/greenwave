package io.github.clobrano.greenwave.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignalPlanTest {
    // Cycle 90 s, green 40 s, green starting at t = 10, 100, 190...
    private val plan = SignalPlan(cycle = 90.0, green = 40.0, offset = 10.0)

    @Test
    fun `color within the cycle`() {
        assertTrue(plan.isGreen(10.0))
        assertTrue(plan.isGreen(49.9))
        assertFalse(plan.isGreen(50.0))
        assertFalse(plan.isGreen(99.9))
        assertTrue(plan.isGreen(100.0))
        // Also before the offset.
        assertTrue(plan.isGreen(-80.0 + 5.0))
    }

    @Test
    fun `next start of green`() {
        assertEquals(10.0, plan.nextGreenStart(10.0), 1e-9)
        assertEquals(100.0, plan.nextGreenStart(10.5), 1e-9)
        assertEquals(100.0, plan.nextGreenStart(60.0), 1e-9)
    }

    @Test
    fun `seconds to the next change`() {
        assertEquals(30.0, plan.secondsToChange(20.0), 1e-9)
        assertEquals(40.0, plan.secondsToChange(60.0), 1e-9)
    }

    @Test
    fun `green windows in the interval`() {
        val windows = plan.greenWindows(30.0, 200.0)
        assertEquals(
            listOf(TimeWindow(10.0, 50.0), TimeWindow(100.0, 140.0), TimeWindow(190.0, 230.0)),
            windows,
        )
    }
}
