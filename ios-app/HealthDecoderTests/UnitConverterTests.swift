import XCTest
@testable import HealthDecoder

/// Port of `UnitConverterTest.kt`, case for case.
///
/// The null-on-unknown cases matter most: plotting a guessed conversion onto a medical trend
/// line is worse than leaving the reading off the chart.
final class UnitConverterTests: XCTestCase {

    func testNotationVariantsCollapseToTheSameCanonicalUnit() {
        let mgdl = UnitConverter.canonicalizeUnitString("mg/dL")
        XCTAssertEqual(mgdl, UnitConverter.canonicalizeUnitString("mg %"))
        XCTAssertEqual(mgdl, UnitConverter.canonicalizeUnitString("mg%"))
        XCTAssertEqual(mgdl, UnitConverter.canonicalizeUnitString("mg/100mL"))

        let gdl = UnitConverter.canonicalizeUnitString("g/dL")
        XCTAssertEqual(gdl, UnitConverter.canonicalizeUnitString("gm %"))
        XCTAssertEqual(gdl, UnitConverter.canonicalizeUnitString("gm/dl"))

        // micro sign, Greek mu, and the "u"/"m" spellings of TSH units all coincide
        XCTAssertEqual(UnitConverter.canonicalizeUnitString("uIU/mL"),
                       UnitConverter.canonicalizeUnitString("µIU/mL"))
        XCTAssertEqual(UnitConverter.canonicalizeUnitString("uIU/mL"),
                       UnitConverter.canonicalizeUnitString("mIU/L"))
    }

    func testSameUnitIgnoringNotationNeedsNoConversion() {
        // mg% and mg/dL are identical, so glucose should not be "converted" between them
        let converted = try? XCTUnwrap(UnitConverter.convert(category: "Blood Sugar", value: 115, fromUnit: "mg %", toUnit: "mg/dL"))
        XCTAssertEqual(converted ?? 0, 115, accuracy: 0.001)
    }

    func testGlucoseConvertsBetweenMmolPerLAndMgPerDL() throws {
        // 1 mmol/L = 18.016 mg/dL
        let toMgdl = try XCTUnwrap(UnitConverter.convert(category: "Blood Sugar", value: 6.0, fromUnit: "mmol/L", toUnit: "mg/dL"))
        XCTAssertEqual(toMgdl, 108.1, accuracy: 0.1)

        let toMmol = try XCTUnwrap(UnitConverter.convert(category: "Blood Sugar", value: 108.1, fromUnit: "mg/dL", toUnit: "mmol/L"))
        XCTAssertEqual(toMmol, 6.0, accuracy: 0.01)
    }

    func testT3ConvertsNmolPerLToNgPerMLTowardTheStandard() throws {
        // 0.68 nmol/L standardized to ng/mL (1 nmol/L = 0.651 ng/mL)
        let converted = try XCTUnwrap(UnitConverter.convert(category: "T3", value: 0.68, fromUnit: "nmol/L", toUnit: "ng/mL"))
        XCTAssertEqual(converted, 0.443, accuracy: 0.01)
    }

    func testConvertingToAUnitAndBackRoundTrips() throws {
        let original: Float = 200
        let toMmol = try XCTUnwrap(
            UnitConverter.convert(category: "Total Cholesterol", value: original, fromUnit: "mg/dL", toUnit: "mmol/L"))
        let back = try XCTUnwrap(
            UnitConverter.convert(category: "Total Cholesterol", value: toMmol, fromUnit: "mmol/L", toUnit: "mg/dL"))
        XCTAssertEqual(original, back, accuracy: 0.01)
    }

    func testUnknownUnitPairReturnsNilRatherThanAGuess() {
        XCTAssertNil(UnitConverter.convert(category: "T3", value: 1.0, fromUnit: "made-up-unit", toUnit: "ng/mL"))
        XCTAssertNil(UnitConverter.convert(category: "Unknown Test", value: 1.0, fromUnit: "mmol/L", toUnit: "mg/dL"))
    }

    func testCategoryMatchingIsCaseInsensitive() {
        XCTAssertNotNil(UnitConverter.convert(category: "blood sugar", value: 6.0, fromUnit: "mmol/L", toUnit: "mg/dL"))
    }

    // MARK: - Beyond the Android suite

    /// The Indian platelet convention is the conversion most likely to be silently wrong, and
    /// Android has no test for it: 1 lakh/µL must read as 100 ×10³/µL, not as 1.
    func testIndianLakhPlateletConventionConverts() throws {
        let converted = try XCTUnwrap(
            UnitConverter.convert(category: "Platelets", value: 2.5, fromUnit: "lakhs/cumm", toUnit: "10^3/uL"))
        XCTAssertEqual(converted, 250, accuracy: 0.01)
    }

    /// mEq/L and mmol/L coincide for monovalent ions but NOT for divalent ones — getting this
    /// backwards would double or halve a calcium result.
    func testValenceIsRespectedForElectrolytesVersusMinerals() throws {
        let sodium = try XCTUnwrap(
            UnitConverter.convert(category: "Sodium", value: 140, fromUnit: "mEq/L", toUnit: "mmol/L"))
        XCTAssertEqual(sodium, 140, accuracy: 0.01, "monovalent: mEq/L ≡ mmol/L")

        let calcium = try XCTUnwrap(
            UnitConverter.convert(category: "Calcium", value: 5.0, fromUnit: "mEq/L", toUnit: "mmol/L"))
        XCTAssertEqual(calcium, 2.5, accuracy: 0.01, "divalent: mEq/L = 2 × mmol/L")
    }

    /// "mmol/Lt" is a real Indian-report spelling; without the suffix fold it would be treated
    /// as an unconvertible unit and the reading would drop off the chart.
    func testLiterSpelledLtFoldsToL() {
        XCTAssertEqual(UnitConverter.canonicalizeUnitString("mmol/Lt"),
                       UnitConverter.canonicalizeUnitString("mmol/L"))
    }

    func testStandardUnitFollowsTheChosenSystem() {
        XCTAssertEqual(UnitConverter.standardUnit(for: "Blood Sugar", system: .conventional), "mg/dL")
        XCTAssertEqual(UnitConverter.standardUnit(for: "Blood Sugar", system: .si), "mmol/L")
        XCTAssertNil(UnitConverter.standardUnit(for: "Not A Test", system: .si))
    }
}
