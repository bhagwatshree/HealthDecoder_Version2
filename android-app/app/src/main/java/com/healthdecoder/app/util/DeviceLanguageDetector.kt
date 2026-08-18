package com.healthdecoder.app.util

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodManager
import com.healthdecoder.app.local.AppSettings
import java.util.Locale

/**
 * Picks the app's initial language from the device's currently active keyboard, not the
 * system display language — a user's keyboard is a better signal of the language they're
 * actually comfortable reading (e.g. English system UI with a Hindi keyboard installed).
 * Falls back to English when no keyboard subtype is available or it isn't one of
 * AppSettings.SUPPORTED_LANGUAGES.
 */
object DeviceLanguageDetector {
    private val isoToSupportedName = mapOf(
        "en" to "English",
        "hi" to "Hindi",
        "mr" to "Marathi",
        "gu" to "Gujarati",
        "ta" to "Tamil",
        "te" to "Telugu",
        "kn" to "Kannada",
        "bn" to "Bengali",
        "pa" to "Punjabi",
        "ml" to "Malayalam",
        "or" to "Odia"
    )

    fun detectFromKeyboard(context: Context): String {
        val iso = runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val subtype = imm?.currentInputMethodSubtype
            when {
                subtype == null -> null
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && subtype.languageTag.isNotBlank() ->
                    Locale.forLanguageTag(subtype.languageTag).language
                subtype.locale.isNotBlank() ->
                    Locale(subtype.locale.substringBefore("_")).language
                else -> null
            }
        }.getOrNull()

        return iso?.let { isoToSupportedName[it] } ?: "English"
    }
}
