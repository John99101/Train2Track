import XCTest
@testable import MVVTrackerCore

final class ReportKeyTests: XCTestCase {
    func testDocIdSanitizesIllegalChars() {
        // Everything not in [A-Za-z0-9_-] becomes '-', exactly like Android.
        let id = ReportKey.documentId(uid: "abc123", tripId: "de:tripId/42 #x.y")
        XCTAssertEqual(id, "abc123__de-tripId-42--x-y")
    }

    func testDocIdKeepsAllowedChars() {
        let id = ReportKey.documentId(uid: "u_1", tripId: "S1-Run_99")
        XCTAssertEqual(id, "u_1__S1-Run_99")
    }

    func testNonAsciiLetterIsReplaced() {
        // Android's regex is ASCII-only; ü must be replaced, not kept.
        let id = ReportKey.documentId(uid: "u", tripId: "Grün")
        XCTAssertEqual(id, "u__Gr-n")
    }

    func testHiddenDocIdMatchesTheServerAndAndroid() {
        // The same value as functions/lib/reportIds.js and ReportIdsTest.kt give.
        XCTAssertEqual(ReportKey.hiddenDocumentId(uid: "abc", tripId: "de:1|2"),
                       "2ae1a21bc6b6cf62b2a4688f169b6a3330e220fcc9ca8aab17797b0436b479bf")
    }

    func testHiddenDocIdDiffersPerTripAndPerDevice() {
        XCTAssertNotEqual(ReportKey.hiddenDocumentId(uid: "abc", tripId: "trip1"),
                          ReportKey.hiddenDocumentId(uid: "abc", tripId: "trip2"))
        XCTAssertNotEqual(ReportKey.hiddenDocumentId(uid: "abc", tripId: "trip1"),
                          ReportKey.hiddenDocumentId(uid: "abd", tripId: "trip1"))
    }

    func testOwnerNoteId() {
        XCTAssertEqual(ReportKey.ownerNoteId(collection: "reports", documentId: "ff00"), "reports__ff00")
        XCTAssertEqual(ReportKey.ownersCollection, "doc_owners")
    }

    func testContractConstants() {
        XCTAssertEqual(ReportKey.collection, "reports")
        XCTAssertEqual(ReportKey.reportTtlMillis, 90 * 60 * 1000)
        XCTAssertEqual(ReportKey.minCorroborations, 1)
    }
}

final class CityLocatorTests: XCTestCase {
    func testNearMunichResolvesToMunchen() {
        let city = CityLocator.nearestCity(lat: 48.14, lon: 11.58)
        XCTAssertEqual(city?.name, "München")
    }

    func testNearBerlinResolvesToBerlin() {
        let city = CityLocator.nearestCity(lat: 52.50, lon: 13.40)
        XCTAssertEqual(city?.name, "Berlin")
    }

    func testFarFromAnyCityReturnsNil() {
        // Middle of the Atlantic — beyond 60 km of every city.
        XCTAssertNil(CityLocator.nearestCity(lat: 0.0, lon: -30.0))
    }

    func testFarFromEveryCityIsNil() {
        // Kassel: >60 km from all nine cities, so no match.
        XCTAssertNil(CityLocator.nearestCity(lat: 51.3127, lon: 9.4797))
    }

    func testDefaultCityIsMunich() {
        XCTAssertEqual(defaultCity.name, "München")
        XCTAssertEqual(germanCities.count, 11)
    }
}

final class TrainInterpolatorTests: XCTestCase {
    func testEpochMillisParsesIsoUtc() {
        var c = DateComponents()
        c.year = 2026; c.month = 8; c.day = 20; c.hour = 15; c.minute = 16; c.second = 0
        c.timeZone = TimeZone(identifier: "UTC")
        let expected = Int64(Calendar(identifier: .gregorian).date(from: c)!
            .timeIntervalSince1970 * 1000)
        XCTAssertEqual(TrainInterpolator.epochMillis("2026-08-20T15:16:00Z"), expected)
    }

    func testEpochMillisParsesFractionalSeconds() {
        XCTAssertNotNil(TrainInterpolator.epochMillis("2026-08-20T15:16:00.500Z"))
    }

    func testEpochMillisRejectsGarbage() {
        XCTAssertNil(TrainInterpolator.epochMillis("not-a-date"))
    }

    private func segment(dep: String, arr: String, mode: String = "METRO") -> MapTripSegment {
        MapTripSegment(
            trips: [MapTripRef(tripId: "trip-1", routeShortName: "S1 (12345)")],
            mode: mode,
            from: MapPlace(name: "A", lat: 0, lon: 0),
            to: MapPlace(name: "B", lat: 0, lon: 10),
            departure: dep,
            arrival: arr,
            polyline: "_p~iF~ps|U_ulLnnqC_mqNvxq`@", // known 3-point line (non-empty decode)
            routeColor: "00A550"
        )
    }

    func testPositionAtMidpointHasLabelAndStops() {
        let dep = "2026-08-20T15:00:00Z"
        let arr = "2026-08-20T15:10:00Z"
        let now = TrainInterpolator.epochMillis("2026-08-20T15:05:00Z")!
        let pos = TrainInterpolator.position(for: segment(dep: dep, arr: arr), nowMs: now)
        XCTAssertNotNil(pos)
        XCTAssertEqual(pos?.label, "S1")          // routeShortName before first space
        XCTAssertEqual(pos?.tripId, "trip-1")
        XCTAssertEqual(pos?.fromStop, "A")
        XCTAssertEqual(pos?.toStop, "B")
        XCTAssertEqual(pos?.colorHex, "00A550")
        XCTAssertEqual(pos?.mode, "METRO")
    }

    func testSegmentNotYetDepartedIsNil() {
        let dep = "2026-08-20T15:00:00Z"
        let arr = "2026-08-20T15:10:00Z"
        let now = TrainInterpolator.epochMillis("2026-08-20T14:59:00Z")!
        XCTAssertNil(TrainInterpolator.position(for: segment(dep: dep, arr: arr), nowMs: now))
    }

    func testNonRailModeDropped() {
        let dep = "2026-08-20T15:00:00Z"
        let arr = "2026-08-20T15:10:00Z"
        let now = TrainInterpolator.epochMillis("2026-08-20T15:05:00Z")!
        XCTAssertNil(TrainInterpolator.position(for: segment(dep: dep, arr: arr, mode: "FERRY"), nowMs: now))
    }

    func testBoundsGuard() {
        XCTAssertTrue(TrainInterpolator.boundsReasonable(north: 48.3, south: 48.0, east: 11.8, west: 11.4))
        // span > 1.2° is rejected
        XCTAssertFalse(TrainInterpolator.boundsReasonable(north: 50.0, south: 48.0, east: 11.8, west: 11.4))
        // degenerate span is rejected
        XCTAssertFalse(TrainInterpolator.boundsReasonable(north: 48.0, south: 48.0, east: 11.4, west: 11.4))
    }
}
