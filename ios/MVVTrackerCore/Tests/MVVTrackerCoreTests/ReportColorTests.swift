import XCTest
@testable import MVVTrackerCore

/// Port of Android's `ReportMarkTest.kt`: both apps colour the "!" alike.
final class ReportColorTests: XCTestCase {

    func testColourFollowsHowManyRidersReport() {
        XCTAssertEqual(ReportColor.orange, ReportColor.forRiders(1))
        XCTAssertEqual(ReportColor.lightRed, ReportColor.forRiders(2))
        XCTAssertEqual(ReportColor.lightRed, ReportColor.forRiders(5))
        XCTAssertEqual(ReportColor.red, ReportColor.forRiders(6))
        XCTAssertEqual(ReportColor.red, ReportColor.forRiders(10))
        XCTAssertEqual(ReportColor.brightRed, ReportColor.forRiders(11))
    }

    /// 3:1 is the WCAG minimum for large or bold text; the white "!" is bold.
    func testTheWhiteMarkStaysReadableOnEveryColour() {
        func channel(_ v: UInt32) -> Double {
            let c = Double(v) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        for rgb in [ReportColor.orange, ReportColor.lightRed, ReportColor.red, ReportColor.brightRed] {
            let l = 0.2126 * channel(rgb >> 16 & 0xFF) + 0.7152 * channel(rgb >> 8 & 0xFF) + 0.0722 * channel(rgb & 0xFF)
            XCTAssertGreaterThanOrEqual((1 + 0.05) / (l + 0.05), 3, String(rgb, radix: 16))
        }
    }
}
