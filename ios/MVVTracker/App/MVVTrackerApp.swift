import SwiftUI
import FirebaseCore
import FirebaseAuth
import FirebaseAppCheck

/// Registers Firebase at launch and establishes the anonymous identity the
/// reports backend keys off of. Uses the AppDelegate adaptor pattern from the
/// Firebase iOS quickstart so `FirebaseApp.configure()` runs before any Firebase
/// API is touched.
final class AppDelegate: NSObject, UIApplicationDelegate {
    private var authHandle: AuthStateDidChangeListenerHandle?
    private var signingIn = false

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // App Check attests requests come from the genuine app binary (App Attest
        // / DeviceCheck), blocking scripted abuse of the reports backend. The
        // provider factory MUST be set before FirebaseApp.configure().
        #if DEBUG
        AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
        #else
        AppCheck.setAppCheckProviderFactory(Train2TrackAppCheckFactory())
        #endif
        FirebaseApp.configure()
        // The listener fires right away with the current state and again whenever the
        // id goes away ("delete my data", or the server removed an id that was unused
        // for a long time), so the app always gets a fresh id by itself.
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] auth, user in
            guard let self, user == nil, !self.signingIn else { return }
            self.signingIn = true
            auth.signInAnonymously { [weak self] _, _ in self?.signingIn = false }
        }
        // Google Mobile Ads is NOT started here: it only starts once the user
        // answered Google's consent dialog (see AdsManager).
        return true
    }
}

@main
struct MVVTrackerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var loc = LocalizationManager.shared
    @StateObject private var consent = ConsentStore.shared
    @StateObject private var citySelection = CitySelection.shared
    @StateObject private var locationService = LocationService.shared
    // Light/dark override (see Settings). Reactive: writing the same AppStorage key
    // in Settings re-themes the whole app instantly.
    @AppStorage("theme_mode") private var themeRaw = ThemeMode.system.rawValue

    var body: some Scene {
        WindowGroup {
            RootFlow()
                .environmentObject(loc)
                .environmentObject(consent)
                .environmentObject(citySelection)
                .environmentObject(locationService)
                .preferredColorScheme(ThemeMode(rawValue: themeRaw)?.colorScheme)
        }
    }
}

/// Release App Check provider: App Attest on iOS 14+, DeviceCheck as fallback.
final class Train2TrackAppCheckFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> AppCheckProvider? {
        if #available(iOS 14.0, *) { return AppAttestProvider(app: app) }
        return DeviceCheckProvider(app: app)
    }
}
