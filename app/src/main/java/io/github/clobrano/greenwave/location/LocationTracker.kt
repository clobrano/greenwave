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

/** Current position, simplified for the UI. */
data class Fix(
    val position: GeoPoint,
    /** Speed in m/s, null if unknown. */
    val speed: Double?,
    /** Last reliable driving direction (degrees), kept while stopped too. */
    val heading: Double?,
    val accuracyMeters: Double?,
    /** Since when (epoch ms, corrected time) I have been stopped; null if moving. */
    val stoppedSince: Long?,
    /** When the fix was taken (epoch ms): GNSS time for GPS fixes, corrected clock otherwise. */
    val timeMillis: Long,
    /** True for satellite fixes; network fixes are coarse and have no speed. */
    val fromGps: Boolean,
)

/**
 * GPS positions from Android's LocationManager (no Google services needed, so it also
 * works on systems like /e/OS). Also feeds [TrustedClock] with GNSS time.
 */
class LocationTracker(
    private val locationManager: LocationManager,
    private val clock: TrustedClock,
) {
    /** Requires the ACCESS_FINE_LOCATION permission to be granted already. */
    @SuppressLint("MissingPermission")
    fun fixes(): Flow<Fix> = callbackFlow {
        var lastHeading: Double? = null
        var stoppedSince: Long? = null
        val listener = LocationListener { location: Location ->
            val fromGps = location.provider == LocationManager.GPS_PROVIDER
            if (fromGps) clock.onGnssFix(location)
            // GPS bearing is unreliable while stopped: keep the last known one.
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
                    timeMillis = if (fromGps) location.time else clock.now(),
                    fromGps = fromGps,
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
