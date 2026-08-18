package com.healthdecoder.app.network

import android.content.Context
import com.healthdecoder.app.model.KeyAssignment

/**
 * Reads the backend's per-user plan/usage snapshot (GET /api/auth/keys). The backend no longer
 * hands the actual Gemini/Sarvam key to the client — every AI call is proxied server-side (see
 * BackendAiClient) — so this is now a plain fetch, kept around because callers still use the
 * returned plan/usage/quota fields to drive the Account screen and quota-exceeded messaging.
 */
object AccountSync {

    /** Fetches the current plan/usage snapshot, or null if the request failed (e.g. offline).
     *  Does not persist anything locally — there's no key left to store on the device. */
    suspend fun refreshAssignedKeys(context: Context): KeyAssignment? =
        runCatching { NetworkModule.getApi(context).getAssignedKeys() }.getOrNull()

    /** Read-only usage/quota snapshot for display (e.g. the Account screen) — unlike
     *  refreshAssignedKeys, this never consumes a free-tier issuance, so it's safe to call
     *  every time the screen opens. Doesn't touch the locally cached active key. */
    suspend fun peekUsage(context: Context): KeyAssignment? =
        runCatching { NetworkModule.getApi(context).getUsage() }.getOrNull()
}
