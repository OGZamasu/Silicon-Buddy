import SwiftUI

@main
struct SiliconBuddyApp: App {
    @State private var app = AppModel()
    @State private var chat = ChatModel()
    @State private var badLink: LinkRefusal?

    var body: some Scene {
        WindowGroup {
            RootView(chat: chat)
                .environment(app)
                .task { await app.refreshReachability() }
                .onOpenURL { url in
                    // A link is a request, not an instruction. Anything can open a URL
                    // in this app — a web page, a message, a QR on a poster — so this
                    // only ever gets as far as asking. `PairingInvite.parse` has already
                    // refused any host that is not on the tailnet by the time we are here.
                    do {
                        app.pendingInvite = try PairingInvite.parse(url.absoluteString)
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
