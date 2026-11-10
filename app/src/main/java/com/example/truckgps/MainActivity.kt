package com.example.truckgps

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.offline.*
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val STYLE = "https://tiles.openfreemap.org/styles/liberty"   // OSM-based vector tiles
const val TOMTOM_KEY = ""   // optional: free key from developer.tomtom.com enables accident alerts

// Dimensions in metres, weight in metric tonnes (Valhalla "truck" costing units)
enum class TruckType(val label: String, val h: Double, val w: Double, val l: Double, val t: Double, val hazmat: Boolean) {
    REEFER("Reefer 53'", 4.11, 2.6, 22.0, 36.3, false),
    DOUBLES("Doubles", 4.11, 2.6, 31.0, 36.3, false),
    HAZMAT("Hazmat", 4.11, 2.6, 22.0, 36.3, true),
    EQUIPMENT("Equipment / Oversize", 4.5, 3.2, 25.0, 45.0, false)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        MapLibre.getInstance(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        setContent { MaterialTheme { TruckApp() } }
    }
}

fun http(url: String, body: String? = null, type: String = "application/json"): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", "TruckGPS/0.1 (contact: you@example.com)") // NWS requires this
    c.connectTimeout = 20000; c.readTimeout = 30000
    if (body != null) { c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body.toByteArray()) } }
    return c.inputStream.bufferedReader().readText()
}

fun decode6(s: String): List<LatLng> {
    val out = ArrayList<LatLng>(); var i = 0; var lat = 0; var lon = 0
    fun next(): Int { var r = 0; var sh = 0; var b: Int
        do { b = s[i++].code - 63; r = r or ((b and 31) shl sh); sh += 5 } while (b >= 32)
        return if (r and 1 != 0) (r shr 1).inv() else r shr 1 }
    while (i < s.length) { lat += next(); lon += next(); out.add(LatLng(lat / 1e6, lon / 1e6)) }
    return out
}

// Truck-legal routing via Valhalla (open source). Swap in your own server for production.
class Step(val text: String, val at: LatLng)
class Route(val pts: List<LatLng>, val summary: String, val steps: List<Step>)

fun fmtDist(m: Double): String =
    if (m >= 800) "%.1f miles".format(m / 1609.34) else "${maxOf(50, (m * 3.281 / 50).roundToInt() * 50)} feet"

// Address / place search via OpenStreetMap Nominatim (max 1 request/sec, no autocomplete per usage policy)
fun geocode(q: String): List<Pair<String, LatLng>> {
    val a = JSONArray(http("https://nominatim.openstreetmap.org/search?format=json&limit=5&q=" + URLEncoder.encode(q, "UTF-8")))
    return (0 until a.length()).map { a.getJSONObject(it) }
        .map { it.getString("display_name") to LatLng(it.getDouble("lat"), it.getDouble("lon")) }
}

fun truckRoute(o: LatLng, d: LatLng, t: TruckType): Route {
    val req = JSONObject()
        .put("locations", JSONArray().put(JSONObject().put("lat", o.latitude).put("lon", o.longitude))
            .put(JSONObject().put("lat", d.latitude).put("lon", d.longitude)))
        .put("costing", "truck")
        .put("costing_options", JSONObject().put("truck", JSONObject()
            .put("height", t.h).put("width", t.w).put("length", t.l).put("weight", t.t)
            .put("hazmat", t.hazmat)))
        .put("directions_options", JSONObject().put("units", "miles"))
    val r = JSONObject(http("https://valhalla1.openstreetmap.de/route", req.toString()))
    val leg = r.getJSONObject("trip").getJSONArray("legs").getJSONObject(0)
    val sum = r.getJSONObject("trip").getJSONObject("summary")
    val pts = decode6(leg.getString("shape"))
    val mans = leg.getJSONArray("maneuvers")
    val steps = (0 until mans.length()).map { mans.getJSONObject(it) }
        .map { Step(it.getString("instruction"), pts[it.getInt("begin_shape_index")]) }
    return Route(pts, "%.0f mi, %.1f h".format(sum.getDouble("length"), sum.getDouble("time") / 3600), steps)
}

