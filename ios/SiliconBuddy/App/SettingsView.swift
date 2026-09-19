import SwiftUI

/// The connection, what this Mac can do, and the honest list of what is still stubbed.
public struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var quickPrompt = QuickPrompt.stored()
    /// The chat model itself, because "does this Mac stream?" is something only the
    /// thing that has tried to stream can answer.
    let chat: ChatModel
    @State private var showingPairing = false
    @State private var showingForget = false

    public init(chat: ChatModel) {
        self.chat = chat
    }

    public var body: some View {
        List {
            Section("Mac") {
                LabeledContent("Name", value: app.macDisplayName)
                if let config = app.config {
                    LabeledContent("Address", value: config.displayAddress)
                    LabeledContent(
                        "Token",
                        value: SharedKeychain.accessGroup == nil
                            ? "Stored in this app's Keychain"
                            : "Stored in the Keychain, shared with the widget"
                    )
                    .foregroundStyle(.secondary)
                    if let deviceID = config.deviceID {
                        LabeledContent("Device id", value: deviceID)
                            .font(.caption)
                    }
                }
                LabeledContent("Status", value: app.reachability.headline)
                LabeledContent(
                    "This device may",
                    value: app.scope == .full ? "Control the Mac" : "Chat and read only"
                )
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
                    value: chat.usesStreaming ? "Using /chat/stream" : "Falling back to /chat"
                )
                LabeledContent(
                    "Conversations",
                    value: chat.usesRemoteConversations ? "On the Mac" : "On this device"
                )
                LabeledContent(
                    "Live events",
                    value: app.events.isLive
                        ? "Streaming from /events"
                        : (app.events.mustPoll ? "Polling — this Mac has no /events" : "Not started")
                )
            } header: {
                Text("What this Mac supports")
            } footer: {
                Text(
                    app.scope == .full
                        ? "Silicon Buddy asks for the newer routes and falls back quietly when a "
                            + "Mac doesn't have them yet. Nothing here needs configuring."
                        : app.scope.explanation
                )
            }

            Section {
                Toggle("Read answers out loud", isOn: Binding(
                    get: { app.speaksReplies },
                    set: { app.speaksReplies = $0 }
                ))
                Picker("Widget question", selection: $quickPrompt) {
                    ForEach(QuickPrompt.presets, id: \.self) { preset in
                        Text(preset).tag(preset)
                    }
                    if !QuickPrompt.presets.contains(quickPrompt) {
                        Text(quickPrompt).tag(quickPrompt)
                    }
                }
                .onChange(of: quickPrompt) { _, value in QuickPrompt.store(value) }
            } header: {
                Text("Reaching in")
            } footer: {
                Text(
                    "The widget's button asks this question with one tap. Hold the "
                        + "microphone in a conversation to ask out loud; let go to send. "
                        + "Where this phone can recognise speech itself the audio never "
                        + "leaves it; where it cannot, the recording goes to Apple to be "
                        + "turned into text, and the composer says which is happening "
                        + "while you hold the button."
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
