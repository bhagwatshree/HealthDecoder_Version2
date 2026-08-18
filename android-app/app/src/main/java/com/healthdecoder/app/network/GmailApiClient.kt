package com.healthdecoder.app.network

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal Gmail REST API client for EmailScanWorker's inbox scanning. Separate from
 * NetworkModule's Retrofit client since this talks to Google (authenticated with the user's
 * Google access token), not our own backend (authenticated with our app JWT).
 *
 * Uses the Gmail REST API rather than raw IMAP because the OAuth scope we request is the
 * narrower gmail.readonly — Google only lets the "restricted" https://mail.google.com/ scope
 * (required for IMAP/SMTP) through without a paid third-party security assessment.
 */
object GmailApiClient {
    private const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"

    // Carries the user's Google OAuth access token and Gmail message contents — never log
    // full bodies in a release build.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (com.healthdecoder.app.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    /** A PDF attachment located on a message, ready to download. */
    data class PdfAttachmentRef(val attachmentId: String, val fileName: String)

    private fun getJson(url: String, accessToken: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("Gmail API error ${response.code}: $body")
            return JSONObject(body)
        }
    }

    /** Returns Gmail message IDs matching [query] (Gmail search syntax, e.g. "after:2026/01/01 subject:report"). */
    fun searchMessageIds(accessToken: String, query: String, maxResults: Int = 50): List<String> {
        val url = "$BASE_URL/messages?maxResults=$maxResults&q=" + URLEncoder.encode(query, "UTF-8")
        val messages = getJson(url, accessToken).optJSONArray("messages") ?: return emptyList()
        return (0 until messages.length()).map { messages.getJSONObject(it).getString("id") }
    }

    /** Walks [messageId]'s MIME part tree and returns the first PDF attachment found, if any. */
    fun findPdfAttachment(accessToken: String, messageId: String): PdfAttachmentRef? {
        val payload = getJson("$BASE_URL/messages/$messageId?format=full", accessToken).optJSONObject("payload")
            ?: return null
        return findPdfInParts(payload)
    }

    private fun findPdfInParts(part: JSONObject): PdfAttachmentRef? {
        val fileName = part.optString("filename", "")
        val attachmentId = part.optJSONObject("body")?.optString("attachmentId", "") ?: ""
        if (fileName.lowercase().endsWith(".pdf") && attachmentId.isNotBlank()) {
            return PdfAttachmentRef(attachmentId, fileName)
        }
        val parts = part.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            findPdfInParts(parts.getJSONObject(i))?.let { return it }
        }
        return null
    }

    /** Downloads and decodes a message attachment's raw bytes. */
    fun downloadAttachment(accessToken: String, messageId: String, attachmentId: String): ByteArray {
        val data = getJson("$BASE_URL/messages/$messageId/attachments/$attachmentId", accessToken).getString("data")
        // Gmail returns unpadded base64url; pad it out so Android's decoder accepts it.
        val padded = data + "=".repeat((4 - data.length % 4) % 4)
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
