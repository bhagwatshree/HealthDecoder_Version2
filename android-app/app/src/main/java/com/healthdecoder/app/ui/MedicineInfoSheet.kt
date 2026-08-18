package com.healthdecoder.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.ai.MedicalEngine
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.MedicineInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drop-in "tap a medicine name to learn why it's used" flow, shared by the Reminders and Medication
 * screens. Create one with [rememberMedicineInfoController], place [MedicineInfoHost] once in the
 * screen, and call [MedicineInfoController.open] from any medicine-name click.
 *
 * If the medicine was looked up before, its saved reference opens immediately; otherwise a small
 * dialog asks first (the first lookup uses AI, then it's stored). A name the AI doesn't recognise —
 * e.g. one you typed in by hand — comes back as "not recognised / no info available".
 */
class MedicineInfoController {
    var pendingConfirm by mutableStateOf<String?>(null)   // waiting for the user to confirm a lookup
        internal set
    var showFor by mutableStateOf<String?>(null)          // sheet currently open for this name
        internal set

    /** Handle a medicine-name tap: open the saved reference if we have it, else ask to look it up. */
    fun open(context: Context, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        if (MedicalEngine.getCachedMedicineInfo(context, n) != null) showFor = n else pendingConfirm = n
    }
}

@Composable
fun rememberMedicineInfoController(): MedicineInfoController = remember { MedicineInfoController() }

/** Hosts the confirm dialog + info sheet for a [MedicineInfoController]. Place once per screen. */
@Composable
fun MedicineInfoHost(controller: MedicineInfoController) {
    controller.pendingConfirm?.let { name ->
        AlertDialog(
            onDismissRequest = { controller.pendingConfirm = null },
            icon = { Icon(Icons.Default.Medication, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(tr("Why is this medicine used?")) },
            text = {
                Text("${tr("Get a short, easy explanation of why")} \"$name\" ${tr("is prescribed, plus key tips. " +
                    "The first lookup uses the internet, then it's saved so it opens instantly next time.")}")
            },
            confirmButton = {
                Button(onClick = { controller.showFor = name; controller.pendingConfirm = null }) {
                    Text(tr("Look it up"))
                }
            },
            dismissButton = { TextButton(onClick = { controller.pendingConfirm = null }) { Text(tr("Not now")) } }
        )
    }
    controller.showFor?.let { name ->
        MedicineInfoSheet(medicineName = name, onDismiss = { controller.showFor = null })
    }
}

/**
 * Category → (emoji-label, icon, accent colour) mapping for visual badges.
 */
private data class CategoryStyle(val label: String, val icon: ImageVector, val color: Color)

private val CATEGORY_STYLES = mapOf(
    "antibiotic"        to CategoryStyle("🧬 Antibiotic",        Icons.Default.Biotech,       Color(0xFF1565C0)),
    "antacid"           to CategoryStyle("🩹 Antacid",           Icons.Default.Healing,       Color(0xFF00838F)),
    "painkiller"        to CategoryStyle("💊 Painkiller",        Icons.Default.Medication,    Color(0xFFD84315)),
    "anti-inflammatory" to CategoryStyle("🔥 Anti-inflammatory", Icons.Default.LocalFireDepartment, Color(0xFFEF6C00)),
    "vitamin/supplement" to CategoryStyle("💪 Vitamin/Supplement", Icons.Default.FitnessCenter, Color(0xFF2E7D32)),
    "antidiabetic"      to CategoryStyle("🩸 Antidiabetic",      Icons.Default.Bloodtype,     Color(0xFF6A1B9A)),
    "antihypertensive"  to CategoryStyle("❤️ Antihypertensive",  Icons.Default.FavoriteBorder, Color(0xFFC62828)),
    "antihistamine"     to CategoryStyle("🤧 Antihistamine",     Icons.Default.AcUnit,        Color(0xFF0277BD)),
    "steroid"           to CategoryStyle("⚡ Steroid",           Icons.Default.FlashOn,       Color(0xFFF9A825)),
    "antifungal"        to CategoryStyle("🛡️ Antifungal",        Icons.Default.Shield,        Color(0xFF4E342E)),
    "antiviral"         to CategoryStyle("🦠 Antiviral",         Icons.Default.BugReport,     Color(0xFF283593)),
    "bronchodilator"    to CategoryStyle("🫁 Bronchodilator",    Icons.Default.Air,           Color(0xFF00695C)),
    "laxative"          to CategoryStyle("🌿 Laxative",          Icons.Default.Spa,           Color(0xFF558B2F)),
    "probiotic"         to CategoryStyle("🦠 Probiotic",         Icons.Default.Spa,           Color(0xFF1B5E20)),
    "cardiac"           to CategoryStyle("❤️ Cardiac",           Icons.Default.Favorite,      Color(0xFFB71C1C)),
    "antipyretic"       to CategoryStyle("🌡️ Antipyretic",       Icons.Default.Thermostat,    Color(0xFFE65100)),
    "muscle relaxant"   to CategoryStyle("🏋️ Muscle Relaxant",   Icons.Default.FitnessCenter, Color(0xFF4527A0)),
    "antidepressant"    to CategoryStyle("🧠 Antidepressant",    Icons.Default.Psychology,    Color(0xFF00838F)),
    "other"             to CategoryStyle("💊 Medicine",          Icons.Default.Medication,    Color(0xFF546E7A)),
    "unknown"           to CategoryStyle("❓ Unknown",           Icons.Default.HelpOutline,   Color(0xFF78909C))
)

private fun styleFor(category: String): CategoryStyle {
    return CATEGORY_STYLES[category.trim().lowercase()] ?: CATEGORY_STYLES["other"]!!
}

/**
 * A bottom sheet that shows medicine info (category, basic use, key notes) and lets
 * the user edit the name and save corrections for OCR misreads.
 *
 * @param medicineName  The initial medicine name to look up.
 * @param reportId      Optional report ID — needed for "Save corrected name" to work.
 * @param onDismiss     Called when the sheet is dismissed.
 * @param onNameCorrected Called after the user saves a corrected name, with (oldName, newName).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicineInfoSheet(
    medicineName: String,
    reportId: String? = null,
    onDismiss: () -> Unit,
    onNameCorrected: ((oldName: String, newName: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editableName by remember { mutableStateOf(medicineName) }
    var currentLookupName by remember { mutableStateOf(medicineName) }
    var info by remember { mutableStateOf<MedicineInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var saveSuccess by remember { mutableStateOf<Boolean?>(null) }

    val nameChanged = editableName.trim() != medicineName.trim() &&
            editableName.trim() != currentLookupName.trim()
    val canSaveName = editableName.trim() != medicineName.trim() &&
            editableName.trim().isNotEmpty() && reportId != null

    // Initial lookup
    LaunchedEffect(medicineName) {
        isLoading = true
        info = withContext(Dispatchers.IO) {
            MedicalEngine.lookupMedicineInfo(context, medicineName)
        }
        isLoading = false
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────
            Text(
                tr("Medicine Reference"),
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ── Editable Name Field ────────────────────────────────────
            OutlinedTextField(
                value = editableName,
                onValueChange = { editableName = it },
                label = { Text(tr("Medicine Name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (nameChanged) {
                        IconButton(onClick = {
                            currentLookupName = editableName.trim()
                            scope.launch {
                                isLoading = true
                                info = withContext(Dispatchers.IO) {
                                    MedicalEngine.lookupMedicineInfo(context, editableName.trim())
                                }
                                isLoading = false
                            }
                        }) {
                            Icon(
                                Icons.Default.Search,
                                tr("Look Up"),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            // Look Up button (when name changed)
            if (nameChanged) {
                Button(
                    onClick = {
                        currentLookupName = editableName.trim()
                        scope.launch {
                            isLoading = true
                            info = withContext(Dispatchers.IO) {
                                MedicalEngine.lookupMedicineInfo(context, editableName.trim())
                            }
                            isLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${tr("Look Up")} \"${editableName.trim()}\"", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // ── Loading State ──────────────────────────────────────────
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Looking up ${currentLookupName}…",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── Info Content ───────────────────────────────────────────
            if (!isLoading && info != null) {
                val medicineInfo = info!!
                val catStyle = styleFor(medicineInfo.category)

                // Category Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(catStyle.color.copy(alpha = 0.1f))
                        .border(1.dp, catStyle.color.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(catStyle.color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            catStyle.icon, tr("Category"),
                            tint = catStyle.color,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            catStyle.label,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = catStyle.color
                        )
                        if (medicineInfo.genericName.isNotEmpty()) {
                            Text(
                                medicineInfo.genericName,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Basic Use — "Why it's prescribed"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    )
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
                                Icons.Default.Info,
                                tr("Info"),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                tr("Why It's Prescribed"),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            medicineInfo.basicUse,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Key Notes
                if (medicineInfo.keyNotes.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Lightbulb,
                                    tr("Tips"),
                                    tint = Color(0xFFF9A825),
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    tr("Key Notes"),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            medicineInfo.keyNotes.forEach { note ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 7.dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Text(
                                        note,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Save Corrected Name button
                if (canSaveName) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF8E1)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    tr("Edit"),
                                    tint = Color(0xFFF57F17),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    tr("Name Correction"),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF57F17)
                                )
                            }
                            Text(
                                "The name \"$medicineName\" will be updated to \"${editableName.trim()}\" in this report.",
                                fontSize = 14.sp,
                                color = Color(0xFF5D4037),
                                lineHeight = 20.sp
                            )

                            if (saveSuccess == true) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                    Text(tr("Name saved!"), color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isSaving = true
                                            val ok = LocalRepository.renameMedicine(
                                                context, reportId!!, medicineName, editableName.trim()
                                            )
                                            isSaving = false
                                            saveSuccess = ok
                                            if (ok) onNameCorrected?.invoke(medicineName, editableName.trim())
                                        }
                                    },
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57F17)),
                                    contentPadding = PaddingValues(vertical = 12.dp)
                                ) {
                                    if (isSaving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                    } else {
                                        Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(tr("Save Corrected Name"), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Disclaimer
                Text(
                    tr("ℹ️ AI-generated reference. Always consult your doctor for medical advice."),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}
