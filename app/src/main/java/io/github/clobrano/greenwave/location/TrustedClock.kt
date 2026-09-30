package io.github.clobrano.greenwave.location

import android.location.Location
import android.os.SystemClock

/**
 * Orologio corretto con l'ora dei satelliti. L'orologio del telefono può sbagliare di
 * qualche secondo, abbastanza da rovinare le previsioni: a ogni fix GPS si misura lo
 * scarto tra ora GNSS e orologio di sistema e lo si applica a [now].
 */
class TrustedClock {
    private val offsets = ArrayDeque<Long>()

    @Volatile
    private var offsetMillis: Long = 0

    /** True dopo almeno un fix GPS: [now] è allineato all'ora GNSS. */
    @Volatile
    var synced: Boolean = false
        private set

    fun onGnssFix(location: Location) {
        // Istante (orologio di sistema) in cui il fix è stato calcolato.
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        val systemAtFix = System.currentTimeMillis() - ageMillis
        synchronized(offsets) {
            offsets.addLast(location.time - systemAtFix)
            if (offsets.size > WINDOW) offsets.removeFirst()
            // Mediana: robusta a qualche fix con ora sbagliata.
            offsetMillis = offsets.sorted()[offsets.size / 2]
        }
        synced = true
    }

    fun now(): Long = System.currentTimeMillis() + offsetMillis

    private companion object {
        const val WINDOW = 15
    }
}
