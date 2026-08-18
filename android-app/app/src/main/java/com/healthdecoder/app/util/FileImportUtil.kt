package com.healthdecoder.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/** Result of importing a picked file: page images and/or extracted document text. */
data class ImportResult(val images: List<Uri> = emptyList(), val text: String = "")

/**
 * Imports a file the user picked from ANY folder (Downloads, Documents, Drive, gallery…).
 * - Images (incl. X-ray photos) pass through as image pages.
 * - PDFs are rendered page-by-page into image pages.
 * - Word (.docx) and text files have their text extracted (fed to the AI as text).
 * So the scan pipeline can read and analyze all of them. Call from a background thread.
 */
object FileImportUtil {

    fun mimeOf(context: Context, uri: Uri): String = context.contentResolver.getType(uri) ?: ""

    fun displayName(context: Context, uri: Uri): String {
        var name = ""
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && c.moveToFirst()) name = c.getString(idx) ?: ""
            }
        } catch (_: Exception) { }
        return name.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "file" }
    }

    fun importFile(context: Context, uri: Uri): ImportResult {
        val mime = mimeOf(context, uri).lowercase()
        val name = uri.toString().lowercase()
        return when {
            mime.contains("pdf") || name.endsWith(".pdf") -> ImportResult(images = renderPdf(context, uri))
            mime.startsWith("image/") -> ImportResult(images = listOfNotNull(cacheImage(context, uri)))
            name.endsWith(".docx") || mime.contains("wordprocessingml") -> ImportResult(text = extractDocx(context, uri))
            name.endsWith(".doc") || mime == "application/msword" -> ImportResult(text = extractLegacyDoc(context, uri))
            mime.startsWith("text/") || name.endsWith(".txt") -> ImportResult(text = readText(context, uri))
            else -> ImportResult(images = listOfNotNull(cacheImage(context, uri))) // assume it's an image
        }
    }

    /**
     * Copies a picked image into the app's own cache and returns a local file:// URI.
     * Picker/gallery content:// URIs only carry a transient read grant that can be revoked
     * (e.g. the process gets trimmed while the picker app is in front); reading it again at
     * Analyze time then fails for every page. Copying immediately avoids depending on that
     * grant surviving until the user taps Analyze.
     */
    fun cacheImage(context: Context, uri: Uri): Uri? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val f = File.createTempFile("picked_", ".img", context.cacheDir)
            input.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
            Uri.fromFile(f)
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    // ── PDF → images ──────────────────────────────────────────────────────────
    private fun renderPdf(context: Context, uri: Uri, maxPages: Int = 15): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val renderer = PdfRenderer(pfd)
                try {
                    val count = minOf(renderer.pageCount, maxPages)
                    for (i in 0 until count) {
                        var page: PdfRenderer.Page? = null
                        try {
                            page = renderer.openPage(i)
                            val scale = 1.5f
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            page = null

                            val f = File.createTempFile("pdfpage_${i}_", ".jpg", context.cacheDir)
                            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            bmp.recycle()
                            out.add(Uri.fromFile(f))
                        } catch (pageEx: Exception) {
                            pageEx.printStackTrace()
                            try { page?.close() } catch (_: Exception) {}
                        }
                    }
                } finally {
                    try { renderer.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    // ── Word .docx → text (docx is a zip of XML; no library needed) ─────────────
    private fun extractDocx(context: Context, uri: Uri): String {
        return try {
            var xml = ""
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        if (e.name == "word/document.xml") { xml = zin.readBytes().toString(Charsets.UTF_8); break }
                        e = zin.nextEntry
                    }
                }
            }
            if (xml.isBlank()) return ""
            xml.replace("</w:p>", "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .trim()
        } catch (e: Exception) {
            e.printStackTrace(); ""
        }
    }

    // ── Legacy .doc → best-effort text (extract readable runs) ──────────────────
    private fun extractLegacyDoc(context: Context, uri: Uri): String {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
            val raw = String(bytes, Charsets.ISO_8859_1)
            // Keep runs of printable characters of length >= 4.
            Regex("[\\x20-\\x7E\\r\\n]{4,}").findAll(raw)
                .map { it.value }
                .filter { it.any { c -> c.isLetter() } }
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
        } catch (e: Exception) {
            e.printStackTrace(); ""
        }
    }

    private fun readText(context: Context, uri: Uri): String = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }?.trim() ?: ""
    } catch (e: Exception) {
        e.printStackTrace(); ""
    }
}
