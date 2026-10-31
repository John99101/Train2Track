import Foundation
import FirebaseAuth
import FirebaseFirestore
import MVVTrackerCore

/// The app's own backend, on Firebase. Swift port of `UserReportBackend.kt`.
/// Writes each rider's train report to the `reports` Firestore collection (shared
/// with Android), records "has left" votes, and streams the reports back aggregated
/// per train with exactly Android's rules (`MVVTrackerCore.ReportAggregation`).
///
/// Every report, delay vote and log entry carries the `city` its train was seen in
/// (a city id or "other", see `reportCity` in MVVTrackerCore), and the listeners
/// load only the cities on screen instead of every report in Germany.
///
/// Clients never write `throttled`, `dismissed`, `verified` or `moderatedAt` (the
/// rules reject it; only the server sets them). The document names, field names and
/// TTL come from
/// `MVVTrackerCore.ReportKey` and MUST stay identical to Android or cross-platform
/// report sharing breaks. A report does not show who wrote it: see `ReportKey`.
final class UserReportBackend {
    static let shared = UserReportBackend()

    private let db = Firestore.firestore()
    private var reports: CollectionReference { db.collection(ReportKey.collection) }

    /// Writes this rider's entry on a train to `collection` (a report or a delay
    /// vote) together with its owner note. There is one entry per rider per train,
    /// under a name that does not show the rider (see `ReportKey`), so writing
    /// again overwrites. Port of Android's `writeEntry`.
    ///
    /// It is sent as a transaction: it either reaches the server now or fails. A
    /// plain write would be kept on the phone while offline and sent whenever the
    /// connection returns, when the report may no longer be true.
    private func writeEntry(collection: String, tripId: String, ttl: TimeInterval,
                            fields: [String: Any]) async throws {
        let uid = try await uid()
        let docId = ReportKey.hiddenDocumentId(uid: uid, tripId: tripId)
        let expiresAt = Timestamp(date: Date().addingTimeInterval(ttl))
        var entry = fields
        entry["tripId"] = tripId
        entry["uid"] = docId
        entry["createdAt"] = FieldValue.serverTimestamp()
        entry["expiresAt"] = expiresAt
        let note: [String: Any] = [
            "uid": uid,
            "collection": collection,
            "doc": docId,
            "tripId": tripId,
            "expiresAt": expiresAt
        ]
        let entryRef = db.collection(collection).document(docId)
        let noteRef = db.collection(ReportKey.ownersCollection)
            .document(ReportKey.ownerNoteId(collection: collection, documentId: docId))
        // At most 15 seconds, like Android: without a connection the transaction can
        // take much longer, and the report window would stay on "Sending".
        try await Self.withTimeLimit(Self.sendTimeout) { [db, entry, note] in
            _ = try await db.runTransaction { transaction, _ -> Any? in
                transaction.setData(entry, forDocument: entryRef)
                transaction.setData(note, forDocument: noteRef)
                return nil
            }
        }
    }

    /// Sending took too long (see `withTimeLimit`).
    struct SendTimeout: Error {}

