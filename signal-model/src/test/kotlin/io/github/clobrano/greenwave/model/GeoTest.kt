package io.github.clobrano.greenwave.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GeoTest {
    private val origin = GeoPoint(45.0, 9.0)

    @Test
    fun `distance and bearing`() {
        val north = GeoPoint(45.001, 9.0)
        assertEquals(111.2, Geo.distance(origin, north), 0.5)
        assertEquals(0.0, Geo.bearing(origin, north), 0.01)
        assertEquals(90.0, Geo.bearing(origin, GeoPoint(45.0, 9.001)), 0.01)
    }

    @Test
    fun `bearing difference across north`() {
        assertEquals(20.0, Geo.angleDifference(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDifference(0.0, 180.0), 1e-9)
    }

    @Test
    fun `picks the nearby light in my direction`() {
        val here = GeoPoint(45.0, 9.0)
        val northbound = LightPosition(1, GeoPoint(45.0002, 9.0), approachBearing = 0.0)
        val southbound = LightPosition(2, GeoPoint(45.0001, 9.0), approachBearing = 180.0)
        val far = LightPosition(3, GeoPoint(45.01, 9.0), approachBearing = null)
        val matcher = LightMatcher()

        assertEquals(northbound, matcher.match(listOf(northbound, southbound, far), here, heading = 5.0))
        assertEquals(southbound, matcher.match(listOf(northbound, southbound, far), here, heading = null))
        assertNull(matcher.match(listOf(far), here, heading = null))
    }

    @Test
    fun `next light ahead in my direction`() {
        val here = GeoPoint(45.0, 9.0)
        val near = LightPosition(1, Geo.offset(here, 0.0, 120.0), approachBearing = 0.0)
        val far = LightPosition(2, Geo.offset(here, 0.0, 420.0), approachBearing = 0.0)
        val behind = LightPosition(3, Geo.offset(here, 180.0, 50.0), approachBearing = 0.0)
        val oppositeLane = LightPosition(4, Geo.offset(here, 0.0, 80.0), approachBearing = 180.0)
        val sideStreet = LightPosition(5, Geo.offset(Geo.offset(here, 0.0, 60.0), 90.0, 100.0), approachBearing = null)
        val lights = listOf(near, far, behind, oppositeLane, sideStreet)
        val matcher = LightMatcher()

        val ahead = matcher.nextAhead(lights, here, heading = 0.0)!!
        assertEquals(near, ahead.light)
        assertEquals(120.0, ahead.distance, 0.5)
        assertEquals(far, matcher.nextAhead(listOf(far, behind), here, heading = 0.0)!!.light)
        assertNull(matcher.nextAhead(lights, here, heading = null))
        assertNull(matcher.nextAhead(listOf(far), here, heading = 0.0, maxDistance = 300.0))
    }
}
