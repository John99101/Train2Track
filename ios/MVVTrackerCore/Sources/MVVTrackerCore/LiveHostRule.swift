import Foundation

/// Which CDN addresses the apps take for the live data (Firestore config/app, text
/// field liveCdn). Only https addresses under example.invalid (the CDN's name for the
/// app is api.example.invalid), so a typing error in the console cannot send the apps
/// anywhere else, nor to a provider the privacy policy does not name.
/// Mirror of Android's `LiveHost.parse`.
public enum LiveHostRule {
    public static let field = "liveCdn"
    /// How long the CDN is left alone after it failed.
    public static let pause: TimeInterval = 10 * 60

    public static func cdnBase(_ value: String?) -> URL? {
        guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty,
              let url = URL(string: text), url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased() else { return nil }
        let allowed = host == "example.invalid" || host.hasSuffix(".example.invalid")
        return allowed ? url : nil
    }

    /// Only the live data goes through the CDN; line routes and track shapes do not.
    public static func isLivePath(_ path: String) -> Bool {
        path.hasPrefix("/api/live/")
    }
}