    /// Runs `work` but gives up after `seconds` with `SendTimeout` (the work itself
    /// cannot be stopped; it may still finish later).
    private static func withTimeLimit(_ seconds: TimeInterval,
                                      _ work: @escaping () async throws -> Void) async throws {
        let once = ResumeOnce()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            Task {
                do {
                    try await work()
                    once.resume(continuation, with: .success(()))
                } catch {
                    once.resume(continuation, with: .failure(error))
                }
            }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                once.resume(continuation, with: .failure(SendTimeout()))
            }
        }
    }

    /// Ensures an anonymous identity exists and returns its uid.
    private func uid() async throws -> String {
        if let user = Auth.auth().currentUser { return user.uid }
        let result = try await Auth.auth().signInAnonymously()
        return result.user.uid
    }

    /// Writes a trip-specific report: one active report per rider per train, so
    /// reporting the same train again overwrites. Throws when it could not be sent.
    func submitReport(tripId: String, line: String, station: String, description: String, city: String) async throws {
        try await writeEntry(
            collection: ReportKey.collection, tripId: tripId,
            ttl: TimeInterval(ReportKey.reportTtlMillis) / 1000.0,
            fields: [
                "localId": UUID().uuidString,
                "line": line,
                "station": station,
                "description": String(description.prefix(500)),   // rules: at most 500 chars
                "state": Self.stateEntered,
                "city": city
            ]
        )

        // Persistent analytics log (does NOT expire, unlike `reports`): every
        // "Entered" is recorded so we can later analyse which trains are controlled
        // how often/when/where. It carries no id: the statistics need no rider.
        // Best-effort; never blocks the report. Only admins/server can read it;
        // wipe it for testing via functions/clearAnalytics.js. Not awaited: without
        // a connection Firestore keeps it and sends it later. (The completion form:
        // inside this async function the plain call would pick the awaiting overload.)
        _ = db.collection("control_log").addDocument(data: [
            "tripId": tripId,
            "line": line,
            "station": station,
            "city": city,
            "createdAt": FieldValue.serverTimestamp()
        ], completion: nil)
    }

    /// "Exited": records an EXIT vote for the train. ANY user viewing the train can
    /// report that the control has left. It overwrites this user's own doc for the
    /// trip, so a rider who entered and then exits flips their own vote; other
    /// riders' exits clear the "!" once two of them agree (see `ReportAggregation`).
    /// Throws when it could not be sent, like Android since 0.5.0.
    func endReport(tripId: String, city: String) async throws {
        try await writeEntry(
            collection: ReportKey.collection, tripId: tripId,
            ttl: TimeInterval(ReportKey.reportTtlMillis) / 1000.0,
            fields: ["state": Self.stateExited, "city": city]
        )
    }

    /// Live report state per train (see `ReportAggregation` for the rules: spam
    /// limit, admin moderation, two independent "has left" votes to clear). Also
    /// re-evaluated every minute so expired reports disappear even when nothing
    /// else changes. Empty on any error so the UI degrades gracefully. Port of
    /// Android's `observeReportedTrips`; call `cancel()` on the result to stop.
    /// `cities` = the report cities on screen (see `reportScope`); nil loads every
    /// city (only the admin console does that).
    func observeReportedTrips(cities: [String]? = nil,
                              _ onChange: @escaping ([String: TripReports]) -> Void) -> ReportObservation {
        let observation = ReportObservation()
        let emit: () -> Void = { [weak observation] in
            guard let observation else { return }
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            onChange(ReportAggregation.aggregate(observation.docs, nowMs: nowMs))
        }
        observation.registration = AuthGatedListener { [reports] in
            CityScopedListener(collection: reports, cities: cities) { documents in
                observation.docs = (documents ?? []).compactMap(Self.reportDoc)
                emit()
            }
        }
        observation.ticker = Timer.scheduledTimer(withTimeInterval: Self.reevaluateSeconds, repeats: true) { _ in emit() }
        return observation
    }

    /// One Firestore report as the aggregation needs it.
    private static func reportDoc(_ doc: QueryDocumentSnapshot) -> ReportDoc? {
        // A just written report has no server time yet: use the local estimate.
        let data = doc.data(with: .estimate)
        guard let tripId = data["tripId"] as? String else { return nil }
        func ms(_ v: Any?) -> Int64? {
            (v as? Timestamp).map { Int64($0.dateValue().timeIntervalSince1970 * 1000) }
        }
        // Normally the hidden name of the entry. An entry of an older app version
        // still holds that rider's id until the server has renamed it.
        let rider = data["uid"] as? String
        let selfUid = Auth.auth().currentUser?.uid
        let own = selfUid.map {
            doc.documentID == ReportKey.hiddenDocumentId(uid: $0, tripId: tripId) || rider == $0
        } ?? false
        return ReportDoc(
            rider: rider ?? doc.documentID,
            tripId: tripId,
            state: data["state"] as? String,
            line: data["line"] as? String,
            station: data["station"] as? String,
            createdAtMs: ms(data["createdAt"]),
            expiresAtMs: ms(data["expiresAt"]),
            throttled: data["throttled"] as? Bool == true,
            dismissed: data["dismissed"] as? Bool == true,
            verified: data["verified"] as? Bool == true,
            own: own
        )
    }

    // MARK: - Delay votes (Phase 6 / Option 2)

    private var delayVotes: CollectionReference { db.collection(Self.delayCollection) }

    /// Crowd-sourced DELAY vote, kept in a SEPARATE `delay_reports` collection so it
    /// can never influence the inspector "!" badge. One vote per rider per train,
    /// overwritten on re-vote. [confirm] = "yes, it's late", else "no, on time".
    /// Expires quickly — delays are ephemeral. Throws when it could not be sent.
    func submitDelayVote(tripId: String, confirm: Bool, city: String) async throws {
        try await writeEntry(
            collection: Self.delayCollection, tripId: tripId, ttl: Self.delayVoteTtl,
            fields: ["vote": confirm ? Self.delayConfirm : Self.delayOnTime, "city": city]
        )
    }

    /// One live delay vote: `confirm` = "it is late", false = "on time".
    struct DelayVote {
        let tripId: String
        let confirm: Bool
        let expiresAtMs: Int64?
    }

    /// The live delay votes of the report `cities` on screen (nil = every city); votes
    /// over the spam limit (flagged by the server) are left out. The caller counts
    /// them with `delayTally`, again every minute, so expired votes drop out (the
    /// query's time is fixed when it starts). Empty on any error.
    @discardableResult
    func observeDelayVotes(cities: [String]? = nil,
                           _ onChange: @escaping ([DelayVote]) -> Void) -> ListenerRegistration {
        AuthGatedListener { [delayVotes] in
            CityScopedListener(collection: delayVotes, cities: cities) { documents in
                guard let documents else { onChange([]); return }
                onChange(documents.compactMap { doc -> DelayVote? in
                    guard let tripId = doc["tripId"] as? String, doc["throttled"] as? Bool != true else { return nil }
                    let confirm: Bool
                    switch doc["vote"] as? String {
                    case Self.delayConfirm: confirm = true
                    case Self.delayOnTime: confirm = false
                    default: return nil
                    }
                    let expires = (doc["expiresAt"] as? Timestamp).map { Int64($0.dateValue().timeIntervalSince1970 * 1000) }
                    return DelayVote(tripId: tripId, confirm: confirm, expiresAtMs: expires)
                })
            }
        }
    }

    /// tripId -> (confirm, onTime) of the votes not yet expired at `nowMs`.
    static func delayTally(_ votes: [DelayVote], nowMs: Int64) -> [String: (confirm: Int, onTime: Int)] {
        var tally: [String: (confirm: Int, onTime: Int)] = [:]
        for v in votes where (v.expiresAtMs ?? .max) > nowMs {
            var t = tally[v.tripId] ?? (0, 0)
            if v.confirm { t.confirm += 1 } else { t.onTime += 1 }
            tally[v.tripId] = t
        }
        return tally
    }

    // MARK: - How full is it? (occupancy votes, from 0.6.0)

    private var occupancyVotes: CollectionReference { db.collection(Self.occupancyCollection) }

    /// This rider's level on the journey, 1 (many seats free) to 5 (very full), in the
    /// `occupancy_votes` collection. One vote per rider per journey under the hidden
    /// name, so a new tap replaces the old one; it counts for 20 minutes. Throws when
    /// it could not be sent. Port of Android's `submitOccupancyVote`.
    func submitOccupancyVote(tripId: String, level: Int, city: String) async throws {
        precondition((1...OccupancyAggregation.levels).contains(level))
        try await writeEntry(
            collection: Self.occupancyCollection, tripId: tripId, ttl: OccupancyAggregation.ttl,
            fields: ["level": level, "city": city]
        )
    }

    /// The live occupancy votes of the report `cities` on screen, each marked when it
    /// is this installation's own. The caller counts them with `OccupancyAggregation`
    /// (and again every minute, so old votes drop out). Empty on any error.
    @discardableResult
    func observeOccupancyVotes(cities: [String]? = nil,
                               _ onChange: @escaping ([OccupancyVote]) -> Void) -> ListenerRegistration {
        AuthGatedListener { [occupancyVotes] in
            CityScopedListener(collection: occupancyVotes, cities: cities) { documents in
                guard let documents else { onChange([]); return }
                let selfUid = Auth.auth().currentUser?.uid
                onChange(documents.compactMap { doc -> OccupancyVote? in
                    guard let tripId = doc["tripId"] as? String,
                          let level = (doc["level"] as? NSNumber)?.intValue else { return nil }
                    let expires = (doc["expiresAt"] as? Timestamp).map { Int64($0.dateValue().timeIntervalSince1970 * 1000) }
                    let own = selfUid.map { doc.documentID == ReportKey.hiddenDocumentId(uid: $0, tripId: tripId) } ?? false
                    return OccupancyVote(tripId: tripId, level: level, expiresAtMs: expires,
                                         throttled: doc["throttled"] as? Bool == true, own: own)
                })
            }
        }
    }

    // MARK: - Disruptions (Phase 6 / Option 2b — server-proxied)

    /// Live nationwide disruptions from the `disruptions` collection, written
    /// server-side by the refreshDisruptions Cloud Function (one 35 MB gtfs.de pull
    /// for everyone). Each device reads a few KB. Active alerts only, severe first.
    @discardableResult
    func observeDisruptions(_ onChange: @escaping ([Disruption]) -> Void) -> ListenerRegistration {
        AuthGatedListener { [db] in db.collection("disruptions").addSnapshotListener { snapshot, error in
            guard let docs = snapshot?.documents, error == nil else { onChange([]); return }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            func ms(_ v: Any?) -> Int64? { (v as? NSNumber)?.int64Value }
            let list = docs.compactMap { doc -> Disruption? in
                guard let title = doc["title"] as? String, !title.isEmpty else { return nil }
                let end = ms(doc["endEpochMillis"])
                if let end, end < now { return nil }
                return Disruption(
                    id: doc.documentID,
                    title: title,
                    description: doc["description"] as? String ?? "",
                    affected: doc["affected"] as? [String] ?? [],
                    startEpochMillis: ms(doc["startEpochMillis"]) ?? now,
                    endEpochMillis: end,
                    severe: doc["severe"] as? Bool ?? false
                )
            }.sorted { $0.severe && !$1.severe }
            onChange(list)
        } }
    }

    private static let reevaluateSeconds: TimeInterval = 60
    private static let sendTimeout: TimeInterval = 15
    private static let stateEntered = "entered"
    private static let stateExited = "exited"
    private static let delayCollection = "delay_reports"
    private static let delayConfirm = "confirm"
    private static let delayOnTime = "on_time"
    private static let delayVoteTtl: TimeInterval = 30 * 60   // 30 min
    private static let occupancyCollection = "occupancy_votes"
}

