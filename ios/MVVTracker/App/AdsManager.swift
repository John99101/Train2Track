import Foundation
import UIKit
import Combine
import GoogleMobileAds
import UserMessagingPlatform
import AppTrackingTransparency

/// The one place that decides whether the app may load advertising. Port of
/// Android's `privacy/AdsConsent.kt`.
///
/// The choice is made in Google's own consent dialog (User Messaging Platform, a
/// consent platform certified for the IAB Transparency and Consent Framework),
/// not in a switch of this app. Nothing is requested from AdMob and Google Mobile
/// Ads is not started until the user has answered that dialog. The ads software
/// reads the stored choice by itself, so an ad request needs no extra flags.
///
/// Deliberately stricter than Google's own `canRequestAds`: that is also true
/// when Google reports "no dialog required", which it does outside the EEA, UK
/// and Switzerland but also when no consent message is set up for the app in
/// AdMob. So without an answered dialog there are no ads at all.
final class AdsManager: ObservableObject {
    static let shared = AdsManager()

    /// Native ad unit (iOS). Google's public TEST unit in debug so dev builds and
    /// real test devices reliably show test creatives; the real unit in release
    /// (mirrors Android's debug/release split).
    #if DEBUG
    static let nativeAdUnit = "ca-app-pub-3940256099942544/3986624511"
    #else
    static let nativeAdUnit = "ca-app-pub-3940256099942544/3986624511"
    #endif

    /// True once the user answered Google's dialog (whatever the answer was).
    @Published private(set) var canRequestAds = false
    /// True where the user must be able to reopen the dialog (Settings shows the entry).
    @Published private(set) var hasPrivacyOptions = false
    /// Goes up whenever the user closed the dialog, so a shown ad is replaced.
    @Published private(set) var choiceVersion = 0

    private var started = false

    /// Asks Google whether a choice is still needed and shows the dialog if so.
    /// Called when the tabbed app appears (never on top of the first start
    /// screens), on every start. Call on the main thread.
    func gatherConsent() {
        ConsentInformation.shared.requestConsentInfoUpdate(with: RequestParameters()) { [weak self] error in
            DispatchQueue.main.async {
                guard let self else { return }
                guard error == nil, let root = Self.topViewController() else {
                    self.refresh()
                    return
                }
                ConsentForm.loadAndPresentIfRequired(from: root) { [weak self] _ in
                    DispatchQueue.main.async { self?.choiceMade() }
                }
            }
        }
        // A choice from an earlier start is valid right away.
        refresh()
    }

    /// Settings: lets the user change or withdraw the choice at any time.
    func showPrivacyOptions() {
        guard let root = Self.topViewController() else { return }
        ConsentForm.presentPrivacyOptionsForm(from: root) { [weak self] _ in
            DispatchQueue.main.async { self?.choiceMade() }
        }
    }

    /// "Delete my data": forgets the choice; the dialog asks again at the next start.
    func resetConsent() {
        ConsentInformation.shared.reset()
        refresh()
    }

    private func choiceMade() {
        choiceVersion += 1
        refresh()
        requestTrackingIfAllowed()
    }

    private func refresh() {
        let info = ConsentInformation.shared
        canRequestAds = info.consentStatus == .obtained
        hasPrivacyOptions = info.privacyOptionsRequirementStatus == .required
        if canRequestAds && !started {
            started = true
            MobileAds.shared.start(completionHandler: nil)
        }
    }

    /// App Tracking Transparency: the system question for the advertising
    /// identifier. It is only asked after the user agreed in Google's dialog to
    /// storing and reading information on the device (purpose 1 of the framework,
    /// which Google's dialog writes to UserDefaults); someone who declined is not
    /// asked a second question.
    private func requestTrackingIfAllowed() {
        guard canRequestAds, ATTrackingManager.trackingAuthorizationStatus == .notDetermined else { return }
        let agreed = UserDefaults.standard.string(forKey: "IABTCF_PurposeConsents")?.first == "1"
        guard agreed else { return }
        ATTrackingManager.requestTrackingAuthorization { _ in }
    }

    /// The view controller currently on top, to present Google's dialog from.
    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let window = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
