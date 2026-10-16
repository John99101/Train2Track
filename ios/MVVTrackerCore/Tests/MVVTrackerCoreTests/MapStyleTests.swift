import XCTest
@testable import MVVTrackerCore

/// Port of Android's `MapStyleTest.kt` (badge size and draw order on the map).
final class MapStyleTests: XCTestCase {

    func testBadgeSizeFollowsTheVisibleWidth() {
        XCTAssertEqual(.near, badgeTier(longitudeSpan: 0.05))
        XCTAssertEqual(.near, badgeTier(longitudeSpan: 0.131))
        XCTAssertEqual(.mid, badgeTier(longitudeSpan: 0.14))      // the start view of a city
        XCTAssertEqual(.mid, badgeTier(longitudeSpan: 0.524))
        XCTAssertEqual(.far, badgeTier(longitudeSpan: 0.6))
        XCTAssertEqual(.far, badgeTier(longitudeSpan: 3.0))
    }

    func testDrawOrderIsBusTramRailThenSBahnAndUBahn() {
        let order = ["BUS", "TRAM", "REGIONAL_RAIL", "METRO"].map { drawRank(mode: $0) }
        XCTAssertEqual(order.sorted(), order)
        XCTAssertEqual(order.count, Set(order).count)
        XCTAssertEqual(drawRank(mode: "METRO"), drawRank(mode: "SUBWAY"))
        XCTAssertEqual(drawRank(mode: "REGIONAL_RAIL"), drawRank(mode: "HIGHSPEED_RAIL"))
    }

    func testAReportedVehicleIsOnTopWhateverItIs() {
        XCTAssertGreaterThan(drawRank(mode: "BUS", flagged: true), drawRank(mode: "METRO"))
        XCTAssertEqual(drawRank(mode: "BUS", flagged: true), drawRank(mode: "SUBWAY", flagged: true))
    }
}