/// Resumes a continuation only once (the first of the work and the time limit).
private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func resume(_ continuation: CheckedContinuation<Void, Error>, with result: Result<Void, Error>) {
        lock.lock()
        defer { lock.unlock() }
        if done { return }
        done = true
        continuation.resume(with: result)
    }
}

/// A running report listener plus its once-a-minute re-evaluation timer.
final class ReportObservation {
    fileprivate var docs: [ReportDoc] = []
    fileprivate var registration: ListenerRegistration?
    fileprivate var ticker: Timer?

    func cancel() {
        registration?.remove(); registration = nil
        ticker?.invalidate(); ticker = nil
    }

    deinit { cancel() }
}

/// The live (not yet expired) documents of a collection, for the given report
/// cities only, or the whole collection when `cities` is nil. If the city query is
/// refused (its index is not deployed yet) it falls back to the whole collection,
/// as older app versions load it. `onDocs(nil)` = any other error. Port of
/// Android's `liveDocs`.
final class CityScopedListener: NSObject, ListenerRegistration {
    private let collection: CollectionReference
    private let cities: [String]?
    private let onDocs: ([QueryDocumentSnapshot]?) -> Void
    private var registration: ListenerRegistration?

    /// Firestore's error code for "missing or insufficient permissions".
    private static let permissionDenied = 7

