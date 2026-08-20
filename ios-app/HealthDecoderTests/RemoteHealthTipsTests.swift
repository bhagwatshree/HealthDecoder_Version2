import XCTest
@testable import HealthDecoder

/// The health-tips cache is fed by a database table, so its real risk is a row that isn't shaped
/// the way the code assumes. Android has already been bitten by exactly this: Gson wrote null into
/// non-null Kotlin fields and a later `.lowercase()` crashed the screen. Swift fails the opposite
/// way — one malformed row would make the whole array fail to decode and silently empty the cache.
///
/// These cases pin the middle ground: tolerate bad rows, drop the unusable ones, keep the rest.
final class RemoteHealthTipsTests: XCTestCase {

    private func parse(_ json: String) -> [String: HealthTip] {
        RemoteHealthTips.parse(Data(json.utf8))
    }

    func testParsesTheBackendShape() {
        let tips = parse("""
        [{"canonical_param":"Sodium","status":"low","headline":"Your sodium ran low","detail":"Coconut water helps."}]
        """)
        XCTAssertEqual(tips.count, 1)
        XCTAssertEqual(tips["Sodium|low"]?.headline, "Your sodium ran low")
        XCTAssertEqual(tips["Sodium|low"]?.detail, "Coconut water helps.")
    }

    func testStatusIsLowercasedIntoTheKey() {
        // The DB is free to store "Low"/"HIGH"; lookups always arrive lowercased.
        let tips = parse("""
        [{"canonical_param":"LDL","status":"HIGH","headline":"h","detail":"d"}]
        """)
        XCTAssertNotNil(tips["LDL|high"])
        XCTAssertNil(tips["LDL|HIGH"])
    }

    func testOneBadRowDoesNotDiscardTheGoodOnes() {
        // A row with null headline/detail is still usable — its key is intact.
        let tips = parse("""
        [{"canonical_param":"Sodium","status":"low","headline":"ok","detail":"ok"},
         {"canonical_param":"Iron","status":"low","headline":null,"detail":null},
         {"canonical_param":"LDL","status":"high","headline":"ok2","detail":"ok2"}]
        """)
        XCTAssertEqual(tips.count, 3, "a null-text row must not take the batch down with it")
        XCTAssertEqual(tips["Iron|low"]?.headline, "")
    }

    func testRowsWithoutAKeyAreDropped() {
        // canonical_param and status ARE the cache key — a row missing either can never be
        // looked up, so it is dropped rather than stored under a garbage key.
        let tips = parse("""
        [{"canonical_param":null,"status":"low","headline":"h","detail":"d"},
         {"canonical_param":"Iron","status":"","headline":"h","detail":"d"},
         {"canonical_param":"  ","status":"low","headline":"h","detail":"d"},
         {"canonical_param":"Calcium","status":"low","headline":"keep","detail":"me"}]
        """)
        XCTAssertEqual(tips.count, 1)
        XCTAssertEqual(tips["Calcium|low"]?.headline, "keep")
    }

    func testMissingFieldsEntirelyAreTolerated() {
        // Not just null — absent. A column added later means older cached JSON lacks the key.
        let tips = parse("""
        [{"canonical_param":"TSH","status":"high"}]
        """)
        XCTAssertEqual(tips["TSH|high"]?.headline, "")
    }

    func testMalformedPayloadYieldsAnEmptyCacheRatherThanThrowing() {
        XCTAssertTrue(parse("not json at all").isEmpty)
        XCTAssertTrue(parse("{}").isEmpty)
        XCTAssertTrue(parse("[]").isEmpty)
    }

    func testKeyIsTrimmed() {
        // Padding in a hand-edited DB row must not produce an unlookupable key.
        let tips = parse("""
        [{"canonical_param":" Vitamin D ","status":" low ","headline":"h","detail":"d"}]
        """)
        XCTAssertNotNil(tips["Vitamin D|low"])
    }
}
