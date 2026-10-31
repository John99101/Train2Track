import XCTest
@testable import MVVTrackerCore

/// Port of Android's `LiveMotionTest.kt`: standing at stops and the speed curve.
final class LiveMotionTests: XCTestCase {

    private func hop(_ trip: String?, _ from: String, _ to: String, _ depS: Int64, _ arrS: Int64,
                     mode: String = "METRO", fromLat: Double = 48.0, fromLon: Double = 11.0) -> LiveSegment {
        LiveSegment(tripId: trip, label: "S1", mode: mode, colorHex: nil, fromName: from, toName: to,
                    fromLat: fromLat, fromLon: fromLon, toLat: 48.1, toLon: 11.1,
                    departureMs: depS * 1000, arrivalMs: arrS * 1000, delayMinutes: nil, isRealtime: true,
                    shapeKey: "")
    }

    func testAVehicleStandsAtTheStopBetweenItsHops() {
        // Arrives at B at 60 s, leaves B at 120 s: shown at B from 60 s on.
        let out = LiveMotion.withStandingTimes([hop("t", "B", "C", 120, 180), hop("t", "A", "B", 0, 60)])
        XCTAssertEqual(out[0].standFromMs, 60_000)
        // Its first hop in the data: a minute before it leaves.
        XCTAssertEqual(out[1].standFromMs, -60_000)
    }

    func testStandingIsCutAfterFiveMinutes() {
        let out = LiveMotion.withStandingTimes([hop("t", "A", "B", 0, 60), hop("t", "B", "C", 1260, 1320)])
        XCTAssertEqual(out[1].standFromMs, 1_260_000 - LiveMotion.maxStandMs)
    }

    func testNoStandingWhenHopsOverlapOrDoNotMeetOrHaveNoJourney() {
        let overlap = LiveMotion.withStandingTimes([hop("t", "A", "B", 0, 60), hop("t", "A", "B", 0, 60)])
        XCTAssertEqual(overlap[1].standFromMs, 0)
        let apart = LiveMotion.withStandingTimes([hop("t", "A", "B", 0, 60),
                                                  hop("t", "X", "Y", 120, 180, fromLat: 50, fromLon: 9)])
        XCTAssertEqual(apart[1].standFromMs, 120_000)
        let noTrip = LiveMotion.withStandingTimes([hop(nil, "A", "B", 0, 60)])
        XCTAssertEqual(noTrip[0].standFromMs, 0)
    }

    func testALongStopKeepsItsStandingTimeAfterTheHopBeforeLeftTheData() {
        let memory = LiveMotion.StandMemory()
        // First download: arrives at B at 60 s, leaves B at 300 s.
        let first = LiveMotion.withStandingTimes([hop("t", "A", "B", 0, 60), hop("t", "B", "C", 300, 360)],
                                                 memory: memory, nowMs: 100_000)
        XCTAssertEqual(first[1].standFromMs, 60_000)
        // Next download: the hop A to B is gone, the vehicle still stands at B from 60 s.
        let next = LiveMotion.withStandingTimes([hop("t", "B", "C", 300, 360)], memory: memory, nowMs: 200_000)
        XCTAssertEqual(next[0].standFromMs, 60_000)
        // Also when the departure is a minute late.
        let late = LiveMotion.withStandingTimes([hop("t", "B", "C", 360, 420)], memory: memory, nowMs: 210_000)
        XCTAssertEqual(late[0].standFromMs, 60_000)
        // Without the memory it would only be shown a minute before it leaves.
        XCTAssertEqual(LiveMotion.withStandingTimes([hop("t", "B", "C", 300, 360)])[0].standFromMs, 240_000)
        // A second visit of the same stop much later (a loop line) starts fresh.
        let again = LiveMotion.withStandingTimes([hop("t", "B", "C", 1500, 1560)], memory: memory, nowMs: 1_400_000)
        XCTAssertEqual(again[0].standFromMs, 1_440_000)
    }

    func testATrainStartsSlowRunsSteadyAndArrivesSlow() {
        let d: Int64 = 120_000
        XCTAssertEqual(LiveMotion.distanceFraction(elapsedMs: -5_000, durationMs: d, mode: "METRO"), 0, accuracy: 1e-9)
        XCTAssertEqual(LiveMotion.distanceFraction(elapsedMs: d, durationMs: d, mode: "METRO"), 1, accuracy: 1e-9)
        XCTAssertEqual(LiveMotion.distanceFraction(elapsedMs: d / 2, durationMs: d, mode: "METRO"), 0.5, accuracy: 1e-9)
        // Behind constant speed right after leaving, ahead of it right before arriving.
        XCTAssertLessThan(LiveMotion.distanceFraction(elapsedMs: 10_000, durationMs: d, mode: "METRO"), 10.0 / 120)
        XCTAssertGreaterThan(LiveMotion.distanceFraction(elapsedMs: 110_000, durationMs: d, mode: "METRO"), 110.0 / 120)
        // Always moving forward.
        var last = -1.0
        for t in 0...120 {
            let f = LiveMotion.distanceFraction(elapsedMs: Int64(t) * 1000, durationMs: d, mode: "BUS")
            XCTAssertGreaterThanOrEqual(f, last)
            last = f
        }
    }

    func testAStandingVehicleIsShownAtItsStop() {
        // Standing at A from -60 s; at -30 s it is at the start of its track.
        let s = LiveMotion.withStandingTimes([hop("t", "A", "B", 0, 60)])
        let shown = LivePositions.compute(s, nowMs: -30_000, withPositions: true, area: nil, track: { _ in nil })
        XCTAssertEqual(shown.count, 1)
        XCTAssertEqual(shown.first?.latitude ?? 0, 48.0, accuracy: 1e-9)
        XCTAssertEqual(shown.first?.fromStop, "A")
    }
}
