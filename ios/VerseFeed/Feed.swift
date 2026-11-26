import SwiftUI
import UIKit

// MARK: - Feed data

/// What a feed shows: the order of verses (Read/today's verse go in Bible order, Shuffle in a
/// fixed random order) split into pages of at most six lines. A page key packs
/// (position in the feed, part of the verse).
final class FeedSource {
    let bible: Bible
    let mode: FeedMode
    let order: [Int]?
    let paginator: Paginator

    init(bible: Bible, mode: FeedMode, order: [Int]?, textWidth: CGFloat) {
        self.bible = bible
        self.mode = mode
        self.order = order
        paginator = Paginator(textWidth: textWidth)
    }

    static func key(_ seq: Int, _ part: Int) -> Int { seq << 8 | part }
    static func seq(_ key: Int) -> Int { key >> 8 }
    static func part(_ key: Int) -> Int { key & 0xFF }

    func verse(atSeq s: Int) -> Int { order?[s] ?? s }
    func verse(_ key: Int) -> Int { verse(atSeq: FeedSource.seq(key)) }

    func parts(_ key: Int) -> Int {
        let v = verse(key)
        return paginator.parts(v, bible.text[v])
    }

    func next(_ key: Int) -> Int? {
        let s = FeedSource.seq(key), p = FeedSource.part(key)
        if p + 1 < parts(key) { return key + 1 }
        return s + 1 < bible.count ? FeedSource.key(s + 1, 0) : nil
    }

    func prev(_ key: Int) -> Int? {
        let s = FeedSource.seq(key), p = FeedSource.part(key)
        if p > 0 { return key - 1 }
        guard s > 0 else { return nil }
        let pk = FeedSource.key(s - 1, 0)
        return FeedSource.key(s - 1, parts(pk) - 1)
    }
}

/// One feed screen's state; created once per visit (kept by @StateObject).
final class FeedSession: ObservableObject {
    let source: FeedSource
    let startKey: Int
    @Published var counterVerse: Int

    init(app: AppState, bible: Bible, mode: FeedMode, start: Int, textWidth: CGFloat) {
        let order = mode == .shuffle ? app.shuffleOrder(for: bible) : nil
        let src = FeedSource(bible: bible, mode: mode, order: order, textWidth: textWidth)
        let d = app.defaults
        var seq = start >= 0 ? start : d.integer(forKey: mode.posKey)
        seq = max(0, min(bible.count - 1, seq))
        var part = start >= 0 ? 0 : d.integer(forKey: mode.partKey)
        part = max(0, min(src.parts(FeedSource.key(seq, 0)) - 1, part))
        d.set(seq, forKey: mode.posKey)
        d.set(part, forKey: mode.partKey)
        source = src
        startKey = FeedSource.key(seq, part)
        counterVerse = src.verse(atSeq: seq)
    }
}

// MARK: - Page

/// One page's content plus how far it has arrived on screen (0 = off screen, 1 = settled).
final class PageModel: ObservableObject {
    let key: Int
    let verseIndex: Int
    let ref: String
    let chunk: String
    let words: [String]
    let part: Int
    let parts: Int
    @Published var progress: Double

    init(key: Int, source: FeedSource, progress: Double) {
        self.key = key
        let v = source.verse(key)
        verseIndex = v
        ref = source.bible.ref(v)
        part = FeedSource.part(key)
        parts = source.parts(key)
        chunk = source.paginator.chunk(source.bible.text[v], part: part)
        words = chunk.components(separatedBy: " ")
        self.progress = progress
    }
}

/// The screen's safe-area insets, shared by all pages. Pages ignore their own safe area (it would
/// change as they slide off screen and make them jitter) and pad by these instead.
final class PageMetrics: ObservableObject {
    @Published var top: CGFloat = 0
    @Published var bottom: CGFloat = 0
}

struct PageView: View {
    @ObservedObject var model: PageModel
    @ObservedObject var metrics: PageMetrics
    let colors: FeedColors
    let showContext: Bool
    let bottomInset: CGFloat
    let onShare: () -> Void
    let onContext: () -> Void

    // Arrival choreography over the whole swipe: the box fades in first, the words follow as it
    // lands, the buttons come last. A leaving page plays it in reverse.
    private static let boxEnd = 0.55, wordsStart = 0.40, wordsEnd = 0.92, buttonsStart = 0.60, buttonsEnd = 0.95

