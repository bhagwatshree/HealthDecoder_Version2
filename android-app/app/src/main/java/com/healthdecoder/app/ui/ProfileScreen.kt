package com.healthdecoder.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.healthdecoder.app.auth.BiometricHelper
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.KeyAssignment
import com.healthdecoder.app.model.UpdateProfileRequest
import com.healthdecoder.app.model.UserAccount
import com.healthdecoder.app.network.AccountSync
import com.healthdecoder.app.network.NetworkModule
import com.healthdecoder.app.network.apiErrorMessage
import com.healthdecoder.app.network.httpCode
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val GENDER_OPTIONS = listOf(
    "Male" to "male",
    "Female" to "female",
    "Other" to "other",
    "Prefer not to say" to "prefer_not_to_say"
)

/**
 * Identity/account screen: the signed-in user's own details (editable), the family members they
 * manage, their BYOK Gemini key, password, and fingerprint login — everything that's about WHO is
 * using the app, as opposed to SettingsScreen's app-behavior and data-management concerns. Reached
 * via Home's top-bar profile icon, not a bottom-nav tab (it isn't one of the 6 first-class
 * destinations).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var account by remember { mutableStateOf<UserAccount?>(null) }
    var assignment by remember { mutableStateOf<KeyAssignment?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isFingerprintEnabled by remember { mutableStateOf(AppSettings.isBiometricEnabled(context)) }
    var fingerprintError by remember { mutableStateOf<String?>(null) }

    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var deleteAccountError by remember { mutableStateOf<String?>(null) }

    var showFamilyManager by remember { mutableStateOf(false) }
    var showEditProfile by remember { mutableStateOf(false) }

    fun load() {
        // Phone OTP sign-in is optional/off by default — most installs are never a logged-in
        // `users` row at all, and that is a normal state, NOT a session expiring. Only call the
        // account API (and treat its 401 as "log the user out") when we actually had a session
        // to begin with; otherwise just render the guest view below.
        if (!AppSettings.isLoggedIn(context)) {
            account = null
            assignment = null
            isLoading = false
            loadError = null
            return
        }
        isLoading = true
        loadError = null
        coroutineScope.launch {
            val api = NetworkModule.getApi(context)
            val result = runCatching {
                val me = api.getMe()
                val keys = AccountSync.peekUsage(context)
                me to keys
            }
            isLoading = false
            result.onSuccess { (me, keys) ->
                account = me
                assignment = keys
            }.onFailure { e ->
                if (e.httpCode() == 401) {
                    AppSettings.logout(context)
                    onLoggedOut()
                } else {
                    loadError = "Couldn't load your account. Check your connection and try again."
                }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    // DESTRUCTIVE AND IRREVERSIBLE: deletes the server account, then — only once that succeeds —
    // wipes every local record too. On failure nothing local is touched.
    fun deleteAccount() {
        isDeletingAccount = true
        deleteAccountError = null
        coroutineScope.launch {
            val result = runCatching { NetworkModule.getApi(context).deleteAccount() }
            result.onSuccess {
                runCatching { LocalRepository.clearAllLocalData(context) }
                AppSettings.logout(context)
                isDeletingAccount = false
                showDeleteAccountDialog = false
                onLoggedOut()
            }.onFailure { e ->
                isDeletingAccount = false
                deleteAccountError = e.apiErrorMessage() ?: e.message?.takeIf { it.isNotBlank() } ?: "Failed to delete account. Check your connection and try again."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TopBarLogo()
                        Text(tr("Profile"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            loadError?.let {
                Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (account == null && !isLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("Not signed in"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            tr("Sign in to edit your name and details, use a personal API key, and enable fingerprint login. The app works fully without it — your family members and records already live on this device."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onNavigateToLogin, shape = RoundedCornerShape(12.dp)) { Text(tr("Sign In")) }
                    }
                }
            }

            account?.let { acc ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val fullName = listOfNotNull(acc.firstName, acc.lastName).joinToString(" ").trim()
                            Text(
                                fullName.ifEmpty { tr("Your Profile") },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showEditProfile = true }) {
                                Icon(Icons.Default.Edit, contentDescription = tr("Edit profile"), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(acc.email, style = MaterialTheme.typography.bodyMedium)
                        acc.msisdn?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "${tr("Plan:")} ${acc.plan.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Family members — add/edit/remove the people this device tracks. Not gated on
            // login: family profiles are local-device data, usable fully offline/anonymously.
            Card(
                modifier = Modifier.fillMaxWidth().clickable { showFamilyManager = true },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(tr("Family Members"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(tr("Add, edit, or remove people this device tracks — including yourself"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            assignment?.let { a ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr("AI Vision Engine & API Key"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (a.billedTo == "own") Color(0xFFE8EAF6) else Color(0xFFE8F5E9)
                            ) {
                                Text(
                                    text = if (a.billedTo == "own") tr("Secondary: Custom Key") else tr("Primary: Shared Key Pool"),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (a.billedTo == "own") Color(0xFF283593) else Color(0xFF2E7D32)
                                )
                            }
                        }

                        when (a.billedTo) {
                            "own" -> Text(tr("Using your individual Gemini API key — unlimited scans, bypassing the free tier limits."), style = MaterialTheme.typography.bodySmall)
                            "premium" -> Text(tr("Premium plan — unlimited usage."), style = MaterialTheme.typography.bodySmall)
                            else -> {
                                val used = a.usageToday.coerceAtMost(a.limit)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    LinearProgressIndicator(
                                        progress = { if (a.limit > 0) used.toFloat() / a.limit else 0f },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                    Text("$used / ${a.limit} free daily scans used (Shared Key Pool)", style = MaterialTheme.typography.bodySmall)
                                }
                                if (a.quotaExceeded) {
                                    Text(
                                        tr("Today's free pool quota is used up. Add a personal API key below for unlimited scans, or wait until tomorrow."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        var customKeyInput by remember { mutableStateOf("") }
                        var isSavingKey by remember { mutableStateOf(false) }
                        var keyActionMessage by remember { mutableStateOf<String?>(null) }
                        var keyActionIsError by remember { mutableStateOf(false) }

                        Text(tr("Custom API Key (Optional)"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            tr("By default the app uses the shared key pool — you don't need to do anything. Advanced: if you already have your own Gemini API key you can paste it below to use it instead."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GetFreeApiKeyButton(onKeyDetected = { key ->
                            customKeyInput = key
                            keyActionMessage = "Pasted API key from clipboard — tap Save to use it."
                            keyActionIsError = false
                        })

                        OutlinedTextField(
                            value = customKeyInput,
                            onValueChange = { customKeyInput = it },
                            label = { Text(tr("Gemini API Key (AIzaSy...)")) },
                            placeholder = { Text(tr("Leave blank to use Primary Shared Key Pool")) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        keyActionMessage?.let { msg ->
                            Text(
                                tr(msg),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (keyActionIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (customKeyInput.isBlank()) {
                                        keyActionMessage = "Please enter a valid API key or tap 'Revert to Shared Pool'."
                                        keyActionIsError = true
                                        return@Button
                                    }
                                    isSavingKey = true
                                    keyActionMessage = null
                                    coroutineScope.launch {
                                        val res = runCatching {
                                            NetworkModule.getApi(context).setGeminiKeyOnAccount(
                                                com.healthdecoder.app.model.ApiKeyRequest(customKeyInput.trim())
                                            )
                                        }
                                        isSavingKey = false
                                        res.onSuccess {
                                            AccountSync.refreshAssignedKeys(context)
                                            keyActionMessage = "Personal API Key saved! Switched to Individual Key mode."
                                            keyActionIsError = false
                                            customKeyInput = ""
                                            load()
                                        }.onFailure { e ->
                                            keyActionMessage = e.apiErrorMessage() ?: "Failed to save key."
                                            keyActionIsError = true
                                        }
                                    }
                                },
                                enabled = !isSavingKey,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text(tr("Save Key")) }

                            if (a.billedTo == "own") {
                                OutlinedButton(
                                    onClick = {
                                        isSavingKey = true
                                        keyActionMessage = null
                                        coroutineScope.launch {
                                            val res = runCatching {
                                                NetworkModule.getApi(context).setGeminiKeyOnAccount(
                                                    com.healthdecoder.app.model.ApiKeyRequest("")
                                                )
                                            }
                                            isSavingKey = false
                                            res.onSuccess {
                                                AccountSync.refreshAssignedKeys(context)
                                                keyActionMessage = "Reverted to Primary Shared Key Pool."
                                                keyActionIsError = false
                                                load()
                                            }.onFailure { e ->
                                                keyActionMessage = e.apiErrorMessage() ?: "Failed to revert to shared pool."
                                                keyActionIsError = true
                                            }
                                        }
                                    },
                                    enabled = !isSavingKey,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text(tr("Revert to Pool")) }
                            }
                        }
                    }
                }
            }

            if (BiometricHelper.isBiometricsAvailable(context)) {
                val enableFingerprintTitle = tr("Enable Fingerprint Login")
                val enableFingerprintSubtitle = tr("Confirm fingerprint to register")
                val fingerprintReloginError = tr("Error: Please log in again to configure fingerprint.")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(tr("Fingerprint Login"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(tr("Sign in quickly using fingerprint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(
                            checked = isFingerprintEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    val activity = context.findActivity() as? FragmentActivity
                                    if (activity != null) {
                                        BiometricHelper.showBiometricPrompt(
                                            activity = activity,
                                            title = enableFingerprintTitle,
                                            subtitle = enableFingerprintSubtitle,
                                            onResult = { result ->
                                                if (result.isSuccess) {
                                                    val currentToken = AppSettings.getAuthToken(context)
                                                    val currentEmail = AppSettings.getUserEmail(context)
                                                    if (currentToken != null && currentEmail != null) {
                                                        AppSettings.setBiometricEnabled(context, true)
                                                        AppSettings.setBiometricToken(context, currentToken)
                                                        AppSettings.setBiometricUserEmail(context, currentEmail)
                                                        isFingerprintEnabled = true
                                                    } else {
                                                        fingerprintError = fingerprintReloginError
                                                    }
                                                } else {
                                                    isFingerprintEnabled = false
                                                }
                                            }
                                        )
                                    }
                                } else {
                                    AppSettings.setBiometricEnabled(context, false)
                                    AppSettings.clearBiometricCredentials(context)
                                    isFingerprintEnabled = false
                                }
                            }
                        )
                    }
                    fingerprintError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                    }
                }
            }

            if (AppSettings.isLoggedIn(context)) {
                var showChangePasswordSection by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showChangePasswordSection = !showChangePasswordSection },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr("Change Password"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showChangePasswordSection = !showChangePasswordSection }) {
                                Icon(imageVector = if (showChangePasswordSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                            }
                        }

                        if (showChangePasswordSection) {
                            var currentPassword by remember { mutableStateOf("") }
                            var newPassword by remember { mutableStateOf("") }
                            var confirmPassword by remember { mutableStateOf("") }
                            var currentPasswordVisible by remember { mutableStateOf(false) }
                            var newPasswordVisible by remember { mutableStateOf(false) }
                            var confirmPasswordVisible by remember { mutableStateOf(false) }
                            var isUpdatingPassword by remember { mutableStateOf(false) }
                            var passwordUpdateError by remember { mutableStateOf<String?>(null) }
                            var passwordUpdateSuccess by remember { mutableStateOf<String?>(null) }

                            OutlinedTextField(
                                value = currentPassword,
                                onValueChange = { currentPassword = it },
                                label = { Text(tr("Current Password")) },
                                singleLine = true,
                                visualTransformation = if (currentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    val image = if (currentPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                    IconButton(onClick = { currentPasswordVisible = !currentPasswordVisible }) { Icon(imageVector = image, contentDescription = null) }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                label = { Text(tr("New Password (min 6 chars)")) },
                                singleLine = true,
                                visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    val image = if (newPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                    IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) { Icon(imageVector = image, contentDescription = null) }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                label = { Text(tr("Confirm New Password")) },
                                singleLine = true,
                                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    val image = if (confirmPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) { Icon(imageVector = image, contentDescription = null) }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            passwordUpdateError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            passwordUpdateSuccess?.let { Text(tr(it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = {
                                    if (currentPassword.isEmpty() || newPassword.isEmpty()) { passwordUpdateError = "All fields are required."; return@Button }
                                    if (newPassword.length < 6) { passwordUpdateError = "New password must be at least 6 characters."; return@Button }
                                    if (newPassword != confirmPassword) { passwordUpdateError = "Passwords do not match."; return@Button }
                                    passwordUpdateError = null
                                    passwordUpdateSuccess = null
                                    isUpdatingPassword = true
                                    coroutineScope.launch {
                                        val result = runCatching {
                                            NetworkModule.getApi(context).changePassword(
                                                com.healthdecoder.app.model.ChangePasswordRequest(currentPassword = currentPassword, newPassword = newPassword)
                                            )
                                        }
                                        isUpdatingPassword = false
                                        result.onSuccess {
                                            it.token?.let { fresh -> AppSettings.setAuthToken(context, fresh) }
                                            passwordUpdateSuccess = "Password updated successfully."
                                            currentPassword = ""; newPassword = ""; confirmPassword = ""
                                        }.onFailure { e ->
                                            passwordUpdateError = e.apiErrorMessage() ?: e.message ?: "Failed to update password."
                                        }
                                    }
                                },
                                enabled = !isUpdatingPassword,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isUpdatingPassword) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Text(tr("Update Password"))
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { AppSettings.logout(context); onLoggedOut() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                ) { Text(tr("Log Out")) }

                OutlinedButton(
                    onClick = { deleteAccountError = null; showDeleteAccountDialog = true },
                    enabled = !isDeletingAccount,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr("Delete Account"))
                }

                deleteAccountError?.let {
                    Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showEditProfile && account != null) {
        EditProfileDialog(
            account = account!!,
            onDismiss = { showEditProfile = false },
            onSaved = { updated -> account = updated; showEditProfile = false }
        )
    }

    if (showFamilyManager) {
        FamilyManagerDialog(onDismiss = { showFamilyManager = false }, onChanged = {})
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteAccountDialog = false },
            title = { Text(tr("Delete account permanently?"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    tr("This permanently deletes your Health Decoder account AND all medical " +
                        "records stored on this device — reports, reminders, appointments, and " +
                        "family profiles. This cannot be undone."),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { deleteAccount() },
                    enabled = !isDeletingAccount,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (isDeletingAccount) CircularProgressIndicator(color = MaterialTheme.colorScheme.onError, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(tr("Delete Permanently"), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }, enabled = !isDeletingAccount) { Text(tr("Cancel")) }
            }
        )
    }
}

/** Edit the signed-in user's own name/DOB/gender via PUT /api/auth/me. Email/phone stay
 *  read-only — they're the login identity, not a display detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileDialog(account: UserAccount, onDismiss: () -> Unit, onSaved: (UserAccount) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var firstName by remember { mutableStateOf(account.firstName ?: "") }
    var lastName by remember { mutableStateOf(account.lastName ?: "") }
    var dob by remember { mutableStateOf(account.dateOfBirth ?: "") }
    var genderIndex by remember { mutableStateOf(GENDER_OPTIONS.indexOfFirst { it.second == account.gender }) }
    var genderMenuExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Edit Profile"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = firstName, onValueChange = { firstName = it },
                    label = { Text(tr("First Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lastName, onValueChange = { lastName = it },
                    label = { Text(tr("Last Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dob, onValueChange = { dob = it },
                    label = { Text(tr("Date of Birth (YYYY-MM-DD)")) },
                    placeholder = { Text("1990-01-01") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { genderMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(if (genderIndex >= 0) GENDER_OPTIONS[genderIndex].first else tr("Select gender"), modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = genderMenuExpanded, onDismissRequest = { genderMenuExpanded = false }) {
                        GENDER_OPTIONS.forEachIndexed { index, (label, _) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { genderIndex = index; genderMenuExpanded = false })
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && firstName.isNotBlank() && lastName.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val result = runCatching {
                            NetworkModule.getApi(context).updateProfile(
                                UpdateProfileRequest(
                                    firstName = firstName.trim(),
                                    lastName = lastName.trim(),
                                    dateOfBirth = dob.trim().takeIf { it.isNotBlank() },
                                    gender = GENDER_OPTIONS.getOrNull(genderIndex)?.second
                                )
                            )
                        }
                        busy = false
                        result.onSuccess { onSaved(it) }
                            .onFailure { e -> error = e.apiErrorMessage() ?: e.message ?: "Failed to save profile." }
                    }
                }
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(tr("Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel")) } }
    )
}
