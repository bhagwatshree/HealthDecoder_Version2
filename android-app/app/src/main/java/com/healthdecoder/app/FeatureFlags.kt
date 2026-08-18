package com.healthdecoder.app

/**
 * Build-time feature switches. Kept as `const` so a disabled feature's code is dead-stripped
 * by R8 in release builds rather than merely hidden.
 */
object FeatureFlags {

    /**
     * Phone-number (MSISDN) OTP sign-in — the Login / Register screens.
     *
     * OFF by default: each OTP sends a billed SMS, and the app is fully usable without an
     * account (records, reminders and AI analysis are all on-device). With this off, the app
     * opens straight to Home and never asks the user to sign in. This also gates the "Phone
     * OTP" login tab and the "Create an account" button — every new account requires a
     * phone-OTP-verified number server-side (see RegisterScreen), so there is no OTP-free path
     * to a NEW account; only existing accounts can still log in, by email/password.
     *
     * Turn it back on by setting `PHONE_AUTH_ENABLED=true` in `local.properties`.
     */
    val PHONE_AUTH_ENABLED: Boolean = BuildConfig.PHONE_AUTH_ENABLED

    /**
     * "Sign In with Google" on the Login screen.
     *
     * OFF by default: this auto-creates a real account for any Google user with no OTP step,
     * and the OAuth client was set up for the developer's own testing — its Google Cloud
     * consent-screen publishing status (test-only vs. public) hasn't been confirmed, so it's
     * unclear whether a stranger tapping this could work at all, and if so what it would cost.
     * Turn back on by setting `GOOGLE_SIGNIN_ENABLED=true` in `local.properties` once verified.
     */
    val GOOGLE_SIGNIN_ENABLED: Boolean = BuildConfig.GOOGLE_SIGNIN_ENABLED

    /**
     * Gmail attachment auto-sync ("Link Google Account" / Email Report Scanner).
     *
     * OFF by default for the same reason as [GOOGLE_SIGNIN_ENABLED]: this was built and tested
     * against one specific Gmail account, runs a DAILY background scan per linked user against
     * the Gmail API, and its cost/quota behavior at real public scale hasn't been verified.
     * Turn back on by setting `GMAIL_SYNC_ENABLED=true` in `local.properties` once verified.
     */
    val GMAIL_SYNC_ENABLED: Boolean = BuildConfig.GMAIL_SYNC_ENABLED
}
