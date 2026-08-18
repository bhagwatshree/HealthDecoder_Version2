package com.healthdecoder.app.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.local.BackgroundScanScheduler
import com.healthdecoder.app.model.DashboardData
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToScan: () -> Unit,
    onNavigateToChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var dashboardData by remember { mutableStateOf<DashboardData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var selectedPeriod by remember { mutableStateOf<String?>(null) }
    var ftsMatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            ftsMatchIds = emptySet()
        } else {
            kotlinx.coroutines.delay(250)
            ftsMatchIds = runCatching { com.healthdecoder.app.local.LocalRepository.searchReportIds(context, searchQuery) }
                .getOrDefault(emptySet())
        }
    }

    fun loadDashboard() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = ""
            try {
                dashboardData = com.healthdecoder.app.local.LocalRepository.getDashboard(context, selectedPeriod)
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to fetch records. Make sure your local server is running and configured correctly in settings."
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadDashboard() }
    LaunchedEffect(Unit) { BackgroundScanScheduler.onJobCompleted.collect { loadDashboard() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TopBarLogo()
                        Text(tr("Records"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToChat) {
                        Icon(imageVector = Icons.Default.QuestionAnswer, contentDescription = tr("Ask about this page"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToScan,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = tr("Scan Report"))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .appWatermark()
        ) {
            BackgroundScanProgressBar(onNavigateToDetail = onNavigateToDetail)

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(tr("Search reports, patient, or document text...")) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = tr("Search")) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = tr("Clear"))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            val periods = listOf(
                null to tr("All Time"),
                "1m" to tr("1 Month"),
                "3m" to tr("3 Months"),
                "6m" to tr("6 Months")
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(periods) { (value, label) ->
                    FilterChip(
                        selected = selectedPeriod == value,
                        onClick = {
                            selectedPeriod = value
                            loadDashboard()
                        },
                        label = { Text(label, fontSize = 12.sp) },
                        leadingIcon = if (selectedPeriod == value) {{
                            Icon(imageVector = Icons.Default.Check, contentDescription = tr("Selected"), modifier = Modifier.size(14.dp))
                        }} else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = tr("Warning"), tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = tr(errorMessage),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (isLoading && dashboardData == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val data = dashboardData
                    val filteredReports = data?.reports.orEmpty().filter { report ->
                        val patientMatch = report.patientName?.contains(searchQuery, ignoreCase = true) == true
                        val commentsMatch = report.comments?.contains(searchQuery, ignoreCase = true) == true
                        val typeMatch = report.reportType?.contains(searchQuery, ignoreCase = true) == true
                        val medMatch = report.medications.any { it.name.contains(searchQuery, ignoreCase = true) }
                        val textMatch = report.id in ftsMatchIds
                        patientMatch || commentsMatch || typeMatch || medMatch || textMatch
                    }
                        // Order by the document's own date (newest first) — that's the order a
                        // patient/doctor actually reads records in, especially when catching up
                        // on old paperwork scanned in a different order than it happened. Falls
                        // back to scan time only when a report has no resolved date at all, so
                        // it still surfaces somewhere sensible instead of sorting to the bottom.
                        .sortedWith(
                            compareByDescending<com.healthdecoder.app.model.MedicalReport> { it.reportDate?.takeIf { d -> d.isNotBlank() } ?: it.createdAt }
                                .thenByDescending { it.createdAt }
                        )

                    if (filteredReports.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.History,
                            title = if (searchQuery.isNotEmpty()) tr("No matching reports") else tr("No scanned history"),
                            description = if (searchQuery.isNotEmpty()) tr("Try searching a different name") else tr("Press 'Scan Report' to upload your first prescription.")
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val inferences = data?.testInferences.orEmpty()
                            if (inferences.isNotEmpty() && searchQuery.isEmpty()) {
                                item {
                                    ClinicalInsightsPanel(
                                        inferences = inferences,
                                        onInferenceClick = { reportId -> onNavigateToDetail(reportId) }
                                    )
                                }
                            }
                            items(filteredReports, key = { it.id }) { report ->
                                ReportItemCard(report = report, onClick = { onNavigateToDetail(report.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}
