import SwiftUI

/// Where the app's data comes from and under which licences (the credit the data
/// sources ask for, in a visible place), and the link to the app's own source
/// code. Reached from the Settings. Port of Android's `AboutScreen.kt`; the same
/// list is on the website and in the terms of use.
struct AboutScreen: View {
    @EnvironmentObject private var loc: LocalizationManager

    private struct SourceLink: Identifiable {
        let label: String
        let url: String
        var id: String { url }
    }

    var body: some View {
        Form {
            Section {
                Text(loc.t("about_intro"))
                    .font(.footnote).foregroundStyle(.secondary)
            }

            source(title: "about_transit_title", text: "about_transit_body", links: [
                SourceLink(label: "transitous.org/sources", url: "https://transitous.org/sources/"),
                SourceLink(label: "delfi.de", url: "https://www.delfi.de/"),
                SourceLink(label: "MOTIS", url: "https://motis-project.de/")
            ])
            source(title: "about_disruptions_title", text: "about_disruptions_body", links: [
                SourceLink(label: "gtfs.de", url: "https://gtfs.de/de/realtime/")
            ])
            source(title: "about_map_title", text: "about_map_body", links: [])

            Section {
                Text(loc.t("about_converted"))
                    .font(.footnote).foregroundStyle(.secondary)
            }

            Section(loc.t("about_open_source_title")) {
                Text(loc.t("about_open_source_body"))
                Link(destination: AppInfo.sourceCodeURL) {
                    HStack(spacing: 10) {
                        // The GitHub mark, only on this link to the source code on GitHub.
                        Image("github")
                            .renderingMode(.template)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 22, height: 22)
                        Text(loc.t("about_github"))
                    }
                }
            }
        }
        .navigationTitle(loc.t("about_title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    /// One data source: what it provides, its licence and the links to it.
    private func source(title: String, text: String, links: [SourceLink]) -> some View {
        Section(loc.t(title)) {
            Text(loc.t(text))
            ForEach(links) { link in
                if let url = URL(string: link.url) {
                    Link(link.label, destination: url)
                }
            }
        }
    }
}
