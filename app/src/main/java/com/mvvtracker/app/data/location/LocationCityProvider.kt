package com.mvvtracker.app.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.mvvtracker.app.data.model.GERMAN_CITIES
import com.mvvtracker.app.data.model.GermanCity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Resolves the user's nearest supported city from their coarse location, so the
 * Map can center there and the Feed can preselect it. Returns null when there is
 * no location permission / fix, or the user isn't near any supported city, in
 * which case callers fall back to the default (München).
 */
@Singleton
class LocationCityProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Nearest supported city within [MAX_KM], or null. */
    fun nearestCity(): GermanCity? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        var best: Location? = null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (provider in providers) {
            val loc = try {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            } catch (e: SecurityException) {
                null
            } ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        val here = best ?: return null

        return GERMAN_CITIES
            .minByOrNull { distanceKm(it.latitude, it.longitude, here.latitude, here.longitude) }
            ?.takeIf { distanceKm(it.latitude, it.longitude, here.latitude, here.longitude) <= MAX_KM }
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        const val MAX_KM = 60.0
    }
}
