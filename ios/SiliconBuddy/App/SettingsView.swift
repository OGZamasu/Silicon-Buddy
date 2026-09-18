import SwiftUI

/// The connection, what this Mac can do, and the honest list of what is still stubbed.
public struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var showingPairing = false
    @State private var showingForget = false

    public init() {}

    public var body: some View {
        List {
            Section("Mac") {
                LabeledContent("Name", value: app.macDisplayName)
                if let config = app.config {
                    LabeledContent("Address", value: config.displayAddress)
                    LabeledContent("Token", value: "Stored in the Keychain")
                        .foregroundStyle(.secondary)
                    if let deviceID = config.deviceID {
                        LabeledContent("Device id", value: deviceID)
                            .font(.caption)
                    }
                }
                LabeledContent("Status", value: app.reachability.headline)
                Button(app.isPaired ? "Pair with another Mac" : "Pair with a Mac") {
                    showingPairing = true
                }
                if app.isPaired {
                    Button("Forget this Mac", role: .destructive) { showingForget = true }
                }
            }

            Section {
                LabeledContent(
                    "Streaming replies",
                    value: app.supportsStreaming ? "Using /chat/stream" : "Falling back to /chat"
                )
                LabeledContent(
                    "Conversations",
                    value: app.supportsRemoteConversations ? "On the Mac" : "On this device"
                )
            } header: {
                Text("What this Mac supports")
            } footer: {
                Text(
                    "Silicon Buddy asks for the newer routes and falls back quietly when a Mac "
                        + "doesn't have them yet. Nothing here needs configuring."
                )
            }

            Section("This device") {
                LabeledContent("Name", value: AppModel.deviceName)
                LabeledContent("Platform", value: AppModel.platform)
                LabeledContent(
                    "App",
                    value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
                )
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingPairing) { PairingView() }
        .confirmationDialog(
            "Forget this Mac?", isPresented: $showingForget, titleVisibility: .visible
        ) {
            Button("Forget", role: .destructive) { app.forget() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The token is deleted from this device's Keychain. Conversations stay.")
        }
        .refreshable { await app.refreshReachability() }
    }
}
