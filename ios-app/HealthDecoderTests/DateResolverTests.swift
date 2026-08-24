import XCTest
@testable import HealthDecoder

/// Port of `DateResolverTest.kt`, case for case.
///
/// The label-priority cases are the ones that matter clinically: a report filed under its
/// "Printed on" date lands in the wrong place on every trend line built from it.
final class DateResolverTests: XCTestCase {

    private func section(
        reportName: String? = nil,
        reportType: String? = nil,
        reportDate: String? = nil,
        dates: [(String, String)] = []
    ) -> ScanExtraction {
        var extraction = ScanExtraction()
        extraction.reportName = reportName
        extraction.reportType = reportType
        extraction.reportDate = reportDate
        extraction.datesFound = dates.map { FoundDate(label: $0.0, date: $0.1) }
        return extraction
    }

    // MARK: - Label priority

    func testLabReportPrefersReportedDateOverCollectedAndPrinted() {
        let s = section(
            reportName: "Complete Blood Count", reportType: "Lab Report",
            dates: [
                ("Printed on", "2026-03-15"),
                ("Sample collected", "2026-03-10"),
                ("Reported on", "2026-03-12"),
            ])
        XCTAssertEqual(DateResolver.resolve(s, category: "blood_test"), "2026-03-12")
    }

    func testLabReportFallsBackToCollectedDateWhenNoReportedDate() {
        let s = section(
            reportType: "Lab Report",
            dates: [("Printed on", "2026-03-15"), ("Collected on", "2026-03-10")])
        XCTAssertEqual(DateResolver.resolve(s, category: "blood_test"), "2026-03-10")
    }

    func testEchoReportPrefersProcedureDateOverReportAndPrintedDates() {
        let s = section(
            reportName: "2D Echocardiography",
            dates: [
                ("Report date", "2026-04-20"),
                ("Date of procedure", "2026-04-18"),
                ("Printed", "2026-04-25"),
            ])
        XCTAssertEqual(DateResolver.resolve(s, category: "2d_echo"), "2026-04-18")
    }

    func testPrescriptionPrefersVisitDate() {
        let s = section(
            reportType: "Prescription",
            dates: [("Printed", "2026-05-02"), ("Visit date", "2026-05-01")])
        XCTAssertEqual(DateResolver.resolve(s, category: "prescription"), "2026-05-01")
    }

    func testUnlabeledDateWinsOverPrintedButLosesToReported() {
        let labeled = section(
            reportType: "Lab Report",
            dates: [("", "2026-02-01"), ("Reported", "2026-02-03")])
        XCTAssertEqual(DateResolver.resolve(labeled, category: "blood_test"), "2026-02-03")

        let bare = section(
            reportType: "Lab Report",
            dates: [("", "2026-02-01"), ("Printed on", "2026-02-05")])
        XCTAssertEqual(DateResolver.resolve(bare, category: "blood_test"), "2026-02-01")
    }

    func testFallsBackToModelChosenDateWhenNothingLabeledParses() {
        let s = section(reportType: "Lab Report", reportDate: "2026-01-20")
        XCTAssertEqual(DateResolver.resolve(s, category: "blood_test"), "2026-01-20")
    }

    // MARK: - Format normalization

    func testParsesIndianDayFirstFormats() {
        XCTAssertEqual(DateResolver.normalize("12/03/2026"), "2026-03-12")
        XCTAssertEqual(DateResolver.normalize("12-03-2026"), "2026-03-12")
        XCTAssertEqual(DateResolver.normalize("12.03.2026"), "2026-03-12")
        XCTAssertEqual(DateResolver.normalize("12 Mar 2026"), "2026-03-12")
        XCTAssertEqual(DateResolver.normalize("12-Mar-2026"), "2026-03-12")
        XCTAssertEqual(DateResolver.normalize("12 March 2026"), "2026-03-12")
        XCTAssertEqual(DateResolver.normalize("Mar 12, 2026"), "2026-03-12")
        XCTAssertEqual(DateResolver.normalize("2026-03-12"), "2026-03-12")
    }

