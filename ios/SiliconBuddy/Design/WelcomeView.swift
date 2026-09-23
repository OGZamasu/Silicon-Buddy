import SwiftUI

/// First-run orientation, with one clear action and no invented machine status.
struct WelcomeView: View {
    let onPair: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 10) {
                Text("YOUR PRIVATE AI")
                    .font(.caption.weight(.semibold))
                    .tracking(1.5)
                    .foregroundStyle(Color.accentColor)
                Text("Your AI.\nWithin reach.")
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .fixedSize(horizontal: false, vertical: true)
                Text("Bring your Mac's intelligence along. Chat, explore models, and check in from wherever you are.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 16) {
                device("desktopcomputer", label: "Your Mac")
                Rectangle().fill(Color.white.opacity(0.3)).frame(height: 1).accessibilityHidden(true)
                Image(systemName: "wifi").foregroundStyle(Color(red: 0.74, green: 0.96, blue: 0.87))
                    .accessibilityHidden(true)
                Rectangle().fill(Color.white.opacity(0.3)).frame(height: 1).accessibilityHidden(true)
                device("iphone", label: "With you")
            }
            .padding(28)
            .frame(maxWidth: .infinity)
            .background(Color(red: 0.08, green: 0.24, blue: 0.22), in: RoundedRectangle(cornerRadius: 28))

            Button(action: onPair) {
                HStack {
                    Text("Pair with a Mac")
                    Spacer()
                    // Decoration: without this VoiceOver reads the arrow out after the title.
                    Image(systemName: "arrow.right")
                        .accessibilityHidden(true)
                }
                .font(.headline)
                .padding(.vertical, 10)
            }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(Theme.onAccent)
            .controlSize(.large)
            .buttonBorderShape(.roundedRectangle(radius: 18))

            Card("A little closer to your Mac", systemImage: "sparkles") {
                feature("bubble.left.and.bubble.right", title: "Pick up a conversation", detail: "Ask the models you already run.")
                feature("square.stack.3d.up", title: "Find the right model", detail: "Explore and manage your Mac's library.")
                feature("wifi", title: "Keep it on your network", detail: "Connect directly over your own tailnet.")
            }
        }
        .padding(.vertical, 8)
    }

    private func device(_ symbol: String, label: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 42, weight: .light))
                .foregroundStyle(Color(red: 0.74, green: 0.96, blue: 0.87))
                .accessibilityHidden(true)
            Text(label).font(.caption.weight(.medium)).foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
        }
        .layoutPriority(1)
    }

    private func feature(_ symbol: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).foregroundStyle(Color.accentColor).frame(width: 24)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }
}
