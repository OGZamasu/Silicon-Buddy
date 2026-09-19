import SwiftUI

/// The compact composer the share sheet shows.
///
/// Small on purpose: a share sheet is not the app, and the only decisions worth making
/// here are what to ask and whether to send it. Everything else — the transcript, the
/// model picker — is a tap away in the app and does not belong on top of Safari.
struct ShareComposerView: View {
    @Bindable var model: ShareModel
    let close: () -> Void

    @FocusState private var promptFocused: Bool

    var body: some View {
        NavigationStack {
            Group {
                switch model.phase {
                case .reading:
                    ProgressView("Reading what you shared…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .ready, .sending, .answered, .failed:
                    content
                }
            }
            .navigationTitle("Ask your Mac")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: close)
                }
                ToolbarItem(placement: .confirmationAction) {
                    switch model.phase {
                    case .answered:
                        Button("Done", action: close)
                    case .sending:
                        ProgressView().controlSize(.small)
                    default:
                        Button("Send") { model.send() }
                            .disabled(!model.isPaired || model.prompt
                                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if !model.isPaired {
                    Label(
                        "Silicon Buddy isn't paired with a Mac yet. Open the app and pair first.",
                        systemImage: "desktopcomputer.trianglebadge.exclamationmark"
                    )
                    .font(.footnote)
                    .foregroundStyle(.orange)
                }

                TextField("What should the model do with this?", text: $model.prompt, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.plain)
                    .padding(10)
                    .background(.background.secondary, in: RoundedRectangle(cornerRadius: 12))
                    .focused($promptFocused)
                    .disabled(isBusy)
                    .accessibilityLabel("Your question")

                sharedSummary

                if let note = model.draft.note {
                    Label(note, systemImage: "scissors")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                answer
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
    }

    private var isBusy: Bool {
        if case .sending = model.phase { return true }
        if case .answered = model.phase { return true }
        return false
    }

    @ViewBuilder
    private var sharedSummary: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.draft.summary)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)

            if !model.draft.images.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(model.draft.images.enumerated()), id: \.offset) { _, dataURL in
                            if let image = SharedPreview.image(from: dataURL) {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 64, height: 64)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                        }
                    }
                }
                .frame(height: 68)
            }

            if !model.draft.quoted.isEmpty {
                Text(model.draft.quoted)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
            }
        }
    }

    @ViewBuilder
    private var answer: some View {
        switch model.phase {
        case .sending:
            VStack(alignment: .leading, spacing: 6) {
                if model.streamed.isEmpty {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text("Thinking…").font(.caption).foregroundStyle(.secondary)
                    }
                } else {
                    Text(model.streamed).font(.callout).textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

        case .answered(let text):
            VStack(alignment: .leading, spacing: 8) {
                Text(text)
                    .font(.callout)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(.background.secondary, in: RoundedRectangle(cornerRadius: 12))
                HStack(spacing: 12) {
                    Button {
                        UIPasteboard.general.string = text
                    } label: {
                        Label("Copy", systemImage: "doc.on.doc")
                    }
                    .font(.caption)
                    Text(
                        model.storedOnMac
                            ? "Saved to a conversation on your Mac."
                            : "This Mac doesn't store conversations, so this stays here."
                    )
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                }
            }

        case .failed(let problem):
            Label(problem, systemImage: "exclamationmark.triangle")
                .font(.footnote)
                .foregroundStyle(.orange)
                .frame(maxWidth: .infinity, alignment: .leading)

        default:
            EmptyView()
        }
    }
}

enum SharedPreview {
    static func image(from dataURL: String) -> UIImage? {
        guard let comma = dataURL.firstIndex(of: ","),
              let data = Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...]))
        else { return nil }
        return UIImage(data: data)
    }
}
