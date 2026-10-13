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

    /// Sent with every request to the timetable service (Transitous), which asks
    /// apps to say who they are and how the operator can be reached: name,
    /// version, website and contact.
    static let userAgent: String = {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        return "Train2Track/\(version) (iOS; +\(website); \(contactMail))"
    }()
}
