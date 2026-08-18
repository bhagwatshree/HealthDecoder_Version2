package com.healthdecoder.app.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

data class FamilyProfile(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("relation") val relation: String,
    @SerializedName("avatarEmoji") val avatarEmoji: String,
    @SerializedName("sex") val sex: String = "",           // "Male" | "Female" | "Other" | ""
    @SerializedName("dateOfBirth") val dateOfBirth: String = "", // YYYY-MM-DD; age is derived from this
    // The account signed in when this profile was created, or null if created while signed out.
    // Null means visible to everyone (guest or any account); a real value means visible only
    // while THAT account is signed in — see LocalRepository.familyMembers().
    @SerializedName("ownerEmail") val ownerEmail: String? = null
)

object MockProfiles {
    val profiles = listOf(
        FamilyProfile("p1", "Amit (Self)", "Self", "👨"),
        FamilyProfile("p2", "Papa", "Father", "👴"),
        FamilyProfile("p3", "Mummy", "Mother", "👵")
    )
}

data class Medication(
    @SerializedName("name") val name: String,
    @SerializedName("dosage") val dosage: String = "",
    @SerializedName("frequency") val frequency: String = "",
    @SerializedName("duration") val duration: String? = "",
    @SerializedName("isOptional") val isOptional: Boolean = false,
    @SerializedName("weeklySchedule") val weeklySchedule: List<String> = emptyList(),
    @SerializedName("notes") val notes: String? = "",
    // Structured start/stop window, resolved to absolute ISO dates by
    // LocalRepository.resolveMedicationDates before a report is saved. startAfterDays/
    // durationDays are the AI's raw relative signals ("start after 5 days", "10 days" -> 10);
    // startDate/endDate hold either the AI's explicit date or the resolved result. All nullable
    // so older saved reports/backups without these fields deserialize unchanged. endDate == null
    // covers BOTH "till follow-up" and "continue" — only a concrete printed end date sets it.
    @SerializedName("startDate") val startDate: String? = null,
    @SerializedName("startAfterDays") val startAfterDays: Int? = null,
    @SerializedName("endDate") val endDate: String? = null,
    @SerializedName("durationDays") val durationDays: Int? = null,
    // "Once every N days" dosing cadence (e.g. "once in 15 days" -> 15), distinct from
    // durationDays (total course length). Null for daily/weekly-named-day patterns, which
    // weeklySchedule/daysOfWeek already express. Reminders count days since startDate (defaulted
    // to the report date when this is set but no explicit start was given — the interval needs
    // an anchor day to count from).
    @SerializedName("intervalDays") val intervalDays: Int? = null
)

data class TestParameter(
    @SerializedName("name") val name: String = "",
    @SerializedName("value") val value: String = "",
    @SerializedName("unit") val unit: String = "",
    @SerializedName("referenceRange") val referenceRange: String = "",
    // Gemini returns null when a parameter has no High/Low/Normal flag (e.g. uptake %),
    // and Gson writes that null despite the default — so this must be nullable.
    @SerializedName("status") val status: String? = "",
    // AI-provided at scan time (see OcrEngine's extraction prompt) so trend charting doesn't
    // need brittle after-the-fact keyword matching — null/blank for reports scanned before this
    // existed, or when the AI didn't have an opinion; DashboardEngine falls back to its own
    // keyword heuristics in that case. Nullable for the same reason as `status` above.
    @SerializedName("trendCategory") val trendCategory: String? = "",
    @SerializedName("trendCondition") val trendCondition: String? = "",
    @SerializedName("excludeFromTrend") val excludeFromTrend: Boolean? = false
)

data class TestResults(
    @SerializedName("parameters") val parameters: List<TestParameter> = emptyList(),
    @SerializedName("findings") val findings: List<String> = emptyList()
)

data class TestDifference(
    @SerializedName("name") val name: String,
    @SerializedName("previous") val previous: String,
    @SerializedName("current") val current: String,
    @SerializedName("change") val change: String = "",
    @SerializedName("status") val status: String = ""
)

data class MedicationChanges(
    @SerializedName("added") val added: List<String> = emptyList(),
    @SerializedName("removed") val removed: List<String> = emptyList(),
    @SerializedName("changed") val changed: List<String> = emptyList()
)

data class ComparisonResult(
    @SerializedName("hasComparison") val hasComparison: Boolean = false,
    @SerializedName("previousReportId") val previousReportId: String? = null,
    @SerializedName("previousReportDate") val previousReportDate: String? = null,
    @SerializedName("comparisonSummary") val comparisonSummary: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("differences") val differences: List<TestDifference> = emptyList(),
    @SerializedName("medicationChanges") val medicationChanges: MedicationChanges? = null
)

