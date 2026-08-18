package com.healthdecoder.app

/**
 * Non-secret build-time config embedded in the app. Actual values come from BuildConfig,
 * generated at build time from android-app/local.properties (gitignored, per-machine — see
 * local.properties.example).
 *
 * Gemini/Sarvam API keys used to live here too; they've been removed — the app no longer
 * embeds any AI provider key, since all AI calls are proxied through the backend (see
 * ai/BackendAiClient.kt / POST /api/ai/generate, /api/ai/tts, /api/ai/translate).
 */
object BuildKeys {
    // Google OAuth "Web application" client ID — powers native Sign in with Google. Not a
    // secret: it identifies the app to Google's consent screen, it doesn't authorize anything
    // by itself.
    val GOOGLE_WEB_CLIENT_ID = BuildConfig.GOOGLE_WEB_CLIENT_ID
}
