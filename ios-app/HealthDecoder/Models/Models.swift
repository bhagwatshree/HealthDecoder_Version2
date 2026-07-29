import Foundation

// Mirrors android-app/.../model/Models.kt. CodingKeys match the Kotlin @SerializedName values
// exactly so a future portable export/import (Phase 4) stays cross-compatible with Android.

struct FamilyProfile: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var relation: String
    var avatarEmoji: String
    var sex: String = ""
    var dateOfBirth: String = ""
}

struct Medication: Codable, Equatable {
    var name: String
    var dosage: String = ""
    var frequency: String = ""
    var duration: String? = ""
    var isOptional: Bool = false
    var weeklySchedule: [String] = []
    var notes: String? = ""
}

struct TestParameter: Codable, Equatable {
    var name: String = ""
    var value: String = ""
    var unit: String = ""
    var referenceRange: String = ""
    var status: String? = ""
    var trendCategory: String? = ""
    var trendCondition: String? = ""
    var excludeFromTrend: Bool? = false
}

struct TestResults: Codable, Equatable {
    var parameters: [TestParameter] = []
    var findings: [String] = []
}

struct TestDifference: Codable, Equatable {
    var name: String
    var previous: String
    var current: String
    var change: String = ""
    var status: String = ""
}

struct MedicationChanges: Codable, Equatable {
    var added: [String] = []
    var removed: [String] = []
    var changed: [String] = []
}

struct ComparisonResult: Codable, Equatable {
    var hasComparison: Bool = false
    var previousReportId: String? = nil
    var previousReportDate: String? = nil
    var comparisonSummary: String? = nil
    var status: String? = nil
    var differences: [TestDifference] = []
    var medicationChanges: MedicationChanges? = nil
}

struct SpecialistRecommendation: Codable, Equatable {
    var specialist: String
    var reason: String
    var urgency: String = "Routine" // Routine | Soon | Urgent
}

struct PrescriptionAlignment: Codable, Equatable {
    var aligned: Bool = true
    var score: String = "N/A" // Good | Partial | Poor | N/A
    var analysis: String = ""
    var flags: [String] = []
}

struct MedicineSideEffect: Codable, Equatable {
    var medicine: String
    var commonEffects: [String] = []
    var seriousEffects: [String] = []
    var severity: String = "Mild" // Mild | Moderate | Serious
    var tips: String = ""
}

struct HealthInsights: Codable, Equatable {
    var interpretation: String = ""
    var specialistRecommendations: [SpecialistRecommendation] = []
    var prescriptionAlignment: PrescriptionAlignment? = nil
    var sideEffects: [MedicineSideEffect] = []
}

/// An original file the user imported (image / PDF / Word), preserved for download.
struct SourceFile: Codable, Equatable {
    var path: String
    var name: String
    var mimeType: String = ""
}

struct MedicalReport: Codable, Identifiable, Equatable {
    var id: String
    var patientName: String?
    var reportDate: String?
    var reportType: String?
    var extractedText: String?
    var comments: String?
    var medications: [Medication] = []
    var imagePath: String
    var imagePaths: [String] = []
    var sourceFiles: [SourceFile] = []
    var createdAt: String
    var testResults: TestResults? = nil
    var comparisonResult: ComparisonResult? = nil
    var reportCategory: String? = nil
    var healthInsights: HealthInsights? = nil
    /// SHA-256 of each scanned page / imported file, used to detect duplicate scans.
    var pageHashes: [String] = []
    var userEmail: String? = nil
    /// False for a report the user only UPLOADED (stored the file, no AI analysis yet) to save
    /// API calls when archiving old data — the detail screen offers to analyze it on demand.
    var analyzed: Bool = true

    enum CodingKeys: String, CodingKey {
        case id, comments, medications, createdAt
        case patientName = "patient_name"
        case reportDate = "report_date"
        case reportType = "report_type"
        case extractedText = "extracted_text"
        case imagePath = "image_path"
        case imagePaths = "image_paths"
        case sourceFiles = "source_files"
        case testResults = "test_results"
        case comparisonResult = "comparison_result"
        case reportCategory = "report_category"
        case healthInsights = "health_insights"
        case pageHashes = "page_hashes"
        case userEmail = "user_email"
        case analyzed
    }
}

struct PendingTest: Codable, Identifiable, Equatable {
    var id: String
    var patientName: String
    var testName: String
    var dueDate: String?
    var status: String
    var resolvedReportId: String?
    var createdAt: String

    enum CodingKeys: String, CodingKey {
        case id, status
        case patientName = "patient_name"
        case testName = "test_name"
        case dueDate = "due_date"
        case resolvedReportId = "resolved_report_id"
        case createdAt = "created_at"
    }
}

