import SwiftUI
import FirebaseFirestore

/// Light/dark preference, stored under the "theme_mode" AppStorage key (parallels
/// Android's ThemeHelper). `.system` follows the device.
enum ThemeMode: String {
    case system, light, dark

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

/// First-run flow gate: consent (once) → onboarding (once) → the tabbed app.
/// Mirrors Android's MainActivity gating on `hasSeenConsentScreen` /
/// `hasSeenTutorial`.
struct RootFlow: View {
    @EnvironmentObject private var consent: ConsentStore
    @ObservedObject private var gate = UpdateGate.shared

    var body: some View {
        Group {
            if gate.updateRequired {
                UpdateRequiredView()                // a blocked version: only the way to update
            } else if !consent.hasSeenConsent {
                ConsentScreen(onFinished: {})       // saveConsent flips the flag → re-renders
            } else if !consent.hasSeenTutorial {
                OnboardingScreen(onFinish: { consent.setTutorialSeen() })
            } else {
                MainTabs()
            }
        }
        .onAppear { gate.check() }
    }
}

/// The oldest build that may still be used. The owner sets it in the Firebase
/// console (Firestore, document config/app, number field iosMinBuild) when a
/// released version has a serious fault; that version then asks for an update.
/// No document, no field or no connection: nothing is blocked. Checked once per
/// start. Port of Android's `MinVersionCheck`.
@MainActor
final class UpdateGate: ObservableObject {
    static let shared = UpdateGate()
    @Published private(set) var updateRequired = false
    private var checked = false

    func check() {
        guard !checked else { return }
        checked = true
        Firestore.firestore().collection("config").document("app").getDocument { [weak self] snap, _ in
            let min = (snap?.get("iosMinBuild") as? NSNumber)?.intValue ?? 0
            let build = Int(AppInfo.buildNumber) ?? Int.max
            Task { @MainActor in self?.updateRequired = build < min }
        }
    }
}

/// Shown instead of the app when this version may no longer be used. Port of
/// Android's `UpdateRequiredScreen`.
struct UpdateRequiredView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 16) {
            Text(loc.t("update_title"))
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(loc.t("update_body"))
                .multilineTextAlignment(.center)
            Button(loc.t("update_button")) { openURL(AppInfo.updateURL) }
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
        }
        .padding(32)
    }
}

/// The app's tab shell: Feed and Map (Report opens by tapping a train), the hidden
/// Admin tab (only when the ID token carries the `admin` claim, like Android), plus
/// Settings. Also owns the app-wide location→city wiring so a manual choice made
/// in the Feed and the location-based suggestion stay consistent across tabs, and
/// the "app is open" heartbeat for the active users count.
struct MainTabs: View {
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var consent: ConsentStore
    @EnvironmentObject private var location: LocationService
    @EnvironmentObject private var citySelection: CitySelection
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var admin = AdminViewModel.shared

    init() {
        // Keep the tab bar always readable: by default it is translucent and the
        // icons can wash out / vanish over the map. Use an OPAQUE background with
        // explicit item colours so the three tabs are always clearly visible.
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        let item = UITabBarItemAppearance()
        item.normal.iconColor = .secondaryLabel
        item.normal.titleTextAttributes = [.foregroundColor: UIColor.secondaryLabel]
        item.selected.iconColor = .label
        item.selected.titleTextAttributes = [.foregroundColor: UIColor.label]
        appearance.stackedLayoutAppearance = item
        appearance.inlineLayoutAppearance = item
        appearance.compactInlineLayoutAppearance = item
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }

    var body: some View {
        TabView {
            FeedScreen()
                .tabItem { Label(loc.t("nav_feed"), systemImage: "list.bullet") }
            MapScreen()
                .tabItem { Label(loc.t("nav_map"), systemImage: "map") }
            if admin.isAdmin {
                AdminScreen(admin: admin)
                    .tabItem { Label(loc.t("nav_admin"), systemImage: "checkmark.shield") }
            }
            SettingsScreen()
                .tabItem { Label(loc.t("nav_settings"), systemImage: "gearshape") }
        }
        .tint(.primary)   // selected tab stays high-contrast in light & dark
        .onAppear {
            // Google's ad consent dialog (and after it the system tracking
            // question) only comes up here, in the app itself, never on top of the
            // first start screens. Until it is answered no ad is loaded.
            AdsManager.shared.gatherConsent()
            location.requestIfConsented(consent.preciseLocationConsent)
            admin.refreshRole()
            if scenePhase == .active { PresenceRepository.shared.start() }
        }
        // Heartbeat only while the app is in the foreground (every 4 minutes).
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { PresenceRepository.shared.start() } else { PresenceRepository.shared.stop() }
        }
        // Re-request if the user turns precise-location on later in Settings.
        .onChange(of: consent.preciseLocationConsent) { _, granted in
            location.requestIfConsented(granted)
        }
        // A location fix preselects the nearest city — unless the user already
        // picked one manually (then it's left untouched).
        .onChange(of: location.nearestCity) { _, nearest in
            if let nearest { citySelection.suggestFromLocation(nearest) }
        }
    }
}
