import SwiftUI

@main
struct VerseFeedApp: App {
    @StateObject private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        ZStack {
            BackgroundView(backdrop: app.backdrop, style: app.theme)

            if let bible = app.bible {
                // No clipping here: it would follow the safe area and slice pages off under the
                // status bar. The window already clips the sliding screens.
                screen(bible)
                    .id(app.screenID)
                    .transition(.push(from: app.pushEdge))

                if !app.screen.isFeed { StatusFade(light: app.theme.isLight) }

                // The feed's verse counter sits outside the sliding screens, so it stays put when
                // you switch between Read, Shuffle and today's verse.
                if let v = app.counterVerse, app.screen.isFeed {
                    VStack {
                        VerseCounter(bible: bible, verseIndex: v, colors: app.feed, light: app.theme.isLight)
                            .padding(.top, 6)
                        Spacer()
                    }
                    .transition(.push(from: app.pushEdge))
                    .allowsHitTesting(false)
                }
            } else {
                ProgressView().tint(app.ui.text)
            }

            VStack {
                Spacer()
                BottomBar()
            }
            .ignoresSafeArea(.keyboard)

            if app.settingsOpen {
                SettingsOverlay()
                    .zIndex(2)
            }

            if app.uiTest { // test runs only: lines the recorded video up with the test's steps
                Button("SyncFlash") { app.flashForSync() }
                    .frame(width: 44, height: 44)
                    .opacity(0.02)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .padding(.top, 120)
                    .zIndex(3)
                if app.syncFlash {
                    Color(red: 1, green: 0, blue: 1).ignoresSafeArea().zIndex(4)
                }
            }
        }
        .preferredColorScheme(app.theme.isLight ? .light : .dark)
    }

    @ViewBuilder private func screen(_ bible: Bible) -> some View {
        switch app.screen {
        case .home:
            HomeScreen(bible: bible)
        case .feed(let mode, let start):
            FeedScreen(app: app, bible: bible, mode: mode, start: start)
        case .books:
            BooksScreen(bible: bible)
        case .chapters(let b):
            ChaptersScreen(bible: bible, book: b)
        case .verses(let b, let c):
            VersesScreen(bible: bible, book: b, chapter: c)
        }
    }
}

/// The universal bottom bar (TikTok-style): Home, Read, Shuffle, Go to. A glowing indicator glides
/// to the active tab and its icon pops.
struct BottomBar: View {
    @EnvironmentObject var app: AppState
    @Namespace private var ns

    /// Height the bar takes above the home indicator; screens keep their content clear of it.
    static let contentInset: CGFloat = 70

    private let icons = ["house.fill", "book.closed.fill", "shuffle", "magnifyingglass"]
    private let names = ["Home", "Read", "Shuffle", "Go to"]

    var body: some View {
        let active = app.screen.tab
        let c = app.ui
        let light = app.theme.isLight
        HStack(spacing: 0) {
            ForEach(0..<4, id: \.self) { i in
                Button { app.selectTab(i) } label: {
                    VStack(spacing: 0) {
                        ZStack {
                            if i == active {
                                Capsule()
                                    .fill(Accent.gradient)
                                    .frame(width: 32, height: 4)
                                    .matchedGeometryEffect(id: "indicator", in: ns)
                            } else {
                                Color.clear.frame(width: 32, height: 4)
                            }
                        }
                        .padding(.bottom, 9)
                        Image(systemName: icons[i])
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(i == active ? c.text : c.subtext)
                            .scaleEffect(i == active ? 1 : 0.9)
                            .frame(height: 36)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 4)
                    .padding(.bottom, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(BounceStyle())
                .accessibilityLabel(names[i])
            }
        }
        .animation(.spring(response: 0.32, dampingFraction: 0.62), value: active)
        .background {
            // Glass, tinted like the Android bar: deep dark glass, or bright frosted white.
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(light ? Color.white.opacity(0.55) : Color.black.opacity(0.5))
                .overlay(alignment: .top) { Rectangle().fill(c.stroke).frame(height: 0.5) }
                .ignoresSafeArea(edges: .bottom)
        }
    }
}

/// The settings card. It grows out of the gear (top right of Home) while the screen dims.
struct SettingsOverlay: View {
    @EnvironmentObject var app: AppState
    @State private var shown = false

    var body: some View {
        let c = app.ui
        ZStack(alignment: .topTrailing) {
            Color.black.opacity(0.4)
                .ignoresSafeArea()
                .onTapGesture { app.closeSettings() }
                .transition(.opacity)

            VStack(alignment: .leading, spacing: 0) {
                Text("Settings").font(.system(size: 20, weight: .bold)).foregroundStyle(c.text)
                    .popIn(shown, delay: 0.06)

                VStack(alignment: .leading, spacing: 0) {
                    label("Translation")
                    Segmented(options: Translation.allCases.map(\.abbr),
                              selected: app.translation.rawValue) { app.setTranslation(Translation(rawValue: $0) ?? .web) }
                    Text(app.translationStatus)
                        .font(.system(size: 13)).foregroundStyle(c.subtext)
                        .padding(.top, 8).padding(.leading, 4)
                        .transaction { $0.animation = nil } // changes in place, no ghosting
                }
                .popIn(shown, delay: 0.10)

                VStack(alignment: .leading, spacing: 0) {
                    label("Theme")
                    Segmented(options: ThemeStyle.allCases.map(\.label),
                              selected: app.theme.rawValue) { app.setTheme(ThemeStyle(rawValue: $0) ?? .colorful) }
                }
                .popIn(shown, delay: 0.145)
            }
            .onAppear { shown = true }
            .padding(.horizontal, 18).padding(.top, 16).padding(.bottom, 20)
            .frame(width: min(360, UIScreen.main.bounds.width - 24))
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(c.sheet))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(c.stroke, lineWidth: 1))
            .shadow(color: .black.opacity(0.25), radius: 24, y: 10)
            .padding(.top, 62)
            .padding(.trailing, 12)
            .transition(.scale(scale: 0.55, anchor: .topTrailing).combined(with: .opacity))
        }
    }

    private func label(_ s: String) -> some View {
        Text(s.uppercased())
            .font(.system(size: 12, weight: .bold)).tracking(1.2)
            .foregroundStyle(app.ui.subtext)
            .padding(.top, 18).padding(.bottom, 8).padding(.leading, 4)
    }
}

/// Equal-width options on a track, with a gradient highlight that glides to the option you tap.
struct Segmented: View {
    @EnvironmentObject var app: AppState
    let options: [String]
    let selected: Int
    let onSelect: (Int) -> Void
    @Namespace private var ns

    var body: some View {
        let c = app.ui
        HStack(spacing: 0) {
            ForEach(Array(options.enumerated()), id: \.offset) { i, title in
                Button {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { onSelect(i) }
                } label: {
                    Text(title)
                        .font(.system(size: 14, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(i == selected ? Color.white : c.text)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background {
                            if i == selected {
                                RoundedRectangle(cornerRadius: 11, style: .continuous)
                                    .fill(Accent.gradient)
                                    .matchedGeometryEffect(id: "highlight", in: ns)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(BounceStyle())
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 15, style: .continuous).fill(c.track))
    }
}
