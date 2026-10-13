import SwiftUI

/// One-time onboarding carousel shown after consent, before the app. Persisted via
/// `ConsentStore.hasSeenTutorial`. Port of `OnboardingScreen.kt`.
struct OnboardingScreen: View {
    @EnvironmentObject private var loc: LocalizationManager
    let onFinish: () -> Void

    private struct Page { let icon: String; let title: String; let body: String }
    private var pages: [Page] {
        [
            Page(icon: "tram.fill", title: "ob1_title", body: "ob1_body"),
            Page(icon: "hand.tap.fill", title: "ob2_title", body: "ob2_body"),
            Page(icon: "list.bullet", title: "ob3_title", body: "ob3_body")
        ]
    }

    @State private var index = 0

    var body: some View {
        VStack {
            HStack {
                Spacer()
                Button(loc.t("onboarding_skip")) { onFinish() }
            }

            TabView(selection: $index) {
                ForEach(Array(pages.enumerated()), id: \.offset) { i, page in
                    VStack {
                        Spacer()
                        ZStack {
                            Circle().fill(Color.accentColor.opacity(0.15)).frame(width: 120, height: 120)
                            Image(systemName: page.icon)
                                .font(.system(size: 56)).foregroundStyle(Color.accentColor)
                        }
                        Text(loc.t(page.title))
                            .font(.title2.bold())
                            .multilineTextAlignment(.center)
                            .padding(.top, 32)
                        Text(loc.t(page.body))
                            .font(.body).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.top, 12)
                            .padding(.horizontal, 8)
                        Spacer()
                    }
                    .padding(.horizontal, 8)
                    .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))

            Button {
                if index == pages.count - 1 { onFinish() }
                else { withAnimation { index += 1 } }
            } label: {
                Text(loc.t(index == pages.count - 1 ? "onboarding_start" : "onboarding_next"))
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(24)
    }
}
