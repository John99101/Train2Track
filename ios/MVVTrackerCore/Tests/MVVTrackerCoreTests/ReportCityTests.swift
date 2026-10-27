import XCTest
@testable import MVVTrackerCore

/// 1:1 port of Android's `ReportCityTest.kt`: where a train is filed, and which
/// cities a view must load so no report on a train it shows is missed.
final class ReportCityTests: XCTestCase {

    private func city(_ id: String) -> GermanCity { germanCities.first { $0.id == id }! }

    func testTrainInACityCentreIsFiledUnderThatCity() {
        for c in germanCities {
            XCTAssertEqual(c.id, reportCity(latitude: c.latitude, longitude: c.longitude))
        }
    }

    func testTrainOutsideEveryRegionIsFiledUnderOther() {
        XCTAssertEqual(cityOther, reportCity(latitude: 51.0504, longitude: 13.7373))   // Dresden
        XCTAssertEqual(cityOther, reportCity(latitude: 51.3127, longitude: 9.4797))    // Kassel
    }

    func testWhereRegionsOverlapTheNearestCentreWins() {
        // Leverkusen lies in the regions of Köln and Düsseldorf, closer to Köln.
        XCTAssertEqual("koeln", reportCity(latitude: 51.0459, longitude: 6.9853))
        // Duisburg: Düsseldorf and Dortmund regions, closer to Düsseldorf.
        XCTAssertEqual("duesseldorf", reportCity(latitude: 51.4344, longitude: 6.7623))
    }

    func testSingleCityLoadsItselfAndUnknown() {
        XCTAssertEqual(["muenchen", cityUnknown], reportScope(for: city("muenchen")))
        XCTAssertEqual(["berlin", cityUnknown], reportScope(for: city("berlin")))
        XCTAssertEqual(["frankfurt", cityUnknown], reportScope(for: city("frankfurt")))
    }

    func testRuhrCitiesLoadEachOther() {
        let ruhr = ["dortmund", "duesseldorf", "koeln", cityUnknown]
        XCTAssertEqual(ruhr, reportScope(for: city("koeln")))
        XCTAssertEqual(ruhr, reportScope(for: city("duesseldorf")))
        XCTAssertEqual(ruhr, reportScope(for: city("dortmund")))
    }

    func testEveryTrainACityShowsIsFiledUnderACityInItsScope() {
        // Sample each city's whole region: wherever a train is, the city it is
        // filed under must be one this city loads.
        for c in germanCities {
            let scope = Set(reportScope(for: c))
            var lat = c.south
            while lat <= c.north {
                var lon = c.west
                while lon <= c.east {
                    let filed = reportCity(latitude: lat, longitude: lon)
                    XCTAssertTrue(scope.contains(filed), "\(c.id) misses \(filed) at \(lat),\(lon)")
                    lon += 0.05
                }
                lat += 0.05
            }
        }
    }

    func testFreeMapBoxLoadsTheCitiesItTouchesOtherAndUnknown() {
        // Around Dresden: no supported city nearby.
        XCTAssertEqual([cityOther, cityUnknown], reportScope(north: 51.2, south: 50.9, east: 13.95, west: 13.5))
        // A box between Köln and Frankfurt that reaches into Köln's region.
        let scope = reportScope(north: 50.75, south: 50.45, east: 7.6, west: 7.2)
        XCTAssertTrue(scope.contains("koeln"))
        XCTAssertTrue(scope.contains(cityOther))
        XCTAssertFalse(scope.contains("muenchen"))
    }

    func testScopeNeverExceedsWhatOneFirestoreQueryMayAskFor() {
        // whereField(in:) takes at most 30 values.
        for c in germanCities { XCTAssertLessThanOrEqual(reportScope(for: c).count, 30) }
        XCTAssertLessThanOrEqual(reportScope(north: 55.0, south: 47.0, east: 15.0, west: 6.0).count, 30)
    }

    func testFetchedMapAreaIsLargerThanTheViewAndNeverTiny() {
        let view = LivePositions.Box(north: 51.36, south: 51.32, east: 12.42, west: 12.34)
        let wide = view.grown(factor: 0.15, minHalfLat: 0.15, minHalfLon: 0.22)
        XCTAssertTrue(wide.contains(view))
        XCTAssertEqual(0.30, wide.north - wide.south, accuracy: 1e-9)
        XCTAssertEqual(0.44, wide.east - wide.west, accuracy: 1e-9)
        // A big view grows by the factor on every side.
        let big = LivePositions.Box(north: 52.0, south: 51.0, east: 13.0, west: 12.0)
        let grown = big.grown(factor: 0.15, minHalfLat: 0.15, minHalfLon: 0.22)
        XCTAssertEqual(1.30, grown.north - grown.south, accuracy: 1e-9)
        XCTAssertTrue(grown.contains(big))
        XCTAssertFalse(big.contains(grown))
    }
}
