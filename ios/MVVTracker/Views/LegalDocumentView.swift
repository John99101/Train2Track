import SwiftUI

/// Renders a plain-text legal document (privacy policy / legal notice) with light
/// structure so the long text reads well instead of being one wall of text.
///
/// Mirrors Android's `RawTextScreen` in `PrivacyPolicyScreen.kt`:
/// - the first line is the title (large, bold),
/// - a line matching `N. …` is a section heading (bold),
/// - a short line that opens a block and is not a sentence is a sub-heading,
/// - a line starting with `• ` is a bullet,
/// - `Last updated:` / `Stand:` is small and muted,
/// - a blank line is spacing,
/// - everything else is a body paragraph.
struct LegalDocumentView: View {
    let text: String

    /// `^\d+\.\s` — a numbered section heading like "1. Controller".
    private static let sectionRegex = try! NSRegularExpression(pattern: "^\\d+\\.\\s.*")

    private static func isSection(_ line: String) -> Bool {
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        return sectionRegex.firstMatch(in: line, options: [], range: range) != nil
    }

    private var lines: [String] {
        text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
    }

    var body: some View {
        let lines = self.lines
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.offset) { index, raw in
                    row(for: raw, at: index, in: lines)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .textSelection(.enabled)
        }
    }

    @ViewBuilder
    private func row(for raw: String, at index: Int, in lines: [String]) -> some View {
        let line = raw.trimmingCharacters(in: .whitespaces)
        let prevBlank = index == 0 ? true
            : lines[index - 1].trimmingCharacters(in: .whitespaces).isEmpty
        let nextBlank = index == lines.count - 1 ? true
            : lines[index + 1].trimmingCharacters(in: .whitespaces).isEmpty

        if line.isEmpty {
            Spacer().frame(height: 10)
        } else if index == 0 {
            Text(line)
                .font(.title3.weight(.bold))
                .padding(.bottom, 2)
        } else if line.hasPrefix("Last updated:") || line.hasPrefix("Stand:") {
            Text(line)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.bottom, 6)
        } else if Self.isSection(line) {
            Text(line)
                .font(.headline)
                .padding(.top, 18)
                .padding(.bottom, 4)
        } else if line.hasPrefix("• ") {
            HStack(alignment: .top, spacing: 8) {
                Text("•").font(.callout)
                ParagraphLabel(text: String(line.dropFirst(2)))
            }
            .padding(.top, 2)
        } else if prevBlank && !nextBlank && line.count <= 52
                    && !line.hasSuffix(".") && !line.hasSuffix(":") && !line.hasSuffix(",") {
            Text(line)
                .font(.subheadline.weight(.semibold))
                .padding(.top, 10)
                .padding(.bottom, 2)
        } else {
            ParagraphLabel(text: line)
                .padding(.bottom, 2)
        }
    }
}

/// A body paragraph or bullet text, drawn by UIKit. SwiftUI's `Text` cut off the
/// end of some long paragraphs here ("… verspätet se…" in the German legal notice,
/// iOS 26 and 27): it measured one line fewer than it then drew. A legal text must
/// never lose words, and UILabel measures and draws the same way.
private struct ParagraphLabel: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UILabel {
        let label = UILabel()
        label.numberOfLines = 0
        label.lineBreakMode = .byWordWrapping
        label.adjustsFontForContentSizeCategory = true
        label.font = .preferredFont(forTextStyle: .callout)
        label.textColor = .label
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return label
    }

    func updateUIView(_ label: UILabel, context: Context) {
        label.text = text
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UILabel, context: Context) -> CGSize? {
        guard let width = proposal.width, width.isFinite, width > 0 else { return nil }
        let fit = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: ceil(fit.height))
    }
}
