import XCTest
@testable import MVVTrackerCore

/// The app must read exactly what the server writes. Port of Android's
/// `LiveSnapshotContractTest.kt`: the fixture is real output of compactLive()
/// (functions/lib/transitous.js) for three Munich segments.
final class LiveSnapshotContractTests: XCTestCase {

    private let fixture = """
    {"v":1,"city":"muenchen","t":1791303272,"stale":false,
     "stops":[["Neubiberg",48.07569,11.66052],["Neuperlach Süd",48.0889,11.645],["Odeonsplatz",48.14338,11.57811],["Universität",48.15006,11.58105],["Erding, Gestütring Ärztezentr.",48.30808,11.91465],["Erding, Santnerstraße",48.3095,11.91672]],
     "lines":[["S5","METRO","00527f"],["U6","SUBWAY","0065ae"],["501","BUS","00355b"]],
     "segs":[[0,"20261006_17:53_de-DELFI_3432306470",0,1,-152,-32,60,1,"5vH9idQNqlgz"],
             [1,"20261006_18:07_de-DELFI_3428091183",2,3,-152,-92,0,1,"vUVB9C8T6nHR"],
             [2,"20261006_18:10_de-DELFI_3423597683",4,5,-152,-92,null,0,""]]}
    """

    func testReadsTheServerFormat() throws {
        let segs = try LiveSnapshot.parse(Data(fixture.utf8)).segments
        XCTAssertEqual(3, segs.count)

        let s5 = segs[0]
        XCTAssertEqual("20261006_17:53_de-DELFI_3432306470", s5.tripId)
        XCTAssertEqual("S5", s5.label)
        XCTAssertEqual("METRO", s5.mode)
        XCTAssertEqual("00527f", s5.colorHex)
        XCTAssertEqual("Neubiberg", s5.fromName)
        XCTAssertEqual("Neuperlach Süd", s5.toName)
        XCTAssertEqual(1791303120000, s5.departureMs)
        XCTAssertEqual(1791303240000, s5.arrivalMs)
        XCTAssertEqual(1, s5.delayMinutes)          // 60 s late
        XCTAssertTrue(s5.isRealtime)
        XCTAssertEqual("5vH9idQNqlgz", s5.shapeKey)
        XCTAssertEqual(48.07569, s5.fromLat, accuracy: 1e-9)
        XCTAssertEqual(11.645, s5.toLon, accuracy: 1e-9)

        XCTAssertEqual(0, segs[1].delayMinutes)

        // Not real-time and without geometry: no delay, empty key, straight line fallback.
        let bus = segs[2]
        XCTAssertNil(bus.delayMinutes)
        XCTAssertEqual("", bus.shapeKey)
        XCTAssertTrue(bus.hasStopCoordinates)
    }

    func testStraightLineUntilGeometryKnown() throws {
        let segs = try LiveSnapshot.parse(Data(fixture.utf8)).segments
        let bus = segs[2]
        let mid = (bus.departureMs + bus.arrivalMs) / 2
        let trains = LivePositions.compute([bus], nowMs: mid, withPositions: true, area: nil) { _ in nil }
        XCTAssertEqual(1, trains.count)
        XCTAssertEqual((48.30808 + 48.3095) / 2, trains[0].latitude, accuracy: 1e-6)
        XCTAssertEqual((11.91465 + 11.91672) / 2, trains[0].longitude, accuracy: 1e-6)
    }

    func testFeedListsWithoutPositions() throws {
        let segs = try LiveSnapshot.parse(Data(fixture.utf8)).segments
        let now = segs[0].departureMs + 1
        let trains = LivePositions.compute(segs, nowMs: now, withPositions: false, area: nil) { _ in nil }
        XCTAssertEqual(3, trains.count)
    }
}

/// Port of the new cases in Android's `GeoPolylineTest.kt`.
final class GeoPolylineServerTests: XCTestCase {
    private let google = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    func testEncodeIsTheInverseOfDecode() {
        XCTAssertEqual(google, GeoPolyline.encode(GeoPolyline.decode(google)))
    }

    func testShapeKeyMatchesTheServer() {
        // Same value as shapeKey() in functions/lib/transitous.js for this polyline.
        XCTAssertEqual("O50N0pk_msaQ", GeoPolyline.shapeKey(google))
    }

    func testTrackMatchesPointAtFraction() {
        let points = GeoPolyline.decode(google)
        let track = Track.of(points)!
        for f in [0.0, 0.1, 0.33, 0.5, 0.77, 1.0] {
            let got = track.pointAt(f)
            let expected = GeoPolyline.pointAtFraction(points, f)!
            XCTAssertEqual(expected.lat, got.lat, accuracy: 1e-9)
            XCTAssertEqual(expected.lon, got.lon, accuracy: 1e-9)
        }
    }
}

final class CityRegionTests: XCTestCase {
    func testServerIdsMatchAndroid() {
        XCTAssertEqual(germanCities.map(\.id),
                       ["muenchen", "berlin", "hamburg", "frankfurt", "nuernberg",
                        "stuttgart", "koeln", "duesseldorf", "dortmund"])
    }

    func testBoxInsideMunichIsMunich() {
        XCTAssertEqual("muenchen", cityForBox(north: 48.2, south: 48.1, east: 11.7, west: 11.5)?.id)
    }

    func testBoxOutsideEveryCityIsNil() {
        XCTAssertNil(cityForBox(north: 47.6, south: 47.5, east: 10.1, west: 10.0))
    }

    func testRouteKeyKeepsModesApart() {
        XCTAssertNotEqual(routeKey(mode: "BUS", label: "100"), routeKey(mode: "TRAM", label: "100"))
        XCTAssertEqual("METRO|S1", routeKey(mode: "METRO", label: "S1"))
    }
}
