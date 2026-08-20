import XCTest
@testable import HealthDecoder

/// Port of `PiiRedactorTest.kt`, case for case.
///
/// The redactor's real risk is not under-redacting — it is OVER-redacting: a black box across a
/// medication row or a lab value is a wrong dose or a lost result, which is far worse than a lab's
/// name reaching the model. The "must survive" cases below are therefore the important half of
/// this file, and should be treated as regressions if they ever start failing.
///
/// `PiiRedactor.shouldRedact`'s second argument is the line's vertical position as 0…1 down the
/// page; 0.05 stands in for the letterhead band, 0.5 for the body, 0.95 for the footer/signature.
final class PiiRedactorTests: XCTestCase {

    private func body(_ line: String) -> Bool {
        PiiRedactor.shouldRedact(line, topFraction: 0.5)
    }
    private func letterhead(_ line: String) -> Bool {
        PiiRedactor.shouldRedact(line, topFraction: 0.05)
    }
    private func footer(_ line: String) -> Bool {
        PiiRedactor.shouldRedact(line, topFraction: 0.95)
    }

    // MARK: - Must be covered

    func testCoversThePatientNameWhereverItAppears() {
        XCTAssertTrue(body("Patient Name : Jayant Bhagwat"))
        XCTAssertTrue(letterhead("Name: Mrs. Sunita Deshmukh"))
        XCTAssertTrue(body("Pt. Name - RAMESH KUMAR"))
    }

    func testCoversAccountAndRecordIdentifiers() {
        XCTAssertTrue(body("UHID: 20260819-4471"))
        XCTAssertTrue(body("Lab No : 5512348"))
        XCTAssertTrue(body("Accession No. AC-99120"))
        XCTAssertTrue(body("Patient ID: P/2026/8841"))
    }

    func testCoversContactDetails() {
        XCTAssertTrue(body("Ph: 9876543210"))
        XCTAssertTrue(body("reports@metropolisindia.com"))
        XCTAssertTrue(body("+91 98765 43210"))
    }

    func testCoversTheReferringOrSigningClinician() {
        XCTAssertTrue(body("Ref. By: Dr. A. K. Sharma"))
        XCTAssertTrue(body("Consultant Pathologist"))
        XCTAssertTrue(footer("Dr. Meera Iyer, MD"))
        XCTAssertTrue(letterhead("Dr. Rajesh Nair"))
    }

    func testCoversTheLabLetterheadAndFooter() {
        XCTAssertTrue(letterhead("SRL Diagnostics Pvt. Ltd."))
        XCTAssertTrue(letterhead("Apollo Hospitals, Navi Mumbai"))
        XCTAssertTrue(footer("Sunrise Pathology Laboratory"))
    }

    // MARK: - Must survive: covering any of these corrupts the extraction

    func testKeepsReportHeadingsThatMerelyContainAProviderWord() {
        XCTAssertFalse(letterhead("PATHOLOGY REPORT"))
        XCTAssertFalse(letterhead("LABORATORY TEST REPORT"))
        XCTAssertFalse(letterhead("DIAGNOSTIC IMAGING REPORT"))
    }

    func testKeepsLabResultRows() {
        XCTAssertFalse(body("Haemoglobin        13.5    g/dL     13.0 - 17.0"))
        XCTAssertFalse(body("Total Cholesterol  212     mg/dL    < 200      High"))
        XCTAssertFalse(body("TSH (Ultra Sensitive)   4.12   uIU/mL"))
    }

    func testKeepsMedicationRowsIncludingPharmaBrandsThatStartWithDr() {
        XCTAssertFalse(body("Tab. Dolo 650mg  1-0-1  x 5 days"))
        // "Dr. Reddy's" is a manufacturer printed in the body of a prescription, not a clinician.
        XCTAssertFalse(body("Dr. Reddy's Omez 20mg  0-0-1"))
        XCTAssertFalse(body("Cap. Amifru 40mg   1-0-0   till follow-up"))
    }

