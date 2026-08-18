package com.healthdecoder.app.reminder

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.theme.MedicalScannerTheme
import kotlinx.coroutines.launch

/**
 * Full-screen medicine reminder for the "large text" style: wakes the screen, shows over
 * the lock screen, and displays every medicine due at this time in very large type so
 * elderly users can read the names clearly. "Taken" logs an intake for each medicine.
 */
class ReminderAlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the display.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val hour = intent.getIntExtra(MedicineReminderManager.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(MedicineReminderManager.EXTRA_MINUTE, -1)
        val due = MedicineReminderManager.dueMedicines(this, hour, minute)
        val slotLabel = due.firstOrNull()?.slot ?: "Medicine"

        // The reminder is being handled here; remove its notification.
        if (hour >= 0) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(MedicineReminderManager.notificationId(hour, minute))
        }

        setContent {
            MedicalScannerTheme {
                AlarmContent(
                    slotLabel = slotLabel,
                    due = due,
                    onTaken = {
                        lifecycleScope.launch {
                            for (med in due) {
                                runCatching {
                                    LocalRepository.logMedicationIntake(
                                        this@ReminderAlarmActivity,
                                        med.patientName, med.medicineName, "TAKEN", null
                                    )
                                }
                            }
                            finish()
                        }
                    },
                    onDismiss = {
                        if (hour >= 0) MedicineReminderManager.snooze(this, hour, minute)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun AlarmContent(
    slotLabel: String,
    due: List<MedicineReminderManager.DueMedicine>,
    onTaken: () -> Unit,
    onDismiss: () -> Unit
) {
    val multiPatient = due.map { it.patientName.lowercase() }.distinct().size > 1

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "$slotLabel Medicines",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                if (!multiPatient) {
                    due.firstOrNull()?.patientName?.takeIf { it.isNotBlank() }?.let {
                        Text(text = "For $it", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))

                if (due.isEmpty()) {
                    Text("No medicines are due right now.", fontSize = 24.sp, textAlign = TextAlign.Center)
                }
                due.forEach { med ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Very large so the medicine name is readable at arm's length.
                            Text(
                                text = med.medicineName,
                                fontSize = 40.sp,
                                lineHeight = 46.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center
                            )
                            if (med.dosage.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = med.dosage,
                                    fontSize = 26.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    textAlign = TextAlign.Center
                                )
                            }
                            if (multiPatient && med.patientName.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "For ${med.patientName}",
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onTaken,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text("Taken", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Remind me later", fontSize = 22.sp)
            }
        }
    }
}
