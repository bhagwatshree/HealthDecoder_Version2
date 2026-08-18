package com.healthdecoder.app.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.healthdecoder.app.BuildKeys
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Native "Sign in with Google": shows the on-device account picker via Credential Manager (no
 * browser tab, no OAuth consent screen), then hands the resulting Google ID token to Firebase
 * Auth to mint a Firebase ID token our backend can verify — see /api/auth/google-signin.
 *
 * This only proves identity. It intentionally does *not* request Gmail access — that remains a
 * separate, deliberately browser-based flow (SettingsScreen's "Link Google Account" for email
 * scanning), since offline Gmail scope requires an explicit consent screen either way.
 */
object GoogleSignInHelper {

    fun isAvailable(): Boolean = BuildKeys.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** Shows the account picker and signs in; [onResult] receives a Firebase ID token to send
     *  to the backend, or the failure (including user cancellation). */
    suspend fun signIn(context: Context, onResult: (Result<String>) -> Unit) {
        if (!isAvailable()) {
            onResult(Result.failure(IllegalStateException("Google sign-in isn't set up on this build yet.")))
            return
        }
        try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildKeys.GOOGLE_WEB_CLIENT_ID)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

            val response = CredentialManager.create(context).getCredential(context, request)
            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                onResult(Result.failure(IllegalStateException("Unexpected credential type from Google Sign-In.")))
                return
            }
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)

            FirebaseAuth.getInstance().signInWithCredential(firebaseCredential)
                .addOnSuccessListener { authResult ->
                    authResult.user?.getIdToken(false)
                        ?.addOnSuccessListener { onResult(Result.success(it.token ?: "")) }
                        ?.addOnFailureListener { onResult(Result.failure(it)) }
                        ?: onResult(Result.failure(IllegalStateException("No user after Google sign-in.")))
                }
                .addOnFailureListener { onResult(Result.failure(it)) }
        } catch (e: Exception) {
            onResult(Result.failure(e))
        }
    }

    /** Forgets the on-device credential state so the account picker shows again next time
     *  (otherwise Credential Manager may silently re-offer the last-used account). */
    suspend fun signOut(context: Context) {
        try {
            FirebaseAuth.getInstance().signOut()
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) { /* no session to clear */ }
    }
}