fun overpassPois(b: LatLngBounds): FeatureCollection {
    val bb = "${b.latitudeSouth},${b.longitudeWest},${b.latitudeNorth},${b.longitudeEast}"
    val q = """[out:json][timeout:25];(
      node["amenity"="fuel"]["hgv"~"yes|designated"]($bb);
      node["highway"="services"]($bb);
      node["amenity"="weighbridge"]($bb);
      node["highway"="weigh_station"]($bb););out body 300;"""
    val els = JSONObject(http("https://overpass-api.de/api/interpreter", "data=" + URLEncoder.encode(q, "UTF-8"),
        "application/x-www-form-urlencoded")).getJSONArray("elements")
    val fs = (0 until els.length()).map { els.getJSONObject(it) }.map { e ->
        val tg = e.optJSONObject("tags") ?: JSONObject()
        val kind = when {
            tg.optString("amenity") == "weighbridge" || tg.optString("highway") == "weigh_station" -> "weigh"
            tg.optString("amenity") == "fuel" -> "fuel"
            else -> "stop" }
        Feature.fromGeometry(Point.fromLngLat(e.getDouble("lon"), e.getDouble("lat"))).also {
            it.addStringProperty("t", kind); it.addStringProperty("name", tg.optString("name", kind)) } }
    return FeatureCollection.fromFeatures(fs)
}

fun weatherAlerts(route: List<LatLng>): List<String> {
    val step = maxOf(1, route.size / 6)
    return route.filterIndexed { i, _ -> i % step == 0 }.flatMap { p ->
        runCatching {
            val f = JSONObject(http("https://api.weather.gov/alerts/active?point=${"%.4f,%.4f".format(p.latitude, p.longitude)}"))
                .getJSONArray("features")
            (0 until f.length()).map { f.getJSONObject(it).getJSONObject("properties").getString("headline") }
        }.getOrDefault(emptyList()) }.distinct().take(6)
}

fun accidents(route: List<LatLng>): FeatureCollection {
    if (TOMTOM_KEY.isBlank()) return FeatureCollection.fromFeatures(emptyList<Feature>())
    val b = LatLngBounds.Builder().includes(route).build()
    val bbox = "${b.longitudeWest},${b.latitudeSouth},${b.longitudeEast},${b.latitudeNorth}"
    val f = "{incidents{geometry{coordinates},properties{iconCategory,events{description}}}}"
    val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=$TOMTOM_KEY&bbox=$bbox" +
        "&fields=" + URLEncoder.encode(f, "UTF-8") + "&categoryFilter=1"   // 1 = accident
    val arr = JSONObject(http(url)).optJSONArray("incidents") ?: JSONArray()
    return FeatureCollection.fromFeatures((0 until arr.length()).mapNotNull {
        var c = arr.getJSONObject(it).getJSONObject("geometry").getJSONArray("coordinates")
        while (c.optJSONArray(0) != null) c = c.getJSONArray(0)
        Feature.fromGeometry(Point.fromLngLat(c.getDouble(0), c.getDouble(1))) })
}

