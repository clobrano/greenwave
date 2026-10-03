package io.github.clobrano.greenwave.model

import io.github.clobrano.greenwave.model.ObservationKind.GREEN_SEEN
import io.github.clobrano.greenwave.model.ObservationKind.GREEN_START
import io.github.clobrano.greenwave.model.ObservationKind.RED_SEEN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PassDetectorTest {
    private val start = GeoPoint(45.0, 9.0)

    /** Northbound light 400 m north of the start, and one for the opposite lane. */
    private val northbound = LightPosition(1, Geo.offset(start, 0.0, 400.0), approachBearing = 0.0)
    private val southbound = LightPosition(2, Geo.offset(start, 0.0, 405.0), approachBearing = 180.0)

    /**
     * Drives north from [start] sampling once per second. Each leg is (seconds, speed m/s);
     * a leg with speed 0 is a stop. Returns everything the detector decided.
     */
    private fun drive(vararg legs: Pair<Int, Double>, detector: PassDetector = PassDetector(listOf(northbound, southbound))): List<DetectedObservation> {
        val out = mutableListOf<DetectedObservation>()
        var t = 0L
        var traveled = 0.0
        for ((seconds, speed) in legs) {
            repeat(seconds) {
                traveled += speed
                t += 1000
                val heading = if (speed > 2.0) 0.0 else null
                out += detector.onSample(DriveSample(t, Geo.offset(start, 0.0, traveled), speed, heading))
            }
        }
        return out
    }

    @Test
    fun `passing without stopping means green`() {
        // 10 m/s: the light at 400 m is crossed at t = 40 s.
        val events = drive(60 to 10.0)

        assertEquals(1, events.size)
        assertEquals(GREEN_SEEN, events[0].kind)
        assertEquals(1L, events[0].lightId)
        assertEquals(40_000.0, events[0].timeMillis.toDouble(), 1_000.0)
    }

    @Test
    fun `stop at the light then restart means red then green`() {
        // 39 s at 10 m/s -> 390 m (10 m before the light), stopped from t = 40 s to t = 70 s.
        val events = drive(39 to 10.0, 30 to 0.0, 20 to 10.0)

        val red = events.single { it.kind == RED_SEEN }
        val green = events.single { it.kind == GREEN_START }
        assertEquals(41_000L, red.timeMillis)
        // Restart detected at t = 70 s, minus the 2 s reaction delay, no cars ahead.
        assertEquals(68_000L, green.timeMillis)
    }

    @Test
    fun `cars queued ahead move the start of green earlier`() {
        // 73 s at 5 m/s -> 365 m: stopped 35 m before the light, about 3 cars ahead.
        val events = drive(73 to 5.0, 30 to 0.0, 20 to 10.0)

        val green = events.single { it.kind == GREEN_START }
        val restart = (73 + 30 + 1) * 1000L
        val carsAhead = (35.0 - 12.0) / 7.5
        val expected = restart - ((2.0 + carsAhead * 2.0) * 1000).toLong()
        assertEquals(expected.toDouble(), green.timeMillis.toDouble(), 1.0)
    }

    @Test
    fun `stop too far back gives only red`() {
        // Stopped 55 m before the light: inside the stop zone but the queue is too long.
        val events = drive(69 to 5.0, 30 to 0.0, 20 to 10.0)

        assertEquals(listOf(RED_SEEN), events.map { it.kind })
    }

    @Test
    fun `stop far from the light is ignored`() {
        // Stopped 200 m before the light (e.g. for a pedestrian), then passed on green.
        val events = drive(20 to 10.0, 10 to 0.0, 30 to 10.0)

        assertEquals(listOf(GREEN_SEEN), events.map { it.kind })
    }

    @Test
    fun `light for the opposite lane is ignored`() {
        val events = drive(60 to 10.0, detector = PassDetector(listOf(southbound)))

        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `offset point is at the given distance and bearing`() {
        val p = Geo.offset(start, 90.0, 250.0)
        assertEquals(250.0, Geo.distance(start, p), 0.1)
        assertEquals(90.0, Geo.bearing(start, p), 0.1)
    }
}
