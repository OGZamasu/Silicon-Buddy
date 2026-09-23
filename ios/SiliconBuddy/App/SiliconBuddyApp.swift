import SwiftUI

@main
struct SiliconBuddyApp: App {
    @State private var app = AppModel()
    @State private var chat = ChatModel()
    /// The Models list, held here rather than by its screen: a load it started is followed
    /// to its end while another screen is showing, and a re-pair reaches it wherever it is.
    @State private var models = ModelsModel()
    /// The render queue, held here for the same reason: a cancel waits up to a minute for
    /// the node, and leaving the screen must not forget that it is on its way.
    @State private var queue = QueueModel()
    @State private var badLink: LinkRefusal?

    var body: some Scene {
        WindowGroup {
            RootView(chat: chat, models: models, queue: queue)
                .environment(app)
                .task { await app.refreshReachability() }
                .onOpenURL { url in
                    // A link is a request, not an instruction. Anything can open a URL
                    // in this app — a web page, a message, a QR on a poster, this app's
                    // own widget — so this only ever gets as far as asking, or as far
                    // as filling the composer in. `PairingInvite.parse` has already
                    // refused any host that is not on the tailnet by the time we are here.
                    do {
                        switch try BuddyLink.parse(url.absoluteString) {
                        case .pair(let invite):
                            app.pendingInvite = invite
                        case .compose(let text):
                            app.pendingCompose = ComposeRequest(text: text)
                        case .conversation(let id):
                            app.pendingCompose = ComposeRequest(text: nil)
                            Task { await chat.open(id: id, using: app.transport) }
                        }
                    } catch {
                        badLink = LinkRefusal(message: error.localizedDescription)
                    }
                }
                .sheet(item: Binding(
                    get: { app.pendingInvite },
                    set: { app.pendingInvite = $0 }
                )) { invite in
                    PairingConfirmationView(invite: invite).environment(app)
                }
                .alert(item: $badLink) { refusal in
                    Alert(
                        title: Text("That link isn't a pairing code"),
                        message: Text(refusal.message),
                        dismissButton: .cancel(Text("OK"))
                    )
                }
        }
    }
}

/// A link this app would not follow, and why.
struct LinkRefusal: Identifiable {
    let id = UUID()
    let message: String
}

extension PairingInvite: Identifiable {
    public var id: String { "\(host):\(port):\(code)" }
}
