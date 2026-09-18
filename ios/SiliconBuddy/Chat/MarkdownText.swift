import SwiftUI

/// Enough Markdown for a model's answer, and no more.
///
/// `AttributedString(markdown:)` handles everything inline — bold, code spans, links —
/// but it flattens block structure, so a fenced code block comes out as one long
/// paragraph with the backticks still in it. This splits the answer into blocks first
/// and lets AttributedString do the inline work inside each one. Small, and it means a
/// streaming answer can be re-rendered on every token without dragging in a parser.
public enum Markdown {
    public enum Block: Equatable, Identifiable, Sendable {
        case paragraph(String)
        case heading(level: Int, text: String)
        case bullet(items: [String])
        case numbered(items: [String])
        case code(language: String?, text: String)
        case quote(String)
        case rule

        public var id: String {
            switch self {
            case .paragraph(let text): "p:\(text.hashValue)"
            case .heading(let level, let text): "h\(level):\(text.hashValue)"
            case .bullet(let items): "ul:\(items.joined().hashValue)"
            case .numbered(let items): "ol:\(items.joined().hashValue)"
            case .code(let language, let text): "code:\(language ?? "")\(text.hashValue)"
            case .quote(let text): "q:\(text.hashValue)"
            case .rule: "hr"
            }
        }
    }

    /// Splits an answer into blocks. Unterminated fences are treated as code to the end,
    /// which is what a half-streamed answer looks like.
    public static func blocks(from markdown: String) -> [Block] {
        var blocks: [Block] = []
        var paragraph: [String] = []
        var bullets: [String] = []
        var numbers: [String] = []
        var quote: [String] = []
        var codeLines: [String] = []
        var codeLanguage: String?
        var inCode = false

        func flushParagraph() {
            guard !paragraph.isEmpty else { return }
            blocks.append(.paragraph(paragraph.joined(separator: "\n")))
            paragraph = []
        }
        func flushBullets() {
            guard !bullets.isEmpty else { return }
            blocks.append(.bullet(items: bullets))
            bullets = []
        }
        func flushNumbers() {
            guard !numbers.isEmpty else { return }
            blocks.append(.numbered(items: numbers))
            numbers = []
        }
        func flushQuote() {
            guard !quote.isEmpty else { return }
            blocks.append(.quote(quote.joined(separator: "\n")))
            quote = []
        }
        func flushAll() {
            flushParagraph()
            flushBullets()
            flushNumbers()
            flushQuote()
        }

        for rawLine in markdown.components(separatedBy: .newlines) {
            let line = rawLine
            let trimmed = line.trimmingCharacters(in: .whitespaces)

            if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                if inCode {
                    blocks.append(.code(language: codeLanguage, text: codeLines.joined(separator: "\n")))
                    codeLines = []
                    codeLanguage = nil
                    inCode = false
                } else {
                    flushAll()
                    let fence = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                    codeLanguage = fence.isEmpty ? nil : fence
                    inCode = true
                }
                continue
            }
            if inCode {
                codeLines.append(line)
                continue
            }

            if trimmed.isEmpty {
                flushAll()
                continue
            }
            if trimmed == "---" || trimmed == "***" || trimmed == "___" {
                flushAll()
                blocks.append(.rule)
                continue
            }
            if trimmed.hasPrefix("#") {
                let hashes = trimmed.prefix { $0 == "#" }.count
                if hashes <= 6, trimmed.dropFirst(hashes).hasPrefix(" ") {
                    flushAll()
                    blocks.append(.heading(
                        level: hashes,
                        text: String(trimmed.dropFirst(hashes)).trimmingCharacters(in: .whitespaces)
                    ))
                    continue
                }
            }
            if trimmed.hasPrefix("> ") || trimmed == ">" {
                flushParagraph(); flushBullets(); flushNumbers()
                quote.append(String(trimmed.dropFirst(trimmed.hasPrefix("> ") ? 2 : 1)))
                continue
            }
            if let bullet = bulletBody(trimmed) {
                flushParagraph(); flushNumbers(); flushQuote()
                bullets.append(bullet)
                continue
            }
            if let (_, body) = numberedBody(trimmed) {
                flushParagraph(); flushBullets(); flushQuote()
                numbers.append(body)
                continue
            }
            flushBullets(); flushNumbers(); flushQuote()
            paragraph.append(line)
        }

        if inCode, !codeLines.isEmpty {
            blocks.append(.code(language: codeLanguage, text: codeLines.joined(separator: "\n")))
        }
        flushAll()
        return blocks
    }

    private static func bulletBody(_ line: String) -> String? {
        for marker in ["- ", "* ", "• ", "+ "] where line.hasPrefix(marker) {
            return String(line.dropFirst(marker.count))
        }
        return nil
    }

    private static func numberedBody(_ line: String) -> (Int, String)? {
        let digits = line.prefix { $0.isNumber }
        guard !digits.isEmpty, let number = Int(digits) else { return nil }
        let rest = line.dropFirst(digits.count)
        guard rest.hasPrefix(". ") || rest.hasPrefix(") ") else { return nil }
        return (number, String(rest.dropFirst(2)))
    }

    /// Inline markup, with the source text as the fallback when it does not parse.
    public static func inline(_ text: String) -> AttributedString {
        (try? AttributedString(
            markdown: text,
            options: .init(
                allowsExtendedAttributes: true,
                interpretedSyntax: .inlineOnlyPreservingWhitespace,
                failurePolicy: .returnPartiallyParsedIfPossible
            )
        )) ?? AttributedString(text)
    }
}

/// Renders an answer. Text stays selectable, because half the point of asking from a
/// phone is copying the answer somewhere else.
public struct MarkdownText: View {
    let markdown: String

    public init(_ markdown: String) { self.markdown = markdown }

    public var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Markdown.blocks(from: markdown)) { block in
                switch block {
                case .paragraph(let text):
                    Text(Markdown.inline(text))
                        .textSelection(.enabled)
                case .heading(let level, let text):
                    Text(Markdown.inline(text))
                        .font(headingFont(level))
                        .textSelection(.enabled)
                case .bullet(let items):
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text("•").foregroundStyle(.secondary)
                                Text(Markdown.inline(item))
                            }
                        }
                    }
                    .textSelection(.enabled)
                case .numbered(let items):
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text("\(index + 1).")
                                    .foregroundStyle(.secondary)
                                    .monospacedDigit()
                                Text(Markdown.inline(item))
                            }
                        }
                    }
                    .textSelection(.enabled)
                case .code(let language, let text):
                    CodeBlock(language: language, text: text)
                case .quote(let text):
                    HStack(spacing: 8) {
                        Rectangle()
                            .fill(.tertiary)
                            .frame(width: 3)
                        Text(Markdown.inline(text))
                            .foregroundStyle(.secondary)
                    }
                    .textSelection(.enabled)
                case .rule:
                    Divider()
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func headingFont(_ level: Int) -> Font {
        switch level {
        case 1: .title2.weight(.semibold)
        case 2: .title3.weight(.semibold)
        case 3: .headline
        default: .subheadline.weight(.semibold)
        }
    }
}

struct CodeBlock: View {
    let language: String?
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let language, !language.isEmpty {
                Text(language)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                Text(text)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(10)
            }
            .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Code block\(language.map { " in \($0)" } ?? "")")
        .accessibilityValue(text)
    }
}
