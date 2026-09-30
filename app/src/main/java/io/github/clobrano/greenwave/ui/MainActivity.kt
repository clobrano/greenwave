package io.github.clobrano.greenwave.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clobrano.greenwave.model.GeoPoint
import java.time.LocalDate

private enum class Tab(val label: String, val symbol: String) {
    MAP("Mappa", "🗺"),
    RECORD("Registra", "🚦"),
    LIGHTS("Semafori", "☰"),
}

class MainActivity : ComponentActivity() {
    private val viewModel: GreenWaveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.onLocationPermission(hasLocationPermission())
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { GreenWaveApp(viewModel) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onLocationPermission(hasLocationPermission())
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
private fun GreenWaveApp(viewModel: GreenWaveViewModel) {
    val lights by viewModel.lights.collectAsStateWithLifecycle()
    val observations by viewModel.observations.collectAsStateWithLifecycle()
    val estimates by viewModel.estimates.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val target by viewModel.recordTarget.collectAsStateWithLifecycle()
    val lastSaved by viewModel.lastSaved.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    var openLightId by rememberSaveable { mutableStateOf<Long?>(null) }
    var newLightAt by rememberSaveable(stateSaver = GeoPointSaver) { mutableStateOf<GeoPoint?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> viewModel.onLocationPermission(result[Manifest.permission.ACCESS_FINE_LOCATION] == true) }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            viewModel.exportCsv(uri) { ok ->
                Toast.makeText(context, if (ok) "Esportazione completata" else "Esportazione fallita", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = openLightId != null) { openLightId = null }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && openLightId == null,
                        onClick = { tab = t; openLightId = null },
                        icon = { Text(t.symbol) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val openLight = lights.firstOrNull { it.id == openLightId }
            when {
                openLight != null -> LightDetailScreen(
                    light = openLight,
                    estimate = estimates[openLight.id],
                    observations = observations.filter { it.lightId == openLight.id },
                    now = now,
                    currentHeading = fix?.heading,
                    toSecondsOfDay = viewModel::secondsOfDay,
                    toEpochMillis = viewModel::toEpochMillis,
                    onSave = viewModel::updateLight,
                    onDelete = { viewModel.deleteLight(it); openLightId = null },
                    onDeleteObservation = viewModel::deleteObservation,
                    onBack = { openLightId = null },
                )
                tab == Tab.MAP -> MapScreen(
                    lights = lights,
                    estimates = estimates,
                    fix = fix,
                    onAddLight = { newLightAt = it },
                    onOpenLight = { openLightId = it },
                )
                tab == Tab.RECORD -> RecordScreen(
                    lights = lights,
                    target = target,
                    estimates = estimates,
                    fix = fix,
                    now = now,
                    clockSynced = viewModel.clockSynced,
                    lastSaved = lastSaved,
                    onChooseTarget = viewModel::chooseRecordTarget,
                    onRecord = viewModel::record,
                    onUndo = viewModel::undoLast,
                    toSecondsOfDay = viewModel::secondsOfDay,
                )
                else -> LightsScreen(
                    lights = lights,
                    observations = observations,
                    estimates = estimates,
                    onOpenLight = { openLightId = it },
                    onMove = viewModel::moveLight,
                    onExport = { exportLauncher.launch("greenwave-${LocalDate.now()}.csv") },
                )
            }
        }
    }

    newLightAt?.let { position ->
        AddLightDialog(
            defaultName = "Semaforo ${lights.size + 1}",
            currentHeading = fix?.heading,
            onConfirm = { form ->
                viewModel.addLight(form.name.trim(), position, form.approachBearing, form.speedLimitKmh)
                newLightAt = null
            },
            onDismiss = { newLightAt = null },
        )
    }
}

private val GeoPointSaver = androidx.compose.runtime.saveable.Saver<GeoPoint?, DoubleArray>(
    save = { it?.let { p -> doubleArrayOf(p.lat, p.lon) } },
    restore = { GeoPoint(it[0], it[1]) },
)
