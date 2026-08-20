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
            // Deliberately short. Establishing a TCP connection either works in a couple of
            // seconds or is not going to: a network that blackholes SYNs to the Function URL's
            // host used to burn the full 60s here, three times over, so a scan spent ~3 minutes
            // looking like it was "analyzing" before reporting a connection error. Fail fast and
            // let the host fallback below do the useful work instead.
            .connectTimeout(15, TimeUnit.SECONDS)
            // Longer than the Lambda's own 120s timeout (see template.yaml), so a slow-but-
            // legitimate Gemini response always finishes server-side before the client gives up.
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Hosts to try for an AI-proxy call, in order.
     *
     * The Function URL is first because it is the only one without API Gateway's hard,
     * non-configurable 30s integration cap, which a large multi-page extraction can exceed.
     * But it lives on a different domain (`*.lambda-url.*.on.aws`) from everything else the app
     * talks to (`*.execute-api.*.amazonaws.com`), and some networks — captive portals, ISP or
     * DNS filtering, restrictive corporate Wi-Fi — resolve or route one and not the other. When
     * that happened, scanning was the only broken feature in an app that otherwise worked
     * perfectly, and it blamed the user's internet for it.
     *
     * So: if the Function URL cannot be reached at all, fall back to API Gateway rather than
     * failing the scan. Single-page extractions finish in a few seconds, well inside the 30s
     * cap, so the fallback is a real scan rather than a slower failure.
     */
    private fun proxyHosts(context: Context): List<String> =
        listOf(NetworkModule.AI_PROXY_BASE_URL, NetworkModule.resolveBaseUrl(context)).distinct()

    class BackendAiException(message: String) : Exception(message)

    fun generateText(context: Context, prompt: String, operation: String = "scan"): String =
        generate(context, prompt, emptyList(), operation)

    fun generateFromImage(context: Context, prompt: String, imageBytes: ByteArray, mimeType: String, operation: String = "scan"): String =
        generate(context, prompt, listOf(imageBytes to mimeType), operation)

    fun generateFromImages(context: Context, prompt: String, images: List<Pair<ByteArray, String>>, operation: String = "scan"): String =
        generate(context, prompt, images, operation)

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

    private fun generate(context: Context, prompt: String, images: List<Pair<ByteArray, String>>, operation: String): String {
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
            addProperty("operation", operation)
        }

        val payload = body.toString()
        val hosts = proxyHosts(context)
        var lastNetworkError: IOException? = null

        // Try each host in turn. A host is only abandoned for a TRANSPORT failure (unreachable,
        // DNS, TLS, timeout) — once a host answers, its answer is authoritative and is either
        // returned or thrown, never retried against a different host. Re-sending a scan that the
        // backend already accepted would double-charge the user's daily quota.
        for (host in hosts) {
            val request = Request.Builder()
                .url("${host}api/ai/generate")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("ngrok-skip-browser-warning", "true")
                .post(payload.toRequestBody(JSON_MEDIA.toMediaType()))
                .build()

            // Retry transient backend/upstream failures (Lambda cold start, Gemini hiccup) with
            // backoff. NOT retried: 429 (our own daily quota — retrying won't help) and other 4xx
            // (a client-side problem with this exact request).
            val maxAttempts = 3
            var attempt = 0
            // 202 means the backend is already paying Gemini for this exact request elsewhere
            // (server-side single-flight de-dup) — most often because API Gateway's hard,
            // non-configurable 30s cap cut this same request off earlier while the Lambda kept
            // running past it. That's real progress, not a failure, so it gets its own much
            // longer, separate budget instead of eating into maxAttempts: ~85s of short polls,
            // just under the server's own 110s abandoned-leader cutoff, giving a slow-but-genuine
            // Gemini call time to finish and be reused for free rather than paid for twice.
            var processingPolls = 0
            val maxProcessingPolls = 25
            val processingPollDelayMs = 3500L

            try {
                while (true) {
                    var result: String? = null
                    var retryDelayMs = 0L
                    var giveUp: BackendAiException? = null

                    client.newCall(request).execute().use { response ->
                        if (response.code == 202) {
                            if (processingPolls < maxProcessingPolls) {
                                processingPolls++
                                retryDelayMs = processingPollDelayMs
                            } else {
                                giveUp = BackendAiException(
                                    "The analysis is taking longer than expected. It may still finish in the " +
                                        "background — check back in a minute before scanning again."
                                )
                            }
                            return@use
                        }

                        val respText = response.body?.string().orEmpty()
                        if (response.isSuccessful) {
                            result = extractText(respText)
                            return@use
                        }

                        val serverMessage = errorMessage(respText)
                        when {
                            response.code == 429 -> giveUp = BackendAiException(serverMessage ?: "Daily free analysis limit reached.")
                            response.code == 413 -> giveUp = BackendAiException(
                                serverMessage ?: "This scan is too large to send in one request. Try fewer pages at a time."
                            )
                            response.code in intArrayOf(502, 503, 504) && attempt < maxAttempts - 1 -> {
                                attempt++
                                retryDelayMs = attempt * 2000L
                            }
                            response.code in intArrayOf(502, 503, 504) -> giveUp = BackendAiException(
                                serverMessage ?: "The analysis server is temporarily unavailable. Please try again shortly."
                            )
                            else -> giveUp = BackendAiException(serverMessage ?: "Request failed (${response.code}).")
                        }
                    }

                    result?.let { return it }
                    giveUp?.let { throw it }
                    try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) { }
                }
            } catch (e: IOException) {
                // Do NOT retry the same host on a transport error — move on immediately.
                // Retrying buys nothing here (a host that can't be connected to won't start
                // working two seconds later) and costs a great deal: OkHttp 4.x has no Happy
                // Eyeballs, so it walks every resolved address in turn, each for the full
                // connectTimeout. The Function URL resolves to 14 addresses, 6 of them IPv6 —
                // on a network advertising IPv6 without a working route, one attempt already
                // burns minutes, and three of them turned a scan into a ~10-minute wait that
                // ended in "check your connection". Falling through to the next host is both
                // faster and far more likely to actually succeed.
                lastNetworkError = e
            }
            // This host failed at the transport layer — try the next.
        }

        // Name the hosts that were actually tried. "Check your internet connection" sent people
        // hunting a fault in their Wi-Fi when the rest of the app was working fine and only this
        // one domain was blocked; the host list is what makes that distinguishable in a bug report.
        throw BackendAiException(
            "Couldn't reach the analysis server (tried ${hosts.size} address(es): " +
                hosts.joinToString { it.substringAfter("://").substringBefore("/") } +
                "). Other parts of the app may still work — this can be a network that blocks " +
                "one of these domains."
        ).also { if (lastNetworkError != null) it.initCause(lastNetworkError) }
    }

    // Our own handlers return {"error": ...}, but AWS's own rejections don't go through them and
    // use {"Message": ...} / {"message": ...} — the 6MB Lambda payload cap is one such, and its
    // very specific reason ("Request must be smaller than 6291456 bytes") was being dropped on
    // the floor and reported as a bare "Request failed (413)".
    private fun errorMessage(json: String): String? = try {
        val obj = JsonParser.parseString(json).asJsonObject
        (obj.get("error") ?: obj.get("Message") ?: obj.get("message"))?.asString
    } catch (e: Exception) { null }

    private fun extractText(json: String): String = try {
        JsonParser.parseString(json).asJsonObject.get("text")?.asString.orEmpty()
    } catch (e: Exception) {
        throw BackendAiException("Could not parse the analysis response.")
    }
}
