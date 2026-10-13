import Foundation
import Combine

/// GDPR consent + first-run flags, persisted in UserDefaults. Port of
/// `privacy/ConsentManager.kt`. Every optional consent defaults OFF (opt-in,
/// Art. 7). The choice about advertising is not kept here: it is made in Google's
/// consent dialog (see `AdsManager`).
@MainActor
final class ConsentStore: ObservableObject {
    static let shared = ConsentStore()

    private let d = UserDefaults.standard
    private enum Keys {
        static let seenConsent = "has_seen_consent_screen"
        static let seenTutorial = "has_seen_tutorial"
        static let location = "precise_location_consent"

        // Values of earlier versions. No longer read; only named here so that
        // "Delete my data" removes them.
        static let old = ["analytics_consent", "push_consent", "ad_personalization_consent", "pseudonymous_id"]
    }

    @Published var hasSeenConsent: Bool { didSet { d.set(hasSeenConsent, forKey: Keys.seenConsent) } }
    @Published var hasSeenTutorial: Bool { didSet { d.set(hasSeenTutorial, forKey: Keys.seenTutorial) } }
    @Published var preciseLocationConsent: Bool { didSet { d.set(preciseLocationConsent, forKey: Keys.location) } }

    private init() {
        hasSeenConsent = d.bool(forKey: Keys.seenConsent)
        hasSeenTutorial = d.bool(forKey: Keys.seenTutorial)
        preciseLocationConsent = d.bool(forKey: Keys.location)
    }

    /// First-run consent screen result.
    func saveConsent(preciseLocation: Bool) {
        preciseLocationConsent = preciseLocation
        hasSeenConsent = true
    }

    func setTutorialSeen() { hasSeenTutorial = true }

    /// "Delete my data" (local part, Android's `resetAllConsent`): withdraw every
    /// consent. The server part and the fresh anonymous identity are handled by
    /// `AccountRepository.deleteAllMyData()`, the advertising choice by `AdsManager`.
    func resetAllConsent() {
        preciseLocationConsent = false
        Keys.old.forEach { d.removeObject(forKey: $0) }
    }
}
