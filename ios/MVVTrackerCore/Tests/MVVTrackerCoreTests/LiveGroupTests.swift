import XCTest
@testable import MVVTrackerCore

/// Port of Android's `LiveGroupTest.kt`. Must match GROUP_MODES in functions/lib/liveGroups.js.
final class LiveGroupTests: XCTestCase {

    private let railModes: Set<String> = ["METRO", "SUBWAY", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL",
                                          "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL"]

    func testEveryShownModeHasTheServersGroup() {
        for m in railModes { XCTAssertEqual(LiveGroup.of(m), .rail, m) }
        XCTAssertEqual(LiveGroup.of("TRAM"), .tram)
        XCTAssertEqual(LiveGroup.of("BUS"), .bus)
        XCTAssertNil(LiveGroup.of("FERRY"))
    }

    func testTheDefaultMapNeedsRailOnly() {
        XCTAssertEqual(LiveGroup.forModes(railModes), [.rail])
    }

    func testAllModesNeedEveryGroup() {
        XCTAssertEqual(LiveGroup.forModes(nil), LiveGroup.all)
        XCTAssertEqual(LiveGroup.forModes(["METRO", "BUS"]), [.rail, .bus])
        XCTAssertEqual(LiveGroup.forModes([]), [])
    }

    func testServerPaths() {
        XCTAssertEqual(LiveGroup.allCases.map(\.rawValue), ["rail", "tram", "bus"])
    }

    func testMergedKeepsTheOrderRailTramBus() {
        func seg(_ label: String, _ mode: String) -> LiveSegment {
            LiveSegment(tripId: label, label: label, mode: mode, colorHex: nil, fromName: nil, toName: nil,
                        fromLat: .nan, fromLon: .nan, toLat: .nan, toLon: .nan,
                        departureMs: 0, arrivalMs: 60_000, delayMinutes: nil, isRealtime: false, shapeKey: "")
        }
        let merged = LiveGroup.merged([.bus: [seg("100", "BUS")], .rail: [seg("S1", "METRO"), seg("U2", "SUBWAY")]])
        XCTAssertEqual(merged.map(\.label), ["S1", "U2", "100"])
        XCTAssertEqual(LiveGroup.merged([:]).count, 0)
    }
}
