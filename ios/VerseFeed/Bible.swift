import Foundation

/// The two public-domain translations bundled with the app.
enum Translation: Int, CaseIterable, Identifiable {
    case web = 0
    case bsb = 1

    var id: Int { rawValue }
    var file: String { self == .web ? "web" : "bsb" }
    var abbr: String { self == .web ? "WEB" : "BSB" }
    var title: String { self == .web ? "World English Bible" : "Berean Standard Bible" }
}

/// One whole Bible translation, loaded from "<file>.txt" ("GEN 1:1 text" per line).
/// Verses the text leaves blank (e.g. Luke 17:36 in the WEB) are skipped.
final class Bible {

    static let names = [
        "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth",
        "1 Samuel", "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra",
        "Nehemiah", "Esther", "Job", "Psalms", "Proverbs", "Ecclesiastes", "Song of Solomon",
        "Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos",
        "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah",
        "Malachi",
        "Matthew", "Mark", "Luke", "John", "Acts", "Romans", "1 Corinthians", "2 Corinthians",
        "Galatians", "Ephesians", "Philippians", "Colossians", "1 Thessalonians",
        "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon", "Hebrews", "James",
        "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation",
    ]
    static let oldTestamentCount = 39
    static let psalms = 18

    let translation: Translation
    let book: [Int]
    let chapter: [Int]
    let verse: [Int]
    let text: [String]
    /// chapterStart[book][chapter - 1] = index of that chapter's first verse.
    let chapterStart: [[Int]]
    /// Verse indices for the verse of the day, in rotation order.
    let daily: [Int]

    var count: Int { text.count }

    private var searchText: [String]?
    private let searchLock = NSLock()

    // MARK: loading

    private static var cache: [Translation: Bible] = [:]
    private static let cacheLock = NSLock()

    /// Loads (once) and returns a translation. Safe to call off the main thread.
    static func load(_ t: Translation) -> Bible {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        if let b = cache[t] { return b }
        let b = Bible(translation: t)
        cache = [t: b] // keep only one translation in memory
        return b
    }

    private init(translation t: Translation) {
        translation = t
        var bk: [Int] = [], chs: [Int] = [], vs: [Int] = [], texts: [String] = []
        bk.reserveCapacity(31_200); chs.reserveCapacity(31_200); vs.reserveCapacity(31_200); texts.reserveCapacity(31_200)
        var codes: [String: Int] = [:]
        var b = -1
        var prevCode = ""

        let url = Bundle.main.url(forResource: t.file, withExtension: "txt")
        let content = url.flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
        for raw in content.split(whereSeparator: { $0 == "\n" || $0 == "\r\n" || $0 == "\r" }) {
            var line = Substring(raw)
            if line.first == "\u{FEFF}" { line = line.dropFirst() }
            guard let sp = line.firstIndex(of: " ") else { continue }
            let code = String(line[..<sp])
            let rest = line[line.index(after: sp)...]
            guard let colon = rest.firstIndex(of: ":"), let ch = Int(rest[..<colon]) else { continue }
            // The verse number is the run of digits after the colon; anything glued to it is text.
            var i = rest.index(after: colon)
            var v = 0
            var digits = 0
            while i < rest.endIndex, rest[i].isASCII, let d = rest[i].wholeNumberValue {
                v = v * 10 + d
                digits += 1
                i = rest.index(after: i)
            }
            guard digits > 0, code.count >= 2, code.count <= 3 else { continue }
            if code != prevCode {
                b += 1
                prevCode = code
                codes[code] = b
            }
            let verseText = rest[i...].trimmingCharacters(in: .whitespaces)
            if verseText.isEmpty { continue }
            bk.append(b); chs.append(ch); vs.append(v); texts.append(verseText)
        }
        book = bk; chapter = chs; verse = vs; text = texts

        var starts = Array(repeating: [Int](), count: Bible.names.count)
        for idx in 0..<texts.count {
            let bb = bk[idx], c = chs[idx]
            guard bb < starts.count else { continue }
            while starts[bb].count < c { starts[bb].append(-1) }
            if starts[bb][c - 1] < 0 { starts[bb][c - 1] = idx }
        }
        chapterStart = starts

        // Verse of the day list ("JHN 3:16" per line).
        var found: [Int] = []
        if let durl = Bundle.main.url(forResource: "daily", withExtension: "txt"),
           let dl = try? String(contentsOf: durl, encoding: .utf8) {
            for raw in dl.split(whereSeparator: { $0 == "\n" || $0 == "\r\n" || $0 == "\r" }) {
                let line = raw.trimmingCharacters(in: .whitespaces)
                if line.isEmpty || line.hasPrefix("#") { continue }
                let parts = line.split(separator: " ")
                guard parts.count == 2, let bi = codes[String(parts[0])] else { continue }
                let cv = parts[1].split(separator: ":")
                guard cv.count == 2, let c = Int(cv[0]), let v = Int(cv[1]) else { continue }
                if let idx = Bible.indexOf(bi, c, v, starts: starts, verse: vs, count: texts.count) { found.append(idx) }
            }
        }
        daily = found.isEmpty ? [0] : found
    }

