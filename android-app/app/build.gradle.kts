import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

// Google Services (Firebase) needs google-services.json, which isn't checked in (per-machine,
// see README/DEPLOY notes on setting up phone-OTP login). Only apply the plugin — and only
// pull in Firebase Auth below — once that file exists, so a fresh checkout still builds.
val hasGoogleServicesConfig = file("google-services.json").exists()
if (hasGoogleServicesConfig) {
    apply(plugin = "com.google.gms.google-services")
}

// API keys are read from local.properties (gitignored, per-machine) rather than being
// committed to source. See android-app/local.properties.example for the expected format.
val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}
fun localProp(key: String): String = (localProperties.getProperty(key) ?: "")

android {
    namespace = "com.healthdecoder.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.healthdecoder.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "1.3.4"

        // No Gemini/Sarvam API keys are embedded here anymore — all AI calls are proxied
        // through the backend (see BackendAiClient), so the APK ships with zero provider keys.
        // OAuth "Web application" client ID (Google Cloud Console) backing native Google
        // Sign-In (Credential Manager requires a *server* client ID, even on Android) — the
        // same client ID as the backend's GOOGLE_CLIENT_ID. See local.properties.example.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProp("GOOGLE_WEB_CLIENT_ID")}\"")

        // Phone (MSISDN) OTP sign-in. OFF by default: every OTP is a billed SMS, and the app
        // works fully on-device without an account. Flip to true (or set PHONE_AUTH_ENABLED=true
        // in local.properties) to bring the phone login/register screens back.
        buildConfigField(
            "boolean", "PHONE_AUTH_ENABLED",
            (localProp("PHONE_AUTH_ENABLED").takeIf { it.isNotBlank() } ?: "false")
        )
        // Google Sign-In / Gmail sync. OFF by default: both were built and tested against the
        // developer's own account, and their behavior/cost at real public scale (OAuth consent
        // screen status, Gmail API quota) hasn't been verified. See FeatureFlags.kt.
        buildConfigField(
            "boolean", "GOOGLE_SIGNIN_ENABLED",
            (localProp("GOOGLE_SIGNIN_ENABLED").takeIf { it.isNotBlank() } ?: "false")
        )
        buildConfigField(
            "boolean", "GMAIL_SYNC_ENABLED",
            (localProp("GMAIL_SYNC_ENABLED").takeIf { it.isNotBlank() } ?: "false")
        )
    }

    // Release signing reads from local.properties (gitignored, per-machine) so the keystore
    // password never touches source control. Falls back to debug signing when those keys are
    // blank (e.g. a fresh checkout without the keystore) so `assembleRelease` still builds for
    // local testing — that fallback build just can't be uploaded to Play Console as-is.
    val hasReleaseSigning = localProp("RELEASE_STORE_PASSWORD").isNotBlank()
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(localProp("RELEASE_STORE_FILE"))
                storePassword = localProp("RELEASE_STORE_PASSWORD")
                keyAlias = localProp("RELEASE_KEY_ALIAS")
                keyPassword = localProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
          excludes += "/META-INF/{AL2.0,LGPL2.1}"
          excludes += "META-INF/NOTICE.md"
          excludes += "META-INF/LICENSE.md"
          excludes += "META-INF/LICENSE.txt"
          excludes += "META-INF/NOTICE.txt"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Retrofit & Networking
  implementation("com.squareup.retrofit2:retrofit:2.11.0")
  implementation("com.squareup.retrofit2:converter-gson:2.11.0")
  implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

  // Coil for Image Loading
  implementation("io.coil-kt:coil-compose:2.6.0")

  // Kotlinx Serialization JSON for Navigation3 Keys
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

  // Material Icons Core & Extended
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.material:material-icons-extended")

  // Google ML Kit Text Recognition (On-device OCR)
  implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")

  // Google ML Kit Barcode Scanning (on-device QR decode) + CameraX (live scanner preview)
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
  implementation("androidx.camera:camera-core:1.4.1")
  implementation("androidx.camera:camera-camera2:1.4.1")
  implementation("androidx.camera:camera-lifecycle:1.4.1")
  implementation("androidx.camera:camera-view:1.4.1")

  // Room (on-device SQLite store for medical records)
  implementation(libs.androidx.room.runtime)
  ksp(libs.androidx.room.compiler)

  // SQLCipher database encryption & Jetpack Security Crypto
  implementation("net.zetetic:android-database-sqlcipher:4.5.4")
  implementation("androidx.security:security-crypto:1.0.0")

  // DocumentFile — used by SafCloudUploader for cloud-folder backup via SAF
  implementation("androidx.documentfile:documentfile:1.0.1")

  // Firebase Auth (phone/OTP sign-in, and Google sign-in below)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  if (hasGoogleServicesConfig) {
      implementation("com.google.firebase:firebase-analytics")
  }

  // Credential Manager — native "Sign in with Google" account picker (no browser)
  implementation("androidx.credentials:credentials:1.3.0")
  implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
  implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

  // Biometric authentication
  implementation(libs.androidx.biometric)

  // JavaMail Android port for IMAP email checking
  implementation("com.sun.mail:android-mail:1.6.7")
  implementation("com.sun.mail:android-activation:1.6.7")

  // AndroidX WorkManager for background scheduling
  implementation("androidx.work:work-runtime-ktx:2.9.0")
}
