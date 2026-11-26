import Foundation

/// Turns what you type into Bible references, YouVersion-style: "john", "jn 3", "1 cor 13:4",
/// "ps 23.1", "song 2", "jude 5". Book names match by prefix plus common abbreviations.
enum RefSearch {

    enum Kind { case book, chapter, verse }

    struct Hit: Identifiable {
        let id = UUID()
        let kind: Kind
        let book: Int
        let index: Int
        let title: String
        let subtitle: String
    }

    /// Abbreviations that are not simply the start of the book's name.
    private static let alias: [String: String] = [
        "gn": "genesis", "lv": "leviticus", "nm": "numbers", "dt": "deuteronomy",
        "jdg": "judges", "jgs": "judges", "rth": "ruth", "sm": "samuel", "sa": "samuel",
        "kgs": "kings", "ki": "kings", "chr": "chronicles", "ch": "chronicles",
        "jb": "job", "ps": "psalms", "psa": "psalms", "pss": "psalms", "psm": "psalms",
        "pr": "proverbs", "prv": "proverbs", "qoh": "ecclesiastes",
        "sos": "songofsolomon", "sng": "songofsolomon", "canticles": "songofsolomon",
        "songofsongs": "songofsolomon", "dn": "daniel", "jl": "joel", "ob": "obadiah",
        "zp": "zephaniah", "zc": "zechariah", "mt": "matthew", "mtt": "matthew",
        "mk": "mark", "mrk": "mark", "lk": "luke", "jn": "john", "jhn": "john",
        "rm": "romans", "co": "corinthians", "th": "thessalonians", "ti": "timothy",
        "tm": "timothy", "php": "philippians", "phm": "philemon", "hb": "hebrews",
        "jas": "james", "jm": "james", "pe": "peter", "pt": "peter", "jd": "jude",
        "rv": "revelation",
    ]

    private static let keys: [String] = Bible.names.map(squash)

    // [1-3] book-name [chapter [ :|.|,|space verse]]
    private static let pattern = try! NSRegularExpression(
        pattern: "^([1-3])?\\s*([a-z][a-z .]*?)\\.?\\s*(?:(\\d{1,3})(?:\\s*[:.,\\s]\\s*(\\d{1,3}))?)?$")

    /// Lower-case text with curly quotes made straight (same length as the input).
    static func normalize(_ s: String) -> String {
        s.lowercased()
            .replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "\u{2018}", with: "'")
            .replacingOccurrences(of: "\u{201C}", with: "\"")
            .replacingOccurrences(of: "\u{201D}", with: "\"")
    }

    static func letterCount(_ s: String) -> Int { s.filter(\.isLetter).count }

    private static func squash(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: " ", with: "").replacingOccurrences(of: ".", with: "")
    }

    /// Books whose name matches the typed number + name, best matches first.
    static func matchBooks(num: String?, name: String) -> [Int] {
        let rest = squash(name)
        guard !rest.isEmpty else { return [] }
        let al = alias[rest]
        var exact: [Int] = [], prefix: [Int] = []
        for (b, key) in keys.enumerated() {
            let numbered = key.first?.isNumber ?? false
            let bare = numbered ? String(key.dropFirst()) : key
            if let n = num, !(numbered && key.first.map(String.init) == n) { continue }
            if bare == rest || (al != nil && bare == al!) { exact.append(b) }
            else if bare.hasPrefix(rest) { prefix.append(b) }
        }
        return exact + prefix
    }

    static func references(_ bible: Bible, _ query: String) -> [Hit] {
        let q = query.lowercased().trimmingCharacters(in: .whitespaces)
        let ns = q as NSString
        guard let m = pattern.firstMatch(in: q, range: NSRange(location: 0, length: ns.length)) else { return [] }
        func group(_ i: Int) -> String? {
            let r = m.range(at: i)
            return r.location == NSNotFound ? nil : ns.substring(with: r)
        }
        let num = group(1)
        let ch = group(3).flatMap { Int($0) }
        let v = group(4).flatMap { Int($0) }
        let books = matchBooks(num: num, name: group(2) ?? "")
        var hits: [Hit] = []
        let limit = ch == nil ? 8 : 5
        for b in books {
            if hits.count >= limit { break }
            let chapters = bible.chapterCount(b)
            guard let c0 = ch else {
                hits.append(Hit(kind: .book, book: b, index: -1, title: Bible.names[b],
                                subtitle: chapters == 1 ? "1 chapter" : "\(chapters) chapters"))
                continue
            }
            var c = c0
            var vs = v
            if chapters == 1 && v == nil { vs = c; c = 1 } // "jude 5" means verse 5
            guard c >= 1, c <= chapters else { continue }
            if let vv = vs {
                guard let idx = bible.indexOf(b, c, vv) else { continue }
                let title = chapters == 1 ? "\(Bible.names[b]) \(vv)" : "\(bible.bookName(b)) \(c):\(vv)"
                hits.append(Hit(kind: .verse, book: b, index: idx, title: title, subtitle: bible.text[idx]))
            } else {
                let first = bible.chapterStart[b][c - 1]
                hits.append(Hit(kind: .chapter, book: b, index: first, title: "\(bible.bookName(b)) \(c)",
                                subtitle: bible.text[first]))
            }
        }
        return hits
    }
}
