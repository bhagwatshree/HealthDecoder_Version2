package com.healthdecoder.app.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Cloud backup uploader that uses Android's Storage Access Framework (SAF).
 *
 * The user picks **any** folder via the system folder-picker — if a cloud app
 * (Google Drive, OneDrive, Dropbox, etc.) is installed, its folders appear in the
 * picker. Once selected, the URI is persisted with `takePersistableUriPermission`
 * so future backups go to the same folder automatically.
 *
 * The cloud provider's own app handles the actual upload — zero OAuth needed.
 *
 * ## How it works
 * 1. User taps "Choose Backup Folder" in Settings → standard Android folder picker opens.
 * 2. User navigates to "Google Drive > My Drive > Backups" (or any OneDrive/Dropbox folder).
 * 3. App saves the chosen folder URI in SharedPreferences.
 * 4. On every backup, the zip file is copied into that folder via SAF's ContentResolver.
 * 5. The cloud app (Drive, OneDrive) auto-syncs the folder to the cloud.
 */
object SafCloudUploader : CloudUploader {

    private const val PREFS = "medical_scanner_prefs"
    private const val KEY_BACKUP_FOLDER_URI = "cloud_backup_folder_uri"

    /** Returns true if the user has selected a backup folder. */
    override fun isConfigured(context: Context): Boolean {
        return getBackupFolderUri(context) != null
    }

    /**
     * Copies [file] into the user-chosen cloud-synced folder via SAF.
     * Returns true on success.
     */
    override fun upload(context: Context, file: File): Boolean {
        val folderUri = getBackupFolderUri(context) ?: return false
        return try {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return false
            if (!folder.canWrite()) return false

            // Create the file in the cloud folder (or overwrite if same name exists)
            val existing = folder.findFile(file.name)
            val target = existing ?: folder.createFile("application/zip", file.nameWithoutExtension)
                ?: return false

            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: return false

            true
        } catch (e: Exception) {
            android.util.Log.w("SafCloudUploader", "Upload failed: ${e.message}", e)
            false
        }
    }

    // ── Configuration helpers (called from Settings UI) ──────────────────────

    /** Saves the folder URI the user picked from the SAF folder chooser. */
    fun setBackupFolderUri(context: Context, uri: Uri?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (uri != null) {
            // Take persistable permission so the URI survives reboots
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable permissions; the URI still
                // works for the current session and often across reboots anyway.
            }
            prefs.edit().putString(KEY_BACKUP_FOLDER_URI, uri.toString()).apply()
        } else {
            prefs.edit().remove(KEY_BACKUP_FOLDER_URI).apply()
        }
    }

    /** Returns the persisted backup folder URI, or null if none has been chosen. */
    fun getBackupFolderUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKUP_FOLDER_URI, null)
        return raw?.let { Uri.parse(it) }
    }

    /**
     * Returns a human-friendly label for the selected folder, e.g. "Google Drive > Backups".
     * Returns null if no folder is configured.
     */
    fun getBackupFolderLabel(context: Context): String? {
        val uri = getBackupFolderUri(context) ?: return null
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc?.name ?: uri.lastPathSegment ?: "Selected folder"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Selected folder"
        }
    }

    /** Disconnects the cloud backup folder. Local backups continue as usual. */
    fun clearBackupFolder(context: Context) {
        val uri = getBackupFolderUri(context)
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* ignore */ }
        }
        setBackupFolderUri(context, null)
    }
}
