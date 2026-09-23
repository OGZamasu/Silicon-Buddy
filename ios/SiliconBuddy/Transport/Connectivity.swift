import Foundation

/// What a reachability probe found.
///
/// Three failures, three different fixes, and the app says which one it is instead of
/// spinning: the tailnet is off, the Mac app is closed, or this device is no longer paired.
public enum Reachability: Sendable, Equatable {
    case unknown
    case checking
    /// The Mac answered and took the token. The version is the Mac app's, as
    /// `Health.appVersionLabel` reads it, and nil when the Mac did not say.
    case ready(version: String?, loadedModel: String?)
    /// `/health` answered but `/status` came back 401.
    case unauthorized
    /// Something is at that address, nothing is on that port.
    case appNotRunning
    /// Nothing at that address at all.
    case unreachable(String)
    /// Some other failure, with the Mac's words.
    case failed(String)

    public var isReady: Bool {
        if case .ready = self { return true }
        return false
    }

    public var headline: String {
        switch self {
        case .unknown: "Not connected"
        case .checking: "Checking…"
        case .ready: "Connected"
        case .unauthorized: "Not paired"
        case .appNotRunning: "App not running"
        case .unreachable: "Unreachable"
        case .failed: "Error"
        }
    }

    public var detail: String {
        switch self {
        case .unknown: "No Mac paired yet."
        case .checking: "Talking to the Mac…"
        case .ready(let version, let model):
            model.map { "\(Self.appName(version)) — \($0)" } ?? Self.appName(version)
        case .unauthorized: "The Mac refused this device's token. Pair again."
        case .appNotRunning: "The Mac is awake but Silicon Optimizer is closed."
        case .unreachable(let host): "Nothing answered at \(host). Is Tailscale on?"
        case .failed(let message): message
        }
    }

    private static func appName(_ version: String?) -> String {
        version.map { "Silicon Optimizer \($0)" } ?? "Silicon Optimizer"
    }

    /// The SF Symbol the connection row shows.
    public var symbol: String {
        switch self {
        case .unknown: "bolt.horizontal.circle"
        case .checking: "arrow.triangle.2.circlepath"
        case .ready: "checkmark.circle.fill"
        case .unauthorized: "lock.slash"
        case .appNotRunning: "macwindow.badge.plus"
        case .unreachable: "wifi.slash"
        case .failed: "exclamationmark.triangle"
        }
    }
}

/// Probes a Mac in the order that separates the three failures.
///
/// `/health` is deliberately unauthenticated on the Mac exactly so a client can tell
/// "app not running" from "bad token" — this is the client half of that bargain.
public struct ConnectivityProbe: Sendable {
    private let transport: any ControlTransport

    public init(transport: any ControlTransport) {
        self.transport = transport
    }

    public func check() async -> Reachability {
        let version: String?
        do {
            version = try await transport.health().appVersionLabel
        } catch let error as TransportError {
            switch error {
            case .appNotRunning: return .appNotRunning
            case .unreachable(let host): return .unreachable(host)
            case .timedOut: return .unreachable("the Mac")
            case .cancelled: return .unknown
            // A Mac old enough to lack /health still proves it is listening.
            case .routeUnavailable: return await authorizedCheck(version: nil)
            default: return .failed(error.localizedDescription)
            }
        } catch {
            return .failed(error.localizedDescription)
        }
        return await authorizedCheck(version: version)
    }

    private func authorizedCheck(version: String?) async -> Reachability {
        do {
            let status = try await transport.status()
            return .ready(version: version, loadedModel: status.loadedModelName ?? status.state)
        } catch let error as TransportError {
            switch error {
            case .unauthorized, .notConfigured: return .unauthorized
            case .appNotRunning: return .appNotRunning
            case .unreachable(let host): return .unreachable(host)
            case .cancelled: return .unknown
            default: return .failed(error.localizedDescription)
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}