    var body: some View {
        let r = model.progress
        let box = easeOut(clamp01(r / PageView.boxEnd))
        let words = (r - PageView.wordsStart) / (PageView.wordsEnd - PageView.wordsStart)
        let btn = easeOut(clamp01((r - PageView.buttonsStart) / (PageView.buttonsEnd - PageView.buttonsStart)))
        VStack(spacing: 18) {
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 12) {
                Text(model.ref.uppercased())
                    .font(.system(size: 12, weight: .bold))
                    .tracking(1.4)
                    .foregroundStyle(colors.sub)
                Text(revealed(words))
                    .font(.system(size: Paginator.fontSize, design: .serif))
                    .lineSpacing(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                if model.parts > 1 {
                    HStack(spacing: 5) {
                        ForEach(0..<model.parts, id: \.self) { i in
                            Circle()
                                .fill(i == model.part ? colors.sub : Color.clear)
                                .overlay(Circle().stroke(colors.sub, lineWidth: 1))
                                .frame(width: 6, height: 6)
                        }
                    }
                    .padding(.top, 4)
                }
            }
            .padding(22)
            .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(colors.boxFill))
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(colors.boxStroke, lineWidth: 1))
            .padding(.horizontal, 18)
            .opacity(box)
            .scaleEffect(0.92 + 0.08 * box)
            .offset(y: (1 - box) * 24)

            HStack(spacing: 8) {
                if showContext { pill("Read in context", action: onContext) }
                pill("Share", action: onShare)
            }
            .opacity(btn)
            .offset(y: (1 - btn) * 10)
            Spacer(minLength: 0)
        }
        .padding(.top, metrics.top + 64)
        .padding(.bottom, metrics.bottom + bottomInset)
        // Depth: pages shrink as they move away from the centre and fade out completely at the
        // screen's edge, so verses and their boxes fade in and out as you scroll.
        .scaleEffect(0.92 + 0.08 * r)
        .opacity(r)
    }

    /// The verse with its words fading in one after another, in reading order.
    private func revealed(_ progress: Double) -> AttributedString {
        let n = model.words.count
        var out = AttributedString()
        for (i, w) in model.words.enumerated() {
            var piece = AttributedString(i < n - 1 ? w + " " : w)
            let start = n > 1 ? 0.45 * Double(i) / Double(n - 1) : 0
            piece.foregroundColor = colors.text.opacity(clamp01((progress - start) / 0.55))
            out += piece
        }
        return out
    }

    private func pill(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(colors.text)
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(Capsule().fill(colors.pill))
        }
        .buttonStyle(BounceStyle())
    }
}

final class PageController: UIHostingController<PageView> {
    let model: PageModel

    init(model: PageModel, metrics: PageMetrics, colors: FeedColors, showContext: Bool, bottomInset: CGFloat,
         onShare: @escaping () -> Void, onContext: @escaping () -> Void) {
        self.model = model
        super.init(rootView: PageView(model: model, metrics: metrics, colors: colors, showContext: showContext,
                                      bottomInset: bottomInset, onShare: onShare, onContext: onContext))
        safeAreaRegions = []
        view.backgroundColor = .clear
    }

    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }
}

// MARK: - Pager

/// Full-screen vertical pager, a direct port of the Android app's VersePager so it feels the same:
/// the page follows your finger; a quick flick or a drag past 12% of the screen turns the page;
/// pages settle in 90-200 ms starting at your finger's speed; touching a settling page finishes it
/// so swipes chain; the ends rubber-band. Only three pages exist (previous, current, next).
final class FeedController: UIViewController, UIGestureRecognizerDelegate {
    private static let distanceThreshold: CGFloat = 0.12
    private static let flingThreshold: CGFloat = 180 // points per second
    /// Scales every page-settle duration (0.88 = 12% snappier than the Android timings).
    private static let snap: Double = 0.88
    private static let minSettle: Double = 0.09 * snap
    private static let maxSettle: Double = 0.20 * snap
    private static let decelerate: Double = 1.5

    private let source: FeedSource
    private let colors: FeedColors
    private let bottomInset: CGFloat
    private let backdrop: Backdrop
    private let onPageChanged: (Int) -> Void
    private let onShare: (Int) -> Void
    private let onContext: (Int) -> Void

