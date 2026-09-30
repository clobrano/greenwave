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

    /** Distanza in metri (formula dell'emisenoverso). */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** Direzione da [a] verso [b] in gradi, 0 = nord, 90 = est. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return positiveMod(Math.toDegrees(atan2(y, x)), 360.0)
    }

    /** Differenza assoluta tra due direzioni, in [0, 180] gradi. */
    fun angleDifference(a: Double, b: Double): Double = abs(signedMod(a - b, 360.0))
}

/** Un semaforo come lo vede il selettore: posizione e direzione di marcia (null = qualsiasi). */
data class LightPosition(val id: Long, val position: GeoPoint, val approachBearing: Double?)

/**
 * Sceglie il semaforo a cui si riferisce un'osservazione: il più vicino entro [maxDistance],
 * scartando quelli con una direzione di marcia diversa dalla mia di oltre [maxAngle] gradi.
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
