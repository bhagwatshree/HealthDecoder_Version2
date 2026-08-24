import XCTest

/// UAT harness. Drives the shipped app the way a tester would — tap Home's tiles, look at what
/// each destination renders, come back — and writes what it saw to the test log plus an attached
/// screenshot per screen.
///
/// This exists so parity findings are OBSERVED rather than inferred from reading source. A screen
/// that exists as a file can still render an empty placeholder, and only running it shows that.
final class UatWalkthrough: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launch()
        dismissDisclaimerIfPresent()
    }

    private func dismissDisclaimerIfPresent() {
        let understand = app.buttons["I Understand"]
        if understand.waitForExistence(timeout: 5) {
            understand.tap()
        }
    }

    private func snap(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// SwiftUI composes a NavigationLink's accessibility label from its child views, so a tile
    /// reading "📸 / Scan Report" is exposed as the single label "📸 Scan Report". Matching by
    /// equality silently finds nothing; match by containment.
    private func tileButton(_ label: String) -> XCUIElement {
        app.buttons.containing(
            NSPredicate(format: "label CONTAINS[c] %@", label)
        ).firstMatch
    }

    /// Every static text the screen is currently showing — the cheapest honest description of
    /// what a tester would see.
    private func visibleText() -> [String] {
        app.staticTexts.allElementsBoundByIndex
            .prefix(40)
            .compactMap { $0.exists ? $0.label : nil }
            .filter { !$0.isEmpty }
    }

    func testWalkHomeAndEveryTile() throws {
        XCTAssertTrue(app.staticTexts["Health Decoder"].waitForExistence(timeout: 10),
                      "Home did not appear")
        snap("00-Home")
        print("UAT|Home|" + visibleText().joined(separator: " ~ "))

        // The six tiles Home offers, in Android's order.
        let tiles = [
            "Scan Report",
            "Records",
            "Medication Reminders",
            "Doctor Appointments",
            "Medications",
            "Pending Tests",
        ]

        for (index, tile) in tiles.enumerated() {
            let button = tileButton(tile)
            guard button.waitForExistence(timeout: 5) else {
                print("UAT|MISSING-TILE|\(tile)")
                continue
            }
            button.tap()

            // Give the destination a moment to render, then record it.
            _ = app.navigationBars.firstMatch.waitForExistence(timeout: 5)
            let texts = visibleText()
            print("UAT|\(tile)|" + texts.joined(separator: " ~ "))
            snap(String(format: "%02d-%@", index + 1, tile))

            // Back to Home for the next tile.
            let back = app.navigationBars.buttons.element(boundBy: 0)
            if back.exists { back.tap() }
            _ = app.staticTexts["Health Decoder"].waitForExistence(timeout: 5)
        }
    }

    /// The Smart Health Lens banner and the toolbar entries aren't tiles, so they're walked
    /// separately.
    func testWalkBannersAndToolbar() throws {
        XCTAssertTrue(app.staticTexts["Health Decoder"].waitForExistence(timeout: 10))

        let lens = tileButton("Try Smart Health Lens")
        if lens.waitForExistence(timeout: 5) {
            lens.tap()
            _ = app.navigationBars.firstMatch.waitForExistence(timeout: 5)
            print("UAT|SmartHealthLens|" + visibleText().joined(separator: " ~ "))
            snap("10-SmartHealthLens")
            let back = app.navigationBars.buttons.element(boundBy: 0)
            if back.exists { back.tap() }
        } else {
            print("UAT|MISSING|Smart Health Lens banner")
        }

        _ = app.staticTexts["Health Decoder"].waitForExistence(timeout: 5)

        // Account, reached from the toolbar person icon.
        let account = app.navigationBars.buttons.element(boundBy: app.navigationBars.buttons.count - 1)
        if account.exists {
            account.tap()
            _ = app.navigationBars.firstMatch.waitForExistence(timeout: 5)
            print("UAT|Account|" + visibleText().joined(separator: " ~ "))
            snap("11-Account")
        }
    }

    /// Records with no data — the state a brand-new install is actually in, and where an empty
    /// screen with no guidance is a real usability finding.
    func testEmptyStates() throws {
        XCTAssertTrue(app.staticTexts["Health Decoder"].waitForExistence(timeout: 10))
        for screen in ["Records", "Pending Tests", "Medications"] {
            let button = tileButton(screen)
            guard button.waitForExistence(timeout: 5) else { continue }
            button.tap()
            _ = app.navigationBars.firstMatch.waitForExistence(timeout: 5)
            let texts = visibleText()
            print("UAT|EMPTY-\(screen)|" + texts.joined(separator: " ~ "))
            snap("20-empty-\(screen)")
            let back = app.navigationBars.buttons.element(boundBy: 0)
            if back.exists { back.tap() }
            _ = app.staticTexts["Health Decoder"].waitForExistence(timeout: 5)
        }
    }
}
