import AVFoundation
import SwiftUI
import UIKit

/// A live camera preview with a shutter, in the twenty lines AVFoundation needs.
///
/// The system picker would be less code, but it owns the whole screen and its flow ends
/// in "Use Photo" — there is nowhere to put the question. This keeps the preview behind
/// the app's own controls, which is the point of camera mode.
struct CameraCapture: UIViewControllerRepresentable {

    enum State: Equatable, Sendable {
        case live
        case denied
        case unavailable
    }

    /// Set to true to take a picture; the controller sets it back.
    @Binding var takePhoto: Bool
    let onState: (State) -> Void
    let onImage: (UIImage) -> Void

    func makeUIViewController(context: Context) -> CameraController {
        let controller = CameraController()
        controller.onState = onState
        controller.onImage = onImage
        return controller
    }

    func updateUIViewController(_ controller: CameraController, context: Context) {
        guard takePhoto else { return }
        // Reset first: the binding is written from a view update, and leaving it true
        // would fire the shutter again on the next one.
        Task { @MainActor in takePhoto = false }
        controller.capture()
    }
}

/// Hands the session to the camera queue. AVFoundation allows exactly this; the
/// compiler cannot know it.
private struct SessionBox: @unchecked Sendable {
    let session: AVCaptureSession
}

@MainActor
final class CameraController: UIViewController {

    var onState: ((CameraCapture.State) -> Void)?
    var onImage: ((UIImage) -> Void)?

    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private var preview: AVCaptureVideoPreviewLayer?
    private var delegate: PhotoDelegate?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    guard let self else { return }
                    granted ? self.configure() : self.onState?(.denied)
                }
            }
        default:
            onState?(.denied)
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // The camera light goes out when the sheet closes, not when ARC gets round to it.
        let box = SessionBox(session: session)
        Task.detached { box.session.stopRunning() }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(
            .builtInWideAngleCamera, for: .video, position: .back
        ) ?? AVCaptureDevice.default(for: .video),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input), session.canAddOutput(output)
        else {
            onState?(.unavailable)
            return
        }
        session.beginConfiguration()
        session.sessionPreset = .photo
        session.addInput(input)
        session.addOutput(output)
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer

        onState?(.live)
        let box = SessionBox(session: session)
        Task.detached { box.session.startRunning() }
    }

    func capture() {
        guard session.isRunning else { return }
        let delegate = PhotoDelegate { [weak self] image in
            self?.onImage?(image)
        }
        // Held for the length of the capture: AVFoundation keeps only a weak reference
        // to the delegate, and a dropped one means a photo that never arrives.
        self.delegate = delegate
        output.capturePhoto(with: AVCapturePhotoSettings(), delegate: delegate)
    }
}

private final class PhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    private let handler: @MainActor (UIImage) -> Void

    init(handler: @escaping @MainActor (UIImage) -> Void) {
        self.handler = handler
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        guard error == nil, let data = photo.fileDataRepresentation(),
              let image = UIImage(data: data)
        else { return }
        let handler = handler
        Task { @MainActor in handler(image) }
    }
}
