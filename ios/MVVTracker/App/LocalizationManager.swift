import Foundation
import Combine

/// Live in-app language override (System / English / Deutsch), mirroring Android's
/// `LocaleHelper` + Settings language picker. Changing the language republishes so
/// the whole SwiftUI tree re-renders in the new language without an app restart.
final class LocalizationManager: ObservableObject {
    static let shared = LocalizationManager()

    /// "" = follow the system, "en", or "de".
    @Published private(set) var languageCode: String {
        didSet { UserDefaults.standard.set(languageCode, forKey: Self.key) }
    }

    private static let key = "app_language"
    let supported = ["", "en", "de"]

    private init() {
        languageCode = UserDefaults.standard.string(forKey: Self.key) ?? ""
    }

    /// The concrete language actually used for lookups (resolves "system").
    var effectiveCode: String {
        if !languageCode.isEmpty { return languageCode }
        let preferred = Locale.preferredLanguages.first ?? "en"
        return preferred.hasPrefix("de") ? "de" : "en"
    }

    private var bundle: Bundle {
        if let path = Bundle.main.path(forResource: effectiveCode, ofType: "lproj"),
           let b = Bundle(path: path) {
            return b
        }
        return .main
    }

    func setLanguage(_ code: String) {
        guard supported.contains(code), code != languageCode else { return }
        languageCode = code
    }

    /// Localized string for a key.
    func t(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    /// Localized, printf-style formatted string (`%1$d`, `%2$@`, …).
    func t(_ key: String, _ args: CVarArg...) -> String {
        let format = bundle.localizedString(forKey: key, value: key, table: nil)
        return String(format: format, locale: Locale(identifier: effectiveCode), arguments: args)
    }
}
