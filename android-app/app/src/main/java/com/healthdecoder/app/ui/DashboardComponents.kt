package com.healthdecoder.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.model.DashboardData
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.MedLogEntry
import com.healthdecoder.app.model.MedicationBulkItem
import com.healthdecoder.app.model.MedicationBulkRequest
import com.healthdecoder.app.model.MedicationHistory
import com.healthdecoder.app.model.PendingTest
import com.healthdecoder.app.network.NetworkModule
import com.healthdecoder.app.local.BackgroundScanScheduler
import com.healthdecoder.app.local.ScanJobStatus
import kotlinx.coroutines.launch

@Composable
fun EmptyStateView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = tr("Empty"),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

fun calculateEndDate(startDateStr: String, durationStr: String): String {
    if (durationStr.isBlank()) return "Ongoing"
    try {
        val cleanDate = startDateStr.split("T")[0] // Handle ISO format
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val date = format.parse(cleanDate) ?: return durationStr
        val calendar = java.util.Calendar.getInstance()
        calendar.time = date
        
        val cleanDuration = durationStr.lowercase().trim()
        val pattern = java.util.regex.Pattern.compile("(\\d+)")
        val matcher = pattern.matcher(cleanDuration)
        if (matcher.find()) {
            val value = matcher.group(1)?.toInt() ?: 0
            if (cleanDuration.contains("day")) {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, value)
            } else if (cleanDuration.contains("week")) {
                calendar.add(java.util.Calendar.WEEK_OF_YEAR, value)
            } else if (cleanDuration.contains("month")) {
                calendar.add(java.util.Calendar.MONTH, value)
            } else {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, value)
            }
            
            val outFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            return outFormat.format(calendar.time)
        }
    } catch (e: Exception) {
        // Fallback
    }
    return durationStr
}

fun parseRoutine(frequencyStr: String, dosageStr: String): List<Pair<String, Boolean>> {
    val freq = (frequencyStr + " " + dosageStr).lowercase()
    
    var morning = false
    var afternoon = false
    var evening = false
    var night = false

    // An explicit clock time ("at 6 pm", "8am", "10 pm") is unambiguous — place the dose by the time
    // of day rather than by a position code, so "0-0-1 (at 6 pm)" is Evening, not Night.
    val timeMatches = Regex("""(\d{1,2})\s*(?::\s*\d{2})?\s*([ap])\.?\s*m""").findAll(freq).toList()
    if (timeMatches.isNotEmpty()) {
        for (mt in timeMatches) {
            var h = mt.groupValues[1].toIntOrNull() ?: continue
            val pm = mt.groupValues[2] == "p"
            if (pm && h != 12) h += 12
            if (!pm && h == 12) h = 0
            when {
                h < 12 -> morning = true
                h < 16 -> afternoon = true
                h < 21 -> evening = true
                else   -> night = true
            }
        }
        return listOf("Morning" to morning, "Afternoon" to afternoon, "Evening" to evening, "Night" to night)
    }

    if (freq.contains("1-0-1") || freq.contains("1 - 0 - 1")) {
        morning = true
        night = true
    } else if (freq.contains("1-1-1") || freq.contains("1 - 1 - 1")) {
        morning = true
        afternoon = true
        night = true
    } else if (freq.contains("1-0-0") || freq.contains("1 - 0 - 0")) {
        morning = true
    } else if (freq.contains("0-1-0") || freq.contains("0 - 1 - 0")) {
        afternoon = true
    } else if (freq.contains("0-0-1") || freq.contains("0 - 0 - 1")) {
        night = true
    } else if (freq.contains("twice daily") || freq.contains("twice a day") || freq.contains("bid") || freq.contains("b.i.d.")) {
        morning = true
        night = true
    } else if (freq.contains("thrice daily") || freq.contains("three times") || freq.contains("tid") || freq.contains("t.i.d.")) {
        morning = true
        afternoon = true
        night = true
    } else {
        if (freq.contains("morning") || freq.contains("breakfast") || freq.contains("am")) morning = true
        if (freq.contains("afternoon") || freq.contains("lunch") || freq.contains("noon")) afternoon = true
        if (freq.contains("evening") || freq.contains("snack") || freq.contains("pm")) evening = true
        if (freq.contains("night") || freq.contains("dinner") || freq.contains("bedtime") || freq.contains("hs")) night = true
        
        // If nothing is matched, check if it contains once daily
        if (!morning && !afternoon && !evening && !night) {
            if (freq.contains("once daily") || freq.contains("od") || freq.contains("daily")) {
                morning = true
            } else {
                morning = true // default to morning
            }
        }
    }
    
    return listOf(
        Pair("Morning", morning),
        Pair("Afternoon", afternoon),
        Pair("Evening", evening),
        Pair("Night", night)
    )
}

