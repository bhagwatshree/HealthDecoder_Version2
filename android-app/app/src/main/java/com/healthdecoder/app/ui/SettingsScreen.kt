package com.healthdecoder.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.FeatureFlags
import com.healthdecoder.app.backup.BackupManager
import com.healthdecoder.app.backup.BackupSync
import com.healthdecoder.app.backup.RestoreScheduler
import com.healthdecoder.app.backup.SafCloudUploader
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.local.LocalStore
import com.healthdecoder.app.local.MaintenanceScheduler
import com.healthdecoder.app.local.SecureKeyManager
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.network.NetworkModule
import com.healthdecoder.app.ui.components.AppBottomNavBar
import com.healthdecoder.app.ui.components.BottomNavTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A successful restore swaps the database/records folder out from under the running process —
// screens, LaunchedEffects, and cached repository state have no way to know their in-memory data
// is now stale. "Go back and refresh" doesn't reliably surface the restored data (Home's refresh
// only pings the server). Restarting the whole task guarantees every screen re-reads from disk.
private fun restartApp(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val restartIntent = android.content.Intent.makeRestartActivityTask(launchIntent.component)
    context.startActivity(restartIntent)
    Runtime.getRuntime().exit(0)
}

/**
 * App-behavior and data-management screen (previously "Account"/`IPConfigScreen`, both folded in
 * here) — language, voice, theme, units, reminder style, demo data, email scanning, and every
 * data-transfer/backup tool. Identity/account-holder concerns (own name, family members, BYOK
 * key, password, fingerprint, logout/delete) live on [ProfileScreen] instead, reached from Home's
 * profile icon rather than this screen's bottom-nav Settings tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToTab: (BottomNavTab) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var prefLanguage by remember { mutableStateOf(AppSettings.getPreferredLanguage(context)) }
    var langExpanded by remember { mutableStateOf(false) }
    var voiceEngine by remember { mutableStateOf(AppSettings.getVoiceEngine(context)) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var reminderStyle by remember { mutableStateOf(AppSettings.getReminderStyle(context)) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteAllResult by remember { mutableStateOf<String?>(null) }
    var deletingAll by remember { mutableStateOf(false) }
    var backupResult by remember { mutableStateOf<String?>(null) }
    var dupCandidates by remember { mutableStateOf<List<MedicalReport>>(emptyList()) }
    var showDupDialog by remember { mutableStateOf(false) }
    var dupResult by remember { mutableStateOf<String?>(null) }
    var dupScanning by remember { mutableStateOf(false) }
    var cloudFolderLabel by remember { mutableStateOf(SafCloudUploader.getBackupFolderLabel(context)) }
    var pendingSyncCount by remember { mutableStateOf(BackupSync.pendingCount(context)) }
    var syncing by remember { mutableStateOf(false) }
    var transferResult by remember { mutableStateOf<String?>(null) }
    var transferBusy by remember { mutableStateOf(false) }
    var patients by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportPatient by remember { mutableStateOf<String?>(null) } // null = all patients
    var exportDelta by remember { mutableStateOf(false) }
    var exportFrom by remember { mutableStateOf("") } // YYYY-MM-DD, inclusive; blank = no lower bound
    var exportTo by remember { mutableStateOf("") }   // YYYY-MM-DD, inclusive; blank = no upper bound
    var patientMenuOpen by remember { mutableStateOf(false) }
    var mergeFrom by remember { mutableStateOf<String?>(null) }
    var mergeTo by remember { mutableStateOf("") }
    var mergeMenuOpen by remember { mutableStateOf(false) }
    var mergeResult by remember { mutableStateOf<String?>(null) }
    var protectBackupWithPassword by remember { mutableStateOf(false) }
    var backupPasswordInput by remember { mutableStateOf("") }
    var backupPasswordConfirmInput by remember { mutableStateOf("") }
    // Separate from the manual-export password above — this one applies to the AUTOMATIC
    // backups LocalRepository.afterWrite() creates after every write and syncs to the cloud
    // folder below. Opt-in, defaults to off (unprotected), same as before this existed.
    var hasAutoBackupPassword by remember { mutableStateOf(false) }
    var autoBackupPasswordInput by remember { mutableStateOf("") }
    var autoBackupPasswordSaveMsg by remember { mutableStateOf<String?>(null) }
    // Set only when a picked file needs a password before restoreBackup() can even attempt it —
    // drives the password-prompt dialog below. Cleared (and the temp file deleted) on cancel.
    var pendingRestoreFile by remember { mutableStateOf<File?>(null) }
    var restorePasswordInput by remember { mutableStateOf("") }
    var restorePasswordError by remember { mutableStateOf<String?>(null) }
    var restoreBusy by remember { mutableStateOf(false) }
    var degradedCount by remember { mutableStateOf(0) }
    var atRiskCount by remember { mutableStateOf(0) }
    // fixDegradedBusy/Progress/Result and recoverBusy/Progress/Result now live in
    // MaintenanceScheduler, not here — see its doc comment for why (surviving navigation away
    // from this screen mid-run, both the coroutine itself and the progress state it reports).

    // Hoisted here because tr() is @Composable and can't be called from the plain onClick /
    // coroutine lambdas below where these Toasts are shown.
    val scanningInboxToast = tr("Scanning inbox for new reports…")
    val foundNewReportsToastPrefix = tr("Found")
    val foundNewReportsToastSuffix = tr("new report(s) — check your notifications.")
    val noNewReportsInLast2DaysToast = tr("No new reports found in the last 2 days.")
    val scanFailedCheckSettingsToast = tr("Scan failed. Check your email settings and try again.")
    val emailScanHistoryClearedToast = tr("Email scan history cleared — reports can be re-detected.")
    val pleaseLinkGoogleAccountToast = tr("Please link your Google Account first.")
    val emailSettingsSavedToast = tr("Email settings saved successfully.")
    val pleaseEnterEmailAddressToast = tr("Please enter an email address.")

    LaunchedEffect(Unit) { patients = LocalRepository.listPatients(context) }
    LaunchedEffect(Unit) { degradedCount = LocalRepository.findDegradedReports(context).size }
    LaunchedEffect(Unit) { hasAutoBackupPassword = SecureKeyManager.getBackupPassword(context) != null }
    LaunchedEffect(Unit) { atRiskCount = LocalRepository.findAtRiskBundles(context).size }

    // SAF folder picker: user picks a cloud-synced folder (Drive / OneDrive / Dropbox / local)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            SafCloudUploader.setBackupFolderUri(context, uri)
            cloudFolderLabel = SafCloudUploader.getBackupFolderLabel(context)
            coroutineScope.launch {
                syncing = true
                withContext(Dispatchers.IO) { BackupSync.syncPending(context) }
                pendingSyncCount = BackupSync.pendingCount(context)
                syncing = false
            }
        }
    }

    // Export a backup zip to any folder the user picks (Google Drive / OneDrive / local).
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) coroutineScope.launch {
            val passwordToUse = if (protectBackupWithPassword) backupPasswordInput else null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = BackupManager.createLocalBackup(context, passwordToUse) ?: return@runCatching "Nothing to back up yet — scan a report first."
                    context.contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                    "Backup exported successfully."
                }.getOrElse { "Export failed: ${it.message}" }
            }
            backupResult = result
            android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
            backupPasswordInput = ""
            backupPasswordConfirmInput = ""
        }
    }

    // The actual restore now runs in RestoreScheduler — a scope that outlives this screen, so
    // navigating to another tab mid-restore no longer cancels it (rememberCoroutineScope()'s
    // scope dies the moment this composable leaves composition; a real restore can take 20-30+
    // seconds, easily longer than someone waits before tapping elsewhere). See RestoreScheduler
    // for the outcome-to-message mapping and post-success restart, now centralized there.

    // Restore from a backup zip the user picks. Filtering OpenDocument to "application/zip" broke
    // restoring from Google Drive: Drive's DocumentsProvider often reports synced/uploaded files as
    // application/octet-stream rather than the exact MIME type, so the picker greyed the backup out
    // or hid it entirely. "*/*" lets any file be picked; restoreBackup already validates the content
    // and reports "not a valid backup file" if it isn't one.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            // Reading a backup off a cloud-synced folder, then staging + verifying it against
            // SQLCipher, can easily take 20-30+ seconds with zero UI feedback otherwise — that
            // read as "nothing happening" (it wasn't stuck, there was just no visible sign it
            // was working at all).
            restoreBusy = true
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File.createTempFile("restore_", ".zip", context.cacheDir)
                    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    if (copied == null || f.length() == 0L) { f.delete(); null } else f
                }.getOrNull()
            }
            if (tmp == null) {
                restoreBusy = false
                val msg = "Restore failed — couldn't read the selected file."
                backupResult = msg
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            restoreBusy = false // hand off to RestoreScheduler.isBusy from here, either path
            if (withContext(Dispatchers.IO) { BackupManager.requiresPassword(tmp) }) {
                restorePasswordInput = ""
                restorePasswordError = null
                pendingRestoreFile = tmp
            } else {
                // Runs in RestoreScheduler's own top-level scope, NOT this composable's — so it
                // survives navigating to another screen while it's in progress.
                RestoreScheduler.start(context, tmp, null)
            }
        }
    }

    fun runExport() {
        coroutineScope.launch {
            transferBusy = true
            transferResult = runCatching {
                val file = LocalRepository.exportData(context, exportPatient, exportDelta, exportFrom.trim(), exportTo.trim())
                if (file == null) "Nothing to export for that selection." else {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share export"))
                    "Export ready — choose where to send it."
                }
            }.getOrElse { e ->
                e.printStackTrace()
                "Export failed. Please try again."
            }
            transferBusy = false
        }
    }

    val portableImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            transferBusy = true
            transferResult = withContext(Dispatchers.IO) {
                runCatching {
                    val res = LocalRepository.importData(context, uri)
                    buildString {
                        append("Added ${res.added}")
                        if (res.updated > 0) append(", updated ${res.updated}")
                        append(" report(s)")
                        if (res.patients.isNotEmpty()) append(" • ${res.patients.joinToString()}")
                    }
                }.getOrElse { e ->
                    e.printStackTrace()
                    "Import failed. Please check the file and try again."
                }
            }
            patients = LocalRepository.listPatients(context)
            transferBusy = false
        }
    }

    fun runMerge() {
        val from = mergeFrom
        val to = mergeTo.trim()
        if (from == null || to.isEmpty()) { mergeResult = "Pick a patient, then type the correct name."; return }
        coroutineScope.launch {
            transferBusy = true
            mergeResult = runCatching {
                val n = LocalRepository.mergePatient(context, from, to)
                patients = LocalRepository.listPatients(context)
                mergeFrom = null; mergeTo = ""
                "Merged $n report(s) into \"$to\"."
            }.getOrElse { "Merge failed: ${it.message}" }
            transferBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TopBarLogo()
                        Text(tr("Settings"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            AppBottomNavBar(currentTab = BottomNavTab.Settings, onNavigate = onNavigateToTab)
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Preferred language for explanations & assistant
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tr("Preferred Language"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = tr("Medicine explanations and the AI assistant will use this language. Medicine and test names stay in English."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedCard(
                            onClick = { langExpanded = true },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(text = tr("Language"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(text = prefLanguage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                Icon(imageVector = if (langExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                            AppSettings.SUPPORTED_LANGUAGES.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang, fontWeight = FontWeight.Medium) },
                                    onClick = { prefLanguage = lang; AppSettings.setPreferredLanguage(context, lang); langExpanded = false }
                                )
                            }
                        }
                    }
                }
            }

            // Voice (Text-to-Speech) engine
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tr("Voice (Read Aloud)"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = tr("Which voice reads answers aloud. Sarvam & Gemini speak Indian languages well; Phone uses your device's built-in voices (may not have Marathi)."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ExposedDropdownMenuBox(expanded = voiceExpanded, onExpandedChange = { voiceExpanded = it }) {
                        OutlinedTextField(
                            value = voiceEngine,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(tr("Voice engine")) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                            AppSettings.VOICE_ENGINES.forEach { eng ->
                                DropdownMenuItem(text = { Text(eng) }, onClick = { voiceEngine = eng; AppSettings.setVoiceEngine(context, eng); voiceExpanded = false })
                            }
                        }
                    }
                }
            }

            // Medicine reminder style: standard notification vs full-screen large text
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tr("Medicine Reminder Style"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = tr("Full Screen shows a large-text alarm page (even on the lock screen) so medicine names are easy to read. Medicines due at the same time always appear together in one reminder."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(
                        Triple(AppSettings.REMINDER_STYLE_NORMAL, "Normal notification", "A standard notification with sound and vibration."),
                        Triple(AppSettings.REMINDER_STYLE_FULLSCREEN, "Full screen (large text)", "Fills the screen with big letters — best for elderly users.")
                    ).forEach { (value, label, desc) ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reminderStyle == value, onClick = { reminderStyle = value; AppSettings.setReminderStyle(context, value) })
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // ── Data Management ─────────────────────────────────────────────
            // Share/Merge and Backup/Restore solve different problems and neither replaces the
            // other: Share/Merge is selective and ADDITIVE (pick a patient/date range, merge into
            // whatever's already on the target phone, keeps the AI analysis so importing is free).
            // Backup/Restore is a whole-device snapshot that REPLACES everything on restore — for
            // disaster recovery on the same phone, or the Drive/OneDrive auto-sync safety net.
            Text(
                text = tr("Data Management"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = tr("Share / Merge sends a selection to another phone or a doctor and adds it to what's there. Backup & Restore is a full snapshot of this device that replaces everything when restored — use it for disaster recovery, not sharing."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Portable transfer — share records to another phone, or merge someone else's in.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tr("Share / Merge Records"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = tr("Export a shareable file of your records (with analysis included) to send to another phone or a doctor. Importing merges it into this phone and never re-runs the AI, so it's free."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Box {
                        OutlinedButton(onClick = { patientMenuOpen = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(exportPatient ?: "All patients", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = patientMenuOpen, onDismissRequest = { patientMenuOpen = false }) {
                            DropdownMenuItem(text = { Text(tr("All patients")) }, onClick = { exportPatient = null; patientMenuOpen = false })
                            patients.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { exportPatient = p; patientMenuOpen = false }) }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { exportDelta = !exportDelta },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("Only new since last export"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (AppSettings.getLastExportAt(context) == null) tr("No previous export yet — this sends everything")
                                else tr("Sends just what changed since last time"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = exportDelta, onCheckedChange = { exportDelta = it })
                    }

                    Text(tr("Date range (optional)"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = exportFrom, onValueChange = { exportFrom = it },
                            label = { Text(tr("From")) }, placeholder = { Text("2026-01-01") }, singleLine = true,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = exportTo, onValueChange = { exportTo = it },
                            label = { Text(tr("To")) }, placeholder = { Text("2026-12-31") }, singleLine = true,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                        )
                    }

                    transferResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { runExport() }, enabled = !transferBusy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            if (transferBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Export")) }
                        }
                        OutlinedButton(onClick = { portableImportLauncher.launch(arrayOf("*/*")) }, enabled = !transferBusy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            if (transferBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Import")) }
                        }
                    }
                }
            }

            // Merge / fix patient names
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tr("Fix / Merge Patient"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = tr("If a name was mis-read on a scan and one person shows up twice, merge the wrong name into the correct one. Moves all their reports, trends, reminders and history together."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Box {
                        OutlinedButton(onClick = { mergeMenuOpen = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(mergeFrom ?: "Select patient to fix", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = mergeMenuOpen, onDismissRequest = { mergeMenuOpen = false }) {
                            if (patients.isEmpty()) {
                                DropdownMenuItem(text = { Text(tr("No patients yet")) }, onClick = { mergeMenuOpen = false })
                            }
                            patients.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { mergeFrom = p; if (mergeTo.isBlank()) mergeTo = p; mergeMenuOpen = false }) }
                        }
                    }

                    OutlinedTextField(
                        value = mergeTo, onValueChange = { mergeTo = it },
                        label = { Text(tr("Correct name")) }, placeholder = { Text(tr("e.g. Rajesh Kumar")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                    )

                    mergeResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }

                    Button(
                        onClick = { runMerge() },
                        enabled = !transferBusy && mergeFrom != null && mergeTo.isNotBlank() && !mergeTo.trim().equals(mergeFrom, ignoreCase = true),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Merge"))
                    }
                }
            }

            // Reports saved while the AI analysis server was unavailable (daily quota, outage) —
            // their date/type/category are guesses, not read from the document. Only shown when
            // there's actually something to fix.
            if (degradedCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("Reports Needing Re-check"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = trFormat("%1\$d report(s) were saved while the AI analysis server was unavailable (e.g. daily quota reached), so their date and category are guesses instead of read from the document. Re-analyzing uses the images already stored — no need to re-scan them yourself.", degradedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (MaintenanceScheduler.fixDegradedBusy) {
                            Text(
                                trFormat(
                                    "Re-analyzing %1\$d of %2\$d…",
                                    MaintenanceScheduler.fixDegradedProgress.first,
                                    MaintenanceScheduler.fixDegradedProgress.second
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        MaintenanceScheduler.fixDegradedResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Button(
                            onClick = {
                                // Runs in a scope that outlives this screen — see MaintenanceScheduler.
                                MaintenanceScheduler.runFixDegraded(context) {
                                    degradedCount = LocalRepository.findDegradedReports(context).size
                                }
                            },
                            enabled = !MaintenanceScheduler.fixDegradedBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (MaintenanceScheduler.fixDegradedBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                            } else {
                                Text(trFormat("Re-analyze All (%1\$d)", degradedCount), color = MaterialTheme.colorScheme.onError)
                            }
                        }
                    }
                }
            }

            // Multi-page scan bundles that predate the chunk-size fix — a panel from the same
            // original document (e.g. an electrolytes page) may never have been saved at all.
            // Only shown when there's actually something to check.
            if (atRiskCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("Check for Missing Report Data"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = trFormat("%1\$d multi-page scan(s) were processed before a fix to how large documents are split for analysis — a panel from the same document (e.g. an electrolytes or PT/INR page) may be missing. This re-checks them using the pages already stored and adds anything that was missed; anything already saved is left alone.", atRiskCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (MaintenanceScheduler.recoverBusy) {
                            Text(
                                trFormat(
                                    "Checking %1\$d of %2\$d…",
                                    MaintenanceScheduler.recoverProgress.first,
                                    MaintenanceScheduler.recoverProgress.second
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        MaintenanceScheduler.recoverResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Button(
                            onClick = {
                                // Runs in a scope that outlives this screen — see MaintenanceScheduler.
                                MaintenanceScheduler.runRecoverMissingPanels(context) {
                                    atRiskCount = LocalRepository.findAtRiskBundles(context).size
                                }
                            },
                            enabled = !MaintenanceScheduler.recoverBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (MaintenanceScheduler.recoverBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                            } else {
                                Text(trFormat("Check Now (%1\$d)", atRiskCount), color = MaterialTheme.colorScheme.onError)
                            }
                        }
                    }
                }
            }

            // Backup & restore (export to Google Drive / OneDrive / any folder)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tr("Full Backup & Restore"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = tr("Export all your records (reports + images) as a single backup file. Choose your Google Drive or OneDrive folder in the picker to keep a cloud copy. Restoring REPLACES everything currently on this device."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // Optional — without a password, the backup file itself is the only thing
                    // protecting this data: anyone who gets a copy of it can open it. With one,
                    // the password is a second factor required at restore time too.
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { protectBackupWithPassword = !protectBackupWithPassword },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("Protect this backup with a password"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                tr("Without one, anyone who gets the backup file can open it"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = protectBackupWithPassword, onCheckedChange = { protectBackupWithPassword = it })
                    }
                    val passwordsMismatch = protectBackupWithPassword && backupPasswordConfirmInput.isNotEmpty() && backupPasswordInput != backupPasswordConfirmInput
                    if (protectBackupWithPassword) {
                        OutlinedTextField(
                            value = backupPasswordInput,
                            onValueChange = { backupPasswordInput = it },
                            label = { Text(tr("Backup password")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = backupPasswordConfirmInput,
                            onValueChange = { backupPasswordConfirmInput = it },
                            label = { Text(tr("Confirm password")) },
                            singleLine = true,
                            isError = passwordsMismatch,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (passwordsMismatch) {
                            Text(tr("Passwords don't match"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            tr("There's no way to reset this if you forget it — write it down somewhere safe. A lost backup password means that backup can never be restored."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    backupResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportLauncher.launch("medical-backup-$stamp.zip")
                            },
                            enabled = !protectBackupWithPassword || (backupPasswordInput.isNotBlank() && !passwordsMismatch),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(tr("Export Backup")) }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            enabled = !restoreBusy && !RestoreScheduler.isBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (restoreBusy || RestoreScheduler.isBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Restoring…"))
                            } else {
                                Text(tr("Restore"))
                            }
                        }
                    }
                    // Survives navigating away mid-restore — see RestoreScheduler doc comment.
                    RestoreScheduler.resultMessage?.let {
                        Text(tr(it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(text = tr("Auto Cloud Backup"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = tr("Pick a folder in Google Drive, OneDrive, or Dropbox. New backups are automatically synced there by the cloud app."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // These automatic backups are the ones that actually leave the device via
                    // cloud sync — the password toggle above only covers a manual Export tap,
                    // so without this, auto-synced backups stay unprotected even for a user who
                    // always protects their manual exports.
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            hasAutoBackupPassword = !hasAutoBackupPassword
                            if (!hasAutoBackupPassword) {
                                SecureKeyManager.setBackupPassword(context, null)
                                autoBackupPasswordInput = ""
                                autoBackupPasswordSaveMsg = null
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("Also protect automatic backups with a password"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                tr("Off by default — these sync to your cloud folder above without a password unless you set one here"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = hasAutoBackupPassword, onCheckedChange = { checked ->
                            hasAutoBackupPassword = checked
                            if (!checked) {
                                SecureKeyManager.setBackupPassword(context, null)
                                autoBackupPasswordInput = ""
                                autoBackupPasswordSaveMsg = null
                            }
                        })
                    }
                    if (hasAutoBackupPassword) {
                        OutlinedTextField(
                            value = autoBackupPasswordInput,
                            onValueChange = { autoBackupPasswordInput = it; autoBackupPasswordSaveMsg = null },
                            label = { Text(tr("Auto-backup password")) },
                            placeholder = { Text(tr("Leave blank to keep the current one")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    SecureKeyManager.setBackupPassword(context, autoBackupPasswordInput)
                                    autoBackupPasswordSaveMsg = "Saved — new automatic backups will use this password."
                                    autoBackupPasswordInput = ""
                                },
                                enabled = autoBackupPasswordInput.isNotBlank(),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text(tr("Save")) }
                            autoBackupPasswordSaveMsg?.let {
                                Text(tr(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            tr("There's no way to reset this if you forget it — write it down somewhere safe."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (cloudFolderLabel != null) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = cloudFolderLabel ?: "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                val statusText = when {
                                    syncing -> "Syncing…"
                                    pendingSyncCount > 0 -> "$pendingSyncCount backup(s) pending sync"
                                    else -> "All backups synced ✓"
                                }
                                Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        syncing = true
                                        withContext(Dispatchers.IO) { BackupSync.syncPending(context) }
                                        pendingSyncCount = BackupSync.pendingCount(context)
                                        syncing = false
                                    }
                                },
                                enabled = !syncing && pendingSyncCount > 0,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text(tr("Sync Now")) }
                            TextButton(
                                onClick = { SafCloudUploader.clearBackupFolder(context); cloudFolderLabel = null },
                                modifier = Modifier.weight(1f)
                            ) { Text(tr("Disconnect"), color = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) { Text(tr("Choose Backup Folder")) }
                    }
                }
            }

            // Duplicate cleanup
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tr("Remove Duplicate Reports"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = tr("Finds reports that were saved more than once (same patient, date, and content) and removes the extra copies. The original of each report is always kept. New scans are checked automatically; this cleans up older duplicates."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    dupResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    OutlinedButton(
                        onClick = {
                            dupScanning = true
                            dupResult = null
                            coroutineScope.launch {
                                val found = runCatching { LocalRepository.findDuplicateReports(context) }.getOrDefault(emptyList())
                                dupScanning = false
                                if (found.isEmpty()) dupResult = "No duplicate reports found."
                                else { dupCandidates = found; showDupDialog = true }
                            }
                        },
                        enabled = !dupScanning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(if (dupScanning) "Scanning…" else "Scan for Duplicates") }
                }
            }

            // Danger zone — delete everything
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tr("Delete All Data"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                    Text(text = tr("Permanently removes every report, medicine, pending test and image. This cannot be undone."), style = MaterialTheme.typography.bodySmall, color = Color(0xFFB71C1C))
                    deleteAllResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { showDeleteAllDialog = true },
                        enabled = !deletingAll,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) {
                        if (deletingAll) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(tr("Delete Everything"), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(tr("App Theme"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    var currentThemeMode by remember { mutableStateOf(AppSettings.getThemeMode(context)) }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = currentThemeMode == AppSettings.THEME_LIGHT,
                            onClick = { currentThemeMode = AppSettings.THEME_LIGHT; AppSettings.setThemeMode(context, AppSettings.THEME_LIGHT) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) { Text(tr("Light")) }
                        SegmentedButton(
                            selected = currentThemeMode == AppSettings.THEME_DARK,
                            onClick = { currentThemeMode = AppSettings.THEME_DARK; AppSettings.setThemeMode(context, AppSettings.THEME_DARK) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) { Text(tr("Dark")) }
                        SegmentedButton(
                            selected = currentThemeMode == AppSettings.THEME_SYSTEM,
                            onClick = { currentThemeMode = AppSettings.THEME_SYSTEM; AppSettings.setThemeMode(context, AppSettings.THEME_SYSTEM) },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) { Text(tr("System")) }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(tr("Lab Units"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        tr("The unit every trend chart standardises readings to. Reports in a different " +
                            "unit are converted automatically; each report still shows its original value."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    var currentUnitSystem by remember { mutableStateOf(AppSettings.getUnitSystem(context)) }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = currentUnitSystem == AppSettings.UNIT_SYSTEM_CONVENTIONAL,
                            onClick = { currentUnitSystem = AppSettings.UNIT_SYSTEM_CONVENTIONAL; AppSettings.setUnitSystem(context, AppSettings.UNIT_SYSTEM_CONVENTIONAL) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text(tr("Indian (mg/dL)")) }
                        SegmentedButton(
                            selected = currentUnitSystem == AppSettings.UNIT_SYSTEM_SI,
                            onClick = { currentUnitSystem = AppSettings.UNIT_SYSTEM_SI; AppSettings.setUnitSystem(context, AppSettings.UNIT_SYSTEM_SI) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text(tr("International (SI)")) }
                    }
                }
            }

            // Local database encryption — the storage layer's own posture, independent of any
            // one account (BYOK key, password, ...), so it lives here rather than on Profile.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(tr("Local Database Encryption"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE8F5E9)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(text = tr("AES-SQLCipher"), color = Color(0xFF2E7D32), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (!SecureKeyManager.isStorageHardwareBacked()) {
                        Text(
                            tr("This device's secure hardware storage is unavailable, so your local " +
                                "records key and linked-email credentials are stored without " +
                                "hardware-backed encryption. Your data still isn't sent anywhere " +
                                "insecurely, but this device offers weaker protection than usual."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Demo data — lets a brand-new user see Records/Trends/Reminders/Doctor Brief
            // populated without scanning anything. Same entry point as the onboarding carousel's
            // "Try Demo".
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var demoDataPresent by remember { mutableStateOf<Boolean?>(null) }
                    var isTogglingDemo by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { demoDataPresent = com.healthdecoder.app.local.DemoDataSeeder.isDemoDataPresent(context) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (demoDataPresent == true) "Demo Data Active" else "Try Demo Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (demoDataPresent == true)
                            "The sample patient \"${com.healthdecoder.app.local.DemoDataSeeder.DEMO_PATIENT_NAME}\" is visible in your family list, with example reports, reminders and an appointment."
                        else
                            "Adds a sample patient with example reports, reminders and an appointment, so you can explore the app before scanning anything real.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (demoDataPresent == true) {
                        OutlinedButton(
                            onClick = {
                                isTogglingDemo = true
                                coroutineScope.launch {
                                    runCatching { com.healthdecoder.app.local.DemoDataSeeder.removeDemoData(context) }
                                    demoDataPresent = com.healthdecoder.app.local.DemoDataSeeder.isDemoDataPresent(context)
                                    isTogglingDemo = false
                                }
                            },
                            enabled = !isTogglingDemo,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isTogglingDemo) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(tr("Remove Demo Data"))
                        }
                    } else {
                        Button(
                            onClick = {
                                isTogglingDemo = true
                                coroutineScope.launch {
                                    runCatching { com.healthdecoder.app.local.DemoDataSeeder.seedDemoData(context) }
                                    demoDataPresent = com.healthdecoder.app.local.DemoDataSeeder.isDemoDataPresent(context)
                                    isTogglingDemo = false
                                }
                            },
                            enabled = !isTogglingDemo,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isTogglingDemo) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text(tr("Add Demo Data"))
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(imageVector = Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(tr("Help Fund This App"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        var researchConsent by remember { mutableStateOf(AppSettings.isResearchDataSharingConsented(context)) }
                        Switch(checked = researchConsent, onCheckedChange = { checked -> researchConsent = checked; AppSettings.setResearchDataSharingConsented(context, checked) })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        tr("Why: Health Decoder stays free by keeping costs low, not by selling your records. " +
                            "If you opt in here, only your age and sex — never your name, reports, or exact location — " +
                            "would be shared in aggregate with medical research institutes, to help fund keeping the app " +
                            "free for every family instead of running ads or charging a subscription."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        tr("This program hasn't launched yet — turning this on today only saves your preference. " +
                            "Nothing is sent anywhere until a real data-sharing pipeline exists, and you can change " +
                            "your answer here at any time."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Email Integration Card — hidden while GMAIL_SYNC_ENABLED is off: this was
            // built and tested against one specific Gmail account, and its Gmail API
            // cost/quota behavior for arbitrary public users hasn't been verified.
            if (FeatureFlags.GMAIL_SYNC_ENABLED) {
                var showEmailIntegration by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showEmailIntegration = !showEmailIntegration },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(tr("Email Report Scanner"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    val linked = AppSettings.getLinkedEmail(context)
                                    Text(
                                        text = if (linked != null) "Linked to $linked" else "Not connected",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (linked != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { showEmailIntegration = !showEmailIntegration }) {
                                Icon(imageVector = if (showEmailIntegration) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                            }
                        }

                        if (showEmailIntegration) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            var emailConsent by remember { mutableStateOf(AppSettings.isEmailConsentGranted(context)) }
                            var scanHour by remember { mutableStateOf(AppSettings.getEmailScanHour(context)) }
                            var scanMinute by remember { mutableStateOf(AppSettings.getEmailScanMinute(context)) }
                            var showScanTimePicker by remember { mutableStateOf(false) }
                            var searchPromptInput by remember { mutableStateOf(AppSettings.getEmailSearchPrompt(context)) }

                            fun rescheduleDailyScan() {
                                com.healthdecoder.app.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tr("Auto-scan Inbox daily"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(tr("Checks for medical report attachments once a day"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = emailConsent,
                                    onCheckedChange = { checked ->
                                        emailConsent = checked
                                        AppSettings.setEmailConsentGranted(context, checked)
                                        if (checked) rescheduleDailyScan() else com.healthdecoder.app.reminder.EmailScanReminderManager.cancel(context)
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = emailConsent) { showScanTimePicker = true },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("Scan time"), style = MaterialTheme.typography.bodyMedium, color = if (emailConsent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    String.format("%02d:%02d", scanHour, scanMinute),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (emailConsent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (showScanTimePicker) {
                                val timeState = rememberTimePickerState(initialHour = scanHour, initialMinute = scanMinute)
                                AlertDialog(
                                    onDismissRequest = { showScanTimePicker = false },
                                    text = { TimePicker(state = timeState) },
                                    confirmButton = {
                                        Button(onClick = {
                                            scanHour = timeState.hour
                                            scanMinute = timeState.minute
                                            AppSettings.setEmailScanTime(context, scanHour, scanMinute)
                                            rescheduleDailyScan()
                                            showScanTimePicker = false
                                        }) { Text(tr("OK")) }
                                    },
                                    dismissButton = { TextButton(onClick = { showScanTimePicker = false }) { Text(tr("Cancel")) } }
                                )
                            }

                            Text(tr("Hospital Search Prompt (Optional)"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = searchPromptInput,
                                onValueChange = { searchPromptInput = it },
                                label = { Text(tr("e.g. Apollo, Metropolis, Fortis")) },
                                placeholder = { Text(tr("Leave blank to search all reports")) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            Text(tr("Translates this intent using AI to target specific lab emails."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))

                            TextButton(
                                onClick = {
                                    AppSettings.setEmailSearchPrompt(context, searchPromptInput)
                                    val request = androidx.work.OneTimeWorkRequestBuilder<com.healthdecoder.app.local.EmailScanWorker>()
                                        .setInputData(
                                            androidx.work.Data.Builder()
                                                .putInt(com.healthdecoder.app.local.EmailScanWorker.KEY_LOOKBACK_DAYS, 2)
                                                .build()
                                        )
                                        .build()
                                    val workManager = androidx.work.WorkManager.getInstance(context)
                                    workManager.enqueueUniqueWork("ManualEmailScanWork", androidx.work.ExistingWorkPolicy.REPLACE, request)
                                    android.widget.Toast.makeText(context, scanningInboxToast, android.widget.Toast.LENGTH_SHORT).show()
                                    coroutineScope.launch {
                                        val info = workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
                                        val message = when (info?.state) {
                                            androidx.work.WorkInfo.State.SUCCEEDED -> {
                                                val count = info.outputData.getInt(com.healthdecoder.app.local.EmailScanWorker.KEY_FOUND_COUNT, 0)
                                                if (count > 0) "$foundNewReportsToastPrefix $count $foundNewReportsToastSuffix" else noNewReportsInLast2DaysToast
                                            }
                                            else -> scanFailedCheckSettingsToast
                                        }
                                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = !AppSettings.getLinkedEmail(context).isNullOrBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(tr("Scan Now (last 2 days)")) }

                            TextButton(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        LocalStore.getDatabase(context).processedEmailDao().deleteAll()
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, emailScanHistoryClearedToast, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !AppSettings.getLinkedEmail(context).isNullOrBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(tr("Clear Email Scan History"), color = MaterialTheme.colorScheme.error) }

                            Spacer(modifier = Modifier.height(8.dp))

                            var emailType by remember { mutableStateOf(AppSettings.getLinkedEmailType(context) ?: "gmail") }
                            var userEmailInput by remember { mutableStateOf(AppSettings.getLinkedEmail(context) ?: "") }
                            var imapHostInput by remember { mutableStateOf(AppSettings.getImapHost(context)) }
                            var imapPortInput by remember { mutableStateOf(AppSettings.getImapPort(context).toString()) }
                            var imapPasswordInput by remember { mutableStateOf(SecureKeyManager.getImapPassword(context) ?: "") }

                            Text(tr("Email Provider"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(selected = emailType == "gmail", onClick = { emailType = "gmail" }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text(tr("Gmail (OAuth)")) }
                                SegmentedButton(selected = emailType == "imap", onClick = { emailType = "imap" }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text(tr("Other (IMAP)")) }
                            }

                            if (emailType == "gmail") {
                                val linkedEmail = AppSettings.getLinkedEmail(context)
                                val hasLinkedGmail = !linkedEmail.isNullOrBlank() && AppSettings.getLinkedEmailType(context) == "gmail"
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = if (hasLinkedGmail) "Linked Gmail Account: $linkedEmail" else "No Google Account Linked",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                val token = AppSettings.getAuthToken(context) ?: ""
                                                // Single-use correlator so the medicalscanner://oauth2-link
                                                // redirect that comes back is only trusted if it's the one
                                                // this exact tap requested — see Navigation.kt.
                                                val nonce = java.util.UUID.randomUUID().toString()
                                                AppSettings.setPendingOAuthNonce(context, nonce)
                                                val url = com.healthdecoder.app.network.NetworkModule.getFullImageUrl(context, "api/auth/google?state=link|$token|$nonce")
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                context.startActivity(intent)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) { Text(if (hasLinkedGmail) "Re-link Google Account" else "Link Google Account") }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = userEmailInput, onValueChange = { userEmailInput = it },
                                    label = { Text(tr("Email Address")) }, modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                                )
                                OutlinedTextField(
                                    value = imapHostInput, onValueChange = { imapHostInput = it },
                                    label = { Text(tr("IMAP Host")) }, placeholder = { Text("imap.mail.yahoo.com") },
                                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = imapPortInput, onValueChange = { imapPortInput = it },
                                    label = { Text(tr("IMAP Port")) }, modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = imapPasswordInput, onValueChange = { imapPasswordInput = it },
                                    label = { Text(tr("App Password / Password")) }, placeholder = { Text(tr("Secure App Password")) },
                                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                                    visualTransformation = PasswordVisualTransformation()
                                )
                                Text(tr("Note: Gmail, Yahoo, and Outlook require you to generate an 'App Password' from your account security settings to log in via IMAP."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (emailType == "gmail") {
                                        val hasLinkedGmail = !AppSettings.getLinkedEmail(context).isNullOrBlank() && AppSettings.getLinkedEmailType(context) == "gmail"
                                        if (!hasLinkedGmail) {
                                            android.widget.Toast.makeText(context, pleaseLinkGoogleAccountToast, android.widget.Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        AppSettings.setEmailSearchPrompt(context, searchPromptInput)
                                        if (emailConsent) com.healthdecoder.app.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                                        else com.healthdecoder.app.reminder.EmailScanReminderManager.cancel(context)
                                        android.widget.Toast.makeText(context, emailSettingsSavedToast, android.widget.Toast.LENGTH_SHORT).show()
                                        showEmailIntegration = false
                                    } else {
                                        if (userEmailInput.isNotBlank()) {
                                            AppSettings.setLinkedEmail(context, userEmailInput)
                                            AppSettings.setLinkedEmailType(context, emailType)
                                            AppSettings.setEmailSearchPrompt(context, searchPromptInput)
                                            AppSettings.setImapHost(context, imapHostInput)
                                            AppSettings.setImapPort(context, imapPortInput.toIntOrNull() ?: 993)
                                            SecureKeyManager.setImapPassword(context, imapPasswordInput)
                                            if (emailConsent) com.healthdecoder.app.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                                            else com.healthdecoder.app.reminder.EmailScanReminderManager.cancel(context)
                                            android.widget.Toast.makeText(context, emailSettingsSavedToast, android.widget.Toast.LENGTH_SHORT).show()
                                            showEmailIntegration = false
                                        } else {
                                            android.widget.Toast.makeText(context, pleaseEnterEmailAddressToast, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text(tr("Save Settings")) }
                        }
                    }
                }
            }
        }
    }

    pendingRestoreFile?.let { tmp ->
        AlertDialog(
            onDismissRequest = { if (!RestoreScheduler.isBusy) { tmp.delete(); pendingRestoreFile = null } },
            title = { Text(tr("Enter Backup Password"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("This backup is password-protected. Enter the password it was created with."))
                    OutlinedTextField(
                        value = restorePasswordInput,
                        onValueChange = { restorePasswordInput = it; restorePasswordError = null },
                        label = { Text(tr("Password")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !RestoreScheduler.isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    restorePasswordError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !RestoreScheduler.isBusy && restorePasswordInput.isNotBlank(),
                    onClick = {
                        // Runs in RestoreScheduler's own scope — survives navigating away from
                        // this dialog/screen while it's in progress, same as the no-password path.
                        RestoreScheduler.start(context, tmp, restorePasswordInput) { outcome ->
                            if (outcome == BackupManager.RestoreOutcome.WRONG_PASSWORD) {
                                restorePasswordError = "Incorrect password — try again."
                            } else {
                                pendingRestoreFile = null
                            }
                        }
                    }
                ) {
                    if (RestoreScheduler.isBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(tr("Restore"))
                }
            },
            dismissButton = {
                TextButton(onClick = { tmp.delete(); pendingRestoreFile = null }, enabled = !RestoreScheduler.isBusy) { Text(tr("Cancel")) }
            }
        )
    }

    if (showDupDialog) {
        AlertDialog(
            onDismissRequest = { showDupDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Remove ${dupCandidates.size} duplicate report${if (dupCandidates.size == 1) "" else "s"}?") },
            text = {
                val preview = dupCandidates.take(5).joinToString("\n") {
                    "• ${it.reportType ?: "Report"} — ${it.patientName ?: "Unknown"} (${it.reportDate ?: "no date"})"
                }
                val more = if (dupCandidates.size > 5) "\n…and ${dupCandidates.size - 5} more" else ""
                Text("These are extra copies of reports you already have. The original of each is kept.\n\n$preview$more")
            },
            confirmButton = {
                Button(onClick = {
                    showDupDialog = false
                    coroutineScope.launch {
                        val removed = runCatching { LocalRepository.deleteDuplicateReports(context) }.getOrDefault(0)
                        dupResult = "Removed $removed duplicate report${if (removed == 1) "" else "s"}."
                        dupCandidates = emptyList()
                    }
                }) { Text(tr("Remove Duplicates")) }
            },
            dismissButton = { TextButton(onClick = { showDupDialog = false }) { Text(tr("Cancel")) } }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFC62828)) },
            title = { Text(tr("Delete everything?")) },
            text = { Text(tr("This permanently deletes ALL reports, medicines, pending tests and images. This cannot be undone.")) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllDialog = false
                        coroutineScope.launch {
                            deletingAll = true
                            deleteAllResult = null
                            runCatching {
                                com.healthdecoder.app.reminder.MedicineReminderManager.cancelAll(context)
                                com.healthdecoder.app.reminder.MedicineScheduleStore.clearAll(context)
                                val appointmentsList = com.healthdecoder.app.reminder.AppointmentStore.loadAll(context)
                                appointmentsList.forEach { com.healthdecoder.app.reminder.AppointmentReminderManager.cancel(context, it.id) }
                                com.healthdecoder.app.reminder.AppointmentStore.clearAll(context)
                            }
                            runCatching { com.healthdecoder.app.local.LocalRepository.clearAllData(context) }
                            deletingAll = false
                            deleteAllResult = "All data deleted."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text(tr("Delete Everything")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }, enabled = !deletingAll) { Text(tr("Cancel")) }
            }
        )
    }
}
