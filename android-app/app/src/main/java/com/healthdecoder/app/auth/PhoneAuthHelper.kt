package com.healthdecoder.app.auth

import android.app.Activity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around Firebase Phone Auth for OTP signup/login. Safe to call even on a build
 * without google-services.json configured yet — [isAvailable] reports that case so screens can
 * show "phone verification isn't set up yet" instead of crashing (see app/build.gradle.kts).
 */
object PhoneAuthHelper {

    fun isAvailable(): Boolean = try {
        FirebaseApp.getInstance()
        true
    } catch (e: IllegalStateException) {
        false
    }

    sealed class OtpEvent {
        data class CodeSent(val verificationId: String) : OtpEvent()
        data class AutoVerified(val idToken: String) : OtpEvent()
        data class Failed(val message: String) : OtpEvent()
    }

    /** Triggers an OTP SMS to [phoneE164] (e.g. "+919876543210"). */
    fun sendOtp(activity: Activity, phoneE164: String, onEvent: (OtpEvent) -> Unit) {
        if (!isAvailable()) {
            onEvent(OtpEvent.Failed("Phone verification isn't set up on this build yet."))
            return
        }
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Instant/auto-retrieved verification (no manual code entry on this device).
                signInWithCredential(credential) { result ->
                    result.onSuccess { onEvent(OtpEvent.AutoVerified(it)) }
                        .onFailure { onEvent(OtpEvent.Failed(it.localizedMessage ?: "Verification failed.")) }
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                onEvent(OtpEvent.Failed(e.localizedMessage ?: "Couldn't send OTP. Check the number and try again."))
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                onEvent(OtpEvent.CodeSent(verificationId))
            }
        }

        val options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
            .setPhoneNumber(phoneE164)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    /** Verifies a manually-entered [code] against [verificationId]; returns a Firebase ID token. */
    fun verifyOtp(verificationId: String, code: String, onResult: (Result<String>) -> Unit) {
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        signInWithCredential(credential, onResult)
    }

    private fun signInWithCredential(credential: PhoneAuthCredential, onResult: (Result<String>) -> Unit) {
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnSuccessListener { result ->
                result.user?.getIdToken(false)
                    ?.addOnSuccessListener { onResult(Result.success(it.token ?: "")) }
                    ?.addOnFailureListener { onResult(Result.failure(it)) }
                    ?: onResult(Result.failure(IllegalStateException("No user after verification.")))
            }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    /** Clears the local Firebase session — our own backend JWT is the source of truth afterward. */
    fun signOut() {
        try { FirebaseAuth.getInstance().signOut() } catch (e: Exception) { /* no session to clear */ }
    }
}
