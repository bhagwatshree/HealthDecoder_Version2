package com.healthdecoder.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.DemoDataSeeder
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.ui.components.AppBottomNavBar
import com.healthdecoder.app.ui.components.BottomNavTab
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import com.healthdecoder.app.model.FamilyProfile


private data class HomeAction(
    val label: String,
    val emoji: String,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRecords: () -> Unit,
    onNavigateToMedicationTracker: () -> Unit,
    onNavigateToReminders: () -> Unit,
    onNavigateToAppointments: () -> Unit = {},
    onNavigateToPendingTests: () -> Unit,
    onNavigateToDiscovery: (String) -> Unit,
    onNavigateToLiveVision: () -> Unit,
    onNavigateToTab: (BottomNavTab) -> Unit = {},
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isBackendReady = false

    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var profiles by remember { mutableStateOf(listOf<FamilyProfile>()) }
    var selectedProfile by remember { mutableStateOf<FamilyProfile?>(null) }
    var expandedProfileMenu by remember { mutableStateOf(false) }
    var showFamilyManager by remember { mutableStateOf(false) }
    var familyReload by remember { mutableStateOf(0) }
    val isLoggedIn = AppSettings.isLoggedIn(context)

    LaunchedEffect(familyReload) {
        // Real, persisted family members (includes people added with no reports yet).
        val loaded = LocalRepository.familyMembers(context)
        profiles = loaded
        val active = AppSettings.getActivePatient(context)
        // Never "Everyone" — always resolve to a real profile once one exists. A stale/renamed
        // active name, or a persisted null from before this screen existed, both fall back to
        // the first profile rather than showing everyone's data under a "Hello, Name" header.
        selectedProfile = when {
            loaded.isEmpty() -> null
            active != null -> loaded.firstOrNull { it.name.equals(active, ignoreCase = true) } ?: loaded.first()
            else -> loaded.first()
        }
        // Keep the persisted "active patient" in sync with what the header now shows — everything
        // downstream (dashboard, Trends, Chat, ...) that reads getActivePatient() needs to scope to
        // the same person the header displays, not a stale or null value.
        if (loaded.isNotEmpty()) {
            AppSettings.setActivePatient(context, selectedProfile?.name)
        }
    }

    if (showFamilyManager) {
        FamilyManagerDialog(
            onDismiss = { showFamilyManager = false; familyReload++ },
            onChanged = { familyReload++; onRefresh() }
        )
    }

    val actions = buildList {
        add(HomeAction("Scan Report", "📸", Color(0xFFE8F5E9), Color(0xFF2E7D32), onNavigateToScan))
        add(HomeAction("Records", "📜", Color(0xFFECEFF1), Color(0xFF455A64), onNavigateToRecords))
        add(HomeAction("Medication Reminders", "⏰", Color(0xFFFFF3E0), Color(0xFFE65100), onNavigateToReminders))
        add(HomeAction("Doctor Appointments", "📅", Color(0xFFE8EAF6), Color(0xFF283593), onNavigateToAppointments))
        add(HomeAction("Medications", "💊", Color(0xFFF3E5F5), Color(0xFF6A1B9A), onNavigateToMedicationTracker))
        add(HomeAction("Pending Tests", "🚨", Color(0xFFFFF9C4), Color(0xFFC62828), onNavigateToPendingTests))
        if (isBackendReady) {
            add(HomeAction("Find Doctors", "🩺", Color(0xFFE0F2F1), Color(0xFF00796B), { onNavigateToDiscovery("doctors") }))
            add(HomeAction("Find Labs", "🧪", Color(0xFFE0F7FA), Color(0xFF006064), { onNavigateToDiscovery("lab_tests") }))
            add(HomeAction("Find Hospitals", "🏥", Color(0xFFE1F5FE), Color(0xFF0277BD), { onNavigateToDiscovery("hospitals") }))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TopBarLogo(size = 32.dp)
                            Text(tr("Health Decoder"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    actions = {
                        LanguagePickerIcon()
                        IconButton(onClick = onNavigateToProfile, modifier = Modifier.size(40.dp)) {
                            Icon(imageVector = Icons.Default.AccountCircle, contentDescription = tr("Profile"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                    )
                )
            },
            bottomBar = {
                AppBottomNavBar(currentTab = BottomNavTab.Home, onNavigate = onNavigateToTab)
            }
        ) { innerPadding ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Greeting: "Welcome" (no profiles yet, no dropdown) / "Hello, Name ▼" (defaults
                    // to the first added profile) — never "Everyone". Refresh sits right after the
                    // name, since it reads naturally as "refresh this person's data".
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    if (profiles.isEmpty()) showFamilyManager = true
                                    else expandedProfileMenu = true
                                }
                            ) {
                                Text(
                                    text = selectedProfile?.let { "${it.avatarEmoji} ${tr("Hello,")} ${it.name}" } ?: tr("Welcome"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (profiles.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = tr("Switch Profile"),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = expandedProfileMenu,
                                onDismissRequest = { expandedProfileMenu = false }
                            ) {
                                profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text("${profile.avatarEmoji} ${profile.name} (${profile.relation})") },
                                        onClick = {
                                            selectedProfile = profile
                                            AppSettings.setActivePatient(context, profile.name)
                                            expandedProfileMenu = false
                                            onRefresh()
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(tr("➕ Add family member")) },
                                    onClick = { expandedProfileMenu = false; showFamilyManager = true }
                                )
                            }
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = tr("Refresh"))
                        }
                    }

                    if (selectedProfile?.name.equals(DemoDataSeeder.DEMO_PATIENT_NAME, ignoreCase = true)) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    tr("🧪 You're viewing sample demo data"),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    tr("This isn't real. Remove it, or sign in to start tracking your own records."),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        coroutineScope.launch {
                                            DemoDataSeeder.removeDemoData(context)
                                            familyReload++
                                            onRefresh()
                                        }
                                    }) { Text(tr("Remove Demo Data")) }
                                    Button(onClick = onNavigateToLogin) { Text(tr("Sign In")) }
                                }
                            }
                        }
                    }

                    HealthTipCard()

                    // Sign-in is optional and no longer surfaced from Settings, so this is the
                    // one persistent entry point for it — shown alongside the tip card, not
                    // instead of it, every visit until the user actually signs in.
                    if (!isLoggedIn) {
                        SignInBanner(onClick = onNavigateToLogin)
                    }

                    actions.chunked(2).forEach { rowActions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowActions.forEach { action ->
                                ActionSquare(action = action, modifier = Modifier.weight(1f))
                            }
                            if (rowActions.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    SmartHealthLensBanner(onClick = onNavigateToLiveVision)

                    BackgroundScanProgressBar(onNavigateToDetail = onNavigateToDetail)

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SignInBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tr("Sign in to sync your records"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    tr("Optional — keeps your data available across devices"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Button(onClick = onClick, shape = RoundedCornerShape(10.dp)) {
                Text(tr("Sign In"))
            }
        }
    }
}

@Composable
private fun ActionSquare(action: HomeAction, modifier: Modifier = Modifier) {
    val label = tr(action.label)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        label = "scale"
    )
    val elevation by animateFloatAsState(
        targetValue = if (isPressed) 0f else 3f,
        label = "elevation"
    )

    // Light mode keeps the exact same colors as today. Dark mode swaps roles instead of just
    // reusing the pale light-mode hex as-is (which would read as a washed-out floating card) or
    // flattening every tile to one neutral dark card (losing the color cue entirely, the mockup's
    // mistake): the saturated content hue becomes a low-alpha tint over the dark surface, and the
    // original pale container hue — already light enough — becomes the readable foreground.
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val bgColor = if (isDark) action.contentColor.copy(alpha = 0.22f).compositeOver(MaterialTheme.colorScheme.surface) else action.containerColor
    val fgColor = if (isDark) action.containerColor else action.contentColor

    // Every tile keeps the SAME fixed size (the original aspectRatio, untouched) so the grid
    // never looks uneven across rows or devices. A two-word label wraps to a second line within
    // that same fixed footprint instead of growing the card (which made a wrapped tile visibly
    // taller than its row neighbor) or shrinking to near-illegible size (which still fell back to
    // an ellipsis on real devices once font scale pushed past ~1.15x, because there's a hard
    // floor on how small text can get before it stops being legible). A slightly smaller, fixed
    // label size leaves enough headroom for two lines to fit the existing card height comfortably
    // even at a large system font scale; overflow="Ellipsis" is only a last-resort safety net.
    Card(
        modifier = modifier
            .aspectRatio(1.6f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = action.onClick
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = action.emoji,
                fontSize = 24.sp,
                // Extra gap before the label — since the icon+label pair is vertically centered
                // as a block, a bigger gap here also nudges the icon up and the label down within
                // that block, instead of the two sitting cramped together in the middle.
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = fgColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
