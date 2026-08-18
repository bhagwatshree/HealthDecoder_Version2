package com.healthdecoder.app.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.DashboardData
import com.healthdecoder.app.model.MedicationBulkItem
import com.healthdecoder.app.model.MedicationHistory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationTrackerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var dashboardData by remember { mutableStateOf<DashboardData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var selectedMedForDetails by remember { mutableStateOf<MedicationHistory?>(null) }
    val medInfo = rememberMedicineInfoController()

    var medSelectionMode by remember { mutableStateOf(false) }
    val selectedMedKeys = remember { mutableStateListOf<String>() }
    var showBulkFrequencyDialog by remember { mutableStateOf(false) }
    fun medKey(m: MedicationHistory) = "${m.reportId}::${m.medicineName}"
    fun exitSelection() {
        medSelectionMode = false
        selectedMedKeys.clear()
    }
    fun toggleMed(m: MedicationHistory) {
        val k = medKey(m)
        if (selectedMedKeys.contains(k)) selectedMedKeys.remove(k) else selectedMedKeys.add(k)
    }

    fun loadDashboard() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = ""
            try {
                dashboardData = LocalRepository.getDashboard(context, null)
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to fetch medications. Make sure your local server is running and configured correctly in settings."
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadDashboard() }

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
                        Text(tr("Medications"), fontWeight = FontWeight.Bold)
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
        bottomBar = {
            if (medSelectionMode) {
                val allMeds = dashboardData?.medicationHistory ?: emptyList()
                val selected = allMeds.filter { selectedMedKeys.contains(medKey(it)) }
                Surface(
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = tr("Cancel selection"))
                        }
                        Text(text = "${selected.size} ${tr("selected")}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { showBulkFrequencyDialog = true },
                            enabled = selected.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Frequency"))
                        }
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        LocalRepository.bulkDeleteMedications(
                                            context,
                                            selected.map { MedicationBulkItem(it.reportId, it.medicineName, it.patientName) }
                                        )
                                        exitSelection()
                                        loadDashboard()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        errorMessage = "Failed to delete the selected medicines."
                                    }
                                }
                            },
                            enabled = selected.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Delete"))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .appWatermark()
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(tr("Search medicine name, dosage...")) },
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

            if (errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = tr(errorMessage),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (isLoading && dashboardData == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filteredMeds = dashboardData?.medicationHistory.orEmpty().filter { med ->
                        med.medicineName.contains(searchQuery, ignoreCase = true) ||
                            med.patientName.contains(searchQuery, ignoreCase = true)
                    }

                    if (filteredMeds.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.Medication,
                            title = if (searchQuery.isNotEmpty()) tr("No matching medications") else tr("No medication history"),
                            description = tr("All medications extracted from prescriptions will show up here along with their dosage history.")
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredMeds, key = { "${it.patientName}-${it.medicineName}" }) { med ->
                                MedicationHistoryCard(
                                    med = med,
                                    selectionMode = medSelectionMode,
                                    selected = selectedMedKeys.contains(medKey(med)),
                                    onClick = {
                                        if (medSelectionMode) toggleMed(med)
                                        else selectedMedForDetails = med
                                    },
                                    onLongClick = {
                                        if (!medSelectionMode) medSelectionMode = true
                                        toggleMed(med)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedMedForDetails != null) {
            MedicationDetailsDialog(
                med = selectedMedForDetails!!,
                onDismiss = { selectedMedForDetails = null },
                onUpdateSuccess = {
                    selectedMedForDetails = null
                    loadDashboard()
                },
                onWhyUsed = {
                    val m = selectedMedForDetails
                    selectedMedForDetails = null
                    if (m != null) medInfo.open(context, m.medicineName)
                }
            )
        }

        // Hosts the "why is this medicine used?" confirm dialog + info sheet.
        MedicineInfoHost(medInfo)

        if (showBulkFrequencyDialog) {
            val allMeds = dashboardData?.medicationHistory ?: emptyList()
            val selected = allMeds.filter { selectedMedKeys.contains(medKey(it)) }
            var bulkFrequency by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showBulkFrequencyDialog = false },
                title = { Text(tr("Change Frequency"), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Apply a new frequency to all ${selected.size} selected medicine(s).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = bulkFrequency,
                            onValueChange = { bulkFrequency = it },
                            label = { Text(tr("New Frequency")) },
                            placeholder = { Text(tr("e.g. 1-0-1, twice daily, as needed")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            tr("Tip: 1-0-1 = morning & night, 1-1-1 = three times a day."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    LocalRepository.bulkUpdateMedications(
                                        context,
                                        selected.map {
                                            MedicationBulkItem(
                                                reportId = it.reportId,
                                                medicineName = it.medicineName,
                                                patientName = it.patientName,
                                                frequency = bulkFrequency
                                            )
                                        }
                                    )
                                    showBulkFrequencyDialog = false
                                    exitSelection()
                                    loadDashboard()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    errorMessage = "Failed to update the selected medicines."
                                    showBulkFrequencyDialog = false
                                }
                            }
                        },
                        enabled = bulkFrequency.isNotBlank() && selected.isNotEmpty()
                    ) {
                        Text("${tr("Apply to")} ${selected.size}")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBulkFrequencyDialog = false }) {
                        Text(tr("Cancel"))
                    }
                }
            )
        }
    }
}
