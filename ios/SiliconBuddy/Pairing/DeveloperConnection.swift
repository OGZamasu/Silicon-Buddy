import Foundation

/// Validates the local control credentials before a probe sends them to the Mac.
enum DeveloperConnection {
    static func configuration(host: String, port: String, token: String) throws -> ServerConfig {
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
        guard TailnetHost.isAllowed(trimmedHost) else {
            throw TransportError.forbidden(TailnetHost.explanation)
        }
        return ServerConfig(host: trimmedHost, port: portNumber, token: trimmedToken)
    }
}
