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
import com.healthdecoder.app.model.PendingTest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingTestsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToDiscovery: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var pendingTests by remember { mutableStateOf<List<PendingTest>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    var showAddTestDialog by remember { mutableStateOf(false) }
    var newPatientName by remember { mutableStateOf("") }
    var newTestName by remember { mutableStateOf("") }
    var newDueDate by remember { mutableStateOf("") }

    fun loadDashboard() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = ""
            try {
                pendingTests = LocalRepository.getDashboard(context, null).pendingTests
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to fetch pending tests. Make sure your local server is running and configured correctly in settings."
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
                        Text(tr("Pending Tests"), fontWeight = FontWeight.Bold)
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
                onClick = { showAddTestDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = tr("Add Test"))
                    Text(tr("Add Test Reminder"), fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(tr("Search test name, patient...")) },
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
                if (isLoading && pendingTests.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filteredTests = pendingTests.filter { test ->
                        test.testName.contains(searchQuery, ignoreCase = true) ||
                            test.patientName.contains(searchQuery, ignoreCase = true)
                    }

                    if (filteredTests.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.NotificationsActive,
                            title = if (searchQuery.isNotEmpty()) tr("No matching tests") else tr("No pending tests"),
                            description = tr("Tests recommended in prescriptions are automatically tracked. You can also add them manually.")
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredTests, key = { it.id }) { test ->
                                PendingTestCard(
                                    test = test,
                                    onResolveClick = {
                                        if (test.resolvedReportId != null) {
                                            onNavigateToDetail(test.resolvedReportId)
                                        }
                                    },
                                    onDeleteClick = {
                                        coroutineScope.launch {
                                            try {
                                                LocalRepository.deletePendingTest(context, test.id)
                                                loadDashboard()
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                errorMessage = "Failed to delete test reminder."
                                            }
                                        }
                                    },
                                    onFindCentersClick = {
                                        onNavigateToDiscovery(test.testName)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAddTestDialog) {
            AlertDialog(
                onDismissRequest = { showAddTestDialog = false },
                title = { Text(tr("Add Recommended Test"), fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newPatientName,
                            onValueChange = { newPatientName = it },
                            label = { Text(tr("Patient Name")) },
                            placeholder = { Text(tr("e.g. John Doe")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newTestName,
                            onValueChange = { newTestName = it },
                            label = { Text(tr("Recommended Test Name")) },
                            placeholder = { Text(tr("e.g. Blood Sugar Fasting (BSF)")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newDueDate,
                            onValueChange = { newDueDate = it },
                            label = { Text(tr("Estimated Due Date (YYYY-MM-DD)")) },
                            placeholder = { Text(tr("e.g. 2026-09-22")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                if (newPatientName.isBlank() || newTestName.isBlank()) {
                                    errorMessage = "Patient and Test names are required."
                                    showAddTestDialog = false
                                    return@launch
                                }
                                try {
                                    LocalRepository.createPendingTest(context, newPatientName, newTestName, newDueDate)
                                    showAddTestDialog = false
                                    newPatientName = ""
                                    newTestName = ""
                                    newDueDate = ""
                                    loadDashboard()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    errorMessage = "Failed to save test reminder."
                                    showAddTestDialog = false
                                }
                            }
                        },
                        enabled = newPatientName.isNotBlank() && newTestName.isNotBlank()
                    ) {
                        Text(tr("Add Reminder"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddTestDialog = false }) {
                        Text(tr("Cancel"))
                    }
                }
            )
        }
    }
}
