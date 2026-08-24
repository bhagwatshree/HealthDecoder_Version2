import XCTest
import UIKit
@testable import HealthDecoder

/// Proves redaction actually happens to the PIXELS, on the real Vision path, for the image shapes
/// Smart Health Lens produces.
///
/// The existing suite only covered `shouldRedact` — the DECISION. That a line is judged redactable
/// says nothing about whether a black box was drawn over it, in the right place, before upload.
/// These tests render an image, run the exact entry point every upload path calls, and read the
/// pixels back.
final class RedactionPixelTests: XCTestCase {

    /// A prescription-shaped page: patient identity in the letterhead band, medicine rows in the
    /// body. Positions are returned so the assertions can sample the right regions.
    private struct Page {
        let image: UIImage
        let nameRect: CGRect
        let medicineRect: CGRect
    }

    private func makePrescription(size: CGSize = CGSize(width: 800, height: 1000)) -> Page {
        let nameRect = CGRect(x: 40, y: 150, width: 500, height: 34)
        let medicineRect = CGRect(x: 40, y: 520, width: 500, height: 34)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))

            func draw(_ text: String, at point: CGPoint, size fontSize: CGFloat = 26) {
                text.draw(at: point, withAttributes: [
                    .font: UIFont.systemFont(ofSize: fontSize),
                    .foregroundColor: UIColor.black,
                ])
            }

            draw("SUNRISE PATHOLOGY LABORATORY", at: CGPoint(x: 40, y: 40), size: 28)
            draw("Patient Name : Jayant Bhagwat", at: CGPoint(x: nameRect.minX, y: nameRect.minY))
            draw("UHID: 20260819-4471", at: CGPoint(x: 40, y: 200))
            draw("Tab. Dolo 650mg  1-0-1  x 5 days", at: CGPoint(x: medicineRect.minX, y: medicineRect.minY))
            draw("Cap. Amifru 40mg  1-0-0", at: CGPoint(x: 40, y: 570))
        }
        return Page(image: image, nameRect: nameRect, medicineRect: medicineRect)
    }

    /// Fraction of pixels in `rect` that are (near) black.
    private func blackFraction(_ image: UIImage, in rect: CGRect) throws -> Double {
        let cg = try XCTUnwrap(image.cgImage)
        let cropped = try XCTUnwrap(cg.cropping(to: rect))
        let width = cropped.width, height = cropped.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let context = try XCTUnwrap(CGContext(
            data: &pixels, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(cropped, in: CGRect(x: 0, y: 0, width: width, height: height))

        var black = 0
        for i in stride(from: 0, to: pixels.count, by: 4) {
            if pixels[i] < 40 && pixels[i + 1] < 40 && pixels[i + 2] < 40 { black += 1 }
        }
        return Double(black) / Double(width * height)
    }

    // MARK: - The Smart Health Lens contract

    /// The entry point Smart Health Lens, chat and the scan pipeline all call.
    func testRedactedForUploadBlacksOutTheNameAndKeepsTheMedicineRow() async throws {
        let page = makePrescription()
        let jpeg = try XCTUnwrap(page.image.jpegData(compressionQuality: 0.95))

        let result = await PiiRedactor.redactedForUpload(jpeg, mimeType: "image/jpeg")
        XCTAssertNotEqual(result.data, jpeg, "a page with identity on it must not be forwarded unchanged")

        let out = try XCTUnwrap(UIImage(data: result.data))

        // Measured as a BEFORE/AFTER delta rather than an absolute threshold. The redaction box
        // hugs the recognizer's line bounds, which are narrower than any rect written by hand, so
        // an absolute "> 90% black" assertion only ever tests how well the fixture rect was
        // guessed. The delta tests the thing that matters: did this region get covered.
        let nameBefore = try blackFraction(page.image, in: page.nameRect)
        let nameAfter = try blackFraction(out, in: page.nameRect)
        let medicineBefore = try blackFraction(page.image, in: page.medicineRect)
        let medicineAfter = try blackFraction(out, in: page.medicineRect)

        XCTAssertGreaterThan(nameAfter - nameBefore, 0.4,
                             "the patient-name line was not covered (before \(nameBefore), after \(nameAfter))")
        XCTAssertLessThan(abs(medicineAfter - medicineBefore), 0.1,
                          "the medicine row changed — covering it is a lost dose (before \(medicineBefore), after \(medicineAfter))")
        XCTAssertGreaterThan(nameAfter, medicineAfter + 0.3,
                             "identity should end up far darker than clinical content")
    }

    /// The same page before redaction, as a control. Without this the test above could pass on an
    /// image that was already dark, or on a crop that never contained the text at all.
    func testTheControlPageIsNotAlreadyBlack() async throws {
        let page = makePrescription()
        let nameBlack = try blackFraction(page.image, in: page.nameRect)
        XCTAssertLessThan(nameBlack, 0.3,
                          "fixture is wrong: the name region is already dark before redaction")
    }

    /// A frame with nothing identity-bearing — a bare medicine strip, the common Smart Lens case —
    /// is forwarded BYTE-FOR-BYTE, so it never pays JPEG generation loss for nothing.
    func testAPageWithNoIdentityIsForwardedUnchanged() async throws {
        let size = CGSize(width: 600, height: 400)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let strip = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            "DOLO 650".draw(at: CGPoint(x: 60, y: 160), withAttributes: [
                .font: UIFont.boldSystemFont(ofSize: 48), .foregroundColor: UIColor.black,
            ])
        }
        let jpeg = try XCTUnwrap(strip.jpegData(compressionQuality: 0.95))
        let result = await PiiRedactor.redactedForUpload(jpeg, mimeType: "image/jpeg")
        XCTAssertEqual(result.data, jpeg, "nothing to redact — the original bytes should pass through")
        XCTAssertEqual(result.mimeType, "image/jpeg")
    }

    /// Fails open: bytes that aren't a decodable image are forwarded rather than dropped. Losing a
    /// clinical page is worse than a letterhead reaching the model.
    func testUndecodableBytesAreForwardedNotDropped() async throws {
        let garbage = Data("definitely not an image".utf8)
        let result = await PiiRedactor.redactedForUpload(garbage, mimeType: "image/jpeg")
        XCTAssertEqual(result.data, garbage)
        XCTAssertEqual(result.mimeType, "image/jpeg")
    }
}
