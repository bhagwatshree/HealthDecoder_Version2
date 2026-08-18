package com.healthdecoder.app.ai

import android.content.Context
import android.util.Base64
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.DeviceIdentity
import com.healthdecoder.app.network.NetworkModule
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Calls Gemini through our own backend (POST /api/ai/generate) instead of Google directly —
 * the API key never touches the device; the backend resolves a pooled or BYOK key per call.
 * Authenticates with the logged-in user's session if any, else the anonymous device token
 * (see DeviceIdentity) — no OTP/login required either way.
 *
 * Same call shape as the (now scan-path-only) GeminiClient it replaces, so callers don't need
 * to change beyond swapping which object they call. On any failure — including the backend
 * being unreachable — this throws rather than falling back to any key stored on the device.
 */
object BackendAiClient {

    private const val JSON_MEDIA = "application/json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            // Longer than the Lambda's own 120s timeout (see template.yaml), so a slow-but-
            // legitimate Gemini response always finishes server-side before the client gives up.
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    class BackendAiException(message: String) : Exception(message)

    fun generateText(context: Context, prompt: String): String =
        generate(context, prompt, emptyList())

    fun generateFromImage(context: Context, prompt: String, imageBytes: ByteArray, mimeType: String): String =
        generate(context, prompt, listOf(imageBytes to mimeType))

    fun generateFromImages(context: Context, prompt: String, images: List<Pair<ByteArray, String>>): String =
        generate(context, prompt, images)

    /**
     * Text-to-speech via POST /api/ai/tts — returns base64-encoded audio clips (WAV), or an
     * empty list on ANY failure (missing auth, network error, backend miss). TTS is a nice-to-
     * have, never worth crashing or blocking the UI over, so this deliberately never throws.
     */
    fun tts(context: Context, text: String, language: String, engine: String): List<String> = try {
        val token = authToken(context)
        val body = JsonObject().apply {
            addProperty("text", text)
            addProperty("language", language)
            addProperty("engine", engine)
        }
        val request = Request.Builder()
            .url("${NetworkModule.AI_PROXY_BASE_URL}api/ai/tts")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            root.getAsJsonArray("audios")?.map { it.asString } ?: emptyList()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    /**
     * Translation via POST /api/ai/translate — returns the translated text, or the ORIGINAL
     * `text` on ANY failure, so a translation miss never blocks the caller.
     */
    fun translate(context: Context, text: String, targetLanguage: String): String = try {
        val token = authToken(context)
        val body = JsonObject().apply {
            addProperty("text", text)
            addProperty("targetLanguage", targetLanguage)
        }
        val request = Request.Builder()
            .url("${NetworkModule.AI_PROXY_BASE_URL}api/ai/translate")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return text
            val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            root.get("translated_text")?.asString ?: text
        }
    } catch (e: Exception) {
        e.printStackTrace()
        text
    }

    private fun authToken(context: Context): String =
        AppSettings.getAuthToken(context)
            ?: AppSettings.getDeviceToken(context)
            ?: DeviceIdentity.ensureToken(context)
            ?: throw BackendAiException("Can't reach the analysis server. Check your connection and try again.")

    private fun generate(context: Context, prompt: String, images: List<Pair<ByteArray, String>>): String {
        val token = authToken(context)

        val imagesJson = JsonArray().apply {
            for ((bytes, mime) in images) {
                add(JsonObject().apply {
                    addProperty("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    addProperty("mimeType", mime)
                })
            }
        }
        val body = JsonObject().apply {
            addProperty("prompt", prompt)
            add("images", imagesJson)
            addProperty("operation", "scan")
        }

        val url = "${NetworkModule.AI_PROXY_BASE_URL}api/ai/generate"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
            .build()

        // Retry transient backend/upstream failures (Lambda cold start, Gemini hiccup) with
        // backoff. NOT retried: 429 (our own daily quota — retrying won't help) and other 4xx
        // (a client-side problem with this exact request).
        val maxAttempts = 3
        var lastNetworkError: IOException? = null
        for (attempt in 0 until maxAttempts) {
            try {
                client.newCall(request).execute().use { response ->
                    val respText = response.body?.string().orEmpty()
                    if (response.isSuccessful) return extractText(respText)

                    val serverMessage = errorMessage(respText)
                    when {
                        response.code == 429 -> throw BackendAiException(serverMessage ?: "Daily free analysis limit reached.")
                        response.code in intArrayOf(502, 503, 504) && attempt < maxAttempts - 1 -> {
                            try { Thread.sleep((attempt + 1) * 2000L) } catch (_: InterruptedException) { }
                        }
                        response.code in intArrayOf(502, 503, 504) -> throw BackendAiException(
                            serverMessage ?: "The analysis server is temporarily unavailable. Please try again shortly."
                        )
                        else -> throw BackendAiException(serverMessage ?: "Request failed (${response.code}).")
                    }
                }
            } catch (e: IOException) {
                lastNetworkError = e
                if (attempt < maxAttempts - 1) {
                    try { Thread.sleep((attempt + 1) * 2000L) } catch (_: InterruptedException) { }
                }
            }
        }
        throw BackendAiException("Can't reach the analysis server. Check your connection and try again.")
            .also { if (lastNetworkError != null) it.initCause(lastNetworkError) }
    }

    private fun errorMessage(json: String): String? = try {
        JsonParser.parseString(json).asJsonObject.get("error")?.asString
    } catch (e: Exception) { null }

    private fun extractText(json: String): String = try {
        JsonParser.parseString(json).asJsonObject.get("text")?.asString.orEmpty()
    } catch (e: Exception) {
        throw BackendAiException("Could not parse the analysis response.")
    }
}
