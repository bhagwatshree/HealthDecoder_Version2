package com.healthdecoder.app.backup

import android.content.Context
import android.net.Uri
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.DemoDataSeeder
import com.healthdecoder.app.local.LocalStore
import com.healthdecoder.app.model.FamilyProfile
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.SourceFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable, cross-device export/import of medical records — distinct from [BackupManager], whose
 * zip contains the raw SQLite DB encrypted with a random PER-DEVICE key (so it can only be
 * restored on the same phone). This format is plain, self-contained, and mergeable:
 *
 *  - `export.json` — the selected reports serialized with their FULL analysis already embedded
 *    (testResults, comparisonResult, healthInsights, medications). File paths are stripped to bare
 *    names; the actual bytes ride alongside under `images/` and `sources/`.
 *  - Import re-materialises those files into the receiving device's records folder, rewrites the
 *    paths, and UPSERTS each report by id — merging into existing data instead of overwriting it,
 *    and skipping anything already present (matched by page hash). Because the analysis travels
 *    inside the JSON, import NEVER re-runs the AI (no API calls, no cost).
 *
 * Supports delta exports (only reports created after a given timestamp) and per-patient exports.
 */
object ExportManager {

    const val FORMAT_VERSION = 1
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val gson: Gson = GsonBuilder().create()

    /** What one export file declares about itself, plus the payload. */
    private data class Payload(
        val formatVersion: Int,
        val exportedAt: String,
        val sinceTimestamp: String?,   // the delta cutoff used, or null for a full export
        val patientFilter: String?,    // the single patient exported, or null for all
        val reports: List<MedicalReport>,  // paths already reduced to bare file names
        val family: List<FamilyProfile> = emptyList()  // patient profiles (name/relation/sex/DOB) for the reports
    )

    /** Outcome of an import, for a user-facing summary. Import is add-or-update (merge), never a
     *  wipe-and-replace: [added] reports were new here, [updated] already existed (by id) and were
     *  refreshed from the file. */
    data class ImportResult(val added: Int, val updated: Int, val patients: Set<String>)

