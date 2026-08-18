package com.healthdecoder.app.local

import android.content.Context
import com.healthdecoder.app.network.NetworkModule
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * On-device cache of UI-chrome translations fetched from the backend's ui_translations
 * table — the DB is the source of truth, so editing a row there reaches every install
 * without a build. Fetched in full once, on the first-ever launch that has network; then
 * re-fetched one language at a time whenever the user explicitly picks it from the
 * language picker, so a later DB edit shows up without waiting for a fresh install.
 * Falls back to the bundled UiTranslations.kt seed (see ui/Localization.kt) until the
 * first successful fetch, and to English if a string isn't translated anywhere yet.
 */
object RemoteUiTranslations {
    private const val PREFS = "remote_translations_prefs"
    private const val KEY_FETCHED_ONCE = "fetched_all_once"
    private val gson = Gson()
    private val mapType = TypeToken.getParameterized(Map::class.java, String::class.java, String::class.java).type

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // In-memory mirror of the persisted JSON blobs — tr() calls this on every recomposition,
    // so avoid re-parsing JSON from SharedPreferences each time.
    private val memoryCache = mutableMapOf<String, Map<String, String>>()

    private fun loadLanguageMap(context: Context, language: String): Map<String, String> {
        memoryCache[language]?.let { return it }
        val raw = prefs(context).getString(language, null) ?: return emptyMap()
        val parsed = runCatching { gson.fromJson<Map<String, String>>(raw, mapType) }.getOrDefault(emptyMap())
        memoryCache[language] = parsed
        return parsed
    }

    private fun saveLanguageMap(context: Context, language: String, map: Map<String, String>) {
        memoryCache[language] = map
        prefs(context).edit().putString(language, gson.toJson(map)).apply()
    }

    fun get(context: Context, language: String, text: String): String? =
        loadLanguageMap(context, language)[text]

    /** Fetches every language once ever; retries on a later launch if it never succeeded (e.g. first launch was offline). */
    suspend fun fetchAllIfNeverFetched(context: Context) {
        if (prefs(context).getBoolean(KEY_FETCHED_ONCE, false)) return
        runCatching { NetworkModule.getApi(context).getAllTranslations() }
            .onSuccess { all ->
                all.forEach { (language, map) -> saveLanguageMap(context, language, map) }
                prefs(context).edit().putBoolean(KEY_FETCHED_ONCE, true).apply()
            }
    }

    /** Re-fetches just one language — called when the user selects it, so DB edits show up without a full re-fetch. */
    suspend fun refreshLanguage(context: Context, language: String) {
        if (language.equals("English", ignoreCase = true)) return
        runCatching { NetworkModule.getApi(context).getLanguageTranslations(language) }
            .onSuccess { map -> saveLanguageMap(context, language, map) }
    }
}
