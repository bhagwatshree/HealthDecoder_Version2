package com.healthdecoder.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.*
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.local.LocalStore
import com.healthdecoder.app.local.BackgroundScanScheduler
import com.healthdecoder.app.util.FileImportUtil
import com.healthdecoder.app.util.ImageUtil
import com.healthdecoder.app.local.SecureKeyManager
import com.healthdecoder.app.network.NetworkModule
import com.healthdecoder.app.model.ProcessedEmail

import java.util.Properties
import java.util.Calendar
import javax.mail.*
import javax.mail.internet.MimeMessage
import javax.mail.search.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    initialImagePath: String? = null,
    onNavigateToDetail: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Memory guard; pages are sent to the AI chunk-by-chunk (see AppSettings scan tuning).
    val maxPagesPerScan = remember { AppSettings.getScanMaxPages(context) }
    val pages = remember { mutableStateListOf<Uri>() } // page images to display + analyze
    // Original imported files (uri, name, mime) preserved so the user can download them later.
    val sources = remember { mutableStateListOf<Triple<Uri, String, String>>() }
    var docText by remember { mutableStateOf("") } // text extracted from Word/text documents
    var uploadingState by remember { mutableStateOf<String?>(null) } // null, "uploading", "ocr", "saving", "error"
    var importingFiles by remember { mutableStateOf(false) }
    var showSizeWarningDialog by remember { mutableStateOf(false) }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var warningSizeMb by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf("") }
    var showQrScanner by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }
    var showSetupAlert by remember { mutableStateOf(false) }
    var showEmailResultDialog by remember { mutableStateOf(false) }
    var emailResultReportName by remember { mutableStateOf("") }
    var emailResultLocalPath by remember { mutableStateOf("") }
    var emailResultMessageId by remember { mutableStateOf("") }
    // Reports EmailScanWorker (background/Scan Now) downloaded but nobody has reviewed yet —
    // shown as a list below (see "Found in email" card) so the user analyzes them one at a
    // time, at their own pace, rather than an auto-popping dialog.
    var pendingEmailQueue by remember { mutableStateOf<List<ProcessedEmail>>(emptyList()) }

    // Hoisted here because tr() is @Composable and can't be called from the plain local
    // functions / listener lambdas below that assign these into errorMessage/localOcrText.
    val failedLocalOcrError = tr("Failed to run local OCR.")
    val failedEmailInboxError = tr("Failed to connect and read email inbox. Please verify settings.")
    val couldntReadSelectedFilesError = tr("Couldn't read the selected file(s). Try an image, PDF, or Word document.")
    val pageLimitSkippedText = tr("Page limit is") to tr("per scan — extra pages were skipped. Analyze these first, then scan the rest.")
    val failedImportSelectedFilesError = tr("Failed to import selected files.")
    val qrNoReportLinkError = tr("That QR code doesn't contain a report link.")
    val qrFetchFailedError = tr("Couldn't fetch the report from that QR code.")
    val qrOpenedWebPageError = tr("That QR opened a web page, not a direct file. Opening it in your browser — " +
        "download the report there, then use \"From Device\" to import it.")
    val cameraInitFailedPrefix = tr("Camera initialization failed:")
    val cameraPermissionNeededError = tr("Camera permission is needed to take a photo. Please allow it, or use 'From Gallery' instead.")
    val reportAddedToPreviewText = tr("Report added to scan preview.")
    val scanStartedText = tr("Scan started — watch the progress above.")
    val documentTextAttachedPrefix = tr("Document text attached")
    val documentTextAttachedSuffix = tr("chars). It will be analyzed with any images.")
    val pagesSelectedPrefix = tr("page(s) selected. Add more pages of the SAME report, then analyze.")
    val noNewReportsFoundText = tr("No new reports found (already scanned or none available).")
    val uploadedOpenLaterText = tr("Uploaded. Open it later to analyze when you're ready.")

    // ON_RESUME (not LaunchedEffect(Unit)) so this re-checks every time the screen comes back
    // into view — e.g. after "Scan Now" finds something while the user was on SettingsScreen —
    // not just the first time ScanScreen is composed.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        coroutineScope.launch {
            val pending = withContext(Dispatchers.IO) {
                LocalStore.getDatabase(context).processedEmailDao().getPending()
            }
            if (pending.isNotEmpty()) {
                pendingEmailQueue = pending
            }
        }
    }


    // ML Kit Local OCR results
    

    var localOcrText by remember { mutableStateOf("") }
    var localOcrRunning by remember { mutableStateOf(false) }
    var autoUseSarvam by remember { mutableStateOf(false) }

    var patientName by remember { mutableStateOf("") } // optional override before analyzing
    var selectedScanType by remember { mutableStateOf("prescription") } // "prescription" or "report"
    var selectedReportCategory by remember { mutableStateOf("blood_test") } // "blood_test", "sonography", "2d_echo", "xray", "other"

    // Setup camera temp file URI
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }

    // Run local Google ML Kit text recognition to check script and computerized text
    val runLocalOcr = { uri: Uri ->
        coroutineScope.launch {
            localOcrRunning = true
            localOcrText = ""
            autoUseSarvam = false
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val inputImage = InputImage.fromFilePath(context, uri)
                
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val recognizedText = visionText.text
                        localOcrText = recognizedText
                        
                        // Check if text has any Indic/regional characters (Devanagari, Gurmukhi, Telugu, etc.)
                        val hasIndic = recognizedText.any { it.code in 0x0900..0x0D7F }
                        
                        // If it contains Indic characters, or if it is empty/extremely short (Latin scanner couldn't read it),
                        // route to Sarvam AI. Otherwise, use Google Scanner + Gemini.
                        val isEnglish = !hasIndic && recognizedText.trim().length >= 15
                        
                        autoUseSarvam = !isEnglish
                        localOcrRunning = false
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        localOcrText = failedLocalOcrError
                        localOcrRunning = false
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                localOcrRunning = false
            }
        }
    }

    // Helper to estimate picked file size in MB
    fun getUriSizeInMb(context: Context, uri: Uri): Float {
        var bytes: Long = 0
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                bytes = it.length
            }
        } catch (e: Exception) {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        bytes = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {}
        }
        return bytes / (1024f * 1024f)
    }

    fun triggerEmailScan() {
        importingFiles = true
        errorMessage = ""
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val email = AppSettings.getLinkedEmail(context)
                val type = AppSettings.getLinkedEmailType(context)
                if (email.isNullOrBlank() || type.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        importingFiles = false
                        showSetupAlert = true
                    }
                    return@withContext
                }

                val db = LocalStore.getDatabase(context)
                val dao = db.processedEmailDao()

                fun reportResult(fileName: String?, filePath: String?, msgId: String?) {
                    importingFiles = false
                    if (filePath != null && fileName != null && msgId != null) {
                        emailResultReportName = fileName
                        emailResultLocalPath = filePath
                        emailResultMessageId = msgId
                        showEmailResultDialog = true
                    } else {
                        android.widget.Toast.makeText(context, noNewReportsFoundText, android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                try {
                    if (type == "gmail") {
                        // Uses the Gmail REST API (gmail.readonly scope), not IMAP — the full
                        // mailbox IMAP scope (https://mail.google.com/) needs a paid Google
                        // security assessment to verify for production. See EmailScanWorker.kt.
                        val accessToken = try {
                            val token = NetworkModule.getApi(context).getGoogleAccessToken().access_token
                            SecureKeyManager.setEmailToken(context, token)
                            token
                        } catch (e: Exception) {
                            SecureKeyManager.getEmailToken(context)
                        }
                        if (accessToken.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                importingFiles = false
                                showSetupAlert = true
                            }
                            return@withContext
                        }

                        val cal = Calendar.getInstance()
                        cal.add(Calendar.DAY_OF_MONTH, -2)
                        val afterClause = "after:${java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(cal.time)}"
                        val keywords = listOf("report", "lab", "diagnostic", "billing", "test", "health", "prescription", "invoice", "medical")
                        val subjectClause = "(" + keywords.joinToString(" OR ") { "subject:$it" } + ")"
                        val customSearch = AppSettings.getEmailSearchPrompt(context)
                        val customClause = if (customSearch.isNotBlank()) " subject:$customSearch" else ""
                        val query = "$afterClause $subjectClause has:attachment filename:pdf$customClause"

                        var foundFileName: String? = null
                        var foundFilePath: String? = null
                        var foundMsgId: String? = null

                        for (messageId in com.healthdecoder.app.network.GmailApiClient.searchMessageIds(accessToken, query)) {
                            if (dao.exists(messageId)) continue
                            val attachment = com.healthdecoder.app.network.GmailApiClient.findPdfAttachment(accessToken, messageId) ?: continue
                            val bytes = com.healthdecoder.app.network.GmailApiClient.downloadAttachment(accessToken, messageId, attachment.attachmentId)

                            val emailImportsDir = File(context.cacheDir, "email_imports").apply { mkdirs() }
                            val tempFile = File(emailImportsDir, "imported_${System.currentTimeMillis()}_${attachment.fileName}")
                            tempFile.writeBytes(bytes)

                            foundFileName = attachment.fileName
                            foundFilePath = tempFile.absolutePath
                            foundMsgId = messageId
                            break
                        }

                        withContext(Dispatchers.Main) { reportResult(foundFileName, foundFilePath, foundMsgId) }
                        return@withContext
                    }

                    // "Other (IMAP)" path — a plain IMAP mailbox with a host/port/app-password.
                    val password = SecureKeyManager.getImapPassword(context)
                    val host = AppSettings.getImapHost(context)
                    val port = AppSettings.getImapPort(context)

                    if (password.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            importingFiles = false
                            showSetupAlert = true
                        }
                        return@withContext
                    }

                    val props = Properties()
                    props.setProperty("mail.store.protocol", "imaps")
                    props.setProperty("mail.imaps.host", host)
                    props.setProperty("mail.imaps.port", port.toString())
                    props.setProperty("mail.imaps.ssl.enable", "true")
                    props.setProperty("mail.imaps.timeout", "8000")
                    props.setProperty("mail.imaps.connectiontimeout", "8000")

                    val session = Session.getInstance(props)
                    val store = session.getStore("imaps")
                    store.connect(host, port, email, password)

                    val inbox = store.getFolder("INBOX")
                    inbox.open(Folder.READ_ONLY)

                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_MONTH, -2)
                    val twoDaysAgo = cal.time
                    val dateTerm = ReceivedDateTerm(ComparisonTerm.GE, twoDaysAgo)

                    val keywords = listOf("report", "lab", "diagnostic", "billing", "test", "health", "prescription", "invoice", "medical")
                    val subjectTerms = keywords.map { SubjectTerm(it) }.toTypedArray()
                    val subjectOrTerm = OrTerm(subjectTerms)

                    val customSearch = AppSettings.getEmailSearchPrompt(context)
                    val finalTerm = if (customSearch.isNotBlank()) {
                        val customTerm = OrTerm(SubjectTerm(customSearch), SubjectTerm(customSearch.lowercase()))
                        AndTerm(dateTerm, AndTerm(subjectOrTerm, customTerm))
                    } else {
                        AndTerm(dateTerm, subjectOrTerm)
                    }

                    val messages = inbox.search(finalTerm)

                    var foundFileName: String? = null
                    var foundFilePath: String? = null
                    var foundMsgId: String? = null

                    for (msg in messages) {
                        if (msg !is MimeMessage) continue
                        val messageId = msg.messageID ?: "${msg.subject}_${msg.sentDate?.time}"
                        if (dao.exists(messageId)) continue

                        val content = msg.content
                        if (content is Multipart) {
                            for (i in 0 until content.count) {
                                val part = content.getBodyPart(i)
                                if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || !part.fileName.isNullOrBlank()) {
                                    val fileName = part.fileName ?: "report.pdf"
                                    if (fileName.lowercase().endsWith(".pdf")) {
                                        val emailImportsDir = File(context.cacheDir, "email_imports").apply { mkdirs() }
                                        val tempFile = File(emailImportsDir, "imported_${System.currentTimeMillis()}_$fileName")
                                        part.inputStream.use { input ->
                                            tempFile.outputStream().use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                        foundFileName = fileName
                                        foundFilePath = tempFile.absolutePath
                                        foundMsgId = messageId
                                        break
                                    }
                                }
                            }
                        }
                        if (foundFilePath != null) break
                    }

                    inbox.close(false)
                    store.close()

                    withContext(Dispatchers.Main) { reportResult(foundFileName, foundFilePath, foundMsgId) }

                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        importingFiles = false
                        errorMessage = failedEmailInboxError
                    }
                }
            }
        }
    }

    // Handles document layout rendering and file bytes loading
    fun importSelectedFiles(uris: List<Uri>) {
        importingFiles = true
        errorMessage = ""
        coroutineScope.launch {
            try {
                val wasEmpty = pages.isEmpty()
                val imported = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val cachedSource = FileImportUtil.cacheImage(context, uri) ?: uri
                        Triple(cachedSource, FileImportUtil.displayName(context, uri), FileImportUtil.mimeOf(context, uri)) to
                            FileImportUtil.importFile(context, uri)
                    }
                }
                val newImages = imported.flatMap { it.second.images }
                val newText = imported.joinToString("\n\n") { it.second.text }.trim()
                if (newImages.isEmpty() && newText.isBlank()) {
                    errorMessage = couldntReadSelectedFilesError
                } else {
                    val room = maxPagesPerScan - pages.size
                    errorMessage = if (newImages.size > room)
                        "${pageLimitSkippedText.first} $maxPagesPerScan ${pageLimitSkippedText.second}"
                    else ""
                    if (newImages.isNotEmpty() && room > 0) {
                        pages.addAll(newImages.take(room))
                        if (wasEmpty) runLocalOcr(pages.first())
                    }
                    if (newText.isNotBlank()) docText = (docText + "\n\n" + newText).trim()
                    imported.forEach { (meta, _) -> sources.add(meta) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = failedImportSelectedFilesError
            } finally {
                importingFiles = false
            }
        }
    }

    // Handles a decoded QR payload: fetches the linked file (if it's a direct image/PDF) and
    // feeds it through the same import pipeline as a Gallery/File pick; falls back to opening
    // it in the browser when it's a portal page rather than a direct file link.
    fun handleQrResult(raw: String) {
        showQrScanner = false
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            errorMessage = qrNoReportLinkError
            return
        }
        coroutineScope.launch {
            importingFiles = true
            errorMessage = ""
            val fetched = try {
                withContext(Dispatchers.IO) { fetchQrLinkedFile(context, raw) }
            } catch (e: Exception) {
                e.printStackTrace()
                importingFiles = false
                errorMessage = qrFetchFailedError
                return@launch
            }
            importingFiles = false
            when (fetched) {
                // importSelectedFiles manages its own importingFiles/errorMessage from here.
                is QrFetchResult.ImportableFile -> importSelectedFiles(listOf(fetched.uri))
                is QrFetchResult.WebPage -> {
                    errorMessage = qrOpenedWebPageError
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw))) }
                }
                is QrFetchResult.Failed -> errorMessage = fetched.message
            }
        }
    }

    // Launcher for picking one or more images from gallery (multi-page report)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                val totalSize = uris.sumOf { getUriSizeInMb(context, it).toDouble() }.toFloat()
                if (totalSize > 10.0f) {
                    pendingUris = uris
                    warningSizeMb = totalSize
                    showSizeWarningDialog = true
                } else {
                    importSelectedFiles(uris)
                }
            }
        }
    )

    // Launcher for picking files from ANY folder (Downloads, Documents, Drive…) incl. PDFs
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                val totalSize = uris.sumOf { getUriSizeInMb(context, it).toDouble() }.toFloat()
                if (totalSize > 10.0f) {
                    pendingUris = uris
                    warningSizeMb = totalSize
                    showSizeWarningDialog = true
                } else {
                    importSelectedFiles(uris)
                }
            }
        }
    )

    // Launcher for capturing a page from camera (can be tapped again to add more pages)
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val captured = cameraTempUri
            if (success && captured != null) {
                val wasEmpty = pages.isEmpty()
                pages.add(captured)
                sources.add(Triple(captured, "Photo_${sources.size + 1}.jpg", "image/jpeg"))
                errorMessage = ""
                if (wasEmpty) runLocalOcr(captured)
            }
        }
    )

    // Helper function to create temporary URI for camera
    fun createCameraTempUri(): Uri {
        val cacheDir = context.cacheDir
        val tempFile = File.createTempFile("scan_capture_", ".jpg", cacheDir).apply {
            createNewFile()
            deleteOnExit()
        }
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, tempFile)
    }

    // Actually opens the camera (assumes permission already granted)
    fun launchCamera() {
        try {
            val uri = createCameraTempUri()
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            errorMessage = "$cameraInitFailedPrefix ${e.localizedMessage}"
        }
    }

    // Runtime CAMERA permission request (required because CAMERA is declared in the manifest)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                launchCamera()
            } else {
                errorMessage = cameraPermissionNeededError
            }
        }
    )

    // Checks permission first, requesting it if needed, then opens the camera
    fun onTakePhotoClicked() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(initialImagePath) {
        if (initialImagePath != null) {
            importSelectedFiles(listOf(Uri.fromFile(File(initialImagePath))))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TopBarLogo()
                        Text(tr("Scan Document"), fontWeight = FontWeight.Bold)
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
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Live status of any scan/upload started from here (stays visible until the user
                // dismisses it), so they can watch it finish without being navigated away.
                BackgroundScanProgressBar(onNavigateToDetail = onNavigateToDetail)

                // Image preview or selection placeholder — tapping it when empty opens the device file picker
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (pages.isEmpty() && !importingFiles) Modifier.heightIn(min = 120.dp)
                            else Modifier.height(300.dp)
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .then(
                            if (pages.isEmpty() && !importingFiles) Modifier.clickable {
                                // Same Drive/cloud-provider MIME-reporting gotcha as backup restore
                                // (see IPConfigScreen's importLauncher): a strict allowlist here
                                // greys out or hides files whose provider doesn't report one of
                                // these exact types. "*/*" is safe — importSelectedFiles already
                                // rejects anything FileImportUtil can't actually read.
                                fileLauncher.launch(arrayOf("*/*"))
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (importingFiles) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = tr("Importing files..."),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = tr("Rendering PDF pages and importing documents. This takes a brief moment."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (pages.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            itemsIndexed(pages) { index, uri ->
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxHeight()
                                        .aspectRatio(0.72f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.06f))
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(model = uri),
                                        contentDescription = "${tr("Page")} ${index + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                    Box(
                                        Modifier.align(Alignment.BottomStart).padding(6.dp)
                                            .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text("${tr("Page")} ${index + 1}", color = Color.White, fontSize = 10.sp) }
                                    IconButton(
                                        onClick = {
                                            pages.removeAt(index)
                                            if (pages.isEmpty()) { localOcrText = ""; autoUseSarvam = false; sources.clear() }
                                        },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = tr("Remove page"), tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = tr("Camera Placeholder"),
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Text(
                                text = tr("Capture or Upload"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = tr("Tap here to select an image, PDF or doc from your device, or use Camera below to snap a photo."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    if (importingFiles || localOcrRunning) {
                        ScannerSweepLine()
                    }
                }

                // Scan Type and Category Selection UI — always visible so it can be set before capturing
                run {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Assign this scan to a specific family member (or auto-detect / add a
                            // new person) so it lands under the exact right patient — no re-typing,
                            // no accidental duplicate patient.
                            Text(
                                text = tr("Who is this report for?"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ScanPatientPicker(
                                value = patientName,
                                onValueChange = { patientName = it },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = tr("Select Scanning Mode"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val scanTypes = listOf(
                                    Pair("prescription", tr("Medicine Scan")),
                                    Pair("report", tr("Reports Scan"))
                                )
                                scanTypes.forEach { (typeKey, typeLabel) ->
                                    val active = selectedScanType == typeKey
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (active) MaterialTheme.colorScheme.primaryContainer 
                                                else MaterialTheme.colorScheme.surface
                                            )
                                            .border(
                                                1.dp,
                                                if (active) MaterialTheme.colorScheme.primary 
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedScanType = typeKey }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = typeLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            
                            if (selectedScanType == "report") {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = tr("Report Category"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val row1 = listOf(
                                        Pair("blood_test", tr("Blood Test")),
                                        Pair("sonography", tr("Sonography")),
                                        Pair("2d_echo", tr("2D Echo"))
                                    )
                                    val row2 = listOf(
                                        Pair("xray", tr("X-Ray")),
                                        Pair("other", tr("Other"))
                                    )
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row1.forEach { (catKey, catLabel) ->
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .background(
                                                        if (selectedReportCategory == catKey) MaterialTheme.colorScheme.secondaryContainer 
                                                        else MaterialTheme.colorScheme.surface
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (selectedReportCategory == catKey) MaterialTheme.colorScheme.secondary 
                                                        else MaterialTheme.colorScheme.outlineVariant,
                                                        RoundedCornerShape(20.dp)
                                                    )
                                                    .clickable { selectedReportCategory = catKey }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = catLabel,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (selectedReportCategory == catKey) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selectedReportCategory == catKey) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row2.forEach { (catKey, catLabel) ->
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .background(
                                                        if (selectedReportCategory == catKey) MaterialTheme.colorScheme.secondaryContainer 
                                                        else MaterialTheme.colorScheme.surface
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (selectedReportCategory == catKey) MaterialTheme.colorScheme.secondary 
                                                        else MaterialTheme.colorScheme.outlineVariant,
                                                        RoundedCornerShape(20.dp)
                                                    )
                                                    .clickable { selectedReportCategory = catKey }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = catLabel,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (selectedReportCategory == catKey) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selectedReportCategory == catKey) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // local ML Kit Analysis Panel
                if (pages.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tr("Google ML Kit Scan Results"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (localOcrRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = tr("Scan Done"),
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            
                            val detectedTextSummary = if (localOcrText.isEmpty()) {
                                if (localOcrRunning) tr("Analyzing image text...") else tr("No clear text detected locally.")
                            } else {
                                if (localOcrText.length > 100) "${localOcrText.take(100)}..." else localOcrText
                            }
                            
                            Text(
                                text = "\"$detectedTextSummary\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )

                            Divider(modifier = Modifier.padding(vertical = 4.dp))

                            // Auto-decision notification
                            val useSarvam = autoUseSarvam
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (useSarvam) tr("Scan Mode: Regional / Indic Script (Sarvam AI)") else tr("Scan Mode: English (Google Scan + Gemini)"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (useSarvam) Color(0xFFE65100) else Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = if (useSarvam) tr("Routing to Sarvam Vision & Translation APIs.") else tr("Routing to local Google scanner & standard Gemini API."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Error Message Card
                if (errorMessage.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = tr("Error"),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Two clean rows of actions: Camera & From Device (Row 1), and Scan QR & Scan Email (Row 2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { onTakePhotoClicked() },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = tr("Camera"))
                        Spacer(modifier = Modifier.width(6.dp))
                        // Fixed single line so every button in these two rows stays the same
                        // height — "From Device"/"Scan Email" used to wrap to a second line here
                        // and visually overflow the button's fixed height while their row sibling
                        // stayed single-line, an uneven, broken-looking row. Ellipsis is only a
                        // last-resort safety net on unusually narrow screens; the tighter content
                        // padding above gives the longer labels enough room to fit in practice.
                        Text(
                            if (pages.isEmpty()) tr("Camera") else tr("Add Page"),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = { fileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = tr("From device"), tint = Color(0xFFE8A838))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            tr("From Device"),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { showQrScanner = true },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = tr("Scan QR Code"), tint = Color(0xFF1565C0))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            tr("Scan QR"),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (!AppSettings.isEmailConsentGranted(context)) {
                                showConsentDialog = true
                            } else if (AppSettings.getLinkedEmail(context).isNullOrBlank()) {
                                showSetupAlert = true
                            } else {
                                triggerEmailScan()
                            }
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Email, contentDescription = tr("Scan from Email"), tint = Color(0xFF6A1B9A))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            tr("Scan Email"),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Reports a background scan (Scan Now / the 7 PM daily alarm) already downloaded
                // but nobody has reviewed yet — one row per report, analyzed one at a time.
                if (pendingEmailQueue.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Found in email (${pendingEmailQueue.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            pendingEmailQueue.forEach { pending ->
                                val localPath = pending.pendingLocalPath
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        pending.attachmentName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        maxLines = 1
                                    )
                                    Row {
                                        TextButton(onClick = {
                                            pendingEmailQueue = pendingEmailQueue.filter { it.messageId != pending.messageId }
                                            coroutineScope.launch(Dispatchers.IO) {
                                                LocalStore.getDatabase(context).processedEmailDao().insert(
                                                    ProcessedEmail(
                                                        messageId = pending.messageId,
                                                        attachmentName = pending.attachmentName,
                                                        processedAt = System.currentTimeMillis()
                                                    )
                                                )
                                            }
                                            try { localPath?.let { File(it).delete() } } catch (_: Exception) {}
                                        }) { Text(tr("Skip")) }
                                        Button(onClick = {
                                            if (localPath == null) return@Button
                                            pendingEmailQueue = pendingEmailQueue.filter { it.messageId != pending.messageId }
                                            importSelectedFiles(listOf(Uri.fromFile(File(localPath))))
                                            coroutineScope.launch(Dispatchers.IO) {
                                                LocalStore.getDatabase(context).processedEmailDao().insert(
                                                    ProcessedEmail(
                                                        messageId = pending.messageId,
                                                        attachmentName = pending.attachmentName,
                                                        processedAt = System.currentTimeMillis()
                                                    )
                                                )
                                            }
                                            android.widget.Toast.makeText(context, reportAddedToPreviewText, android.widget.Toast.LENGTH_SHORT).show()
                                        }) { Text(tr("Analyze")) }
                                    }
                                }
                            }
                        }
                    }
                }

                // Indicator when a Word/text document's text has been attached
                if (docText.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "$documentTextAttachedPrefix (${docText.length} $documentTextAttachedSuffix",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { docText = "" }) {
                                Icon(Icons.Default.Close, contentDescription = tr("Remove document text"))
                            }
                        }
                    }
                }

                if (pages.isNotEmpty() || docText.isNotBlank()) {
                    Text(
                        text = if (pages.isNotEmpty()) "${pages.size} $pagesSelectedPrefix"
                               else tr("Document attached. Add pages/images if needed, then analyze."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = {
                        if (pages.isEmpty() && docText.isBlank()) return@Button
                        val referenceText = listOf(localOcrText, docText).filter { it.isNotBlank() }.joinToString("\n\n")
                        val category = if (selectedScanType == "report") selectedReportCategory else "prescription"

                        BackgroundScanScheduler.startScan(
                            context = context,
                            pageUris = pages.toList(),
                            sourceUris = sources.toList(),
                            referenceText = referenceText,
                            scanType = selectedScanType,
                            category = category,
                            patientName = patientName
                        )

                        // Stay on this screen (don't kick the user out) and reset the picker so the
                        // scan can't be double-submitted; the progress card at the top now tracks it
                        // to completion, and the same card shows on every screen it's placed on.
                        pages.clear(); sources.clear(); docText = ""; localOcrText = ""; autoUseSarvam = false
                        android.widget.Toast.makeText(
                            context,
                            scanStartedText,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    enabled = uploadingState == null && (pages.isNotEmpty() || docText.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = tr("Analyze"))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr("Analyze & Scan Document"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                // Upload-only: store the file now, no AI call. For archiving old records without
                // spending API quota — the report's detail screen can analyze it later on demand.
                // (Document-only text can't be re-analyzed from a stored image, so require pages.)
                if (pages.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            val category = if (selectedScanType == "report") selectedReportCategory else "prescription"
                            BackgroundScanScheduler.startUpload(
                                context = context,
                                pageUris = pages.toList(),
                                sourceUris = sources.toList(),
                                category = category,
                                patientName = patientName
                            )
                            pages.clear(); sources.clear(); docText = ""; localOcrText = ""; autoUseSarvam = false
                            android.widget.Toast.makeText(
                                context,
                                uploadedOpenLaterText,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        },
                        enabled = uploadingState == null,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.SaveAlt, contentDescription = tr("Upload only"))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Upload Only (No Scan)"), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = tr("Stores the file without using AI — no API calls. You can analyze it anytime from its details."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (showQrScanner) {
                Dialog(
                    onDismissRequest = { showQrScanner = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    QrScannerScreen(
                        onResult = { value -> handleQrResult(value) },
                        onDismiss = { showQrScanner = false }
                    )
                }
            }

            if (showSizeWarningDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showSizeWarningDialog = false
                        pendingUris = emptyList()
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(tr("Large Files Selected"), fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Text(
                            text = tr("The selected files are very large (%.2f MB). Processing these files will take longer and might cause memory issues. We recommend keeping files under 10 MB. Do you want to proceed anyway?").format(warningSizeMb),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showSizeWarningDialog = false
                                importSelectedFiles(pendingUris)
                                pendingUris = emptyList()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(tr("Proceed Anyway"))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showSizeWarningDialog = false
                                pendingUris = emptyList()
                            }
                        ) {
                            Text(tr("Cancel"))
                        }
                    }
                )
            }

            if (showConsentDialog) {
                AlertDialog(
                    onDismissRequest = { showConsentDialog = false },
                    title = { Text(tr("Email Access Consent"), fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = tr("Health Decoder requires your permission to connect to your email inbox. We will only search for emails from the last 2 days containing potential medical report attachments (PDFs) and extract them locally on your phone. No email contents are sent to our servers. Do you consent to this?"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showConsentDialog = false
                                AppSettings.setEmailConsentGranted(context, true)
                                if (AppSettings.getLinkedEmail(context).isNullOrBlank()) {
                                    showSetupAlert = true
                                } else {
                                    triggerEmailScan()
                                }
                            }
                        ) {
                            Text(tr("I Consent"))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConsentDialog = false }) {
                            Text(tr("Cancel"))
                        }
                    }
                )
            }

            if (showSetupAlert) {
                AlertDialog(
                    onDismissRequest = { showSetupAlert = false },
                    title = { Text(tr("Email Integration Required"), fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = tr("You haven't linked an email account yet. Please go to Settings (Account tab) to link your Gmail account or IMAP details first."),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(onClick = { showSetupAlert = false }) {
                            Text(tr("OK"))
                        }
                    }
                )
            }

            if (showEmailResultDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showEmailResultDialog = false
                        try { File(emailResultLocalPath).delete() } catch (_: Exception) {}
                    },
                    title = { Text(tr("New Medical Report Found"), fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = "We found report '$emailResultReportName' in your inbox. Do you want to download and upload it for scanning?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showEmailResultDialog = false
                                val uri = Uri.fromFile(File(emailResultLocalPath))
                                importSelectedFiles(listOf(uri))
                                coroutineScope.launch(Dispatchers.IO) {
                                    LocalStore.getDatabase(context).processedEmailDao().insert(
                                        ProcessedEmail(
                                            messageId = emailResultMessageId,
                                            attachmentName = emailResultReportName,
                                            processedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                                android.widget.Toast.makeText(context, reportAddedToPreviewText, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(tr("Yes, Import"))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showEmailResultDialog = false
                                coroutineScope.launch(Dispatchers.IO) {
                                    LocalStore.getDatabase(context).processedEmailDao().insert(
                                        ProcessedEmail(
                                            messageId = emailResultMessageId,
                                            attachmentName = emailResultReportName,
                                            processedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                                try { File(emailResultLocalPath).delete() } catch (_: Exception) {}
                            }
                        ) {
                            Text(tr("Skip & Mark Read"))
                        }
                    }
                )
            }

        }
    }
}

/** Result of fetching the URL decoded from a report's printed QR code. */
private sealed class QrFetchResult {
    /** A direct image/PDF link — [uri] points at a cached local copy ready to import. */
    data class ImportableFile(val uri: Uri) : QrFetchResult()
    /** An HTML page (e.g. a lab portal needing login), not a direct file link. */
    object WebPage : QrFetchResult()
    data class Failed(val message: String) : QrFetchResult()
}

private const val MAX_QR_FETCH_BYTES = 15L * 1024 * 1024

private val qrFetchClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

/** Reads at most [max] bytes from [input]; returns null if the stream has more than that. */
private fun readBytesCapped(input: java.io.InputStream, max: Long): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = input.read(chunk)
        if (n == -1) break
        total += n
        if (total > max) return null
        buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray()
}

/**
 * Fetches the URL decoded from a report's QR code. Most Indian diagnostic labs print a QR
 * linking straight to the official digital copy (image/PDF) — download and cache it as a
 * local file so it can flow through the normal import pipeline. If it resolves to a web page
 * instead (a portal needing login/OTP), the caller falls back to opening it in the browser.
 */
private fun fetchQrLinkedFile(context: Context, url: String): QrFetchResult {
    val request = Request.Builder().url(url).build()
    try {
        qrFetchClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return QrFetchResult.Failed("The linked report couldn't be reached (HTTP ${response.code}).")
            }
            val contentType = (response.header("Content-Type") ?: "").lowercase()
            val body = response.body ?: return QrFetchResult.Failed("The linked report was empty.")
            return when {
                contentType.startsWith("image/") || contentType.contains("pdf") -> {
                    val bytes = body.byteStream().use { readBytesCapped(it, MAX_QR_FETCH_BYTES) }
                        ?: return QrFetchResult.Failed("That file is too large to import (over 15MB).")
                    val ext = if (contentType.contains("pdf")) "pdf"
                        else contentType.substringAfter("image/").substringBefore(";").ifBlank { "jpg" }
                    val tempFile = File.createTempFile("qr_report_", ".$ext", context.cacheDir)
                    tempFile.writeBytes(bytes)
                    val authority = "${context.packageName}.fileprovider"
                    QrFetchResult.ImportableFile(FileProvider.getUriForFile(context, authority, tempFile))
                }
                contentType.contains("html") -> QrFetchResult.WebPage
                else -> QrFetchResult.Failed("Unrecognized file type from that QR code.")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return QrFetchResult.Failed("Couldn't reach the linked report — check your connection.")
    }
}

/**
 * Helper to convert Uri to MultipartBody.Part for Retrofit
 */
private fun uriToMultipartBodyPart(context: Context, uri: Uri, partName: String): MultipartBody.Part? {
    try {
        val contentResolver = context.contentResolver
        var fileName = "report_scan.jpg"
        
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        
        // Clean special characters in filename to avoid HTTP header issues
        fileName = fileName.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
        
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, fileName)
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
        val requestFile = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
        return MultipartBody.Part.createFormData(partName, tempFile.name, requestFile)
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

@Composable
private fun ScannerSweepLine(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweepOffsetY by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweepOffset"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val y = size.height * sweepOffsetY
        
        // Draw the laser line with a glow brush
        val laserBrush = Brush.verticalGradient(
            colors = listOf(
                Color(0x002E7D32),
                Color(0xFF2E7D32),
                Color(0x002E7D32)
            ),
            startY = y - 10.dp.toPx(),
            endY = y + 10.dp.toPx()
        )
        drawRect(
            brush = laserBrush,
            topLeft = Offset(0f, y - 10.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(size.width, 20.dp.toPx())
        )
        
        // Draw the main bright solid laser core line
        drawLine(
            color = Color(0xFF2E7D32),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 3.dp.toPx()
        )
    }
}

