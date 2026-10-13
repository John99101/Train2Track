package com.mvvtracker.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mvvtracker.app.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.data.live.routeKey
import com.mvvtracker.app.viewmodel.LiveMapViewModel
import com.mvvtracker.app.viewmodel.TrainPosition
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

internal const val TRAIN_TAG = "train"
internal const val ROUTE_TAG = "route"

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

    val mapView = remember {
        MapView(context).apply {
            // Free OpenStreetMap tiles. To use the (paid, commercial) CARTO style
            // instead, swap this for setTileSource(CARTO_VOYAGER).
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(centerLat, centerLon))
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
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(GeoPoint(centerLat, centerLon))
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
        mapView.addOnFirstLayoutListener { _, _, _, _, _ -> reportBounds() }
        mapView.post { reportBounds() }
        onDispose { }
    }

    val shown = if (modeFilter == null) trains else trains.filter { it.mode in modeFilter }

    // Only fetch full routes for the lines the map is actually showing.
    val shownRouteKeys = remember(shown) { shown.mapTo(HashSet()) { routeKey(it.mode, it.label) } }
    LaunchedEffect(shownRouteKeys) { liveViewModel.ensureRoutes(shownRouteKeys) }

    // Persistent overlays reused across position ticks (see update below): moving a
    // marker or keeping a static route line is far cheaper than rebuilding them all
    // every tick.
    val markerCache = remember { HashMap<String, Marker>() }
    val markerFlagged = remember { HashMap<String, Boolean>() }
    val routeCache = remember { HashMap<String, Polyline>() }

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
                        markerCache[key]?.position?.setCoords(
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
                val density = context.resources.displayMetrics.density

                // ROUTES (persistent): one full line per shown line with a cached
                // route. Static, so add once and keep across ticks; only add newly
                // shown lines and drop ones no longer shown.
                val repByRoute = HashMap<String, TrainPosition>()
                shown.forEach { t ->
                    val k = routeKey(t.mode, t.label)
                    if (routes.containsKey(k)) repByRoute.putIfAbsent(k, t)
                }
                repByRoute.forEach { (k, rep) ->
                    if (!routeCache.containsKey(k)) {
                        val pl = routeOverlay(rep, routes.getValue(k), density)
                        routeCache[k] = pl
                        mv.overlays.add(0, pl)   // keep routes under the markers
                    }
                }
                (routeCache.keys - repByRoute.keys).toList().forEach { k ->
                    routeCache.remove(k)?.let { mv.overlays.remove(it) }
                }

                // TRAIN MARKERS (reused): move existing markers, add new ones, drop
                // gone ones — instead of recreating every marker each tick.
                val shownByKey = LinkedHashMap<String, TrainPosition>()
                shown.forEach { shownByKey[trainKey(it)] = it }
                glides.clear()
                shownByKey.forEach { (key, train) ->
                    val flagged = train.tripId != null && train.tripId in flaggedTripIds
                    val existing = markerCache[key]
                    if (existing != null) {
                        // Glide from where it is drawn now; a big jump (new data put the
                        // train somewhere else entirely) snaps instead of sliding across.
                        val from = existing.position
                        if (abs(from.latitude - train.latitude) < SNAP_DEGREES &&
                            abs(from.longitude - train.longitude) < SNAP_DEGREES
                        ) {
                            glides[key] = Glide(from.latitude, from.longitude, train.latitude, train.longitude)
                        } else {
                            from.setCoords(train.latitude, train.longitude)
                        }
                        // Keep the live/schedule-only fade in sync (cheap — no bitmap
                        // re-render), in case the trip gains or loses its real-time feed.
                        existing.alpha = if (train.isRealtime) 0.85f else 0.4f
                        // Re-render the badge only when the report state actually flips,
                        // so a train that gets reported shows the "!" (and clears it).
                        if (markerFlagged[key] != flagged) {
                            existing.icon = trainBadgeFor(context, train, flagged)
                            markerFlagged[key] = flagged
                        }
                        existing.setOnMarkerClickListener { _, _ ->
                            onTrainClick(train.label, train.tripId, train.fromStop ?: train.toStop, train.mode, train.city)
                            true
                        }
                    } else {
                        val m = trainMarker(mv, context, train, flagged, onTrainClick)
                        markerCache[key] = m
                        markerFlagged[key] = flagged
                        mv.overlays.add(m)
                    }
                }
                (markerCache.keys - shownByKey.keys).toList().forEach { key ->
                    markerCache.remove(key)?.let { mv.overlays.remove(it) }
                    markerFlagged.remove(key)
                }
                if (glides.isNotEmpty()) glideSignal.trySend(Unit)

                mv.invalidate()
            }
        )

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
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

