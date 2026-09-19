import AppIntents
import SwiftUI
import WidgetKit

/// The Home Screen widget's face.
///
/// Two sizes of the same three facts: which Mac, what is loaded on it, and the last
/// thing it said. The small one gives the whole surface to opening the composer; the
/// medium one has room to show the buttons it offers.
struct BuddyWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: BuddyWidgetEntry

    var body: some View {
        switch family {
        case .systemSmall: small
        default: medium
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 4) {
            header
            if let body = entry.body(limit: 90) {
                Text(body)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(4)
            } else {
                Text(entry.problem ?? "Tap to ask your Mac.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            }
            Spacer(minLength: 2)
            if entry.isPaired {
                // One button, and the rest of the surface opens the composer.
                Button(intent: AskQuickPromptIntent(prompt: entry.quickPrompt)) {
                    Label("Ask", systemImage: "sparkles")
                        .font(.caption2.weight(.semibold))
                        .lineLimit(1)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(BuddyLink.composeURL())
    }

    private var medium: some View {
        VStack(alignment: .leading, spacing: 6) {
            header
            if let body = entry.body(limit: 220) {
                Text(body)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(4)
            } else {
                Text(entry.problem ?? "Ask your Mac a question without opening the app.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 2)
            HStack(spacing: 8) {
                // A deep link rather than an intent: this one is meant to open the app,
                // with the composer focused and nothing sent.
                Link(destination: BuddyLink.composeURL() ?? URL(string: "siliconbuddy://ask")!) {
                    Label("Ask", systemImage: "square.and.pencil")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)

                if entry.isPaired {
                    Button(intent: AskQuickPromptIntent(prompt: entry.quickPrompt)) {
                        Label(
                            BuddySnapshot.trim(entry.quickPrompt, to: 26),
                            systemImage: "sparkles"
                        )
                        .font(.caption)
                        .lineLimit(1)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                if entry.quickAnswer != nil {
                    Button(intent: ClearQuickAnswerIntent()) {
                        Image(systemName: "xmark")
                            .font(.caption2)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .accessibilityLabel("Clear the answer")
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var header: some View {
        HStack(spacing: 5) {
            Image(systemName: entry.isPaired ? "desktopcomputer" : "desktopcomputer.trianglebadge.exclamationmark")
                .font(.caption2)
                .foregroundStyle(entry.problem == nil ? Color.accentColor : .orange)
            Text(entry.headline)
                .font(.caption.weight(.semibold))
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            entry.isPaired
                ? "Loaded on \(entry.snapshot?.macName ?? "your Mac"): \(entry.headline)"
                : "Not paired with a Mac"
        )
    }
}

/// The Lock Screen accessory. One line: what is loaded.
struct LoadedModelView: View {
    @Environment(\.widgetFamily) private var family
    let entry: BuddyWidgetEntry

    var body: some View {
        switch family {
        case .accessoryInline:
            Text(entry.headline)
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Image(systemName: "cpu").font(.caption2)
                    Text(initials).font(.caption2.weight(.semibold)).lineLimit(1)
                }
            }
            .accessibilityLabel("Loaded on your Mac: \(entry.headline)")
        default:
            VStack(alignment: .leading, spacing: 2) {
                Label("On your Mac", systemImage: "desktopcomputer")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(entry.headline)
                    .font(.headline)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .widgetURL(BuddyLink.composeURL())
        }
    }

    /// "Qwen3 4B" in a circle is illegible; "Q4" is not. Initials of the first two
    /// words, which is what a model name's first two words usually are.
    private var initials: String {
        guard let name = entry.snapshot?.loadedModelName, !name.isEmpty else { return "—" }
        return name.split(separator: " ").prefix(2).compactMap { $0.first }.map(String.init)
            .joined()
            .uppercased()
    }
}
