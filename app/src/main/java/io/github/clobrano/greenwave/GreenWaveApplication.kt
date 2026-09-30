package io.github.clobrano.greenwave

import android.app.Application
import android.location.LocationManager
import io.github.clobrano.greenwave.data.AppDatabase
import io.github.clobrano.greenwave.data.GreenWaveRepository
import io.github.clobrano.greenwave.location.LocationTracker
import io.github.clobrano.greenwave.location.TrustedClock

/** Oggetti condivisi dall'intera app (iniezione delle dipendenze fatta a mano). */
class GreenWaveApplication : Application() {
    val clock by lazy { TrustedClock() }
    val repository by lazy { GreenWaveRepository(AppDatabase.create(this)) }
    val locationTracker by lazy { LocationTracker(getSystemService(LocationManager::class.java), clock) }
}
