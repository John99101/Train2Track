import SwiftUI
import Combine
import GoogleMobileAds

/// Loads a single Google **native** ad (test unit) and publishes it. Renders
/// nothing on failure so the slot never blocks the UI. Mirrors `NativeAdSlot.kt`.
final class NativeAdLoader: NSObject, ObservableObject, NativeAdLoaderDelegate {
    /// Singleton so the AdLoader (and its in-flight request) outlives the Feed's
    /// frequent re-renders — otherwise the loader is deallocated before the ad
    /// response arrives and no delegate callback ever fires.
    static let shared = NativeAdLoader()

    @Published var nativeAd: NativeAd?
    private var loader: AdLoader?

    /// No flags of our own: the ads software reads the choice from Google's
    /// consent dialog by itself. Only call this when `AdsManager.canRequestAds`.
    func load() {
        guard loader == nil else { return }
        let loader = AdLoader(
            adUnitID: AdsManager.nativeAdUnit,
            rootViewController: nil,
            adTypes: [.native],
            options: nil
        )
        loader.delegate = self
        self.loader = loader
        loader.load(Request())
    }

    /// Drop the current ad and load a fresh one (used after a dismiss cooldown).
    func reload() {
        clear()
        load()
    }

    /// Drop the current ad without loading a new one (ads are not allowed any more).
    func clear() {
        nativeAd = nil
        loader = nil
    }

    // GMA delivers these on the main thread.
    func adLoader(_ adLoader: AdLoader, didReceive nativeAd: NativeAd) {
        self.nativeAd = nativeAd
    }

    func adLoader(_ adLoader: AdLoader, didFailToReceiveAdWithError error: Error) {
        // Leave the slot empty — never blocks the UI (as on Android). Clear the
        // loader so a later appearance can retry (test ads sometimes no-fill).
        NSLog("[Ads] native ad failed: %@", error.localizedDescription)
        self.loader = nil
    }
}

/// A real AdMob native ad rendered inside our own card, dismissible with an "X"
/// that appears after 15 s (a countdown ring shows until then). Self-managing:
/// after a dismiss it hides, then loads a fresh ad one minute later —
/// instead of vanishing for the session. Nothing is requested before the user
/// answered Google's consent dialog (see `AdsManager`). Port of `NativeAdSlot.kt`.
struct NativeAdSlot: View {
    @EnvironmentObject private var loc: LocalizationManager
    @ObservedObject private var ads = AdsManager.shared
    @ObservedObject private var loader = NativeAdLoader.shared
    @State private var elapsed = 0.0
    @State private var hidden = false
    private let skipAfter = 15.0
    // After a dismiss, a fresh ad pops up one minute later (the owner's choice).
    private let reloadAfter = 60.0
    private let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    private var canSkip: Bool { elapsed >= skipAfter }

    var body: some View {
        content
        .onAppear {
            if ads.canRequestAds { loader.load() }
        }
        .onReceive(timer) { _ in
            if elapsed < skipAfter { elapsed = min(elapsed + 0.1, skipAfter) }
        }
        // Like Android: ads start once the choice is made, and a changed choice
        // replaces the ad that is shown. One handler for both values (they change
        // together when the dialog closes), so only one ad is requested.
        .onChange(of: AdChoice(allowed: ads.canRequestAds, version: ads.choiceVersion)) { _, choice in
            elapsed = 0
            if choice.allowed { loader.reload() } else { loader.clear() }
        }
        .onChange(of: hidden) { _, isHidden in
            guard isHidden else { return }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(reloadAfter * 1_000_000_000))
                elapsed = 0
                if ads.canRequestAds { loader.reload() }
                hidden = false
            }
        }
    }

    /// Always renders a present view (a zero-height clear placeholder when there
    /// is no ad) so the enclosing `onAppear` fires and the ad load is triggered —
    /// an empty `Group` would not appear, so the load would never run.
    @ViewBuilder private var content: some View {
        if !hidden, let ad = loader.nativeAd {
            NativeAdContainer(nativeAd: ad, openText: loc.t("ad_open"))
                .frame(height: 140)
                .padding(10)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                .overlay(alignment: .topLeading) {
                    Text(loc.t("ad_label"))
                        .font(.caption2).foregroundStyle(.secondary)
                        .padding(6)
                }
                .overlay(alignment: .topTrailing) { skipControl }
        } else {
            Color.clear.frame(height: 0)
        }
    }

    @ViewBuilder private var skipControl: some View {
        if canSkip {
            Button(action: { hidden = true }) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 24, height: 24)
                    .background(Color.black.opacity(0.6), in: Circle())
            }
            .accessibilityLabel(loc.t("ad_skip"))
            .padding(6)
        } else {
            ProgressView(value: elapsed, total: skipAfter)
                .progressViewStyle(.circular)
                .frame(width: 20, height: 20)
                .padding(8)
        }
    }
}

