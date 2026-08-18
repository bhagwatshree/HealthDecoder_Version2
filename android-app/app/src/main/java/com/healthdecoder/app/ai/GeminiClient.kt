package com.healthdecoder.app.ai

/**
 * Small text-formatting helpers shared by callers that parse Gemini's JSON responses.
 *
 * The actual Gemini calls used to happen directly from the phone here (with an embedded API
 * key); that path is dead code and has been removed — all AI calls now go through the backend
 * proxy (see BackendAiClient / POST /api/ai/generate), so no key ships in the APK.
 */
object GeminiClient {

    /** Strips ```json fences some models add around JSON output. */
    fun stripJsonFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        }
        return t
    }
}
