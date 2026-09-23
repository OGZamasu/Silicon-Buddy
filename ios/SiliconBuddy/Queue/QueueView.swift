import SwiftUI

/// The render queue on the Mac: what it is rendering, what is waiting, what finished or
/// failed, and the Mac's own controls on them.
///
/// Read when it opens and every few seconds while it is on screen. "Cancel render" is on a
/// clip only where the Mac marks it `canCancel` — its node said it can stop that one job —
/// and asks first, because the GPU work so far is thrown away. Stop following stays what
/// it was: this Mac lets go of the render, and the node may still finish it.
public struct QueueView: View {
    @Environment(AppModel.self) private var app
    @State private var model = QueueModel()
    @State private var confirming: Confirmation?

    /// Something that throws work away, waiting to be meant.
    enum Confirmation: Identifiable {
        case cancel(String), remove(String), retryUncertain(String), clearFinished
        var id: String {
            switch self {
            case .cancel(let id): "cancel-\(id)"
            case .remove(let id): "remove-\(id)"
            case .retryUncertain(let id): "retry-\(id)"
            case .clearFinished: "clear"
            }
        }
    }

    public init() {}

    public var body: some View {
        List {
            if let error = model.error {
                Section {
                    Text(error).font(.footnote).foregroundStyle(.red)
                    Button("Dismiss") { model.clearError() }.buttonStyle(.borderless)
                }
            }
            if !app.canControl, app.isPaired {
                Section {
                    Text("Paired for chat only: this phone can watch the queue, and its controls are the Mac owner's.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Section {
                HStack {
                    Button(model.queue?.paused == true ? "Resume" : "Pause") {
                        Task { await model.pauseOrResume(using: app.transport) }
                    }
                    Spacer()
                    Button("Clear finished") { confirming = .clearFinished }
                        .disabled(model.finished.isEmpty)
                }
                .buttonStyle(.borderless)
                .disabled(!app.canControl || model.queue == nil || model.sending)
                if let message = model.queue?.message {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Section("Clips") {
                ForEach(model.items) { item in
                    QueueRow(
                        item: item, model: model, canControl: app.canControl,
                        confirm: { confirming = $0 }
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.canvas)
        .navigationTitle("Render queue")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await model.refresh(using: app.transport) }
        // A re-pair is a different Mac with a different queue.
        .task(id: app.connectionGeneration) {
            model.reset()
            while !Task.isCancelled {
                await model.refresh(using: app.transport)
                try? await Task.sleep(for: .seconds(5))
            }
        }
        .overlay {
            // An unread queue is not an empty one: that is the error above.
            if model.items.isEmpty, !model.isLoading, model.queue != nil || !app.isPaired {
                Placeholder(
                    title: "Nothing in the queue",
                    message: app.isPaired
                        ? "Clips queued on the Mac show up here as they wait, render and finish."
                        : "Pair with a Mac to see its render queue.",
                    systemImage: "film.stack"
                )
            }
        }
        .confirmationDialog(
            title(for: confirming), isPresented: isConfirming, titleVisibility: .visible,
            presenting: confirming
        ) { confirmation in
            switch confirmation {
            case .cancel(let id):
                Button("Cancel render", role: .destructive) {
                    Task { await model.cancelRender(id, using: app.transport) }
                }
                Button("Keep rendering", role: .cancel) {}
            case .remove(let id):
                Button("Remove", role: .destructive) {
                    Task { await model.remove(id, using: app.transport) }
                }
                Button("Keep it", role: .cancel) {}
            case .retryUncertain(let id):
                Button("Retry anyway", role: .destructive) {
                    Task { await model.retry(id, confirmNewRender: true, using: app.transport) }
                }
                Button("Leave it", role: .cancel) {}
            case .clearFinished:
                Button("Clear them", role: .destructive) {
                    Task { await model.clearFinished(using: app.transport) }
                }
                Button("Keep them", role: .cancel) {}
            }
        } message: { confirmation in
            Text(message(for: confirmation))
        }
    }

    private var isConfirming: Binding<Bool> {
        Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } })
    }

    private func title(for confirmation: Confirmation?) -> String {
        switch confirmation {
        case .cancel: "Cancel this render?"
        case .remove: "Remove this take?"
        case .retryUncertain: "Retry a handover the Mac never saw accepted?"
        case .clearFinished: "Clear the finished clips?"
        case nil: ""
        }
    }

    private func message(for confirmation: Confirmation) -> String {
        switch confirmation {
        case .cancel:
            "This asks the node to stop the render. The GPU work it has done is thrown away and nothing is published for this take; rendering it again starts from the beginning."
        case .remove: "This take will not be rendered."
        case .retryUncertain: "Retrying may render it twice."
        case .clearFinished:
            "This takes \(model.finished.count) finished \(model.finished.count == 1 ? "clip" : "clips") off the Mac's queue. The files it already wrote stay where they are."
        }
    }
}

/// One clip: what it is, where it is, and the verbs the Mac offers on it.
struct QueueRow: View {
    @Environment(AppModel.self) private var app
    let item: ControlAPI.VideoQueueView.Item
    let model: QueueModel
    let canControl: Bool
    let confirm: (QueueView.Confirmation) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(item.title.isEmpty ? "Untitled" : item.title).font(.headline)
                Spacer()
                Text(model.isActive(item) ? "Running now" : item.phase.label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(item.phase == .failed || item.phase == .cancelled ? Color.red : Color.secondary)
            }
            Text("scene \(item.scene) · take \(item.variation)")
                .font(.caption)
                .foregroundStyle(.secondary)
            if !item.prompt.isEmpty {
                Text(item.prompt).font(.subheadline).lineLimit(3)
            }
            if let error = item.error {
                Text(error).font(.caption).foregroundStyle(.red)
            } else {
                Text(item.settings).font(.caption).foregroundStyle(.secondary)
            }
            if let outcome = model.cancelOutcome(for: item) {
                Text(outcome.note)
                    .font(.caption)
                    .foregroundStyle(outcome.isWarning ? Color.red : Color.secondary)
                if outcome != .sending, let detail = item.cancelDetail, !detail.isEmpty {
                    Text("The node: \(detail)").font(.caption).foregroundStyle(.tertiary)
                }
            }
            controls
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder private var controls: some View {
        let following = model.isActive(item)
        HStack(spacing: 16) {
            if following {
                Button("Stop following") {
                    Task { await model.stopFollowing(item.id, using: app.transport) }
                }
                .disabled(!canControl || model.sending)
            }
            if item.canRetry {
                Button("Retry") {
                    if item.uncertainSubmission {
                        confirm(.retryUncertain(item.id))
                    } else {
                        Task { await model.retry(item.id, confirmNewRender: false, using: app.transport) }
                    }
                }
                .disabled(!canControl || model.sending)
            }
            if item.canRemove {
                Button("Remove") { confirm(.remove(item.id)) }
                    .disabled(!canControl || model.sending)
            }
            // Not merely disabled for a chat-only pairing, as the others are: it is not a
            // button that device has.
            if item.offersCancelRender(canControl: canControl) {
                Button("Cancel render", role: .destructive) { confirm(.cancel(item.id)) }
                    .disabled(model.cancelling.contains(item.id))
            }
        }
        .buttonStyle(.borderless)
        .font(.subheadline)
        if following {
            Text("Stopping pauses the queue and lets go of this render. The node may still finish it — the Mac won't claim to have cancelled work on another machine.")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
    }
}