/**
 * Maps a medicine's [weeklySchedule] strings (e.g. ["Wednesday","Saturday"]) plus any free-text
 * frequency into Calendar.DAY_OF_WEEK codes (1=Sun .. 7=Sat). An EMPTY result means "every day"
 * (the normal daily case) — only weekly scripts that name specific days produce codes, so a
 * twice-weekly medicine like Tolvaptan (Wed & Sat) reminds only on those days.
 */
fun parseWeekdays(weeklySchedule: List<String>, frequencyStr: String): List<Int> {
    // The structured schedule is the doctor's actual answer to "which days"; the free-text
    // frequency is consulted only when the schedule names none. Reading BOTH is what caused a
    // 5-days-a-week anticoagulant to remind on its two off days: the frequency text names those
    // days precisely BECAUSE they are excluded, and merging the two strings lost that.
    //
    // Note there is deliberately no early return for "Everyday": the model emits it as the
    // schema's default even for a medicine whose frequency text then says "Thursday & Sunday
    // off", and short-circuiting on it skipped the only place the restriction was written down.
    // It names no weekday, so it simply falls through to the frequency text and, when that names
    // none either, to the empty "no restriction" answer it meant in the first place.
    val fromSchedule = activeWeekdaysIn(weeklySchedule.joinToString(" "))
    if (fromSchedule.isNotEmpty()) return fromSchedule
    return activeWeekdaysIn(frequencyStr)
}

/** Day names, matched on word boundaries so "month" is not a Monday and "saturation" not a Saturday. */
private val DAY_PATTERNS: List<Pair<Int, Regex>> = listOf(
    1 to """sun(day)?""", 2 to """mon(day)?""", 3 to """tue(s|sday)?""", 4 to """wed(nesday)?""",
    5 to """thu(r|rs|rsday)?""", 6 to """fri(day)?""", 7 to """sat(urday)?"""
).map { (code, body) -> code to Regex("""\b${body}s?\b""", RegexOption.IGNORE_CASE) }

/** Wording that flips named days from "take on these" to "skip these". */
private val DAY_EXCLUSION = Regex("""\b(off|except|excluding|omit|skip|holiday)\b""", RegexOption.IGNORE_CASE)

/**
 * Days named in [text] as Calendar.DAY_OF_WEEK codes (1=Sun..7=Sat), honouring exclusion wording:
 * "Thursday & Sunday off" yields Mon/Tue/Wed/Fri/Sat, not Thursday and Sunday. An empty result
 * means the text named no days at all, which callers read as "every day".
 */
private fun activeWeekdaysIn(text: String): List<Int> {
    if (text.isBlank()) return emptyList()
    val named = DAY_PATTERNS.filter { (_, pattern) -> pattern.containsMatchIn(text) }.map { it.first }.toSet()
    if (named.isEmpty()) return emptyList()
    if (!DAY_EXCLUSION.containsMatchIn(text)) return named.sorted()
    // Every day excluded is not a real prescription; fall back to the named days rather than
    // returning empty, which callers would read as "every day" — the opposite of what it says.
    return ((1..7).toSet() - named).sorted().ifEmpty { named.sorted() }
}

