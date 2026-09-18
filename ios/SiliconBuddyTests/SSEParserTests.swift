import XCTest
@testable import SiliconBuddy

final class SSEParserTests: XCTestCase {

    private func events(from stream: String) -> [SSEEvent] {
        var parser = SSEParser()
        var found: [SSEEvent] = []
        for line in stream.components(separatedBy: "\n") {
            if let event = parser.consume(line: line) { found.append(event) }
        }
        if let last = parser.finish() { found.append(last) }
        return found
    }

    func testNamedEventWithData() {
        let found = events(from: "event: token\ndata: Hello\n\n")
        XCTAssertEqual(found, [SSEEvent(name: "token", data: "Hello")])
    }

    func testUnnamedEventDefaultsToMessage() {
        let found = events(from: "data: plain\n\n")
        XCTAssertEqual(found.first?.name, "message")
        XCTAssertEqual(found.first?.data, "plain")
    }

    func testMultipleDataLinesJoinWithNewline() {
        let found = events(from: "event: token\ndata: first\ndata: second\n\n")
        XCTAssertEqual(found.first?.data, "first\nsecond")
    }

    func testCommentsAreIgnoredButNoticed() {
        var parser = SSEParser()
        XCTAssertNil(parser.consume(line: ": heartbeat"))
        XCTAssertTrue(parser.sawComment)
        XCTAssertNil(parser.consume(line: "data: after"))
        XCTAssertEqual(parser.consume(line: "")?.data, "after")
    }

    func testOnlyOneLeadingSpaceIsStripped() {
        let found = events(from: "data:  two spaces\n\n")
        XCTAssertEqual(found.first?.data, " two spaces")
    }

    func testFieldWithoutColonHasEmptyValue() {
        let found = events(from: "event\ndata: body\n\n")
        // A bare "event" line sets an empty name, so the event falls back to "message".
        XCTAssertEqual(found.first?.data, "body")
    }

    func testCarriageReturnsAreStripped() {
        let found = events(from: "event: token\r\ndata: windows\r\n\r\n")
        XCTAssertEqual(found, [SSEEvent(name: "token", data: "windows")])
    }

    func testBlockWithoutDataDispatchesNothing() {
        let found = events(from: "event: token\n\n")
        XCTAssertTrue(found.isEmpty)
    }

    func testIdAndRetryAreCarried() {
        let found = events(from: "id: 7\nretry: 2500\ndata: x\n\n")
        XCTAssertEqual(found.first?.id, "7")
        XCTAssertEqual(found.first?.retry, 2500)
    }

    func testEventNameDoesNotLeakIntoTheNextEvent() {
        let found = events(from: "event: reasoning\ndata: one\n\ndata: two\n\n")
        XCTAssertEqual(found.map(\.name), ["reasoning", "message"])
    }

    func testChunkedDeliverySplitsMidLine() {
        var parser = SSEParser()
        var found: [SSEEvent] = []
        found += parser.consume(chunk: "event: tok")
        found += parser.consume(chunk: "en\ndata: Hel")
        found += parser.consume(chunk: "lo\n\nevent: finished\ndata: {\"promptTokens\":1}\n\n")
        XCTAssertEqual(found.count, 2)
        XCTAssertEqual(found.first, SSEEvent(name: "token", data: "Hello"))
        XCTAssertEqual(found.last?.name, "finished")
    }

    func testStreamThatEndsWithoutBlankLineStillDispatches() {
        var parser = SSEParser()
        _ = parser.consume(chunk: "event: token\ndata: cut off")
        XCTAssertEqual(parser.finish(), SSEEvent(name: "token", data: "cut off"))
    }

    func testUnknownFieldsAreIgnored() {
        let found = events(from: "weird: value\ndata: fine\n\n")
        XCTAssertEqual(found.first?.data, "fine")
    }

    func testDecodingAFinishedEvent() throws {
        let event = SSEEvent(
            name: "finished",
            data: #"{"promptTokens": 12, "generatedTokens": 40, "tokensPerSecond": 21.5}"#
        )
        let metrics = try event.decode(BuddyAPI.ChatMetrics.self)
        XCTAssertEqual(metrics.promptTokens, 12)
        XCTAssertEqual(metrics.generatedTokens, 40)
        XCTAssertEqual(metrics.tokensPerSecond, 21.5, accuracy: 0.001)
    }

    func testARealisticChatStream() {
        let stream = """
        : open

        event: reasoning
        data: The user wants a greeting.

        event: token
        data: Hello

        event: token
        data: , world

        event: finished
        data: {"promptTokens":9,"generatedTokens":3,"tokensPerSecond":18.2}


        """
        let found = events(from: stream)
        XCTAssertEqual(found.map(\.name), ["reasoning", "token", "token", "finished"])
        XCTAssertEqual(found[1].data + found[2].data, "Hello, world")
    }
}
