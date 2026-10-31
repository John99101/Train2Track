package com.mvvtracker.app.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class LiveHostTest {

    @After fun reset() = LiveHost.configure(null)

    @Test fun onlyHttpsAddressesUnderTrain2TrackAreTaken() {
        assertEquals("api.example.invalid", LiveHost.parse("https://api.example.invalid/")?.host)
        assertEquals("cdn.example.invalid", LiveHost.parse(" https://cdn.example.invalid ")?.host)
        // The old KeyCDN test domain is no longer taken (Cloudflare since 30 October 2026).
        assertNull(LiveHost.parse("https://t2t-1a2b.kxcdn.com/"))
        assertNotNull(LiveHost.parse("https://example.invalid/"))
        assertNull(LiveHost.parse(null))
        assertNull(LiveHost.parse(""))
        assertNull(LiveHost.parse("http://api.example.invalid/"))
        assertNull(LiveHost.parse("https://example.com/"))
        assertNull(LiveHost.parse("https://example.invalid.example.com/"))
        assertNull(LiveHost.parse("https://evilexample.invalid/"))
        assertNull(LiveHost.parse("api.example.invalid"))
    }

    @Test fun aFailureLeavesTheCdnAloneForTenMinutes() {
        LiveHost.configure("https://api.example.invalid/")
        assertNotNull(LiveHost.current(nowMs = 1_000L))
        LiveHost.failed(nowMs = 1_000L)
        assertNull(LiveHost.current(nowMs = 1_000L + LiveHost.PAUSE_MS - 1))
        assertNotNull(LiveHost.current(nowMs = 1_000L + LiveHost.PAUSE_MS))
    }

    @Test fun onlyTheLiveDataPaths() {
        assertEquals(true, LiveHost.isLivePath("/api/live/berlin/rail"))
        assertEquals(true, LiveHost.isLivePath("/api/live/berlin"))
        assertEquals(false, LiveHost.isLivePath("/api/routes/berlin"))
        assertEquals(false, LiveHost.isLivePath("/api/shapes/berlin"))
        assertEquals(false, LiveHost.isLivePath("/api/route/berlin/TRAM/27/x"))
    }

    // ---- the interceptor, with a stand-in for the network ----------------------------

    /** Answers instead of the network and notes the host of every request. */
    private class FakeNetwork(val answer: (Request) -> Response) : Interceptor {
        val hosts = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            hosts += chain.request().url.host
            return answer(chain.request())
        }
    }

    private fun response(request: Request, code: Int) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
        .body("{}".toResponseBody(null)).build()

    private fun call(fake: FakeNetwork, path: String): Int {
        val client = OkHttpClient.Builder().addInterceptor(LiveHostInterceptor()).addInterceptor(fake).build()
        return client.newCall(Request.Builder().url("https://example.invalid$path").build()).execute().use { it.code }
    }

    @Test fun withoutCdnEverythingGoesToTheServer() {
        val fake = FakeNetwork { response(it, 200) }
        call(fake, "/api/live/berlin/rail")
        assertEquals(listOf("example.invalid"), fake.hosts)
    }

    @Test fun liveDataGoesThroughTheCdnRoutesDoNot() {
        LiveHost.configure("https://api.example.invalid/")
        val fake = FakeNetwork { response(it, if (it.url.host == "api.example.invalid") 304 else 200) }
        assertEquals(304, call(fake, "/api/live/berlin/rail"))
        call(fake, "/api/routes/berlin")
        assertEquals(listOf("api.example.invalid", "example.invalid"), fake.hosts)
    }

    @Test fun aCdnErrorFallsBackToTheServerAndPausesTheCdn() {
        LiveHost.configure("https://api.example.invalid/")
        val fake = FakeNetwork { response(it, if (it.url.host == "api.example.invalid") 503 else 200) }
        assertEquals(200, call(fake, "/api/live/berlin/rail"))
        assertEquals(200, call(fake, "/api/live/berlin/bus"))
        // CDN once, the server for that request, then the server only (paused).
        assertEquals(listOf("api.example.invalid", "example.invalid", "example.invalid"), fake.hosts)
    }

    @Test fun anUnreachableCdnFallsBackToTheServer() {
        LiveHost.configure("https://cdn.example.invalid/")
        val fake = FakeNetwork {
            if (it.url.host == "cdn.example.invalid") throw IOException("no route") else response(it, 200)
        }
        assertEquals(200, call(fake, "/api/live/berlin/rail"))
        assertEquals(listOf("cdn.example.invalid", "example.invalid"), fake.hosts)
    }
}