    init(collection: CollectionReference, cities: [String]?, onDocs: @escaping ([QueryDocumentSnapshot]?) -> Void) {
        self.collection = collection
        self.cities = cities
        self.onDocs = onDocs
        super.init()
        listen(byCity: true)
    }

    private func listen(byCity: Bool) {
        var query: Query = collection
        let scoped = byCity && !(cities ?? []).isEmpty
        if scoped, let cities { query = query.whereField("city", in: cities) }
        registration = query
            .whereField("expiresAt", isGreaterThan: Timestamp(date: Date()))
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self else { return }
                if let error, scoped, (error as NSError).code != Self.permissionDenied {
                    self.registration?.remove()
                    self.listen(byCity: false)
                    return
                }
                self.onDocs(error == nil ? snapshot?.documents : nil)
            }
    }

    func remove() {
        registration?.remove()
        registration = nil
    }
}

/// Attaches a Firestore listener only while someone is signed in (the rules require
/// it) and re-attaches when the identity changes: on the very first launch the
/// anonymous sign-in may finish after the screen started listening, and "delete my
/// data" swaps the anonymous account. A listener that failed for lack of auth would
/// otherwise never recover.
final class AuthGatedListener: NSObject, ListenerRegistration {
    private let attach: () -> ListenerRegistration
    private var authHandle: AuthStateDidChangeListenerHandle?
    private var registration: ListenerRegistration?
    private var uid: String?

    init(_ attach: @escaping () -> ListenerRegistration) {
        self.attach = attach
        super.init()
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            guard let self else { return }
            if user?.uid == self.uid, self.registration != nil { return }
            self.registration?.remove()
            self.registration = nil
            self.uid = user?.uid
            if user != nil { self.registration = self.attach() }
        }
    }

    func remove() {
        registration?.remove()
        registration = nil
        if let authHandle { Auth.auth().removeStateDidChangeListener(authHandle) }
        authHandle = nil
    }
}
