import PhotosUI
import SwiftUI

/// The transcript, the composer, and everything that happens between a question and an
/// answer.
public struct ChatView: View {
    @Environment(AppModel.self) private var app
    @Bindable var model: ChatModel

    @State private var photoItem: PhotosPickerItem?
    @State private var showingCamera = false
    @State private var expandedReasoning: Set<String> = []
    @FocusState private var composerFocused: Bool

    public init(model: ChatModel) {
        self.model = model
    }

    public var body: some View {
        VStack(spacing: 0) {
            transcript
            composer
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(model.current?.title ?? "Chat")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        Task { await model.newConversation(using: app.transport) }
                    } label: {
                        Label("New conversation", systemImage: "square.and.pencil")
                    }
                    if let text = model.current?.messages.last(where: { $0.role == .assistant })?.content,
                       !text.isEmpty {
                        Button {
                            UIPasteboard.general.string = text
                        } label: {
                            Label("Copy last reply", systemImage: "doc.on.doc")
                        }
                        ShareLink(item: text) {
                            Label("Share last reply", systemImage: "square.and.arrow.up")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel("Conversation actions")
            }
        }
        .task { await model.loadConversations(using: app.transport) }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let image = UIImage(data: data),
                   let attachment = ImagePreparation.attachment(from: image) {
                    model.attachments.append(attachment)
                }
                photoItem = nil
            }
        }
        .sheet(isPresented: $showingCamera) {
            CameraPicker { image in
                if let attachment = ImagePreparation.attachment(from: image) {
                    model.attachments.append(attachment)
                }
            }
            .ignoresSafeArea()
        }
    }

    // MARK: - Transcript

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Theme.gap) {
                    if let conversation = model.current, !conversation.messages.isEmpty {
                        ForEach(conversation.messages) { message in
                            MessageBubble(
                                message: message,
                                isReasoningExpanded: expandedReasoning.contains(message.id),
                                toggleReasoning: {
                                    if expandedReasoning.contains(message.id) {
                                        expandedReasoning.remove(message.id)
                                    } else {
                                        expandedReasoning.insert(message.id)
                                    }
                                }
                            )
                            .id(message.id)
                        }
                    } else {
                        emptyState
                    }
                    Color.clear.frame(height: 1).id(bottomID)
                }
                .padding(Theme.gap)
            }
            .onChange(of: model.current?.messages.last?.content) { _, _ in
                withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo(bottomID, anchor: .bottom) }
            }
            .onChange(of: model.current?.messages.count) { _, _ in
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(bottomID, anchor: .bottom) }
            }
        }
    }

    private let bottomID = "bottom"

    private var emptyState: some View {
        VStack(spacing: Theme.gap) {
            Placeholder(
                title: "Ask your Mac",
                message: app.reachability.isReady
                    ? "The model loaded on the Mac answers here. Attach a picture for a vision model."
                    : app.reachability.detail,
                systemImage: "bubble.left.and.text.bubble.right"
            )
            Text(model.storageNote)
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(.top, 60)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Composer

    private var composer: some View {
        VStack(spacing: 8) {
            if !model.attachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(model.attachments) { attachment in
                            AttachmentThumb(attachment: attachment) {
                                model.attachments.removeAll { $0.id == attachment.id }
                            }
                        }
                    }
                    .padding(.horizontal, 2)
                }
                .frame(height: 64)
            }
            HStack(alignment: .bottom, spacing: 8) {
                Menu {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label("Photo Library", systemImage: "photo.on.rectangle")
                    }
                    if UIImagePickerController.isSourceTypeAvailable(.camera) {
                        Button {
                            showingCamera = true
                        } label: {
                            Label("Camera", systemImage: "camera")
                        }
                    }
                } label: {
                    Image(systemName: "paperclip")
                        .font(.title3)
                        .frame(width: 34, height: 34)
                }
                .accessibilityLabel("Attach a picture")

                TextField("Message", text: $model.draft, axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.background.secondary, in: RoundedRectangle(cornerRadius: 18))
                    .focused($composerFocused)
                    .submitLabel(.send)
                    .accessibilityLabel("Message")

                if model.isSending {
                    Button {
                        model.cancel()
                    } label: {
                        Image(systemName: "stop.circle.fill")
                            .font(.title)
                            .symbolRenderingMode(.hierarchical)
                    }
                    .accessibilityLabel("Stop generating")
                } else {
                    Button {
                        model.send(using: app.transport)
                        composerFocused = false
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.title)
                            .symbolRenderingMode(.hierarchical)
                    }
                    .disabled(
                        model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            && model.attachments.isEmpty
                    )
                    .accessibilityLabel("Send")
                }
            }
        }
        .padding(Theme.gap)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }
}

