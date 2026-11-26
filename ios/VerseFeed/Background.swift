import SwiftUI
import QuartzCore

/// Drives the shared background: the Colorful palette (blended verse to verse as you swipe) and
/// the parallax "travel" that moves glows and bubbles with the scroll.
final class Backdrop: NSObject, ObservableObject {
    @Published var palette = Palettes.all[0]
    @Published var travel: Double = 0

    private var travelBase: Double = 0
    private var lastTravel: Double = 0
    private var link: CADisplayLink?
    private var blendFrom = Palettes.all[0]
    private var blendTo = Palettes.all[0]
    private var blendStart: CFTimeInterval = 0
    private let blendDuration: CFTimeInterval = 0.45

    /// Called while swiping the feed: blend from one verse's palette toward the next by `t`.
    func update(from: PalettePair, to: PalettePair, t: Double, travel: Double) {
        lastTravel = travel
        self.travel = travelBase + travel
        if link != nil {
            if t == 0 { return } // let a palette blend finish unless the user is dragging
            stopBlend()
        }
        palette = from.mix(to, t)
    }

    /// A new feed is starting: carry the parallax on from where the last one left it.
    func startSegment() {
        travelBase += lastTravel
        lastTravel = 0
    }

    /// Smoothly shifts the Colorful gradient to another palette (e.g. moving between screens).
    func animate(to p: PalettePair) {
        guard p != palette else { return }
        stopBlend()
        if UIAccessibility.isReduceMotionEnabled {
            palette = p
            return
        }
        blendFrom = palette
        blendTo = p
        blendStart = CACurrentMediaTime()
        let l = CADisplayLink(target: self, selector: #selector(step))
        l.add(to: .main, forMode: .common)
        link = l
    }

    @objc private func step() {
        let t = min(1, (CACurrentMediaTime() - blendStart) / blendDuration)
        palette = blendFrom.mix(blendTo, easeOut(t))
        if t >= 1 { stopBlend() }
    }

    private func stopBlend() {
        link?.invalidate()
        link = nil
    }
}

/// The backdrop behind every screen, in the chosen theme.
struct BackgroundView: View {
    @ObservedObject var backdrop: Backdrop
    let style: ThemeStyle

    var body: some View {
        Group {
            switch style {
            case .colorful: ColorfulBackground(backdrop: backdrop)
            case .blurLight: BubbleBackground(backdrop: backdrop, dark: false)
            case .blurDark: BubbleBackground(backdrop: backdrop, dark: true)
            }
        }
        .ignoresSafeArea()
    }
}

/// A diagonal gradient in the current verse's colours with two soft lights that slowly wander
/// on their own and swing with each swipe.
private struct ColorfulBackground: View {
    @ObservedObject var backdrop: Backdrop

    var body: some View {
        TimelineView(.animation) { ctx in
            let time = ctx.date.timeIntervalSinceReferenceDate
            GeometryReader { geo in
                let w = Double(geo.size.width), h = Double(geo.size.height)
                let big: Double = max(w, h) * 1.1
                let tr: Double = backdrop.travel
                let x1: Double = w * (0.18 + 0.10 * sin(time * 0.21))
                let y1: Double = h * (0.22 + 0.10 * sin(tr * 0.7) + 0.05 * cos(time * 0.17))
                let x2: Double = w * (0.86 + 0.08 * cos(time * 0.19 + 1.3))
                let y2: Double = h * (0.80 + 0.10 * cos(tr * 0.55) + 0.05 * sin(time * 0.15))
                ZStack {
                    LinearGradient(colors: [backdrop.palette.a.color, backdrop.palette.b.color],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                    glow(size: big).position(x: x1, y: y1)
                    glow(size: big).position(x: x2, y: y2)
                }
            }
        }
    }

    private func glow(size: CGFloat) -> some View {
        Circle()
            .fill(RadialGradient(colors: [Color.white.opacity(0.15), Color.white.opacity(0.05), .clear],
                                 center: .center, startRadius: 0, endRadius: size / 2))
            .frame(width: size, height: size)
    }
}

/// One constant grey gradient (light or dark) with slowly rising, heavily blurred bubbles that
/// also shift with the scroll.
private struct BubbleBackground: View {
    @ObservedObject var backdrop: Backdrop
    let dark: Bool

    private struct Bubble {
        var x: Double, y: Double, r: Double, speed: Double, phase: Double, sway: Double, depth: Double, alpha: Double
    }

    private static func make(dark: Bool) -> [Bubble] {
        var rng = SplitMix64(seed: 7)
        func rnd() -> Double { Double(rng.next() >> 11) / Double(1 << 53) }
        let peak = dark ? 0.10 : 0.85
        return (0..<14).map { _ in
            let depth = 0.25 + rnd() * 0.75
            let core = 0.04 + 0.16 * depth * rnd() + 0.03 * depth
            return Bubble(x: rnd(), y: rnd(), r: core * 2.4, speed: 5 + 14 * depth, phase: rnd() * 6.283,
                          sway: 6 + 10 * rnd(), depth: depth, alpha: peak * (0.6 + 0.4 * rnd()))
        }
    }

    var body: some View {
        let bubbles = BubbleBackground.make(dark: dark)
        let base: [Color] = dark
            ? [Color(red: 0.165, green: 0.173, blue: 0.192), Color(red: 0.09, green: 0.094, blue: 0.106), Color(red: 0.039, green: 0.039, blue: 0.047)]
            : [Color(red: 0.988, green: 0.988, blue: 0.992), Color(red: 0.937, green: 0.945, blue: 0.957), Color(red: 0.878, green: 0.89, blue: 0.91)]
        TimelineView(.animation) { ctx in
            let time = ctx.date.timeIntervalSinceReferenceDate
            let travel = backdrop.travel
            Canvas { g, size in
                let rect = CGRect(origin: .zero, size: size)
                g.fill(Path(rect), with: .linearGradient(Gradient(colors: base), startPoint: .zero,
                                                        endPoint: CGPoint(x: size.width * 0.35, y: size.height)))
                let W = Double(size.width), H = Double(size.height)
                let unit: Double = min(W, H)
                for b in bubbles {
                    let r: Double = b.r * unit
                    let span: Double = H + 2 * r
                    let rise: Double = time * b.speed
                    let parallax: Double = travel * b.depth * H * 0.35
                    var y: Double = b.y * span - rise - parallax
                    y = y.truncatingRemainder(dividingBy: span)
                    if y < 0 { y += span }
                    y -= r
                    let x: Double = b.x * W + sin(time * 0.35 + b.phase) * b.sway
                    let a: Double = b.alpha
                    let shading = GraphicsContext.Shading.radialGradient(
                        Gradient(stops: [
                            .init(color: Color.white.opacity(a), location: 0),
                            .init(color: Color.white.opacity(a * 0.88), location: 0.2),
                            .init(color: Color.white.opacity(a * 0.61), location: 0.4),
                            .init(color: Color.white.opacity(a * 0.32), location: 0.6),
                            .init(color: Color.white.opacity(a * 0.12), location: 0.8),
                            .init(color: Color.white.opacity(0), location: 1),
                        ]),
                        center: CGPoint(x: x, y: y), startRadius: 0, endRadius: r)
                    g.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), with: shading)
                }
            }
        }
    }
}
