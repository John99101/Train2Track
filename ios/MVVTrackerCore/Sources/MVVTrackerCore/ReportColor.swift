/// The colour of the "!" of a reported journey, by how many riders report it:
/// 1 orange (not confirmed yet), 2 to 5 light red, 6 to 10 red, 11 or more bright red.
/// The same in the Feed, on the Map, on the train page and at the stations, and the
/// same values as Android (ui/components/ReportMark.kt). Colours are 0xRRGGBB.
public enum ReportColor {
    public static let orange: UInt32 = 0xE65100
    public static let lightRed: UInt32 = 0xEF5350
    public static let red: UInt32 = 0xDC2828
    public static let brightRed: UInt32 = 0xFF1744

    public static func forRiders(_ riders: Int) -> UInt32 {
        switch riders {
        case ...1: return orange
        case 2...5: return lightRed
        case 6...10: return red
        default: return brightRed
        }
    }
}
