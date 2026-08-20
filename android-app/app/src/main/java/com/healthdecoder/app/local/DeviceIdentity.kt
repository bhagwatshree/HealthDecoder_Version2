package com.healthdecoder.app.local

import android.content.Context
import com.google.gson.JsonObject
import com.healthdecoder.app.network.NetworkModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Anonymous per-install identity for the AI proxy (see BackendAiClient). Phone OTP sign-in is
 * optional/off by default, so most phones never become a logged-in user — this registers a
 * UUID the app generates once with the backend (no SMS, no login) and gets back a long-lived
 * device token, so /api/ai/generate can still meter/pool a Gemini key per install.
 */
object DeviceIdentity {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns a usable device token, registering with the backend first if none is cached yet.
     * Synchronous/blocking (matches GeminiClient/BackendAiClient's style) — call only from a
     * background thread. Returns null if registration fails (e.g. no connectivity); callers
     * should surface a clear "can't reach the server" error rather than falling back to any
     * embedded key.
     */
    fun ensureToken(context: Context): String? {
        AppSettings.getDeviceToken(context)?.let { return it }

        val deviceId = AppSettings.getOrCreateInstallId(context)
        val body = JsonObject().apply { addProperty("deviceId", deviceId) }

        // Same host fallback as BackendAiClient.generate(): the Function URL sits on a different
        // domain from the rest of the backend, and a network that blocks it would leave the app
        // with no device token at all — which reads to the user as "every scan fails" even though
        // the identical handler is reachable through API Gateway.
        val hosts = listOf(NetworkModule.AI_PROXY_BASE_URL, NetworkModule.resolveBaseUrl(context)).distinct()

        for (host in hosts) {
            val request = Request.Builder()
                .url("${host}api/device/register")
                .addHeader("ngrok-skip-browser-warning", "true")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val token = com.google.gson.JsonParser.parseString(text)
                            .asJsonObject.get("token")?.asString?.takeIf { it.isNotBlank() }
                        if (token != null) {
                            AppSettings.setDeviceToken(context, token)
                            return token
                        }
                    }
                }
            } catch (e: Exception) {
                // Transport failure against this host — try the next one.
            }
        }
        return null
    }
}
