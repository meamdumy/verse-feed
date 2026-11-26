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

struct PageView: View {
    @ObservedObject var model: PageModel
    let colors: FeedColors
    let showContext: Bool
    let bottomInset: CGFloat
    let onShare: () -> Void
    let onContext: () -> Void

    // Arrival choreography: the box lands first, words start once it has, the buttons come last.
    private static let boxEnd = 0.30, wordsStart = 0.30, wordsEnd = 0.85, buttonsStart = 0.55, buttonsEnd = 0.90

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
        .padding(.top, 64)
        .padding(.bottom, bottomInset)
        // Depth: pages shrink and dim slightly as they move away from the centre.
        .scaleEffect(0.92 + 0.08 * r)
        .opacity(0.5 + 0.5 * r)
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

    init(model: PageModel, colors: FeedColors, showContext: Bool, bottomInset: CGFloat,
         onShare: @escaping () -> Void, onContext: @escaping () -> Void) {
        self.model = model
        super.init(rootView: PageView(model: model, colors: colors, showContext: showContext,
                                      bottomInset: bottomInset, onShare: onShare, onContext: onContext))
        view.backgroundColor = .clear
    }

    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }
}

// MARK: - Pager

/// Full-screen vertical pager using iOS's own paging scroll (UIPageViewController), so it feels
/// native. Watches the scroll to drive each page's arrival, the background blend and haptics.
final class FeedController: UIViewController, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
    private let source: FeedSource
    private let startKey: Int
    private let colors: FeedColors
    private let bottomInset: CGFloat
    private let backdrop: Backdrop
    private let onPageChanged: (Int) -> Void
    private let onShare: (Int) -> Void
    private let onContext: (Int) -> Void

    private let pager = UIPageViewController(transitionStyle: .scroll, navigationOrientation: .vertical,
                                             options: [.interPageSpacing: 0])
    private let pages = NSHashTable<PageController>.weakObjects()
    private var offsetObservation: NSKeyValueObservation?
    private var currentKey: Int
    private var travel = 0
    private let haptic = UIImpactFeedbackGenerator(style: .light)
    private var introLink: CADisplayLink?
    private var introStart: CFTimeInterval = 0

    init(source: FeedSource, startKey: Int, colors: FeedColors, bottomInset: CGFloat, backdrop: Backdrop,
         onPageChanged: @escaping (Int) -> Void, onShare: @escaping (Int) -> Void, onContext: @escaping (Int) -> Void) {
        self.source = source
        self.startKey = startKey
        self.colors = colors
        self.bottomInset = bottomInset
        self.backdrop = backdrop
        self.onPageChanged = onPageChanged
        self.onShare = onShare
        self.onContext = onContext
        currentKey = startKey
        super.init(nibName: nil, bundle: nil)
    }

    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        addChild(pager)
        pager.view.backgroundColor = .clear
        pager.view.frame = view.bounds
        pager.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(pager.view)
        pager.didMove(toParent: self)
        pager.dataSource = self
        pager.delegate = self
        let first = makePage(startKey, progress: UIAccessibility.isReduceMotionEnabled ? 1 : 0)
        pager.setViewControllers([first], direction: .forward, animated: false)
        if let scroll = pager.view.subviews.compactMap({ $0 as? UIScrollView }).first {
            offsetObservation = scroll.observe(\.contentOffset, options: [.new]) { [weak self] _, _ in
                self?.scrolled()
            }
        }
        backdrop.startSegment()
        backdrop.animate(to: Palettes.forVerse(source.verse(startKey)))
        haptic.prepare()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        playIntro()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        introLink?.invalidate()
        introLink = nil
    }

    private func makePage(_ key: Int, progress: Double) -> PageController {
        let model = PageModel(key: key, source: source, progress: progress)
        let v = model.verseIndex
        let pc = PageController(model: model, colors: colors, showContext: source.mode == .shuffle,
                                bottomInset: bottomInset,
                                onShare: { [weak self] in self?.onShare(v) },
                                onContext: { [weak self] in self?.onContext(v) })
        pages.add(pc)
        return pc
    }

    /// When the feed opens, the first page arrives: box, then words, then buttons (0.55 s).
    private func playIntro() {
        guard !UIAccessibility.isReduceMotionEnabled,
              let pc = pager.viewControllers?.first as? PageController, pc.model.progress < 1 else { return }
        introStart = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(introStep))
        link.add(to: .main, forMode: .common)
        introLink = link
    }

    @objc private func introStep() {
        let t = min(1, (CACurrentMediaTime() - introStart) / 0.55)
        (pager.viewControllers?.first as? PageController)?.model.progress = t
        if t >= 1 {
            introLink?.invalidate()
            introLink = nil
        }
    }

    /// Every scroll frame: each visible page's arrival follows its distance from the centre, and
    /// the background blends toward the page being revealed.
    private func scrolled() {
        let h = view.bounds.height
        guard h > 0 else { return }
        if introLink != nil {
            introLink?.invalidate()
            introLink = nil
        }
        var off = 0.0
        for pc in pages.allObjects where pc.isViewLoaded && pc.view.window != nil {
            let y = pc.view.convert(CGPoint.zero, to: view).y
            let o = Double(y / h)
            pc.model.progress = 1 - min(1, abs(o))
            if pc.model.key == currentKey { off = -o }
        }
        let to: Int? = off > 0 ? source.next(currentKey) : (off < 0 ? source.prev(currentKey) : nil)
        let from = Palettes.forVerse(source.verse(currentKey))
        let target = to.map { Palettes.forVerse(source.verse($0)) } ?? from
        backdrop.update(from: from, to: target, t: min(1, abs(off)), travel: Double(travel) + off)
    }

    // MARK: data source

    func pageViewController(_ pvc: UIPageViewController, viewControllerBefore vc: UIViewController) -> UIViewController? {
        guard let pc = vc as? PageController, let k = source.prev(pc.model.key) else { return nil }
        return makePage(k, progress: 0)
    }

    func pageViewController(_ pvc: UIPageViewController, viewControllerAfter vc: UIViewController) -> UIViewController? {
        guard let pc = vc as? PageController, let k = source.next(pc.model.key) else { return nil }
        return makePage(k, progress: 0)
    }

    // MARK: delegate

    func pageViewController(_ pvc: UIPageViewController, didFinishAnimating finished: Bool,
                            previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
        guard completed, let pc = pvc.viewControllers?.first as? PageController else { return }
        let k = pc.model.key
        guard k != currentKey else { return }
        travel += k > currentKey ? 1 : -1
        currentKey = k
        pc.model.progress = 1
        haptic.impactOccurred()
        haptic.prepare()
        onPageChanged(k)
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
            .onAppear { app.counterVerse = session.counterVerse }
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
