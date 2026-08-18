package com.healthdecoder.app.local

import android.content.Context
import com.healthdecoder.app.model.FamilyProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Local app preferences (SharedPreferences-backed). No AI provider API keys are stored here
 * anymore — Gemini/Sarvam calls are proxied through the backend (see ai/BackendAiClient.kt),
 * so the phone never holds a raw key at all, "bring your own key" included (that's saved
 * server-side only, see ProfileScreen's Save Key handler).
 */
object AppSettings {
    private const val PREFS = "medical_scanner_prefs"
    private const val KEY_LANGUAGE = "preferred_language"
    private const val KEY_VOICE_ENGINE = "voice_engine"
    private const val KEY_REMINDER_STYLE = "reminder_style"
    private const val KEY_DISCLAIMER_ACCEPTED = "medical_disclaimer_accepted"
    private const val KEY_TREND_STANDARD_UNITS = "trend_standard_units"

    private val gson = Gson()

    // ── Family members / patients ────────────────────────────────────────────
    // A persisted, user-managed list of the people this account tracks. A member's `name` is the
    // join key to reports (report.patientName), so renaming a member cascades to their records via
    // LocalRepository.mergePatient. The "active" member scopes the home dashboard and list screens.
    private const val KEY_FAMILY = "family_profiles"
    private const val KEY_ACTIVE_PATIENT = "active_patient"

    fun getFamilyProfilesRaw(context: Context): List<FamilyProfile> {
        val json = prefs(context).getString(KEY_FAMILY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<FamilyProfile>>(json, object : TypeToken<List<FamilyProfile>>() {}.type)
        }.getOrNull() ?: emptyList()
    }

    fun setFamilyProfiles(context: Context, list: List<FamilyProfile>) {
        prefs(context).edit().putString(KEY_FAMILY, gson.toJson(list)).apply()
    }

    /** The family member the app is currently scoped to (null = show everyone). */
    fun getActivePatient(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_PATIENT, null)?.takeIf { it.isNotBlank() }

    fun setActivePatient(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_PATIENT, name?.trim().orEmpty()).apply()
    }

    /**
     * The unit each test is standardized to on the trend chart — the first non-blank unit ever
     * seen for that test, LOCKED so later readings in a different unit get converted to it (and
     * so the standard survives deleting or period-filtering the report it came from). Keyed by
     * "<patientName>|<trendCategory>". Trend charting only; the report screen is unaffected.
     */
    fun getTrendStandardUnits(context: Context): Map<String, String> {
        val json = prefs(context).getString(KEY_TREND_STANDARD_UNITS, null) ?: return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
        }.getOrNull() ?: emptyMap()
    }

    /** Moves a patient's locked trend units to a merged name. On a key clash the target's existing
     *  unit wins (its own history is preserved); otherwise the source's unit carries over. */
    fun migrateTrendUnitsPatient(context: Context, fromPatient: String, toPatient: String) {
        if (fromPatient.equals(toPatient, ignoreCase = true)) return
        val current = getTrendStandardUnits(context)
        if (current.isEmpty()) return
        val updated = LinkedHashMap<String, String>(current)
        val fromPrefix = "$fromPatient|"
        for ((key, unit) in current) {
            if (!key.startsWith(fromPrefix)) continue
            val newKey = "$toPatient|" + key.removePrefix(fromPrefix)
            if (!updated.containsKey(newKey)) updated[newKey] = unit
            updated.remove(key)
        }
        prefs(context).edit().putString(KEY_TREND_STANDARD_UNITS, gson.toJson(updated)).apply()
    }

    /** Records [unit] as the standard for [key] ("<patient>|<category>") only if none is set yet. */
    fun lockTrendStandardUnitIfAbsent(context: Context, key: String, unit: String) {
        if (unit.isBlank()) return
        val current = getTrendStandardUnits(context)
        if (current.containsKey(key)) return
        val updated = current + (key to unit)
        prefs(context).edit().putString(KEY_TREND_STANDARD_UNITS, gson.toJson(updated)).apply()
    }

    fun isDisclaimerAccepted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISCLAIMER_ACCEPTED, false)

    fun setDisclaimerAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, accepted).apply()
    }

    /** First-launch onboarding carousel (see OnboardingScreen / Navigation.kt), shown once right
     *  after the medical disclaimer is accepted, and never again. */
    private const val KEY_ONBOARDING_SEEN = "onboarding_seen"

    fun isOnboardingSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_SEEN, false)

    fun setOnboardingSeen(context: Context, seen: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_SEEN, seen).apply()
    }

    /** One-time tooltip on first Home visit pointing at the account icon, for a user who hasn't
     *  signed in yet (sign-in is optional, so there's otherwise no prominent affordance for it).
     *  Marked seen the moment it's shown — see HomeScreen — so it never reappears, even if the
     *  user backgrounds the app without dismissing it. */
    /** Unit system trend charts standardise every reading to. Conventional (Indian/US: mg/dL,
     *  g/dL, ng/mL …) is the default since that's what Indian labs mostly print; SI (mmol/L,
     *  µmol/L, g/L …) is the international/research convention. This single value is the ONLY
     *  thing stored — the actual per-test unit comes from UnitConverter's table, keyed off it. */
    const val UNIT_SYSTEM_CONVENTIONAL = "conventional"
    const val UNIT_SYSTEM_SI = "si"
    private const val KEY_UNIT_SYSTEM = "trend_unit_system"

    fun getUnitSystem(context: Context): String =
        prefs(context).getString(KEY_UNIT_SYSTEM, UNIT_SYSTEM_CONVENTIONAL) ?: UNIT_SYSTEM_CONVENTIONAL

    fun setUnitSystem(context: Context, system: String) {
        prefs(context).edit().putString(KEY_UNIT_SYSTEM, system).apply()
    }

    /** Resolves the stored preference to the enum the converter uses. */
    fun getUnitSystemEnum(context: Context): com.healthdecoder.app.ai.UnitConverter.System =
        if (getUnitSystem(context) == UNIT_SYSTEM_SI)
            com.healthdecoder.app.ai.UnitConverter.System.SI
        else
            com.healthdecoder.app.ai.UnitConverter.System.CONVENTIONAL

    // ── Portable export delta marker ────────────────────────────────────────
    // createdAt of the newest report included in the last portable export, so "export new since
    // last time" can ship only what changed. Stored per account so switching users doesn't leak
    // one user's cutoff onto another's export.
    private const val KEY_LAST_EXPORT_AT = "last_export_at"

    fun getLastExportAt(context: Context): String? =
        prefs(context).getString(KEY_LAST_EXPORT_AT + "_" + (getUserEmail(context) ?: ""), null)

    fun setLastExportAt(context: Context, isoTimestamp: String) {
        prefs(context).edit().putString(KEY_LAST_EXPORT_AT + "_" + (getUserEmail(context) ?: ""), isoTimestamp).apply()
    }

    /** Medicine reminder styles: a standard notification, or a full-screen alarm page
     *  with very large text so elderly users can read the medicine names clearly. */
    const val REMINDER_STYLE_NORMAL = "normal"
    const val REMINDER_STYLE_FULLSCREEN = "fullscreen"

    fun getReminderStyle(context: Context): String =
        prefs(context).getString(KEY_REMINDER_STYLE, REMINDER_STYLE_NORMAL) ?: REMINDER_STYLE_NORMAL

    fun setReminderStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_REMINDER_STYLE, style).apply()
    }

    // ── Scan pipeline tuning (internal, no UI) ──────────────────────────────
    // Large multi-document scans are sent to the AI in chunks of this many pages per
    // request; one giant request exceeds free-tier request/response limits and fails.
    // Raise via setScanChunkPages() if a paid API tier with bigger limits is used.
    //
    // MUST stay comfortably above how many pages a single cohesive multi-panel lab report
    // can run (Indian diagnostic labs commonly bundle CBC + PT/INR + urine + biochemistry +
    // electrolytes into ONE 7-10 page PDF for one blood draw) — a report that lands exactly
    // on the chunk boundary gets split into two AI requests, and the isolated tail chunk
    // (e.g. just the last page) can silently fail to parse and vanish with no error shown,
    // dropping that page's data (a real incident: a 7-page report's electrolytes panel,
    // alone in page 7 of a 6-page chunk split, never made it into the saved report at all).
    private const val KEY_SCAN_CHUNK_PAGES = "scan_chunk_pages"
    private const val KEY_SCAN_MAX_PAGES = "scan_max_pages"

    fun getScanChunkPages(context: Context): Int =
        prefs(context).getInt(KEY_SCAN_CHUNK_PAGES, 12).coerceIn(1, 30)

    fun setScanChunkPages(context: Context, pages: Int) {
        prefs(context).edit().putInt(KEY_SCAN_CHUNK_PAGES, pages).apply()
    }

    /** Total page cap per scan session (memory guard). */
    fun getScanMaxPages(context: Context): Int =
        prefs(context).getInt(KEY_SCAN_MAX_PAGES, 60).coerceIn(5, 200)

    fun setScanMaxPages(context: Context, pages: Int) {
        prefs(context).edit().putInt(KEY_SCAN_MAX_PAGES, pages).apply()
    }

    // Scan bundles [LocalRepository.recoverMissingPanels] has already fully checked (not
    // necessarily found anything in, just checked) — without this, the same at-risk bundle
    // (flagged purely by its ORIGINAL page count, which never changes) would be re-flagged and
    // re-processed forever, even after it's already been recovered or confirmed complete.
    private const val KEY_CHECKED_RECOVERY_BUNDLES = "checked_recovery_bundles"

    fun getCheckedRecoveryBundles(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_CHECKED_RECOVERY_BUNDLES, emptySet()) ?: emptySet()

    fun markRecoveryBundleChecked(context: Context, bundleKey: String) {
        val current = getCheckedRecoveryBundles(context)
        prefs(context).edit().putStringSet(KEY_CHECKED_RECOVERY_BUNDLES, current + bundleKey).apply()
    }

    // Minimum spacing between Gemini requests. The free tier allows ~20 requests/minute;
    // without pacing a bulk scan bursts past it and every call 429s. Set to 0 when a
    // paid API tier is used.
    private const val KEY_AI_MIN_INTERVAL_MS = "ai_min_request_interval_ms"

    fun getAiMinRequestIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_AI_MIN_INTERVAL_MS, 3200L).coerceIn(0L, 30_000L)

    fun setAiMinRequestIntervalMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_AI_MIN_INTERVAL_MS, ms).apply()
    }

    /** Text-to-speech engines the user can pick. */
    val VOICE_ENGINES = listOf("Sarvam", "Gemini", "Phone")

    fun getVoiceEngine(context: Context): String =
        prefs(context).getString(KEY_VOICE_ENGINE, "Sarvam") ?: "Sarvam"

    fun setVoiceEngine(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_VOICE_ENGINE, engine).apply()
    }

    /** Languages offered for medicine explanations (must match backend LANGUAGE_CODES). */
    val SUPPORTED_LANGUAGES = listOf(
        "English", "Hindi", "Marathi", "Gujarati", "Tamil",
        "Telugu", "Kannada", "Bengali", "Punjabi", "Malayalam", "Odia"
    )

    // On first read ever (key never set), seed from the device's active keyboard language
    // instead of hardcoding English, then persist it so this only runs once.
    fun getPreferredLanguage(context: Context): String {
        val p = prefs(context)
        if (!p.contains(KEY_LANGUAGE)) {
            val detected = com.healthdecoder.app.util.DeviceLanguageDetector.detectFromKeyboard(context)
            p.edit().putString(KEY_LANGUAGE, detected).apply()
            return detected
        }
        return p.getString(KEY_LANGUAGE, "English") ?: "English"
    }

    fun setPreferredLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Account / login ──────────────────────────────────────────────────────
    // All AI calls (scan/insights/chat/tts/translate) go through the backend's /api/ai/*
    // proxy — it resolves a pooled or BYOK Gemini/Sarvam key server-side per call, and the
    // key never reaches the device. See ai/BackendAiClient.kt and network/AccountSync.kt.
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_USER_EMAIL = "auth_user_email"

    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_BIOMETRIC_TOKEN = "biometric_token"
    private const val KEY_BIOMETRIC_USER_EMAIL = "biometric_user_email"

    fun getAuthToken(context: Context): String? =
        prefs(context).getString(KEY_AUTH_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setAuthToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getUserEmail(context: Context): String? = prefs(context).getString(KEY_USER_EMAIL, null)

    fun setUserEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_USER_EMAIL, email).apply()
    }

    fun isLoggedIn(context: Context): Boolean = getAuthToken(context) != null

    // ── Anonymous device identity (no OTP/login) ────────────────────────────
    // Backs the AI proxy (BackendAiClient / POST /api/ai/generate): phone OTP sign-in is
    // optional/off by default, so most installs authenticate as this anonymous device instead
    // of a real user. installId is generated once and never changes; deviceToken is the JWT
    // DeviceIdentity gets back from POST /api/device/register using that id.
    private const val KEY_INSTALL_ID = "device_install_id"
    private const val KEY_DEVICE_TOKEN = "device_auth_token"

    fun getOrCreateInstallId(context: Context): String {
        prefs(context).getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_INSTALL_ID, fresh).apply()
        return fresh
    }

    fun getDeviceToken(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setDeviceToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    /** Logs out. There's no local key state to clear anymore — AI calls always go through
     *  the backend proxy, which falls back to the anonymous device pool once logged out.
     *
     *  Does clear the active patient selection: it's shown right next to the account row on
     *  Home, so leaving a specific family member's name selected after sign-out reads as if
     *  it's still tied to the account that just logged out — and on a shared device, the next
     *  person to sign in would otherwise land straight on a stranger's selected patient. */
    fun logout(context: Context) {
        prefs(context).edit().remove(KEY_AUTH_TOKEN).remove(KEY_USER_EMAIL).apply()
        setActivePatient(context, null)
    }

    /**
     * Wipes every identifying value this class stores. Used by "Delete Account" (see
     * ProfileScreen -> LocalRepository.clearAllLocalData), where the user is promised total
     * removal — so it is not enough to drop the medical records: anything naming a person also
     * has to go. Easy things to miss, all cleared here: the linked mailbox address and IMAP
     * server config, the biometric quick-login slot (which otherwise keeps a session token and
     * email for an account that no longer exists), the export watermark keyed by email, and the
     * per-test trend units map whose KEYS are patient names.
     *
     * Deliberately KEPT, because none of it identifies anyone: the anonymous device install id
     * (the free-tier quota counter is tied to it, so clearing it would hand out a fresh quota),
     * and plain UI preferences — theme, language, unit system, disclaimer acceptance, scan tuning.
     *
     * Secrets held OUTSIDE this class — the Gmail OAuth token and the IMAP password — live in
     * SecureKeyManager and are cleared by the caller.
     */
    fun clearAllPersonalData(context: Context) {
        // Read before the batched edit is applied, since the marker's key embeds the email.
        val exportMarkerKey = KEY_LAST_EXPORT_AT + "_" + (getUserEmail(context) ?: "")
        prefs(context).edit()
            .remove(KEY_AUTH_TOKEN).remove(KEY_USER_EMAIL)
            .remove(KEY_BIOMETRIC_ENABLED).remove(KEY_BIOMETRIC_TOKEN).remove(KEY_BIOMETRIC_USER_EMAIL)
            .remove(KEY_FAMILY).remove(KEY_ACTIVE_PATIENT)
            .remove(KEY_TREND_STANDARD_UNITS)
            .remove(exportMarkerKey)
            .remove(KEY_EMAIL_CONSENT).remove(KEY_LINKED_EMAIL).remove(KEY_LINKED_EMAIL_TYPE)
            .remove(KEY_IMAP_HOST).remove(KEY_IMAP_PORT).remove(KEY_EMAIL_SEARCH_PROMPT)
            .remove(KEY_EMAIL_SCAN_HOUR).remove(KEY_EMAIL_SCAN_MINUTE)
            .remove(KEY_RESEARCH_DATA_CONSENT)
            .apply()
    }

    fun isBiometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun getBiometricToken(context: Context): String? =
        prefs(context).getString(KEY_BIOMETRIC_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setBiometricToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_BIOMETRIC_TOKEN, token).apply()
    }

    fun getBiometricUserEmail(context: Context): String? =
        prefs(context).getString(KEY_BIOMETRIC_USER_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun setBiometricUserEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_BIOMETRIC_USER_EMAIL, email).apply()
    }

    fun clearBiometricCredentials(context: Context) {
        prefs(context).edit()
            .remove(KEY_BIOMETRIC_TOKEN)
            .remove(KEY_BIOMETRIC_USER_EMAIL)
            .apply()
    }

    // ── OAuth deep-link nonce ────────────────────────────────────────────────
    // "Link Google Account" opens a browser flow that redirects back into the app via the
    // medicalscanner://oauth2(-link) custom scheme — which any other app on the device can
    // also fire directly, since a custom scheme isn't exclusive. A single-use nonce generated
    // right before launching the flow, and required to match on the way back (Navigation.kt),
    // is what stops an unsolicited deep link from being trusted as a real OAuth result.
    private const val KEY_PENDING_OAUTH_NONCE = "pending_oauth_nonce"
    private const val KEY_PENDING_OAUTH_NONCE_AT = "pending_oauth_nonce_at"
    private const val OAUTH_NONCE_TTL_MS = 5 * 60 * 1000L // 5 minutes — plenty for a login round trip

    fun setPendingOAuthNonce(context: Context, nonce: String) {
        prefs(context).edit()
            .putString(KEY_PENDING_OAUTH_NONCE, nonce)
            .putLong(KEY_PENDING_OAUTH_NONCE_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Non-destructive: returns the pending nonce (or null if none/expired) WITHOUT clearing it.
     * Deliberately not "consume on read" — a stray/attacker-fired deep link with no or a wrong
     * nonce must not burn a legitimate flow that's still in flight. Only [clearPendingOAuthNonce]
     * (called once the real match is confirmed) actually consumes it.
     */
    fun peekPendingOAuthNonce(context: Context): String? {
        val p = prefs(context)
        val nonce = p.getString(KEY_PENDING_OAUTH_NONCE, null)
        val setAt = p.getLong(KEY_PENDING_OAUTH_NONCE_AT, 0L)
        if (nonce.isNullOrBlank()) return null
        if (System.currentTimeMillis() - setAt > OAUTH_NONCE_TTL_MS) return null
        return nonce
    }

    fun clearPendingOAuthNonce(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_OAUTH_NONCE).remove(KEY_PENDING_OAUTH_NONCE_AT).apply()
    }

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    private const val KEY_THEME_MODE = "theme_mode"

    fun getThemeMode(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }

    // ── Email Scanning Configurations ────────────────────────────────────────
    private const val KEY_EMAIL_CONSENT = "email_consent_granted"
    private const val KEY_LINKED_EMAIL = "linked_email_address"
    private const val KEY_LINKED_EMAIL_TYPE = "linked_email_type"
    private const val KEY_IMAP_HOST = "linked_imap_host"
    private const val KEY_IMAP_PORT = "linked_imap_port"
    private const val KEY_EMAIL_SEARCH_PROMPT = "email_search_prompt"
    private const val KEY_EMAIL_SCAN_HOUR = "email_scan_hour"
    private const val KEY_EMAIL_SCAN_MINUTE = "email_scan_minute"

    fun isEmailConsentGranted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EMAIL_CONSENT, false)

    fun setEmailConsentGranted(context: Context, granted: Boolean) {
        prefs(context).edit().putBoolean(KEY_EMAIL_CONSENT, granted).apply()
    }

    // ── Research data sharing (opt-in, off by default) ──────────────────────
    // Records the user's preference only. No aggregation/transmission pipeline exists yet —
    // this flag is read by nothing else today. It exists so a future opt-in-only pipeline can
    // launch scoped to users who already said yes, instead of asking again.
    private const val KEY_RESEARCH_DATA_CONSENT = "research_data_sharing_consent"

    fun isResearchDataSharingConsented(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESEARCH_DATA_CONSENT, false)

    fun setResearchDataSharingConsented(context: Context, granted: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESEARCH_DATA_CONSENT, granted).apply()
    }

    fun getLinkedEmail(context: Context): String? =
        prefs(context).getString(KEY_LINKED_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun setLinkedEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_LINKED_EMAIL, email).apply()
    }

    fun getLinkedEmailType(context: Context): String? =
        prefs(context).getString(KEY_LINKED_EMAIL_TYPE, null)?.takeIf { it.isNotBlank() }

    fun setLinkedEmailType(context: Context, type: String?) {
        prefs(context).edit().putString(KEY_LINKED_EMAIL_TYPE, type).apply()
    }

    fun getImapHost(context: Context): String =
        prefs(context).getString(KEY_IMAP_HOST, "imap.gmail.com") ?: "imap.gmail.com"

    fun setImapHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_IMAP_HOST, host).apply()
    }

    fun getImapPort(context: Context): Int =
        prefs(context).getInt(KEY_IMAP_PORT, 993)

    fun setImapPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_IMAP_PORT, port).apply()
    }

    fun getEmailScanHour(context: Context): Int =
        prefs(context).getInt(KEY_EMAIL_SCAN_HOUR, 19)

    fun getEmailScanMinute(context: Context): Int =
        prefs(context).getInt(KEY_EMAIL_SCAN_MINUTE, 0)

    fun setEmailScanTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_EMAIL_SCAN_HOUR, hour)
            .putInt(KEY_EMAIL_SCAN_MINUTE, minute)
            .apply()
    }

    fun getEmailSearchPrompt(context: Context): String =
        prefs(context).getString(KEY_EMAIL_SEARCH_PROMPT, "") ?: ""

    fun setEmailSearchPrompt(context: Context, prompt: String) {
        prefs(context).edit().putString(KEY_EMAIL_SEARCH_PROMPT, prompt).apply()
    }
}