fun parseFoodInstruction(frequencyStr: String, dosageStr: String): String? {
    val text = (frequencyStr + " " + dosageStr).lowercase()
    return when {
        text.contains("empty stomach") || text.contains("empty") -> "Empty Stomach"
        text.contains("before food") || text.contains("before meal") || text.contains("before breakfast") || text.contains("before lunch") || text.contains("before dinner") -> "Before Meals"
        text.contains("after food") || text.contains("after meal") || text.contains("after breakfast") || text.contains("after lunch") || text.contains("after dinner") -> "After Meals"
        text.contains("with food") || text.contains("with meal") -> "Take With Food"
        else -> null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MedicationHistoryCard(
    med: MedicationHistory,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val activeDays = remember(med.weeklySchedule, med.currentFrequency) {
        getActiveDaysFromSchedule(med.weeklySchedule, med.currentFrequency)
    }
    val routine = remember(med.currentFrequency, med.currentDosage) {
        parseRoutine(med.currentFrequency, med.currentDosage)
    }
    val foodInstruction = remember(med.currentFrequency, med.currentDosage) {
        parseFoodInstruction(med.currentFrequency, med.currentDosage)
    }
    val calculatedEndDate = remember(med.lastUpdated, med.currentDuration) {
        calculateEndDate(med.lastUpdated, med.currentDuration)
    }
    val plainInstruction = remember(med.currentFrequency, med.currentDosage) {
        getFrequencyExplanation(med.currentFrequency, med.currentDosage)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Selection checkbox row (only in multi-select mode)
            if (selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (selected) tr("Selected") else tr("Not selected"),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (selected) tr("Selected") else tr("Tap to select"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Patient Name & Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = med.patientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                
                val badgeColors = when (med.status.lowercase()) {
                    "active" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32)) // Green
                    "changed" -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100)) // Orange
                    else -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828)) // Red (Discontinued)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColors.first)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = med.status.uppercase(),
                        color = badgeColors.second,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Medicine Name & Food Instruction chip
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = med.medicineName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (foodInstruction != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restaurant,
                            contentDescription = tr("Food instruction"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = foodInstruction,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Dynamic Decoded Instruction block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = tr("Instruction"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = plainInstruction,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Routine visual capsules
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = tr("Daily Dosage Routine"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    routine.forEach { (slot, active) ->
                        val slotIcon = when (slot) {
                            "Morning" -> Icons.Default.WbSunny
                            "Afternoon" -> Icons.Default.WbSunny
                            "Evening" -> Icons.Default.WbSunny
                            else -> Icons.Default.Bedtime
                        }
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer 
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .border(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) 
                                    else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = slotIcon,
                                    contentDescription = slot,
                                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = slot,
                                    fontSize = 10.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            // Dosage details + Calendar Schedule strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Dosage & Frequency"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (med.currentFrequency.isNotEmpty()) "${med.currentDosage} (${med.currentFrequency})" else med.currentDosage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("Duration"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = med.currentDuration.ifEmpty { tr("Ongoing") },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (med.currentDuration.isNotEmpty()) {
                            Text(
                                text = "Ends: $calculatedEndDate",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // Calendar Days Strip (Active Highlighted)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (med.isOptional) tr("Optional / As Needed") else tr("Weekly Schedule"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (med.isOptional) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (med.isOptional) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val dayLabels = listOf(tr("M"), tr("T"), tr("W"), tr("T"), tr("F"), tr("S"), tr("S"))
                            dayLabels.forEachIndexed { idx, day ->
                                val active = activeDays.getOrElse(idx) { true }
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (med.status.lowercase() == "discontinued") {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            } else if (active) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = day,
                                        color = if (med.status.lowercase() == "discontinued") {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        } else if (active) {
                                            Color.White
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Last verified on: ${med.lastUpdated}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                
                if (med.previousDosage.isNotEmpty()) {
                    Text(
                        text = "Prev: ${med.previousDosage} ${med.previousFrequency.takeIf { it.isNotEmpty() }?.let { "($it)" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun PendingTestCard(
    test: PendingTest,
    onResolveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onFindCentersClick: () -> Unit
) {
    val isPending = test.status == "Pending"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = test.patientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isPending) Color(0xFFFFEBEE) else Color(0xFFE8F5E9))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isPending) tr("Report not available") else tr("Completed"),
                        color = if (isPending) Color(0xFFC62828) else Color(0xFF2E7D32),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Test name and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = test.testName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = tr("Delete Reminder"),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(tr("Due Date"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = test.dueDate ?: tr("No timeline specified"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPending) MaterialTheme.colorScheme.onSurface else Color(0xFF2E7D32)
                    )
                }

                if (isPending) {
                    TextButton(onClick = onFindCentersClick) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(imageVector = Icons.Default.Search, contentDescription = tr("Find Labs"), modifier = Modifier.size(16.dp))
                            Text(tr("Find Lab Centers"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (test.resolvedReportId != null) {
                    TextButton(onClick = onResolveClick) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(imageVector = Icons.Default.Visibility, contentDescription = tr("View Report"), modifier = Modifier.size(16.dp))
                            Text(tr("View Scanned Report"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Several reports extracted from ONE scanned document, drawn inside a single box so it is obvious
 * they came from the same PDF and were only split apart for detail. The patient and date are
 * stated once in the header instead of on every panel, and the source file is named because that
 * is the least ambiguous way to say "these are the same piece of paper".
 *
 * Each panel inside is still its own tappable card opening its own detail screen — the split is
 * real, this only makes the relationship visible.
 */
@Composable
fun ReportGroupCard(
    reports: List<MedicalReport>,
    onReportClick: (String) -> Unit
) {
    val first = reports.first()
    val sourceName = reports.firstNotNullOfOrNull { r -> r.sourceFiles.firstOrNull()?.name?.takeIf { it.isNotBlank() } }

    // The box is read by its OUTLINE, not by its fill. A tinted fill alone cannot work in both
    // themes from one value — light enough to show against a white page is invisible against a
    // near-black one, and vice versa — so the grouping is carried by a definite primary-tinted
    // border, with the fill only lifting the container a step away from the page behind it.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                .compositeOver(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Patient: ${first.patientName ?: "Unknown Patient"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = first.reportDate ?: tr("No Date"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = listOfNotNull(
                        sourceName,
                        "${reports.size} ${tr("reports from this document")}"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            reports.forEach { report ->
                ReportItemCard(
                    report = report,
                    onClick = { onReportClick(report.id) },
                    insideGroup = true
                )
            }
        }
    }
}

@Composable
fun ReportItemCard(
    report: MedicalReport,
    onClick: () -> Unit,
    // True when this card is a panel INSIDE a document group. The group already draws the outline
    // and states the patient and date once in its header, so a panel repeats neither: a second
    // border inside the first reads as a box in a box, and repeating "Patient / date" on every
    // panel of one PDF is most of what made that list hard to read. A standalone report keeps
    // both, so every top-level row in the list carries the same outline whether it came from a
    // document with one report or eight.
    insideGroup: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (insideGroup) null
            else BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val typeColor = when (report.reportType?.lowercase()) {
                "prescription" -> Pair(Color(0xFFE3F2FD), Color(0xFF1565C0)) // Blue
                "lab report" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32)) // Green
                else -> Pair(Color(0xFFECEFF1), Color(0xFF455A64)) // Grey
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(typeColor.first)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = (report.reportType ?: tr("Report")).uppercase(),
                    color = typeColor.second,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!insideGroup) {
                Text(
                    text = "Patient: ${report.patientName ?: "Unknown Patient"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = report.reportDate ?: tr("No Date"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (report.medications.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    report.medications.forEach { med ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            val medLabel = if (med.dosage.isNotBlank()) "${med.name} ${med.dosage}" else med.name
                            Text(
                                text = medLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!report.comments.isNullOrBlank()) {
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = report.comments,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

fun getFrequencyExplanation(frequency: String, dosage: String): String {
    val freq = frequency.trim().lowercase()
    val dose = dosage.trim().ifEmpty { "1 pill/tablet" }
    
    if (freq.contains("1-0-1") || freq.contains("1 - 0 - 1")) {
        return "Take $dose in the Morning and $dose at Night."
    }
    if (freq.contains("1-1-1") || freq.contains("1 - 1 - 1")) {
        return "Take $dose in the Morning, $dose in the Afternoon, and $dose at Night."
    }
    if (freq.contains("1-0-0") || freq.contains("1 - 0 - 0")) {
        return "Take $dose in the Morning only."
    }
    if (freq.contains("0-1-0") || freq.contains("0 - 1 - 0")) {
        return "Take $dose in the Afternoon only."
    }
    if (freq.contains("0-0-1") || freq.contains("0 - 0 - 1")) {
        return "Take $dose at Night only."
    }
    if (freq.contains("1/2-0-1/2") || freq.contains("0.5-0-0.5")) {
        return "Take half ($dose) in the Morning and half ($dose) at Night."
    }
    if (freq.contains("1/2-0-0") || freq.contains("0.5-0-0")) {
        return "Take half ($dose) in the Morning only."
    }
    if (freq.contains("0-0-1/2") || freq.contains("0-0-0.5")) {
        return "Take half ($dose) at Night only."
    }
    if (freq.contains("twice daily") || freq.contains("twice a day") || freq.contains("bid") || freq.contains("b.i.d.")) {
        return "Take $dose twice a day (typically Morning and Night)."
    }
    if (freq.contains("thrice daily") || freq.contains("three times") || freq.contains("tid") || freq.contains("t.i.d.")) {
        return "Take $dose three times a day (Morning, Afternoon, and Night)."
    }
    if (freq.contains("once daily") || freq.contains("od") || freq.contains("daily")) {
        return "Take $dose once daily."
    }
    if (freq.contains("every 6 hours") || freq.contains("q6h")) {
        return "Take $dose every 6 hours."
    }
    if (freq.contains("every 4 hours") || freq.contains("q4h")) {
        return "Take $dose every 4 hours."
    }
    if (freq.contains("every 8 hours") || freq.contains("q8h")) {
        return "Take $dose every 8 hours."
    }
    if (freq.contains("every 12 hours") || freq.contains("q12h")) {
        return "Take $dose every 12 hours."
    }
    
    if (freq.contains("alternate") || freq.contains("alt")) {
        return "Take $dose every alternate day."
    }
    if (freq.contains("weekly") || freq.contains("once a week")) {
        return "Take $dose once a week."
    }
    if (freq.contains("2 days a week") || freq.contains("twice weekly") || freq.contains("2 days/week") || freq.contains("2 days / week")) {
        return "Take $dose two days a week."
    }
    if (freq.contains("3 days a week") || freq.contains("three times a week") || freq.contains("3 days/week")) {
        return "Take $dose three days a week."
    }
    
    if (freq.contains("as needed") || freq.contains("prn") || freq.contains("sos") || freq.contains("optional") || freq.contains("whenever needed")) {
        return "Take $dose only when needed / optionally."
    }
    
    return "Take $dose according to instructions: $frequency"
}

fun getActiveDaysFromSchedule(weeklySchedule: List<String>, frequencyStr: String): List<Boolean> {
    if (weeklySchedule.isNotEmpty() && !weeklySchedule.contains("Everyday")) {
        if (weeklySchedule.contains("As Needed") || weeklySchedule.contains("Optional")) {
            return listOf(false, false, false, false, false, false, false)
        }
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return days.map { day -> weeklySchedule.any { it.startsWith(day, ignoreCase = true) } }
    }
    
    val freq = frequencyStr.lowercase()
    if (freq.contains("as needed") || freq.contains("prn") || freq.contains("sos") || freq.contains("optional") || freq.contains("whenever needed")) {
        return listOf(false, false, false, false, false, false, false)
    }
    
    // Same negation-aware parse the reminders use, so the day dots shown here can never disagree
    // with the days the alarms actually fire on. Codes are Calendar's (1=Sun..7=Sat); this list
    // is Mon..Sun.
    val activeCodes = activeWeekdaysIn(frequencyStr)
    if (activeCodes.isNotEmpty()) {
        return listOf(2, 3, 4, 5, 6, 7, 1).map { it in activeCodes }
    }
    
    if (freq.contains("alternate") || freq.contains("alt")) {
        return listOf(true, false, true, false, true, false, true)
    }
    
    if (freq.contains("weekly") || freq.contains("once a week") || freq.contains("1 day a week")) {
        return listOf(false, false, false, false, false, false, true)
    }
    
    if (freq.contains("2 days a week") || freq.contains("twice weekly") || freq.contains("2 days/week") || freq.contains("2 days / week")) {
        return listOf(true, false, false, true, false, false, false)
    }
    
    if (freq.contains("3 days a week") || freq.contains("three times a week") || freq.contains("3 days/week")) {
        return listOf(true, false, true, false, true, false, false)
    }
    
    return listOf(true, true, true, true, true, true, true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailsDialog(
    med: MedicationHistory,
    onDismiss: () -> Unit,
    onUpdateSuccess: () -> Unit,
    onWhyUsed: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var medName by remember { mutableStateOf(med.medicineName) }
    var dosage by remember { mutableStateOf(med.currentDosage) }
    var frequency by remember { mutableStateOf(med.currentFrequency) }
    var duration by remember { mutableStateOf(med.currentDuration) }
    var isOptional by remember { mutableStateOf(med.isOptional) }
    var weeklySchedule by remember { mutableStateOf(med.weeklySchedule) }
    var notes by remember { mutableStateOf(med.notes) }
    var startDate by remember { mutableStateOf(med.currentStartDate ?: "") }
    var endDate by remember { mutableStateOf(med.currentEndDate ?: "") }
    
    var intakeLogs by remember { mutableStateOf<List<MedLogEntry>>(emptyList()) }
    var isLogsLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    
    val loadLogs = {
        coroutineScope.launch {
            isLogsLoading = true
            try {
                intakeLogs = LocalRepository.getMedicationLogs(context, med.patientName, med.medicineName)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLogsLoading = false
            }
        }
    }
    
    LaunchedEffect(med) {
        loadLogs()
    }
    
    val formatTimestamp = { timestampStr: String ->
        try {
            val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val date = inputFormat.parse(timestampStr)
            if (date != null) {
                val outputFormat = java.text.SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", java.util.Locale.getDefault())
                outputFormat.format(date)
            } else {
                timestampStr
            }
        } catch (e: Exception) {
            timestampStr
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = med.medicineName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Patient: ${med.patientName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    OutlinedButton(
                        onClick = onWhyUsed,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Why is this medicine used?"), fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = tr("DIRECTIONS FOR PATIENT"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = getFrequencyExplanation(frequency, dosage),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("RECORD INTAKE"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        LocalRepository.logMedicationIntake(
                                            context, med.patientName, med.medicineName, "TAKEN", frequency
                                        )
                                        loadLogs()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = tr("Log Dose"))
                                Text(tr("Log Dose Taken Now"), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = tr("EDIT PRESCRIPTION METADATA"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        OutlinedTextField(
                            value = medName,
                            onValueChange = { medName = it },
                            label = { Text(tr("Medicine Name")) },
                            placeholder = { Text(tr("e.g. Metformin 500mg")) },
                            singleLine = true,
                            supportingText = {
                                Text(tr("Fixing a mis-scanned name updates it in every report, reminder and log for this patient."))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = dosage,
                            onValueChange = { dosage = it },
                            label = { Text(tr("Dosage")) },
                            placeholder = { Text(tr("e.g. 1 tablet")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        OutlinedTextField(
                            value = frequency,
                            onValueChange = { frequency = it },
                            label = { Text(tr("Frequency String")) },
                            placeholder = { Text(tr("e.g. 1-0-1")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        OutlinedTextField(
                            value = duration,
                            onValueChange = { duration = it },
                            label = { Text(tr("Duration")) },
                            placeholder = { Text(tr("e.g. 5 days")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = startDate,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(tr("Starts")) },
                                        placeholder = { Text(tr("Not set")) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Box(
                                        modifier = Modifier.matchParentSize().clickable {
                                            val cal = java.util.Calendar.getInstance()
                                            android.app.DatePickerDialog(
                                                context,
                                                { _, y, m, d -> startDate = "%04d-%02d-%02d".format(y, m + 1, d) },
                                                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)
                                            ).show()
                                        }
                                    )
                                }
                                if (startDate.isNotEmpty()) {
                                    TextButton(onClick = { startDate = "" }) { Text(tr("Clear")) }
                                }
                            }
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = endDate,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(tr("Ends")) },
                                        placeholder = { Text(tr("Not set")) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Box(
                                        modifier = Modifier.matchParentSize().clickable {
                                            val cal = java.util.Calendar.getInstance()
                                            android.app.DatePickerDialog(
                                                context,
                                                { _, y, m, d -> endDate = "%04d-%02d-%02d".format(y, m + 1, d) },
                                                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)
                                            ).show()
                                        }
                                    )
                                }
                                if (endDate.isNotEmpty()) {
                                    TextButton(onClick = { endDate = "" }) { Text(tr("Clear")) }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text(tr("Special Patient Notes")) },
                            placeholder = { Text(tr("e.g. Take with warm water")) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tr("Optional / As Needed"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(tr("Check this if taken only when symptoms arise (e.g. SOS, PRN)"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isOptional,
                                onCheckedChange = { 
                                    isOptional = it
                                    if (it) {
                                        weeklySchedule = listOf("As Needed")
                                    } else {
                                        weeklySchedule = listOf("Everyday")
                                    }
                                }
                            )
                        }
                    }
                }
                
                if (!isOptional) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = tr("CUSTOM WEEKLY SCHEDULE"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(tr("Select which days the patient should take this medicine:"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            
                            val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                daysOfWeek.forEach { day ->
                                    val isSelected = weeklySchedule.contains(day) || (weeklySchedule.contains("Everyday") || weeklySchedule.isEmpty())
                                    
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .clickable {
                                                val currentList = if (weeklySchedule.contains("Everyday") || weeklySchedule.isEmpty()) {
                                                    daysOfWeek.toMutableList()
                                                } else {
                                                    weeklySchedule.toMutableList()
                                                }
                                                
                                                if (currentList.contains(day)) {
                                                    currentList.remove(day)
                                                } else {
                                                    currentList.add(day)
                                                }
                                                
                                                if (currentList.size == 7) {
                                                    weeklySchedule = listOf("Everyday")
                                                } else if (currentList.isEmpty()) {
                                                    weeklySchedule = listOf("Everyday")
                                                } else {
                                                    weeklySchedule = currentList
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = day.take(1),
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("RECENT ACTIVITY & HISTORY"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        if (isLogsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                        } else if (intakeLogs.isEmpty()) {
                            Text(
                                text = tr("No recorded logs for this medicine yet."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                intakeLogs.take(5).forEach { log ->
                                    val action = log.actionType
                                    val time = log.takenAt
                                    val logNotes = log.notes ?: ""
                                    
                                    val displayText = when (action) {
                                        "TAKEN" -> tr("Dose recorded as taken")
                                        "UPDATE_DETAILS" -> tr("Frequency/details updated")
                                        else -> action
                                    }
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = displayText,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (action == "TAKEN") Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = formatTimestamp(time),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (logNotes.isNotEmpty()) {
                                            Text(
                                                text = logNotes,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        isSaving = true
                        try {
                            LocalRepository.updateMedicineEverywhere(
                                context, med.reportId, med.patientName, med.medicineName, medName,
                                dosage, frequency, duration, isOptional, weeklySchedule, notes,
                                // Always the live edited value (never null) — this dialog has no
                                // separate "untouched" state, so a blank string here must mean
                                // "clear it", not "leave it alone" (see resolveOptionalDate).
                                startDate.trim(), endDate.trim()
                            )
                            onUpdateSuccess()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving && medName.isNotBlank() && dosage.isNotEmpty() && frequency.isNotEmpty()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                } else {
                    Text(tr("Save Changes"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Close"))
            }
        }
    )
}

@Composable
fun ClinicalInsightsPanel(
    inferences: List<com.healthdecoder.app.model.TestInference>,
    onInferenceClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = tr("Insights"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = tr("Clinical Insights"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = tr("Based on your latest uploaded reports, here is what has changed and the differences:"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                inferences.forEach { inference ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onInferenceClick(inference.reportId) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Keep this at "what kind of document is it" granularity (Discharge
                                // Summary, Prescription, Medical Report) — not the specific panel
                                // name a multi-section scan extracted (e.g. "Haemogram (CBC)",
                                // "Doppler"), which reads as clutter when several such cards are
                                // all really about the same single visit/document.
                                val type = inference.reportType.lowercase()
                                val categoryLabel = when {
                                    type.contains("discharge") ->
                                        if (inference.hasMedications) tr("Discharge Summary + Prescription") else tr("Discharge Summary")
                                    inference.reportCategory.equals("prescription", ignoreCase = true) || type.contains("prescription") ->
                                        tr("Prescription")
                                    else -> tr("Medical Report")
                                }
                                Text(
                                    text = "${inference.patientName} • $categoryLabel",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                StatusBadge(inference.status)
                            }
                            Text(
                                text = inference.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Date: ${inference.reportDate}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = tr("View details"),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = tr("Details"),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
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
}

@Composable
fun BackgroundScanProgressBar(
    onNavigateToDetail: (String) -> Unit
) {
    val activeJobs = BackgroundScanScheduler.activeJobs
    if (activeJobs.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        activeJobs.forEach { job ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = job.status == ScanJobStatus.COMPLETED) {
                        job.resultReportId?.let { onNavigateToDetail(it) }
                        BackgroundScanScheduler.removeJob(job.id)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = when (job.status) {
                        ScanJobStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                        ScanJobStatus.COMPLETED -> Color(0xFFE8F5E9)
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                ),
                elevation = CardDefaults.cardElevation(2.dp)
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (job.status == ScanJobStatus.COMPLETED) {
                                Icon(Icons.Default.CheckCircle, contentDescription = tr("Success"), tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                            } else if (job.status == ScanJobStatus.ERROR) {
                                Icon(Icons.Default.Error, contentDescription = tr("Error"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                            Text(
                                text = tr("Active Ingestion"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (job.status) {
                                    ScanJobStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                                    ScanJobStatus.COMPLETED -> Color(0xFF1B5E20)
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                        }
                        val (badgeText, badgeColor) = when (job.status) {
                            ScanJobStatus.COMPLETED -> tr("DONE") to Color(0xFF2E7D32)
                            ScanJobStatus.ERROR -> tr("FAILED") to MaterialTheme.colorScheme.error
                            else -> tr("PROCESSING") to MaterialTheme.colorScheme.primary
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(text = badgeText, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        if (job.status == ScanJobStatus.COMPLETED || job.status == ScanJobStatus.ERROR) {
                            IconButton(
                                onClick = { BackgroundScanScheduler.removeJob(job.id) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = tr("Close"), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    val statusText = when (job.status) {
                        ScanJobStatus.UPLOADING -> tr("Uploading report...")
                        ScanJobStatus.OCR -> tr("Analyzing with AI...")
                        ScanJobStatus.SAVING -> tr("Saving details...")
                        ScanJobStatus.COMPLETED -> tr("Report scan complete! Tap to view.")
                        ScanJobStatus.ERROR -> job.error ?: tr("Scan failed.")
                    }
                    Text(
                        text = "${tr("Analyzing")}: ${job.patientName} (${job.scanType.replaceFirstChar { it.lowercase() }}) — $statusText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Duplicate scan: let the user jump to the already-saved document (e.g. to delete it).
                    if (job.status == ScanJobStatus.ERROR && job.duplicateReportId != null) {
                        Button(
                            onClick = {
                                job.duplicateReportId?.let { onNavigateToDetail(it) }
                                BackgroundScanScheduler.removeJob(job.id)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("View existing document"), fontWeight = FontWeight.Bold)
                        }
                    }

                    if (job.status != ScanJobStatus.COMPLETED && job.status != ScanJobStatus.ERROR) {
                        LinearProgressIndicator(
                            progress = { job.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        )

                        val steps = listOf(tr("OCR Extractor"), tr("Gemini Analysis"), tr("Database Sync"))
                        val currentStep = when (job.status) {
                            ScanJobStatus.UPLOADING -> 0
                            ScanJobStatus.OCR -> 1
                            ScanJobStatus.SAVING -> 2
                            else -> -1
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            steps.forEachIndexed { index, step ->
                                Text(
                                    text = if (index < steps.lastIndex) "$step • " else step,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (index == currentStep) FontWeight.Bold else FontWeight.Normal,
                                    color = if (index == currentStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

