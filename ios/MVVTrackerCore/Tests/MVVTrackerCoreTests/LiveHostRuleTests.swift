import XCTest
@testable import MVVTrackerCore

final class LiveHostRuleTests: XCTestCase {

    func testOnlyHttpsAddressesUnderTrain2TrackOrKeyCdnAreTaken() {
        XCTAssertEqual(LiveHostRule.cdnBase("https://api.example.invalid/")?.host, "api.example.invalid")
        XCTAssertEqual(LiveHostRule.cdnBase(" https://t2t-1a2b.kxcdn.com ")?.host, "t2t-1a2b.kxcdn.com")
        XCTAssertNotNil(LiveHostRule.cdnBase("https://example.invalid/"))
        XCTAssertNil(LiveHostRule.cdnBase(nil))
        XCTAssertNil(LiveHostRule.cdnBase(""))
        XCTAssertNil(LiveHostRule.cdnBase("http://api.example.invalid/"))
        XCTAssertNil(LiveHostRule.cdnBase("https://example.com/"))
        XCTAssertNil(LiveHostRule.cdnBase("https://example.invalid.example.com/"))
        XCTAssertNil(LiveHostRule.cdnBase("https://evilexample.invalid/"))
        XCTAssertNil(LiveHostRule.cdnBase("api.example.invalid"))
    }

    func testOnlyTheLiveDataPaths() {
        XCTAssertTrue(LiveHostRule.isLivePath("/api/live/berlin/rail"))
        XCTAssertTrue(LiveHostRule.isLivePath("/api/live/berlin"))
        XCTAssertFalse(LiveHostRule.isLivePath("/api/routes/berlin"))
        XCTAssertFalse(LiveHostRule.isLivePath("/api/shapes/berlin"))
        XCTAssertFalse(LiveHostRule.isLivePath("/api/route/berlin/TRAM/27/x"))
    }
}
