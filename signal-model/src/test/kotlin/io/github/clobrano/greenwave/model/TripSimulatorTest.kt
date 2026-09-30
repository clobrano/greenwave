package io.github.clobrano.greenwave.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TripSimulatorTest {
    private val simulator = TripSimulator(cruiseSpeed = 10.0, startupDelay = 2.0)

    // Two lights at 100 m and 400 m, same cycle but out of phase.
    private val route = listOf(
        RouteSignal("A", 100.0, SignalPlan(cycle = 60.0, green = 30.0, offset = 0.0)),
        RouteSignal("B", 400.0, SignalPlan(cycle = 60.0, green = 20.0, offset = 45.0)),
        RouteSignal("Pedestrian", 250.0, null),
    )

    @Test
    fun `stop at red and restart`() {
        // Departure at 25: A at t = 35 (red until 60) -> restarts at 62; B at t = 92 (red until 105).
        val trip = simulator.simulate(route, 25.0)
        assertEquals(2, trip.stops)
        assertEquals(listOf("A", "B"), trip.stoppedAt)
        assertEquals(27.0 + 15.0, trip.waitSeconds, 1e-9)
        assertEquals(107.0, trip.arrival, 1e-9)
    }

    @Test
    fun `there is a departure without stops`() {
        // Departure at 5: A at t = 15 (green), B at t = 45 (green from 45 to 65).
        val trips = simulator.scan(route, 0.0, 60.0, step = 5.0)
        val best = trips.minBy { it.stops }
        assertEquals(0, best.stops)
        assertTrue(trips.any { it.stops == 2 })
    }
}
