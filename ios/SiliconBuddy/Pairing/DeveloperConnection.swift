import Foundation

/// Validates the local control credentials before a probe sends them to the Mac.
enum DeveloperConnection {
    /// The host rule is `PairingView.developerHostProblem`'s: loopback, in a build that dials
    /// one (`local`), and nowhere else.
    static func configuration(
        host: String, port: String, token: String, local: Bool = TailnetHost.allowsLocal
    ) throws -> ServerConfig {
        let trimmedToken = token.trimmingCharacters(in: .whitespacesAndNewlines)
        let digits = trimmedToken.filter { !$0.isWhitespace && $0 != "-" }
        guard !(digits.count == 6 && digits.allSatisfy(\.isASCII) && digits.allSatisfy(\.isNumber)) else {
            throw TransportError.badRequest(
                "That is a six-digit pairing code. Use Enter code with the Mac's Tailscale "
                    + "address shown beside it. For a local Simulator connection, enter the "
                    + "port and full token from control.json."
            )
        }
        guard !trimmedToken.isEmpty else {
            throw TransportError.badRequest("Enter the full control token from control.json.")
        }
        guard let portNumber = Int(port.trimmingCharacters(in: .whitespacesAndNewlines)),
              (1...65535).contains(portNumber) else {
            throw TransportError.badRequest("Enter the port from control.json (1–65535).")
        }
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        if let problem = PairingView.developerHostProblem(trimmedHost, local: local) {
            throw TransportError.forbidden(problem)
        }
        return ServerConfig(host: trimmedHost, port: portNumber, token: trimmedToken)
    }
}
