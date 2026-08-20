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
