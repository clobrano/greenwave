package io.github.clobrano.greenwave.model

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distance in meters (haversine formula). */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** Direction from [a] to [b] in degrees, 0 = north, 90 = east. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return positiveMod(Math.toDegrees(atan2(y, x)), 360.0)
    }

    /** Absolute difference between two directions, in [0, 180] degrees. */
    fun angleDifference(a: Double, b: Double): Double = abs(signedMod(a - b, 360.0))

    /** Point [distance] meters away from [from] in direction [bearing] (degrees). */
    fun offset(from: GeoPoint, bearing: Double, distance: Double): GeoPoint {
        val angular = distance / EARTH_RADIUS_M
        val theta = Math.toRadians(bearing)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(theta))
        val lon2 = lon1 + atan2(sin(theta) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
}

/** A traffic light as seen by the matcher: position and driving direction (null = any). */
data class LightPosition(val id: Long, val position: GeoPoint, val approachBearing: Double?)

/**
 * Picks the traffic light an observation refers to: the nearest one within [maxDistance],
 * skipping those whose driving direction differs from mine by more than [maxAngle] degrees.
 */
class LightMatcher(
    private val maxDistance: Double = 80.0,
    private val maxAngle: Double = 45.0,
) {
    fun match(lights: List<LightPosition>, me: GeoPoint, heading: Double?): LightPosition? =
        lights
            .map { it to Geo.distance(me, it.position) }
            .filter { (light, distance) ->
                distance <= maxDistance &&
                    (heading == null || light.approachBearing == null ||
                        Geo.angleDifference(heading, light.approachBearing) <= maxAngle)
            }
            .minByOrNull { it.second }
            ?.first
}
