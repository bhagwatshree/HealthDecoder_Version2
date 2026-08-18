package com.healthdecoder.app.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.HealthSummary
import com.healthdecoder.app.model.Medication
import com.healthdecoder.app.reminder.AppointmentSchedule
import com.healthdecoder.app.reminder.AppointmentStore
import com.healthdecoder.app.reminder.doctorLabel
import com.healthdecoder.app.ui.components.AppBottomNavBar
import com.healthdecoder.app.ui.components.BottomNavTab
import com.healthdecoder.app.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the screen needs, built once so the UI can render real sections instead of one
 *  pre-formatted text blob — the flat text used for Share/TTS is derived from this, not the
 *  other way around. */
private data class DoctorBriefData(
    val patientName: String,
    val ageSexLine: String?,       // "76y, Male" or null if unknown
    val lastReportLine: String,    // "Last report: 2026-07-29 · Prothrombin Time[Panel]"
    val reportCount: Int,
    val narrative: String,
    val flags: List<String>,
    val keyResults: List<KeyResult>,
    val medicines: List<Medication>
)

private data class KeyResult(val name: String, val value: String, val unit: String, val status: String, val arrow: String)

private fun trendArrow(trend: String): String = when (trend) {
    "improving" -> "↗"; "worsening" -> "↘"; "increasing" -> "↑"; "decreasing" -> "↓"; else -> "→"
}

/** Plain-text version for Share and text-to-speech — built from the same structured data the
 *  screen renders, so the two can never drift apart. */
