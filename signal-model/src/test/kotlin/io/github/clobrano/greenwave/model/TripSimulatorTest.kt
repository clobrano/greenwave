package io.github.clobrano.greenwave.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TripSimulatorTest {
    private val simulator = TripSimulator(cruiseSpeed = 10.0, startupDelay = 2.0)

    // Due semafori a 100 m e 400 m, stesso ciclo ma sfasati.
    private val route = listOf(
        RouteSignal("A", 100.0, SignalPlan(cycle = 60.0, green = 30.0, offset = 0.0)),
        RouteSignal("B", 400.0, SignalPlan(cycle = 60.0, green = 20.0, offset = 45.0)),
        RouteSignal("Pedonale", 250.0, null),
    )

    @Test
    fun `fermata al rosso e ripartenza`() {
        // Partenza a 25: A a t = 35 (rosso fino a 60) -> riparte a 62; B a t = 92 (rosso fino a 105).
        val trip = simulator.simulate(route, 25.0)
        assertEquals(2, trip.stops)
        assertEquals(listOf("A", "B"), trip.stoppedAt)
        assertEquals(27.0 + 15.0, trip.waitSeconds, 1e-9)
        assertEquals(107.0, trip.arrival, 1e-9)
    }

    @Test
    fun `esiste una partenza senza fermate`() {
        // Partenza a 5: A a t = 15 (verde), B a t = 45 (verde da 45 a 65).
        val trips = simulator.scan(route, 0.0, 60.0, step = 5.0)
        val best = trips.minBy { it.stops }
        assertEquals(0, best.stops)
        assertTrue(trips.any { it.stops == 2 })
    }
}
