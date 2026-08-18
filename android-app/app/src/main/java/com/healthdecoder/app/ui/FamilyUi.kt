package com.healthdecoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.FamilyProfile
import kotlinx.coroutines.launch

private val avatarChoices = listOf("👤", "🧑", "👩", "👨", "👵", "👴", "🧒", "👧", "👦", "🧓")
private val sexChoices = listOf("Male", "Female", "Other")

/** Whole years from a YYYY-MM-DD date of birth, or null if unparseable/blank. */
fun familyAge(dob: String): Int? {
    val parts = dob.trim().split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    val cal = java.util.Calendar.getInstance()
    val now = java.util.Calendar.getInstance()
    cal.set(y, m - 1, d)
    if (cal.after(now)) return null
    var age = now.get(java.util.Calendar.YEAR) - y
    val beforeBirthday = now.get(java.util.Calendar.MONTH) < (m - 1) ||
        (now.get(java.util.Calendar.MONTH) == (m - 1) && now.get(java.util.Calendar.DAY_OF_MONTH) < d)
    if (beforeBirthday) age--
    return age.takeIf { it in 0..130 }
}

/** One-line "Papa • Father • M • 58y" style summary for a profile. */
fun familySubtitle(p: FamilyProfile): String = buildList {
    if (p.relation.isNotBlank()) add(p.relation)
    if (p.sex.isNotBlank()) add(p.sex.first().uppercase())
    familyAge(p.dateOfBirth)?.let { add("${it}y") }
}.joinToString(" • ")

/**
 * Add or edit one family member. [existing] null = add mode. On save it persists via
 * LocalRepository (a rename cascades to that person's records) and calls [onSaved].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyEditDialog(existing: FamilyProfile?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var relation by remember { mutableStateOf(existing?.relation ?: "") }
    var sex by remember { mutableStateOf(existing?.sex ?: "") }
    var dob by remember { mutableStateOf(existing?.dateOfBirth ?: "") }
    var emoji by remember { mutableStateOf(existing?.avatarEmoji ?: "👤") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add family member" else "Edit ${existing.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Avatar picker
                Text(tr("Avatar"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    avatarChoices.forEach { e ->
                        val sel = e == emoji
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .border(if (sel) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = relation, onValueChange = { relation = it },
                    label = { Text(tr("Relation (e.g. Father, Self)")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Text(tr("Sex"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sexChoices.forEach { s ->
                        FilterChip(selected = sex == s, onClick = { sex = if (sex == s) "" else s }, label = { Text(s) })
                    }
                }

                OutlinedTextField(
                    value = dob, onValueChange = { dob = it },
                    label = { Text(tr("Birthdate (YYYY-MM-DD)")) },
                    placeholder = { Text("1968-04-15") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { familyAge(dob)?.let { Text("Age: $it") } },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        val ok = if (existing == null) {
                            LocalRepository.addFamilyMember(context, name, relation, sex, dob.trim(), emoji)
                        } else {
                            LocalRepository.updateFamilyMember(context, existing.id, name, relation, sex, dob.trim(), emoji); true
                        }
                        busy = false
                        if (ok) onSaved() else error = "A member with that name already exists."
                    }
                }
            ) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

/** Lists family members with add/edit/remove. Removing is only offered for people with no records
 *  (rename/merge is the tool for a mis-scanned duplicate). */
@Composable
fun FamilyManagerDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<FamilyProfile>>(emptyList()) }
    var editing by remember { mutableStateOf<FamilyProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) { members = LocalRepository.familyMembers(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Family / Patients"), fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(members, key = { it.id }) { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(m.avatarEmoji, fontSize = 22.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Bold)
                            familySubtitle(m).takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Default.Edit, contentDescription = tr("Edit")) }
                        IconButton(onClick = {
                            scope.launch {
                                if (LocalRepository.reportCountFor(context, m.name) > 0) {
                                    // Has records — can't just remove; guide to rename/merge instead.
                                    editing = m
                                } else {
                                    LocalRepository.removeFamilyMember(context, m.id); refresh++; onChanged()
                                }
                            }
                        }) { Icon(Icons.Default.Delete, contentDescription = tr("Remove"), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(tr("Add"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } }
    )

    if (adding) FamilyEditDialog(existing = null, onDismiss = { adding = false }, onSaved = { adding = false; refresh++; onChanged() })
    editing?.let { m ->
        FamilyEditDialog(existing = m, onDismiss = { editing = null }, onSaved = { editing = null; refresh++; onChanged() })
    }
}

/**
 * Patient selector for the Scan screen: pick an existing family member or "New person…" (which
 * reveals a name field). [value] is the chosen patient name, empty = auto-detect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPatientPicker(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var members by remember { mutableStateOf<List<FamilyProfile>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { members = LocalRepository.familyMembers(context) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        manualEntry -> "New person"
                        value.isBlank() -> "Auto-detect patient"
                        else -> value
                    },
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(tr("Auto-detect from report")) }, onClick = { manualEntry = false; onValueChange(""); menuOpen = false })
                members.forEach { m ->
                    DropdownMenuItem(
                        text = { Text("${m.avatarEmoji}  ${m.name}" + familySubtitle(m).let { if (it.isNotBlank()) "  ·  $it" else "" }) },
                        onClick = { manualEntry = false; onValueChange(m.name); menuOpen = false }
                    )
                }
                DropdownMenuItem(
                    text = { Text(tr("➕  New person…")) },
                    onClick = { manualEntry = true; onValueChange(""); menuOpen = false }
                )
            }
        }
        if (manualEntry) {
            OutlinedTextField(
                value = value, onValueChange = onValueChange,
                label = { Text(tr("New patient name")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
