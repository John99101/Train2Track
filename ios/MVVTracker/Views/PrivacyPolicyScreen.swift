import SwiftUI

/// In-app privacy policy (GDPR Art. 13/14), embedded per language and rendered
/// with light structure via `LegalDocumentView`. The legal notice (Impressum) is a
/// separate document — see `LegalNoticeScreen`. Port of `PrivacyPolicyScreen.kt`.
struct PrivacyPolicyScreen: View {
    @EnvironmentObject private var loc: LocalizationManager

    var body: some View {
        LegalDocumentView(text: loc.effectiveCode == "de" ? Self.germanText : Self.englishText)
            .navigationTitle(loc.t("privacy_title"))
            .navigationBarTitleDisplayMode(.inline)
    }

    // The full text is embedded per language so it works offline. Kept in sync with
    // Android's res/raw (English) and res/raw-de (German) plain-text sources.
    private static let englishText = """
Privacy Policy
Last updated: placeholder

This text is a placeholder. The public source code does not contain the privacy policy of the Train2Track service.

1. If you publish this app
Whoever publishes or operates a version of this app is responsible for the personal data it processes. Put your own privacy policy here. It must describe exactly what your version does.

2. What this code processes
As a starting point, this is what the code in this repository does:
• each installation signs in anonymously and gets a random identifier
• reports and delay votes are stored on the server under a name calculated from the identifier and the journey, together with a note of which identifier they belong to that no app can read
• while the app is open it sends a signal with the identifier, the time and the platform, so that active installations can be counted
• the server counts the reports and votes of each identifier for a short time to limit misuse
• the location is used on the device only and is never sent to the server
• usage statistics (Android) are off until the user switches them on
• advertising is requested only after the user has answered the consent dialog
• requests for live data and map tiles reach the providers of that data with the IP address of the device

3. The original service
The privacy policy of Train2Track is available on its website.
"""

    private static let germanText = """
Datenschutzerklärung
Stand: Platzhalter

Dieser Text ist ein Platzhalter. Der öffentliche Quellcode enthält die Datenschutzerklärung des Dienstes Train2Track nicht.

1. Wenn Sie diese App veröffentlichen
Wer eine Fassung dieser App veröffentlicht oder betreibt, ist für die personenbezogenen Daten verantwortlich, die sie verarbeitet. Setzen Sie hier Ihre eigene Datenschutzerklärung ein. Sie muss genau beschreiben, was Ihre Fassung tut.

2. Was dieser Code verarbeitet
Als Ausgangspunkt: Das tut der Code in diesem Repository.
• jede Installation meldet sich anonym an und erhält eine zufällige Kennung
• Meldungen und Verspätungsstimmen werden auf dem Server unter einem Namen gespeichert, der aus der Kennung und der Fahrt berechnet wird, zusammen mit einem Vermerk, zu welcher Kennung sie gehören, den keine App lesen kann
• solange die App geöffnet ist, sendet sie ein Signal mit der Kennung, der Uhrzeit und der Plattform, damit aktive Installationen gezählt werden können
• der Server zählt die Meldungen und Stimmen jeder Kennung für kurze Zeit, um Missbrauch zu begrenzen
• der Standort wird nur auf dem Gerät verwendet und nie an den Server gesendet
• die Nutzungsstatistik (Android) ist aus, bis sie eingeschaltet wird
• Werbung wird erst angefragt, nachdem der Einwilligungsdialog beantwortet wurde
• Anfragen nach Live Daten und Kartenkacheln erreichen die Anbieter dieser Daten mit der IP Adresse des Geräts

3. Der ursprüngliche Dienst
Die Datenschutzerklärung von Train2Track steht auf dessen Website.
"""
}
