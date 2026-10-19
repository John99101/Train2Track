import SwiftUI
import FirebaseCore
import FirebaseAuth
import FirebaseAppCheck
import Network

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
            guard user == nil else { return }
            self?.signIn(auth)
        }
        // A first start without internet: sign in as soon as a connection is there.
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            DispatchQueue.main.async {
                self?.retryDelay = Self.firstRetry
                self?.signIn(Auth.auth())
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "train2track.network"))
        // Google Mobile Ads is NOT started here: it only starts once the user
        // answered Google's consent dialog (see AdsManager).
        return true
    }

    /// Signs in when there is no id; when that fails (no connection, the server
    /// was busy), tries again after 15 s, then less often. Port of Android's
    /// `MvvTrackerApp.signIn`.
    private func signIn(_ auth: Auth) {
        guard auth.currentUser == nil, !signingIn else { return }
        signingIn = true
        auth.signInAnonymously { [weak self] _, error in
            guard let self else { return }
            self.signingIn = false
            guard error != nil else {
                self.retryDelay = Self.firstRetry
                return
            }
            let delay = self.retryDelay
            self.retryDelay = min(self.retryDelay * 2, Self.maxRetry)
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in self?.signIn(auth) }
        }
    }

    private let pathMonitor = NWPathMonitor()
    private var retryDelay: TimeInterval = AppDelegate.firstRetry
    private static let firstRetry: TimeInterval = 15
    private static let maxRetry: TimeInterval = 300
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
