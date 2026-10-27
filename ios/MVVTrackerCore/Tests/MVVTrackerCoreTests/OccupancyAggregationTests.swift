import XCTest
@testable import MVVTrackerCore

/// The same cases as Android's `OccupancyAggregationTest`.
final class OccupancyAggregationTests: XCTestCase {
    private let now: Int64 = 1_000_000

    private func vote(_ trip: String, _ level: Int, expiresIn: Int64 = 60_000,
                      throttled: Bool = false, own: Bool = false) -> OccupancyVote {
        OccupancyVote(tripId: trip, level: level, expiresAtMs: now + expiresIn, throttled: throttled, own: own)
    }

    func testOneRiderIsShownAtOnce() {
        let result = OccupancyAggregation.aggregate([vote("t1", 3, own: true)], nowMs: now)
        XCTAssertEqual(result["t1"]?.level, 3)
        XCTAssertEqual(result["t1"]?.votes, 1)
        XCTAssertEqual(result["t1"]?.ownLevel, 3)
    }

    func testEveryVoteMovesTheAverage() {
        // 3 and 4 show 4; a third rider with 3 brings it back to 3.
        XCTAssertEqual(OccupancyAggregation.aggregate([vote("t1", 3), vote("t1", 4)], nowMs: now)["t1"]?.level, 4)
        XCTAssertEqual(OccupancyAggregation.aggregate([vote("t1", 3), vote("t1", 4), vote("t1", 3)], nowMs: now)["t1"]?.level, 3)
        XCTAssertEqual(OccupancyAggregation.aggregate([vote("t1", 1), vote("t1", 5), vote("t1", 5)], nowMs: now)["t1"]?.level, 4)
    }

    func testExpiredThrottledAndInvalidVotesDoNotCount() {
        let votes = [
            vote("t1", 5),
            vote("t1", 1, expiresIn: -1),       // older than 20 minutes
            vote("t1", 1, throttled: true),     // over the spam limit
            vote("t1", 9),                      // not a level
            vote("t2", 1), vote("t2", 2)
        ]
        let result = OccupancyAggregation.aggregate(votes, nowMs: now)
        XCTAssertEqual(result["t1"]?.votes, 1)
        XCTAssertEqual(result["t1"]?.level, 5)
        XCTAssertNil(result["t1"]?.ownLevel)
        XCTAssertEqual(result["t2"]?.level, 2)  // 1.5 rounds up
    }

    func testAverageRoundsToTheNearestLevel() {
        XCTAssertEqual(OccupancyAggregation.average([5, 1, 3]), 3)
        XCTAssertEqual(OccupancyAggregation.average([2, 5]), 4)        // 3.5
        XCTAssertEqual(OccupancyAggregation.average([1, 1, 1, 5]), 2)  // 2.0
        XCTAssertEqual(OccupancyAggregation.average([1, 2, 2, 2]), 2)  // 1.75
        XCTAssertTrue(OccupancyAggregation.aggregate([], nowMs: now).isEmpty)
    }
}
