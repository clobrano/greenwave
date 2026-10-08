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

    /** A leg of a free-form drive: [seconds] at [speed] m/s toward [bearing] degrees. */
    private data class Leg(val seconds: Int, val speed: Double, val bearing: Double)

    /** Like [drive] but starting anywhere and turning between legs. */
    private fun route(from: GeoPoint, lights: List<LightPosition>, vararg legs: Leg): List<DetectedObservation> {
        val detector = PassDetector(lights)
        val out = mutableListOf<DetectedObservation>()
        var t = 0L
        var position = from
        for (leg in legs) {
            repeat(leg.seconds) {
                position = Geo.offset(position, leg.bearing, leg.speed)
                t += 1000
                val heading = if (leg.speed > 2.0) leg.bearing else null
                out += detector.onSample(DriveSample(t, position, leg.speed, heading))
            }
        }
        return out
    }

    // A crossroads 400 m north of the start: the main road runs north-south and both of its
    // lights are tracked; the secondary road runs east-west and its lights are not.
    private val crossroads = Geo.offset(start, 0.0, 400.0)
    private val mainNorthbound = LightPosition(10, Geo.offset(crossroads, 180.0, 8.0), approachBearing = 0.0)
    private val mainSouthbound = LightPosition(11, Geo.offset(crossroads, 0.0, 8.0), approachBearing = 180.0)

    @Test
    fun `crossing on the secondary road does not count for the main road lights`() {
        // Eastbound through the crossroads, stopping 10 m before it and then moving on:
        // the car passes a few meters from both main road lights.
        val west = Geo.offset(crossroads, 270.0, 400.0)
        val events = route(
            west, listOf(mainNorthbound, mainSouthbound),
            Leg(39, 10.0, 90.0), Leg(30, 0.0, 90.0), Leg(40, 10.0, 90.0),
        )

        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `light without a direction is not recorded automatically`() {
        val anyDirection = LightPosition(20, Geo.offset(start, 0.0, 400.0), approachBearing = null)

        val events = route(start, listOf(anyDirection), Leg(60, 10.0, 0.0))

        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `turning into another road before the light drops it`() {
        // Northbound on the main road, but turning east 100 m before the crossroads.
        val events = route(
            start, listOf(mainNorthbound, mainSouthbound),
            Leg(30, 10.0, 0.0), Leg(40, 10.0, 90.0),
        )

        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `turning right at the crossroads after the green still counts`() {
        // Stop 12 m before the northbound light, move off past its stop line and turn east
        // in the middle of the crossroads.
        val events = route(
            start, listOf(mainNorthbound, mainSouthbound),
            Leg(38, 10.0, 0.0), Leg(30, 0.0, 0.0), Leg(3, 5.0, 0.0), Leg(20, 10.0, 90.0),
        )

        assertEquals(listOf(RED_SEEN, GREEN_START), events.map { it.kind })
        assertTrue(events.all { it.lightId == mainNorthbound.id })
    }

    @Test
    fun `GPS jumping back after the light does not record a second pass`() {
        // Northbound at 10 m/s through the light at 400 m; right after crossing it, one fix
        // lands 3 m before the light (GPS noise), then the car goes on.
        val detector = PassDetector(listOf(northbound))
        val distances = (1..40).map { it * 10.0 } + listOf(397.0) + (41..60).map { it * 10.0 }
        val events = distances.mapIndexed { i, d ->
            detector.onSample(DriveSample((i + 1) * 1000L, Geo.offset(start, 0.0, d), 10.0, 0.0))
        }.flatten()

        assertEquals(listOf(GREEN_SEEN), events.map { it.kind })
    }

    @Test
    fun `offset point is at the given distance and bearing`() {
        val p = Geo.offset(start, 90.0, 250.0)
        assertEquals(250.0, Geo.distance(start, p), 0.1)
        assertEquals(90.0, Geo.bearing(start, p), 0.1)
    }
}
