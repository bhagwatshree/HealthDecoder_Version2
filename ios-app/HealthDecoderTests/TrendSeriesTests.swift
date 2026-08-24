import XCTest
@testable import HealthDecoder

/// The trend pipeline is where a silent error becomes a wrong chart: a value converted the wrong
/// way, two different tests merged onto one line, or a normal-range band shaded from a range that
/// only applied to one of the readings.
final class TrendSeriesTests: XCTestCase {

    private func report(
        id: String,
        date: String,
        patient: String = "A",
        params: [TestParameter]
    ) -> MedicalReport {
        var r = MedicalReport(id: id, imagePath: "", createdAt: date)
        r.patientName = patient
        r.reportDate = date
        r.testResults = TestResults(parameters: params)
        return r
    }

    private func param(
        _ name: String, _ value: String, _ unit: String,
        ref: String = "", status: String = "", exclude: Bool? = false,
        trendCategory: String? = ""
    ) -> TestParameter {
        var p = TestParameter()
        p.name = name
        p.value = value
        p.unit = unit
        p.referenceRange = ref
        p.status = status
        p.excludeFromTrend = exclude
        p.trendCategory = trendCategory
        return p
    }

    // MARK: - Reference range parsing

    func testParsesClosedRanges() {
        XCTAssertEqual(DashboardEngine.parseRefRange("70-100").low, 70)
        XCTAssertEqual(DashboardEngine.parseRefRange("70-100").high, 100)
        XCTAssertEqual(DashboardEngine.parseRefRange("13.0 – 17.0 g/dL").low, 13.0)
        XCTAssertEqual(DashboardEngine.parseRefRange("13.0 – 17.0 g/dL").high, 17.0)
        XCTAssertEqual(DashboardEngine.parseRefRange("4.0 to 5.6").high, 5.6)
    }

    func testParsesOneSidedLimits() {
        XCTAssertNil(DashboardEngine.parseRefRange("<200").low)
        XCTAssertEqual(DashboardEngine.parseRefRange("<200").high, 200)
        XCTAssertEqual(DashboardEngine.parseRefRange("> 40").low, 40)
        XCTAssertNil(DashboardEngine.parseRefRange("> 40").high)
        XCTAssertEqual(DashboardEngine.parseRefRange("Up to 5.5").high, 5.5)
        XCTAssertEqual(DashboardEngine.parseRefRange("Less than 200").high, 200)
    }

    /// A bare number with no direction can't be placed as a band — shading from a guess would
    /// tell the reader their value is normal or abnormal on no evidence.
    func testRefusesToGuessABandFromANonRange() {
        XCTAssertNil(DashboardEngine.parseRefRange("150").low)
        XCTAssertNil(DashboardEngine.parseRefRange("150").high)
        XCTAssertNil(DashboardEngine.parseRefRange("Negative").high)
        XCTAssertNil(DashboardEngine.parseRefRange("").high)
        XCTAssertNil(DashboardEngine.parseRefRange(nil).high)
    }

    // MARK: - Series building

    func testGroupsTheSameTestAcrossReportsOntoOneLine() {
        let reports = [
            report(id: "1", date: "2026-01-10", params: [param("Haemoglobin", "13.5", "g/dL")]),
            report(id: "2", date: "2026-02-10", params: [param("Hemoglobin", "14.1", "g/dL")]),
            report(id: "3", date: "2026-03-10", params: [param("HB", "14.4", "g/dL")]),
        ]
        let summary = DashboardEngine.buildHealthSummary(
            reports: reports, standardUnits: DashboardEngine.resolveStandardUnits(reports))
        let hb = summary.parameterTrends.first { $0.name == "Hemoglobin" }
        XCTAssertNotNil(hb, "three spellings of haemoglobin must collapse to one line")
        XCTAssertEqual(hb?.dataPoints.count, 3)
    }

    /// The guard that stops one report contributing two points to the same line — Hemoglobin and
    /// MCH both mention "hemoglobin" and would otherwise collapse together.
    func testOneReportContributesAtMostOnePointPerTest() {
        let reports = [report(id: "1", date: "2026-01-10", params: [
            param("Hemoglobin", "13.5", "g/dL"),
            param("MCH", "29", "pg"),
            param("MCHC", "33", "g/dL"),
        ])]
        let summary = DashboardEngine.buildHealthSummary(reports: reports)
        for trend in summary.parameterTrends {
            XCTAssertEqual(trend.dataPoints.count, 1, "\(trend.name) got more than one point from one report")
        }
        XCTAssertEqual(Set(summary.parameterTrends.map(\.name)), ["Hemoglobin", "MCH", "MCHC"])
    }

    func testStandardisesUnitsAcrossLabs() {
        // First report locks mg/dL; the second lab printed mmol/L and must be converted onto it.
        let reports = [
            report(id: "1", date: "2026-01-10", params: [param("Blood Sugar (F)", "108", "mg/dL")]),
            report(id: "2", date: "2026-02-10", params: [param("Fasting Glucose", "6.0", "mmol/L")]),
        ]
        let summary = DashboardEngine.buildHealthSummary(
            reports: reports, standardUnits: DashboardEngine.resolveStandardUnits(reports))
        let sugar = summary.parameterTrends.first { $0.name == "Blood Sugar" }
        XCTAssertEqual(sugar?.dataPoints.count, 2)

        let second = sugar?.dataPoints.last
        XCTAssertEqual(second?.unit, "mg/dL")
        XCTAssertTrue(second?.converted == true)
        XCTAssertEqual(second?.originalUnit, "mmol/L")
        XCTAssertEqual(Float(second?.value ?? "0") ?? 0, 108.1, accuracy: 0.2)
    }