@SuppressLint("MissingPermission")
@Composable
fun TruckApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(TruckType.REEFER) }
    var dest by remember { mutableStateOf<LatLng?>(null) }
    var status by remember { mutableStateOf("Long-press the map to set a destination") }
    var alerts by remember { mutableStateOf(listOf<String>()) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mv = remember { MapView(ctx).also { it.onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var poiJob by remember { mutableStateOf<Job?>(null) }
    var route by remember { mutableStateOf<Route?>(null) }
    var stepIdx by remember { mutableStateOf(1) }
    var navigating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<Pair<String, LatLng>>()) }
    val said = remember { mutableSetOf<Int>() }
    val tts = remember { var t: TextToSpeech? = null; t = TextToSpeech(ctx) { t?.language = Locale.US }; t!! }
    val lm = remember { ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> when (e) {
            Lifecycle.Event.ON_START -> mv.onStart(); Lifecycle.Event.ON_RESUME -> mv.onResume()
            Lifecycle.Event.ON_PAUSE -> mv.onPause(); Lifecycle.Event.ON_STOP -> mv.onStop()
            Lifecycle.Event.ON_DESTROY -> mv.onDestroy(); else -> {} } }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
    }

    LaunchedEffect(Unit) {
        mv.getMapAsync { m ->
            map = m
            m.setStyle(STYLE) { st ->
                st.addSource(GeoJsonSource("route")); st.addSource(GeoJsonSource("poi")); st.addSource(GeoJsonSource("inc"))
                st.addLayer(LineLayer("route-l", "route").withProperties(lineColor(Color.rgb(25, 118, 210)), lineWidth(6f)))
                st.addLayer(CircleLayer("poi-l", "poi").withProperties(circleRadius(7f),
                    circleColor(Expression.match(Expression.get("t"), Expression.color(Color.GRAY),
                        Expression.stop("fuel", Expression.color(Color.rgb(46, 125, 50))),
                        Expression.stop("stop", Expression.color(Color.rgb(245, 124, 0))),
                        Expression.stop("weigh", Expression.color(Color.rgb(123, 31, 162))))),
                    circleStrokeColor(Color.WHITE), circleStrokeWidth(1.5f)))
                st.addLayer(CircleLayer("inc-l", "inc").withProperties(circleRadius(10f),
                    circleColor(Color.RED), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)))
                runCatching {
                    m.locationComponent.apply {
                        activateLocationComponent(LocationComponentActivationOptions.builder(ctx, st).build())
                        isLocationComponentEnabled = true; cameraMode = CameraMode.TRACKING; renderMode = RenderMode.COMPASS }
                }
            }
            m.addOnMapLongClickListener { ll -> dest = ll; status = "Destination set. Tap Route."; true }
            m.addOnCameraIdleListener {
                if (m.cameraPosition.zoom < 8) return@addOnCameraIdleListener
                poiJob?.cancel()
                poiJob = scope.launch(Dispatchers.IO) {
                    delay(600)
                    runCatching { overpassPois(m.projection.visibleRegion.latLngBounds) }.onSuccess { fc ->
                        withContext(Dispatchers.Main) { m.style?.getSourceAs<GeoJsonSource>("poi")?.setGeoJson(fc) } }
                }
            }
        }
    }

    fun doRoute() {
        val m = map ?: return
        val d = dest ?: run { status = "Long-press the map first"; return }
        val loc = m.locationComponent.lastKnownLocation ?: run { status = "Waiting for GPS fix…"; return }
        status = "Routing…"
        scope.launch(Dispatchers.IO) {
            runCatching {
                val rt = truckRoute(LatLng(loc.latitude, loc.longitude), d, type); val pts = rt.pts; val sum = rt.summary
                val wx = weatherAlerts(pts); val inc = accidents(pts)
                withContext(Dispatchers.Main) {
                    route = rt; stepIdx = 1; said.clear()
                    val line = Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }))
                    m.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(line)
                    m.style?.getSourceAs<GeoJsonSource>("inc")?.setGeoJson(inc)
                    m.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(pts).build(), 100))
                    alerts = wx + if (inc.features()!!.isNotEmpty()) listOf("${inc.features()!!.size} accident(s) on route") else emptyList()
                    status = "${type.label}: $sum"
                }
            }.onFailure { status = "Route failed: ${it.message}" }
        }
    }

    fun speak(t: String) { tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "nav") }
    var lastReroute by remember { mutableStateOf(0L) }
    val listener = remember { LocationListener { loc ->
        val r = route ?: return@LocationListener
        val me = LatLng(loc.latitude, loc.longitude)
        if (r.pts.minOf { it.distanceTo(me) } > 150 && System.currentTimeMillis() - lastReroute > 15000) {
            lastReroute = System.currentTimeMillis(); speak("Rerouting"); doRoute(); return@LocationListener }
        val st = r.steps.getOrNull(stepIdx) ?: return@LocationListener
        val d = st.at.distanceTo(me)
        if (d < 60) { speak(st.text); stepIdx++; if (stepIdx >= r.steps.size) navigating = false }
        else if (d < 400 && said.add(stepIdx)) speak("In ${fmtDist(d)}, ${st.text}")
    } }
    LaunchedEffect(navigating) { if (!navigating) lm.removeUpdates(listener) }
    DisposableEffect(Unit) { onDispose { lm.removeUpdates(listener); tts.shutdown() } }
    fun startNav() {
        val r = route ?: run { status = "Tap Route first"; return }
        navigating = true; stepIdx = 1; said.clear()
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 5f, listener)
        speak(r.steps.firstOrNull()?.text ?: "Starting navigation")
    }
    fun search() {
        scope.launch(Dispatchers.IO) {
            runCatching { geocode(query) }
                .onSuccess { r -> results = r; if (r.isEmpty()) status = "No matches" }
                .onFailure { status = "Search failed: ${it.message}" } }
    }

    fun downloadOffline() {
        val m = map ?: return
        val def = OfflineTilePyramidRegionDefinition(STYLE, m.projection.visibleRegion.latLngBounds,
            m.cameraPosition.zoom, 14.0, ctx.resources.displayMetrics.density)
        OfflineManager.getInstance(ctx).createOfflineRegion(def, "region-${System.currentTimeMillis()}".toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(r: OfflineRegion) {
                    r.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(s: OfflineRegionStatus) {
                            status = if (s.isComplete) "Offline map saved" else
                                "Downloading ${(100 * s.completedResourceCount / maxOf(1, s.requiredResourceCount))}%" }
                        override fun onError(e: OfflineRegionError) { status = "Offline error: ${e.message}" }
                        override fun mapboxTileCountLimitExceeded(limit: Long) { status = "Tile limit exceeded; zoom in" }
                    })
                    r.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) { status = "Offline error: $error" }
            })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mv }, Modifier.fillMaxSize())
        Row(Modifier.align(androidx.compose.ui.Alignment.TopStart).statusBarsPadding().padding(8.dp)
            .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TruckType.values().forEach { t ->
                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
        }
        Column(Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 8.dp, end = 8.dp)) {
            if (navigating) {
                Card(Modifier.fillMaxWidth()) { Text(route?.steps?.getOrNull(stepIdx)?.text ?: "Arriving",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
            } else {
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Search address or place") })
                    Spacer(Modifier.width(6.dp)); Button(onClick = { search() }) { Text("Go") } } }
                results.forEach { (name, ll) ->
                    Card(Modifier.fillMaxWidth().clickable {
                        dest = ll; results = emptyList(); status = "Destination set. Tap Route."
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 12.0)) }) {
                        Text(name, Modifier.padding(8.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall) } }
            }
        }
        Card(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().padding(8.dp).fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status, style = MaterialTheme.typography.titleSmall)
                alerts.forEach { Text("⚠ $it", color = androidx.compose.ui.graphics.Color(0xFFC62828),
                    style = MaterialTheme.typography.bodySmall) }
                Text("● Fuel  ● Truck stop  ● Weigh station", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { doRoute() }) { Text("Route") }
                    Button(onClick = { if (navigating) navigating = false else startNav() }) { Text(if (navigating) "Stop" else "Start") }
                    OutlinedButton(onClick = { downloadOffline() }) { Text("Offline map") }
                }
            }
        }
    }
}