    func testStripsTimePortions() {
        XCTAssertEqual(DateResolver.normalize("12/03/2026 10:45 AM"), "2026-03-12")
        XCTAssertEqual(DateResolver.normalize("2026-03-12T09:30:00"), "2026-03-12")
    }

    func testRejectsImplausibleAndUnparsableDates() {
        XCTAssertNil(DateResolver.normalize("2099-01-01"))   // far future
        XCTAssertNil(DateResolver.normalize("1950-01-01"))   // >60 years old
        XCTAssertNil(DateResolver.normalize("not a date"))
        XCTAssertNil(DateResolver.normalize(nil))
        XCTAssertNil(DateResolver.normalize(""))
    }

    func testSkipsInvalidLabeledDateAndFallsDownTheLadder() {
        let s = section(
            reportType: "Lab Report",
            dates: [("Reported", "2099-12-31"), ("Collected", "2026-03-10")])
        XCTAssertEqual(DateResolver.resolve(s, category: "blood_test"), "2026-03-10")
    }

    // MARK: - Medicine start/end dates (legitimately in the future, unlike report dates)

    /// Real case: a handwritten addition on a follow-up prescription — "Ecosprin Gold 20 — 0-0-1
    /// — from 20/10/26" — a NEW medicine starting about two months after the visit date.
    func testNormalizeMedicineDateAcceptsAFutureDateThatNormalizeRejects() throws {
        let future = try XCTUnwrap(Calendar(identifier: .gregorian)
            .date(byAdding: .month, value: 2, to: Date()))

        let iso = DateFormatter()
        iso.locale = Locale(identifier: "en_US_POSIX")
        iso.dateFormat = "yyyy-MM-dd"

        let dayFirst = DateFormatter()
        dayFirst.locale = Locale(identifier: "en_US_POSIX")
        dayFirst.dateFormat = "dd/MM/yyyy"

        let printed = dayFirst.string(from: future)
        XCTAssertEqual(DateResolver.normalizeMedicineDate(printed), iso.string(from: future))
        // normalize() exists to sanity-check REPORT dates — it must keep rejecting future dates,
        // so the two functions can't quietly collapse into one behaviour.
        XCTAssertNil(DateResolver.normalize(printed))
    }

    func testNormalizeMedicineDateStillRejectsImplausibleOcrGarbage() throws {
        let calendar = Calendar(identifier: .gregorian)
        let dayFirst = DateFormatter()
        dayFirst.locale = Locale(identifier: "en_US_POSIX")
        dayFirst.dateFormat = "dd/MM/yyyy"

        let farFuture = try XCTUnwrap(calendar.date(byAdding: .year, value: 10, to: Date()))
        XCTAssertNil(DateResolver.normalizeMedicineDate(dayFirst.string(from: farFuture)))

        let farPast = try XCTUnwrap(calendar.date(byAdding: .year, value: -10, to: Date()))
        XCTAssertNil(DateResolver.normalizeMedicineDate(dayFirst.string(from: farPast)))
    }

    // MARK: - Beyond the Android suite

    /// Android relies on the device default calendar. On iOS a device set to a non-Gregorian
    /// calendar or a non-English region is a real configuration in India, and it would otherwise
    /// parse "12 Mar 2026" into a different era. The formatter pins locale and calendar for this.
    func testParsingIsIndependentOfDeviceLocaleAndCalendar() {
        XCTAssertEqual(DateResolver.normalize("12 Mar 2026"), "2026-03-12",
                       "month-name parsing must not depend on the device language")
        XCTAssertEqual(DateResolver.normalize("2026-03-12"), "2026-03-12",
                       "ISO parsing must not shift under a non-Gregorian device calendar")
    }

    /// A two-digit year must read as this century, not year 26 AD — the ladder skips a pattern
    /// that parses to an implausible date and tries the next one.
    func testTwoDigitYearResolvesToThisCentury() {
        XCTAssertEqual(DateResolver.normalize("12/03/26"), "2026-03-12")
    }
}