data class SpecialistRecommendation(
    @SerializedName("specialist") val specialist: String,
    @SerializedName("reason") val reason: String,
    @SerializedName("urgency") val urgency: String = "Routine" // Routine | Soon | Urgent
)

data class PrescriptionAlignment(
    @SerializedName("aligned") val aligned: Boolean = true,
    @SerializedName("score") val score: String = "N/A", // Good | Partial | Poor | N/A
    @SerializedName("analysis") val analysis: String = "",
    @SerializedName("flags") val flags: List<String> = emptyList()
)

data class MedicineSideEffect(
    @SerializedName("medicine") val medicine: String,
    @SerializedName("commonEffects") val commonEffects: List<String> = emptyList(),
    @SerializedName("seriousEffects") val seriousEffects: List<String> = emptyList(),
    @SerializedName("severity") val severity: String = "Mild", // Mild | Moderate | Serious
    @SerializedName("tips") val tips: String = ""
)

data class HealthInsights(
    @SerializedName("interpretation") val interpretation: String = "",
    @SerializedName("specialistRecommendations") val specialistRecommendations: List<SpecialistRecommendation> = emptyList(),
    @SerializedName("prescriptionAlignment") val prescriptionAlignment: PrescriptionAlignment? = null,
    @SerializedName("sideEffects") val sideEffects: List<MedicineSideEffect> = emptyList()
)

data class TrendDataPoint(
    @SerializedName("date") val date: String,
    @SerializedName("value") val value: String,
    @SerializedName("unit") val unit: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("reportId") val reportId: String = "", // report this value came from (for click-through)
    // Test condition for this specific reading (e.g. "Fasting" / "PP" / "Random" for blood
    // sugar) — points on the same trend line can differ, so this rides per-point, not per-trend.
    @SerializedName("context") val context: String = "",
    // Cross-lab unit standardization (trend chart only — the report screen shows the printed
    // value). When a reading's printed unit differs from the test's locked standard unit and a
    // verified conversion exists, `value`/`unit` above hold the CONVERTED figures and these hold
    // what the report actually printed, so the chart can note "converted from X". `converted` is
    // false either when no conversion was needed or when the unit couldn't be safely converted.
    @SerializedName("originalValue") val originalValue: String = "",
    @SerializedName("originalUnit") val originalUnit: String = "",
    @SerializedName("converted") val converted: Boolean = false,
    // The reading's healthy reference range, parsed from the report and expressed in the SAME
    // (possibly converted) unit as `value` above, so the chart can shade a normal-range band.
    // Null when the report stated no range, or a one-sided limit (e.g. "<200") leaves a bound open.
    @SerializedName("refLow") val refLow: Float? = null,
    @SerializedName("refHigh") val refHigh: Float? = null
)

data class ParameterTrend(
    @SerializedName("name") val name: String,
    @SerializedName("dataPoints") val dataPoints: List<TrendDataPoint> = emptyList(),
    @SerializedName("trend") val trend: String = "stable" // improving | worsening | stable | increasing | decreasing
)

data class MedicationTimelineEntry(
    @SerializedName("date") val date: String,
    @SerializedName("reportId") val reportId: String,
    @SerializedName("reportCategory") val reportCategory: String? = null,
    @SerializedName("added") val added: List<String> = emptyList(),
    @SerializedName("removed") val removed: List<String> = emptyList(),
    @SerializedName("changed") val changed: List<String> = emptyList(),
    @SerializedName("activeMedicines") val activeMedicines: List<Medication> = emptyList()
)

data class HealthSummary(
    @SerializedName("overallNarrative") val overallNarrative: String = "",
    @SerializedName("parameterTrends") val parameterTrends: List<ParameterTrend> = emptyList(),
    @SerializedName("medicationTimeline") val medicationTimeline: List<MedicationTimelineEntry> = emptyList(),
    @SerializedName("activeFlags") val activeFlags: List<String> = emptyList()
)

data class TestInference(
    @SerializedName("reportId") val reportId: String,
    @SerializedName("patientName") val patientName: String,
    @SerializedName("reportDate") val reportDate: String,
    @SerializedName("reportCategory") val reportCategory: String,
    @SerializedName("reportType") val reportType: String = "",
    @SerializedName("hasMedications") val hasMedications: Boolean = false,
    @SerializedName("summary") val summary: String,
    @SerializedName("status") val status: String
)

/** An original file the user imported (image / PDF / Word), preserved for download. */
data class SourceFile(
    @SerializedName("path") val path: String,
    @SerializedName("name") val name: String,
    @SerializedName("mime") val mimeType: String = ""
)

