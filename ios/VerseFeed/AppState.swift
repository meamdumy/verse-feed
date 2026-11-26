import SwiftUI
import UIKit

enum FeedMode: Int {
    case read = 0
    case shuffle = 1
    case daily = 2 // today's verse in context: part of Home, keeps its own place

    var posKey: String { ["read_pos", "shuffle_pos", "daily_pos"][rawValue] }
    var partKey: String { ["read_part", "shuffle_part", "daily_part"][rawValue] }
}

enum Screen: Equatable {
    case home
    case feed(FeedMode, Int) // start index (-1 = where you left off)
    case books
    case chapters(Int)
    case verses(Int, Int)

    var isFeed: Bool {
        if case .feed = self { return true }
        return false
    }

    /// Which bottom-bar tab this screen belongs to.
    var tab: Int {
        switch self {
        case .home: return 0
        case .feed(let m, _): return m == .read ? 1 : m == .shuffle ? 2 : 0
        case .books, .chapters, .verses: return 3
        }
    }
}

/// App-wide state: navigation, the loaded Bible, settings, and saved positions.
final class AppState: ObservableObject {
    @Published var bible: Bible?
    @Published var screen: Screen = .home
    @Published var screenID = UUID()
    @Published var pushEdge: Edge = .trailing
    @Published var theme: ThemeStyle
    @Published var translation: Translation
    @Published var settingsOpen = false
    @Published var themeBlur: CGFloat = 0
    @Published var translationStatus = ""
    @Published var homeScrollToBottom = false
    @Published var homeScrollToTop = 0 // bumped when Home is tapped while already on Home
    @Published var fadeTransition = false // next screen change crossfades instead of pushing
    @Published var counterVerse: Int? // the feed's "verse x/N"; lives outside the sliding screens
    private var browseReturn: Screen = .home // where Books' back arrow goes

    let backdrop = Backdrop()
    let defaults = UserDefaults.standard
    private var shuffleOrder: [Int]?
    private var translationGen = 0

    init() {
        theme = ThemeStyle(rawValue: UserDefaults.standard.integer(forKey: "bg_style")) ?? .colorful
        translation = Translation(rawValue: UserDefaults.standard.integer(forKey: "translation")) ?? .web
        let t = translation
        DispatchQueue.global(qos: .userInitiated).async {
            let b = Bible.load(t)
            DispatchQueue.main.async {
                self.bible = b
                self.translationStatus = b.translation.title
                self.backdrop.animate(to: Palettes.forVerse(self.dailyIndex))
                self.applyLaunchScreen()
            }
        }
    }

    /// For automated screenshots only: `-startScreen read|shuffle|daily|books|chapters|settings`
    /// passed on launch opens that screen directly. Normal launches never set it.
    private func applyLaunchScreen() {
        guard let s = defaults.volatileDomain(forName: UserDefaults.argumentDomain)["startScreen"] as? String else { return }
        switch s {
        case "read": screen = .feed(.read, 0)
        case "shuffle": screen = .feed(.shuffle, 0)
        case "daily": screen = .feed(.daily, dailyIndex)
        case "books": screen = .books
        case "chapters": screen = .chapters(Bible.psalms)
        case "settings": settingsOpen = true
        default: break
        }
        screenID = UUID()
    }

    var ui: UIColors { UIColors.of(theme) }
    var feed: FeedColors { FeedColors.of(theme) }

    // MARK: navigation

    /// Changes screen with a push: the new screen slides fully in from `edge` while the old one
    /// slides fully out the other side, so the see-through screens never overlap.
    func go(_ s: Screen, from edge: Edge) {
        pushEdge = edge
        fadeTransition = false
        DispatchQueue.main.async {
            withAnimation(.snappy(duration: 0.32)) {
                self.screen = s
                self.screenID = UUID()
                // Leaving the feed: the verse counter slides out with it.
                if !s.isFeed { self.counterVerse = nil }
            }
        }
    }

    func selectTab(_ tab: Int) {
        guard bible != nil else { return }
        if settingsOpen { closeSettings() }
        let cur = screen.tab
        if tab == cur {
            if tab == 0 {
                if case .feed = screen { leaveDaily(toBottom: false) } else { homeScrollToTop += 1 }
            }
            return
        }
        let edge: Edge = tab > cur ? .trailing : .leading
        switch tab {
        case 0: go(.home, from: edge)
        case 1: go(.feed(.read, -1), from: edge)
        case 2: go(.feed(.shuffle, -1), from: edge)
        default:
            if case .feed(let m, _) = screen { browseReturn = .feed(m, -1) } else { browseReturn = .home }
            go(.books, from: edge)
        }
    }

