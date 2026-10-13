import Foundation
import FirebaseAuth
import FirebaseFunctions

/// "Delete my data" (Art. 17 GDPR), as promised in the app and on the data
/// deletion page. Port of Android's `AccountRepository`:
///  1. the `deleteMyData` Cloud Function erases everything on the server tied to
///     this device's pseudonymous id (reports, delay votes, presence) and the
///     anonymous account itself; the inspection statistics only lose the link;
///  2. local data goes: this device's reports, seen trains, filters, every consent
///     (the advertising choice is forgotten, so Google's dialog asks again at the
///     next start);
///  3. the app signs in with a fresh anonymous id, so nothing new links to the old.
///
/// If the server step fails the old id is kept, so the user can simply retry
/// (with a new id the old data could no longer be found from the app).
@MainActor
final class AccountRepository {
    static let shared = AccountRepository()

    private lazy var functions = Functions.functions(region: ReportKeyRegion.value)

    /// Returns true when the server side deletion succeeded.
    func deleteAllMyData() async -> Bool {
        var serverOk = true
        if Auth.auth().currentUser != nil {
            do {
                _ = try await functions.httpsCallable("deleteMyData").call()
            } catch {
                serverOk = false
            }
        }
        OwnReportsStore.shared.clear()
        SeenTrainsStore.shared.clear()
        FilterStore.shared.clear()
        ConsentStore.shared.resetAllConsent()
        AdsManager.shared.resetConsent()
        // Signing out is enough: the app signs in with a fresh id by itself (see
        // the auth listener in AppDelegate).
        if serverOk { try? Auth.auth().signOut() }
        return serverOk
    }
}