@Entity(
    tableName = "reports",
    indices = [
        Index("patientName", "reportDate"),
        Index("reportDate"),
        Index("reportCategory")
    ]
)
data class MedicalReport(
    @PrimaryKey @SerializedName("id") val id: String,
    @SerializedName("patient_name") val patientName: String?,
    @SerializedName("report_date") val reportDate: String?,
    @SerializedName("report_type") val reportType: String?,
    @SerializedName("extracted_text") val extractedText: String?,
    @SerializedName("comments") val comments: String?,
    @SerializedName("medications") val medications: List<Medication> = emptyList(),
    @SerializedName("image_path") val imagePath: String,
    @SerializedName("image_paths") val imagePaths: List<String> = emptyList(),
    @SerializedName("source_files") val sourceFiles: List<SourceFile> = emptyList(),
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("test_results") val testResults: TestResults? = null,
    @SerializedName("comparison_result") val comparisonResult: ComparisonResult? = null,
    @SerializedName("report_category") val reportCategory: String? = null,
    @SerializedName("health_insights") val healthInsights: HealthInsights? = null,
    /** SHA-256 of each scanned page / imported file, used to detect duplicate scans. */
    @ColumnInfo(defaultValue = "'[]'")
    @SerializedName("page_hashes") val pageHashes: List<String> = emptyList(),
    @SerializedName("user_email") val userEmail: String? = null,
    /**
     * False for a report the user only UPLOADED (stored the file, no AI analysis yet) to save
     * API calls when archiving old data — the detail screen offers to analyze it on demand. True
     * for anything that has been through AI extraction. Defaults to 1 so every report that
     * existed before this flag is correctly treated as already analyzed.
     */
    @ColumnInfo(defaultValue = "1")
    @SerializedName("analyzed") val analyzed: Boolean = true
)

data class ReportUpdateRequest(
    @SerializedName("patient_name") val patientName: String,
    @SerializedName("report_date") val reportDate: String,
    @SerializedName("report_type") val reportType: String,
    @SerializedName("comments") val comments: String,
    @SerializedName("medications") val medications: List<Medication>,
    @SerializedName("extracted_text") val extractedText: String,
    @SerializedName("test_results") val testResults: TestResults? = null,
    @SerializedName("report_category") val reportCategory: String? = null
)

@Entity(tableName = "pending_tests", indices = [Index("patientName"), Index("status")])
data class PendingTest(
    @PrimaryKey @SerializedName("id") val id: String,
    @SerializedName("patient_name") val patientName: String,
    @SerializedName("test_name") val testName: String,
    @SerializedName("due_date") val dueDate: String?,
    @SerializedName("status") val status: String,
    @SerializedName("resolved_report_id") val resolvedReportId: String?,
    @SerializedName("created_at") val createdAt: String
)

data class MedicationHistory(
    @SerializedName("patientName") val patientName: String,
    @SerializedName("medicineName") val medicineName: String,
    @SerializedName("currentDosage") val currentDosage: String,
    @SerializedName("currentFrequency") val currentFrequency: String = "",
    @SerializedName("currentDuration") val currentDuration: String = "",
    @SerializedName("previousDosage") val previousDosage: String = "",
    @SerializedName("previousFrequency") val previousFrequency: String = "",
    @SerializedName("status") val status: String, // Active, Changed, Discontinued
    @SerializedName("lastUpdated") val lastUpdated: String,
    @SerializedName("reportId") val reportId: String = "",
    @SerializedName("isOptional") val isOptional: Boolean = false,
    @SerializedName("weeklySchedule") val weeklySchedule: List<String> = emptyList(),
    @SerializedName("notes") val notes: String = "",
    @SerializedName("currentStartDate") val currentStartDate: String? = null,
    @SerializedName("currentEndDate") val currentEndDate: String? = null,
    @SerializedName("currentIntervalDays") val currentIntervalDays: Int? = null
)

data class ScannedReportData(
    @SerializedName("patient_name") val patientName: String?,
    @SerializedName("report_date") val reportDate: String?,
    @SerializedName("report_type") val reportType: String?,
    @SerializedName("report_category") val reportCategory: String?,
    @SerializedName("medications") val medications: List<Medication> = emptyList(),
    @SerializedName("test_results") val testResults: TestResults? = null,
    @SerializedName("comments") val comments: String?,
    @SerializedName("raw_text") val rawText: String? = null
)

data class CompareResponse(
    @SerializedName("report1") val report1: ScannedReportData,
    @SerializedName("report2") val report2: ScannedReportData,
    @SerializedName("comparison") val comparison: ComparisonResult
)

