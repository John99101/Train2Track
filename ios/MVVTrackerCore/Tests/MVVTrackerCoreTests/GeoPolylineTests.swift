import XCTest
@testable import MVVTrackerCore

final class GeoPolylineTests: XCTestCase {

    /// The canonical Google encoded-polyline reference example.
    /// `_p~iF~ps|U_ulLnnqC_mqNvxq`@` → (38.5,-120.2) (40.7,-120.95) (43.252,-126.453)
    func testDecodeCanonicalExample() {
        let points = GeoPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        XCTAssertEqual(points.count, 3)
        XCTAssertEqual(points[0].lat, 38.5, accuracy: 1e-6)
        XCTAssertEqual(points[0].lon, -120.2, accuracy: 1e-6)
        XCTAssertEqual(points[1].lat, 40.7, accuracy: 1e-6)
        XCTAssertEqual(points[1].lon, -120.95, accuracy: 1e-6)
        XCTAssertEqual(points[2].lat, 43.252, accuracy: 1e-6)
        XCTAssertEqual(points[2].lon, -126.453, accuracy: 1e-6)
    }

    func testDecodeEmptyString() {
        XCTAssertTrue(GeoPolyline.decode("").isEmpty)
    }

    func testPointAtFractionEndpoints() {
        let pts = [GeoPoint(0, 0), GeoPoint(0, 10)]
        XCTAssertEqual(GeoPolyline.pointAtFraction(pts, 0.0), GeoPoint(0, 0))
        XCTAssertEqual(GeoPolyline.pointAtFraction(pts, 1.0), GeoPoint(0, 10))
        // out-of-range clamps to the ends
        XCTAssertEqual(GeoPolyline.pointAtFraction(pts, -1.0), GeoPoint(0, 0))
        XCTAssertEqual(GeoPolyline.pointAtFraction(pts, 2.0), GeoPoint(0, 10))
    }

    func testPointAtFractionMidpointEastWest() {
        // Along the equator lon-distance is not lat-corrected (cos(0)=1),
        // so half the fraction is half the longitude.
        let pts = [GeoPoint(0, 0), GeoPoint(0, 10)]
        let mid = GeoPolyline.pointAtFraction(pts, 0.5)
        XCTAssertEqual(mid?.lat ?? .nan, 0.0, accuracy: 1e-9)
        XCTAssertEqual(mid?.lon ?? .nan, 5.0, accuracy: 1e-9)
    }

    func testPointAtFractionEmptyAndSingle() {
        XCTAssertNil(GeoPolyline.pointAtFraction([], 0.5))
        XCTAssertEqual(GeoPolyline.pointAtFraction([GeoPoint(1, 2)], 0.5), GeoPoint(1, 2))
    }

    /// A round-trip sanity check: decoding then interpolating start/end of a real
    /// segment returns the first/last decoded vertices.
    func testInterpolationSpansDecodedLine() {
        let pts = GeoPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        XCTAssertEqual(GeoPolyline.pointAtFraction(pts, 0.0), pts.first)
        XCTAssertEqual(GeoPolyline.pointAtFraction(pts, 1.0), pts.last)
    }
}
