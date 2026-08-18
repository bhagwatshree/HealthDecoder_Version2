package com.healthdecoder.app.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.healthdecoder.app.util.LanguageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runtime translation cache for text that is NOT app chrome — content that can change without
 * an app release, so it can never be covered by the hand-maintained maps in UiTranslations.kt.
 *
 * The clearest example is health tips: the bundled lists are only an offline seed, and the real
 * source is the backend's `health_tips` table (see [RemoteHealthTips]). A tip added there
 * tomorrow has no map entry and never will, so a static-map-only approach leaves every new tip
 * in English forever. Same for anything derived from a scanned report (canonical test names,
 * doctor/lab text).
 *
 * So: chrome goes in UiTranslations.kt (free, offline, exact), content comes through here
 * (translated once via the backend's Sarvam proxy, then cached on-device forever). The split is
 * deliberate — do not add report- or DB-driven strings to the static maps.
 *
 * Every failure mode degrades to English rather than blocking a screen: no network, no auth, a
 * backend error, or a first render before the fetch finishes all just show the source text.
 */
object DynamicTranslations {
    private const val PREFS = "dynamic_translations_prefs"
    private const val MAX_ENTRIES_PER_LANGUAGE = 500
    private val gson = Gson()
    private val mapType = TypeToken.getParameterized(Map::class.java, String::class.java, String::class.java).type

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // language -> (source text -> translation). Insertion-ordered so the oldest entries are the
    // ones dropped once a language passes the cap.
    private val memoryCache = mutableMapOf<String, LinkedHashMap<String, String>>()

    @Synchronized
    private fun loadLanguageMap(context: Context, language: String): LinkedHashMap<String, String> {
        memoryCache[language]?.let { return it }
        val raw = prefs(context).getString(language, null)
        val parsed = raw
            ?.let { runCatching { gson.fromJson<Map<String, String>>(it, mapType) }.getOrNull() }
            ?: emptyMap()
        val ordered = LinkedHashMap<String, String>(parsed)
        memoryCache[language] = ordered
        return ordered
    }

    @Synchronized
    private fun store(context: Context, language: String, text: String, translated: String) {
        val map = loadLanguageMap(context, language)
        map.remove(text)
        map[text] = translated
        while (map.size > MAX_ENTRIES_PER_LANGUAGE) {
            map.remove(map.keys.first())
        }
        prefs(context).edit().putString(language, gson.toJson(map)).apply()
    }

    /** The already-translated text, or null if this string hasn't been fetched for this language yet. */
    fun cached(context: Context, language: String, text: String): String? =
        if (language.equals("English", ignoreCase = true)) text
        else loadLanguageMap(context, language)[text]

    /**
     * Cache hit, or one network round-trip through the backend's translate proxy. Returns the
     * original English text on any failure, and does not cache it in that case, so a translation
     * missed while offline is retried on a later render rather than being cached as English.
     */
    suspend fun translate(context: Context, language: String, text: String): String {
        if (language.equals("English", ignoreCase = true) || text.isBlank()) return text
        cached(context, language, text)?.let { return it }

        val translated = withContext(Dispatchers.IO) {
            runCatching { LanguageUtil.translate(context, text, language) }.getOrDefault(text)
        }
        if (translated.isNotBlank() && translated != text) {
            store(context, language, text, translated)
        }
        return translated
    }
}
