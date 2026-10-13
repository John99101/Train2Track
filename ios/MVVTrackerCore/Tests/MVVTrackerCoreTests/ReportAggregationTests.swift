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

    /// `entry` with another station.
    private func entry(_ rider: String, _ at: Int64?, station: String, _ flags: String...) -> ReportDoc {
        ReportDoc(rider: rider, tripId: trip, state: "entered", line: "S1", station: station,
                  createdAtMs: at, expiresAtMs: now + 60_000,
                  throttled: flags.contains("throttled"), dismissed: flags.contains("dismissed"))
    }

    func testTimeAndStationOfTheNewestOnBoardReport() {
        let result = ReportAggregation.aggregate(
            [
                entry("a", now - 600_000),
                entry("b", now - 120_000, station: "Stachus"),
                // Newer, but none of these counts: over the limit, hidden, a "has left" vote.
                entry("c", now - 30_000, station: "Isartor", "throttled"),
                entry("d", now - 20_000, station: "Isartor", "dismissed"),
                exit("e", now - 10_000),
            ],
            nowMs: now
        )[trip]!
        XCTAssertEqual(now - 120_000, result.reportedAtMs)
        XCTAssertEqual("Stachus", result.station)
    }

    func testNoReportTimeWithoutAnOnBoardReport() {
        XCTAssertNil(ReportAggregation.aggregate([exit("a", now - 1000)], nowMs: now)[trip]!.reportedAtMs)
    }

    func testAReportWithoutServerTimeCountsAsMadeNow() {
        let result = ReportAggregation.aggregate([entry("a", nil, station: "Marienplatz")], nowMs: now)[trip]!
        XCTAssertEqual(now, result.reportedAtMs)
    }

    func testMinutesSinceRoundsDownAndIsNeverNegative() {
        XCTAssertEqual(0, ReportAggregation.minutesSince(now - 59_999, nowMs: now))
        XCTAssertEqual(1, ReportAggregation.minutesSince(now - 60_000, nowMs: now))
        XCTAssertEqual(89, ReportAggregation.minutesSince(now - 89 * 60_000 - 5, nowMs: now))
        XCTAssertEqual(0, ReportAggregation.minutesSince(now + 30_000, nowMs: now))
    }
}
