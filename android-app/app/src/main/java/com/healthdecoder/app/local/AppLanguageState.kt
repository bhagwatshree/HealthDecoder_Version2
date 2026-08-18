package com.healthdecoder.app.local

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-wide current language as Compose state, so switching it from anywhere (the top-bar
 * language picker) recomposes every screen that reads it immediately — no activity restart.
 * Backed by AppSettings, which seeds the very first value from the device's keyboard.
 */
object AppLanguageState {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val state = mutableStateOf<String?>(null)

    val current: String
        get() = state.value ?: "English"

    fun ensureInit(context: Context) {
        if (state.value == null) {
            state.value = AppSettings.getPreferredLanguage(context)
        }
    }

    fun select(context: Context, language: String) {
        AppSettings.setPreferredLanguage(context, language)
        state.value = language
        // Pull the latest translations for this language in case they were edited in the DB
        // since the last full fetch — cheap, single-language call, not a full re-fetch.
        scope.launch { RemoteUiTranslations.refreshLanguage(context, language) }
    }
}