    private var keys: [Int?] = [nil, nil, nil]
    private var pages: [PageController?] = [nil, nil, nil]
    private let metrics = PageMetrics()
    private var drag: CGFloat = 0
    private var dragStart: CGFloat = 0
    private var travel = 0
    private let haptic = UIImpactFeedbackGenerator(style: .light)
    /// Only the feed on screen drives the shared background (not one sliding away).
    var active = false

    private var settleLink: CADisplayLink?
    private var settleFrom: CGFloat = 0
    private var settleTo: CGFloat = 0
    private var settleDir = 0
    private var settleStart: CFTimeInterval = 0
    private var settleDuration: Double = 0

    private var introLink: CADisplayLink?
    private var introStart: CFTimeInterval = 0

    init(source: FeedSource, startKey: Int, colors: FeedColors, bottomInset: CGFloat, backdrop: Backdrop,
         onPageChanged: @escaping (Int) -> Void, onShare: @escaping (Int) -> Void, onContext: @escaping (Int) -> Void) {
        self.source = source
        self.colors = colors
        self.bottomInset = bottomInset
        self.backdrop = backdrop
        self.onPageChanged = onPageChanged
        self.onShare = onShare
        self.onContext = onContext
        keys[1] = startKey
        super.init(nibName: nil, bundle: nil)
    }

    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        view.clipsToBounds = true
        keys[0] = keys[1].flatMap { source.prev($0) }
        keys[2] = keys[1].flatMap { source.next($0) }
        for k in 0..<3 { bind(k) }
        pages[1]?.model.progress = UIAccessibility.isReduceMotionEnabled ? 1 : 0
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        pan.delegate = self
        view.addGestureRecognizer(pan)
        haptic.prepare()
        backdrop.startSegment()
        if let k = keys[1] { backdrop.animate(to: Palettes.forVerse(source.verse(k))) }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        active = true
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        active = true
        playIntro()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        active = false
        stopIntro()
        settleLink?.invalidate()
        settleLink = nil
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        if metrics.top != view.safeAreaInsets.top { metrics.top = view.safeAreaInsets.top }
        if metrics.bottom != view.safeAreaInsets.bottom { metrics.bottom = view.safeAreaInsets.bottom }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        for pc in pages.compactMap({ $0 }) { pc.view.bounds = CGRect(origin: .zero, size: view.bounds.size) }
        layoutPages()
    }

    // MARK: pages

    private func makePage(_ key: Int) -> PageController {
        let model = PageModel(key: key, source: source, progress: 0)
        let v = model.verseIndex
        return PageController(model: model, metrics: metrics, colors: colors, showContext: source.mode == .shuffle,
                              bottomInset: bottomInset,
                              onShare: { [weak self] in self?.onShare(v) },
                              onContext: { [weak self] in self?.onContext(v) })
    }

    private func bind(_ k: Int) {
        if let old = pages[k] {
            old.willMove(toParent: nil)
            old.view.removeFromSuperview()
            old.removeFromParent()
            pages[k] = nil
        }
        guard let key = keys[k] else { return }
        let pc = makePage(key)
        addChild(pc)
        pc.view.frame = view.bounds
        view.addSubview(pc.view)
        pc.didMove(toParent: self)
        pages[k] = pc
    }

    /// Positions the three pages, sets each page's arrival progress from its distance to the
    /// centre, and blends the background toward the page being revealed.
    private func layoutPages() {
        let h = view.bounds.height
        guard h > 0 else { return }
        for k in 0..<3 {
            guard let pc = pages[k] else { continue }
            let y = CGFloat(k - 1) * h + drag
            pc.view.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY + y)
            let o = Double(y / h)
            if !(k == 1 && introLink != nil) { pc.model.progress = 1 - min(1, abs(o)) }
        }
        guard active, let cur = keys[1] else { return }
        let off = Double(-drag / h)
        let to: Int? = off > 0 ? keys[2] : (off < 0 ? keys[0] : nil)
        let from = Palettes.forVerse(source.verse(cur))
        let target = to.map { Palettes.forVerse(source.verse($0)) } ?? from
        backdrop.update(from: from, to: target, t: min(1, abs(off)), travel: Double(travel) + off)
    }

    // MARK: gestures

    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard let pan = g as? UIPanGestureRecognizer else { return true }
        let v = pan.velocity(in: view)
        return abs(v.y) > abs(v.x)
    }

    @objc private func panned(_ g: UIPanGestureRecognizer) {
        switch g.state {
        case .began:
            finishSettle() // swiping again mid-animation chains straight on
            stopIntro()
            dragStart = drag
        case .changed:
            applyDrag(dragStart + g.translation(in: view).y)
        case .ended, .cancelled, .failed:
            release(velocity: g.velocity(in: view).y)
        default:
            break
        }
    }

    private func applyDrag(_ target: CGFloat) {
        let h = view.bounds.height
        guard h > 0 else { return }
        var next = target
        let atStart = keys[0] == nil, atEnd = keys[2] == nil
        if (next > 0 && atStart) || (next < 0 && atEnd) {
            next = max(-h * 0.2, min(h * 0.2, next * 0.35)) // rubber band at the ends
        }
        drag = max(-h, min(h, next))
        layoutPages()
    }

    private func release(velocity vy: CGFloat) {
        let h = view.bounds.height
        var dir = 0
        if abs(vy) > FeedController.flingThreshold { dir = vy < 0 ? 1 : -1 } // flick up = next
        else if drag < -h * FeedController.distanceThreshold { dir = 1 }
        else if drag > h * FeedController.distanceThreshold { dir = -1 }
        if dir == 1 && drag > 0 { dir = 0 }
        if dir == -1 && drag < 0 { dir = 0 }
        if dir == 1 && keys[2] == nil { dir = 0 }
        if dir == -1 && keys[0] == nil { dir = 0 }
        if dir != 0 { // tick the moment you let go and the page is committed to turning
            haptic.impactOccurred()
            haptic.prepare()
        }
        settle(dir, speed: dir == 0 ? 0 : abs(vy))
    }

    /// Animates to the next (1), previous (-1) or back to the current (0) page, starting at the
    /// finger's speed so a flick carries straight on.
    private func settle(_ dir: Int, speed: CGFloat) {
        let h = view.bounds.height
        settleLink?.invalidate()
        settleLink = nil
        let to = -CGFloat(dir) * h
        let dist = abs(to - drag)
        if h <= 0 || dist < 1 {
            drag = to
            if dir != 0 { commit(dir) } else { layoutPages() }
            return
        }
        var t: Double
        if speed > 0 { t = 2 * FeedController.decelerate * Double(dist / speed) * FeedController.snap } // ~finger speed
        else { t = (0.11 + 0.09 * Double(dist / h)) * FeedController.snap }
        t = max(FeedController.minSettle, min(FeedController.maxSettle, t))
        settleFrom = drag
        settleTo = to
        settleDir = dir
        settleStart = CACurrentMediaTime()
        settleDuration = t
        let link = CADisplayLink(target: self, selector: #selector(settleStep))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        settleLink = link
    }

    @objc private func settleStep() {
        let t = min(1, (CACurrentMediaTime() - settleStart) / settleDuration)
        let eased = 1 - pow(1 - t, 2 * FeedController.decelerate)
        drag = settleFrom + (settleTo - settleFrom) * CGFloat(eased)
        layoutPages()
        if t >= 1 {
            settleLink?.invalidate()
            settleLink = nil
            if settleDir != 0 { commit(settleDir) }
        }
    }

    /// If a page is still settling, jump to where it was going so the next swipe starts fresh.
    private func finishSettle() {
        guard settleLink != nil else { return }
        settleLink?.invalidate()
        settleLink = nil
        drag = settleTo
        if settleDir != 0 { commit(settleDir) } else { layoutPages() }
    }

    private func commit(_ dir: Int) {
        if dir > 0 {
            if let old = pages[0] { old.willMove(toParent: nil); old.view.removeFromSuperview(); old.removeFromParent() }
            pages = [pages[1], pages[2], nil]
            keys = [keys[1], keys[2], keys[2].flatMap { source.next($0) }]
            travel += 1
            drag = 0
            bind(2)
        } else {
            if let old = pages[2] { old.willMove(toParent: nil); old.view.removeFromSuperview(); old.removeFromParent() }
            pages = [nil, pages[0], pages[1]]
            keys = [keys[1].flatMap { source.prev($0) }, keys[0], keys[1]]
            travel -= 1
            drag = 0
            bind(0)
        }
        layoutPages()
        if let k = keys[1] { onPageChanged(k) }
    }

    // MARK: intro

    /// When the feed opens, the first page arrives: box, then words, then buttons (0.55 s).
    private func playIntro() {
        guard introLink == nil, !UIAccessibility.isReduceMotionEnabled,
              let pc = pages[1], pc.model.progress < 1 else { return }
        introStart = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(introStep))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        introLink = link
    }

    @objc private func introStep() {
        let t = min(1, (CACurrentMediaTime() - introStart) / 0.55)
        pages[1]?.model.progress = t
        if t >= 1 { stopIntro() }
    }

    private func stopIntro() {
        introLink?.invalidate()
        introLink = nil
    }
}

