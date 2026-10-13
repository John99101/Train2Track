import Foundation
import FirebaseAuth
import FirebaseFirestore

/// Devices with the app open in the last `windowMinutes` (server count, every 5 min).
struct LiveStats: Equatable {
    let activeUsers: Int
    let android: Int
    let ios: Int
    let windowMinutes: Int
    let updatedAt: Date?
}

/// Active users: while the app is in the foreground it writes presence/{uid} =
/// { lastSeen, platform } every few minutes (nothing else: the rules allow exactly
/// these two fields; the server deletes it after an hour of inactivity). The
/// liveStats Cloud Function counts these into stats/live. Port of Android's
/// `PresenceRepository`.
@MainActor
final class PresenceRepository {
    static let shared = PresenceRepository()

    private static let heartbeatSeconds: UInt64 = 4 * 60   // the server counts the last 10 minutes
    private var loop: Task<Void, Never>?

    /// Starts the heartbeat (call when the app becomes active).
    func start() {
        guard loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.beat()
                try? await Task.sleep(nanoseconds: Self.heartbeatSeconds * 1_000_000_000)
            }
        }
    }

    /// Stops the heartbeat (call when the app leaves the foreground).
    func stop() {
        loop?.cancel()
        loop = nil
    }

    private func beat() async {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        // Offline or not deployed yet: the count is best effort.
        try? await Firestore.firestore().collection("presence").document(uid)
            .setData(["lastSeen": FieldValue.serverTimestamp(), "platform": "ios"])
    }

    /// The server's active users count (stats/live), nil until it exists.
    func observeLiveStats(_ onChange: @escaping (LiveStats?) -> Void) -> ListenerRegistration {
        Firestore.firestore().collection("stats").document("live").addSnapshotListener { snap, error in
            guard let snap, error == nil, snap.exists, let d = snap.data() else { onChange(nil); return }
            func int(_ k: String) -> Int? { (d[k] as? NSNumber)?.intValue }
            onChange(LiveStats(
                activeUsers: int("activeUsers") ?? 0,
                android: int("android") ?? 0,
                ios: int("ios") ?? 0,
                windowMinutes: int("windowMinutes") ?? 10,
                updatedAt: (d["updatedAt"] as? Timestamp)?.dateValue()
            ))
        }
    }
}