    // MARK: lookups

    func chapterCount(_ b: Int) -> Int { chapterStart[b].count }

    /// Index just past the last verse of the chapter.
    func chapterEnd(_ b: Int, _ ch: Int) -> Int {
        if ch < chapterStart[b].count { return chapterStart[b][ch] }
        if b + 1 < Bible.names.count { return chapterStart[b + 1][0] }
        return count
    }

    func lastVerseNumber(_ b: Int, _ ch: Int) -> Int { verse[chapterEnd(b, ch) - 1] }

    func indexOf(_ b: Int, _ ch: Int, _ v: Int) -> Int? {
        Bible.indexOf(b, ch, v, starts: chapterStart, verse: verse, count: count)
    }

    private static func indexOf(_ b: Int, _ ch: Int, _ v: Int, starts: [[Int]], verse: [Int], count: Int) -> Int? {
        guard b >= 0, b < starts.count, ch >= 1, ch <= starts[b].count else { return nil }
        let start = starts[b][ch - 1]
        let end: Int
        if ch < starts[b].count { end = starts[b][ch] }
        else if b + 1 < starts.count, !starts[b + 1].isEmpty { end = starts[b + 1][0] }
        else { end = count }
        guard start >= 0 else { return nil }
        for i in start..<end where verse[i] == v { return i }
        return nil
    }

    /// The verse, or the next one that exists (translations differ on a few verses).
    func indexAtOrAfter(_ b: Int, _ ch: Int, _ v: Int) -> Int {
        guard b >= 0, b < Bible.names.count else { return 0 }
        let c = max(1, min(ch, chapterStart[b].count))
        let end = chapterEnd(b, c)
        for i in chapterStart[b][c - 1]..<end where verse[i] >= v { return i }
        return min(end, count - 1)
    }

    func bookName(_ b: Int, singular: Bool = true) -> String {
        singular && b == Bible.psalms ? "Psalm" : Bible.names[b]
    }

    func ref(_ i: Int) -> String { "\(bookName(book[i])) \(chapter[i]):\(verse[i])" }

    // MARK: search

    /// Verses containing `needle` (already normalized). Returns the first `limit` matches in Bible
    /// order and how many verses match in total. Safe to call off the main thread.
    func search(_ needle: String, limit: Int = 60) -> (matches: [Int], total: Int) {
        searchLock.lock()
        if searchText == nil { searchText = text.map(RefSearch.normalize) }
        let t = searchText!
        searchLock.unlock()
        var out: [Int] = []
        var total = 0
        for i in 0..<t.count where t[i].contains(needle) {
            if out.count < limit { out.append(i) }
            total += 1
        }
        return (out, total)
    }
}