struct FeedPager: UIViewControllerRepresentable {
    let session: FeedSession
    let colors: FeedColors
    let bottomInset: CGFloat
    let backdrop: Backdrop
    let onPageChanged: (Int) -> Void
    let onShare: (Int) -> Void
    let onContext: (Int) -> Void

    func makeUIViewController(context: Context) -> FeedController {
        FeedController(source: session.source, startKey: session.startKey, colors: colors, bottomInset: bottomInset,
                       backdrop: backdrop, onPageChanged: onPageChanged, onShare: onShare, onContext: onContext)
    }

    func updateUIViewController(_ vc: FeedController, context: Context) {}

    static func dismantleUIViewController(_ vc: FeedController, coordinator: ()) {
        vc.active = false // a feed sliding away must not repaint the shared background
    }
}

// MARK: - Screen

struct FeedScreen: View {
    @EnvironmentObject var app: AppState
    @StateObject private var session: FeedSession
    private let mode: FeedMode

    init(app: AppState, bible: Bible, mode: FeedMode, start: Int) {
        self.mode = mode
        // Text width inside the verse box: screen minus the box's margins (18) and padding (22).
        let width = UIScreen.main.bounds.width - 2 * 18 - 2 * 22
        _session = StateObject(wrappedValue: FeedSession(app: app, bible: bible, mode: mode, start: start, textWidth: width))
    }

