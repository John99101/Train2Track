package com.mvvtracker.app.data.remote

import java.security.MessageDigest
import java.util.Base64
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A decoded track, prepared once so positions along it are cheap: [lats]/[lons]
 * are the points and [cumulative] the (latitude-corrected) distance from the
 * start to each point. [pointAt] is then a binary search instead of re-measuring
 * the whole line, which is what made the old per-tick recompute expensive.
 */
class Track(val lats: DoubleArray, val lons: DoubleArray, private val cumulative: DoubleArray) {

    val size: Int get() = lats.size

    /** Writes the point [fraction] (0..1) of the way along into [out] as [lat, lon]. */
    fun pointAt(fraction: Double, out: DoubleArray) {
        val n = lats.size
        val total = cumulative[n - 1]
        if (n == 1 || fraction <= 0.0 || total <= 0.0) { out[0] = lats[0]; out[1] = lons[0]; return }
        if (fraction >= 1.0) { out[0] = lats[n - 1]; out[1] = lons[n - 1]; return }
        val target = fraction * total
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] < target) lo = mid else hi = mid
        }
        val len = cumulative[hi] - cumulative[lo]
        val t = if (len <= 0.0) 0.0 else (target - cumulative[lo]) / len
        out[0] = lats[lo] + (lats[hi] - lats[lo]) * t
        out[1] = lons[lo] + (lons[hi] - lons[lo]) * t
    }

    fun points(): List<DoubleArray> = List(size) { doubleArrayOf(lats[it], lons[it]) }

    companion object {
        fun of(points: List<DoubleArray>): Track? {
            if (points.isEmpty()) return null
            val n = points.size
            val lats = DoubleArray(n) { points[it][0] }
            val lons = DoubleArray(n) { points[it][1] }
            val cum = DoubleArray(n)
            for (i in 1 until n) {
                val dLat = lats[i] - lats[i - 1]
                val dLon = (lons[i] - lons[i - 1]) * cos(Math.toRadians((lats[i] + lats[i - 1]) / 2.0))
                cum[i] = cum[i - 1] + sqrt(dLat * dLat + dLon * dLon)
            }
            return Track(lats, lons, cum)
        }
    }
}

/**
 * Decoding of Google "encoded polyline" strings (precision 5, as used by MOTIS)
 * and interpolation of a point a given fraction of the way along the line.
 * Used to turn a train's [departure, arrival] track segment into a live position.
 */
object GeoPolyline {

    /**
     * Short stable key for an encoded geometry: the first 12 characters of its
     * SHA-1 in URL-safe base64. Must match shapeKey() in functions/lib/transitous.js
     * so geometries fetched directly from transitous and via the server share one cache.
     */
    fun shapeKey(encoded: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(encoded.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(12)
    }

    /**
     * Returns the polyline points as [latitude, longitude] pairs. [precision] is the
     * number of decimal digits the coordinates were encoded with: MOTIS map/trips
     * segments use 5, while /api/v1/trip leg geometry uses 7.
     *
     * A damaged string (cut off in the middle of a value, or not a polyline at all)
     * gives the points decoded up to that place instead of crashing: the strings come
     * from the network and are kept on the device, so one bad string would otherwise
     * crash the app at every start. Same as GeoPolyline.swift on iOS.
     */
    fun decode(encoded: String, precision: Int = 5): List<DoubleArray> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<DoubleArray>()
        var index = 0
        var lat = 0
        var lng = 0

        // The next value, or null when the string ends before the value does (or a
        // value runs on longer than any real coordinate can).
        fun nextValue(): Int? {
            var shift = 0
            var result = 0
            var b: Int
            do {
                if (index >= encoded.length || shift > 30) return null
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }

        while (index < encoded.length) {
            val dLat = nextValue() ?: break
            val dLng = nextValue() ?: break
            lat += dLat
            lng += dLng
            points.add(doubleArrayOf(lat / factor, lng / factor))
        }
        return points
    }

    /** Encodes [lat, lon] points as a Google encoded polyline (inverse of [decode]). */
    fun encode(points: List<DoubleArray>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        fun put(delta: Long) {
            var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (v >= 0x20) {
                out.append(((0x20L or (v and 0x1f)) + 63).toInt().toChar())
                v = v shr 5
            }
            out.append((v + 63).toInt().toChar())
        }
        for (p in points) {
            val lat = Math.round(p[0] * factor)
            val lng = Math.round(p[1] * factor)
            put(lat - prevLat)
            put(lng - prevLng)
            prevLat = lat
            prevLng = lng
        }
        return out.toString()
    }

    /**
     * Ramer–Douglas–Peucker simplification: drops points that stray less than
     * [toleranceDeg] (degrees) from the line, cutting a dense route to far fewer
     * points so it is cheaper to store and draw. Iterative (no deep recursion).
     */
    fun simplify(points: List<DoubleArray>, toleranceDeg: Double): List<DoubleArray> {
        val n = points.size
        if (n < 3) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var maxDist = 0.0
            var idx = -1
            for (i in first + 1 until last) {
                val d = perpDistanceDeg(points[i], points[first], points[last])
                if (d > maxDist) { maxDist = d; idx = i }
            }
            if (maxDist > toleranceDeg && idx != -1) {
                keep[idx] = true
                stack.addLast(intArrayOf(first, idx))
                stack.addLast(intArrayOf(idx, last))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Perpendicular distance (in degrees, longitude latitude-corrected) from p to line a–b. */
    private fun perpDistanceDeg(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val midLatRad = Math.toRadians((a[0] + b[0]) / 2.0)
        val kx = cos(midLatRad)
        val ax = a[1] * kx; val ay = a[0]
        val bx = b[1] * kx; val by = b[0]
        val px = p[1] * kx; val py = p[0]
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) {
            val ex = px - ax; val ey = py - ay
            return sqrt(ex * ex + ey * ey)
        }
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        val projX = ax + t * dx; val projY = ay + t * dy
        val ex = px - projX; val ey = py - projY
        return sqrt(ex * ex + ey * ey)
    }

    /**
     * The point [fraction] (0..1) of the way along [points], measured by
     * (latitude-corrected) planar distance. Returns [lat, lon], or null if empty.
     */
    fun pointAtFraction(points: List<DoubleArray>, fraction: Double): DoubleArray? {
        if (points.isEmpty()) return null
        if (points.size == 1 || fraction <= 0.0) return points.first()
        if (fraction >= 1.0) return points.last()

        val segmentLengths = DoubleArray(points.size - 1)
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val c = points[i + 1]
            val midLatRad = Math.toRadians((a[0] + c[0]) / 2.0)
            val dLat = c[0] - a[0]
            val dLon = (c[1] - a[1]) * cos(midLatRad)
            val d = sqrt(dLat * dLat + dLon * dLon)
            segmentLengths[i] = d
            total += d
        }
        if (total <= 0.0) return points.first()

        var target = fraction * total
        for (i in segmentLengths.indices) {
            val len = segmentLengths[i]
            if (target <= len) {
                val t = if (len == 0.0) 0.0 else target / len
                val a = points[i]
                val c = points[i + 1]
                return doubleArrayOf(a[0] + (c[0] - a[0]) * t, a[1] + (c[1] - a[1]) * t)
            }
            target -= len
        }
        return points.last()
    }
}