struct MedLogEntry: Codable, Identifiable, Equatable {
    var id: String
    var patientName: String
    var medicineName: String
    var actionType: String // "TAKEN" | "UPDATE_DETAILS"
    var frequency: String?
    var notes: String?
    var takenAt: String

    enum CodingKeys: String, CodingKey {
        case id, frequency, notes
        case patientName = "patient_name"
        case medicineName = "medicine_name"
        case actionType = "action_type"
        case takenAt = "taken_at"
    }
}

// ── Scanned/AI extraction payload (Gemini response shape) ──────────────────────

struct ScannedReportData: Codable, Equatable {
    var patientName: String?
    var reportDate: String?
    var reportType: String?
    var reportCategory: String?
    var medications: [Medication] = []
    var testResults: TestResults? = nil
    var comments: String?
    var rawText: String? = nil

    enum CodingKeys: String, CodingKey {
        case patientName = "patient_name"
        case reportDate = "report_date"
        case reportType = "report_type"
        case reportCategory = "report_category"
        case medications
        case testResults = "test_results"
        case comments
        case rawText = "raw_text"
    }
}

// ── Auth / per-user free tier ───────────────────────────────────────────────

struct AuthRequest: Codable {
    var email: String
    var password: String
}

/// Full registration: profile fields + email/password + a Firebase phone-OTP ID token (Phase 4;
/// left as an optional-in-practice field so Phase 1's email/password-only signup can send "").
struct SignupRequest: Codable {
    var firstName: String
    var lastName: String
    var dateOfBirth: String // "YYYY-MM-DD"
    var gender: String // "male" | "female" | "other" | "prefer_not_to_say"
    var email: String
    var password: String
    var phoneIdToken: String
}

struct PhoneLoginRequest: Codable {
    var phoneIdToken: String
}

struct GoogleSignInRequest: Codable {
    var idToken: String
}

struct UserAccount: Codable, Equatable {
    var id: String
    var firstName: String?
    var lastName: String?
    var dateOfBirth: String?
    var gender: String?
    var email: String
    var msisdn: String?
    var plan: String = "free" // "free" | "premium"
    var hasOwnGeminiKey: Bool = false
    var hasOwnSarvamKey: Bool = false
    var createdAt: String?
}

struct AuthResponse: Codable {
    var token: String
    var user: UserAccount
}

/// Response from GET /api/auth/keys or /api/auth/usage.
struct KeyAssignment: Codable {
    var geminiKey: String? = nil
    var sarvamKey: String? = nil
    var plan: String = "free"
    var billedTo: String = "free" // "own" | "free" | "premium" | "none"
    var usageToday: Int = 0
    var limit: Int = 0
    var quotaExceeded: Bool = false
}

struct ApiKeyRequest: Codable {
    var apiKey: String

    enum CodingKeys: String, CodingKey {
        case apiKey = "api_key"
    }
}

struct ResetPasswordRequest: Codable {
    var phoneIdToken: String
    var newPassword: String
}

struct ChangePasswordRequest: Codable {
    var currentPassword: String
    var newPassword: String
}

struct SimpleResponse: Codable {
    var success: Bool
    var message: String
    var token: String? = nil
}

struct ChatMessage: Codable, Identifiable, Equatable {
    var id = UUID()
    var role: String // "user" | "assistant"
    var content: String

    enum CodingKeys: String, CodingKey { case role, content }
}

struct DetailedAnalysisSection: Codable, Equatable, Identifiable {
    var title: String
    var content: String
    var id: String { title }
}

struct DetailedAnalysis: Codable, Equatable {
    var summary: String = ""
    var sections: [DetailedAnalysisSection] = []
    var disclaimer: String = ""
    var source: String = "" // "ai" | "local"
    var generatedAt: String? = nil
    var cached: Bool = false
}

struct MedicationHistory: Codable, Identifiable, Equatable {
    var patientName: String
    var medicineName: String
    var currentDosage: String
    var currentFrequency: String = ""
    var currentDuration: String = ""
    var previousDosage: String = ""
    var previousFrequency: String = ""
    var status: String // Active | Changed | Discontinued
    var lastUpdated: String
    var reportId: String = ""
    var isOptional: Bool = false
    var weeklySchedule: [String] = []
    var notes: String = ""

    var id: String { "\(patientName.lowercased())|\(medicineName.lowercased())" }
}

struct MedicineInfo: Codable, Equatable {
    var category: String = ""
    var genericName: String = ""
    var basicUse: String = ""
    var keyNotes: [String] = []
}
