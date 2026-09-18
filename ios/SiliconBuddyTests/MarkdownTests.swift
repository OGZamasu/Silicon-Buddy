import XCTest
@testable import SiliconBuddy

final class MarkdownTests: XCTestCase {

    func testAPlainParagraph() {
        XCTAssertEqual(Markdown.blocks(from: "Hello there."), [.paragraph("Hello there.")])
    }

    func testBlankLinesSeparateParagraphs() {
        let blocks = Markdown.blocks(from: "One.\n\nTwo.")
        XCTAssertEqual(blocks, [.paragraph("One."), .paragraph("Two.")])
    }

    func testHeadings() {
        let blocks = Markdown.blocks(from: "# Title\n## Subtitle\nBody")
        XCTAssertEqual(blocks, [
            .heading(level: 1, text: "Title"),
            .heading(level: 2, text: "Subtitle"),
            .paragraph("Body"),
        ])
    }

    func testAHashWithoutASpaceIsNotAHeading() {
        XCTAssertEqual(Markdown.blocks(from: "#hashtag"), [.paragraph("#hashtag")])
    }

    func testBulletsGroupIntoOneList() {
        let blocks = Markdown.blocks(from: "- one\n- two\n* three")
        XCTAssertEqual(blocks, [.bullet(items: ["one", "two", "three"])])
    }

    func testNumberedLists() {
        let blocks = Markdown.blocks(from: "1. first\n2. second")
        XCTAssertEqual(blocks, [.numbered(items: ["first", "second"])])
    }

    func testFencedCodeKeepsItsIndentationAndLanguage() {
        let source = """
        Try this:

        ```swift
        let x = 1
            let y = 2
        ```

        Done.
        """
        let blocks = Markdown.blocks(from: source)
        XCTAssertEqual(blocks, [
            .paragraph("Try this:"),
            .code(language: "swift", text: "let x = 1\n    let y = 2"),
            .paragraph("Done."),
        ])
    }

    func testAnUnclosedFenceIsStillCode() {
        // Exactly what a half-streamed answer looks like.
        let blocks = Markdown.blocks(from: "```python\nprint(1)")
        XCTAssertEqual(blocks, [.code(language: "python", text: "print(1)")])
    }

    func testCodeWithoutALanguage() {
        XCTAssertEqual(
            Markdown.blocks(from: "```\nraw\n```"),
            [.code(language: nil, text: "raw")]
        )
    }

    func testMarkersInsideACodeBlockAreNotParsed() {
        let blocks = Markdown.blocks(from: "```\n# not a heading\n- not a bullet\n```")
        XCTAssertEqual(blocks, [.code(language: nil, text: "# not a heading\n- not a bullet")])
    }

    func testQuotesAndRules() {
        let blocks = Markdown.blocks(from: "> quoted\n\n---\n\ntext")
        XCTAssertEqual(blocks, [.quote("quoted"), .rule, .paragraph("text")])
    }

    func testEmptyInputIsNoBlocks() {
        XCTAssertTrue(Markdown.blocks(from: "").isEmpty)
        XCTAssertTrue(Markdown.blocks(from: "\n\n").isEmpty)
    }

    func testInlineMarkupIsParsed() {
        let attributed = Markdown.inline("**bold** and `code`")
        XCTAssertFalse(String(attributed.characters).contains("**"))
        XCTAssertEqual(String(attributed.characters), "bold and code")
    }

    func testBrokenInlineMarkupFallsBackToTheText() {
        let text = "unclosed **bold"
        XCTAssertFalse(String(Markdown.inline(text).characters).isEmpty)
    }

    func testARealisticAnswerSplitsIntoTheRightBlocks() {
        let answer = """
        Here's how to do it.

        1. Open the file
        2. Change the line

        ```bash
        swift build
        ```

        > Careful: this rebuilds everything.
        """
        let kinds = Markdown.blocks(from: answer).map { block -> String in
            switch block {
            case .paragraph: "p"
            case .heading: "h"
            case .bullet: "ul"
            case .numbered: "ol"
            case .code: "code"
            case .quote: "quote"
            case .rule: "hr"
            }
        }
        XCTAssertEqual(kinds, ["p", "ol", "code", "quote"])
    }
}