@Entity(tableName = "med_logs", indices = [Index("patientName", "medicineName")])
data class MedLogEntry(
    @PrimaryKey @SerializedName("id") val id: String,
    @SerializedName("patient_name") val patientName: String,
    @SerializedName("medicine_name") val medicineName: String,
    @SerializedName("action_type") val actionType: String,
    @SerializedName("frequency") val frequency: String? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("taken_at") val takenAt: String
)

data class MedicationBulkItem(
    @SerializedName("reportId") val reportId: String,
    @SerializedName("medicineName") val medicineName: String,
    @SerializedName("patientName") val patientName: String? = null,
    @SerializedName("frequency") val frequency: String? = null,
    @SerializedName("weeklySchedule") val weeklySchedule: List<String>? = null,
    @SerializedName("isOptional") val isOptional: Boolean? = null
)

data class MedicationBulkRequest(
    @SerializedName("items") val items: List<MedicationBulkItem>
)

data class DetailedAnalysisSection(
    @SerializedName("title") val title: String,
    @SerializedName("content") val content: String
)

data class DetailedAnalysis(
    @SerializedName("summary") val summary: String = "",
    @SerializedName("sections") val sections: List<DetailedAnalysisSection> = emptyList(),
    @SerializedName("disclaimer") val disclaimer: String = "",
    @SerializedName("source") val source: String = "", // "ai" or "local"
    @SerializedName("generatedAt") val generatedAt: String? = null,
    @SerializedName("cached") val cached: Boolean = false
)

data class ChatMessage(
    @SerializedName("role") val role: String, // "user" or "assistant"
    @SerializedName("content") val content: String
)

data class ChatRequest(
    @SerializedName("question") val question: String,
    @SerializedName("patient_name") val patientName: String? = null,
    @SerializedName("report_id") val reportId: String? = null,
    @SerializedName("history") val history: List<ChatMessage> = emptyList(),
    @SerializedName("language") val language: String = "English",
    @SerializedName("image_path") val imagePath: String? = null
)

data class ChatResponse(
    @SerializedName("answer") val answer: String = "",
    @SerializedName("source") val source: String = "" // "ai" or "local"
)

data class TtsRequest(
    @SerializedName("text") val text: String,
    @SerializedName("language") val language: String = "English",
    @SerializedName("engine") val engine: String = "sarvam" // "sarvam" or "gemini"
)

data class TtsResponse(
    @SerializedName("audios") val audios: List<String> = emptyList() // base64 WAV clips
)

data class DashboardData(
    @SerializedName("reports") val reports: List<MedicalReport> = emptyList(),
    @SerializedName("pendingTests") val pendingTests: List<PendingTest> = emptyList(),
    @SerializedName("medicationHistory") val medicationHistory: List<MedicationHistory> = emptyList(),
    @SerializedName("testInferences") val testInferences: List<TestInference> = emptyList()
)

data class MedicineInfo(
    @SerializedName("category") val category: String = "",
    @SerializedName("genericName") val genericName: String = "",
    @SerializedName("basicUse") val basicUse: String = "",
    @SerializedName("keyNotes") val keyNotes: List<String> = emptyList()
)

// ── Auth / per-user free tier ────────────────────────────────────────────────

data class AuthRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)

/** Full registration: profile fields + email/password, plus an OPTIONAL Firebase phone-OTP ID
 *  token. Signup works without it (email+password only, no billed SMS); [phoneIdToken] is only
 *  sent when phone auth is enabled (see FeatureFlags.PHONE_AUTH_ENABLED) and the user completed
 *  the OTP step. */
data class SignupRequest(
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("dateOfBirth") val dateOfBirth: String, // "YYYY-MM-DD"
    @SerializedName("gender") val gender: String, // "male" | "female" | "other" | "prefer_not_to_say"
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("phoneIdToken") val phoneIdToken: String? = null
)

/** Phone+OTP login: the phone was already OTP-verified client-side by the Firebase SDK. */
data class PhoneLoginRequest(
    @SerializedName("phoneIdToken") val phoneIdToken: String
)

/** Native Google sign-in: the account was already picked on-device via Credential Manager,
 *  and Firebase Auth turned it into this ID token. */
data class GoogleSignInRequest(
    @SerializedName("idToken") val idToken: String
)

