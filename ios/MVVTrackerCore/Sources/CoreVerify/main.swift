import Foundation
import MVVTrackerCore

// A tiny XCTest-free test runner so the ported core logic can be verified with
// the plain Swift toolchain on a machine without Xcode. Mirrors the XCTest
// assertions in Tests/. Exits non-zero on the first failure.

var failures = 0
func check(_ cond: Bool, _ msg: String) {
    if cond { print("  ✓ \(msg)") }
    else { print("  ✗ FAIL: \(msg)"); failures += 1 }
}
func approx(_ a: Double, _ b: Double, _ eps: Double = 1e-6) -> Bool { abs(a - b) <= eps }

print("GeoPolyline")
do {
    let pts = GeoPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
    check(pts.count == 3, "canonical example decodes to 3 points")
    check(approx(pts[0].lat, 38.5) && approx(pts[0].lon, -120.2), "point 0 == (38.5, -120.2)")
    check(approx(pts[1].lat, 40.7) && approx(pts[1].lon, -120.95), "point 1 == (40.7, -120.95)")
    check(approx(pts[2].lat, 43.252) && approx(pts[2].lon, -126.453), "point 2 == (43.252, -126.453)")
    check(GeoPolyline.decode("").isEmpty, "empty string decodes to []")

    let line = [GeoPoint(0, 0), GeoPoint(0, 10)]
    check(GeoPolyline.pointAtFraction(line, 0.0) == GeoPoint(0, 0), "fraction 0 -> first")
    check(GeoPolyline.pointAtFraction(line, 1.0) == GeoPoint(0, 10), "fraction 1 -> last")
    check(GeoPolyline.pointAtFraction(line, -1.0) == GeoPoint(0, 0), "fraction <0 clamps to first")
    check(GeoPolyline.pointAtFraction(line, 2.0) == GeoPoint(0, 10), "fraction >1 clamps to last")
    let mid = GeoPolyline.pointAtFraction(line, 0.5)
    check(mid != nil && approx(mid!.lat, 0) && approx(mid!.lon, 5), "midpoint of equator line == (0,5)")
    check(GeoPolyline.pointAtFraction([], 0.5) == nil, "empty -> nil")
    check(GeoPolyline.pointAtFraction([GeoPoint(1, 2)], 0.5) == GeoPoint(1, 2), "single point -> itself")
}

print("ReportKey (cross-platform Firestore contract)")
do {
    check(ReportKey.documentId(uid: "abc123", tripId: "de:tripId/42 #x.y") == "abc123__de-tripId-42--x-y",
          "doc id sanitizes illegal chars to '-'")
    check(ReportKey.documentId(uid: "u_1", tripId: "S1-Run_99") == "u_1__S1-Run_99",
          "doc id keeps [A-Za-z0-9_-]")
    check(ReportKey.documentId(uid: "u", tripId: "Grün") == "u__Gr-n",
          "non-ASCII letter replaced (ASCII-only like Android)")
    check(ReportKey.hiddenDocumentId(uid: "abc", tripId: "de:1|2")
          == "2ae1a21bc6b6cf62b2a4688f169b6a3330e220fcc9ca8aab17797b0436b479bf",
          "hidden doc id == the server's and Android's value")
    check(ReportKey.ownerNoteId(collection: "reports", documentId: "ff00") == "reports__ff00",
          "owner note id")
    check(ReportKey.collection == "reports", "collection name == reports")
    check(ReportKey.reportTtlMillis == 90 * 60 * 1000, "TTL == 90 min")
    check(ReportKey.minCorroborations == 1, "MIN_CORROBORATIONS == 1")
}

print("CityLocator")
do {
    check(CityLocator.nearestCity(lat: 48.14, lon: 11.58)?.name == "München", "near Munich -> München")
    check(CityLocator.nearestCity(lat: 52.50, lon: 13.40)?.name == "Berlin", "near Berlin -> Berlin")
    check(CityLocator.nearestCity(lat: 0.0, lon: -30.0) == nil, "mid-Atlantic -> nil")
    check(CityLocator.nearestCity(lat: 51.3127, lon: 9.4797) == nil, "Kassel (>60 km from all) -> nil")
    check(defaultCity.name == "München" && germanCities.count == 9, "9 cities, default München")
}

print("TrainInterpolator")
do {
    var comps = DateComponents()
    comps.year = 2026; comps.month = 8; comps.day = 20
    comps.hour = 15; comps.minute = 16; comps.second = 0
    comps.timeZone = TimeZone(identifier: "UTC")
    let expected = Int64(Calendar(identifier: .gregorian).date(from: comps)!.timeIntervalSince1970 * 1000)
    check(TrainInterpolator.epochMillis("2026-08-20T15:16:00Z") == expected, "ISO UTC parses to epoch ms")
    check(TrainInterpolator.epochMillis("2026-08-20T15:16:00.500Z") != nil, "fractional seconds parse")
    check(TrainInterpolator.epochMillis("not-a-date") == nil, "garbage -> nil")

    let seg = MapTripSegment(
        trips: [MapTripRef(tripId: "trip-1", routeShortName: "S1 (12345)")],
        mode: "METRO",
        from: MapPlace(name: "A", lat: 0, lon: 0),
        to: MapPlace(name: "B", lat: 0, lon: 10),
        departure: "2026-08-20T15:00:00Z",
        arrival: "2026-08-20T15:10:00Z",
        polyline: "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
        routeColor: "00A550")
    let now = TrainInterpolator.epochMillis("2026-08-20T15:05:00Z")!
    let pos = TrainInterpolator.position(for: seg, nowMs: now)
    check(pos != nil, "underway METRO segment yields a position")
    check(pos?.label == "S1", "label is routeShortName before first space")
    check(pos?.tripId == "trip-1" && pos?.fromStop == "A" && pos?.toStop == "B", "carries tripId + stops")
    check(pos?.colorHex == "00A550" && pos?.mode == "METRO", "carries color + mode")

    let early = TrainInterpolator.epochMillis("2026-08-20T14:59:00Z")!
    check(TrainInterpolator.position(for: seg, nowMs: early) == nil, "not-yet-departed -> nil")

    let busSeg = MapTripSegment(
        trips: [MapTripRef(tripId: "b", routeShortName: "100")], mode: "FERRY",
        from: MapPlace(name: "A", lat: 0, lon: 0), to: MapPlace(name: "B", lat: 0, lon: 10),
        departure: "2026-08-20T15:00:00Z", arrival: "2026-08-20T15:10:00Z",
        polyline: "_p~iF~ps|U_ulLnnqC_mqNvxq`@", routeColor: nil)
    check(TrainInterpolator.position(for: busSeg, nowMs: now) == nil, "non-shown mode (ferry) dropped")

    check(TrainInterpolator.boundsReasonable(north: 48.3, south: 48.0, east: 11.8, west: 11.4),
          "sensible Munich bbox is fetchable")
    check(!TrainInterpolator.boundsReasonable(north: 50.0, south: 48.0, east: 11.8, west: 11.4),
          "span > 1.2° rejected")
    check(!TrainInterpolator.boundsReasonable(north: 48.0, south: 48.0, east: 11.4, west: 11.4),
          "degenerate span rejected")
}

print("")
if failures == 0 {
    print("ALL CHECKS PASSED ✅")
} else {
    print("\(failures) CHECK(S) FAILED ❌")
    exit(1)
}
