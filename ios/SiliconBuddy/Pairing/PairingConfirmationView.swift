import SwiftUI

/// The sheet between scanning a code and holding a token.
///
/// A QR is a picture of a URL: whoever printed it chose the host. A link can be opened
/// by a web page, a message, or anything else on the device. So a scan never pairs by
/// itself — it names the machine it wants to pair with and waits. And when a Mac is
/// already paired, replacing it takes a second, separate yes, because the first one was
/// about a new Mac, not about losing the old one.
public struct PairingConfirmationView: View {
    let invite: PairingInvite

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    @State private var confirmingReplacement = false

    public init(invite: PairingInvite) {
        self.invite = invite
    }

    public var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent(
                        "Address", value: "\(TailnetHost.forURL(invite.host)):\(invite.port)"
                    )
                    LabeledContent("Code", value: displayCode)
                } header: {
                    Text("Pair with this Mac?")
                } footer: {
                    Text(
                        "Silicon Buddy will ask that address for a token of its own and "
                            + "keep it in the Keychain. Only pair with a code you can see "
                            + "on your own Mac's screen."
                    )
                }

                if let current = app.config {
                    Section {
                        Label(
                            "This replaces \(app.macDisplayName) (\(current.displayAddress)).",
                            systemImage: "exclamationmark.triangle"
                        )
                        .foregroundStyle(.orange)
                    } footer: {
                        Text("The token for that Mac is deleted from this device.")
                    }
                }

                // The exchange is the app's, so this sheet only shows how it is going — and
                // closing it, once it may, never cuts off a code the Mac may have spent.
                switch phase {
                case .failed(let message, _):
                    Section {
                        Label(message, systemImage: "xmark.octagon")
                            .foregroundStyle(.red)
                    }
                case .otherFailed(let other, let message):
                    Section {
                        Label(
                            "The other pairing (\(Self.address(of: other))) didn't finish: \(message)",
                            systemImage: "xmark.octagon"
                        )
                        .foregroundStyle(.red)
                    }
                case .waiting(let other):
                    // Not a spinner: nothing is happening to *this* code yet, and saying no
                    // to it is still fine.
                    Section {
                        Label(
                            "Waiting for the other pairing (\(Self.address(of: other))) to finish…",
                            systemImage: "hourglass"
                        )
                        .foregroundStyle(.secondary)
                    }
                case .ready, .spending:
                    EmptyView()
                }

                Section {
                    Button {
                        if app.isPaired {
                            confirmingReplacement = true
                        } else {
                            pair()
                        }
                    } label: {
                        HStack {
                            Text(app.isPaired ? "Replace this Mac…" : "Pair")
                            Spacer()
                            if phase == .spending { ProgressView().controlSize(.small) }
                        }
                    }
                    .disabled(app.pairing.isWorking)
                }
            }
            .navigationTitle("Pairing code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Not while this code is being spent: it cannot be taken back, and
                    // "Not now" would say it had been.
                    Button("Not now") { dismiss() }.disabled(phase == .spending)
                }
            }
            .confirmationDialog(
                "Replace \(app.macDisplayName)?",
                isPresented: $confirmingReplacement,
                titleVisibility: .visible
            ) {
                Button("Replace with \(invite.host)", role: .destructive) { pair() }
                Button("Keep \(app.macDisplayName)", role: .cancel) {}
            } message: {
                Text(
                    "This device will stop talking to \(app.macDisplayName) and its token "
                        + "will be deleted from this device."
                )
            }
        }
        .interactiveDismissDisabled(phase == .spending)
        .onDisappear {
            // Swiped away or closed: a refusal it was showing has been seen. Not one still
            // on its way, which the app says when it lands.
            switch phase {
            case .failed, .otherFailed: app.pairing.acknowledge()
            case .ready, .spending, .waiting: break
            }
        }
    }

    private var phase: PairingExchange.Phase {
        app.pairing.phase(of: invite)
    }

    static func address(of invite: PairingInvite) -> String {
        "\(TailnetHost.forURL(invite.host)):\(invite.port)"
    }

    /// Spaced the way the Mac shows it, so the two can be compared at a glance.
    private var displayCode: String {
        guard invite.code.count == 6 else { return invite.code }
        return "\(invite.code.prefix(3)) \(invite.code.suffix(3))"
    }

    /// Paired, the invite is gone from `AppModel`, and this sheet with it.
    private func pair() {
        app.startPairing(invite)
    }
}
