import SwiftUI

/// Header + scrolling content shared by the Books, Chapters and Verses screens.
private struct BrowseScaffold<Content: View>: View {
    @EnvironmentObject var app: AppState
    let title: String
    let subtitle: String?
    let showBack: Bool
    @ViewBuilder let content: () -> Content

    var body: some View {
        let c = app.ui
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    if showBack {
                        Button { app.back() } label: {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(c.text)
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel("Back")
                    }
                    Text(title).font(.system(size: 26, weight: .bold)).foregroundStyle(c.text)
                }
                if let subtitle {
                    Text(subtitle).font(.system(size: 14)).foregroundStyle(c.subtext)
                        .padding(.leading, showBack ? 48 : 0).padding(.bottom, 10)
                }
                content()
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            .padding(.bottom, 100)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
    }
}

/// A grid of tiles that pop in one after another, row by row.
private struct TileGrid: View {
    @EnvironmentObject var app: AppState
    let labels: [String]
    let columns: Int
    let indexOffset: Int
    let onTap: (Int) -> Void
    @State private var shown = false

    var body: some View {
        let c = app.ui
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: columns), spacing: 8) {
            ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                Button { onTap(i) } label: {
                    Text(label)
                        .font(.system(size: 15))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(c.text)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(c.surface))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(c.stroke, lineWidth: 1))
                }
                .buttonStyle(BounceStyle())
                .opacity(shown ? 1 : 0)
                .scaleEffect(shown ? 1 : 0.94)
                .offset(y: shown ? 0 : 12)
                .animation(.easeOut(duration: 0.22).delay(0.07 + Double(min(i + indexOffset, 28)) * 0.012), value: shown)
            }
        }
        .onAppear { shown = true }
    }
}

private struct SectionLabel: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 12, weight: .bold)).tracking(1.2)
            .foregroundStyle(color)
            .padding(.top, 18).padding(.bottom, 8).padding(.leading, 4)
    }
}

struct BooksScreen: View {
    @EnvironmentObject var app: AppState
    let bible: Bible
    @State private var query = ""
    @State private var textHits: [Int] = []
    @State private var textTotal = 0
    @State private var searching = false
    @State private var searchGen = 0
    @FocusState private var focused: Bool

