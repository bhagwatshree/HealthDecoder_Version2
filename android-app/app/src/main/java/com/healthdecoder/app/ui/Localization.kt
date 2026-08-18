package com.healthdecoder.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.healthdecoder.app.local.AppLanguageState
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.DynamicTranslations
import com.healthdecoder.app.local.RemoteUiTranslations

/**
 * Translates [text] into the app-wide preferred language. Lookup order: the on-device
 * cache of the backend's ui_translations table (DB is the source of truth — edits there
 * reach every install without a build), then the bundled UiTranslations.kt seed (works
 * before the first successful fetch / fully offline), then the original English text.
 * No network call happens here — fetching is handled by RemoteUiTranslations, kicked off
 * once at app start and again whenever the user picks a language.
 */
@Composable
fun tr(text: String): String {
    if (text.isBlank()) return text
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppLanguageState.ensureInit(context) }
    val language = AppLanguageState.current

    if (language.equals("English", ignoreCase = true)) return text

    return staticLookup(context, language, text) ?: text
}

private fun staticLookup(context: android.content.Context, language: String, text: String): String? =
    RemoteUiTranslations.get(context, language, text) ?: UiTranslations.lookup(language, text)

/**
 * [tr] for content rather than chrome — text that can change without an app release, so no
 * hand-maintained map can ever cover it: health tips (the backend's `health_tips` table is the
 * real source, the bundled lists are only an offline seed) and anything read off a scanned
 * report, such as canonical test names.
 *
 * Still prefers the static maps when the string happens to be in them — free, offline, and
 * exact — and only falls through to a translation call for what they don't cover. The English
 * text renders first and is replaced when the translation arrives, so a slow or absent network
 * costs nothing but the original wording; see [DynamicTranslations] for the cache.
 */
@Composable
fun trDynamic(text: String): String {
    if (text.isBlank()) return text
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppLanguageState.ensureInit(context) }
    val language = AppLanguageState.current

    if (language.equals("English", ignoreCase = true)) return text
    staticLookup(context, language, text)?.let { return it }

    var resolved by remember(language, text) {
        mutableStateOf(DynamicTranslations.cached(context, language, text) ?: text)
    }
    LaunchedEffect(language, text) {
        resolved = DynamicTranslations.translate(context, language, text)
    }
    return resolved
}

/**
 * [tr] for a string that embeds a runtime value. The key stays a positional format template
 * ("Re-analyze All (%1\$d)") so it can live in the translation maps like any other string, and
 * each language can move the placeholder to wherever its grammar needs it. Interpolating first —
 * tr("Re-analyze All ($count)") — builds a different string on every call, so it never matches a
 * key and silently renders in English forever.
 *
 * A translation with the wrong format specifiers would throw at render time, so a bad row in the
 * DB can't crash a screen: formatting failures fall back to the English template.
 */
@Composable
fun trFormat(template: String, vararg args: Any?): String {
    val localized = tr(template)
    return runCatching { String.format(localized, *args) }
        .getOrElse { runCatching { String.format(template, *args) }.getOrDefault(template) }
}

@Composable
fun LanguagePickerIcon() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppLanguageState.ensureInit(context) }
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(imageVector = Icons.Default.Translate, contentDescription = tr("Change language"))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        AppSettings.SUPPORTED_LANGUAGES.forEach { lang ->
            DropdownMenuItem(
                text = {
                    Text(
                        lang,
                        fontWeight = if (lang == AppLanguageState.current) FontWeight.Bold else FontWeight.Normal
                    )
                },
                trailingIcon = {
                    if (lang == AppLanguageState.current) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null)
                    }
                },
                onClick = {
                    AppLanguageState.select(context, lang)
                    expanded = false
                }
            )
        }
    }
}