private fun DoctorBriefData.toPlainText(): String = buildString {
    append("🩺 Doctor Brief — $patientName")
    ageSexLine?.let { append(" ($it)") }
    append("\n$lastReportLine\n")
    append("$reportCount report(s) on file\n")
    if (narrative.isNotBlank()) append("\nSummary:\n$narrative\n")
    if (flags.isNotEmpty()) {
        append("\n⚠ Needs attention:\n")
        flags.forEach { append("• $it\n") }
    }
    if (keyResults.isNotEmpty()) {
        append("\nKey results (latest):\n")
        keyResults.forEach { r -> append("• ${r.name}: ${r.value} ${r.unit}".trim() + " (${r.arrow} ${r.status.ifBlank { "stable" }})\n") }
    }
    if (medicines.isNotEmpty()) {
        append("\n💊 Current medicines:\n")
        medicines.forEach { m ->
            val meta = listOf(m.dosage, m.frequency).filter { it.isNotBlank() }.joinToString(" · ")
            append("• ${m.name}${if (meta.isNotBlank()) " ($meta)" else ""}\n")
        }
    }
    append("\n— Prepared by Health Decoder")
}.trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorBriefScreen(
    patientName: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToTab: (BottomNavTab) -> Unit = {}
) {
    val context = LocalContext.current
    var appointment by remember { mutableStateOf<AppointmentSchedule?>(null) }
    var brief by remember { mutableStateOf<DoctorBriefData?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(patientName) {
        loading = true
        val (appt, data) = withContext(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val appts = AppointmentStore.loadAll(context)
            val next = appts.filter { it.date >= today }
                .sortedWith(compareBy({ it.date }, { it.time })).firstOrNull()
                ?: appts.sortedByDescending { it.date }.firstOrNull()

            val target = patientName.trim()
            val reports = LocalRepository.getReports(context)
                .filter { target.isBlank() || it.patientName.equals(target, ignoreCase = true) }
                .sortedByDescending { it.reportDate ?: it.createdAt }

            val result = if (reports.isEmpty()) {
                DoctorBriefData(
                    patientName = target.ifBlank { "this patient" },
                    ageSexLine = null,
                    lastReportLine = "No records found${if (target.isNotBlank()) " for $target" else ""} yet — scan a report first.",
                    reportCount = 0, narrative = "", flags = emptyList(), keyResults = emptyList(), medicines = emptyList()
                )
            } else {
                val who = target.ifBlank { reports.first().patientName ?: "Patient" }
                val summary: HealthSummary = LocalRepository.getHealthSummary(context, who, "all")
                val profile = LocalRepository.familyMembers(context).firstOrNull { it.name.equals(who, ignoreCase = true) }
                val ageSex = listOfNotNull(
                    profile?.let { familyAge(it.dateOfBirth) }?.let { "${it}y" },
                    profile?.sex?.takeIf { it.isNotBlank() }
                ).joinToString(", ").takeIf { it.isNotBlank() }

                val keyResults = summary.parameterTrends
                    .mapNotNull { t -> t.dataPoints.lastOrNull()?.let { last -> KeyResult(t.name, last.value, last.unit, last.status, trendArrow(t.trend)) } }

                val meds = summary.medicationTimeline.lastOrNull()?.activeMedicines
                    ?.filter { it.name.isNotBlank() }?.distinctBy { it.name.trim().lowercase() }
                    ?: reports.flatMap { it.medications }.filter { it.name.isNotBlank() }.distinctBy { it.name.trim().lowercase() }

                DoctorBriefData(
                    patientName = who,
                    ageSexLine = ageSex,
                    lastReportLine = reports.first().let { "Last report: ${it.reportDate ?: "—"} · ${it.reportType ?: "Report"}" },
                    reportCount = reports.size,
                    narrative = summary.overallNarrative,
                    flags = summary.activeFlags.take(5),
                    keyResults = keyResults,
                    medicines = meds
                )
            }
            next to result
        }
        appointment = appt
        brief = data
        loading = false
    }

    // ── Text-to-speech (on-device). Language set on the real instance once ready. ──
    var isPlaying by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
            }
        }
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isPlaying = true }
            override fun onDone(utteranceId: String?) { isPlaying = false }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { isPlaying = false }
        })
        tts = engine
        onDispose { engine.stop(); engine.shutdown() }
    }

    val shareTitle = tr("Share brief") // hoisted: tr() is @Composable
    fun shareBrief() {
        val data = brief
        if (data == null || loading) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Doctor Brief — ${data.patientName}")
            putExtra(Intent.EXTRA_TEXT, data.toPlainText())
        }
        runCatching { context.startActivity(Intent.createChooser(intent, shareTitle)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TopBarLogo()
                        Text(tr("Doctor Visit Brief"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { shareBrief() }, enabled = !loading) {
                        Icon(Icons.Default.Share, contentDescription = tr("Share"), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            AppBottomNavBar(currentTab = BottomNavTab.Brief, onNavigate = onNavigateToTab)
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .appWatermark()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            val data = brief ?: return@Column

            // ── Patient header ──────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) { Text("🧑", fontSize = 22.sp) }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(data.patientName, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                                data.ageSexLine?.let {
                                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(data.lastReportLine, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (data.reportCount > 0) {
                        Text(
                            "${data.reportCount} ${tr("report(s) on file")}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ── Appointment ──────────────────────────────────────────────────
            val a = appointment
            ClinicalSectionCard(
                title = tr("Upcoming Appointment"),
                icon = Icons.Default.CalendarMonth,
                accentColor = MaterialTheme.colorScheme.primary
            ) {
                if (a != null) {
                    ClinicalRow(title = a.doctorLabel(), subtitle = a.place.ifBlank { null }) {
                        Text("${a.date}\n${a.time}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                } else {
                    Text(tr("No upcoming appointment"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Needs attention ──────────────────────────────────────────────
            if (data.flags.isNotEmpty()) {
                ClinicalSectionCard(
                    title = tr("Needs Attention"),
                    icon = Icons.Default.Warning,
                    accentColor = ClinicalStatus.High
                ) {
                    data.flags.forEach { flag ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", color = ClinicalStatus.High, fontWeight = FontWeight.Bold)
                            Text(flag, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
                        }
                    }
                }
            }

            // ── Key results grid ──────────────────────────────────────────────
            if (data.keyResults.isNotEmpty()) {
                ClinicalSectionCard(
                    title = tr("Key Results"),
                    icon = Icons.Default.MonitorHeart,
                    accentColor = MaterialTheme.colorScheme.primary,
                    subtitle = tr("Latest value for each tracked test")
                ) {
                    val rows = data.keyResults.chunked(2)
                    rows.forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { r ->
                                ResultTile(
                                    label = r.name, value = r.value, unit = r.unit, status = r.status,
                                    trendArrow = r.arrow, modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── Current medicines ────────────────────────────────────────────
            if (data.medicines.isNotEmpty()) {
                ClinicalSectionCard(
                    title = tr("Active Medication Schedule"),
                    icon = Icons.Default.Medication,
                    accentColor = MaterialTheme.colorScheme.tertiary
                ) {
                    data.medicines.forEach { m ->
                        val meta = listOf(m.dosage, m.frequency).filter { it.isNotBlank() }.joinToString(" · ")
                        ClinicalRow(title = m.name, subtitle = meta.ifBlank { null })
                    }
                }
            }

            if (data.narrative.isNotBlank()) {
                ClinicalSectionCard(
                    title = tr("Clinical Summary"),
                    icon = Icons.Default.MonitorHeart,
                    accentColor = MaterialTheme.colorScheme.secondary
                ) {
                    Text(data.narrative, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            // ── Read-aloud ───────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (isPlaying) { tts?.stop(); isPlaying = false }
                            else {
                                val spoken = data.toPlainText().replace(Regex("[🩺⚠💊•↗↘↑↓→]"), "").replace("\n", ". ")
                                tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "BriefingTTS")
                                isPlaying = true
                            }
                        },
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).size(48.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop" else "Play", tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("Read the brief aloud"), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            if (isPlaying) tr("Playing…") else tr("Tap play to hear this summary"),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Button(
                onClick = { shareBrief() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Share with Doctor"), fontWeight = FontWeight.Bold)
            }
        }
    }
}
