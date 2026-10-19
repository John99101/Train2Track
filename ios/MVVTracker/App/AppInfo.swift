import Foundation

/// Who the app is towards the outside: its addresses and how it names itself.
/// Port of Android's `util/AppInfo.kt`.
enum AppInfo {
    static let website = "https://example.invalid"
    static let contactMail = "contact@example.invalid"

    /// The public source code of the apps and the server.
    static let sourceCodeURL = URL(string: "https://github.com/John99101/Train2Track")!

    /// Where unlawful content can be reported (Article 16 Digital Services Act).
    static let reportContentURL = URL(string: "https://example.invalid/report")!

    /// The app's page in the App Store. Fill in the Apple ID of the app (App Store
    /// Connect, App Information) once it is known: https://apps.apple.com/app/id<number>.
    static let appStoreURL: URL? = nil

    /// Where "Update now" leads: the App Store page, until then the website (it
    /// links to the stores).
    static var updateURL: URL { appStoreURL ?? URL(string: website)! }

    /// The installed version and build, e.g. "0.5.1" and "14".
    static let versionName = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    static let buildNumber = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"

    /// Sent with every request to the timetable service (Transitous), which asks
    /// apps to say who they are and how the operator can be reached: name,
    /// version, website and contact.
    static let userAgent: String = {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        return "Train2Track/\(version) (iOS; +\(website); \(contactMail))"
    }()
}
