package io.github.clobrano.greenwave.ui

import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.clobrano.greenwave.data.LightEstimate
import io.github.clobrano.greenwave.data.TrafficLightEntity
import io.github.clobrano.greenwave.location.Fix
import io.github.clobrano.greenwave.model.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

// Free OpenStreetMap-based vector style, no API key needed.
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val LIGHTS_SOURCE = "lights"
private const val LIGHTS_LAYER = "lights-circles"
private const val LABELS_LAYER = "lights-labels"
private const val ME_SOURCE = "me"
private const val ME_LAYER = "me-circle"
private const val FOLLOW_ZOOM = 17.0
private const val FOLLOW_ANIMATION_MS = 900

@Composable
fun MapScreen(
    lights: List<TrafficLightEntity>,
    estimates: Map<Long, LightEstimate>,
    fix: Fix?,
    onAddLight: (GeoPoint) -> Unit,
    onOpenLight: (Long) -> Unit,
) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    // Locked on the car: the map follows the GPS position and driving direction.
    var following by rememberSaveable { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        LightsMap(
            lights = lights,
            estimates = estimates,
            fix = fix,
            following = following,
            onUserPan = { following = false },
            onLongPress = onAddLight,
            onLightClick = { selectedId = it },
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
            if (lights.isEmpty()) {
                Card { Text("Long-press on the map to add a traffic light", Modifier.padding(12.dp)) }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { following = !following },
            containerColor = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (following) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Text(if (following) "◉ Unlock map" else "◎ Follow car") }

        val selected = lights.firstOrNull { it.id == selectedId }
        if (selected != null) {
            val estimate = estimates[selected.id]?.estimate
            // Above the follow button, so the two never overlap.
            Card(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 88.dp).fillMaxWidth(0.75f)) {
                Column(Modifier.padding(12.dp)) {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                    Text(estimate?.status.label, color = estimate?.status.color)
                    estimate?.plan?.let { Text("Cycle ${formatSeconds(it.cycle)}, green ${formatSeconds(it.green)}") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedId = null }) { Text("Close") }
                        Button(onClick = { onOpenLight(selected.id) }) { Text("Details") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LightsMap(
    lights: List<TrafficLightEntity>,
    estimates: Map<Long, LightEstimate>,
    fix: Fix?,
    following: Boolean,
    onUserPan: () -> Unit,
    onLongPress: (GeoPoint) -> Unit,
    onLightClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centered by remember { mutableStateOf(false) }
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnLightClick by rememberUpdatedState(onLightClick)
    val currentOnUserPan by rememberUpdatedState(onUserPan)

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                s.addSource(GeoJsonSource(LIGHTS_SOURCE))
                s.addSource(GeoJsonSource(ME_SOURCE))
                s.addLayer(
                    CircleLayer(ME_LAYER, ME_SOURCE).withProperties(
                        circleRadius(7f),
                        circleColor("#2979FF"),
                        circleStrokeWidth(3f),
                        circleStrokeColor("#FFFFFF"),
                    ),
                )
                s.addLayer(
                    CircleLayer(LIGHTS_LAYER, LIGHTS_SOURCE).withProperties(
                        circleRadius(10f),
                        circleColor(get("color")),
                        circleStrokeWidth(2f),
                        circleStrokeColor("#000000"),
                    ),
                )
                s.addLayer(
                    SymbolLayer(LABELS_LAYER, LIGHTS_SOURCE).withProperties(
                        textField(get("name")),
                        textFont(arrayOf("Noto Sans Regular")),
                        textSize(13f),
                        textAnchor("top"),
                        textOffset(arrayOf(0f, 1.2f)),
                        textColor("#000000"),
                        textHaloColor("#FFFFFF"),
                        textHaloWidth(1.5f),
                    ),
                )
                style = s
            }
            // Moving the map by hand releases the lock on the car.
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) currentOnUserPan()
            }
            m.addOnMapLongClickListener { latLng ->
                currentOnLongPress(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val area = RectF(p.x - 30, p.y - 30, p.x + 30, p.y + 30)
                val id = m.queryRenderedFeatures(area, LIGHTS_LAYER)
                    .firstOrNull()?.getNumberProperty("id")?.toLong()
                if (id != null) currentOnLightClick(id)
                id != null
            }
        }
    }

    LaunchedEffect(style, lights, estimates) {
        val source = style?.getSourceAs<GeoJsonSource>(LIGHTS_SOURCE) ?: return@LaunchedEffect
        val features = lights.map { light ->
            Feature.fromGeometry(Point.fromLngLat(light.lon, light.lat)).apply {
                addNumberProperty("id", light.id)
                addStringProperty("name", "${light.routeOrder + 1}. ${light.name}")
                addStringProperty("color", colorHex(estimates[light.id]?.estimate?.status.color.toArgb()))
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(style, fix) {
        val source = style?.getSourceAs<GeoJsonSource>(ME_SOURCE) ?: return@LaunchedEffect
        val me = fix?.position ?: return@LaunchedEffect
        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(me.lon, me.lat)))
    }

    // First centering when not following or before the first fix: on the first light.
    LaunchedEffect(map, lights.isNotEmpty()) {
        val m = map ?: return@LaunchedEffect
        if (centered || fix != null) return@LaunchedEffect
        val target = lights.firstOrNull()?.position ?: return@LaunchedEffect
        m.cameraPosition = CameraPosition.Builder().target(LatLng(target.lat, target.lon)).zoom(16.0).build()
        centered = true
    }

    // Follow the car: center on every fix and turn the map to the driving direction.
    // The animation lasts about as long as the GPS interval, so the movement looks continuous.
    LaunchedEffect(map, fix, following) {
        val m = map ?: return@LaunchedEffect
        val me = fix?.position ?: return@LaunchedEffect
        if (!following) return@LaunchedEffect
        val current = m.cameraPosition
        val position = CameraPosition.Builder()
            .target(LatLng(me.lat, me.lon))
            .zoom(if (centered) current.zoom else FOLLOW_ZOOM)
            .bearing(fix.heading ?: current.bearing)
            .build()
        if (centered) {
            m.easeCamera(CameraUpdateFactory.newCameraPosition(position), FOLLOW_ANIMATION_MS, false)
        } else {
            m.cameraPosition = position
            centered = true
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun colorHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