    var body: some View {
        let colors = app.feed
        FeedPager(session: session, colors: colors, bottomInset: BottomBar.contentInset + 8, backdrop: app.backdrop,
                  onPageChanged: { key in
                      let d = app.defaults
                      d.set(FeedSource.seq(key), forKey: mode.posKey)
                      d.set(FeedSource.part(key), forKey: mode.partKey)
                      session.counterVerse = session.source.verse(key)
                      app.counterVerse = session.counterVerse
                  },
                  onShare: { app.share($0) },
                  onContext: { v in app.go(.feed(.read, v), from: .leading) })
            .ignoresSafeArea()
            .onAppear {
                if app.counterVerse == nil {
                    // Arriving from another tab: the counter slides in with the feed.
                    withAnimation(.snappy(duration: 0.32)) { app.counterVerse = session.counterVerse }
                } else {
                    app.counterVerse = session.counterVerse // feed to feed: it stays put
                }
            }
    }
}

/// "verse x/N" in a glass pill, fixed at the top while the verses scroll past. The number changes
/// in place (no fade, so it never flickers) and the pill is sized for the chapter's widest number.
struct VerseCounter: View {
    let bible: Bible
    let verseIndex: Int
    let colors: FeedColors
    let light: Bool

    var body: some View {
        let last = bible.lastVerseNumber(bible.book[verseIndex], bible.chapter[verseIndex])
        let widest = "verse " + String(String(last).map { _ in Character("8") }) + "/\(last)"
        ZStack {
            Text(widest).hidden()
            Text("verse \(bible.verse[verseIndex])/\(last)")
                .contentTransition(.identity)
        }
        .font(.system(size: 14, weight: .bold).monospacedDigit())
        .foregroundStyle(colors.text)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Capsule().fill(.ultraThinMaterial))
        .overlay(Capsule().stroke(light ? Color.black.opacity(0.08) : Color.white.opacity(0.12), lineWidth: 1))
        .environment(\.colorScheme, light ? .light : .dark)
        .transaction { $0.animation = nil }
    }
}

extension View {
    /// Fades, lifts and pops a view in after `delay` once `shown` turns true.
    func popIn(_ shown: Bool, delay: Double) -> some View {
        self
            .opacity(shown ? 1 : 0)
            .scaleEffect(shown ? 1 : 0.94)
            .offset(y: shown ? 0 : 12)
            .animation(.easeOut(duration: 0.2).delay(delay), value: shown)
    }
}

/// Quick press-down-and-spring-back on tap.
struct BounceStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.5), value: configuration.isPressed)
    }
}
