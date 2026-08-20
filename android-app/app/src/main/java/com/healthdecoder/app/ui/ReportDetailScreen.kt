package com.healthdecoder.app.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.healthdecoder.app.ai.DashboardEngine
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.Medication
import com.healthdecoder.app.model.ReportUpdateRequest
import com.healthdecoder.app.model.HealthInsights
import com.healthdecoder.app.model.SpecialistRecommendation
import com.healthdecoder.app.model.PrescriptionAlignment
import com.healthdecoder.app.model.MedicineSideEffect
import com.healthdecoder.app.network.NetworkModule
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    reportId: String,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToAnalysis: (String) -> Unit = {},
    onNavigateToDiscovery: (String, String) -> Unit = { _, _ -> },
    highlightParam: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var report by remember { mutableStateOf<MedicalReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isEditing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    // Editable States
    var editPatientName by remember { mutableStateOf("") }
    var editReportDate by remember { mutableStateOf("") }
    var editReportType by remember { mutableStateOf("") }
    var editComments by remember { mutableStateOf("") }
    var editRawText by remember { mutableStateOf("") }
    val editMedications = remember { mutableStateListOf<Medication>() }
    
    // Collapsible states
    var rawTextExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReprocessDialog by remember { mutableStateOf(false) }
    var isReprocessing by remember { mutableStateOf(false) }
    var showFullImageDialog by remember { mutableStateOf(false) }
    var medicineSheetName by remember { mutableStateOf<String?>(null) }
    var fullImagePath by remember { mutableStateOf<String?>(null) }

    // Save an original file to a user-chosen location (Downloads / Drive) via the system picker.
    var pendingSave by remember { mutableStateOf<com.healthdecoder.app.model.SourceFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val sf = pendingSave
        if (uri != null && sf != null) {
            coroutineScope.launch {
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        java.io.File(sf.path).inputStream().use { input ->
                            context.contentResolver.openOutputStream(uri)?.use { input.copyTo(it) }
                        }
                    }
                }.onFailure { errorMessage = "Couldn't save the file." }
            }
        }
        pendingSave = null
    }
    fun openSource(sf: com.healthdecoder.app.model.SourceFile) {
        try {
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", java.io.File(sf.path)
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, sf.mimeType.ifBlank { "*/*" })
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            errorMessage = "No app available to open this file type."
        }
    }
    // Share the original file straight to WhatsApp / email / etc. — for sending to a doctor.
    fun shareSource(sf: com.healthdecoder.app.model.SourceFile) {
        try {
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", java.io.File(sf.path)
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = sf.mimeType.ifBlank { "*/*" }
                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share report"))
        } catch (e: Exception) {
            errorMessage = "Couldn't share this file."
        }
    }

    // Fetch report details from backend
    val loadReportDetails = {
        coroutineScope.launch {
            isLoading = true
            errorMessage = ""
            try {
                val fetchedReport = LocalRepository.getReport(context, reportId)
                if (fetchedReport == null) {
                    errorMessage = "Report not found."
                } else {
                    report = fetchedReport
                    // Initialize edit fields
                    editPatientName = fetchedReport.patientName ?: ""
                    editReportDate = fetchedReport.reportDate ?: ""
                    editReportType = fetchedReport.reportType ?: "Other"
                    editComments = fetchedReport.comments ?: ""
                    editRawText = fetchedReport.extractedText ?: ""
                    editMedications.clear()
                    editMedications.addAll(fetchedReport.medications)

                    // The comparison and insights cards are built on demand rather than at scan
                    // time, so opening a report is what pays for them — and only once. The report
                    // above is already on screen while this runs; the cards appear when ready.
                    runCatching { LocalRepository.ensureEnrichment(context, reportId) }
                        .getOrNull()?.let { report = it }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to load report details."
            } finally {
                isLoading = false
            }
        }
    }

    // Load on launch
    LaunchedEffect(reportId) {
        loadReportDetails()
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
                        Text(if (isEditing) tr("Edit Details") else tr("Report Details"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditing) {
                            // Cancel edit
                            isEditing = false
                            report?.let {
                                editPatientName = it.patientName ?: ""
                                editReportDate = it.reportDate ?: ""
                                editReportType = it.reportType ?: "Other"
                                editComments = it.comments ?: ""
                                editRawText = it.extractedText ?: ""
                                editMedications.clear()
                                editMedications.addAll(it.medications)
                            }
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (isEditing) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = tr("Back")
                        )
                    }
                },
                actions = {
                    if (!isLoading && report != null) {
                        if (isEditing) {
                            // Save Button
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    isLoading = true
                                    errorMessage = ""
                                    try {
                                        val requestBody = ReportUpdateRequest(
                                            patientName = editPatientName,
                                            reportDate = editReportDate,
                                            reportType = editReportType,
                                            comments = editComments,
                                            medications = editMedications.toList(),
                                            extractedText = editRawText
                                        )
                                        val updated = LocalRepository.updateReport(context, reportId, requestBody)
                                        if (updated != null) report = updated
                                        isEditing = false
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        errorMessage = "Failed to save edits. Check network settings."
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = tr("Save"), tint = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            // Reprocess Button — re-run AI extraction from the original scanned
                            // image, for when the earlier scan came back incomplete (API down).
                            if (report?.imagePaths?.isNotEmpty() == true) {
                                IconButton(onClick = { showReprocessDialog = true }, enabled = !isReprocessing) {
                                    if (isReprocessing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Icon(imageVector = Icons.Default.Autorenew, contentDescription = tr("Reprocess"))
                                }
                            }
                            // Edit Button
                            IconButton(onClick = { isEditing = true }) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = tr("Edit"))
                            }
                            // Delete Button
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = tr("Delete"), tint = MaterialTheme.colorScheme.error)
                            }
                        }
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
            if (isLoading && report == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (report == null) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = tr("Error"), modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Text(text = tr(errorMessage.ifEmpty { "Report not found." }))
                        Button(onClick = { loadReportDetails() }) { Text(tr("Retry")) }
                    }
                }
            } else {
                val currentReport = report!!
                val scrollState = rememberScrollState()
                // Deep-linking from a Trends chart point: scroll to and briefly highlight
                // the specific parameter row instead of dropping the user at the top.
                var columnTopInRoot by remember { mutableStateOf(0f) }
                val paramRowOffsets = remember { mutableStateMapOf<String, Float>() }
                var didScrollToHighlight by remember { mutableStateOf(false) }
                var highlightedParamKey by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(highlightParam, paramRowOffsets.size, columnTopInRoot) {
                    if (didScrollToHighlight || highlightParam == null) return@LaunchedEffect
                    val targetY = paramRowOffsets[highlightParam] ?: return@LaunchedEffect
                    didScrollToHighlight = true
                    val target = (targetY - columnTopInRoot + scrollState.value - 24f).coerceAtLeast(0f)
                    scrollState.animateScrollTo(target.toInt())
                    highlightedParamKey = highlightParam
                    kotlinx.coroutines.delay(2000)
                    highlightedParamKey = null
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .onGloballyPositioned { columnTopInRoot = it.positionInRoot().y }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // All document page images (swipe to view every page)
                    val pagePaths = currentReport.imagePaths.ifEmpty {
                        listOfNotNull(currentReport.imagePath.takeIf { it.isNotBlank() })
                    }
                    if (pagePaths.isNotEmpty()) {
                        if (pagePaths.size > 1) {
                            Text(
                                "${pagePaths.size} pages — swipe to view",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(pagePaths) { idx, path ->
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxWidth(if (pagePaths.size == 1) 1f else 0.82f)
                                        .height(210.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { fullImagePath = path; showFullImageDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(model = java.io.File(path)),
                                        contentDescription = "${tr("Page")} ${idx + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
                                    Row(
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Fullscreen, contentDescription = tr("Expand"), tint = Color.White, modifier = Modifier.size(14.dp))
                                        Text(if (pagePaths.size > 1) "Page ${idx + 1}" else tr("Tap to view"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Uploaded-but-not-analyzed banner with a prominent one-tap analyze action.
                    if (!currentReport.analyzed) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text(tr("Uploaded — not analyzed yet"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                }
                                Text(
                                    tr("This report's file is saved but hasn't been analyzed by AI, so it has no test values, trends or insights yet. Analyze it whenever you're ready (uses some API quota)."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = { showReprocessDialog = true },
                                    enabled = !isReprocessing,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    if (isReprocessing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    else {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(tr("Analyze Now"), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Original file(s): open, share (to a doctor) or download the exact file the user imported
                    if (currentReport.sourceFiles.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("Original file(s)"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                currentReport.sourceFiles.forEach { sf ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text(sf.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        IconButton(onClick = { openSource(sf) }) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = tr("Open"))
                                        }
                                        IconButton(onClick = { shareSource(sf) }) {
                                            Icon(Icons.Default.Share, contentDescription = tr("Share"))
                                        }
                                        IconButton(onClick = { pendingSave = sf; saveLauncher.launch(sf.name) }) {
                                            Icon(Icons.Default.Download, contentDescription = tr("Download"))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Main info panel
                    if (isEditing) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = tr("Info"),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = tr("You can manually correct spelling mistakes in the Patient Name, Date, or Type below. Tapping the Save (Checkmark) icon in the top right will update the history database."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = editPatientName,
                                    onValueChange = { editPatientName = it },
                                    label = { Text(tr("Patient Name")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = editReportDate,
                                    onValueChange = { editReportDate = it },
                                    label = { Text(tr("Report Date (YYYY-MM-DD)")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = editReportType,
                                    onValueChange = { editReportType = it },
                                    label = { Text(tr("Report Type")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            // Patient/date and the report type are stacked, not placed side by side.
                            // A real report type runs long ("Diagnostic Lab Report (Haemogram,
                            // PT/INR, Urine Routine, Biochemistry, Electrolytes)"), and as the
                            // right-hand item of a SpaceBetween row its pill grew back across the
                            // patient's name and covered it. On its own line it can use the full
                            // width and wrap normally.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Patient: ${currentReport.patientName ?: "Unknown"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Date: ${currentReport.reportDate ?: "Unknown"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = currentReport.reportType ?: "Other",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    // Clinical Insights & Trend Comparison Card
                    val comp = currentReport.comparisonResult
                    if (comp != null && comp.hasComparison) {
        val insightAccent = when (comp.status?.lowercase()) {
                            "improved" -> Color(0xFF2E7D32)
                            "worsened" -> Color(0xFFC62828)
                            "mixed" -> Color(0xFFE65100)
                            else -> null
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                // Tinted OVER the theme's own surface (not a hardcoded pale hex) so
                                // the card reads correctly in dark theme too — see statusContainerColor.
                                containerColor = insightAccent?.let { statusContainerColor(it) }
                                    ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = when (comp.status?.lowercase()) {
                                            "improved" -> Icons.Default.TrendingUp
                                            "worsened" -> Icons.Default.TrendingDown
                                            else -> Icons.Default.Info
                                        },
                                        contentDescription = tr("Trend"),
                                        tint = when (comp.status?.lowercase()) {
                                            "improved" -> Color(0xFF2E7D32)
                                            "worsened" -> Color(0xFFC62828)
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    )
                                    Text(
                                        text = tr("Clinical Insight & Progress"),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = when (comp.status?.lowercase()) {
                                            "improved" -> Color(0xFF2E7D32)
                                            "worsened" -> Color(0xFFC62828)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                
                                Text(
                                    text = comp.comparisonSummary ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                if (comp.differences.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = tr("Parameter Changes:"),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    comp.differences.forEach { diff ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = diff.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                            Text(
                                                text = "${diff.previous} → ${diff.current}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (diff.status == "improved") Color(0xFF2E7D32) else if (diff.status == "worsened") Color(0xFFC62828) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                val medChg = comp.medicationChanges
                                if (medChg != null && (medChg.added.isNotEmpty() || medChg.removed.isNotEmpty() || medChg.changed.isNotEmpty())) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = tr("Medication Adjustments:"),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    medChg.added.forEach { m ->
                                        Text(text = "+ ${tr("Added:")} $m", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                                    }
                                    medChg.removed.forEach { m ->
                                        Text(text = "- ${tr("Discontinued:")} $m", fontSize = 12.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Medium)
                                    }
                                    medChg.changed.forEach { m ->
                                        Text(text = "• ${tr("Modified:")} $m", fontSize = 12.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Medium)
                                    }
                                }

                                if (comp.previousReportId != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { onNavigateToDetail(comp.previousReportId) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            contentColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.align(Alignment.End),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(imageVector = Icons.Default.Visibility, contentDescription = tr("View"), modifier = Modifier.size(14.dp))
                                            Text(tr("View Previous Report"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Test Results / Lab parameters table Card
                    val testRes = currentReport.testResults
                    if (testRes != null && (testRes.parameters.isNotEmpty() || testRes.findings.isNotEmpty())) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = tr("Tested Parameters"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                if (testRes.parameters.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                                        verticalArrangement = Arrangement.spacedBy(1.dp)
                                    ) {
                                        // Header Row
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = tr("Parameter"), modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(text = tr("Value"), modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.End)
                                            Text(text = tr("Ref Range"), modifier = Modifier.weight(1.8f), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.End)
                                        }
                                        
                                        testRes.parameters.forEach { param ->
                                            val paramKey = DashboardEngine.canonicalParamName(param.name)
                                            val rowHighlight by animateColorAsState(
                                                if (highlightedParamKey == paramKey) MaterialTheme.colorScheme.primaryContainer
                                                else Color.Transparent,
                                                label = "paramRowHighlight"
                                            )
                                            Divider(color = MaterialTheme.colorScheme.outlineVariant)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .onGloballyPositioned { paramRowOffsets[paramKey] = it.positionInRoot().y }
                                                    .background(rowHighlight)
                                                    .padding(8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(text = param.name, modifier = Modifier.weight(2f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                
                                                Row(
                                                    modifier = Modifier.weight(1.2f),
                                                    horizontalArrangement = Arrangement.End,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(text = "${param.value} ${param.unit}".trim(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                                
                                                Row(
                                                    modifier = Modifier.weight(1.8f),
                                                    horizontalArrangement = Arrangement.End,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // The range yields width to the badge instead of the
                                                    // other way round: a long range ("1,50,000 - 4,50,000")
                                                    // used to squeeze the pill until its label wrapped.
                                                    Text(
                                                        text = param.referenceRange.ifEmpty { "-" },
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.End,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )

                                                    val paramStatus = param.status ?: ""
                                                    if (paramStatus.isNotEmpty()) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        StatusBadge(paramStatus, compact = true)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                if (testRes.findings.isNotEmpty()) {
                                    if (testRes.parameters.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                    Text(
                                        text = tr("Observations / Impressions:"),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    testRes.findings.forEach { finding ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(text = "•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Text(text = finding, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ══ CARD 1: What This Report Means ═══════════════════════════════════════
                    val hi = currentReport.healthInsights
                    if (hi != null && hi.interpretation.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                // Same reason as the alignment card below: tint over the theme's
                                // surface so this stays readable in dark mode.
                                containerColor = statusContainerColor(MaterialTheme.colorScheme.primary)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF3F51B5)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Psychology,
                                            contentDescription = tr("Insights"),
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Text(
                                        text = tr("What This Report Means"),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color(0xFF1A237E)
                                    )
                                }

                                Text(
                                    text = hi.interpretation,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF37474F),
                                    lineHeight = 22.sp
                                )

                                if (hi.specialistRecommendations.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = tr("Recommended Specialist(s):"),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF3F51B5)
                                    )
                                    hi.specialistRecommendations.forEach { rec ->
                                        val (urgencyAccent, urgencyIcon) = when (rec.urgency.lowercase()) {
                                            "urgent" -> ClinicalStatus.High to "🚨"
                                            "soon" -> ClinicalStatus.Low to "⚠️"
                                            else -> ClinicalStatus.Normal to "📅"
                                        }
                                        val bgColor = statusContainerColor(urgencyAccent)
                                        val textColor = urgencyAccent
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(bgColor)
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(text = urgencyIcon, fontSize = 16.sp)
                                            Column {
                                                Text(
                                                    text = rec.specialist,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = textColor
                                                )
                                                Text(
                                                    text = rec.reason,
                                                    fontSize = 12.sp,
                                                    color = textColor.copy(alpha = 0.8f),
                                                    lineHeight = 16.sp
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .padding(top = 4.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(textColor.copy(alpha = 0.12f))
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = rec.urgency,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = textColor
                                                        )
                                                    }
                                                    TextButton(
                                                        onClick = { onNavigateToDiscovery("doctors", rec.specialist) },
                                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = textColor, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(tr("Find Nearby"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ══ Detailed Analysis call-to-action (opens a separate screen on demand) ══
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToAnalysis(reportId) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3F51B5)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Article,
                                    contentDescription = tr("Detailed analysis"),
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("View Detailed Analysis"),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = tr("In-depth, plain-language breakdown of this report"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }

                    // ══ CARD 2: Prescription Alignment Check ════════════════════════════════
                    val alignment = hi?.prescriptionAlignment
                    if (alignment != null && alignment.score != "N/A") {
                        // Accent tinted OVER the theme's surface, never a fixed pale hex: a pale
                        // fill stays pale in dark theme while the body text stays white, which is
                        // what made this card's own text unreadable there. Same treatment as the
                        // comparison card above — see statusContainerColor.
                        val (accent, iconVec) = when (alignment.score.lowercase()) {
                            "good" -> ClinicalStatus.Normal to Icons.Default.CheckCircle
                            "partial" -> ClinicalStatus.Low to Icons.Default.Warning
                            "poor" -> ClinicalStatus.High to Icons.Default.Cancel
                            else -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Default.Info
                        }
                        val cardBg = statusContainerColor(accent)
                        val headerColor = accent
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = iconVec,
                                            contentDescription = tr("Alignment"),
                                            tint = headerColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Text(
                                            text = tr("Prescription Alignment"),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = headerColor
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(headerColor)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = alignment.score,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                    }
                                }

                                Text(
                                    text = alignment.analysis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp
                                )

                                if (alignment.flags.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        alignment.flags.forEach { flag ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(headerColor.copy(alpha = 0.08f))
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Text(text = "⚠️", fontSize = 14.sp)
                                                Text(
                                                    text = flag,
                                                    fontSize = 12.sp,
                                                    color = headerColor,
                                                    lineHeight = 17.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ══ CARD 3: Medicine Side-Effects Accordion ══════════════════════════════
                    val sideEffects = hi?.sideEffects ?: emptyList()
                    if (sideEffects.isNotEmpty()) {
                        var expandedMed by remember { mutableStateOf<String?>(null) }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF7B1FA2)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Medication,
                                            contentDescription = tr("Side Effects"),
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = tr("Medicine Side-Effects"),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Color(0xFF4A148C)
                                        )
                                        Text(
                                            text = tr("Tap a medicine to expand"),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                sideEffects.forEach { se ->
                                    val isExpanded = expandedMed == se.medicine
                                    val severityColor = when (se.severity.lowercase()) {
                                        "serious" -> ClinicalStatus.High
                                        "moderate" -> ClinicalStatus.Low
                                        else -> ClinicalStatus.Normal
                                    }
                                    val severityBg = statusContainerColor(severityColor)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    expandedMed = if (isExpanded) null else se.medicine
                                                }
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.LocalPharmacy,
                                                    contentDescription = se.medicine,
                                                    tint = Color(0xFF7B1FA2),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = se.medicine,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(severityBg)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = se.severity,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = severityColor
                                                    )
                                                }
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                    contentDescription = tr("Toggle"),
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        AnimatedVisibility(visible = isExpanded) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                                    .padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                if (se.commonEffects.isNotEmpty()) {
                                                    Text(
                                                        text = tr("Common Side-Effects:"),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    se.commonEffects.forEach { eff ->
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalAlignment = Alignment.Top
                                                        ) {
                                                            Text(text = "•", color = Color(0xFF7B1FA2), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                            Text(text = eff, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 16.sp)
                                                        }
                                                    }
                                                }
                                                if (se.seriousEffects.isNotEmpty()) {
                                                    Text(
                                                        text = tr("Watch Out For:"),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        color = Color(0xFFC62828)
                                                    )
                                                    se.seriousEffects.forEach { eff ->
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(RoundedCornerShape(6.dp))
                                                                .background(statusContainerColor(ClinicalStatus.High))
                                                                .padding(8.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalAlignment = Alignment.Top
                                                        ) {
                                                            Text(text = "🚨", fontSize = 12.sp)
                                                            Text(text = eff, fontSize = 12.sp, color = Color(0xFFC62828), lineHeight = 16.sp)
                                                        }
                                                    }
                                                }
                                                if (se.tips.isNotEmpty()) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(statusContainerColor(MaterialTheme.colorScheme.primary))
                                                            .padding(8.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalAlignment = Alignment.Top
                                                    ) {
                                                        Text(text = "💡", fontSize = 12.sp)
                                                        Text(
                                                            text = se.tips,
                                                            fontSize = 12.sp,
                                                            color = Color(0xFF0D47A1),
                                                            lineHeight = 16.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }
                    }

                    // Doctor Comments Panel
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("Doctor's Comments & Instructions"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (isEditing) {
                                OutlinedTextField(
                                    value = editComments,
                                    onValueChange = { editComments = it },
                                    modifier = Modifier.fillMaxWidth().height(120.dp),
                                    placeholder = { Text(tr("Enter doctor's comments or notes here...")) }
                                )
                            } else {
                                Text(
                                    text = currentReport.comments.takeIf { !it.isNullOrBlank() } ?: "No comments available.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }

                    // Prescriptions / Medications List
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tr("Prescribed Medications"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (isEditing) {
                                    IconButton(onClick = {
                                        editMedications.add(Medication(name = "New Medicine", dosage = "", frequency = "", duration = ""))
                                    }) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = tr("Add Medicine"), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }

                            if (isEditing) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    editMedications.forEachIndexed { index, med ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("${tr("Medication")} #${index + 1}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                                    IconButton(onClick = { editMedications.removeAt(index) }) {
                                                        Icon(imageVector = Icons.Default.Delete, contentDescription = tr("Remove"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                                    }
                                                }
                                                OutlinedTextField(
                                                    value = med.name,
                                                    onValueChange = { editMedications[index] = med.copy(name = it) },
                                                    label = { Text(tr("Name")) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedTextField(
                                                        value = med.dosage,
                                                        onValueChange = { editMedications[index] = med.copy(dosage = it) },
                                                        label = { Text(tr("Dosage")) },
                                                        modifier = Modifier.weight(1f),
                                                        singleLine = true
                                                    )
                                                    OutlinedTextField(
                                                        value = med.frequency,
                                                        onValueChange = { editMedications[index] = med.copy(frequency = it) },
                                                        label = { Text(tr("Frequency")) },
                                                        modifier = Modifier.weight(1f),
                                                        singleLine = true
                                                    )
                                                }
                                                OutlinedTextField(
                                                    value = med.duration ?: "",
                                                    onValueChange = { editMedications[index] = med.copy(duration = it) },
                                                    label = { Text(tr("Duration")) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                if (currentReport.medications.isEmpty()) {
                                    Text(tr("No medications extracted."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    currentReport.medications.forEach { med ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Medication,
                                                    contentDescription = tr("Medication"),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = med.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (med.dosage.isNotEmpty()) {
                                                            Text(text = "${tr("Dosage:")} ${med.dosage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                        if (med.frequency.isNotEmpty()) {
                                                            Text(text = "•  ${med.frequency}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }
                                                    if (!med.duration.isNullOrEmpty()) {
                                                        Text(text = "${tr("Duration:")} ${med.duration}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                                    }
                                                }
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { medicineSheetName = med.name }
                                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f))
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = tr("What is this medicine for?"),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.ChevronRight,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // The raw transcription is deliberately NOT shown: it's a machine artifact
                    // (on-device OCR output), not something a patient reads or edits. It is still
                    // stored and still matters — it backs full-text search and the token-overlap
                    // duplicate check in LocalStore — so [editRawText] keeps round-tripping the
                    // saved value untouched rather than dropping it on edit.
                }
            }

            // Image Preview Modal
            if (showFullImageDialog && report != null) {
                val fullImageUrl = java.io.File(fullImagePath ?: report!!.imagePath)
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + panChange
                }
                
                AlertDialog(
                    onDismissRequest = { showFullImageDialog = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                    text = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.8f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.95f))
                                .transformable(state = transformState),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = rememberAsyncImagePainter(model = fullImageUrl),
                                contentDescription = tr("Full Screen Preview"),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y
                                    ),
                                contentScale = ContentScale.Fit
                            )
                            if (scale > 1f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(16.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .clickable {
                                            scale = 1f
                                            offset = Offset.Zero
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(tr("Reset Zoom"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showFullImageDialog = false }) {
                            Text(tr("Close"))
                        }
                    }
                )
            }

            // Delete Confirmation Dialog
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(tr("Delete Report?")) },
                    text = { Text(tr("Are you sure you want to delete this medical report? This will permanently remove the record from your database and delete the uploaded image file.")) },
                    confirmButton = {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    showDeleteDialog = false
                                    isLoading = true
                                    try {
                                        LocalRepository.deleteReport(context, reportId)
                                        onNavigateBack() // Go back to dashboard on success
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        errorMessage = "Failed to delete report."
                                        isLoading = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(tr("Delete"))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text(tr("Cancel"))
                        }
                    }
                )
            }

            // Reprocess Confirmation Dialog
            if (showReprocessDialog) {
                AlertDialog(
                    onDismissRequest = { showReprocessDialog = false },
                    title = { Text(tr("Reprocess this report?")) },
                    text = { Text(tr("This re-runs AI analysis on the originally scanned image and refreshes the extracted test values, medicines and insights below. Useful if the earlier scan came back incomplete (e.g. the API was briefly unavailable). It may use some of your API quota.")) },
                    confirmButton = {
                        Button(onClick = {
                            showReprocessDialog = false
                            coroutineScope.launch {
                                isReprocessing = true
                                errorMessage = ""
                                try {
                                    val updated = LocalRepository.reprocessReport(context, reportId)
                                    if (updated != null) report = updated
                                    else errorMessage = "Couldn't reprocess: report or its scanned image is missing."
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    errorMessage = "Failed to reprocess this report."
                                } finally {
                                    isReprocessing = false
                                }
                            }
                        }) {
                            Text(tr("Reprocess"))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showReprocessDialog = false }) {
                            Text(tr("Cancel"))
                        }
                    }
                )
            }

            // Medicine reference sheet (category, why prescribed, notes, name correction)
            medicineSheetName?.let { name ->
                MedicineInfoSheet(
                    medicineName = name,
                    reportId = reportId,
                    onDismiss = { medicineSheetName = null },
                    onNameCorrected = { _, _ ->
                        medicineSheetName = null
                        loadReportDetails()
                    }
                )
            }
        }
    }
}

