import XCTest
@testable import SiliconBuddy

final class DeveloperConnectionTests: XCTestCase {
    func testPairingCodesCannotBecomeControlCredentials() {
        for code in ["123456", "123 456", " 123-456\n"] {
            XCTAssertThrowsError(
                try DeveloperConnection.configuration(host: "127.0.0.1", port: "8788", token: code)
            ) { error in
                XCTAssertTrue(error.localizedDescription.contains("Enter code"))
                XCTAssertTrue(error.localizedDescription.contains("Tailscale address"))
                XCTAssertFalse(error.localizedDescription.contains(code.trimmingCharacters(in: .whitespacesAndNewlines)))
            }
        }
    }

    func testLocalCredentialsKeepTheirActualPortAndFullToken() throws {
        let config = try DeveloperConnection.configuration(
            host: " 127.0.0.1\n", port: " 53572\n", token: " full-control-token\n"
        )
        XCTAssertEqual(config.host, "127.0.0.1")
        XCTAssertEqual(config.port, 53572)
        XCTAssertEqual(config.token, "full-control-token")
    }

    func testInvalidCredentialsFailBeforeAProbeCanBeBuilt() {
        for port in ["", "0", "65536", "http"] {
            XCTAssertThrowsError(
                try DeveloperConnection.configuration(host: "127.0.0.1", port: port, token: "full-token")
            )
        }
        XCTAssertThrowsError(
            try DeveloperConnection.configuration(host: "127.0.0.1", port: "53572", token: " \n")
        )
        XCTAssertThrowsError(
            try DeveloperConnection.configuration(host: "example.com", port: "53572", token: "full-token")
        )
    }

    /// The form builds what it probes through here, so the host rule holds here too: the
    /// control token goes to loopback, and in a build that dials no local Mac, nowhere.
    func testTheControlTokenIsBuiltOnlyForLoopback() {
        for host in ["100.64.0.9", "fd7a:115c:a1e0::9", "10.0.2.2"] {
            XCTAssertThrowsError(
                try DeveloperConnection.configuration(host: host, port: "53572", token: "full-token"),
                "\(host) must not get the token"
            ) { error in
                XCTAssertEqual(
                    error as? TransportError, .forbidden(PairingView.developerHostProblem(host) ?? "")
                )
            }
        }
        XCTAssertThrowsError(
            try DeveloperConnection.configuration(
                host: "127.0.0.1", port: "53572", token: "full-token", local: false
            )
        )
    }

    func testLocalAddressGuidanceDoesNotMistakeTailnetOrUntrustedHostsForLocal() {
        for host in ["127.0.0.1", "127.1.2.3", "localhost", " LOCALHOST\n", "::1", "[::1]", "10.0.2.2"] {
            XCTAssertTrue(TailnetHost.isLocal(host), host)
        }
        for host in ["100.64.0.9", "fd7a:115c:a1e0::9", "127.0.0.1.example.com", "127.000.0.1", "::", ""] {
            XCTAssertFalse(TailnetHost.isLocal(host), host)
        }
    }
}