    func testKeepsDatesAndTheirLabels() {
        XCTAssertFalse(body("Reported on : 19/08/2026"))
        XCTAssertFalse(body("Sample Collected : 18-08-2026 09:14"))
        XCTAssertFalse(body("Date of Examination: 12 March 2026"))
    }

    func testKeepsClinicalNarrative() {
        XCTAssertFalse(body("Mild fatty liver changes noted in segment IV."))
        XCTAssertFalse(body("Advise: repeat lipid profile after 3 months."))
        XCTAssertFalse(body("No focal lesion identified."))
    }

    func testIgnoresBlankLines() {
        XCTAssertFalse(body(""))
        XCTAssertFalse(body("   "))
    }
}

/// Exercises the rules against REAL Vision output rather than hand-written strings: every line
/// and vertical position below was captured by running `VNRecognizeTextRequest` over a rendered
/// lab report with a letterhead, an identity block, a results table and a signature block.
///
/// This is the check that catches a rule that works on tidy test input but not on what the
/// recognizer actually emits — note "Reported on: 19/08/2026" arrives without the space before
/// the colon that the hand-written case has.
final class PiiRedactorRealPageTests: XCTestCase {

    /// (topFraction, line) exactly as Vision reported them.
    private static let page: [(CGFloat, String)] = [
        (0.038, "SUNRISE PATHOLOGY LABORATORY"),
        (0.071, "Plot 14, MIDC, Pune 411019 Ph: 9876543210"),
        (0.092, "reports@sunrisepath.com"),
        (0.134, "LABORATORY TEST REPORT"),
        (0.180, "Patient Name : Jayant Bhagwat"),
        (0.199, "UHID: 20260819-4471"),
        (0.221, "Ref. By: Dr. A. K. Sharma"),
        (0.244, "Reported on: 19/08/2026"),
        (0.297, "Test"), (0.295, "Result"), (0.295, "Unit"), (0.296, "Reference"),
        (0.324, "Haemoglobin"), (0.324, "13.5"), (0.323, "g/dL"), (0.322, "13.0 - 17.0"),
        (0.350, "Total Cholesterol"), (0.347, "212"), (0.347, "mg/dL"), (0.347, "< 200"),
        (0.375, "HbA1c"), (0.374, "6.8"), (0.375, "%"), (0.375, "4.0 - 5.6"),
        (0.401, "TSH"), (0.398, "4.12"), (0.398, "ulU/mL"), (0.401, "0.4 - 4.0"),
        (0.426, "Platelets"), (0.423, "2.5"), (0.421, "lakhs/cumm"), (0.424, "1.5 - 4.1"),
        (0.904, "Dr. Meera Iyer, MD"),
        (0.927, "Consultant Pathologist"),
    ]

    private static let mustCover: Set<String> = [
        "SUNRISE PATHOLOGY LABORATORY",
        "Plot 14, MIDC, Pune 411019 Ph: 9876543210",
        "reports@sunrisepath.com",
        "Patient Name : Jayant Bhagwat",
        "UHID: 20260819-4471",
        "Ref. By: Dr. A. K. Sharma",
        "Dr. Meera Iyer, MD",
        "Consultant Pathologist",
    ]

    func testRedactsExactlyTheIdentityLinesOnARealPage() {
        for (topFraction, line) in Self.page {
            let redacted = PiiRedactor.shouldRedact(line, topFraction: topFraction)
            if Self.mustCover.contains(line) {
                XCTAssertTrue(redacted, "identity line left exposed: \(line)")
            } else {
                XCTAssertFalse(redacted, "clinical content wrongly covered: \(line)")
            }
        }
    }

    /// Every value, unit and reference range must survive — this is the assertion that stands
    /// between a redaction rule and a misread dose.
    func testNoResultRowIsEverCovered() {
        let clinical = Self.page.filter { !Self.mustCover.contains($0.1) }
        XCTAssertEqual(clinical.count, 26, "test fixture drifted")
        for (topFraction, line) in clinical {
            XCTAssertFalse(PiiRedactor.shouldRedact(line, topFraction: topFraction),
                           "clinical content wrongly covered: \(line)")
        }
    }
}