    /// No verified factor means the reading stays exactly as printed and is NOT marked converted.
    /// Silently rescaling it would put a wrong number on a medical chart.
    func testLeavesAnUnconvertibleUnitAsPrinted() {
        let reports = [
            report(id: "1", date: "2026-01-10", params: [param("Ferritin", "40", "ng/mL")]),
            report(id: "2", date: "2026-02-10", params: [param("Ferritin", "55", "made-up-unit")]),
        ]
        let summary = DashboardEngine.buildHealthSummary(
            reports: reports, standardUnits: DashboardEngine.resolveStandardUnits(reports))
        let last = summary.parameterTrends.first { $0.name == "Ferritin" }?.dataPoints.last
        XCTAssertEqual(last?.value, "55")
        XCTAssertEqual(last?.unit, "made-up-unit")
        XCTAssertFalse(last?.converted ?? true)
    }

    /// When a value is converted its reference band has to move with it, or the band would be in
    /// mmol/L while the point is in mg/dL.
    func testConvertsTheReferenceBandAlongsideTheValue() {
        let reports = [
            report(id: "1", date: "2026-01-10", params: [param("Blood Sugar", "100", "mg/dL", ref: "70-100")]),
            report(id: "2", date: "2026-02-10", params: [param("Blood Sugar", "6.0", "mmol/L", ref: "3.9-5.6")]),
        ]
        let summary = DashboardEngine.buildHealthSummary(
            reports: reports, standardUnits: DashboardEngine.resolveStandardUnits(reports))
        let converted = summary.parameterTrends.first { $0.name == "Blood Sugar" }?.dataPoints.last
        XCTAssertEqual(converted?.refLow ?? 0, 70.3, accuracy: 1.0, "3.9 mmol/L ≈ 70 mg/dL")
        XCTAssertEqual(converted?.refHigh ?? 0, 100.9, accuracy: 1.0, "5.6 mmol/L ≈ 101 mg/dL")
    }

    func testExcludedParametersNeverTrend() {
        let reports = [report(id: "1", date: "2026-01-10", params: [
            param("Hemoglobin", "13.5", "g/dL"),
            param("Blood Group", "B+", "", exclude: true),
        ])]
        let summary = DashboardEngine.buildHealthSummary(reports: reports)
        XCTAssertFalse(summary.parameterTrends.contains { $0.name == "Blood Group" })
    }

    /// A urine dipstick's "++" must never join the numeric mg/dL blood-sugar line.
    func testNonNumericGlucoseDoesNotJoinTheBloodSugarLine() {
        let reports = [report(id: "1", date: "2026-01-10", params: [
            param("Blood Sugar", "108", "mg/dL"),
            param("Glucose", "++", ""),
        ])]
        let summary = DashboardEngine.buildHealthSummary(reports: reports)
        let sugar = summary.parameterTrends.first { $0.name == "Blood Sugar" }
        XCTAssertEqual(sugar?.dataPoints.count, 1, "the dipstick reading must not be plotted here")
    }

    func testBloodSugarContextIsCapturedPerPoint() {
        let reports = [report(id: "1", date: "2026-01-10", params: [
            param("Fasting Blood Sugar (FBS)", "108", "mg/dL"),
        ])]
        let summary = DashboardEngine.buildHealthSummary(reports: reports)
        XCTAssertEqual(summary.parameterTrends.first?.dataPoints.first?.context, "Fasting")
    }

    // MARK: - Direction

    func testDirectionReadsImprovingWhenStatusReturnsToNormal() {
        let reports = [
            report(id: "1", date: "2026-01-10", params: [param("LDL", "180", "mg/dL", status: "High")]),
            report(id: "2", date: "2026-03-10", params: [param("LDL", "95", "mg/dL", status: "Normal")]),
        ]
        let summary = DashboardEngine.buildHealthSummary(reports: reports)
        XCTAssertEqual(summary.parameterTrends.first { $0.name == "LDL" }?.trend, "improving")
    }

    func testDirectionReadsStableForATinyChange() {
        let reports = [
            report(id: "1", date: "2026-01-10", params: [param("Hemoglobin", "14.0", "g/dL", status: "Normal")]),
            report(id: "2", date: "2026-03-10", params: [param("Hemoglobin", "14.1", "g/dL", status: "Normal")]),
        ]
        let summary = DashboardEngine.buildHealthSummary(reports: reports)
        XCTAssertEqual(summary.parameterTrends.first { $0.name == "Hemoglobin" }?.trend, "stable")
    }

    // MARK: - Categories

    func testEveryTestLandsInExactlyOnePanel() {
        XCTAssertEqual(DashboardEngine.categoryOf("Hemoglobin"), "Blood Count")
        XCTAssertEqual(DashboardEngine.categoryOf("HbA1c"), "Diabetes")
        XCTAssertEqual(DashboardEngine.categoryOf("LDL"), "Heart & Cholesterol")
        XCTAssertEqual(DashboardEngine.categoryOf("INR"), "Coagulation (PT/INR)")
        // Anything unmatched must still be reachable rather than hidden.
        XCTAssertEqual(DashboardEngine.categoryOf("Some Novel Assay"), DashboardEngine.categoryOther)
    }
}
