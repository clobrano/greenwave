package io.github.clobrano.greenwave.location

import android.location.Location
import android.os.SystemClock

/**
 * Clock corrected with satellite time. The phone clock can be off by a few seconds,
 * enough to ruin predictions: on every GPS fix we measure the offset between GNSS time
 * and the system clock and apply it in [now].
 */
class TrustedClock {
    private val offsets = ArrayDeque<Long>()

    @Volatile
    private var offsetMillis: Long = 0

    /** True after at least one GPS fix: [now] is aligned to GNSS time. */
    @Volatile
    var synced: Boolean = false
        private set

    fun onGnssFix(location: Location) {
        // Instant (system clock) at which the fix was computed.
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        val systemAtFix = System.currentTimeMillis() - ageMillis
        synchronized(offsets) {
            offsets.addLast(location.time - systemAtFix)
            if (offsets.size > WINDOW) offsets.removeFirst()
            // Median: robust to a few fixes with a wrong time.
            offsetMillis = offsets.sorted()[offsets.size / 2]
        }
        synced = true
    }

    fun now(): Long = System.currentTimeMillis() + offsetMillis

    private companion object {
        const val WINDOW = 15
    }
}
