package com.healthdecoder.app.ui

import android.app.Activity
import android.app.DatePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.healthdecoder.app.FeatureFlags
import com.healthdecoder.app.auth.PhoneAuthHelper
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.model.SignupRequest
import com.healthdecoder.app.network.AccountSync
import com.healthdecoder.app.network.NetworkModule
import kotlinx.coroutines.launch
import java.util.Calendar

private val GENDER_OPTIONS = listOf(
    "Male" to "male",
    "Female" to "female",
    "Other" to "other",
    "Prefer not to say" to "prefer_not_to_say"
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Registration: name/surname/DOB/gender + email/password, plus — only when
 * [FeatureFlags.PHONE_AUTH_ENABLED] is on — a phone number verified by OTP (Firebase Phone
 * Auth) before the account is created. Every OTP is a billed SMS, so with the flag off (the
 * shipped default) signup is free: email + password only, no phone field, no "Send OTP" step.
 * Email and phone each get a UNIQUE constraint on the same user row server-side (see
 * db_init.sql); when phone isn't collected, the backend synthesises a placeholder MSISDN so
 * that constraint still holds (see server.js signup).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    prepopulatedMsisdn: String? = null,
    onRegistered: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val coroutineScope = rememberCoroutineScope()

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var dobMillis by remember { mutableStateOf<Long?>(null) }
    var dobDisplay by remember { mutableStateOf("") }
    var genderIndex by remember { mutableStateOf(-1) }
    var genderMenuExpanded by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val initialPhone = remember(prepopulatedMsisdn) {
        prepopulatedMsisdn?.removePrefix("+91")?.trim() ?: ""
    }
    var phoneDigits by remember { mutableStateOf(initialPhone) }
    var otpCode by remember { mutableStateOf("") }

    var verificationId by remember { mutableStateOf<String?>(null) }
    var otpSent by remember { mutableStateOf(false) }
    var isSendingOtp by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var consentChecked by remember { mutableStateOf(false) }
    // Optional at signup today (see class doc) — collected here and attached to the account
    // right after it's created, same call ProfileScreen's "Custom API Key" section uses.
    var apiKeyInput by remember { mutableStateOf("") }

    fun validateForm(): String? {
        if (firstName.trim().isEmpty() || lastName.trim().isEmpty()) return "Enter your first and last name."
        if (dobMillis == null) return "Select your date of birth."
        if (genderIndex == -1) return "Select a gender."
        if (email.trim().isEmpty() || !email.contains("@")) return "Enter a valid email."
        if (password.length < 6) return "Password must be at least 6 characters."
        if (FeatureFlags.PHONE_AUTH_ENABLED && phoneDigits.length != 10) return "Enter a valid 10-digit mobile number."
        return null
    }

    /** Attaches the optional API key collected on this form to the just-created account —
     *  fire-and-forget: signup itself already succeeded, so a key-save hiccup shouldn't block
     *  or fail the whole flow. Same call ProfileScreen's "Custom API Key" section uses. */
    suspend fun attachApiKeyIfProvided() {
        val key = apiKeyInput.trim()
        if (key.isBlank()) return
        runCatching {
            NetworkModule.getApi(context).setGeminiKeyOnAccount(com.healthdecoder.app.model.ApiKeyRequest(key))
        }
    }

    fun signupWithIdToken(idToken: String) {
        coroutineScope.launch {
            val cal = Calendar.getInstance().apply { timeInMillis = dobMillis!! }
            val dob = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val result = runCatching {
                val api = NetworkModule.getApi(context)
                api.signup(
                    SignupRequest(
                        firstName = firstName.trim(),
                        lastName = lastName.trim(),
                        dateOfBirth = dob,
                        gender = GENDER_OPTIONS[genderIndex].second,
                        email = email.trim(),
                        password = password,
                        phoneIdToken = idToken
                    )
                )
            }
            isVerifying = false
            PhoneAuthHelper.signOut()
            result.onSuccess { auth ->
                AppSettings.setAuthToken(context, auth.token)
                AppSettings.setUserEmail(context, auth.user.email)
                attachApiKeyIfProvided()
                runCatching { AccountSync.refreshAssignedKeys(context) }
                onRegistered()
            }.onFailure { e ->
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Check your connection and try again."
            }
        }
    }

    /** Email+password signup, no phone/OTP involved — used when PHONE_AUTH_ENABLED is off. */
    fun signupDirect() {
        val validationError = validateForm()
        if (validationError != null) {
            errorMessage = validationError
            return
        }
        errorMessage = null
        isSubmitting = true
        coroutineScope.launch {
            val cal = Calendar.getInstance().apply { timeInMillis = dobMillis!! }
            val dob = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val result = runCatching {
                val api = NetworkModule.getApi(context)
                api.signup(
                    SignupRequest(
                        firstName = firstName.trim(),
                        lastName = lastName.trim(),
                        dateOfBirth = dob,
                        gender = GENDER_OPTIONS[genderIndex].second,
                        email = email.trim(),
                        password = password,
                        phoneIdToken = null
                    )
                )
            }
            isSubmitting = false
            result.onSuccess { auth ->
                AppSettings.setAuthToken(context, auth.token)
                AppSettings.setUserEmail(context, auth.user.email)
                attachApiKeyIfProvided()
                runCatching { AccountSync.refreshAssignedKeys(context) }
                onRegistered()
            }.onFailure { e ->
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Check your connection and try again."
            }
        }
    }

    fun sendOtp() {
        val validationError = validateForm()
        if (validationError != null) {
            errorMessage = validationError
            return
        }
        if (activity == null) {
            errorMessage = "Couldn't start phone verification. Please try again."
            return
        }
        errorMessage = null
        isSendingOtp = true
        PhoneAuthHelper.sendOtp(activity, "+91$phoneDigits") { event ->
            isSendingOtp = false
            when (event) {
                is PhoneAuthHelper.OtpEvent.CodeSent -> {
                    verificationId = event.verificationId
                    otpSent = true
                }
                is PhoneAuthHelper.OtpEvent.AutoVerified -> {
                    isVerifying = true
                    signupWithIdToken(event.idToken)
                }
                is PhoneAuthHelper.OtpEvent.Failed -> {
                    errorMessage = event.message
                }
            }
        }
    }

    fun verifyOtpAndSignup() {
        val id = verificationId ?: return
        if (otpCode.length < 6) {
            errorMessage = "Enter the 6-digit code sent to your phone."
            return
        }
        errorMessage = null
        isVerifying = true
        PhoneAuthHelper.verifyOtp(id, otpCode) { result ->
            result.onSuccess { signupWithIdToken(it) }
                .onFailure {
                    isVerifying = false
                    errorMessage = "Incorrect or expired code. Please try again."
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TopBarLogo()
                        Text(tr("Create Account"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!otpSent) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text(tr("First name")) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text(tr("Last name")) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = dobDisplay,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(tr("Date of birth")) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable {
                                    val cal = Calendar.getInstance()
                                    DatePickerDialog(
                                        context,
                                        { _, year, month, dayOfMonth ->
                                            val picked = Calendar.getInstance().apply { set(year, month, dayOfMonth, 0, 0, 0) }
                                            dobMillis = picked.timeInMillis
                                            dobDisplay = "%02d/%02d/%04d".format(dayOfMonth, month + 1, year)
                                        },
                                        cal.get(Calendar.YEAR) - 25, cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                                }
                        )
                    }

                    ExposedDropdownMenuBox(
                        expanded = genderMenuExpanded,
                        onExpandedChange = { genderMenuExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = if (genderIndex >= 0) GENDER_OPTIONS[genderIndex].first else "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(tr("Gender")) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable)
                        )
                        ExposedDropdownMenu(expanded = genderMenuExpanded, onDismissRequest = { genderMenuExpanded = false }) {
                            GENDER_OPTIONS.forEachIndexed { index, (label, _) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { genderIndex = index; genderMenuExpanded = false })
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(tr("Email")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(tr("Password")) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        val description = if (passwordVisible) tr("Hide password") else tr("Show password")
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(imageVector = image, contentDescription = description)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (FeatureFlags.PHONE_AUTH_ENABLED) {
                    OutlinedTextField(
                        value = phoneDigits,
                        onValueChange = { phoneDigits = it.filter { c -> c.isDigit() }.take(10) },
                        label = { Text(tr("Mobile number")) },
                        singleLine = true,
                        leadingIcon = { Text(tr("+91"), modifier = Modifier.padding(start = 12.dp)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Optional today — see the class doc comment above. Same "open AI Studio, then
                // auto-fill from clipboard on return" flow as ProfileScreen's API key section.
                Text(tr("Free Gemini API Key (Optional)"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    tr("Skip this and the app uses a shared free pool by default. Adding your own key up front means you're never limited by that pool's daily cap."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GetFreeApiKeyButton(onKeyDetected = { key -> apiKeyInput = key })
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text(tr("Gemini API Key (AIzaSy...)")) },
                    placeholder = { Text(tr("Optional — leave blank to use the shared pool")) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { consentChecked = !consentChecked }
                ) {
                    Checkbox(checked = consentChecked, onCheckedChange = { consentChecked = it })
                    Text(
                        tr("I agree that this app is not a medical device and does not provide clinical advice."),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                errorMessage?.let {
                    Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { if (FeatureFlags.PHONE_AUTH_ENABLED) sendOtp() else signupDirect() },
                    enabled = !isSendingOtp && !isSubmitting && consentChecked,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSendingOtp || isSubmitting) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (FeatureFlags.PHONE_AUTH_ENABLED) tr("Send OTP") else tr("Create Account"), fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tr("Already have an account? Sign In"))
                }
            } else {
                Text(
                    "Enter the 6-digit code sent to +91$phoneDigits",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text(tr("OTP code")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { verifyOtpAndSignup() },
                    enabled = !isVerifying,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Verify & Create Account"), fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = { otpSent = false; otpCode = ""; errorMessage = null }) {
                    Text(tr("Change phone number"))
                }
            }
        }
    }
}
