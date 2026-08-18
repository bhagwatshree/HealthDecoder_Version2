package com.healthdecoder.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Google API keys from AI Studio are all "AIza" + 35 more base64url-ish characters. */
private val GEMINI_KEY_PATTERN = Regex("^AIza[0-9A-Za-z_\\-]{35}$")

fun looksLikeGeminiApiKey(text: String): Boolean = GEMINI_KEY_PATTERN.matches(text.trim())

/**
 * Sends the user to Google AI Studio to create a free Gemini API key, then auto-fills it back
 * in without the user needing to manually paste: AI Studio's own key-creation page already has
 * a "copy" button next to the freshly created key, so when the user taps that and switches back
 * to this app, [onKeyDetected] fires with whatever's on the clipboard IF it matches the shape of
 * a real Gemini key. We can't control or auto-copy from Google's own page (it's their UI, not
 * ours) — this is the closest equivalent achievable from the app side, and degrades gracefully:
 * if the user doesn't copy anything, nothing auto-fills and they can paste manually as before.
 */
@Composable
fun GetFreeApiKeyButton(modifier: Modifier = Modifier, onKeyDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var awaitingReturn by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingReturn) {
                awaitingReturn = false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(context).toString().trim()
                    if (looksLikeGeminiApiKey(text)) onKeyDetected(text)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OutlinedButton(
        onClick = {
            // Only arm the clipboard check if the browser actually opened — otherwise a failed
            // launch (no browser installed, blocked by a work profile, etc.) would leave this
            // waiting for the NEXT app resume for any unrelated reason and read whatever
            // happens to be on the clipboard then, not necessarily anything from this flow.
            val opened = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            }.isSuccess
            awaitingReturn = opened
        },
        modifier = modifier
    ) {
        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr("Get Free API Key"), style = MaterialTheme.typography.labelLarge)
    }
}
