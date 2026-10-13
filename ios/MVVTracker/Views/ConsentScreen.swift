import SwiftUI

/// First-run consent. Every toggle defaults OFF (opt-in, GDPR Art. 7). Shown once;
/// afterwards `ConsentStore.hasSeenConsent` suppresses it. Port of `ConsentScreen.kt`.
/// Advertising is not asked here: Google's consent dialog follows after the first
/// start screens (see `AdsManager`).
struct ConsentScreen: View {
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var consent: ConsentStore
    let onFinished: () -> Void

    @State private var locationOptIn = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    Circle().fill(Color.accentColor.opacity(0.15)).frame(width: 72, height: 72)
                    Image(systemName: "tram.fill")
                        .font(.system(size: 34)).foregroundStyle(Color.accentColor)
                }
                .padding(.bottom, 20)

                Text(loc.t("consent_title"))
                    .font(.title.bold())
                Text(loc.t("consent_intro"))
                    .font(.callout).foregroundStyle(.secondary)
                    .padding(.top, 8)
                    .padding(.bottom, 24)

                consentCard("location.fill", "consent_location_title", "consent_location_desc", $locationOptIn)

                Button {
                    consent.saveConsent(preciseLocation: locationOptIn)
                    onFinished()
                } label: {
                    Text(loc.t("consent_continue"))
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 28)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 32)
        }
    }

    private func consentCard(_ icon: String, _ titleKey: String, _ descKey: String,
                             _ binding: Binding<Bool>) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 22)).foregroundStyle(Color.accentColor)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(loc.t(titleKey)).font(.subheadline.weight(.semibold))
                Text(loc.t(descKey)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 12)
            Toggle("", isOn: binding).labelsHidden()
        }
        .padding(16)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .padding(.vertical, 6)
    }
}