data class UserAccount(
    @SerializedName("id") val id: String,
    @SerializedName("firstName") val firstName: String? = null,
    @SerializedName("lastName") val lastName: String? = null,
    @SerializedName("dateOfBirth") val dateOfBirth: String? = null,
    @SerializedName("gender") val gender: String? = null,
    @SerializedName("email") val email: String,
    @SerializedName("msisdn") val msisdn: String? = null,
    @SerializedName("plan") val plan: String = "free", // "free" | "premium"
    @SerializedName("hasOwnGeminiKey") val hasOwnGeminiKey: Boolean = false,
    @SerializedName("hasOwnSarvamKey") val hasOwnSarvamKey: Boolean = false,
    @SerializedName("createdAt") val createdAt: String? = null
)

data class AuthResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user") val user: UserAccount
)

/** Response from GET /api/auth/keys — which key the phone should use right now, and usage. */
data class KeyAssignment(
    @SerializedName("geminiKey") val geminiKey: String? = null,
    @SerializedName("sarvamKey") val sarvamKey: String? = null,
    @SerializedName("plan") val plan: String = "free",
    @SerializedName("billedTo") val billedTo: String = "free", // "own" | "free" | "premium" | "none"
    @SerializedName("usageToday") val usageToday: Int = 0,
    @SerializedName("limit") val limit: Int = 0,
    @SerializedName("quotaExceeded") val quotaExceeded: Boolean = false
)

data class ApiKeyRequest(
    @SerializedName("api_key") val apiKey: String
)

data class ApiKeyResponse(
    @SerializedName("message") val message: String = "",
    @SerializedName("hasOwnGeminiKey") val hasOwnGeminiKey: Boolean? = null,
    @SerializedName("hasOwnSarvamKey") val hasOwnSarvamKey: Boolean? = null
)

data class ResetPasswordRequest(
    @SerializedName("phoneIdToken") val phoneIdToken: String,
    @SerializedName("newPassword") val newPassword: String
)

data class ChangePasswordRequest(
    @SerializedName("currentPassword") val currentPassword: String,
    @SerializedName("newPassword") val newPassword: String
)

/** PUT /api/auth/me — editable identity fields only; email/phone stay read-only (used for login). */
data class UpdateProfileRequest(
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("dateOfBirth") val dateOfBirth: String? = null,
    @SerializedName("gender") val gender: String? = null
)

data class SimpleResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    // Only changePassword sets this: bumping token_version server-side invalidates every
    // previously-issued token (any other device/session) for security, so this device needs
    // a freshly-signed one to stay logged in instead of being logged out by its own request.
    @SerializedName("token") val token: String? = null
)

// ── Healthcare & Lab Test Discovery ──────────────────────────────────────────

data class DiscoveryTestItem(
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double,
    @SerializedName("timeToReport") val timeToReport: String? = null
)

data class DiscoveryFacility(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("address") val address: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("distance") val distance: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("type") val type: String? = null, // "hospital", "laboratory", "clinic"
    @SerializedName("hasEmergency") val hasEmergency: Boolean = false,
    @SerializedName("homeCollection") val homeCollection: Boolean = false,
    @SerializedName("matchedTests") val matchedTests: List<DiscoveryTestItem> = emptyList(),
    // Doctor details:
    @SerializedName("specialty") val specialty: String? = null,
    @SerializedName("experience") val experience: String? = null,
    @SerializedName("clinic") val clinic: String? = null,
    @SerializedName("fee") val fee: Double? = null,
    @SerializedName("slots") val slots: List<String> = emptyList()
)

data class DiscoverySearchRequest(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("category") val category: String, // hospitals | lab_tests | doctors
    @SerializedName("query") val query: String? = null,
    @SerializedName("mode") val mode: String = "commercial" // commercial | uhi
)

data class DiscoverySearchResponse(
    @SerializedName("results") val results: List<DiscoveryFacility> = emptyList(),
    @SerializedName("search_id") val searchId: String? = null,
    @SerializedName("gateway_status") val gatewayStatus: String? = null,
    @SerializedName("message") val message: String? = null
)

data class UhiPrice(
    @SerializedName("value") val value: Double,
    @SerializedName("currency") val currency: String
)

data class UhiItem(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("descriptor") val descriptor: Map<String, String>? = null,
    @SerializedName("price") val price: UhiPrice? = null,
    @SerializedName("slots") val slots: List<String> = emptyList()
)

data class UhiProvider(
    @SerializedName("provider_id") val providerId: String,
    @SerializedName("provider_name") val providerName: String,
    @SerializedName("type") val type: String,
    @SerializedName("distance") val distance: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("homeCollection") val homeCollection: Boolean = false,
    @SerializedName("items") val items: List<UhiItem> = emptyList()
)

data class UhiPollResponse(
    @SerializedName("search_id") val searchId: String,
    @SerializedName("intent") val intent: String,
    @SerializedName("results") val results: List<UhiProvider> = emptyList()
)

