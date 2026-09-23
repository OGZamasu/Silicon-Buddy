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
    @State private var working = false
    @State private var failure: String?
    @State private var paired = false

    public init(invite: PairingInvite) {
        self.invite = invite
    }

    public var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("Address", value: "\(invite.host):\(invite.port)")
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

                if let failure {
                    Section {
                        Label(failure, systemImage: "xmark.octagon")
                            .foregroundStyle(.red)
                    }
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
                            if working { ProgressView().controlSize(.small) }
                            if paired {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.green)
                            }
                        }
                    }
                    .disabled(working || paired)
                }
            }
            .navigationTitle("Pairing code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Not now") { dismiss() }
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
        .interactiveDismissDisabled(working)
    }

    /// Spaced the way the Mac shows it, so the two can be compared at a glance.
    private var displayCode: String {
        guard invite.code.count == 6 else { return invite.code }
        return "\(invite.code.prefix(3)) \(invite.code.suffix(3))"
    }

    private func pair() {
        working = true
        failure = nil
        Task {
            do {
                try await app.pair(with: invite)
                working = false
                paired = true
                try? await Task.sleep(for: .milliseconds(600))
                app.pendingInvite = nil
                dismiss()
            } catch let error as TransportError where error.isMissingRoute {
                working = false
                failure = PairingView.macTooOldForCodes
            } catch {
                working = false
                failure = error.localizedDescription
            }
        }
    }
}
