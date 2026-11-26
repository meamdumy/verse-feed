import SwiftUI

/// One setting themes everything: the background behind every screen and light or dark UI.
enum ThemeStyle: Int, CaseIterable, Identifiable {
    case colorful = 0
    case blurLight = 1
    case blurDark = 2

    var id: Int { rawValue }
    var label: String { ["Colorful", "Blur light", "Blur dark"][rawValue] }
    var isLight: Bool { self == .blurLight }
}

struct RGB: Equatable {
    var r: Double, g: Double, b: Double

    static func hex(_ v: UInt32) -> RGB {
        RGB(r: Double((v >> 16) & 0xFF) / 255, g: Double((v >> 8) & 0xFF) / 255, b: Double(v & 0xFF) / 255)
    }

    func mix(_ o: RGB, _ t: Double) -> RGB {
        RGB(r: r + (o.r - r) * t, g: g + (o.g - g) * t, b: b + (o.b - b) * t)
    }

    var color: Color { Color(red: r, green: g, blue: b) }
}

struct PalettePair: Equatable {
    var a: RGB
    var b: RGB

    func mix(_ o: PalettePair, _ t: Double) -> PalettePair { PalettePair(a: a.mix(o.a, t), b: b.mix(o.b, t)) }
}

enum Palettes {
    private static let raw: [(UInt32, UInt32)] = [
        (0x1E1B4B, 0x4338CA), (0x0F172A, 0x0E7490), (0x052E16, 0x15803D), (0x3B0764, 0x9333EA),
        (0x431407, 0xB8400B), (0x172554, 0x2563EB), (0x4C0519, 0xBE123C), (0x1C1917, 0x57534E),
        (0x042F2E, 0x0F766E), (0x3A1D04, 0x95590A),
    ]
    static let all: [PalettePair] = raw.map { PalettePair(a: RGB.hex($0.0), b: RGB.hex($0.1)) }

    static func forVerse(_ i: Int) -> PalettePair { all[((i % all.count) + all.count) % all.count] }
}

enum Accent {
    static let a = Color(red: 0x37 / 255, green: 0x30 / 255, blue: 0xA3 / 255)
    static let b = Color(red: 0x7C / 255, green: 0x3A / 255, blue: 0xED / 255)
    static var gradient: LinearGradient {
        LinearGradient(colors: [a, b], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// Colours for menus (home, browse, search, settings).
struct UIColors {
    let text: Color
    let subtext: Color
    let surface: Color
    let stroke: Color
    let sheet: Color
    let track: Color

    static func of(_ s: ThemeStyle) -> UIColors {
        if s.isLight {
            return UIColors(text: Color(red: 0.11, green: 0.106, blue: 0.122),
                            subtext: Color(red: 0.373, green: 0.361, blue: 0.408),
                            surface: Color.white.opacity(0.65), stroke: Color.black.opacity(0.12),
                            sheet: Color.white.opacity(0.97), track: Color.black.opacity(0.08))
        }
        return UIColors(text: Color(red: 0.949, green: 0.945, blue: 0.965),
                        subtext: Color(red: 0.702, green: 0.694, blue: 0.741),
                        surface: Color.white.opacity(0.12), stroke: Color.white.opacity(0.16),
                        sheet: Color(red: 0.11, green: 0.114, blue: 0.141).opacity(0.97),
                        track: Color.black.opacity(0.22))
    }
}

/// Colours for the verse feed, to suit the background.
struct FeedColors {
    let text: Color
    let sub: Color
    let boxFill: Color
    let boxStroke: Color
    let pill: Color

    static func of(_ s: ThemeStyle) -> FeedColors {
        switch s {
        case .blurLight:
            let ink = Color(red: 0.114, green: 0.125, blue: 0.145)
            return FeedColors(text: ink, sub: ink.opacity(0.6), boxFill: Color.white.opacity(0.62),
                              boxStroke: Color.black.opacity(0.12), pill: Color.black.opacity(0.07))
        case .blurDark:
            return FeedColors(text: .white, sub: Color.white.opacity(0.6), boxFill: Color.white.opacity(0.08),
                              boxStroke: Color.white.opacity(0.15), pill: Color.white.opacity(0.12))
        case .colorful:
            return FeedColors(text: .white, sub: Color.white.opacity(0.7), boxFill: Color.white.opacity(0.12),
                              boxStroke: Color.white.opacity(0.25), pill: Color.white.opacity(0.15))
        }
    }
}

func clamp01(_ v: Double) -> Double { max(0, min(1, v)) }

func easeOut(_ t: Double) -> Double {
    let u = 1 - t
    return 1 - u * u * u
}
