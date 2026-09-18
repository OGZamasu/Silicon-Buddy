import SwiftUI

/// The first screen: what the Mac is doing right now.
public struct DashboardView: View {
    @Environment(AppModel.self) private var app
    @State private var model = DashboardModel()

    public init() {}

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: Theme.gap) {
                ConnectionCard()
                if app.isPaired {
                    loadedModelCard
                    metricsCard
                    machineCard
                    swarmCard
                } else {
                    Placeholder(
                        title: "No Mac paired",
                        message: "Scan the code in Silicon Optimizer → Settings → Silicon Buddy, "
                            + "or enter the address by hand.",
                        systemImage: "qrcode.viewfinder"
                    )
                    .padding(.top, 40)
                }
                if let error = model.error, app.isPaired {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(Theme.gap)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Silicon Buddy")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await reload() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await reload() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("Refresh")
                .disabled(model.isLoading)
            }
        }
        .task {
            await reload()
            model.startLiveUpdates(using: app.transport)
        }
        // Pairing happens in a sheet over this screen, so the first reading has to be
        // triggered by the Mac arriving, not only by the screen appearing.
        .onChange(of: app.config) { _, _ in
            Task {
                await reload()
                model.startLiveUpdates(using: app.transport)
            }
        }
        .onDisappear { model.stopLiveUpdates() }
    }

    private func reload() async {
        await app.refreshReachability()
        await model.refresh(using: app.transport, app: app)
    }

    // MARK: - Cards

    private var loadedModelCard: some View {
        Card(
            "Loaded model", systemImage: "cpu",
            footnote: model.lastUpdated.map { Format.relative($0) }
        ) {
            VStack(alignment: .leading, spacing: Theme.tight) {
                Text(model.loadedModelTitle)
                    .font(.title3.weight(.semibold))
                    .lineLimit(2)
                Text(model.loadedModelDetail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if let rate = model.status?.lastGenerationTokensPerSecond {
                    Pill(Format.rate(rate), tint: .green, filled: true)
                        .padding(.top, 2)
                }
            }
        }
    }

    private var metricsCard: some View {
        Card("Live metrics", systemImage: "gauge.with.dots.needle.33percent") {
            if let metrics = model.metrics {
                VStack(alignment: .leading, spacing: Theme.gap) {
                    MeterRow(
                        label: "Memory",
                        detail: "\(Format.bytes(metrics.memoryUsedBytes)) of "
                            + Format.bytes(metrics.memoryTotalBytes),
                        fraction: model.memoryFraction,
                        tint: model.memoryFraction > 0.9 ? .orange : .accentColor
                    )
                    MeterRow(
                        label: "GPU",
                        detail: Format.percent(metrics.gpuUtilization),
                        fraction: metrics.gpuUtilization,
                        tint: .purple
                    )
                    MeterRow(
                        label: "CPU",
                        detail: Format.percent(metrics.cpuUtilization),
                        fraction: metrics.cpuUtilization,
                        tint: .teal
                    )
                    HStack {
                        Stat("Pressure", metrics.memoryPressure)
                        Stat("Swap", Format.bytes(metrics.swapUsedBytes))
                        Stat("Wired", Format.bytes(metrics.memoryWiredBytes))
                    }
                }
            } else {
                Text("No reading yet.").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var machineCard: some View {
        Card("This Mac", systemImage: "desktopcomputer") {
            if let profile = model.profile {
                VStack(alignment: .leading, spacing: Theme.gap) {
                    Text(profile.chip)
                        .font(.headline)
                    HStack {
                        Stat("GPU cores", "\(profile.gpuCores)")
                        Stat("CPU", "\(profile.performanceCores)P + \(profile.efficiencyCores)E")
                        Stat("Neural", "\(profile.neuralEngineCores)")
                    }
                    HStack {
                        Stat("Memory", Format.bytes(profile.totalMemoryBytes))
                        Stat("Model budget", Format.bytes(profile.modelBudgetBytes))
                        Stat("Disk free", Format.bytes(profile.diskFreeBytes))
                    }
                    if let node = model.node {
                        Divider()
                        HStack(spacing: Theme.tight) {
                            Text(node.name).font(.subheadline.weight(.medium))
                            Pill(
                                node.metrics.queueDepth == 0
                                    ? "Idle" : "\(node.metrics.queueDepth) queued",
                                tint: node.metrics.queueDepth == 0 ? .green : .orange,
                                filled: true
                            )
                            Spacer()
                            Text("\(Format.gigabytes(node.metrics.headroomGB)) free")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                        capabilityGrid(node.capabilities.map {
                            CapabilityChip(id: $0.id, ready: $0.ready)
                        })
                    }
                }
            } else {
                Text("No profile yet.").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var swarmCard: some View {
        Card(
            "Swarm", systemImage: "point.3.connected.trianglepath.dotted",
            footnote: model.swarm.map { Format.seconds($0.polledSecondsAgo) }
        ) {
            if let peers = model.swarm?.peers, !peers.isEmpty {
                VStack(alignment: .leading, spacing: Theme.gap) {
                    ForEach(peers) { peer in
                        VStack(alignment: .leading, spacing: Theme.tight) {
                            HStack(spacing: Theme.tight) {
                                Circle()
                                    .fill(peer.reachable ? Color.green : Color.red)
                                    .frame(width: 8, height: 8)
                                    .accessibilityHidden(true)
                                Text(peer.name).font(.subheadline.weight(.medium))
                                Spacer()
                                Text(peer.baseURL)
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            if let error = peer.error {
                                Text(error).font(.caption).foregroundStyle(.red)
                            }
                            capabilityGrid(peer.capabilities.map {
                                CapabilityChip(id: $0.id, ready: $0.ready)
                            })
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(
                            "\(peer.name), \(peer.reachable ? "reachable" : "unreachable"), "
                                + "\(peer.capabilities.filter(\.ready).count) capabilities ready"
                        )
                    }
                }
            } else {
                Text("No peers. The node is offline or not configured.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func capabilityGrid(_ capabilities: [CapabilityChip]) -> some View {
        FlowLayout(spacing: 6) {
            ForEach(capabilities) { capability in
                Pill(
                    capability.id,
                    tint: capability.ready ? .green : .secondary,
                    filled: capability.ready
                )
                .accessibilityLabel(
                    "\(capability.id), \(capability.ready ? "ready" : "not ready")"
                )
            }
        }
    }
}

/// One capability, as the dashboard shows it.
struct CapabilityChip: Identifiable, Equatable {
    let id: String
    let ready: Bool
}

/// Wraps pills onto as many lines as they need. Small enough to own rather than depend on.
public struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    public init(spacing: CGFloat = 6) { self.spacing = spacing }

    public func sizeThatFits(
        proposal: ProposedViewSize, subviews: Subviews, cache: inout ()
    ) -> CGSize {
        let width = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var total = CGSize(width: 0, height: 0)
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth > 0, rowWidth + spacing + size.width > width {
                total.width = max(total.width, rowWidth)
                total.height += rowHeight + spacing
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth += (rowWidth > 0 ? spacing : 0) + size.width
                rowHeight = max(rowHeight, size.height)
            }
        }
        total.width = max(total.width, rowWidth)
        total.height += rowHeight
        return total
    }

    public func placeSubviews(
        in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()
    ) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// The connection row, shown at the top of the dashboard and in Settings.
public struct ConnectionCard: View {
    @Environment(AppModel.self) private var app
    @State private var showingPairing = false

    public init() {}

    public var body: some View {
        Card("Connection", systemImage: "antenna.radiowaves.left.and.right") {
            HStack(spacing: Theme.gap) {
                Image(systemName: app.reachability.symbol)
                    .font(.title2)
                    .foregroundStyle(tint)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(app.isPaired ? app.macDisplayName : "No Mac paired")
                        .font(.headline)
                    Text(app.reachability.detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let config = app.config {
                        Text(config.displayAddress)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                Spacer(minLength: 0)
                if !app.isPaired {
                    Button("Pair") { showingPairing = true }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                "Connection: \(app.reachability.headline). \(app.reachability.detail)"
            )
        }
        .sheet(isPresented: $showingPairing) { PairingView() }
    }

    private var tint: Color {
        switch app.reachability {
        case .ready: .green
        case .checking, .unknown: .secondary
        case .unauthorized, .failed: .orange
        case .appNotRunning, .unreachable: .red
        }
    }
}
