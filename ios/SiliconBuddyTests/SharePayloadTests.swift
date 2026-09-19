import XCTest
@testable import SiliconBuddy

/// What the share sheet does with whatever another app handed it.
final class SharePayloadTests: XCTestCase {

    private func jpeg(bytes: Int) -> Data { Data(repeating: 0xFF, count: bytes) }

    // MARK: - The prefilled question

    func testTextAloneAsksForASummary() {
        let draft = ShareNormaliser.draft(for: SharePayload(items: [.text("A long article.")]))
        XCTAssertEqual(draft.prompt, "Summarise this")
        XCTAssertEqual(draft.quoted, "A long article.")
        XCTAssertTrue(draft.images.isEmpty)
    }

    func testALinkAloneAsksForASummary() throws {
        let url = try XCTUnwrap(URL(string: "https://example.com/piece"))
        let draft = ShareNormaliser.draft(for: SharePayload(items: [.url(url)]))
        XCTAssertEqual(draft.prompt, "Summarise this")
        XCTAssertEqual(draft.quoted, "https://example.com/piece")
        XCTAssertEqual(draft.summary, "Shared: example.com")
    }

    func testAPictureAloneAsksWhatItIs() {
        let draft = ShareNormaliser.draft(for: SharePayload(items: [.image(jpeg(bytes: 900))]))
        XCTAssertEqual(draft.prompt, "What is this?")
        XCTAssertEqual(draft.images.count, 1)
        XCTAssertTrue(draft.images[0].hasPrefix("data:image/jpeg;base64,"))
        XCTAssertTrue(draft.quoted.isEmpty)
    }

    func testAPictureWithTextAsksAboutBoth() {
        let draft = ShareNormaliser.draft(
            for: SharePayload(items: [.image(jpeg(bytes: 100)), .text("The caption")])
        )
        XCTAssertEqual(draft.prompt, "What is this, and what does the text say about it?")
        XCTAssertEqual(draft.quoted, "The caption")
    }

    // MARK: - Tidying up what arrived

    /// Safari sends the page's URL as a URL *and* as text. Quoting it twice would waste
    /// the model's context on a duplicate.
    func testAURLThatAlsoArrivedAsTextIsQuotedOnce() throws {
        let url = try XCTUnwrap(URL(string: "https://example.com/a"))
        let draft = ShareNormaliser.draft(
            for: SharePayload(items: [.url(url), .text("https://example.com/a")])
        )
        XCTAssertEqual(draft.quoted, "https://example.com/a")
    }

    func testEmptyAndWhitespaceTextIsDropped() {
        let draft = ShareNormaliser.draft(for: SharePayload(items: [.text("   \n  ")]))
        XCTAssertTrue(draft.isEmpty)
        XCTAssertEqual(draft.summary, "Nothing to send")
        XCTAssertEqual(draft.prompt, "", "there is nothing to suggest a question about")
    }

    func testTextIsTrimmedBeforeItIsQuoted() {
        let draft = ShareNormaliser.draft(for: SharePayload(items: [.text("  hello  ")]))
        XCTAssertEqual(draft.quoted, "hello")
    }

    // MARK: - The Mac's caps

    func testMorePicturesThanTheMacTakesAreDroppedAndSaidSo() {
        let items = (0..<12).map { _ in SharePayload.Item.image(jpeg(bytes: 100)) }
        let draft = ShareNormaliser.draft(for: SharePayload(items: items))
        XCTAssertEqual(draft.images.count, SendLimits.maximumAttachments)
        XCTAssertNotNil(draft.note)
        XCTAssertTrue(draft.note?.contains("8") ?? false)
    }

    func testAPictureOverTheSizeCapIsDroppedRatherThanRefusedOnArrival() {
        let draft = ShareNormaliser.draft(
            for: SharePayload(items: [
                .image(jpeg(bytes: SendLimits.maximumImageBytes + 1)),
                .image(jpeg(bytes: 500)),
            ])
        )
        XCTAssertEqual(draft.images.count, 1)
        XCTAssertEqual(draft.note, "One picture was too large to send even after shrinking.")
    }

    func testAWholeWebPageIsCutToSomethingAModelCanRead() {
        let long = String(repeating: "word ", count: 5_000)
        let draft = ShareNormaliser.draft(for: SharePayload(items: [.text(long)]))
        XCTAssertEqual(draft.quoted.count, ShareNormaliser.maximumQuotedCharacters)
        XCTAssertTrue(draft.note?.contains("cut") ?? false)
    }

    /// Eight pictures each inside the per-image cap can still be three times the body
    /// the Mac accepts. Select-all in Photos and share is exactly that shape.
    func testPicturesAreDroppedUntilTheWholeMessageFits() {
        let items = (0..<8).map { _ in SharePayload.Item.image(jpeg(bytes: 1_400_000)) }
        let draft = ShareNormaliser.draft(for: SharePayload(items: items))
        XCTAssertLessThan(draft.images.count, 8)
        XCTAssertTrue(draft.note?.contains("4 MB") ?? false)
    }

    /// The whole point of the caps: what comes out has to be sendable.
    @MainActor
    func testWhatComesOutPassesTheSameCheckTheComposerApplies() {
        let items = (0..<10).map { _ in SharePayload.Item.image(jpeg(bytes: 1_400_000)) }
        let draft = ShareNormaliser.draft(for: SharePayload(items: items))
        let attachments = draft.images.compactMap { dataURL -> ChatAttachment? in
            guard let comma = dataURL.firstIndex(of: ","),
                  let data = Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...]))
            else { return nil }
            return ChatAttachment(jpeg: data)
        }
        XCTAssertNil(ChatModel.attachmentProblem(for: attachments, message: draft.message))
    }

    // MARK: - The message that actually goes

    func testTheMessageIsTheQuestionThenTheMaterial() {
        var draft = ShareNormaliser.draft(for: SharePayload(items: [.text("Body")]))
        draft.prompt = "In one line"
        XCTAssertEqual(draft.message, "In one line\n\nBody")
    }

    func testAPictureOnlyMessageIsJustTheQuestion() {
        let draft = ShareNormaliser.draft(for: SharePayload(items: [.image(jpeg(bytes: 10))]))
        XCTAssertEqual(draft.message, "What is this?")
    }

    func testTheHeaderNamesWhatArrived() throws {
        let url = try XCTUnwrap(URL(string: "https://example.com/x"))
        let draft = ShareNormaliser.draft(
            for: SharePayload(items: [.image(jpeg(bytes: 10)), .url(url), .text("note")])
        )
        XCTAssertEqual(draft.summary, "Shared: a picture, example.com and some text")
    }
}
