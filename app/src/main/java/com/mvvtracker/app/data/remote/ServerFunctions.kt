package com.mvvtracker.app.data.remote

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.mvvtracker.app.data.repository.UserReportBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the server's callable functions (functions/index.js: deleteMyData and the
 * admin functions) over plain HTTPS, with the same protocol Google's Functions
 * library uses: a JSON body `{"data": ...}`, the caller's Firebase ID token and
 * the App Check token as headers, and `{"result": ...}` or `{"error": ...}` back.
 *
 * The app does this itself because Google's Functions library brings the old
 * Instance ID component along, which registers the installation with Google's
 * messaging service on every call. The app has no notifications, so it must not
 * register for them either.
 */
@Singleton
class ServerFunctions @Inject constructor(
    private val auth: FirebaseAuth,
    private val client: OkHttpClient
) {
    /**
     * Calls [name] with [data] and returns its result object (null if the function
     * returns none). Throws with the server's message when the call fails.
     */
    suspend fun call(
        name: String,
        data: Map<String, Any?> = emptyMap(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): JSONObject? = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: throw IOException("Not signed in.")
        val idToken = user.getIdToken(false).await().token ?: throw IOException("Not signed in.")
        // Without a valid App Check token the server decides (see ENFORCE_APP_CHECK).
        val appCheck = runCatching {
            FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
        }.getOrNull()

        val request = Request.Builder()
            .url(baseUrl() + name)
            .post(JSONObject().put("data", JSONObject(data)).toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $idToken")
            .apply { if (!appCheck.isNullOrEmpty()) header("X-Firebase-AppCheck", appCheck) }
            .build()

        val call = client.newBuilder()
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds + 5, TimeUnit.SECONDS)
            .build()
            .newCall(request)
        call.execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            if (!response.isSuccessful) {
                val message = json?.optJSONObject("error")?.optString("message").orEmpty()
                throw IOException(message.ifEmpty { "HTTP ${response.code}" })
            }
            json?.optJSONObject("result")
        }
    }

    private fun baseUrl(): String {
        val project = FirebaseApp.getInstance().options.projectId
        return "https://${UserReportBackend.FUNCTIONS_REGION}-$project.cloudfunctions.net/"
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 70L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
