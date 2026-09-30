package io.github.clobrano.greenwave.location

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import io.github.clobrano.greenwave.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Posizione corrente semplificata per l'interfaccia. */
data class Fix(
    val position: GeoPoint,
    /** Velocità in m/s, null se non nota. */
    val speed: Double?,
    /** Ultima direzione di marcia affidabile (gradi), mantenuta anche da fermo. */
    val heading: Double?,
    val accuracyMeters: Double?,
    /** Da quando (epoch ms, ora corretta) sono fermo; null se in movimento. */
    val stoppedSince: Long?,
)

/**
 * Posizioni dal GPS tramite il LocationManager di Android (non richiede i servizi Google,
 * quindi funziona anche su sistemi come /e/OS). Aggiorna anche [TrustedClock] con l'ora GNSS.
 */
class LocationTracker(
    private val locationManager: LocationManager,
    private val clock: TrustedClock,
) {
    /** Richiede il permesso ACCESS_FINE_LOCATION già concesso. */
    @SuppressLint("MissingPermission")
    fun fixes(): Flow<Fix> = callbackFlow {
        var lastHeading: Double? = null
        var stoppedSince: Long? = null
        val listener = LocationListener { location: Location ->
            if (location.provider == LocationManager.GPS_PROVIDER) clock.onGnssFix(location)
            // Da fermo la direzione del GPS non è affidabile: si tiene l'ultima nota.
            if (location.hasBearing() && location.hasSpeed() && location.speed > MIN_SPEED_FOR_HEADING) {
                lastHeading = location.bearing.toDouble()
            }
            if (location.hasSpeed()) {
                when {
                    location.speed < STOPPED_SPEED -> if (stoppedSince == null) stoppedSince = clock.now()
                    location.speed > MOVING_SPEED -> stoppedSince = null
                }
            }
            trySend(
                Fix(
                    position = GeoPoint(location.latitude, location.longitude),
                    speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                    heading = lastHeading,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    stoppedSince = stoppedSince,
                ),
            )
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
        providers.forEach {
            locationManager.requestLocationUpdates(it, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        }
        providers.firstNotNullOfOrNull { locationManager.getLastKnownLocation(it) }
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < MAX_LAST_KNOWN_AGE_NS }
            ?.let { listener.onLocationChanged(it) }
        awaitClose { locationManager.removeUpdates(listener) }
    }

    private companion object {
        const val INTERVAL_MS = 1000L
        const val MIN_SPEED_FOR_HEADING = 2.0f
        const val STOPPED_SPEED = 1.0f
        const val MOVING_SPEED = 2.5f
        const val MAX_LAST_KNOWN_AGE_NS = 2 * 60 * 1_000_000_000L
    }
}
