package com.mvvtracker.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mvvtracker.app.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.data.live.routeKey
import com.mvvtracker.app.ui.components.ReportRed
import com.mvvtracker.app.viewmodel.LiveMapViewModel
import com.mvvtracker.app.viewmodel.TrainPosition
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.advancedpolyline.MonochromaticPaintList
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

internal const val TRAIN_TAG = "train"

/**
 * CARTO "Voyager" basemap - a clean OSM-based style, kept ready but NOT active:
 * the app uses free OpenStreetMap tiles (see setTileSource below). CARTO's public
 * tiles need a paid plan for commercial use, so to activate it later just pass
 * [CARTO_VOYAGER] to setTileSource and restore the "© CARTO" attribution.
 */
@Suppress("unused")
private val CARTO_VOYAGER = XYTileSource(
    "CartoVoyager", 0, 20, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
    ),
    "© OpenStreetMap contributors © CARTO"
)

/**
 * The OpenStreetMap tiles are recoloured on the phone to a pale, mostly grey map,
 * so the vehicles and route lines stand out. Nothing else about the tiles changes.
 */
private val PALE_MAP: ColorFilter = ColorMatrixColorFilter(
    ColorMatrix().apply {
        setSaturation(PALE_MAP_SATURATION)
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    PALE_MAP_CONTRAST, 0f, 0f, 0f, PALE_MAP_LIFT,
                    0f, PALE_MAP_CONTRAST, 0f, 0f, PALE_MAP_LIFT,
                    0f, 0f, PALE_MAP_CONTRAST, 0f, PALE_MAP_LIFT,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
)
private const val PALE_MAP_SATURATION = 0.3f
private const val PALE_MAP_CONTRAST = 0.78f   // darkest text becomes dark grey ...
private const val PALE_MAP_LIFT = 56f         // ... and white stays white (0.78 * 255 + 56)

@Composable
fun LiveTrainMap(
    liveViewModel: LiveMapViewModel,
    centerLat: Double,
    centerLon: Double,
    modeFilter: Set<String>?,
    flaggedTripIds: Set<String>,
    showUserLocation: Boolean = false,
    modifier: Modifier = Modifier,
    onTrainClick: (line: String, tripId: String?, station: String?, mode: String?, city: String) -> Unit =
        { _, _, _, _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = context.resources.displayMetrics.density

    val trains by liveViewModel.trains.collectAsStateWithLifecycle()
    val loadError by liveViewModel.loadError.collectAsStateWithLifecycle()
    val routes by liveViewModel.routes.collectAsStateWithLifecycle()
    val dataDelayed by liveViewModel.dataDelayed.collectAsStateWithLifecycle()

    // Only fetch/update while this screen is on screen AND the app is in the
    // foreground: leaving the app (home button, screen off) stops the downloads too.
    LifecycleStartEffect(liveViewModel) {
        liveViewModel.resume()
        onStopOrDispose { liveViewModel.pause() }
    }

    // Persistent overlays reused across position ticks (see update below): moving a
    // marker or keeping a static route line is far cheaper than rebuilding them all
    // every tick. Two layers, so the routes always stay under the vehicles.
    val routeLayer = remember { Layer<Polyline>(tappable = false) }
    val vehicleLayer = remember { Layer<Marker>(tappable = true) }
    val routeLines = remember { HashMap<String, RouteLine>() }
    val badges = remember { HashMap<String, Badge>() }
    val drawn = remember { DrawnStyle() }

    // The badge size and line width follow the zoom; a tapped line is shown in full colour.
    var tier by remember { mutableStateOf(zoomTier(START_ZOOM)) }
    var selectedRoute by remember { mutableStateOf<String?>(null) }

    val mapView = remember {
        MapView(context).apply {
            // Free OpenStreetMap tiles. To use the (paid, commercial) CARTO style
            // instead, swap this for setTileSource(CARTO_VOYAGER).
            setTileSource(TileSourceFactory.MAPNIK)
            overlayManager.tilesOverlay.setColorFilter(PALE_MAP)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(START_ZOOM)
            controller.setCenter(GeoPoint(centerLat, centerLon))
            // Lowest overlay, so it only gets the taps no vehicle took: a tap on a
            // route line shows that line in full colour, a tap elsewhere ends it.
            // Lines that share a track lie on top of each other: tapping the same
            // spot again goes on to the next one.
            val map = this
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    val tolerance = (LINE_TAP_DP * density).toDouble()
                    val hits = drawn.routeOrder.asReversed()
                        .filter { it.line.isCloseTo(p, tolerance, map) }
                        .map { it.key }
                    val at = hits.indexOf(selectedRoute)
                    selectedRoute = when {
                        hits.isEmpty() -> null
                        hits.size == 1 && at == 0 -> null   // the only line here, tapped again
                        else -> hits[(at + 1) % hits.size]
                    }
                    return hits.isNotEmpty()
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
            overlays.add(routeLayer)
            overlays.add(vehicleLayer)
            onResume()
        }
    }

    val reportBounds = remember(mapView) {
        {
            val bb = mapView.boundingBox
            liveViewModel.updateBounds(
                north = bb.latNorth, south = bb.latSouth, east = bb.lonEast, west = bb.lonWest
            )
        }
    }

    LaunchedEffect(centerLat, centerLon) {
        // Kick off the fetch right away with an estimated box (before the map
        // finishes laying out), then refine from the real bounds after layout.
        liveViewModel.updateBounds(
            north = centerLat + 0.10, south = centerLat - 0.10,
            east = centerLon + 0.16, west = centerLon - 0.16
        )
        selectedRoute = null   // another city: its lines are other lines
        mapView.controller.setZoom(START_ZOOM)
        mapView.controller.setCenter(GeoPoint(centerLat, centerLon))
        tier = zoomTier(START_ZOOM)
        mapView.post { reportBounds() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { reportBounds(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { reportBounds(); return false }
        }
        mapView.addMapListener(DelayedMapListener(listener, 300))
        // Not delayed: the badges and lines change size as soon as the zoom has changed.
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                tier = zoomTier(event?.zoomLevel ?: mapView.zoomLevelDouble)
                return false
            }
        })
        mapView.addOnFirstLayoutListener { _, _, _, _, _ -> reportBounds() }
        mapView.post { reportBounds() }
        onDispose { }
    }

    val shown = if (modeFilter == null) trains else trains.filter { it.mode in modeFilter }

    // Only fetch full routes for the lines the map is actually showing.
    val shownRouteKeys = remember(shown) { shown.mapTo(HashSet()) { routeKey(it.mode, it.label) } }
    LaunchedEffect(shownRouteKeys) { liveViewModel.ensureRoutes(shownRouteKeys) }

    // Reported vehicles inside the visible part of the map, in a fixed order so the
    // chip below can step through them. (Recomputed with every position tick.)
    val reportsInView =
        // (No reports, or the map has no size yet: nothing to look for.)
        if (flaggedTripIds.isEmpty() || mapView.width == 0) emptyList()
        else {
            val visibleBox = mapView.boundingBox
            shown
                .filter { it.tripId != null && it.tripId in flaggedTripIds && visibleBox.contains(it.latitude, it.longitude) }
                .sortedBy { it.tripId }
        }
    val lastJump = remember { mutableStateOf<String?>(null) }

    // Smooth movement: each tick gives every train a new target; the frame loop
    // below slides the marker from where it is drawn now to that target over one
    // tick, so trains glide continuously instead of hopping every second.
    // (A plain channel, not Compose state: the map's update block below must not
    // write state, or every update would trigger another recomposition.)
    val glides = remember { HashMap<String, Glide>() }
    val glideSignal = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        while (true) {
            glideSignal.receive()
            var start = -1L
            var lastDraw = 0L
            while (true) {
                // New targets mid-glide: they start from where the markers are now.
                if (glideSignal.tryReceive().isSuccess) start = -1L
                val done = withFrameMillis { now ->
                    if (start < 0) start = now
                    val t = ((now - start).toDouble() / GLIDE_MILLIS).coerceIn(0.0, 1.0)
                    // ~30 fps is plenty for vehicles and keeps redraws cheap on slow phones.
                    if (t < 1.0 && now - lastDraw < FRAME_MIN_MILLIS) return@withFrameMillis false
                    lastDraw = now
                    glides.forEach { (key, g) ->
                        // In place: no new objects per frame (osmdroid draws from it directly).
                        badges[key]?.marker?.position?.setCoords(
                            g.fromLat + (g.toLat - g.fromLat) * t,
                            g.fromLon + (g.toLon - g.fromLon) * t
                        )
                    }
                    mapView.invalidate()
                    t >= 1.0
                }
                if (done) break
            }
        }
    }

    // "You are here" dot — only when the user consented to precise location (that's
    // the only time we hold a location permission).
    // Location updates run only while the map is on screen and the app is in the
    // foreground (they stop when the user leaves the app).
    val locationOverlay = remember { MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) }
    LifecycleStartEffect(showUserLocation) {
        if (showUserLocation) {
            locationOverlay.enableMyLocation()
            if (locationOverlay !in mapView.overlays) mapView.overlays.add(locationOverlay)
        } else {
            mapView.overlays.remove(locationOverlay)
        }
        mapView.invalidate()
        onStopOrDispose { locationOverlay.disableMyLocation() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                // ROUTES (persistent): one full line per shown line with a cached
                // route. Static, so add once and keep across ticks; only add newly
                // shown lines and drop ones no longer shown.
                val repByRoute = HashMap<String, TrainPosition>()
                shown.forEach { t ->
                    val k = routeKey(t.mode, t.label)
                    if (routes.containsKey(k)) repByRoute.putIfAbsent(k, t)
                }
                var routesChanged = false
                repByRoute.forEach { (k, rep) ->
                    if (!routeLines.containsKey(k)) {
                        routeLines[k] = RouteLine(k, rep, routes.getValue(k), density)
                        routesChanged = true
                    }
                }
                (routeLines.keys - repByRoute.keys).toList().forEach { k ->
                    routeLines.remove(k)
                    routesChanged = true
                }
                // Width by zoom, pale unless tapped; the tapped line lies on top, below
                // it S-Bahn and U-Bahn over rail over tram over bus.
                if (routesChanged || drawn.tier != tier || drawn.selectedRoute != selectedRoute) {
                    routeLines.values.forEach { it.style(tier, it.key == selectedRoute, density) }
                    drawn.routeOrder = routeLines.values.sortedWith(
                        compareBy<RouteLine>({ it.key == selectedRoute }, { it.rank }, { it.key })
                    )
                    routeLayer.items = drawn.routeOrder.map { it.line }
                }

                // TRAIN MARKERS (reused): move existing markers, add new ones, drop
                // gone ones — instead of recreating every marker each tick.
                val shownByKey = LinkedHashMap<String, TrainPosition>()
                shown.forEach { shownByKey[trainKey(it)] = it }
                var orderChanged = false
                glides.clear()
                shownByKey.forEach { (key, train) ->
                    val flagged = train.tripId != null && train.tripId in flaggedTripIds
                    // A reported vehicle keeps the full badge at every zoom.
                    val size = if (flagged) ZoomTier.NEAR else tier
                    val rank = drawRank(train.mode, flagged)
                    val existing = badges[key]
                    if (existing != null) {
                        val marker = existing.marker
                        // Glide from where it is drawn now; a big jump (new data put the
                        // train somewhere else entirely) snaps instead of sliding across.
                        val from = marker.position
                        if (abs(from.latitude - train.latitude) < SNAP_DEGREES &&
                            abs(from.longitude - train.longitude) < SNAP_DEGREES
                        ) {
                            glides[key] = Glide(from.latitude, from.longitude, train.latitude, train.longitude)
                        } else {
                            from.setCoords(train.latitude, train.longitude)
                        }
                        // Keep the live/schedule-only fade in sync (cheap — no bitmap
                        // re-render), in case the trip gains or loses its real-time feed.
                        marker.alpha = badgeAlpha(train, flagged)
                        // Re-render the badge only when the report state flips or the
                        // zoom asks for another size.
                        if (existing.flagged != flagged || existing.size != size) {
                            marker.icon = trainBadgeFor(context, train, flagged, size)
                            existing.flagged = flagged
                            existing.size = size
                        }
                        if (existing.rank != rank) {
                            existing.rank = rank
                            orderChanged = true
                        }
                        marker.setOnMarkerClickListener { _, _ ->
                            onTrainClick(train.label, train.tripId, train.fromStop ?: train.toStop, train.mode, train.city)
                            true
                        }
                    } else {
                        val m = trainMarker(mv, context, train, flagged, size, onTrainClick)
                        badges[key] = Badge(key, m, flagged, size, rank)
                        orderChanged = true
                    }
                }
                (badges.keys - shownByKey.keys).toList().forEach { key ->
                    badges.remove(key)
                    orderChanged = true
                }
                // Later in the list = drawn on top and tapped first.
                if (orderChanged) {
                    vehicleLayer.items = badges.values
                        .sortedWith(compareBy<Badge>({ it.rank }, { it.key }))
                        .map { it.marker }
                }
                if (glides.isNotEmpty()) glideSignal.trySend(Unit)

                drawn.tier = tier
                drawn.selectedRoute = selectedRoute
                mv.invalidate()
            }
        )

        Column(modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = when {
                        loadError && shown.isEmpty() -> stringResource(R.string.feed_unavailable)
                        shown.isEmpty() -> stringResource(R.string.map_loading)
                        // Our server is unreachable or serves an older snapshot.
                        dataDelayed -> stringResource(R.string.map_trains_delayed, shown.size)
                        else -> stringResource(R.string.map_trains_live, shown.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // Reports in the visible part of the map; a tap moves the map to the next one.
            if (reportsInView.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = ReportRed,
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = 2.dp,
                    modifier = Modifier.clickable {
                        val at = reportsInView.indexOfFirst { it.tripId == lastJump.value }
                        val next = reportsInView[(at + 1) % reportsInView.size]
                        lastJump.value = next.tripId
                        mapView.controller.animateTo(GeoPoint(next.latitude, next.longitude))
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "!",
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pluralStringResource(R.plurals.map_reports_in_view, reportsInView.size, reportsInView.size),
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // The credit OpenStreetMap asks for; a tap opens its copyright page.
        val uriHandler = LocalUriHandler.current
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .clickable { uriHandler.openUri(OSM_COPYRIGHT_URL) }
        ) {
            Text(
                text = stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** Stable per-marker key: the trip id, or a label+stops fallback when absent. */
private fun trainKey(t: TrainPosition): String =
    t.tripId ?: "${t.label}|${t.fromStop}|${t.toStop}"

/** One marker's slide from its drawn position to its newest position. */
private class Glide(val fromLat: Double, val fromLon: Double, val toLat: Double, val toLon: Double)

/** A vehicle's marker and how it is drawn right now. */
private class Badge(val key: String, val marker: Marker, var flagged: Boolean, var size: ZoomTier, var rank: Int)

/** What the map's update block last applied, so it only restyles when something changed. */
private class DrawnStyle {
    var tier: ZoomTier? = null
    var selectedRoute: String? = null
    /** The route lines in draw order, the top one last. */
    var routeOrder: List<RouteLine> = emptyList()
}

/**
 * A group of overlays drawn in list order (the last one on top). Taps go to the top
 * one first, or to none when the layer is not [tappable].
 */
private class Layer<T : Overlay>(private val tappable: Boolean) : Overlay() {
    var items: List<T> = emptyList()

    override fun draw(canvas: Canvas, projection: Projection) {
        for (i in items.indices) items[i].draw(canvas, projection)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (!tappable) return false
        for (i in items.indices.reversed()) {
            if (items[i].onSingleTapConfirmed(e, mapView)) return true
        }
        return false
    }

    override fun onDetach(mapView: MapView) {
        items.forEach { it.onDetach(mapView) }
        items = emptyList()
    }
}

// One glide spans one position tick, so consecutive glides join into steady motion.
private const val GLIDE_MILLIS = LiveMapViewModel.TICK_MILLIS
private const val FRAME_MIN_MILLIS = 30L
// ~2 km: further than a train moves in a tick, so this is a data jump, not motion.
private const val SNAP_DEGREES = 0.02

// Route lines: a white edge on each side, pale unless tapped, a little wider when tapped.
private const val LINE_EDGE_DP = 1f
private const val PALE_LINE_ALPHA = 150
private const val SELECTED_EXTRA_DP = 1.5f
// How far from a line a tap still counts as a tap on it.
private const val LINE_TAP_DP = 14f

/** The badge drawable for a train: line colour, rounded-square (rail) or pill
 *  (bus/tram), in the size the zoom asks for; with a red ring and "!" when [flagged]. */
private fun trainBadgeFor(context: Context, train: TrainPosition, flagged: Boolean, size: ZoomTier): Drawable =
    trainBadge(
        context,
        trainColorArgb(train.colorHex, train.label, train.mode),
        train.label,
        flagged,
        train.mode == "BUS" || train.mode == "TRAM",
        size
    )

/**
 * Real-time-backed trains are near-solid; schedule-only trains (no live feed —
 * drawn where they *should* be, not where they are) are faded so the user can tell
 * the difference at a glance (Phase 6 / Option 1). A reported vehicle is never faded.
 */
private fun badgeAlpha(train: TrainPosition, flagged: Boolean): Float = when {
    flagged -> 1f
    train.isRealtime -> 0.85f
    else -> 0.4f
}

/**
 * The route line of one line: a white edge underneath, the line's colour on top,
 * with a per-mode stroke style (S-Bahn solid, U-Bahn dashed, Tram dotted, Bus
 * dash-dot).
 */
private class RouteLine(val key: String, train: TrainPosition, points: List<DoubleArray>, density: Float) {
    val rank = drawRank(train.mode)
    private val edge = linePaint(Color.WHITE)
    private val fill = linePaint(trainColorArgb(train.colorHex, train.label, train.mode)).apply {
        pathEffect = dashForMode(train.mode, density)
    }
    val line = Polyline().apply {
        setPoints(points.map { GeoPoint(it[0], it[1]) })
        // Drawn one after the other: first the edge, then the colour on top of it.
        outlinePaintLists.add(MonochromaticPaintList(edge))
        outlinePaintLists.add(MonochromaticPaintList(fill))
    }

    fun style(tier: ZoomTier, selected: Boolean, density: Float) {
        val width = lineWidthDp(tier) + if (selected) SELECTED_EXTRA_DP else 0f
        fill.strokeWidth = width * density
        // Over the white edge a see-through colour is a paler colour.
        fill.alpha = if (selected) 255 else PALE_LINE_ALPHA
        edge.strokeWidth = (width + 2f * LINE_EDGE_DP) * density
    }

    private fun linePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

/** Stroke pattern per transport mode (null = solid). */
private fun dashForMode(mode: String, d: Float): DashPathEffect? = when (mode) {
    "SUBWAY" -> DashPathEffect(floatArrayOf(11f * d, 7f * d), 0f)                 // U-Bahn: dashed
    "TRAM" -> DashPathEffect(floatArrayOf(1f * d, 7f * d), 0f)                     // Tram: dotted (round cap)
    "BUS" -> DashPathEffect(floatArrayOf(11f * d, 6f * d, 1f * d, 6f * d), 0f)     // Bus: dash-dot
    else -> null                                                                  // S-Bahn & other rail: solid
}

private fun trainMarker(
    mapView: MapView,
    context: Context,
    train: TrainPosition,
    flagged: Boolean,
    size: ZoomTier,
    onTrainClick: (String, String?, String?, String?, String) -> Unit
): Marker {
    return Marker(mapView).apply {
        position = GeoPoint(train.latitude, train.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = trainBadgeFor(context, train, flagged, size)
        alpha = badgeAlpha(train, flagged)
        title = train.label
        relatedObject = TRAIN_TAG
        // Tapping a train opens the Report screen for exactly that train.
        setOnMarkerClickListener { _, _ ->
            onTrainClick(train.label, train.tripId, train.fromStop ?: train.toStop, train.mode, train.city)
            true
        }
    }
}

/**
 * The line's colour: the official colour from the feed (correct in every city),
 * then a Munich fallback table, then a per-mode default.
 */
internal fun trainColorArgb(colorHex: String?, label: String, mode: String): Int {
    if (!colorHex.isNullOrBlank()) {
        val clean = if (colorHex.startsWith("#")) colorHex else "#$colorHex"
        runCatching { Color.parseColor(clean) }.getOrNull()?.let { return it }
    }
    return MUNICH_LINE_COLORS[label] ?: colorForMode(mode)
}

internal fun colorForMode(mode: String): Int = when (mode) {
    "METRO" -> Color.rgb(0, 153, 68)
    "SUBWAY" -> Color.rgb(0, 98, 155)
    "TRAM" -> Color.rgb(214, 90, 20)          // Straßenbahn: orange
    "BUS" -> Color.rgb(124, 58, 173)          // Bus: violet
    "REGIONAL_RAIL", "REGIONAL_FAST_RAIL" -> Color.rgb(0, 150, 136)
    "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL" -> Color.rgb(60, 60, 60)
    else -> Color.rgb(120, 120, 120)
}

private val MUNICH_LINE_COLORS: Map<String, Int> = mapOf(
    "S1" to 0xFF16BAE7.toInt(), "S2" to 0xFF76B82A.toInt(), "S3" to 0xFF951B81.toInt(),
    "S4" to 0xFFE30613.toInt(), "S6" to 0xFF00975F.toInt(), "S7" to 0xFF943126.toInt(),
    "S8" to 0xFF222222.toInt(), "S20" to 0xFFEE6BA0.toInt(),
    "U1" to 0xFF52822F.toInt(), "U2" to 0xFFC20831.toInt(), "U3" to 0xFFEC6725.toInt(),
    "U4" to 0xFF00A984.toInt(), "U5" to 0xFFBC7A00.toInt(), "U6" to 0xFF0065B1.toInt()
)

// Cache the (relatively expensive) badge bitmaps; wrappers are cheap per marker.
private val badgeBitmaps = HashMap<String, Bitmap>()

private fun trainBadge(
    context: Context, color: Int, label: String, flagged: Boolean, elongated: Boolean, size: ZoomTier
): Drawable {
    // A dot shows no label, so all lines of one colour share one bitmap.
    val key = if (size == ZoomTier.FAR) "dot|$color|$elongated" else "$color|$label|$flagged|$elongated|$size"
    val bitmap = badgeBitmaps.getOrPut(key) {
        val density = context.resources.displayMetrics.density
        if (size == ZoomTier.FAR) createDotBitmap(density, color, elongated)
        else createBadgeBitmap(density, color, label, flagged, elongated, small = size == ZoomTier.MID)
    }
    return BitmapDrawable(context.resources, bitmap)
}

/**
 * A line badge: a **rounded square** for rail (S-/U-Bahn and other trains) or an
 * **elongated roundel** (pill) for bus/tram. Sized to the label so longer bus
 * numbers still fit; [small] is the size for a map that is zoomed out a little. A
 * reported trip ([flagged]) gets a red ring around the whole badge and a red "!".
 */
private fun createBadgeBitmap(
    density: Float, color: Int, label: String, flagged: Boolean, elongated: Boolean, small: Boolean
): Bitmap {
    val text = label.take(if (elongated) 5 else 4)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = (if (small) 7f else 9f) * density
    }

    val height = (if (small) 16f else 22f) * density
    val padH = (if (elongated) (if (small) 5.5f else 8f) else (if (small) 2.5f else 3.5f)) * density
    val shapeW = (paint.measureText(text) + 2f * padH).coerceAtLeast(if (elongated) height * 1.6f else height)
    // Room around the shape: for its outline, or for the ring and the "!" of a report.
    val margin = (if (flagged) REPORT_MARGIN_DP else 3f) * density
    val bmpW = (shapeW + 2f * margin).toInt().coerceAtLeast(1)
    val bmpH = (height + 2f * margin).toInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val rect = RectF(margin, margin, margin + shapeW, margin + height)
    val corner = if (elongated) height / 2f else (if (small) 4.5f else 6f) * density
    val outline = (if (small) 1.2f else 1.5f) * density

    paint.style = Paint.Style.FILL
    paint.color = color
    canvas.drawRoundRect(rect, corner, corner, paint)
    paint.style = Paint.Style.STROKE
    paint.color = Color.WHITE
    paint.strokeWidth = outline
    canvas.drawRoundRect(rect, corner, corner, paint)

    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    val cx = rect.centerX()
    val cy = rect.centerY()
    val fm = paint.fontMetrics
    canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, paint)

    if (flagged) {
        // A red ring around the whole badge, just outside its white outline.
        val ringWidth = 2.5f * density
        val out = outline / 2f + ringWidth / 2f
        val ring = RectF(rect.left - out, rect.top - out, rect.right + out, rect.bottom + out)
        paint.style = Paint.Style.STROKE
        paint.color = REPORT_RED
        paint.strokeWidth = ringWidth
        canvas.drawRoundRect(ring, corner + out, corner + out, paint)

        // The "!" on the top right corner, pushed outwards so it hides little of the label.
        val br = 9f * density
        val bx = rect.right + 3f * density
        val by = rect.top - 3f * density
        paint.style = Paint.Style.FILL
        canvas.drawCircle(bx, by, br, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 1.5f * density
        canvas.drawCircle(bx, by, br, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 13f * density
        val fm2 = paint.fontMetrics
        canvas.drawText("!", bx, by - (fm2.ascent + fm2.descent) / 2f, paint)
    }

    return bitmap
}

/**
 * The badge of a vehicle on a map that is zoomed far out: a dot in the line's
 * colour without a label, smaller for bus and tram. The bitmap is larger than the
 * dot, so the dot can still be tapped.
 */
private fun createDotBitmap(density: Float, color: Int, elongated: Boolean): Bitmap {
    val side = (DOT_TAP_DP * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = (if (elongated) 3.5f else 5f) * density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.FILL
    paint.color = color
    canvas.drawCircle(side / 2f, side / 2f, radius, paint)
    paint.style = Paint.Style.STROKE
    paint.color = Color.WHITE
    paint.strokeWidth = 1.2f * density
    canvas.drawCircle(side / 2f, side / 2f, radius, paint)
    return bitmap
}

private val REPORT_RED = ReportRed.toArgb()
// Room for the red ring and for the "!" that sticks out of the top right corner.
private const val REPORT_MARGIN_DP = 13.5f
private const val DOT_TAP_DP = 20f
