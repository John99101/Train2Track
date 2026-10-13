import SwiftUI
import CoreLocation
import MVVTrackerCore

extension TrainPosition {
    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    /// Localization key for this mode's short label (S-Bahn / U-Bahn / Bus / Tram).
    var modeLabelKey: String {
        switch mode {
        case "SUBWAY": return "label_ubahn"
        case "BUS": return "label_bus"
        case "TRAM": return "label_tram"
        default: return "label_sbahn"
        }
    }

    /// Line colour, exactly like Android's `trainColorArgb`: the official colour
    /// from the feed (correct in every city), then a Munich fallback table, then a
    /// per-mode default.
    var displayColor: Color {
        if let hex = colorHex, !hex.trimmingCharacters(in: .whitespaces).isEmpty, let c = Color(hex: hex) { return c }
        if let rgb = Self.munichLineColors[label] { return Color(rgb: rgb) }
        return Color(rgb: Self.colorForMode(mode))
    }

    static func colorForMode(_ mode: String) -> UInt32 {
        switch mode {
        case "METRO": return 0x009944
        case "SUBWAY": return 0x00629B
        case "TRAM": return 0xD65A14            // Straßenbahn: orange
        case "BUS": return 0x7C3AAD             // Bus: violet
        case "REGIONAL_RAIL", "REGIONAL_FAST_RAIL": return 0x009688
        case "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL": return 0x3C3C3C
        default: return 0x787878
        }
    }

    private static let munichLineColors: [String: UInt32] = [
        "S1": 0x16BAE7, "S2": 0x76B82A, "S3": 0x951B81, "S4": 0xE30613, "S6": 0x00975F,
        "S7": 0x943126, "S8": 0x222222, "S20": 0xEE6BA0,
        "U1": 0x52822F, "U2": 0xC20831, "U3": 0xEC6725, "U4": 0x00A984, "U5": 0xBC7A00, "U6": 0x0065B1
    ]
}

/// Our own mode badge (not the operators' trademarked logos): a coloured tile with a
/// letter for S-/U-Bahn and a transit glyph for tram/bus. Sized to sit inline with text.
/// METRO = S-Bahn (green "S" circle), SUBWAY = U-Bahn (blue "U" square).
struct ModeSymbol: View {
    let mode: String
    var size: CGFloat = 20

    // Same colours as Android's ModeSymbol.kt.
    private static let metroGreen = Color(rgb: 0x009944)   // S-Bahn
    private static let subwayBlue = Color(rgb: 0x00629B)   // U-Bahn
    private static let tramOrange = Color(rgb: 0xD65A14)
    private static let busViolet = Color(rgb: 0x7C3AAD)
    private static let otherGrey = Color(rgb: 0x787878)

    var body: some View {
        switch mode {
        case "BUS": iconBadge("bus.fill", Self.busViolet)
        case "TRAM": iconBadge("tram.fill", Self.tramOrange)
        case "SUBWAY": letterBadge("U", Self.subwayBlue, corner: size * 0.2)
        case "METRO": letterBadge("S", Self.metroGreen, corner: size / 2)
        default: letterBadge("•", Self.otherGrey, corner: size / 2)
        }
    }

    private func letterBadge(_ letter: String, _ color: Color, corner: CGFloat) -> some View {
        Text(letter)
            .font(.system(size: size * 0.6, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color, in: RoundedRectangle(cornerRadius: corner))
    }

    private func iconBadge(_ symbol: String, _ color: Color) -> some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.58, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color, in: Circle())
    }
}

extension Color {
    init(rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255.0,
                  green: Double((rgb >> 8) & 0xFF) / 255.0,
                  blue: Double(rgb & 0xFF) / 255.0)
    }

    /// Parses a 6-digit hex string (optionally prefixed with '#').
    init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(
            red: Double((v >> 16) & 0xFF) / 255.0,
            green: Double((v >> 8) & 0xFF) / 255.0,
            blue: Double(v & 0xFF) / 255.0
        )
    }

    /// Readable label colour (black on light line colours, white on dark).
    var contrastingLabel: Color {
        let ui = UIColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        let luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return luminance > 0.6 ? .black : .white
    }
}
