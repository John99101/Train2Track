import Foundation
import FirebaseAuth
import FirebaseFirestore
import MVVTrackerCore

/// Backs the admin console: moderate reported trains (verify, dismiss, restore),
/// see how many devices are online, and read the inspection statistics.
/// Port of Android's `AdminViewModel`.
///
/// The role comes ONLY from the `admin` custom claim on the Firebase ID token
/// (set by the server for the ids in functions/lib/admins.js) — there is no login. Hiding the console
/// from riders is only the UX: every admin Cloud Function re-checks the claim.
@MainActor
final class AdminViewModel: ObservableObject {
    static let shared = AdminViewModel()

    enum StatsState: Equatable {
        case idle, loading, loaded(ControlStats), failed
    }

    @Published private(set) var isAdmin = false
    /// Every train with live reports (also dismissed / spam blocked ones), newest first.
    @Published private(set) var reportedTrips: [TripReports] = []
    @Published private(set) var liveStats: LiveStats?
    @Published private(set) var stats: StatsState = .idle
    /// Last failed action's message (shown once in the console).
    @Published private(set) var actionError: String?
    @Published private(set) var routesRebuilding = false

    private var authHandle: AuthStateDidChangeListenerHandle?
    private var observation: ReportObservation?
    private var liveListener: ListenerRegistration?
    private var forcedOnce = false

    private init() {
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] _, _ in
            Task { @MainActor in self?.refreshRole() }
        }
    }

    /// Reads the claim; once per session straight from the server, so a freshly
    /// granted claim shows up without reinstalling.
    func refreshRole() {
        guard let user = Auth.auth().currentUser else { setAdmin(false); return }
        let force = !forcedOnce
        forcedOnce = true
        user.getIDTokenResult(forcingRefresh: force) { [weak self] result, _ in
            let admin = (result?.claims["admin"] as? Bool) == true
            Task { @MainActor in self?.setAdmin(admin) }
        }
    }

    private func setAdmin(_ admin: Bool) {
        #if DEBUG
        // UI preview only (simulator): `-T2TPreviewAdmin` shows the console. Every
        // server action still requires the real admin claim and fails without it.
        isAdmin = admin || ProcessInfo.processInfo.arguments.contains("-T2TPreviewAdmin")
        #else
        isAdmin = admin
        #endif
        if isAdmin { startListening() } else { stopListening() }
    }

    private func startListening() {
        if observation == nil {
            observation = UserReportBackend.shared.observeReportedTrips { [weak self] trips in
                Task { @MainActor in
                    self?.reportedTrips = trips.values.sorted { $0.latestMs > $1.latestMs }
                }
            }
        }
        if liveListener == nil {
            liveListener = PresenceRepository.shared.observeLiveStats { [weak self] live in
                Task { @MainActor in self?.liveStats = live }
            }
        }
    }

    private func stopListening() {
        observation?.cancel(); observation = nil
        liveListener?.remove(); liveListener = nil
        reportedTrips = []
        liveStats = nil
        stats = .idle
    }

    private func adminAction(_ block: @escaping @MainActor () async throws -> Void) {
        guard isAdmin else { return }
        Task {
            actionError = nil
            do { try await block() } catch { actionError = error.localizedDescription }
        }
    }

    func moderate(_ tripId: String, _ moderation: AdminBackend.Moderation) {
        adminAction { try await AdminBackend.shared.moderateTrip(tripId, moderation) }
    }

    func loadStats() {
        adminAction { [weak self] in
            self?.stats = .loading
            do {
                let s = try await AdminBackend.shared.controlStats()
                self?.stats = .loaded(s)
            } catch {
                self?.stats = .failed
            }
        }
    }

    func rebuildRoutes() {
        adminAction { [weak self] in
            self?.routesRebuilding = true
            defer { self?.routesRebuilding = false }
            try await AdminBackend.shared.rebuildRoutes()
        }
    }
}
