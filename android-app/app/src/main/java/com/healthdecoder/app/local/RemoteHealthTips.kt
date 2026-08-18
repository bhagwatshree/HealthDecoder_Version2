package com.healthdecoder.app.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.healthdecoder.app.ai.HealthTip
import com.healthdecoder.app.network.NetworkModule

data class RemoteHealthTip(
    @SerializedName("canonical_param") val canonicalParam: String,
    @SerializedName("status") val status: String,
    @SerializedName("headline") val headline: String,
    @SerializedName("detail") val detail: String
)

/**
 * On-device cache of personalized health tips fetched from the backend's health_tips table —
 * same pattern as [RemoteUiTranslations]: the DB is the source of truth, so a new tip added
 * there reaches every install without an app release. Fetched once per install (retried on a
 * later launch if it never succeeded); [com.healthdecoder.app.ai.PersonalizedTips] checks this
 * cache first and falls back to its bundled seed for any test not yet covered here.
 */
object RemoteHealthTips {
    private const val PREFS = "remote_health_tips_prefs"
    private const val KEY_FETCHED_ONCE = "fetched_once"
    private const val KEY_TIPS_JSON = "tips_json"
    private val gson = Gson()
    private val listType = TypeToken.getParameterized(List::class.java, RemoteHealthTip::class.java).type

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // key = "canonicalParam|status" (status lowercased) -> tip. Parsed once per process.
    private var memoryCache: Map<String, HealthTip>? = null

    private fun loadCache(context: Context): Map<String, HealthTip> {
        memoryCache?.let { return it }
        val raw = prefs(context).getString(KEY_TIPS_JSON, null)
        val parsed = raw?.let { runCatching { gson.fromJson<List<RemoteHealthTip>>(it, listType) }.getOrNull() }
            ?.associate { "${it.canonicalParam}|${it.status.lowercase()}" to HealthTip(it.headline, it.detail) }
            ?: emptyMap()
        memoryCache = parsed
        return parsed
    }

    /** The remote tip for this canonical test + status ("low"/"high"), or null if not cached
     *  yet / not covered — caller falls back to its own bundled seed in that case. */
    fun get(context: Context, canonicalParam: String, status: String): HealthTip? =
        loadCache(context)["$canonicalParam|${status.lowercase()}"]

    /** Fetches every tip once ever; retries on a later launch if it never succeeded (e.g. first launch was offline). */
    suspend fun fetchIfNeverFetched(context: Context) {
        if (prefs(context).getBoolean(KEY_FETCHED_ONCE, false)) return
        runCatching { NetworkModule.getApi(context).getHealthTips() }
            .onSuccess { tips ->
                memoryCache = tips.associate { "${it.canonicalParam}|${it.status.lowercase()}" to HealthTip(it.headline, it.detail) }
                prefs(context).edit()
                    .putString(KEY_TIPS_JSON, gson.toJson(tips))
                    .putBoolean(KEY_FETCHED_ONCE, true)
                    .apply()
            }
    }
}
