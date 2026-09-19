import AVFoundation
import SwiftUI
import UIKit

/// Point the camera at something and ask about it.
///
/// Not the system picker: the picker's flow is take, review, accept, and only then do
/// you get to say what you wanted to know. Here the question is typed first and the
/// shutter sends — which is what "what is this?" actually needs. The frame is scaled to
/// the Mac's per-image cap before it leaves, by the same code the composer uses.
@MainActor
@Observable
final class CameraModeModel {
    enum Phase: Equatable {
        case framing
        case sending
        case answered(String)
        case failed(String)
    }

    var prompt = "What is this?"
    private(set) var phase: Phase = .framing
    private(set) var captured: ChatAttachment?
    private(set) var streamed = ""
    private(set) var storedOnMac = false

    /// The model that will answer, so the sheet can warn when it cannot see.
    var visionWarning: String?

    private func append(_ piece: String) { streamed += piece }

    func retake() {
        captured = nil
        streamed = ""
        phase = .framing
    }

    func captured(_ image: UIImage, transport: (any ControlTransport)?) {
        guard let attachment = ImagePreparation.attachment(from: image) else {
            phase = .failed("That picture couldn't be prepared for sending.")
            return
        }
        captured = attachment
        ask(transport: transport)
    }

    func ask(transport: (any ControlTransport)?) {
        guard let attachment = captured else { return }
        let question = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        streamed = ""
        phase = .sending
        // Strong: the sheet is on screen for the length of one answer, and a model
        // released mid-stream would leave it showing "Thinking…" with nothing coming.
        Task { @MainActor in
            do {
                let outcome = try await OneShotAsk.send(
                    message: question.isEmpty ? "What is this?" : question,
                    images: [attachment.dataURL],
                    title: "Camera",
                    maxTokens: 1024,
                    using: transport,
                    onToken: { piece in
                        Task { @MainActor in self.append(piece) }
                    }
                )
                self.storedOnMac = outcome.storedOnMac
                self.phase = .answered(outcome.answer)
            } catch {
                self.phase = .failed(
                    (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                )
            }
        }
    }
}

struct CameraModeView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var model = CameraModeModel()
    @State private var cameraState: CameraCapture.State = .live
    @State private var shutter = false

    /// Set when the loaded model cannot see, so the sheet can say so before a photo is
    /// taken rather than after the answer comes back confused.
    let supportsVision: Bool?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                Color.black.ignoresSafeArea()

                if let captured = model.captured, let image = UIImage(data: captured.jpeg) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .ignoresSafeArea()
                } else {
                    CameraCapture(takePhoto: $shutter, onState: { cameraState = $0 }) { image in
                        model.captured(image, transport: app.transport)
                    }
                    .ignoresSafeArea()
                }

                controls
            }
            .navigationTitle("Camera")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                if model.captured != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Retake") { model.retake() }
                    }
                }
            }
        }
    }

    private var controls: some View {
        VStack(spacing: 10) {
            if supportsVision == false {
                Label(
                    "The model loaded on your Mac doesn't read pictures. Load one that does "
                        + "from Models, or the answer will be a guess.",
                    systemImage: "eye.slash"
                )
                .font(.caption)
                .foregroundStyle(.orange)
                .padding(8)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
            }

            answer

            if cameraState == .denied {
                Label(
                    "Silicon Buddy can't use the camera. Turn it on in Settings → Silicon Buddy.",
                    systemImage: "camera.badge.ellipsis"
                )
                .font(.caption)
                .foregroundStyle(.white)
            } else if cameraState == .unavailable {
                Label("This device has no camera.", systemImage: "camera.badge.ellipsis")
                    .font(.caption)
                    .foregroundStyle(.white)
            }

            HStack(spacing: 12) {
                TextField("What do you want to know?", text: $model.prompt, axis: .vertical)
                    .lineLimit(1...3)
                    .textFieldStyle(.plain)
                    .padding(10)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(.white)
                    .accessibilityLabel("Your question about the picture")

                if model.captured == nil {
                    Button {
                        shutter = true
                    } label: {
                        Image(systemName: "camera.circle.fill")
                            .font(.system(size: 46))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.black, .white)
                    }
                    .disabled(cameraState != .live)
                    .accessibilityLabel("Take a photo and ask")
                } else {
                    Button {
                        model.ask(transport: app.transport)
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 42))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.black, .white)
                    }
                    .disabled(isBusy)
                    .accessibilityLabel("Ask again")
                }
            }
        }
        .padding(16)
    }

    private var isBusy: Bool {
        if case .sending = model.phase { return true }
        return false
    }

    @ViewBuilder
    private var answer: some View {
        switch model.phase {
        case .sending:
            ScrollView {
                Text(model.streamed.isEmpty ? "Thinking…" : model.streamed)
                    .font(.callout)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 160)
            .padding(10)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))

        case .answered(let text):
            ScrollView {
                Text(text)
                    .font(.callout)
                    .foregroundStyle(.white)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 200)
            .padding(10)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))

        case .failed(let problem):
            Label(problem, systemImage: "exclamationmark.triangle")
                .font(.caption)
                .foregroundStyle(.orange)
                .padding(8)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))

        case .framing:
            EmptyView()
        }
    }
}
