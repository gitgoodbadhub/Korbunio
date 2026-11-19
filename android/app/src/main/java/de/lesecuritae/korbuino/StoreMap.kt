package de.lesecuritae.korbuino

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** Free OpenStreetMap vector map without registration or key (attribution is shown by the map itself). */
internal const val STORE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/** Full-screen map with the numbered stops of the shopping route. */
@Composable
internal fun StoreMapDialog(stops: List<StoreStop>, retailerNames: Map<String, String>, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var showLocation by remember { mutableStateOf(hasLocationPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        showLocation = result.values.any { it } || hasLocationPermission(context)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag("store-map-dialog")) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Filialen auf der Karte", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("store-map-close")) { Text("Schließen") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { StoreMap(stops, showLocation) }
                if (!showLocation) {
                    TextButton(onClick = {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, modifier = Modifier.padding(horizontal = 12.dp).testTag("store-map-my-location")) { Text("Meinen Standort anzeigen") }
                }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    stops.forEachIndexed { index, stop ->
                        Text("${index + 1}. ${retailerNames[stop.retailerId] ?: stop.name} · ${stop.address.ifBlank { stop.name }}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { StoreRoute.googleMapsUrl(stops)?.let { runCatching { uriHandler.openUri(it) } } }) {
                        Text(if (stops.size > 1) "Ganze Route in Google Maps" else "In Google Maps öffnen")
                    }
                }
            }
        }
    }
}

internal fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/** MapLibre view bound to the Compose lifecycle; the stops are drawn as numbered circles, the own position as blue dot. */
@SuppressLint("MissingPermission") // only activated after hasLocationPermission()
@Composable
private fun StoreMap(stops: List<StoreStop>, showLocation: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
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
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView, stops, showLocation) {
        mapView.getMapAsync { map ->
            map.uiSettings.setRotateGesturesEnabled(false)
            map.uiSettings.setAttributionEnabled(true)
            val style = Style.Builder().fromUri(STORE_MAP_STYLE)
                .withSource(GeoJsonSource("korbunio-stops", StoreRoute.stopsGeoJson(stops)))
                .withLayer(CircleLayer("korbunio-stop-circles", "korbunio-stops").withProperties(
                    PropertyFactory.circleRadius(13f),
                    PropertyFactory.circleColor("#1B6E4F"),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ))
                .withLayer(SymbolLayer("korbunio-stop-numbers", "korbunio-stops").withProperties(
                    PropertyFactory.textField(Expression.get("n")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(13f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ))
            map.setStyle(style) { loaded ->
                // Own position: Android location service, no Google services; never fatal for the map.
                val own = if (showLocation && hasLocationPermission(context)) runCatching {
                    map.locationComponent.apply {
                        activateLocationComponent(LocationComponentActivationOptions.builder(context, loaded)
                            .useDefaultLocationEngine(true).build())
                        setLocationComponentEnabled(true)
                        setRenderMode(RenderMode.NORMAL)
                        setCameraMode(CameraMode.NONE)
                    }.lastKnownLocation?.let { LatLng(it.latitude, it.longitude) }
                }.getOrNull() else null
                val stopPoints = stops.map { LatLng(it.lat, it.lon) }
                // Show the own position too when it is near the shops (not when the phone is far away).
                val near = own?.takeIf { o -> stops.any { StoreRoute.distanceKm(o.latitude, o.longitude, it.lat, it.lon) < 30.0 } }
                val points = stopPoints + listOfNotNull(near)
                when {
                    points.isEmpty() -> Unit
                    points.distinct().size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 14.0))
                    else -> map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 120))
                }
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().testTag("store-map"))
}