    func back() {
        if settingsOpen { closeSettings(); return }
        switch screen {
        case .feed(let m, _):
            if m == .daily { leaveDaily(toBottom: true) } else { go(.home, from: .leading) }
        case .books: go(browseReturn, from: .leading)
        case .chapters: go(.books, from: .leading)
        case .verses(let b, _):
            if let bible, bible.chapterCount(b) == 1 { go(.books, from: .leading) } else { go(.chapters(b), from: .leading) }
        case .home: break
        }
    }

    func openDaily() { go(.feed(.daily, dailyIndex), from: .bottom) }

    func leaveDaily(toBottom: Bool) {
        homeScrollToBottom = toBottom
        go(.home, from: .top)
    }

    func openBook(_ b: Int) {
        guard let bible else { return }
        if bible.chapterCount(b) == 1 { go(.verses(b, 1), from: .trailing) } else { go(.chapters(b), from: .trailing) }
    }

    // MARK: data helpers

    var dailyIndex: Int {
        guard let list = bible?.daily, !list.isEmpty else { return 0 }
        let day = Int(floor(Date().timeIntervalSince1970 / 86_400))
        return list[((day % list.count) + list.count) % list.count]
    }

    var readPos: Int? {
        guard let bible, defaults.object(forKey: "read_pos") != nil else { return nil }
        let p = defaults.integer(forKey: "read_pos")
        return p >= 0 && p < bible.count ? p : nil
    }

    func shuffleOrder(for bible: Bible) -> [Int] {
        if let o = shuffleOrder, o.count == bible.count { return o }
        var seed = UInt64(bitPattern: Int64(defaults.integer(forKey: "shuffle_seed")))
        if seed == 0 {
            seed = UInt64.random(in: 1...UInt64.max)
            defaults.set(Int(Int64(bitPattern: seed)), forKey: "shuffle_seed")
        }
        var rng = SplitMix64(seed: seed)
        var order = Array(0..<bible.count)
        order.shuffle(using: &rng)
        shuffleOrder = order
        return order
    }

    func share(_ i: Int) {
        guard let bible else { return }
        let body = "\u{201C}\(bible.text[i])\u{201D}\n\u{2014} \(bible.ref(i)) (\(bible.translation.abbr))"
        let vc = UIActivityViewController(activityItems: [body], applicationActivities: nil)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.keyWindow?.rootViewController
        while let p = top?.presentedViewController { top = p }
        top?.present(vc, animated: true)
    }

    // MARK: settings

    func openSettings() {
        withAnimation(.spring(response: 0.34, dampingFraction: 0.78)) { settingsOpen = true }
    }

    func closeSettings() {
        withAnimation(.easeIn(duration: 0.18)) { settingsOpen = false }
    }

    /// The theme changes at the peak of a quick blur, then everything sharpens into the new look.
    func setTheme(_ t: ThemeStyle) {
        guard t != theme else { return }
        defaults.set(t.rawValue, forKey: "bg_style")
        withAnimation(.easeIn(duration: 0.12)) { themeBlur = 18 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
            self.theme = t
            withAnimation(.easeOut(duration: 0.34)) { self.themeBlur = 0 }
        }
    }

    /// Loads the new translation in the background; the reading spot is kept by book/chapter/verse.
    func setTranslation(_ t: Translation) {
        guard t != translation, let old = bible else { return }
        translation = t
        defaults.set(t.rawValue, forKey: "translation")
        translationGen += 1
        let gen = translationGen
        translationStatus = "Loading \(t.title)\u{2026}"
        let readPos = self.readPos
        DispatchQueue.global(qos: .userInitiated).async {
            let nb = Bible.load(t)
            DispatchQueue.main.async {
                guard gen == self.translationGen else { return }
                if let p = readPos {
                    self.defaults.set(nb.indexAtOrAfter(old.book[p], old.chapter[p], old.verse[p]), forKey: "read_pos")
                }
                self.defaults.set(0, forKey: "read_part")
                self.defaults.set(0, forKey: "shuffle_part")
                self.defaults.set(min(self.defaults.integer(forKey: "shuffle_pos"), nb.count - 1), forKey: "shuffle_pos")
                self.shuffleOrder = nil
                self.fadeTransition = true // the screen underneath crossfades to the new text
                withAnimation(.easeInOut(duration: 0.3)) {
                    self.bible = nb
                    self.screenID = UUID()
                }
                self.translationStatus = "\(nb.translation.title)  \u{2713}"
            }
        }
    }
}

/// Small, fast, seedable random generator (so Shuffle keeps the same order between launches).
struct SplitMix64: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
