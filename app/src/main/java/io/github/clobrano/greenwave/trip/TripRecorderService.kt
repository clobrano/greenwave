package io.github.clobrano.greenwave.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.github.clobrano.greenwave.GreenWaveApplication
import io.github.clobrano.greenwave.R
import io.github.clobrano.greenwave.model.DriveSample
import io.github.clobrano.greenwave.model.PassDetector
import io.github.clobrano.greenwave.ui.MainActivity
import io.github.clobrano.greenwave.ui.formatTime
import io.github.clobrano.greenwave.ui.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the trip recorder is doing, shown on the Record screen. */
data class TripState(
    val recording: Boolean = false,
    val detected: Int = 0,
    val lastEvent: String? = null,
)

/** Shared between the service and the UI. */
class TripStateHolder {
    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state.asStateFlow()

    fun update(transform: (TripState) -> TripState) = _state.update(transform)
}

/**
 * Records a trip in the background: follows the GPS, feeds [PassDetector] and saves the
 * colors it infers at each traffic light. Runs as a foreground service so it keeps going
 * with the screen off; Android shows its notification with a Stop action.
 */
class TripRecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val app get() = application as GreenWaveApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(TripState(recording = true)), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (job == null) job = scope.launch { record() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        app.tripState.update { it.copy(recording = false) }
        super.onDestroy()
    }

    private suspend fun record() = coroutineScope {
        val detector = PassDetector(emptyList())
        var names = emptyMap<Long, String>()
        app.tripState.update { TripState(recording = true) }

        launch {
            app.repository.lights.collect { lights ->
                detector.updateLights(lights.map { it.toLightPosition() })
                names = lights.associate { it.id to it.name }
            }
        }

        // Network fixes are coarse and have no speed: only satellite fixes are used.
        app.locationTracker.fixes().filter { it.fromGps }.collect { fix ->
            val events = detector.onSample(DriveSample(fix.timeMillis, fix.position, fix.speed, fix.heading))
            for (event in events) {
                // The light may have been deleted meanwhile: skip the event rather than crash.
                val saved = runCatching { app.repository.addDetected(event, app.clock.synced) }.getOrNull() ?: continue
                app.tripState.update {
                    it.copy(
                        detected = it.detected + 1,
                        lastEvent = "${saved.kind.label} at ${formatTime(saved.epochMillis)} – ${names[saved.lightId].orEmpty()}",
                    )
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(app.tripState.state.value))
            }
        }
    }

    private fun notification(state: TripState): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trip recording", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TripRecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_light)
            .setContentTitle("Recording trip · ${state.detected} events")
            .setContentText(state.lastEvent ?: "Detecting traffic lights from GPS")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "trip"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.clobrano.greenwave.STOP_TRIP"

        /** Needs the fine location permission already granted. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TripRecorderService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TripRecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
