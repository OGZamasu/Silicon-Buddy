import XCTest
@testable import SiliconBuddy

/// Pairing without a camera (#17). A device on the tailnet gets in only by spending the
/// code the Mac shows: the control token in the Mac's control.json is refused anywhere but
/// the Mac's own loopback. So what a person types has to end where a scan does, at
/// `POST /buddy/pair`, and must never need that token.
final class ManualPairingTests: XCTestCase {

    private var server: LoopbackServer!

    override func setUpWithError() throws {
        try super.setUpWithError()
        server = try LoopbackServer()
    }

    override func tearDown() {
        server.stop()
        server = nil
        super.tearDown()
    }

    // MARK: - What a person types

    func testTheCodeAndAddressTheMacShowsAreEnough() throws {
        let invite = try PairingInvite.typed(address: "100.64.0.9", code: "418203")
        XCTAssertEqual(
            invite, PairingInvite(host: "100.64.0.9", port: PairingInvite.defaultPort, code: "418203")
        )
        XCTAssertEqual(PairingInvite.defaultPort, 8788)
    }

    func testTheCodeMayBeTypedTheWayTheMacSpacesIt() throws {
        XCTAssertEqual(try PairingInvite.typed(address: "100.64.0.9", code: "418 203").code, "418203")
        XCTAssertEqual(try PairingInvite.typed(address: "100.64.0.9", code: " 418-203 ").code, "418203")
    }

    func testAPortCanBeGivenInItsFieldOrAfterTheAddress() throws {
        XCTAssertEqual(try PairingInvite.typed(address: "127.0.0.1", code: "418203", port: "8924").port, 8924)
        XCTAssertEqual(
            try PairingInvite.typed(address: "100.64.0.9:8924", code: "418203", port: "8788").port, 8924
        )
        XCTAssertEqual(try PairingInvite.typed(address: "100.64.0.9:8924", code: "418203").host, "100.64.0.9")
        XCTAssertEqual(
            try PairingInvite.typed(address: "100.64.0.9", code: "418203", port: " ").port,
            PairingInvite.defaultPort
        )
    }

    func testAnIPv6TailnetAddressIsNeverSplitAtAColon() throws {
        let invite = try PairingInvite.typed(address: "fd7a:115c:a1e0::9", code: "418203")
        XCTAssertEqual(invite.host, "fd7a:115c:a1e0::9")
        XCTAssertEqual(invite.port, PairingInvite.defaultPort)
    }

    func testAPastedPairingLinkIsReadAsTheLink() throws {
        let link = "siliconbuddy://pair?host=100.64.0.9&port=8788&code=418203"
        XCTAssertTrue(PairingInvite.isLink(" \(link)"))
        XCTAssertFalse(PairingInvite.isLink("100.64.0.9"))
        XCTAssertEqual(try PairingInvite.typed(address: link, code: ""), try PairingInvite.parse(link))
    }

