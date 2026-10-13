import XCTest
@testable import MVVTrackerCore

/// 1:1 port of Android's `FallbackPacerTest.kt`: when the app may ask our server,
/// and when transitous directly.
final class FallbackPacerTests: XCTestCase {

    private let minute: Int64 = 60_000

    func testAtTheStartEverythingIsAllowed() {
        let pacer = FallbackPacer()
        XCTAssertTrue(pacer.serverAllowed(nowMs: 0))
        XCTAssertTrue(pacer.directAllowed("muenchen", nowMs: 0))
    }

    func testServerErrorLeavesTheServerAloneForTwoMinutes() {
        var pacer = FallbackPacer()
        pacer.onServerFailure(nowMs: 1_000, deviceOffline: false)
        XCTAssertFalse(pacer.serverAllowed(nowMs: 1_000))
        XCTAssertFalse(pacer.serverAllowed(nowMs: 1_000 + 2 * minute - 1))
        XCTAssertTrue(pacer.serverAllowed(nowMs: 1_000 + 2 * minute))
    }

    func testPhoneWithoutConnectionDoesNotBackOffFromTheServer() {
        var pacer = FallbackPacer()
        pacer.onServerFailure(nowMs: 1_000, deviceOffline: true)
        XCTAssertTrue(pacer.serverAllowed(nowMs: 1_001))
    }

    func testTransitousIsAskedAtMostOncePerCityInTwoMinutes() {
        var pacer = FallbackPacer()
        pacer.onDirectRequest("muenchen", nowMs: 5_000)
        XCTAssertFalse(pacer.directAllowed("muenchen", nowMs: 5_000))
        XCTAssertFalse(pacer.directAllowed("muenchen", nowMs: 5_000 + 2 * minute - 1))
        XCTAssertTrue(pacer.directAllowed("muenchen", nowMs: 5_000 + 2 * minute))
        // Another city has its own clock.
        XCTAssertTrue(pacer.directAllowed("berlin", nowMs: 5_001))
    }

    func testServerDownForAnHourMakesAboutThirtyDirectRequestsNot180() {
        var pacer = FallbackPacer()
        var direct = 0
        var server = 0
        var now: Int64 = 0
        // The map refreshes every 20 s; the server fails every time it is asked.
        while now < 60 * minute {
            if pacer.serverAllowed(nowMs: now) {
                server += 1
                pacer.onServerFailure(nowMs: now, deviceOffline: false)
            }
            if pacer.directAllowed("muenchen", nowMs: now) {
                pacer.onDirectRequest("muenchen", nowMs: now)
                direct += 1
            }
            now += 20_000
        }
        XCTAssertEqual(30, direct)
        XCTAssertEqual(30, server)
    }

    func testOfflinePhoneTriesTheServerAtEveryRefreshAndTransitousOnlyEveryTwoMinutes() {
        var pacer = FallbackPacer()
        var direct = 0
        var server = 0
        var now: Int64 = 0
        while now < 10 * minute {
            if pacer.serverAllowed(nowMs: now) {
                server += 1
                pacer.onServerFailure(nowMs: now, deviceOffline: true)
            }
            if pacer.directAllowed("muenchen", nowMs: now) {
                pacer.onDirectRequest("muenchen", nowMs: now)
                direct += 1
            }
            now += 20_000
        }
        XCTAssertEqual(30, server)   // every 20 s: back online within one refresh
        XCTAssertEqual(5, direct)
    }
}
