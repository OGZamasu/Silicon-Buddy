import AVFoundation
import SwiftUI

/// The camera, looking for the QR code the Mac shows.
///
/// AVFoundation rather than a library: one metadata output, one delegate callback, and
/// the first code that parses as a pairing invite wins. The session is torn down as
/// soon as one does, so the camera light goes out when the work is done.
public struct QRScannerView: UIViewControllerRepresentable {
    public enum CameraState: Equatable, Sendable {
        case scanning
        case denied
        case unavailable
    }

    let onFound: (String) -> Void
    let onState: (CameraState) -> Void

    public init(onFound: @escaping (String) -> Void, onState: @escaping (CameraState) -> Void) {
        self.onFound = onFound
        self.onState = onState
    }

    public func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onFound = onFound
        controller.onState = onState
        return controller
    }

    public func updateUIViewController(_ controller: ScannerController, context: Context) {}
}

/// Hands the capture session to the camera queue. AVFoundation allows exactly this;
/// the compiler cannot know it.
private struct SessionBox: @unchecked Sendable {
    let session: AVCaptureSession
}

@MainActor
public final class ScannerController: UIViewController {
    var onFound: ((String) -> Void)?
    var onState: ((QRScannerView.CameraState) -> Void)?

    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var hasFound = false

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    guard let self else { return }
                    if granted { self.configure() } else { self.onState?(.denied) }
                }
            }
        default:
            onState?(.denied)
        }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else {
            onState?(.unavailable)
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            onState?(.unavailable)
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer

        onState?(.scanning)
        start()
    }

    private func start() {
        guard !session.isRunning else { return }
        // Starting a capture session blocks for a noticeable moment, so it never happens
        // on the main thread. AVCaptureSession is documented as safe to start and stop
        // from another queue, which is what the box asserts.
        let box = SessionBox(session: session)
        Self.cameraQueue.async { box.session.startRunning() }
    }

    static let cameraQueue = DispatchQueue(label: "dev.siliconoptimizer.buddy.camera")

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stop()
    }

    func stop() {
        guard session.isRunning else { return }
        let box = SessionBox(session: session)
        Self.cameraQueue.async { box.session.stopRunning() }
    }
}

extension ScannerController: AVCaptureMetadataOutputObjectsDelegate {
    public nonisolated func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        let codes = metadataObjects
            .compactMap { $0 as? AVMetadataMachineReadableCodeObject }
            .compactMap(\.stringValue)
        guard let code = codes.first else { return }
        Task { @MainActor [weak self] in
            guard let self, !self.hasFound else { return }
            self.hasFound = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            self.stop()
            self.onFound?(code)
        }
    }
}