    func testATypedAddressOffTheTailnetIsRefusedAsAScannedOneIs() {
        for host in ["evil.example.com", "192.168.1.10", "100.64.0.1.evil.example.com"] {
            XCTAssertThrowsError(try PairingInvite.typed(address: host, code: "418203")) {
                XCTAssertEqual($0 as? PairingInvite.ParseError, .hostNotOnTailnet(host))
            }
        }
        XCTAssertThrowsError(try PairingInvite.typed(
            address: "siliconbuddy://pair?host=evil.example.com&port=8788&code=418203", code: ""
        )) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .hostNotOnTailnet("evil.example.com"))
        }
    }

    func testACodeThatIsNotSixDigitsIsRefused() {
        for code in ["41820", "4182031", "abc123", "--", "418 20x", "４１８２０３"] {
            XCTAssertThrowsError(try PairingInvite.typed(address: "100.64.0.9", code: code), code) {
                XCTAssertEqual($0 as? PairingInvite.ParseError, .badCode(code))
            }
        }
    }

    func testABadPortOrAMissingAddressSaysWhich() {
        XCTAssertThrowsError(try PairingInvite.typed(address: "100.64.0.9", code: "418203", port: "99999")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .badPort("99999"))
        }
        XCTAssertThrowsError(try PairingInvite.typed(address: "100.64.0.9:http", code: "418203")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .badPort("http"))
        }
        XCTAssertThrowsError(try PairingInvite.typed(address: "  ", code: "418203")) {
            XCTAssertEqual($0 as? PairingInvite.ParseError, .noAddress)
            XCTAssertTrue(($0 as? LocalizedError)?.errorDescription?.contains("address") == true)
        }
    }

    // MARK: - Where it goes

    func testATypedCodeIsSpentAtBuddyPairWithNoToken() async throws {
        server.reply("/buddy/pair", 200, """
        {"deviceID":"D1","token":"device-token","macName":"Demo Mac","port":8788,"scope":"chat"}
        """)
        let invite = try PairingInvite.typed(
            address: "127.0.0.1", code: "418 203", port: String(server.port)
        )

        let config = try await invite.exchange(deviceName: "iPad", platform: "ipados")

        let request = try XCTUnwrap(server.request(to: "/buddy/pair"), "the code never reached the Mac")
        XCTAssertEqual(request.method, "POST")
        XCTAssertNil(
            request.headers["authorization"], "the code is the credential; nothing else goes with it"
        )
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: request.body) as? [String: Any]
        )
        XCTAssertEqual(body["code"] as? String, "418203")
        XCTAssertEqual(body["platform"] as? String, "ipados")
        // What the device keeps is the Mac's answer: its own token, the port to keep dialling.
        XCTAssertEqual(config.host, "127.0.0.1")
        XCTAssertEqual(config.port, 8788)
        XCTAssertEqual(config.token, "device-token")
        XCTAssertEqual(config.macName, "Demo Mac")
        XCTAssertEqual(config.deviceID, "D1")
        XCTAssertEqual(config.scope, .chat)
    }

    func testAWrongTypedCodeIsTheMacsRefusalNotAMissingRoute() async throws {
        server.reply("/buddy/pair", 403, #"{"error":"That pairing code is not the one on screen."}"#)
        let invite = try PairingInvite.typed(address: "127.0.0.1", code: "000000", port: String(server.port))
        do {
            _ = try await invite.exchange(deviceName: "iPad", platform: "ipados")
            XCTFail("Expected a refusal")
        } catch let error as TransportError {
            XCTAssertFalse(error.isMissingRoute)
            XCTAssertEqual(error.errorDescription, "That pairing code is not the one on screen.")
        }
    }

    func testAMacTooOldForCodesIsToldApart() async throws {
        server.reply("/buddy/pair", 401, #"{"error":"Invalid or missing control token."}"#)
        let invite = try PairingInvite.typed(address: "127.0.0.1", code: "418203", port: String(server.port))
        do {
            _ = try await invite.exchange(deviceName: "iPad", platform: "ipados")
            XCTFail("Expected the route to be reported as missing")
        } catch let error as TransportError {
            XCTAssertEqual(error, .routeUnavailable("/buddy/pair"))
        }
    }

    func testNothingIsDialledForAnInviteOffTheTailnet() async {
        let stub = StubTransport()
        stub.pairResult = .success(BuddyAPI.PairResponse(
            deviceID: "D1", token: "t", macName: "Elsewhere", port: 8788, scope: "full"
        ))
        let built = Counter()
        do {
            _ = try await PairingInvite(host: "192.168.1.10", port: 8788, code: "418203")
                .exchange(deviceName: "iPad", platform: "ipados") { _ in
                    built.increment()
                    return stub
                }
            XCTFail("Expected a refusal")
        } catch let error as TransportError {
            XCTAssertTrue(error.isForbidden)
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
        XCTAssertEqual(built.value, 0)
    }
}

/// Counts from inside a `@Sendable` closure.
private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.withLock { count } }
    func increment() { lock.withLock { count += 1 } }
}
