package com.healthdecoder.app.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.model.CompareResponse
import com.healthdecoder.app.model.ComparisonResult
import com.healthdecoder.app.model.ScannedReportData
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.ui.components.AppBottomNavBar
import com.healthdecoder.app.ui.components.BottomNavTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToTab: (BottomNavTab) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var uri1 by remember { mutableStateOf<Uri?>(null) }
    var uri2 by remember { mutableStateOf<Uri?>(null) }
    var name1 by remember { mutableStateOf("") }
    var name2 by remember { mutableStateOf("") }

    var scanType1 by remember { mutableStateOf("prescription") }
    var scanType2 by remember { mutableStateOf("prescription") }
    var category1 by remember { mutableStateOf("blood_test") }
    var category2 by remember { mutableStateOf("blood_test") }

    var isComparing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CompareResponse?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    val picker1 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uri1 = uri
            name1 = getFileName(context, uri)
            result = null
            errorMessage = ""
        }
    }
    val picker2 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uri2 = uri
            name2 = getFileName(context, uri)
            result = null
            errorMessage = ""
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
                        Text(tr("Compare Reports"), fontWeight = FontWeight.Bold)
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
            AppBottomNavBar(currentTab = BottomNavTab.Compare, onNavigate = onNavigateToTab)
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .appWatermark()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = tr("Upload two reports, prescriptions, or scans to compare what changed between them."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Two file picker cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReportPickerCard(
                        label = tr("Report 1 (Older)"),
                        fileName = name1,
                        scanType = scanType1,
                        category = category1,
                        onPickFile = { picker1.launch("*/*") },
                        onClearFile = { uri1 = null; name1 = ""; result = null },
                        onScanTypeChange = { scanType1 = it },
                        onCategoryChange = { category1 = it },
                        modifier = Modifier.weight(1f)
                    )
                    ReportPickerCard(
                        label = tr("Report 2 (Newer)"),
                        fileName = name2,
                        scanType = scanType2,
                        category = category2,
                        onPickFile = { picker2.launch("*/*") },
                        onClearFile = { uri2 = null; name2 = ""; result = null },
                        onScanTypeChange = { scanType2 = it },
                        onCategoryChange = { category2 = it },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (errorMessage.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(tr(errorMessage), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            val u1 = uri1 ?: return@launch
                            val u2 = uri2 ?: return@launch
                            isComparing = true
                            errorMessage = ""
                            result = null
                            try {
                                val bytes1 = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(u1)?.use { it.readBytes() } }
                                    ?: throw Exception("Could not read Report 1")
                                val bytes2 = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(u2)?.use { it.readBytes() } }
                                    ?: throw Exception("Could not read Report 2")
                                val mime1 = context.contentResolver.getType(u1) ?: "image/jpeg"
                                val mime2 = context.contentResolver.getType(u2) ?: "image/jpeg"

                                val effectiveCategory1 = if (scanType1 == "prescription") "prescription" else category1
                                val effectiveCategory2 = if (scanType2 == "prescription") "prescription" else category2

                                result = LocalRepository.compare(
                                    context,
                                    bytes1, mime1, scanType1, effectiveCategory1,
                                    bytes2, mime2, scanType2, effectiveCategory2
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                                errorMessage = "Comparison failed: ${e.localizedMessage ?: "Unknown error"}"
                            } finally {
                                isComparing = false
                            }
                        }
                    },
                    enabled = uri1 != null && uri2 != null && !isComparing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CompareArrows, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Compare Reports"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                result?.let { CompareResultPanel(it) }

                Spacer(Modifier.height(32.dp))
            }

            // Loading overlay
            AnimatedVisibility(visible = isComparing, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(24.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(56.dp))
                            Text(tr("Analyzing & Comparing..."), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                tr("AI is reading both documents and identifying what changed."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportPickerCard(
    label: String,
    fileName: String,
    scanType: String,
    category: String,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onScanTypeChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            // File pick zone
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        if (fileName.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .background(if (fileName.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onPickFile),
                contentAlignment = Alignment.Center
            ) {
                if (fileName.isNotEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(
                            text = fileName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            textAlign = TextAlign.Center
                        )
                        TextButton(
                            onClick = onClearFile,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(20.dp)
                        ) {
                            Text(tr("Change"), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
                        Text(tr("Tap to pick\nImage or PDF"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }

            // Scan type toggle
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("prescription" to "Rx", "report" to "Report").forEach { (key, lbl) ->
                    val active = scanType == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            .clickable { onScanTypeChange(key) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tr(lbl), fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Category chips (only for "report" type)
            if (scanType == "report") {
                val categories = listOf("blood_test" to "Blood", "sonography" to "Sono", "2d_echo" to "Echo", "xray" to "X-Ray", "other" to "Other")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categories.chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (key, lbl) ->
                                val active = category == key
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                                        .border(1.dp, if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                        .clickable { onCategoryChange(key) }
                                        .padding(vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(tr(lbl), fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // Fill remaining slots if row < 3
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareResultPanel(result: CompareResponse) {
    val comparison = result.comparison

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(tr("Comparison Result"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                // Report names row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportSummaryChip(label = tr("Report 1"), name = result.report1.patientName, date = result.report1.reportDate, type = result.report1.reportType, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                    ReportSummaryChip(label = tr("Report 2"), name = result.report2.patientName, date = result.report2.reportDate, type = result.report2.reportType, modifier = Modifier.weight(1f))
                }
            }
        }

        // Overall status + summary
        if (comparison.hasComparison && comparison.comparisonSummary != null) {
            val statusColors = when (comparison.status?.lowercase()) {
                "improved" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
                "worsened" -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828))
                "mixed" -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100))
                else -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = statusColors.first)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = statusColors.second, modifier = Modifier.size(18.dp))
                        Text(
                            text = "${tr("Overall:")} ${comparison.status?.replaceFirstChar { it.uppercase() } ?: tr("Compared")}",
                            fontWeight = FontWeight.Bold,
                            color = statusColors.second
                        )
                    }
                    Text(comparison.comparisonSummary, style = MaterialTheme.typography.bodyMedium, color = statusColors.second)
                }
            }
        } else if (!comparison.hasComparison) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("Not enough comparable data found between the two reports."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Medication changes
        val medChanges = comparison.medicationChanges
        if (medChanges != null && (medChanges.added.isNotEmpty() || medChanges.removed.isNotEmpty() || medChanges.changed.isNotEmpty())) {
            SectionCard(title = tr("Medication Changes"), icon = Icons.Default.Medication) {
                if (medChanges.added.isNotEmpty()) {
                    ChangeGroup(label = tr("Added"), items = medChanges.added, color = Color(0xFF2E7D32), bgColor = Color(0xFFE8F5E9))
                }
                if (medChanges.removed.isNotEmpty()) {
                    ChangeGroup(label = tr("Discontinued"), items = medChanges.removed, color = Color(0xFFC62828), bgColor = Color(0xFFFFEBEE))
                }
                if (medChanges.changed.isNotEmpty()) {
                    ChangeGroup(label = tr("Modified"), items = medChanges.changed, color = Color(0xFFE65100), bgColor = Color(0xFFFFF3E0))
                }
            }
        }

        // Test parameter differences — rendered as a comparison matrix (Parameter | Previous | Current)
        if (comparison.differences.isNotEmpty()) {
            SectionCard(title = tr("Test Parameter Changes"), icon = Icons.Default.Science) {
                Column(
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = tr("Parameter"), modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(text = tr("Previous"), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Text(text = tr("Current"), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }

                    comparison.differences.forEach { diff ->
                        val diffColor = when (diff.status.lowercase()) {
                            "improved" -> Color(0xFF2E7D32)
                            "worsened" -> Color(0xFFC62828)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val rowBg = if (diff.status.lowercase() == "worsened") Color(0xFFFFEBEE).copy(alpha = 0.4f) else Color.Transparent
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = diff.name, modifier = Modifier.weight(2f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = diff.previous,
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = diff.current, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = diffColor, textAlign = TextAlign.Center)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (diff.status == "improved") Color(0xFFE8F5E9) else if (diff.status == "worsened") Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = diff.change.replaceFirstChar { it.uppercase() },
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = diffColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSummaryChip(label: String, name: String?, date: String?, type: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(name ?: tr("Unknown"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(date ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Text(type ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun ChangeGroup(label: String, items: List<String>, color: Color, bgColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = color, modifier = Modifier.size(8.dp))
                Text(item, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var name = "document"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx != -1 && cursor.moveToFirst()) name = cursor.getString(idx)
    }
    return name
}

private fun uriToMultipart(context: Context, uri: Uri, partName: String): MultipartBody.Part? {
    return try {
        val contentResolver = context.contentResolver
        var fileName = getFileName(context, uri)
        fileName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, "compare_${partName}_$fileName")
        tempFile.outputStream().use { inputStream.copyTo(it) }
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val requestFile = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
        MultipartBody.Part.createFormData(partName, tempFile.name, requestFile)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