/// Whether ads may be requested, and which answer of the consent dialog that is.
private struct AdChoice: Equatable {
    let allowed: Bool
    let version: Int
}

/// Hosts a `NativeAdView` with a media view + headline / body / call-to-action,
/// bound to the ad. Using `MediaView` for the main asset satisfies the AdMob
/// native-ad policy (the validator flags an ImageView used in its place).
private struct NativeAdContainer: UIViewRepresentable {
    let nativeAd: NativeAd
    /// The button text when the ad brings none ("Open" / "Öffnen").
    let openText: String

    func makeUIView(context: Context) -> NativeAdView {
        let adView = NativeAdView()

        let media = MediaView()
        media.contentMode = .scaleAspectFill
        media.clipsToBounds = true
        media.layer.cornerRadius = 8
        media.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let headline = UILabel()
        headline.font = .preferredFont(forTextStyle: .subheadline)
        headline.adjustsFontForContentSizeCategory = true
        headline.numberOfLines = 1

        let body = UILabel()
        body.font = .preferredFont(forTextStyle: .caption1)
        body.textColor = .secondaryLabel
        body.numberOfLines = 2

        let cta = UILabel()
        cta.font = .preferredFont(forTextStyle: .caption1)
        cta.textColor = .white
        cta.textAlignment = .center
        cta.backgroundColor = .tintColor
        cta.layer.cornerRadius = 6
        cta.layer.masksToBounds = true
        cta.setContentHuggingPriority(.required, for: .horizontal)
        cta.setContentCompressionResistancePriority(.required, for: .horizontal)

        let textCol = UIStackView(arrangedSubviews: [headline, body])
        textCol.axis = .vertical
        textCol.spacing = 2

        let bottomRow = UIStackView(arrangedSubviews: [textCol, cta])
        bottomRow.axis = .horizontal
        bottomRow.spacing = 10
        bottomRow.alignment = .center

        let root = UIStackView(arrangedSubviews: [media, bottomRow])
        root.axis = .horizontal
        root.spacing = 10
        root.alignment = .center
        root.translatesAutoresizingMaskIntoConstraints = false
        root.isLayoutMarginsRelativeArrangement = true
        root.layoutMargins = UIEdgeInsets(top: 4, left: 18, bottom: 4, right: 30)

        adView.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: adView.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: adView.trailingAnchor),
            root.topAnchor.constraint(equalTo: adView.topAnchor),
            root.bottomAnchor.constraint(equalTo: adView.bottomAnchor),
            // AdMob requires a video MediaView of at least 120×120 points.
            media.widthAnchor.constraint(equalToConstant: 120),
            media.heightAnchor.constraint(equalToConstant: 120),
            cta.widthAnchor.constraint(greaterThanOrEqualToConstant: 64),
            cta.heightAnchor.constraint(equalToConstant: 30)
        ])

        adView.mediaView = media
        adView.headlineView = headline
        adView.bodyView = body
        adView.callToActionView = cta
        return adView
    }

    func updateUIView(_ adView: NativeAdView, context: Context) {
        (adView.headlineView as? UILabel)?.text = nativeAd.headline
        (adView.bodyView as? UILabel)?.text = nativeAd.body
        (adView.callToActionView as? UILabel)?.text = "  " + (nativeAd.callToAction ?? openText) + "  "
        (adView.mediaView)?.mediaContent = nativeAd.mediaContent
        adView.nativeAd = nativeAd
    }
}
