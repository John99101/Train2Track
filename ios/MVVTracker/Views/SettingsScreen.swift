import SwiftUI
import FirebaseAuth

/// Exposes the GDPR rights a user can exercise in-app: withdraw consent per-purpose
/// (Art. 7(3); the advertising choice by reopening Google's consent dialog), delete my data on the server and the device (Art. 17), view the
/// privacy policy (Art. 13/14) and legal notice, and pick the app language. It also
/// shows the random identifier (to name it in a request by mail) and leads to the
/// data sources, the source code and the page for reporting unlawful content.
/// Port of `SettingsScreen.kt`.
struct SettingsScreen: View {
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var consent: ConsentStore
    @ObservedObject private var ads = AdsManager.shared

    @State private var showDeleteConfirm = false

    /// "Delete my data" progress, like Android's `ConsentViewModel.DeleteState`.
    private enum DeleteState { case idle, running, done, failed }
    @State private var deleteState: DeleteState = .idle
    @AppStorage("theme_mode") private var themeRaw = ThemeMode.system.rawValue

    /// The random identifier of this installation (privacy policy, section 4), nil
    /// while the app is not signed in. It changes with "Delete my data".
    @State private var randomId: String? = Auth.auth().currentUser?.uid
    @State private var randomIdCopied = false
    @State private var authHandle: AuthStateDidChangeListenerHandle?

    /// Whether the app is currently dark, given the stored override.
    private var effectiveDark: Bool {
        switch ThemeMode(rawValue: themeRaw) ?? .system {
        case .light: return false
        case .dark: return true
        case .system: return UITraitCollection.current.userInterfaceStyle == .dark
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(loc.t("settings_intro"))
                        .font(.footnote).foregroundStyle(.secondary)
                }

                Section(loc.t("settings_language")) {
                    Picker(loc.t("settings_language"), selection: languageBinding) {
                        Text(loc.t("lang_system")).tag("")
                        Text("English").tag("en")
                        Text("Deutsch").tag("de")
                    }
                }

                Section(loc.t("settings_theme")) {
                    HStack(spacing: 10) {
                        themeButton(loc.t("theme_light"), dark: false)
                        themeButton(loc.t("theme_dark"), dark: true)
                    }
                }

                Section {
                    Toggle(loc.t("settings_location"), isOn: $consent.preciseLocationConsent)
                }
                // Without this the toggles inherit the app's `.primary` tint (white
                // in dark mode) and vanish when on; a green "on" state reads in both.
                .tint(.green)

                // The advertising choice is made in Google's consent dialog; this
                // reopens it (only where that dialog applies).
                if ads.hasPrivacyOptions {
                    Section {
                        Button(loc.t("settings_ad_privacy")) { ads.showPrivacyOptions() }
                    }
                }

                Section {
                    NavigationLink(loc.t("settings_view_policy")) { PrivacyPolicyScreen() }
                    NavigationLink(loc.t("settings_view_legal")) { LegalNoticeScreen() }
                    // Where the data comes from, and the app's own source code.
                    NavigationLink {
                        AboutScreen()
                    } label: {
                        Label(loc.t("settings_about"), systemImage: "info.circle")
                    }
                    // Reporting unlawful content: the page explains it and offers a prepared mail.
                    Link(loc.t("settings_report_content"), destination: AppInfo.reportContentURL)
                }

                // The operator knows nothing else about a user, so a request by
                // mail has to name this identifier.
                if let randomId {
                    Section {
                        HStack {
                            Text(randomId)
                                .font(.footnote.monospaced())
                                .textSelection(.enabled)
                            Spacer()
                            Button {
                                UIPasteboard.general.string = randomId
                                randomIdCopied = true
                            } label: {
                                Image(systemName: "doc.on.doc")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(loc.t("settings_random_id_copy"))
                        }
                    } header: {
                        Text(loc.t("settings_random_id"))
                    } footer: {
                        Text(loc.t(randomIdCopied ? "settings_random_id_copied" : "settings_random_id_desc"))
                    }
                }

                Section {
                    Button(role: .destructive) { showDeleteConfirm = true } label: {
                        HStack {
                            Text(loc.t("settings_delete")).frame(maxWidth: .infinity)
                            if deleteState == .running { ProgressView() }
                        }
                    }
                    .disabled(deleteState == .running)
                    if deleteState == .done {
                        Text(loc.t("settings_delete_done")).font(.footnote)
                    } else if deleteState == .failed {
                        Text(loc.t("settings_delete_failed")).font(.footnote).foregroundStyle(.red)
                    }
                } footer: {
                    Text(loc.t("settings_delete_desc"))
                }

                // Which version is installed: helps when someone reports a problem.
                Section {
                    Text(loc.t("settings_version", AppInfo.versionName, AppInfo.buildNumber))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(loc.t("settings_title"))
            .onAppear {
                guard authHandle == nil else { return }
                authHandle = Auth.auth().addStateDidChangeListener { _, user in
                    if randomId != user?.uid { randomIdCopied = false }
                    randomId = user?.uid
                }
            }
            .onDisappear {
                if let authHandle { Auth.auth().removeStateDidChangeListener(authHandle) }
                authHandle = nil
            }
            .confirmationDialog(loc.t("settings_delete"), isPresented: $showDeleteConfirm,
                                titleVisibility: .visible) {
                Button(loc.t("settings_delete"), role: .destructive) { deleteAllMyData() }
            } message: {
                Text(loc.t("settings_delete_desc"))
            }
        }
    }

    /// On the server and on the device, see `AccountRepository`.
    private func deleteAllMyData() {
        guard deleteState != .running else { return }
        deleteState = .running
        Task {
            let ok = await AccountRepository.shared.deleteAllMyData()
            deleteState = ok ? .done : .failed
        }
    }

    private var languageBinding: Binding<String> {
        Binding(get: { loc.languageCode }, set: { loc.setLanguage($0) })
    }

    /// A Light/Dark choice button; the one matching the current appearance is filled.
    private func themeButton(_ title: String, dark: Bool) -> some View {
        let selected = (effectiveDark == dark)
        return Button {
            themeRaw = (dark ? ThemeMode.dark : ThemeMode.light).rawValue
        } label: {
            Text(title).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        // Selected: filled with `.primary` (black↔white) + an inverting label, so
        // the text never matches its background. Unselected: grey fill, primary text.
        .tint(selected ? Color.primary : Color(.systemGray4))
        .foregroundStyle(selected ? Color(.systemBackground) : Color.primary)
    }
}
