import XCTest
@testable import SiliconBuddy

/// What `siliconbuddy://` links mean, and what they are not allowed to do.
final class BuddyLinkTests: XCTestCase {

    func testAPairingLinkStillParsesAsOne() throws {
        guard case .pair(let invite) = try BuddyLink.parse(
            "siliconbuddy://pair?host=100.64.0.1&port=8788&code=123456"
        ) else { return XCTFail("expected a pairing invite") }
        XCTAssertEqual(invite.host, "100.64.0.1")
    }

    func testAPairingLinkToAHostOffTheTailnetIsStillRefused() {
        XCTAssertThrowsError(
            try BuddyLink.parse("siliconbuddy://pair?host=evil.example.com&port=80&code=123456")
        )
    }

    func testTheWidgetsAskLinkOpensAnEmptyComposer() throws {
        guard case .compose(let text) = try BuddyLink.parse("siliconbuddy://ask") else {
            return XCTFail("expected a compose request")
        }
        XCTAssertNil(text)
    }

    func testAskCanCarryTextToTypeIn() throws {
        let url = try XCTUnwrap(BuddyLink.composeURL(text: "what is this?"))
        guard case .compose(let text) = try BuddyLink.parse(url.absoluteString) else {
            return XCTFail("expected a compose request")
        }
        XCTAssertEqual(text, "what is this?")
    }

    /// A URL can be opened by anything on the phone. The most one may do is fill the
    /// box in, and a megabyte of it would be a denial of service dressed as a shortcut.
    func testComposedTextIsCutToSomethingSomebodyMeantToSend() throws {
        let long = String(repeating: "x", count: 10_000)
        guard case .compose(let text) = try BuddyLink.parse("siliconbuddy://ask?text=\(long)")
        else { return XCTFail("expected a compose request") }
        XCTAssertEqual(text?.count, BuddyLink.maximumComposedCharacters)
    }

    func testAnUnknownActionIsRefusedRatherThanGuessed() {
        XCTAssertThrowsError(try BuddyLink.parse("siliconbuddy://delete?all=true")) { error in
            XCTAssertEqual(error as? BuddyLink.ParseError, .unknownAction("delete"))
        }
    }

    func testAnotherAppsSchemeIsRefused() {
        XCTAssertThrowsError(try BuddyLink.parse("shortcuts://run?name=x")) { error in
            XCTAssertEqual(error as? BuddyLink.ParseError, .wrongScheme("shortcuts"))
        }
    }

    func testAConversationLinkNeedsAnId() {
        XCTAssertThrowsError(try BuddyLink.parse("siliconbuddy://conversation"))
    }
}
