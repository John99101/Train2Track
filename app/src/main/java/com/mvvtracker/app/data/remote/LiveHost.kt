package com.mvvtracker.app.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Where the live vehicle data is loaded from: the Train2Track server itself, or a
 * CDN in front of it (KeyCDN) once the owner sets its address in Firestore
 * (document config/app, text field liveCdn, for example "https://api.example.invalid/").
 * Read once per start together with the minimum build ([MinVersionCheck]). No
 * field, an empty field or an address that is not allowed: the server itself.
 *
 * Only the live data goes through the CDN (paths under /api/live/); line routes and
 * track shapes stay on the server. When the CDN fails (no connection, or an answer
 * of 500 and above), the request is sent to the server again at once and the CDN
 * is left alone for ten minutes, so a broken CDN never stops the live data.
 * iOS: `LiveHost.swift`.
 */
object LiveHost {
    const val FIELD = "liveCdn"
    const val PAUSE_MS = 10 * 60_000L

    @Volatile private var cdn: HttpUrl? = null
    @Volatile private var pausedUntil = 0L

    fun configure(value: String?) {
        cdn = parse(value)
        pausedUntil = 0L
    }

    /** The CDN to use now, or null for the server itself. */
    fun current(nowMs: Long = System.currentTimeMillis()): HttpUrl? =
        cdn?.takeIf { nowMs >= pausedUntil }

    /** The CDN failed: use the server itself for the next ten minutes. */
    fun failed(nowMs: Long = System.currentTimeMillis()) {
        pausedUntil = nowMs + PAUSE_MS
    }

    /**
     * Only https addresses under example.invalid (the CDN's own name for the app) or
     * KeyCDN's test domain kxcdn.com are taken, so a typing error in the console
     * cannot send the apps anywhere else.
     */
    fun parse(value: String?): HttpUrl? {
        val url = value?.trim()?.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        val host = url.host
        val allowed = host == "example.invalid" || host.endsWith(".example.invalid") || host.endsWith(".kxcdn.com")
        return url.takeIf { allowed }
    }

    /** Only these requests may go through the CDN. */
    fun isLivePath(encodedPath: String): Boolean = encodedPath.startsWith("/api/live/")
}

/** Sends the live data requests to the CDN when one is set, see [LiveHost]. */
class LiveHostInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cdn = LiveHost.current()
        if (cdn == null || !LiveHost.isLivePath(request.url.encodedPath)) return chain.proceed(request)

        val viaCdn = request.newBuilder()
            .url(request.url.newBuilder().scheme(cdn.scheme).host(cdn.host).port(cdn.port).build())
            .build()
        try {
            val response = chain.proceed(viaCdn)
            if (response.code < 500) return response
            response.close()
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
        }
        LiveHost.failed()
        return chain.proceed(request)
    }
}
