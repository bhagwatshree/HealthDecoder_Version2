package com.healthdecoder.app.ai

import android.content.Context

/**
 * Text-to-speech: delegates to the backend's /api/ai/tts proxy (see BackendAiClient) instead
 * of calling Sarvam/Gemini directly — no API key ships on the device. The "Phone" engine is
 * handled by the UI with Android's TextToSpeech and never reaches here.
 */
object SpeechEngine {

    fun synthesize(context: Context, text: String, language: String, engine: String): List<String> =
        BackendAiClient.tts(context, text, language, engine)
}
