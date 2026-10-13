import XCTest
@testable import MVVTrackerCore

/// 1:1 port of Android's `ReportAggregationTest.kt`: both apps must show the same "!".
final class ReportAggregationTests: XCTestCase {

    private let now: Int64 = 1_000_000_000
    private let trip = "T1"

    private func entry(_ rider: String, _ at: Int64, _ flags: String...) -> ReportDoc {
        ReportDoc(rider: rider, tripId: trip, state: "entered", line: "S1", station: "Marienplatz",
                  createdAtMs: at, expiresAtMs: now + 60_000,
                  throttled: flags.contains("throttled"), dismissed: flags.contains("dismissed"),
                  verified: flags.contains("verified"))
    }

    private func exit(_ rider: String, _ at: Int64, own: Bool = false) -> ReportDoc {
        ReportDoc(rider: rider, tripId: trip, state: "exited", line: nil, station: nil,
                  createdAtMs: at, expiresAtMs: now + 60_000, own: own)
    }

    private func flagged(_ docs: ReportDoc...) -> Int {
        ReportAggregation.aggregate(docs, nowMs: now)[trip]!.flagged
    }

    func testEntriesCount() {
        XCTAssertEqual(2, flagged(entry("a", now - 5000), entry("b", now - 4000)))
    }

    func testSingleExitFromSomeoneElseDoesNotClear() {
        XCTAssertEqual(1, flagged(entry("a", now - 5000), exit("troll", now - 1000)))
    }

    func testTwoDifferentExitsClear() {
        XCTAssertEqual(0, flagged(entry("a", now - 5000), exit("b", now - 2000), exit("c", now - 1000)))
    }

    func testReporterLeavingClearsTheirOwnReport() {
        // "a" entered, then flipped its own doc to exited: only the exit is left.
        XCTAssertEqual(0, flagged(exit("a", now - 1000)))
    }

    func testExitsOlderThanANewEntryDoNotCount() {
        XCTAssertEqual(1, flagged(exit("b", now - 9000), exit("c", now - 8000), entry("a", now - 1000)))
    }

    func testOwnExitClearsOnlyForSelf() {
        XCTAssertEqual(0, flagged(entry("a", now - 5000), exit("me", now - 1000, own: true)))
        XCTAssertEqual(1, flagged(entry("a", now - 5000), exit("me", now - 1000, own: false)))
    }

    func testDismissedThrottledAndExpiredDoNotCount() {
        let old = entry("e", now - 99_000)
        let expired = ReportDoc(rider: old.rider, tripId: old.tripId, state: old.state, line: old.line,
                                station: old.station, createdAtMs: old.createdAtMs, expiresAtMs: now - 1)
        let result = ReportAggregation.aggregate(
            [entry("a", now - 5000, "dismissed"), entry("b", now - 4000, "throttled"), expired, entry("c", now - 3000)],
            nowMs: now
        )[trip]!
        XCTAssertEqual(1, result.flagged)
        XCTAssertEqual(1, result.dismissed)
        XCTAssertEqual(1, result.throttled)
    }

    func testVerifiedComesThrough() {
        XCTAssertTrue(ReportAggregation.aggregate([entry("a", now - 5000, "verified")], nowMs: now)[trip]!.verified)
    }
}