// One glide spans one position tick, so consecutive glides join into steady motion.
private const val GLIDE_MILLIS = LiveMapViewModel.TICK_MILLIS
private const val FRAME_MIN_MILLIS = 30L
// ~2 km: further than a train moves in a tick, so this is a data jump, not motion.
private const val SNAP_DEGREES = 0.02

/** The badge drawable for a train: line colour, rounded-square (rail) or pill
 *  (bus/tram), with a red "!" corner when [flagged]. */
private fun trainBadgeFor(context: Context, train: TrainPosition, flagged: Boolean): Drawable =
    trainBadge(
        context,
        trainColorArgb(train.colorHex, train.label, train.mode),
        train.label,
        flagged,
        train.mode == "BUS" || train.mode == "TRAM"
    )

/**
 * The route line for one line, in the line's colour and with a per-mode stroke
 * style: S-Bahn solid, U-Bahn dashed, Tram dotted, Bus dash-dot.
 */
private fun routeOverlay(train: TrainPosition, points: List<DoubleArray>, density: Float): Polyline {
    return Polyline().apply {
        relatedObject = ROUTE_TAG
        setPoints(points.map { GeoPoint(it[0], it[1]) })
        outlinePaint.color = trainColorArgb(train.colorHex, train.label, train.mode)
        outlinePaint.alpha = 200
        outlinePaint.strokeWidth = 5f * density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.pathEffect = dashForMode(train.mode, density)
        // Don't let the line swallow taps meant for the train badges on top of it.
        setOnClickListener { _, _, _ -> false }
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
    onTrainClick: (String, String?, String?, String?, String) -> Unit
): Marker {
    return Marker(mapView).apply {
        position = GeoPoint(train.latitude, train.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = trainBadgeFor(context, train, flagged)
        // Real-time-backed trains are near-solid; schedule-only trains (no live
        // feed — drawn where they *should* be, not where they are) are faded so
        // the user can tell the difference at a glance (Phase 6 / Option 1).
        alpha = if (train.isRealtime) 0.85f else 0.4f
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

private fun trainBadge(context: Context, color: Int, label: String, flagged: Boolean, elongated: Boolean): Drawable {
    val key = "$color|$label|$flagged|$elongated"
    val bitmap = badgeBitmaps.getOrPut(key) { createBadgeBitmap(context, color, label, flagged, elongated) }
    return BitmapDrawable(context.resources, bitmap)
}

/**
 * A line badge: a **rounded square** for rail (S-/U-Bahn and other trains) or an
 * **elongated roundel** (pill) for bus/tram. Sized to the label so longer bus
 * numbers still fit; a red "!" corner badge marks a flagged trip.
 */
private fun createBadgeBitmap(context: Context, color: Int, label: String, flagged: Boolean, elongated: Boolean): Bitmap {
    val density = context.resources.displayMetrics.density
    val text = label.take(if (elongated) 5 else 4)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = 9f * density
    }

    val height = 22f * density
    val padH = (if (elongated) 8f else 5f) * density
    val shapeW = if (elongated) (paint.measureText(text) + 2f * padH).coerceAtLeast(height * 1.6f)
                 else height
    val margin = 3f * density   // room for the "!" corner badge to overflow
    val bmpW = (shapeW + 2f * margin).toInt().coerceAtLeast(1)
    val bmpH = (height + 2f * margin).toInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val rect = RectF(margin, margin, margin + shapeW, margin + height)
    val corner = if (elongated) height / 2f else 6f * density

    paint.style = Paint.Style.FILL
    paint.color = color
    canvas.drawRoundRect(rect, corner, corner, paint)
    paint.style = Paint.Style.STROKE
    paint.color = Color.WHITE
    paint.strokeWidth = 1.5f * density
    canvas.drawRoundRect(rect, corner, corner, paint)

    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    val cx = rect.centerX()
    val cy = rect.centerY()
    val fm = paint.fontMetrics
    canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, paint)

    if (flagged) {
        val br = 7f * density
        val bx = bmpW - br
        val by = br
        paint.color = Color.rgb(220, 40, 40)
        canvas.drawCircle(bx, by, br, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 1.5f * density
        canvas.drawCircle(bx, by, br, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 10f * density
        val fm2 = paint.fontMetrics
        canvas.drawText("!", bx, by - (fm2.ascent + fm2.descent) / 2f, paint)
    }

    return bitmap
}
