import Foundation

struct FamilyProfile: Codable, Identifiable {
    let id: String
    let name: String
    let relation: String
    let avatarEmoji: String
    var sex: String = ""
    var dateOfBirth: String = ""
}

struct Medication: Codable {
    let name: String
    var dosage: String = ""
    var frequency: String = ""
    var duration: String? = ""
    var isOptional: Bool = false
    var weeklySchedule: [String] = []
    var notes: String? = ""
}

struct TestParameter: Codable {
    var name: String = ""
    var value: String = ""
    var unit: String = ""
    var referenceRange: String = ""
    var status: String? = ""
    var trendCategory: String? = ""
    var trendCondition: String? = ""
    var excludeFromTrend: Bool? = false
}

struct TestResults: Codable {
    var parameters: [TestParameter] = []
    var findings: [String] = []
}

struct SourceFile: Codable {
    let path: String
    let name: String
    var mimeType: String = ""
}

struct MedicalReport: Codable, Identifiable {
    let id: String
    var patientName: String?
    var reportDate: String?
    var reportType: String?
    var extractedText: String?
    var comments: String?
    var medications: [Medication] = []
    var imagePath: String
    var imagePaths: [String] = []
    var sourceFiles: [SourceFile] = []
    let createdAt: String
    var testResults: TestResults? = nil
    var reportCategory: String? = nil
    var pageHashes: [String] = []
    var userEmail: String? = nil
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
        case reportCategory = "report_category"
        case pageHashes = "page_hashes"
        case userEmail = "user_email"
        case analyzed
    }
}
