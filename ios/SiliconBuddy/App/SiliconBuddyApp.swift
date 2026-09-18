import SwiftUI

@main
struct SiliconBuddyApp: App {
    @State private var app = AppModel()
    @State private var chat = ChatModel()
    @State private var pairingInvite: PairingInvite?

    var body: some Scene {
        WindowGroup {
            RootView(chat: chat)
                .environment(app)
                .task { await app.refreshReachability() }
                .onOpenURL { url in
                    // The Mac's QR is a link too: tapping it on the device pairs without
                    // a camera at all. If the Mac cannot pair yet, the sheet opens so the
                    // address can be entered by hand instead.
                    guard let invite = try? PairingInvite.parse(url.absoluteString) else { return }
                    Task {
                        do { try await app.pair(with: invite) }
                        catch { pairingInvite = invite }
                    }
                }
                .sheet(item: $pairingInvite) { _ in
                    PairingView().environment(app)
                }
        }
    }
}

extension PairingInvite: Identifiable {
    public var id: String { "\(host):\(port):\(code)" }
}
