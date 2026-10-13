import Foundation

/// One document of the shared `reports` collection, as far as the rules need it.
/// Mirror of Android's `ReportDoc` (`data/repository/ReportAggregation.kt`).
public struct ReportDoc: Equatable, Sendable {
    /// Tells the riders on one train apart: a different value per rider. It is the
    /// hidden name of the entry (see `ReportKey`), not the rider's id.
    public let rider: String?
    public let tripId: String
    public let state: String?          // "entered" | "exited" (missing = legacy entry)
    public let line: String?
    public let station: String?
    public let createdAtMs: Int64?
    public let expiresAtMs: Int64?
    public let throttled: Bool         // over the spam limit (set by the server)
    public let dismissed: Bool         // hidden by an admin (set by the server)
    public let verified: Bool          // checked by an admin (set by the server)
    /// This device's own entry.
    public let own: Bool

    public init(rider: String?, tripId: String, state: String?, line: String?, station: String?,
                createdAtMs: Int64?, expiresAtMs: Int64?,
                throttled: Bool = false, dismissed: Bool = false, verified: Bool = false,
                own: Bool = false) {
        self.rider = rider; self.tripId = tripId; self.state = state
        self.line = line; self.station = station
        self.createdAtMs = createdAtMs; self.expiresAtMs = expiresAtMs
        self.throttled = throttled; self.dismissed = dismissed; self.verified = verified
        self.own = own
    }
}

/// The report state of one train, after the rules below. Mirror of `TripReports`.
public struct TripReports: Equatable, Sendable {
    public let tripId: String
    public let line: String?
    public let station: String?
    /// Riders whose "on board" report counts.
    public let onBoard: Int
    /// "Has left" votes newer than the latest "on board" report.
    public let exits: Int
    /// What the "!" shows: `onBoard`, or 0 once the control is confirmed gone.
    public let flagged: Int
    public let verified: Bool
    public let dismissed: Int
    public let throttled: Int
    public let latestMs: Int64
}

/// The rules that turn the raw reports of a train into its "!" state. Exact port of
/// Android's `ReportAggregation` so both apps always show the same "!":
///
///  - Expired reports, reports over the spam limit and reports an admin dismissed
///    do not count.
///  - A rider who reported "on board" and then "has left" flips their own report,
///    so their entry simply stops counting.
///  - "Has left" from OTHER riders clears the train only once `exitQuorum`
///    different riders said so after the latest "on board" report.
///  - The rider who voted "has left" no longer sees the "!" themselves right away.
public enum ReportAggregation {
    public static let stateExited = "exited"
    public static let exitQuorum = 2

    public static func aggregate(_ docs: [ReportDoc], nowMs: Int64) -> [String: TripReports] {
        Dictionary(grouping: docs, by: \.tripId).mapValues { all in
            forTrip(all[0].tripId, all, nowMs: nowMs)
        }
    }

    private static func forTrip(_ tripId: String, _ all: [ReportDoc], nowMs: Int64) -> TripReports {
        let live = all.filter { $0.expiresAtMs == nil || $0.expiresAtMs! > nowMs }
        let valid = live.filter { !$0.dismissed && !$0.throttled }
        let entries = valid.filter { $0.state != stateExited }
        let exits = valid.filter { $0.state == stateExited }
        let latestEntry = entries.map { $0.createdAtMs ?? nowMs }.max()
        let freshExits: [ReportDoc]
        if let latestEntry {
            freshExits = exits.filter { ($0.createdAtMs ?? nowMs) > latestEntry }
        } else {
            freshExits = []
        }
        let selfLeft = freshExits.contains { $0.own }
        // Kotlin's distinct() counts a missing value as one value too, like a Set of optionals.
        let distinctExitRiders = Set(freshExits.map(\.rider)).count
        let flagged: Int
        if entries.isEmpty {
            flagged = 0
        } else if distinctExitRiders >= exitQuorum {
            flagged = 0
        } else if selfLeft {
            flagged = 0
        } else {
            flagged = entries.count
        }
        // maxByOrNull keeps the FIRST of equal maxima; max(by:) keeps the last, so
        // scan by hand to pick the same document as Android.
        let pool = entries.isEmpty ? live : entries
        var newest: ReportDoc?
        for doc in pool where newest == nil || (doc.createdAtMs ?? 0) > (newest!.createdAtMs ?? 0) {
            newest = doc
        }
        return TripReports(
            tripId: tripId,
            line: newest?.line.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 },
            station: newest?.station.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 },
            onBoard: entries.count,
            exits: freshExits.count,
            flagged: flagged,
            verified: valid.contains { $0.verified },
            dismissed: live.filter(\.dismissed).count,
            throttled: live.filter { $0.throttled && !$0.dismissed }.count,
            latestMs: live.map { $0.createdAtMs ?? 0 }.max() ?? 0
        )
    }
}
