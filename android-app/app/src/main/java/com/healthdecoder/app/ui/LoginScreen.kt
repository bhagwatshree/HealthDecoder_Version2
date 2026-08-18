package com.healthdecoder.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Email
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.healthdecoder.app.FeatureFlags
import com.healthdecoder.app.local.SecureKeyManager
import android.widget.Toast
import com.healthdecoder.app.model.ResetPasswordRequest
import androidx.fragment.app.FragmentActivity
import com.healthdecoder.app.auth.BiometricHelper
import com.healthdecoder.app.auth.GoogleSignInHelper
import com.healthdecoder.app.auth.PhoneAuthHelper
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.model.AuthRequest
import com.healthdecoder.app.model.GoogleSignInRequest
import com.healthdecoder.app.model.PhoneLoginRequest
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

/**
 * Login only — two methods, either works for any account: email/password, or phone+OTP
 * (Firebase Phone Auth). Creating a new account happens on RegisterScreen, which is the only
 * place that collects the full profile (name/DOB/gender) and verifies the phone number.
 * Signing in is required — every session is tied to an account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onNavigateToRegister: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val coroutineScope = rememberCoroutineScope()

    var usePhoneLogin by remember { mutableStateOf(false) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var phoneDigits by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var otpSent by remember { mutableStateOf(false) }
    var isSendingOtp by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showBiometricOnboarding by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var pendingToken by remember { mutableStateOf("") }
    var pendingEmail by remember { mutableStateOf("") }

    // Hoisted here because tr() is @Composable and can't be called from the plain (non-composable)
    // local functions below that assign these into errorMessage.
    val genericErrorText = tr("Something went wrong. Check your connection and try again.")
    val enterEmailPasswordError = tr("Enter an email and password.")
    val enterValidMobileError = tr("Enter a valid 10-digit mobile number.")
    val couldntStartPhoneVerificationError = tr("Couldn't start phone verification. Please try again.")
    val googleSignInFailedError = tr("Google sign-in failed. Please try again.")
    val enter6DigitCodeError = tr("Enter the 6-digit code sent to your phone.")
    val incorrectExpiredCodeError = tr("Incorrect or expired code. Please try again.")
    val passwordResetSuccessToast = tr("Password reset successfully. Log in with your new password.")
    val enableFingerprintOnboardingTitle = tr("Enable Fingerprint Login")
    val enableFingerprintOnboardingSubtitle = tr("Confirm your fingerprint to register")

    fun onAuthSuccess(token: String, userEmail: String) {
        AppSettings.setAuthToken(context, token)
        AppSettings.setUserEmail(context, userEmail)
        coroutineScope.launch {
            runCatching { AccountSync.refreshAssignedKeys(context) }
            if (BiometricHelper.isBiometricsAvailable(context) && !AppSettings.isBiometricEnabled(context)) {
                pendingToken = token
                pendingEmail = userEmail
                showBiometricOnboarding = true
            } else {
                onLoggedIn()
            }
        }
    }

    fun loginWithEmail() {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || password.isEmpty()) {
            errorMessage = enterEmailPasswordError
            return
        }
        errorMessage = null
        isLoading = true
        coroutineScope.launch {
            val result = runCatching { NetworkModule.getApi(context).login(AuthRequest(trimmedEmail, password)) }
            isLoading = false
            result.onSuccess { onAuthSuccess(it.token, it.user.email) }
                .onFailure { e ->
                    errorMessage = e.apiErrorMessage()
                        ?: e.message?.takeIf { it.isNotBlank() }
                        ?: genericErrorText
                }
        }
    }

    fun loginWithPhoneToken(idToken: String) {
        isLoading = true
        coroutineScope.launch {
            val result = runCatching { NetworkModule.getApi(context).loginPhone(PhoneLoginRequest(idToken)) }
            isLoading = false
            PhoneAuthHelper.signOut()
            result.onSuccess { onAuthSuccess(it.token, it.user.email) }
                .onFailure { e ->
                    if (e.httpCode() == 404) {
                        // Phone was OTP-verified but has no account — take them to sign-up.
                        errorMessage = null
                        onNavigateToRegister(phoneDigits)
                    } else {
                        errorMessage = e.apiErrorMessage()
                            ?: e.message?.takeIf { it.isNotBlank() }
                            ?: genericErrorText
                    }
                }
        }
    }

    fun loginWithGoogle() {
        errorMessage = null
        isLoading = true
        coroutineScope.launch {
            GoogleSignInHelper.signIn(context) { googleResult ->
                googleResult.onSuccess { idToken ->
                    coroutineScope.launch {
                        val result = runCatching { NetworkModule.getApi(context).googleSignIn(GoogleSignInRequest(idToken)) }
                        isLoading = false
                        result.onSuccess { onAuthSuccess(it.token, it.user.email) }
                            .onFailure { e ->
                                errorMessage = e.apiErrorMessage()
                                    ?: e.message?.takeIf { it.isNotBlank() }
                                    ?: genericErrorText
                            }
                    }
                }.onFailure { e ->
                    isLoading = false
                    // User simply closed the account picker — not an error worth showing.
                    if (e !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                        errorMessage = e.message?.takeIf { it.isNotBlank() } ?: googleSignInFailedError
                    }
                }
            }
        }
    }

    fun sendLoginOtp() {
        if (phoneDigits.length != 10) {
            errorMessage = enterValidMobileError
            return
        }
        if (activity == null) {
            errorMessage = couldntStartPhoneVerificationError
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
                is PhoneAuthHelper.OtpEvent.AutoVerified -> loginWithPhoneToken(event.idToken)
                is PhoneAuthHelper.OtpEvent.Failed -> errorMessage = event.message
            }
        }
    }

    fun verifyLoginOtp() {
        val id = verificationId ?: return
        if (otpCode.length < 6) {
            errorMessage = enter6DigitCodeError
            return
        }
        errorMessage = null
        isLoading = true
        PhoneAuthHelper.verifyOtp(id, otpCode) { result ->
            result.onSuccess { loginWithPhoneToken(it) }
                .onFailure {
                    isLoading = false
                    errorMessage = incorrectExpiredCodeError
                }
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .appWatermark()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = com.healthdecoder.app.R.drawable.ic_health_decoder_logo),
                contentDescription = tr("Health Decoder Logo"),
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Health Decoder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = tr("Secure Medical Report Analyzer"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = tr("Signing in gives you your own free daily AI usage allowance, separate from other users of this app."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))

            if (AppSettings.isBiometricEnabled(context) && AppSettings.getBiometricToken(context) != null) {
                val fingerprintLoginTitle = tr("Fingerprint Login")
                val fingerprintLoginSubtitle = tr("Confirm fingerprint to sign in")
                val noSavedFingerprintError = tr("No saved fingerprint credentials found.")
                val biometricAuthFailedError = tr("Biometric authentication failed.")
                val couldNotStartBiometricError = tr("Could not start biometric authentication.")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val savedEmail = AppSettings.getBiometricUserEmail(context) ?: tr("your account")
                        Text(
                            text = "${tr("Saved Login:")} $savedEmail",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val fragmentActivity = activity as? FragmentActivity
                                if (fragmentActivity != null) {
                                    errorMessage = null
                                    BiometricHelper.showBiometricPrompt(
                                        activity = fragmentActivity,
                                        title = fingerprintLoginTitle,
                                        subtitle = fingerprintLoginSubtitle,
                                        onResult = { result ->
                                            if (result.isSuccess) {
                                                val savedToken = AppSettings.getBiometricToken(context)
                                                val savedEmail = AppSettings.getBiometricUserEmail(context)
                                                if (savedToken != null && savedEmail != null) {
                                                    onAuthSuccess(savedToken, savedEmail)
                                                } else {
                                                    errorMessage = noSavedFingerprintError
                                                }
                                            } else {
                                                errorMessage = result.exceptionOrNull()?.message ?: biometricAuthFailedError
                                            }
                                        }
                                    )
                                } else {
                                    errorMessage = couldNotStartBiometricError
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(tr("Log In with Fingerprint"), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Text(
                    text = tr("Or sign in with another method"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Phone OTP tab hidden while PHONE_AUTH_ENABLED is off (billed SMS cost) — email/
            // password is the only login path for an existing account until that's re-enabled.
            if (FeatureFlags.PHONE_AUTH_ENABLED) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !usePhoneLogin,
                        onClick = { usePhoneLogin = false; errorMessage = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text(tr("Email")) }
                    SegmentedButton(
                        selected = usePhoneLogin,
                        onClick = { usePhoneLogin = true; errorMessage = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(tr("Phone OTP")) }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (!usePhoneLogin) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(tr("Email")) },
                    leadingIcon = { Icon(imageVector = Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(tr("Password")) },
                    leadingIcon = { Icon(imageVector = Icons.Default.Lock, contentDescription = null) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        val description = if (passwordVisible) "Hide password" else "Show password"
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(imageVector = image, contentDescription = description)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(text = tr("Forgot Password?"),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { showForgotPasswordDialog = true }
                            .padding(vertical = 4.dp)
                    )
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { loginWithEmail() },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Log In"), fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (!otpSent) {
                OutlinedTextField(
                    value = phoneDigits,
                    onValueChange = { phoneDigits = it.filter { c -> c.isDigit() }.take(10) },
                    label = { Text(tr("Mobile number")) },
                    singleLine = true,
                    leadingIcon = { Text("+91", modifier = Modifier.padding(start = 12.dp)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { sendLoginOtp() },
                    enabled = !isSendingOtp,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSendingOtp) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Send OTP"), fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Text(
                    "Enter the 6-digit code sent to +91$phoneDigits",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { verifyLoginOtp() },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Verify & Log In"), fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = { otpSent = false; otpCode = ""; errorMessage = null }) {
                    Text(tr("Change phone number"))
                }
            }

            // Google Sign-In creates a real account with no OTP step, using an OAuth client
            // that was only ever tested against one account — hidden until its public-scale
            // cost/quota story (and OAuth consent screen publishing status) is verified.
            if (FeatureFlags.GOOGLE_SIGNIN_ENABLED) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Google Sign In Button — native account picker via Credential Manager, no browser.
                OutlinedButton(
                    onClick = { loginWithGoogle() },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Image(
                        painter = painterResource(id = com.healthdecoder.app.R.drawable.ic_google_logo),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(tr("Sign In with Google"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            // Signup no longer needs phone OTP (see RegisterScreen — email+password is enough),
            // so this is always available, regardless of PHONE_AUTH_ENABLED.
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { onNavigateToRegister(null) }) {
                Text(tr("New here? Create an account"))
            }
        }
    }

    if (showBiometricOnboarding) {
        AlertDialog(
            onDismissRequest = {
                showBiometricOnboarding = false
                onLoggedIn()
            },
            title = { Text(tr("Enable Fingerprint Login?")) },
            text = { Text(tr("Use your fingerprint to quickly sign in next time without waiting for an OTP or entering passwords.")) },
            confirmButton = {
                Button(
                    onClick = {
                        showBiometricOnboarding = false
                        val fragmentActivity = activity as? FragmentActivity
                        if (fragmentActivity != null) {
                            BiometricHelper.showBiometricPrompt(
                                activity = fragmentActivity,
                                title = enableFingerprintOnboardingTitle,
                                subtitle = enableFingerprintOnboardingSubtitle,
                                onResult = { result ->
                                    if (result.isSuccess) {
                                        AppSettings.setBiometricEnabled(context, true)
                                        AppSettings.setBiometricToken(context, pendingToken)
                                        AppSettings.setBiometricUserEmail(context, pendingEmail)
                                    }
                                    onLoggedIn()
                                }
                            )
                        } else {
                            onLoggedIn()
                        }
                    }
                ) {
                    Text(tr("Enable"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBiometricOnboarding = false
                        onLoggedIn()
                    }
                ) {
                    Text(tr("Not Now"))
                }
            }
        )
    }

    if (showForgotPasswordDialog && activity != null) {
        ForgotPasswordDialog(
            activity = activity,
            onDismiss = { showForgotPasswordDialog = false },
            onSuccess = {
                showForgotPasswordDialog = false
                Toast.makeText(context, passwordResetSuccessToast, Toast.LENGTH_LONG).show()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgotPasswordDialog(
    activity: Activity,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) } // 1: Input phone, 2: Input OTP + Password
    var phoneDigits by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var otpCode by remember { mutableStateOf("") }

    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    var isSending by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Hoisted here because tr() is @Composable and can't be called from the plain onClick
    // lambdas below that assign these into errorMsg.
    val enterValidMobileError = tr("Enter a valid 10-digit mobile number.")
    val enter6DigitOtpError = tr("Enter the 6-digit OTP code.")
    val passwordMin6CharsError = tr("Password must be at least 6 characters.")
    val passwordsDoNotMatchError = tr("Passwords do not match.")
    val failedToResetPasswordError = tr("Failed to reset password.")
    val authFailedRetryOtpError = tr("Authentication failed. Try requesting OTP again.")
    val verificationTimedOutError = tr("Verification timed out. Please try again.")
    val incorrectExpiredOtpError = tr("Incorrect or expired OTP code.")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Forgot Password"), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (step == 1) {
                    Text(
                        tr("Enter the 10-digit mobile number linked to your account to verify your identity via OTP."),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = phoneDigits,
                        onValueChange = { phoneDigits = it.filter { c -> c.isDigit() }.take(10) },
                        label = { Text(tr("Mobile Number")) },
                        singleLine = true,
                        leadingIcon = { Text("+91 ", modifier = Modifier.padding(start = 12.dp)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "${tr("Enter the 6-digit OTP code sent to")} +91 $phoneDigits ${tr("and set your new password.")}",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = { otpCode = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(tr("6-Digit OTP")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
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
                            IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                Icon(imageVector = image, contentDescription = null)
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(10.dp),
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
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(imageVector = image, contentDescription = null)
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                Button(
                    onClick = {
                        if (phoneDigits.length != 10) {
                            errorMsg = enterValidMobileError
                            return@Button
                        }
                        errorMsg = null
                        isSending = true
                        PhoneAuthHelper.sendOtp(activity, "+91$phoneDigits") { event ->
                            isSending = false
                            when (event) {
                                is PhoneAuthHelper.OtpEvent.CodeSent -> {
                                    verificationId = event.verificationId
                                    step = 2
                                }
                                is PhoneAuthHelper.OtpEvent.AutoVerified -> {
                                    verificationId = "auto-verified"
                                    // If auto-verified immediately, we can step forward
                                    step = 2
                                }
                                is PhoneAuthHelper.OtpEvent.Failed -> {
                                    errorMsg = event.message
                                }
                            }
                        }
                    },
                    enabled = !isSending
                ) {
                    if (isSending) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Send OTP"))
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (otpCode.length < 6 && verificationId != "auto-verified") {
                            errorMsg = enter6DigitOtpError
                            return@Button
                        }
                        if (newPassword.length < 6) {
                            errorMsg = passwordMin6CharsError
                            return@Button
                        }
                        if (newPassword != confirmPassword) {
                            errorMsg = passwordsDoNotMatchError
                            return@Button
                        }
                        errorMsg = null
                        isLoading = true

                        val performReset = { token: String ->
                            coroutineScope.launch {
                                val result = runCatching {
                                    NetworkModule.getApi(context).resetPasswordOtp(
                                        ResetPasswordRequest(token, newPassword)
                                    )
                                }
                                isLoading = false
                                PhoneAuthHelper.signOut()
                                result.onSuccess {
                                    onSuccess()
                                }.onFailure { e ->
                                    errorMsg = e.apiErrorMessage() ?: e.message ?: failedToResetPasswordError
                                }
                            }
                        }

                        if (verificationId == "auto-verified") {
                            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                            if (user != null) {
                                user.getIdToken(false).addOnSuccessListener {
                                    performReset(it.token ?: "")
                                }.addOnFailureListener {
                                    isLoading = false
                                    errorMsg = authFailedRetryOtpError
                                }
                            } else {
                                isLoading = false
                                errorMsg = verificationTimedOutError
                            }
                        } else {
                            PhoneAuthHelper.verifyOtp(verificationId!!, otpCode) { result ->
                                result.onSuccess { token ->
                                    performReset(token)
                                }.onFailure {
                                    isLoading = false
                                    errorMsg = incorrectExpiredOtpError
                                }
                            }
                        }
                    },
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Reset Password"))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        }
    )
}

