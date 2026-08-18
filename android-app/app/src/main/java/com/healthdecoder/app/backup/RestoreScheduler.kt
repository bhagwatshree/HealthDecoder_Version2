package com.healthdecoder.app.backup

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a restore in a scope that outlives the Settings screen — a restore can take 20-30+
 * seconds on a real backup (staged extraction + SQLCipher decrypt verification), and a
 * coroutine launched from rememberCoroutineScope() gets CANCELLED the moment the composable
 * that started it leaves composition (e.g. the user taps a different bottom-nav tab while
 * waiting). That used to silently abort an in-progress restore with no error at all — exactly
 * matching a real report of "navigating away seems to have stopped the restore". Mirrors
 * BackgroundScanScheduler's same top-level-scope pattern, already solving this for scans.
 */
object RestoreScheduler {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var isBusy by mutableStateOf(false)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set

    /** True when a password prompt is needed before [start] can actually attempt anything —
     *  the caller checks this synchronously (see BackupManager.requiresPassword) before calling. */
    fun start(context: Context, backupZip: File, password: String?, onOutcome: (BackupManager.RestoreOutcome) -> Unit = {}) {
        if (isBusy) return
        isBusy = true
        resultMessage = null
        val appContext = context.applicationContext
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { BackupManager.restoreBackup(appContext, backupZip, password) }
            if (outcome != BackupManager.RestoreOutcome.PASSWORD_REQUIRED && outcome != BackupManager.RestoreOutcome.WRONG_PASSWORD) {
                runCatching { backupZip.delete() }
            }
            val message = when (outcome) {
                BackupManager.RestoreOutcome.SUCCESS -> "Backup restored — reloading…"
                BackupManager.RestoreOutcome.NOT_A_BACKUP ->
                    "Restore failed — this doesn't look like a Backup & Restore file. If it came from \"Share / Merge Records\", use the Import button in that section instead."
                BackupManager.RestoreOutcome.INCOMPATIBLE_KEY ->
                    "Restore failed — this backup can't be opened on this device (likely made on a different phone, or before a reinstall). Your existing data on this device was NOT changed."
                BackupManager.RestoreOutcome.READ_ERROR -> "Restore failed — couldn't read the selected file."
                BackupManager.RestoreOutcome.PASSWORD_REQUIRED, BackupManager.RestoreOutcome.WRONG_PASSWORD -> null
            }
            if (message != null) {
                resultMessage = message
                android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG).show()
            }
            isBusy = false
            onOutcome(outcome)
            if (outcome == BackupManager.RestoreOutcome.SUCCESS) {
                delay(800) // let the toast register before the app restarts
                restartApp(appContext)
            }
        }
    }

    // Duplicated (not reused) from SettingsScreen's private restartApp() — every screen's cached
    // in-memory state needs a full process restart to guarantee it re-reads the just-restored
    // data from disk, not just Settings' own; keeping it here avoids exposing that private
    // function across a package boundary for what's a two-line implementation anyway.
    private fun restartApp(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val restartIntent = android.content.Intent.makeRestartActivityTask(launchIntent.component)
        context.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    }
}
