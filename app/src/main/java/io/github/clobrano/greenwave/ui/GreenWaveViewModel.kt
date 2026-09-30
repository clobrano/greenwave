package io.github.clobrano.greenwave.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.clobrano.greenwave.GreenWaveApplication
import io.github.clobrano.greenwave.data.LightEstimate
import io.github.clobrano.greenwave.data.ObservationEntity
import io.github.clobrano.greenwave.data.ObservationSource
import io.github.clobrano.greenwave.data.TrafficLightEntity
import io.github.clobrano.greenwave.location.Fix
import io.github.clobrano.greenwave.model.Geo
import io.github.clobrano.greenwave.model.GeoPoint
import io.github.clobrano.greenwave.model.LightMatcher
import io.github.clobrano.greenwave.model.ObservationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Semaforo a cui verrà attribuita la prossima osservazione manuale. */
data class RecordTarget(val light: TrafficLightEntity, val automatic: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class GreenWaveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GreenWaveApplication
    private val repository = app.repository
    private val clock = app.clock
    private val matcher = LightMatcher()

    private val locationPermission = MutableStateFlow(false)

    val lights: StateFlow<List<TrafficLightEntity>> =
        repository.lights.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val observations: StateFlow<List<ObservationEntity>> =
        repository.observations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val fix: StateFlow<Fix?> = locationPermission
        .flatMapLatest { granted -> if (granted) app.locationTracker.fixes() else emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Battito ogni secondo per countdown e stato "ora". */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(clock.now())
            delay(1_000 - clock.now() % 1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock.now())

    /** Stime per la fascia oraria attuale, ricalcolate quando cambiano i dati o ogni minuto. */
    val estimates: StateFlow<Map<Long, LightEstimate>> =
        combine(observations, now.map { it / 60_000 }.distinctUntilChanged()) { obs, minute -> obs to minute }
            .map { (obs, _) -> repository.estimate(obs, clock.now()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val manualTargetId = MutableStateFlow<Long?>(null)

    val recordTarget: StateFlow<RecordTarget?> =
        combine(lights, fix, manualTargetId) { lights, fix, manualId ->
            lights.firstOrNull { it.id == manualId }?.let { RecordTarget(it, automatic = false) }
                ?: fix?.let { f ->
                    matcher.match(lights.map { it.toLightPosition() }, f.position, f.heading)
                        ?.let { match -> RecordTarget(lights.first { it.id == match.id }, automatic = true) }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _lastSaved = MutableStateFlow<ObservationEntity?>(null)
    val lastSaved: StateFlow<ObservationEntity?> = _lastSaved.asStateFlow()

    val clockSynced: Boolean get() = clock.synced

    fun onLocationPermission(granted: Boolean) {
        locationPermission.value = granted
    }

    fun chooseRecordTarget(lightId: Long?) {
        manualTargetId.value = lightId
    }

    fun record(kind: ObservationKind) {
        val target = recordTarget.value ?: return
        // L'istante si prende subito, prima di qualsiasi lavoro asincrono.
        val now = clock.now()
        val observation = ObservationEntity(
            lightId = target.light.id,
            epochMillis = now,
            kind = kind,
            source = ObservationSource.MANUAL,
            gnssTime = clock.synced,
        )
        val waitedAtRed = if (kind == ObservationKind.GREEN_START) redWaitObservation(target.light, now) else null
        viewModelScope.launch {
            lastSavedExtra = waitedAtRed?.let { repository.addObservation(it) }
            _lastSaved.value = repository.addObservation(observation)
        }
    }

    /** Osservazione aggiunta insieme a [lastSaved] (l'attesa al rosso), da annullare con essa. */
    private var lastSavedExtra: ObservationEntity? = null

    /**
     * Se il GPS dice che ero fermo vicino al semaforo prima del verde, in quell'intervallo
     * era rosso: lo si registra come RED_SEEN. Questa informazione permette allo stimatore
     * di distinguere il ciclo vero dalla sua metà.
     */
    private fun redWaitObservation(light: TrafficLightEntity, greenAt: Long): ObservationEntity? {
        val f = fix.value ?: return null
        val stoppedSince = f.stoppedSince ?: return null
        val waited = greenAt - stoppedSince
        if (waited !in MIN_RED_WAIT_MS..MAX_RED_WAIT_MS) return null
        if (Geo.distance(f.position, light.position) > MAX_DISTANCE_FOR_RED_WAIT_M) return null
        return ObservationEntity(
            lightId = light.id,
            epochMillis = stoppedSince + 1_000,
            kind = ObservationKind.RED_SEEN,
            source = ObservationSource.GPS,
            gnssTime = clock.synced,
        )
    }

    fun undoLast() {
        val last = _lastSaved.value ?: return
        val extra = lastSavedExtra
        _lastSaved.value = null
        lastSavedExtra = null
        viewModelScope.launch {
            repository.deleteObservation(last)
            extra?.let { repository.deleteObservation(it) }
        }
    }

    fun addLight(name: String, position: GeoPoint, approachBearing: Double?, speedLimitKmh: Int) {
        viewModelScope.launch { repository.addLight(name, position, approachBearing, speedLimitKmh) }
    }

    fun updateLight(light: TrafficLightEntity) {
        viewModelScope.launch { repository.updateLight(light) }
    }

    fun deleteLight(light: TrafficLightEntity) {
        if (manualTargetId.value == light.id) manualTargetId.value = null
        viewModelScope.launch { repository.deleteLight(light) }
    }

    fun moveLight(light: TrafficLightEntity, delta: Int) {
        viewModelScope.launch { repository.moveLight(light, delta) }
    }

    fun deleteObservation(observation: ObservationEntity) {
        viewModelScope.launch { repository.deleteObservation(observation) }
    }

    fun secondsOfDay(epochMillis: Long): Double = repository.secondsOfDay(epochMillis)

    fun toEpochMillis(referenceMillis: Long, secondsOfDay: Double): Long =
        repository.toEpochMillis(referenceMillis, secondsOfDay)

    private companion object {
        const val MIN_RED_WAIT_MS = 4_000L
        const val MAX_RED_WAIT_MS = 180_000L
        const val MAX_DISTANCE_FOR_RED_WAIT_M = 100.0
    }

    fun exportCsv(uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { repository.exportCsv(it) }
                }.isSuccess
            }
            onDone(ok)
        }
    }
}
