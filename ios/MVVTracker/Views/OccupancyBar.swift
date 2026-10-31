import SwiftUI

/// The five occupancy levels, from many seats free (1, green) to very full (5, red).
/// The colours read in light and dark mode; every level also has its name, so the
/// bar never depends on colour alone. Port of Android's `OccupancyBar.kt`.
enum Occupancy {
    static let colors: [Color] = [
        Color(rgb: 0x2E7D32),
        Color(rgb: 0x7CB342),
        Color(rgb: 0xF9A825),
        Color(rgb: 0xEF6C00),
        Color(rgb: 0xC62828)
    ]

    static func color(_ level: Int) -> Color { colors[min(max(level - 1, 0), colors.count - 1)] }

    /// "occupancy_level_1" ... "occupancy_level_5".
    static func labelKey(_ level: Int) -> String { "occupancy_level_\(min(max(level, 1), colors.count))" }
}

/// The small bar for the Feed and the train's card: `level` of five segments filled
/// in that level's colour. Read out as "Occupancy: standing only".
struct OccupancyMeter: View {
    @EnvironmentObject private var loc: LocalizationManager
    let level: Int
    var segmentWidth: CGFloat = 7
    var height: CGFloat = 8

    var body: some View {
        HStack(spacing: 2) {
            ForEach(1...Occupancy.colors.count, id: \.self) { i in
                RoundedRectangle(cornerRadius: 2)
                    .fill(i <= level ? Occupancy.color(level) : Color.primary.opacity(0.15))
                    .frame(width: segmentWidth, height: height)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(loc.t("occupancy_meter_desc", loc.t(Occupancy.labelKey(level)))))
    }
}

/// The bar to pull in the report window: five stops from green (many seats free)
/// to red (very full). Pulling only moves the knob; the card's Submit button sends
/// it. VoiceOver: swipe up or down to change, read out with the level's name.
/// Port of Android's `OccupancySlider`.
struct OccupancySlider: View {
    @EnvironmentObject private var loc: LocalizationManager
    @Binding var level: Int
    private let knob: CGFloat = 26

    var body: some View {
        GeometryReader { geo in
            let count = Occupancy.colors.count
            let step = max(geo.size.width - knob, 1) / CGFloat(count - 1)
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(LinearGradient(colors: Occupancy.colors, startPoint: .leading, endPoint: .trailing))
                    .frame(height: 8)
                    .padding(.horizontal, knob / 2)
                // Dots only at the inner stops (2, 3 and 4), as on Android.
                ForEach(1..<(count - 1), id: \.self) { i in
                    Circle()
                        .fill(Color.white.opacity(0.85))
                        .frame(width: 4, height: 4)
                        .offset(x: knob / 2 + step * CGFloat(i) - 2)
                }
                Circle()
                    .fill(Occupancy.color(level))
                    .overlay(Circle().strokeBorder(Color.white, lineWidth: 3))
                    .shadow(radius: 1.5)
                    .frame(width: knob, height: knob)
                    .offset(x: step * CGFloat(level - 1))
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .contentShape(Rectangle())
            .simultaneousGesture(
                DragGesture(minimumDistance: 10).onChanged { value in
                    // A vertical drag belongs to the surrounding report form.
                    guard abs(value.translation.width) > abs(value.translation.height) else { return }
                    let stop = Int(((value.location.x - knob / 2) / step).rounded()) + 1
                    level = min(max(stop, 1), count)
                }
            )
            // A tap jumps to the nearest stop, as on Android.
            .onTapGesture { location in
                let stop = Int(((location.x - knob / 2) / step).rounded()) + 1
                level = min(max(stop, 1), count)
            }
        }
        .frame(height: 34)
        .accessibilityElement()
        .accessibilityLabel(Text(loc.t("occupancy_title")))
        .accessibilityValue(Text(loc.t(Occupancy.labelKey(level))))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: level = min(level + 1, Occupancy.colors.count)
            case .decrement: level = max(level - 1, 1)
            @unknown default: break
            }
        }
    }
}