struct AttachmentThumb: View {
    let attachment: ChatAttachment
    let remove: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            if let image = UIImage(data: attachment.jpeg) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 60, height: 60)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            Button(action: remove) {
                Image(systemName: "xmark.circle.fill")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, .black.opacity(0.6))
            }
            .padding(2)
            .accessibilityLabel("Remove attachment")
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Attached picture")
    }
}

struct MessageBubble: View {
    let message: ChatMessage
    let isReasoningExpanded: Bool
    let toggleReasoning: () -> Void

    var body: some View {
        VStack(alignment: message.role == .user ? .trailing : .leading, spacing: 6) {
            if !message.images.isEmpty {
                HStack(spacing: 6) {
                    ForEach(Array(message.images.enumerated()), id: \.offset) { _, dataURL in
                        if let image = Self.image(from: dataURL) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 96, height: 96)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: message.role == .user ? .trailing : .leading)
            }

            if let reasoning = message.reasoning, !reasoning.isEmpty {
                ReasoningBlock(
                    text: reasoning, isExpanded: isReasoningExpanded, toggle: toggleReasoning
                )
            }

            if !message.content.isEmpty {
                Group {
                    if message.role == .user {
                        Text(message.content)
                            .textSelection(.enabled)
                    } else {
                        MarkdownText(message.content)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(
                    message.role == .user
                        ? AnyShapeStyle(Color.accentColor.opacity(0.18))
                        : AnyShapeStyle(.background.secondary),
                    in: RoundedRectangle(cornerRadius: 14)
                )
                .frame(maxWidth: .infinity, alignment: message.role == .user ? .trailing : .leading)
                .contextMenu {
                    Button {
                        UIPasteboard.general.string = message.content
                    } label: {
                        Label("Copy", systemImage: "doc.on.doc")
                    }
                    ShareLink(item: message.content) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                }
            }

            if message.isStreaming, message.content.isEmpty {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text("Thinking…").font(.caption).foregroundStyle(.secondary)
                }
            }

            if let failure = message.failure {
                Label(failure, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            if let metrics = message.metrics, metrics.generatedTokens > 0 {
                Text(
                    "\(metrics.generatedTokens) tokens · "
                        + Format.rate(metrics.tokensPerSecond)
                )
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .monospacedDigit()
            }
        }
        .frame(maxWidth: .infinity, alignment: message.role == .user ? .trailing : .leading)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(message.role == .user ? "You said" : "The model replied")
    }

    static func image(from dataURL: String) -> UIImage? {
        guard let comma = dataURL.firstIndex(of: ","),
              let data = Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...]))
        else { return nil }
        return UIImage(data: data)
    }
}

struct ReasoningBlock: View {
    let text: String
    let isExpanded: Bool
    let toggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(action: toggle) {
                HStack(spacing: 6) {
                    Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                    Text("Thinking")
                        .font(.caption.weight(.medium))
                    Spacer()
                }
                .foregroundStyle(.secondary)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isExpanded ? "Hide the model's thinking" : "Show the model's thinking")

            if isExpanded {
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
    }
}

/// The camera, for "what is this?" questions. A thin wrapper because SwiftUI has no
/// camera of its own.
struct CameraPicker: UIViewControllerRepresentable {
    let onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    @MainActor
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage { parent.onImage(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

/// The list of conversations: the Chat tab's root on iPhone, the sidebar's lower half
/// on iPad.
public struct ConversationListView: View {
    @Environment(AppModel.self) private var app
    @Bindable var model: ChatModel
    let openConversation: (String) -> Void

    public init(model: ChatModel, openConversation: @escaping (String) -> Void) {
        self.model = model
        self.openConversation = openConversation
    }

    public var body: some View {
        List {
            Section {
                ForEach(model.conversations) { summary in
                    Button {
                        openConversation(summary.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(summary.title).font(.body).lineLimit(1)
                            Text("\(summary.messageCount) messages · \(Format.relative(summary.updatedAt))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .onDelete { offsets in
                    let ids = offsets.map { model.conversations[$0].id }
                    Task {
                        for id in ids { await model.delete(id: id, using: app.transport) }
                    }
                }
            } footer: {
                Text(model.storageNote)
            }
        }
        .overlay {
            if model.conversations.isEmpty {
                Placeholder(
                    title: "No conversations",
                    message: "Start one and it stays here.",
                    systemImage: "bubble.left.and.bubble.right"
                )
            }
        }
        .navigationTitle("Chat")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task {
                        await model.newConversation(using: app.transport)
                        if let id = model.current?.id { openConversation(id) }
                    }
                } label: {
                    Image(systemName: "square.and.pencil")
                }
                .accessibilityLabel("New conversation")
            }
        }
        .task { await model.loadConversations(using: app.transport) }
        .refreshable { await model.loadConversations(using: app.transport) }
    }
}
