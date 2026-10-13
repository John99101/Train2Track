import SwiftUI

/// In-app legal notice (Impressum) + terms of use. German law treats this as a
/// document distinct from the privacy policy, so it is a separate screen. Embedded
/// per language and rendered via `LegalDocumentView`. Mirrors Android's `LegalNoticeScreen`.
struct LegalNoticeScreen: View {
    @EnvironmentObject private var loc: LocalizationManager

    var body: some View {
        LegalDocumentView(text: loc.effectiveCode == "de" ? Self.germanText : Self.englishText)
            .navigationTitle(loc.t("legal_title"))
            .navigationBarTitleDisplayMode(.inline)
    }

    // The full text is embedded per language so it works offline. Kept in sync with
    // Android's res/raw (English) and res/raw-de (German) plain-text sources.
    private static let englishText = """
Legal Notice and Terms of Use
Last updated: placeholder

This text is a placeholder. The public source code does not contain the legal notice and the terms of use of the Train2Track service.

1. If you publish this app
Whoever publishes or operates a version of this app is its operator. Put your own legal notice and terms of use here: who you are, how you can be reached, and the rules under which your service may be used, as the law of your country requires.

2. The original service
The legal notice and the terms of use of Train2Track are available on its website.
"""

    private static let germanText = """
Impressum und Nutzungsbedingungen
Stand: Platzhalter

Dieser Text ist ein Platzhalter. Der öffentliche Quellcode enthält das Impressum und die Nutzungsbedingungen des Dienstes Train2Track nicht.

1. Wenn Sie diese App veröffentlichen
Wer eine Fassung dieser App veröffentlicht oder betreibt, ist ihr Betreiber. Setzen Sie hier Ihr eigenes Impressum und Ihre eigenen Nutzungsbedingungen ein: wer Sie sind, wie man Sie erreicht und nach welchen Regeln Ihr Dienst genutzt werden darf, so wie es das Recht Ihres Landes verlangt.

2. Der ursprüngliche Dienst
Das Impressum und die Nutzungsbedingungen von Train2Track stehen auf dessen Website.
"""
}
