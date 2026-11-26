import UIKit

/// Splits verses into pages of at most `maxLines` lines, measured with TextKit at the verse box's
/// text width. It measures a little narrower than the real box, so SwiftUI's own line breaking can
/// never need a seventh line.
final class Paginator {

    static let maxLines = 6
    static let fontSize: CGFloat = 24

    static var font: UIFont {
        let base = UIFont.systemFont(ofSize: fontSize)
        if let serif = base.fontDescriptor.withDesign(.serif) {
            return UIFont(descriptor: serif, size: fontSize)
        }
        return base
    }

    private let width: CGFloat
    private let font: UIFont
    private var partsCache: [Int: Int] = [:]

    init(textWidth: CGFloat) {
        width = max(40, textWidth - 8)
        font = Paginator.font
    }

    /// Number of pages verse `v` needs.
    func parts(_ v: Int, _ text: String) -> Int {
        if let p = partsCache[v] { return p }
        let lines = lineRanges(text).count
        let p = max(1, (lines + Paginator.maxLines - 1) / Paginator.maxLines)
        partsCache[v] = p
        return p
    }

    /// The text of page `part` of a verse.
    func chunk(_ text: String, part: Int) -> String {
        let lines = lineRanges(text)
        let first = part * Paginator.maxLines
        guard first < lines.count else { return text }
        let last = min(lines.count, first + Paginator.maxLines) - 1
        let start = lines[first].location
        let end = NSMaxRange(lines[last])
        let ns = text as NSString
        return ns.substring(with: NSRange(location: start, length: end - start))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func lineRanges(_ s: String) -> [NSRange] {
        let storage = NSTextStorage(string: s, attributes: [.font: font])
        let layout = NSLayoutManager()
        let container = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        layout.addTextContainer(container)
        storage.addLayoutManager(layout)
        layout.ensureLayout(for: container)
        var ranges: [NSRange] = []
        var glyph = 0
        let total = layout.numberOfGlyphs
        while glyph < total {
            var lineGlyphs = NSRange(location: 0, length: 0)
            layout.lineFragmentRect(forGlyphAt: glyph, effectiveRange: &lineGlyphs)
            if lineGlyphs.length == 0 { break }
            ranges.append(layout.characterRange(forGlyphRange: lineGlyphs, actualGlyphRange: nil))
            glyph = NSMaxRange(lineGlyphs)
        }
        if ranges.isEmpty { ranges.append(NSRange(location: 0, length: (s as NSString).length)) }
        return ranges
    }
}
