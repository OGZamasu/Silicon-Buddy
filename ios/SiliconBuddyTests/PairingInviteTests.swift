import XCTest
@testable import SiliconBuddy

final class PairingInviteTests: XCTestCase {

    func testParsesTheMacsQRCode() throws {
        let invite = try PairingInvite.parse("siliconbuddy://pair?host=100.118.191.4&port=8788&code=418203")
        XCTAssertEqual(invite.host, "100.118.191.4")
        XCTAssertEqual(invite.port, 8788)
        XCTAssertEqual(invite.code, "418203")
    }

    func testAcceptsThePathSpelling() throws {
        let invite = try PairingInvite.parse("siliconbuddy:///pair?host=127.0.0.1&port=80&code=000000")
        XCTAssertEqual(invite.host, "127.0.0.1")
        XCTAssertEqual(invite.port, 80)
    }

    func testIsCaseInsensitiveAboutTheScheme() throws {
        let invite = try PairingInvite.parse("SiliconBuddy://Pair?host=100.64.0.1&port=1&code=123456")
        XCTAssertEqual(invite.port, 1)
    }

    func testTrimsSurroundingWhitespace() throws {
        let invite = try PairingInvite.parse("  siliconbuddy://pair?host=100.64.0.1&port=9&code=123456\n")
        XCTAssertEqual(invite.host, "100.64.0.1")
    }

    func testRejectsAnotherScheme() {
        XCTAssertThrowsError(try PairingInvite.parse("https://example.com/pair?host=100.64.0.1&port=1&code=123456")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .wrongScheme("https"))
        }
    }

    func testRejectsAnotherAction() {
        XCTAssertThrowsError(try PairingInvite.parse("siliconbuddy://open?host=100.64.0.1&port=1&code=123456")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .wrongAction("open"))
        }
    }

    func testRejectsAMissingHost() {
        XCTAssertThrowsError(try PairingInvite.parse("siliconbuddy://pair?port=1&code=123456")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .missing("host"))
        }
    }

    func testRejectsAMissingPort() {
        XCTAssertThrowsError(try PairingInvite.parse("siliconbuddy://pair?host=100.64.0.1&code=123456")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .missing("port"))
        }
    }

    func testRejectsAPortOutOfRange() {
        XCTAssertThrowsError(try PairingInvite.parse("siliconbuddy://pair?host=100.64.0.1&port=99999&code=123456")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .badPort("99999"))
        }
    }

    func testRejectsAPortThatIsNotANumber() {
        XCTAssertThrowsError(try PairingInvite.parse("siliconbuddy://pair?host=100.64.0.1&port=eight&code=123456")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .badPort("eight"))
        }
    }

    func testRejectsACodeOfTheWrongLength() {
        XCTAssertThrowsError(try PairingInvite.parse("siliconbuddy://pair?host=100.64.0.1&port=1&code=12345")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .badCode("12345"))
        }
    }

    func testRejectsANonNumericCode() {
        XCTAssertThrowsError(try PairingInvite.parse("siliconbuddy://pair?host=100.64.0.1&port=1&code=abc123")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .badCode("abc123"))
        }
    }

    func testRejectsEmptyText() {
        XCTAssertThrowsError(try PairingInvite.parse("   ")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .notAURL)
        }
    }

    func testRejectsAnArbitraryQRCode() {
        XCTAssertThrowsError(try PairingInvite.parse("WIFI:S:Home;T:WPA;P:hunter2;;"))
    }

    func testRoundTripsThroughItsOwnURL() throws {
        let original = PairingInvite(host: "100.64.0.1", port: 8788, code: "007007")
        let url = try XCTUnwrap(original.url)
        XCTAssertEqual(try PairingInvite.parse(url.absoluteString), original)
    }
}
