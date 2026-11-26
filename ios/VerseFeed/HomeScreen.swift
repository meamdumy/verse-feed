import SwiftUI

private struct BottomEdgeKey: PreferenceKey {
    static var defaultValue: CGFloat = .infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

struct HomeScreen: View {
    @EnvironmentObject var app: AppState
    let bible: Bible
    @State private var pull: CGFloat = 0
    @State private var opened = false
    @State private var bob = false

    private let trigger: CGFloat = 90

    var body: some View {
        let c = app.ui
        let daily = app.dailyIndex
        GeometryReader { outer in
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(Date.now.formatted(.dateTime.weekday(.wide).month(.wide).day()).uppercased())
                            .font(.system(size: 12, weight: .bold)).tracking(1)
                            .foregroundStyle(c.subtext)
                            .padding(.top, 8)
                            .id("top")
                        HStack {
                            Text("Verse Feed").font(.system(size: 32, weight: .bold)).foregroundStyle(c.text)
                            Spacer()
                            Button { app.settingsOpen ? app.closeSettings() : app.openSettings() } label: {
                                Image(systemName: "gearshape.fill")
                                    .font(.system(size: 19, weight: .semibold))
                                    .foregroundStyle(c.text)
                                    .rotationEffect(.degrees(app.settingsOpen ? 90 : 0))
                                    .frame(width: 44, height: 44)
                                    .background(Circle().fill(c.surface))
                                    .overlay(Circle().stroke(c.stroke, lineWidth: 1))
                            }
                            .buttonStyle(BounceStyle())
                            .accessibilityLabel("Settings")
                        }
                        .padding(.bottom, 8)

                        verseOfTheDay(daily)
                            .padding(.bottom, 10)

                        if let p = app.readPos {
                            row("Continue reading", bible.ref(p)) { app.go(.feed(.read, p), from: .trailing) }
                        } else {
                            row("Start reading", "Genesis 1:1, one verse at a time") { app.go(.feed(.read, 0), from: .trailing) }
                        }
                        row("Shuffle", "An endless feed of random verses") { app.selectTab(2) }
                        row("Browse", "Jump to any book, chapter, or verse") { app.selectTab(3) }

                        Spacer(minLength: 28)

                        // Keep swiping up past the bottom to read today's verse in context.
                        VStack(spacing: 2) {
                            Image(systemName: "chevron.up")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(Accent.b)
                                .offset(y: bob ? -6 : 0)
                                // The endless bob is scoped to the arrow alone; started with a
                                // global withAnimation it also caught the screen's transitions.
                                .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: bob)
                            Text(pull >= trigger ? "Release to open today\u{2019}s verse" : "Swipe up to read today\u{2019}s verse in context")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(pull >= trigger ? c.text : c.subtext)
                        }
                        .scaleEffect(1 + 0.08 * min(1, pull / trigger))
                        .frame(maxWidth: .infinity)
                        Text("\(bible.translation.title) (\(bible.translation.abbr)) \u{00B7} Public domain \u{00B7} eBible.org")
                            .font(.system(size: 12))
                            .foregroundStyle(c.subtext)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)
                            .padding(.top, 6)
                        Color.clear.frame(height: 1)
                            .background(GeometryReader { g in
                                Color.clear.preference(key: BottomEdgeKey.self, value: g.frame(in: .named("home")).maxY)
                            })
                            .id("bottom")
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, BottomBar.contentInset + 12) // room for the bottom bar
                    .frame(minHeight: outer.size.height, alignment: .top)
                }
                .scrollBounceBehavior(.always)
                .scrollIndicators(.hidden)
                .coordinateSpace(name: "home")
                .onPreferenceChange(BottomEdgeKey.self) { maxY in
                    // Past the bottom, iOS lets the page bounce up: that overshoot is the pull.
                    let over = max(0, (outer.size.height - BottomBar.contentInset - 12) - maxY)
                    if (over >= trigger) != (pull >= trigger) {
                        UISelectionFeedbackGenerator().selectionChanged() // tick when it's far enough
                    }
                    pull = over
                }
                // Like Android: it opens when you let go, if you pulled far enough.
                .simultaneousGesture(DragGesture(minimumDistance: 12).onEnded { _ in
                    if pull >= trigger && !opened {
                        opened = true
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        app.openDaily()
                    }
                })
                .onChange(of: app.homeScrollToTop) { _, _ in
                    withAnimation(.snappy) { proxy.scrollTo("top", anchor: .top) }
                }
                .onAppear {
                    if app.homeScrollToBottom {
                        app.homeScrollToBottom = false
                        proxy.scrollTo("bottom", anchor: .bottom)
                    }
                    if !UIAccessibility.isReduceMotionEnabled {
                        DispatchQueue.main.async { bob = true } // after the screen's transition starts
                    }
                    app.backdrop.animate(to: Palettes.forVerse(daily)) // Colorful: today's verse's colours
                }
            }
        }
    }

    private func verseOfTheDay(_ i: Int) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("VERSE OF THE DAY")
                .font(.system(size: 12, weight: .bold)).tracking(1.5)
                .foregroundStyle(Color.white.opacity(0.8))
            Text(bible.text[i])
                .font(.system(size: 21, design: .serif))
                .lineSpacing(4)
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
            Text(bible.ref(i)).font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
            HStack(spacing: 8) {
                cardPill("Read in context") { app.openDaily() }
                cardPill("Share") { app.share(i) }
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Accent.gradient))
    }

    private func cardPill(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 16).padding(.vertical, 9)
                .background(Capsule().fill(Color.white.opacity(0.2)))
        }
        .buttonStyle(BounceStyle())
    }

    private func row(_ title: String, _ subtitle: String, action: @escaping () -> Void) -> some View {
        let c = app.ui
        return Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 17, weight: .bold)).foregroundStyle(c.text)
                Text(subtitle).font(.system(size: 14)).foregroundStyle(c.subtext)
            }
            .padding(.horizontal, 18).padding(.vertical, 15)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.surface))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(c.stroke, lineWidth: 1))
        }
        .buttonStyle(BounceStyle())
    }
}