    var body: some View {
        let c = app.ui
        BrowseScaffold(title: "Books", subtitle: nil, showBack: false) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(c.subtext)
                TextField("", text: $query, prompt: Text("Search: John 3:16, Ps 23, or words").foregroundStyle(c.subtext))
                    .foregroundStyle(c.text)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.search)
                    .focused($focused)
                    .onSubmit(openFirst)
                if !query.isEmpty {
                    Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(c.subtext) }
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.surface))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(c.stroke, lineWidth: 1))
            .padding(.top, 8)

            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                SectionLabel(text: "Old Testament", color: c.subtext)
                TileGrid(labels: Array(Bible.names[0..<Bible.oldTestamentCount]), columns: 2, indexOffset: 0) { app.openBook($0) }
                SectionLabel(text: "New Testament", color: c.subtext)
                TileGrid(labels: Array(Bible.names[Bible.oldTestamentCount...]), columns: 2, indexOffset: 20) {
                    app.openBook(Bible.oldTestamentCount + $0)
                }
            } else {
                results
            }
        }
        .onChange(of: query) { _, q in runTextSearch(q) }
    }

    private var refHits: [RefSearch.Hit] { RefSearch.references(bible, query) }

    @ViewBuilder private var results: some View {
        let c = app.ui
        let hits = refHits
        VStack(alignment: .leading, spacing: 8) {
            ForEach(hits) { h in
                resultRow(h.title, AttributedString(h.subtitle), lines: h.kind == .book ? 1 : 3) { open(h) }
            }
            let needle = RefSearch.normalize(query.trimmingCharacters(in: .whitespaces))
            if RefSearch.letterCount(needle) >= 3 {
                if searching {
                    Text("Searching verses\u{2026}").font(.system(size: 13)).foregroundStyle(c.subtext).padding(.top, 8)
                } else if textTotal > 0 {
                    Text("VERSES \u{00B7} \(textTotal > textHits.count ? "\(textHits.count)+" : "\(textTotal)") \(textTotal == 1 ? "match" : "matches")")
                        .font(.system(size: 13, weight: .bold)).tracking(0.8)
                        .foregroundStyle(c.subtext).padding(.top, 10)
                    ForEach(textHits, id: \.self) { idx in
                        resultRow(bible.ref(idx), highlight(bible.text[idx], needle), lines: 3) {
                            app.go(.feed(.read, idx), from: .trailing)
                        }
                    }
                } else if hits.isEmpty {
                    Text("No matches").font(.system(size: 13)).foregroundStyle(c.subtext).padding(.top, 8)
                }
            } else if hits.isEmpty {
                Text("No matches").font(.system(size: 13)).foregroundStyle(c.subtext).padding(.top, 8)
            }
        }
        .padding(.top, 10)
    }

    private func open(_ h: RefSearch.Hit) {
        focused = false
        if h.kind == .book { app.openBook(h.book) } else { app.go(.feed(.read, h.index), from: .trailing) }
    }

    private func openFirst() {
        if let h = refHits.first { open(h) }
        else if let idx = textHits.first { app.go(.feed(.read, idx), from: .trailing) }
    }

    private func runTextSearch(_ q: String) {
        searchGen += 1
        let gen = searchGen
        let needle = RefSearch.normalize(q.trimmingCharacters(in: .whitespaces))
        guard RefSearch.letterCount(needle) >= 3 else {
            textHits = []
            textTotal = 0
            searching = false
            return
        }
        searching = true
        let b = bible
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            guard gen == searchGen else { return } // still typing
            DispatchQueue.global(qos: .userInitiated).async {
                let r = b.search(needle)
                DispatchQueue.main.async {
                    guard gen == searchGen else { return }
                    withAnimation(.easeOut(duration: 0.2)) {
                        textHits = r.matches
                        textTotal = r.total
                        searching = false
                    }
                }
            }
        }
    }

    /// Verse text with every occurrence of the search in bold.
    private func highlight(_ text: String, _ needle: String) -> AttributedString {
        var a = AttributedString(text)
        let norm = RefSearch.normalize(text)
        guard norm.count == text.count, !needle.isEmpty else { return a }
        var searchStart = norm.startIndex
        while let r = norm.range(of: needle, range: searchStart..<norm.endIndex) {
            let lo = norm.distance(from: norm.startIndex, to: r.lowerBound)
            let len = norm.distance(from: r.lowerBound, to: r.upperBound)
            let s = a.index(a.startIndex, offsetByCharacters: lo)
            let e = a.index(s, offsetByCharacters: len)
            a[s..<e].font = .system(size: 14, weight: .bold)
            a[s..<e].foregroundColor = app.ui.text
            searchStart = r.upperBound
        }
        return a
    }

    private func resultRow(_ title: String, _ subtitle: AttributedString, lines: Int, action: @escaping () -> Void) -> some View {
        let c = app.ui
        return Button(action: action) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.system(size: 16, weight: .bold)).foregroundStyle(c.text)
                Text(subtitle).font(.system(size: 14)).foregroundStyle(c.subtext).lineLimit(lines)
                    .multilineTextAlignment(.leading)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.surface))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(c.stroke, lineWidth: 1))
        }
        .buttonStyle(BounceStyle())
        .transition(.opacity.combined(with: .offset(y: 8)))
    }
}

struct ChaptersScreen: View {
    @EnvironmentObject var app: AppState
    let bible: Bible
    let book: Int

    var body: some View {
        BrowseScaffold(title: Bible.names[book], subtitle: "Choose a chapter", showBack: true) {
            TileGrid(labels: (1...bible.chapterCount(book)).map(String.init), columns: 5, indexOffset: 0) {
                app.go(.verses(book, $0 + 1), from: .trailing)
            }
        }
    }
}

struct VersesScreen: View {
    @EnvironmentObject var app: AppState
    let bible: Bible
    let book: Int
    let chapter: Int

    var body: some View {
        let start = bible.chapterStart[book][chapter - 1]
        let end = bible.chapterEnd(book, chapter)
        let title = bible.chapterCount(book) == 1 ? Bible.names[book] : "\(bible.bookName(book)) \(chapter)"
        BrowseScaffold(title: title, subtitle: "Choose a verse to start scrolling from", showBack: true) {
            TileGrid(labels: (start..<end).map { String(bible.verse[$0]) }, columns: 5, indexOffset: 0) {
                app.go(.feed(.read, start + $0), from: .trailing)
            }
        }
    }
}
