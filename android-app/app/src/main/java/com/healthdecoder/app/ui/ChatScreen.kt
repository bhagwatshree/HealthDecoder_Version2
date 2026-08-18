package com.healthdecoder.app.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.ai.SpeechEngine
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.ChatMessage
import com.healthdecoder.app.model.ChatRequest
import com.healthdecoder.app.ui.components.AppBottomNavBar
import com.healthdecoder.app.ui.components.BottomNavTab
import com.healthdecoder.app.util.AudioPlayer
import com.healthdecoder.app.util.LanguageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SUGGESTED_QUESTIONS = listOf(
    "What do my latest test results mean?",
    "Explain my current medicines in simple words",
    "Are any of my values abnormal?",
    "What should I ask my doctor next?"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToScan: (String?) -> Unit,
    modifier: Modifier = Modifier,
    initialPatientName: String = "",
    // Which screen the user opened Chat from (e.g. "Records", "Medication Tracker"). Shown
    // in the top bar and folded into the question sent to the AI so answers stay scoped to
    // what's on screen, without needing a separate backend field.
    contextHint: String? = null,
    onNavigateToTab: (BottomNavTab) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var patientName by remember { mutableStateOf(initialPatientName) }
    var language by remember { mutableStateOf(AppSettings.getPreferredLanguage(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Hoisted here because tr() is @Composable and can't be called from inside the
    // coroutineScope.launch block below where these Toasts are shown.
    val navigatingToFindCareToast = tr("Action: Navigating to Find Care...")
    val reminderSetToastPrefix = tr("Action: Reminder set for")
    val reminderSetToastMid = tr("at")
    val appointmentBookedToastPrefix = tr("Action: Appointment booked with")
    val appointmentBookedToastMid1 = tr("on")
    val appointmentBookedToastMid2 = tr("at")

    val listState = rememberLazyListState()
    
    var attachedImagePath by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            Thread {
                val result = com.healthdecoder.app.util.FileImportUtil.importFile(context, it)
                if (result.images.isNotEmpty()) {
                    attachedImagePath = result.images.first().path
                }
            }.start()
        }
    }

    // Text-to-speech so answers can be read aloud (voice assistance for non-readers).
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { ttsRef.value?.language = LanguageUtil.localeFor(language) }
            }
        }
        ttsRef.value = engine
        onDispose { runCatching { engine.stop(); engine.shutdown() }; AudioPlayer.stop() }
    }
    LaunchedEffect(language) { runCatching { ttsRef.value?.language = LanguageUtil.localeFor(language) } }

    // Read aloud using the engine chosen in Settings: Phone (on-device), or Sarvam/Gemini
    // (cloud voices, better for Indic languages). Cloud engines fall back to phone TTS.
    fun phoneSpeak(text: String) {
        runCatching {
            ttsRef.value?.language = LanguageUtil.localeFor(language)
            ttsRef.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat")
        }
    }
    fun speak(text: String) {
        val engine = AppSettings.getVoiceEngine(context)
        if (engine == "Phone") { phoneSpeak(text); return }
        coroutineScope.launch {
            val played = runCatching {
                val audios = withContext(Dispatchers.IO) {
                    SpeechEngine.synthesize(context, text, language, engine.lowercase())
                }
                audios.isNotEmpty() && AudioPlayer.playBase64Wavs(context, audios)
            }.getOrDefault(false)
            if (!played) phoneSpeak(text)
        }
    }

    LaunchedEffect(messages.size, isLoading) {
        val target = messages.size + (if (isLoading) 1 else 0)
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    val sendQuestion: (String) -> Unit = sendLambda@{ question ->
        val trimmed = question.trim()
        if (trimmed.isEmpty() && attachedImagePath == null || isLoading) return@sendLambda
        errorMessage = ""
        val userMsg = if (trimmed.isEmpty()) "[Attached Image]" else trimmed
        messages.add(ChatMessage(role = "user", content = userMsg))
        input = ""
        val history = messages.dropLast(1).toList()
        isLoading = true
        val imageToSend = attachedImagePath
        attachedImagePath = null // clear for next message
        
        val questionForApi = if (contextHint != null) {
            "The user is currently viewing the \"$contextHint\" screen of the app. $trimmed"
        } else trimmed
        
        coroutineScope.launch {
            try {
                val response = LocalRepository.chat(
                    context,
                    ChatRequest(
                        question = questionForApi,
                        patientName = patientName.trim().ifEmpty { null },
                        history = history,
                        language = language,
                        imagePath = imageToSend
                    )
                )
                var finalAnswer = response.answer
                val toolRegex = "\\[TOOL:\\s*(.*?)\\s*\\]".toRegex()
                val match = toolRegex.find(finalAnswer)
                if (match != null) {
                    val toolCommand = match.groupValues[1]
                    finalAnswer = finalAnswer.replace(match.value, "").trim()
                    
                    if (toolCommand.startsWith("navigate")) {
                        android.widget.Toast.makeText(context, navigatingToFindCareToast, android.widget.Toast.LENGTH_LONG).show()
                    } else if (toolCommand.startsWith("scanDocument")) {
                        if (imageToSend != null) {
                            onNavigateToScan(imageToSend)
                        } else {
                            onNavigateToScan(null)
                        }
                    } else if (toolCommand.startsWith("setReminder")) {
                        val regex = "setReminder\\((.*?),(.*?)\\)".toRegex()
                        val reminderMatch = regex.find(toolCommand)
                        if (reminderMatch != null) {
                            val med = reminderMatch.groupValues[1].trim()
                            val time = reminderMatch.groupValues[2].trim()
                            
                            val existing = com.healthdecoder.app.reminder.MedicineScheduleStore.loadAll(context).find { it.medicineName == med }
                            val schedule = existing ?: com.healthdecoder.app.reminder.MedicineSchedule(
                                medicineName = med,
                                patientName = "Self",
                                dosage = "1 pill",
                                frequency = "Daily",
                                slots = mutableMapOf()
                            )
                            
                            var hour = 8
                            var min = 0
                            try {
                                val parts = time.split(":")
                                if (parts.size == 2) {
                                    hour = parts[0].trim().toInt()
                                    min = parts[1].trim().toInt()
                                }
                            } catch (e: Exception) {}
                            
                            val slots = schedule.slots.toMutableMap()
                            slots[time] = com.healthdecoder.app.reminder.SlotConfig(enabled = true, hour = hour, minute = min)
                            
                            val updatedSchedule = schedule.copy(slots = slots)
                            com.healthdecoder.app.reminder.MedicineScheduleStore.upsert(context, updatedSchedule)
                            
                            android.widget.Toast.makeText(context, "$reminderSetToastPrefix $med $reminderSetToastMid $time", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else if (toolCommand.startsWith("addAppointment")) {
                        val regex = "addAppointment\\((.*?),(.*?),(.*?)\\)".toRegex()
                        val apptMatch = regex.find(toolCommand)
                        if (apptMatch != null) {
                            val doctor = apptMatch.groupValues[1].trim()
                            val date = apptMatch.groupValues[2].trim()
                            val time = apptMatch.groupValues[3].trim()
                            
                            var hour = 9
                            var min = 0
                            try {
                                val parts = time.split(":")
                                if (parts.size == 2) {
                                    hour = parts[0].trim().toInt()
                                    min = parts[1].trim().toInt()
                                }
                            } catch (e: Exception) {}
                            
                            com.healthdecoder.app.reminder.AppointmentStore.upsert(context, com.healthdecoder.app.reminder.AppointmentSchedule(
                                doctorName = doctor,
                                date = date,
                                time = time,
                                place = "Unknown",
                                hour = hour,
                                minute = min,
                                patientName = AppSettings.getActivePatient(context).orEmpty()
                            ))
                            
                            android.widget.Toast.makeText(context, "$appointmentBookedToastPrefix $doctor $appointmentBookedToastMid1 $date $appointmentBookedToastMid2 $time", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }

                messages.add(ChatMessage(role = "assistant", content = finalAnswer))
                if (finalAnswer.isNotBlank()) speak(finalAnswer) // read the answer aloud
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Couldn't reach the assistant. Check that the server is running and configured in settings."
            } finally {
                isLoading = false
            }
        }
    }

    // Voice input (speech-to-text) in the selected language.
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) sendQuestion(spoken)
        }
    }
    fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LanguageUtil.tagFor(language))
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your question in $language")
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            errorMessage = "Voice input isn't available on this device. Please install/enable Google voice typing."
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
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = Color.LightGray
                        )
                        Column {
                            Text(tr("DocBot"), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                            Text(
                                text = contextHint?.let { "Asking about: $it" } ?: tr("online"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { messages.clear() }) {
                            Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = tr("Clear chat"))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF075E54),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Column {
                ChatInputBar(
                    input = input,
                    attachedImagePath = attachedImagePath,
                    onInputChange = { input = it },
                    onSend = { sendQuestion(input) },
                    onVoice = { startVoiceInput() },
                    onAttach = { imagePicker.launch(arrayOf("*/*")) },
                    onRemoveAttachment = { attachedImagePath = null },
                    enabled = !isLoading
                )
                AppBottomNavBar(currentTab = BottomNavTab.Chat, onNavigate = onNavigateToTab)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFECE5DD))
        ) {
            contextHint?.let { hint ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = tr("Asking about: "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Language selector + optional patient scope
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LanguageDropdown(
                    selected = language,
                    onSelected = {
                        language = it
                        AppSettings.setPreferredLanguage(context, it)
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = patientName,
                    onValueChange = { patientName = it },
                    label = { Text(tr("Patient (optional)")) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.2f)
                )
            }

            // Safety disclaimer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    tr("Explains your records in simple terms. Not a doctor — always confirm with your physician."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            if (errorMessage.isNotEmpty()) {
                Text(
                    text = tr(errorMessage),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty() && !isLoading) {
                    ChatEmptyState(onQuestionClick = { sendQuestion(it) }, onVoice = { startVoiceInput() })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages) { msg ->
                            ChatBubble(
                                message = msg,
                                onSpeak = if (msg.role == "assistant") ({ speak(msg.content) }) else null
                            )
                        }
                        if (isLoading) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .widthIn(max = 320.dp)
                                            .clip(
                                                RoundedCornerShape(
                                                    topStart = 16.dp, topEnd = 16.dp,
                                                    bottomStart = 4.dp, bottomEnd = 16.dp
                                                )
                                            )
                                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        TypingIndicator()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedCard(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = tr("Language"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = selected,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AppSettings.SUPPORTED_LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang, fontWeight = FontWeight.Medium) },
                    onClick = {
                        onSelected(lang)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatEmptyState(onQuestionClick: (String) -> Unit, onVoice: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.QuestionAnswer,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            tr("Ask by typing or tap the mic to speak"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            tr("I explain test results, medicines, and doctor's notes in plain language."),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )
        Button(onClick = onVoice, shape = RoundedCornerShape(24.dp)) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(tr("Speak your question"))
        }
        Spacer(Modifier.height(16.dp))
        SUGGESTED_QUESTIONS.forEach { q ->
            Surface(
                onClick = { onQuestionClick(q) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(tr(q), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onSpeak: (() -> Unit)?) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) Color(0xFFDCF8C6) else Color(0xFFFFFFFF)
    val textColor = Color.Black

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                shadowElevation = 1.dp,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(text = message.content, style = MaterialTheme.typography.bodyMedium, color = textColor)
                }
            }
            if (onSpeak != null) {
                TextButton(onClick = onSpeak, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.VolumeUp, contentDescription = tr("Read aloud"), modifier = Modifier.size(16.dp), tint = Color(0xFF075E54))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Listen"), fontSize = 12.sp, color = Color(0xFF075E54))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputBar(
    input: String,
    attachedImagePath: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .background(Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        if (attachedImagePath != null) {
            Box(modifier = Modifier.padding(bottom = 8.dp).padding(start = 8.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp) {
                    Box {
                        // In a real app we'd load the image Bitmap here using Coil or similar,
                        // For now we show an icon indicating an attachment is ready.
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = tr("Attachment"),
                            modifier = Modifier.size(64.dp).padding(8.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = onRemoveAttachment,
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp).padding(2.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = tr("Remove"), tint = Color.Red)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                TextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text(tr("Message"), color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
                    trailingIcon = {
                        IconButton(onClick = onAttach, enabled = enabled) {
                            Icon(Icons.Default.AttachFile, contentDescription = tr("Attach image"), tint = Color.Gray)
                        }
                    }
                )
            }
            
            val isTyping = input.isNotBlank() || attachedImagePath != null
            FloatingActionButton(
                onClick = { if (isTyping && enabled) onSend() else onVoice() },
                containerColor = Color(0xFF128C7E),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                elevation = FloatingActionButtonDefaults.elevation(2.dp)
            ) {
                Icon(
                    imageVector = if (isTyping) Icons.Default.Send else Icons.Default.Mic,
                    contentDescription = if (isTyping) tr("Send") else tr("Speak")
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val dotCount = 3
    val dotSize = 8.dp
    val delayUnit = 150

    val dots = (0 until dotCount).map { index ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = -6f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 400,
                    delayMillis = index * delayUnit
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot-$index"
        )
    }

    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { dotOffset ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .offset(y = dotOffset.value.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
        }
    }
}

