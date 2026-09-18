import Foundation
import Observation

/// Everything the first screen shows, fetched together.
///
/// Each reading is kept separately so one slow or missing endpoint — `/v1/node` on an
/// older Mac, say — leaves the rest of the screen intact instead of blanking it.
@MainActor
@Observable
public final class DashboardModel {
    public private(set) var status: ControlAPI.Status?
    public private(set) var profile: ControlAPI.Profile?
    public private(set) var metrics: ControlAPI.Metrics?
    public private(set) var swarm: ControlAPI.SwarmView?
    public private(set) var node: ControlAPI.NodeAdvertisement?
    public private(set) var isLoading = false
    public private(set) var error: String?
    public private(set) var lastUpdated: Date?

    private var ticker: Task<Void, Never>?

    public init() {}

    /// One pass over every dashboard endpoint, in parallel.
    public func refresh(using transport: (any ControlTransport)?, app: AppModel? = nil) async {
        guard let transport else {
            error = TransportError.notConfigured.localizedDescription
            return
        }
        isLoading = true
        defer { isLoading = false }

        async let statusTask = transport.status()
        async let profileTask = transport.profile()
        async let metricsTask = transport.metrics()
        async let swarmTask = transport.swarm()
        async let nodeTask = transport.node()

        let newStatus = try? await statusTask
        let newProfile = try? await profileTask
        let newMetrics = try? await metricsTask
        let newSwarm = try? await swarmTask
        let newNode = try? await nodeTask

        if newStatus == nil, newProfile == nil, newMetrics == nil {
            // Nothing answered: this is a connection problem, not an empty Mac.
            error = "Couldn't reach the Mac."
        } else {
            error = nil
            lastUpdated = Date()
        }
        if let newStatus { status = newStatus }
        if let newProfile { profile = newProfile }
        if let newMetrics { metrics = newMetrics }
        if let newSwarm { swarm = newSwarm }
        if let newNode {
            node = newNode
            app?.noteMacName(newNode.name)
        }
    }

    /// Live metrics while the dashboard is on screen. Polling, because `GET /events`
    /// arrives with M0; when it does this becomes the fallback rather than the plan.
    public func startLiveUpdates(using transport: (any ControlTransport)?, every seconds: Double = 4) {
        ticker?.cancel()
        guard let transport else { return }
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(seconds))
                guard !Task.isCancelled else { return }
                let reading = try? await transport.metrics()
                let state = try? await transport.status()
                guard let self, !Task.isCancelled else { return }
                if let reading { self.metrics = reading }
                if let state { self.status = state }
                if reading != nil { self.lastUpdated = Date() }
            }
        }
    }

    public func stopLiveUpdates() {
        ticker?.cancel()
        ticker = nil
    }

    // MARK: - Derived

    public var memoryFraction: Double {
        guard let metrics, metrics.memoryTotalBytes > 0 else { return 0 }
        return Double(metrics.memoryUsedBytes) / Double(metrics.memoryTotalBytes)
    }

    public var loadedModelTitle: String {
        if let name = status?.loadedModelName { return name }
        if let activity = status?.activity { return activity }
        return status?.state ?? "Unknown"
    }

    public var loadedModelDetail: String {
        guard let status else { return "No reading yet." }
        var parts: [String] = [status.state]
        if let context = status.contextLength {
            parts.append("\(context / 1024)K context")
        }
        if status.expertStreaming { parts.append("expert streaming") }
        if let activity = status.activity, status.loadedModelID != nil { parts.append(activity) }
        return parts.joined(separator: " · ")
    }

    public var reachablePeers: Int {
        swarm?.peers.filter(\.reachable).count ?? 0
    }
}
