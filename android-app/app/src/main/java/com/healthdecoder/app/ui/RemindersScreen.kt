package com.healthdecoder.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.MedicationHistory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    onNavigateBack: () -> Unit,
    onNavigateToChat: () -> Unit,
    focus: String = "medicines",  // "medicines" (Medication Reminders) or "appointments" (Doctor Appointments)
    modifier: Modifier = Modifier
) {
    val showAppointments = focus == "appointments"
    val screenTitle = if (showAppointments) "Doctor Appointments" else "Medication Reminders"
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var medicationHistory by remember { mutableStateOf<List<MedicationHistory>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = ""
            try {
                medicationHistory = LocalRepository.getDashboard(context, null).medicationHistory
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to fetch reminders. Make sure your local server is running and configured correctly in settings."
            } finally {
                isLoading = false
            }
        }
    }

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
                        Text(tr(screenTitle), fontWeight = FontWeight.Bold)
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
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = tr(errorMessage),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (isLoading && medicationHistory.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                TodaysMedicinesTab(medicationHistory = medicationHistory, showAppointments = showAppointments)
            }
        }
    }
}