    private fun exportsDir(context: Context): File =
        File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }

    /**
     * Writes a portable export zip of [reports] to the cache dir and returns it (ready to share via
     * a chooser / SAF). [sinceTimestamp] and [patientFilter] are recorded in the manifest for
     * traceability only — [reports] must already be the filtered set.
     */
    fun export(
        context: Context,
        reports: List<MedicalReport>,
        sinceTimestamp: String?,
        patientFilter: String?,
        family: List<FamilyProfile> = emptyList()
    ): File {
        // The "Try Demo" sample patient (see DemoDataSeeder) must never leave the device — a real
        // backup exported for safekeeping, or handed to another family member's phone, must not
        // carry fake reports/medicines that could be mistaken for that person's actual records.
        // Filtered here, at the write boundary, so this holds regardless of what a caller passes.
        val reports = reports.filterNot { it.patientName.equals(DemoDataSeeder.DEMO_PATIENT_NAME, ignoreCase = true) }
        val family = family.filterNot { it.name.trim().equals(DemoDataSeeder.DEMO_PATIENT_NAME, ignoreCase = true) }

        val tag = (patientFilter?.replace(Regex("[^A-Za-z0-9]"), "_")?.take(20) ?: "all")
        val outFile = File(exportsDir(context), "MedicalAssist_${tag}_${stamp.format(Date())}.zip")

        // Reduce each report's absolute file paths to bare names; the bytes go in the zip separately.
        val portable = reports.map { r ->
            r.copy(
                imagePath = r.imagePath.baseName(),
                imagePaths = r.imagePaths.map { it.baseName() },
                sourceFiles = r.sourceFiles.map { it.copy(path = it.path.baseName()) },
                userEmail = null // re-scoped to the importing account on the way back in
            )
        }

        ZipOutputStream(FileOutputStream(outFile).buffered()).use { zip ->
            // Bundle every referenced image / source file exactly once.
            val addedImages = HashSet<String>()
            val addedSources = HashSet<String>()
            for (r in reports) {
                (listOfNotNull(r.imagePath.takeIf { it.isNotBlank() }) + r.imagePaths).forEach { path ->
                    val f = File(path)
                    if (f.exists() && addedImages.add(f.name)) zip.putFile("images/${f.name}", f)
                }
                for (sf in r.sourceFiles) {
                    val f = File(sf.path)
                    if (f.exists() && addedSources.add(f.name)) zip.putFile("sources/${f.name}", f)
                }
                // The cached AI "detailed analysis" deep-dive (kept in its own file, not the DB row)
                // rides along so the importing device never has to re-run — and re-pay for — it.
                val detail = File(LocalStore.detailedAnalysisDir(context), "${r.id}.json")
                if (detail.exists()) zip.putFile("detailed/${r.id}.json", detail)
            }
            val payload = Payload(FORMAT_VERSION, nowIso(), sinceTimestamp, patientFilter, portable, family)
            zip.putNextEntry(ZipEntry("export.json"))
            zip.write(gson.toJson(payload).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return outFile
    }

    /**
     * Merges a portable export (from [uri]) into this device. Files are re-materialised, paths
     * rewritten, and reports upserted by id; a report whose content already exists here (same page
     * hash under a different id) is skipped. No AI is invoked. Throws on an unreadable/wrong file.
     */
    fun import(context: Context, uri: Uri): ImportResult {
        val imagesDir = LocalStore.imagesDir(context)
        val sourcesDir = LocalStore.sourcesDir(context)
        var payloadJson: String? = null
        // Cached detailed-analysis JSON, buffered by report id so it can be restored only for the
        // reports we actually import (a skipped report keeps whatever it already has locally).
        val detailedById = HashMap<String, ByteArray>()

        // Pass 1: stream the zip, writing file entries straight into the records folder and
        // buffering export.json (its order within the zip isn't guaranteed).
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        entry.isDirectory -> {}
                        name == "export.json" -> payloadJson = zin.readBytes().toString(Charsets.UTF_8)
                        name.startsWith("images/") -> writeSafely(imagesDir, name.substringAfter("images/"), zin)
                        name.startsWith("sources/") -> writeSafely(sourcesDir, name.substringAfter("sources/"), zin)
                        name.startsWith("detailed/") ->
                            detailedById[File(name.substringAfter("detailed/")).nameWithoutExtension] = zin.readBytes()
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        } ?: throw IllegalStateException("Couldn't open the selected file.")

        val json = payloadJson ?: throw IllegalStateException("This isn't a Health Decoder export file.")
        val rawPayload = gson.fromJson(json, Payload::class.java)
            ?: throw IllegalStateException("The export file is corrupted.")

        // Gson populates fields via reflection, bypassing Kotlin's null-safety entirely — a JSON
        // missing "reports" (an older export format, a hand-edited file, or any other field-name
        // mismatch) silently leaves these Kotlin-"non-nullable" properties actually null at
        // runtime, which used to crash immediately below with a bare NPE on the very next
        // iteration ("Attempt to invoke interface method ... iterator() on a null object
        // reference") instead of a message that says what's actually wrong.
        val safeReports = rawPayload.reports ?: emptyList()
        val safeFamily = rawPayload.family ?: emptyList()

        // Second layer of the same guard as export(): even if a stray file predating that fix (or
        // a hand-edited one) somehow contains the demo patient, refuse to import it — this device's
        // own "Try Demo" flow is the only legitimate source of that data, never an imported file.
        val payload = rawPayload.copy(
            reports = safeReports.filterNot { it.patientName.equals(DemoDataSeeder.DEMO_PATIENT_NAME, ignoreCase = true) },
            family = safeFamily.filterNot { it.name.trim().equals(DemoDataSeeder.DEMO_PATIENT_NAME, ignoreCase = true) }
        )

        var added = 0
        var updated = 0
        val patients = linkedSetOf<String>()
        for (r in payload.reports) {
            // Add-or-update by report id (a merge, never a wipe): a new id is inserted, an existing
            // one is refreshed from the file. Keying on the unique id — not page hash — is what lets
            // every report of a multi-page scan bundle import on a fresh device (siblings share hashes).
            val alreadyHere = LocalStore.getReport(context, r.id) != null

            val rehydrated = r.copy(
                imagePath = r.imagePath.takeIf { it.isNotBlank() }?.let { File(imagesDir, it).absolutePath } ?: "",
                imagePaths = r.imagePaths.map { File(imagesDir, it).absolutePath },
                sourceFiles = r.sourceFiles.map { it.copy(path = File(sourcesDir, it.path.baseName()).absolutePath) },
                userEmail = null // upsertReport re-scopes it to the importing account
            )
            LocalStore.upsertReport(context, rehydrated)
            // Restore this report's cached deep-dive analysis so it isn't re-run (re-paid) on view.
            detailedById[r.id]?.let { bytes ->
                runCatching { File(LocalStore.detailedAnalysisDir(context), "${r.id}.json").writeBytes(bytes) }
            }
            if (alreadyHere) updated++ else added++
            r.patientName?.takeIf { it.isNotBlank() }?.let { patients.add(it) }
        }

        // Merge the patient profiles (name/relation/sex/DOB) into this device's family list,
        // add-or-update by name so the imported people's details come across too.
        if (payload.family.isNotEmpty()) {
            val fam = AppSettings.getFamilyProfilesRaw(context).toMutableList()
            for (incoming in payload.family) {
                val i = fam.indexOfFirst { it.name.trim().equals(incoming.name.trim(), ignoreCase = true) }
                if (i >= 0) fam[i] = fam[i].copy(
                    relation = incoming.relation.ifBlank { fam[i].relation },
                    avatarEmoji = incoming.avatarEmoji.ifBlank { fam[i].avatarEmoji },
                    sex = incoming.sex.ifBlank { fam[i].sex },
                    dateOfBirth = incoming.dateOfBirth.ifBlank { fam[i].dateOfBirth }
                ) else fam.add(incoming)
            }
            AppSettings.setFamilyProfiles(context, fam)
        }
        return ImportResult(added, updated, patients)
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private fun String.baseName(): String = if (isBlank()) this else File(this).name

    private fun ZipOutputStream.putFile(entryName: String, file: File) {
        putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(this) }
        closeEntry()
    }

    /** Writes a zip entry's bytes into [dir] under a sanitized base name (guards path traversal). */
    private fun writeSafely(dir: File, rawName: String, input: java.io.InputStream) {
        val safe = File(rawName).name // strip any path components
        if (safe.isBlank() || safe == "." || safe == "..") return
        val out = File(dir, safe)
        if (!out.canonicalPath.startsWith(dir.canonicalPath)) return
        FileOutputStream(out).use { input.copyTo(it) }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
}
