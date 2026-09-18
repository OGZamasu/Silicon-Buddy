import SwiftUI

/// Two ways to reach a Mac: scan the code it shows, or type the address in.
///
/// The advanced form is not a debugging leftover — it is the path that works today,
/// before the Mac has `POST /buddy/pair`, and it stays afterwards for anyone whose Mac
/// is on a screen they cannot point a camera at.
public struct PairingView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    @State private var mode: Mode = .scan
    @State private var cameraState: QRScannerView.CameraState = .scanning
    @State private var host = ""
    @State private var port = "8788"
    @State private var token = ""
    @State private var status: Status = .idle

    enum Mode: String, CaseIterable, Identifiable {
        case scan = "Scan"
        case advanced = "Advanced"
        var id: String { rawValue }
    }

    enum Status: Equatable {
        case idle
        case working
        case failed(String)
        case paired(String)
    }

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("How", selection: $mode) {
                    ForEach(Mode.allCases) { mode in Text(mode.rawValue).tag(mode) }
                }
                .pickerStyle(.segmented)
                .padding()

                switch mode {
                case .scan: scanner
                case .advanced: advancedForm
                }
            }
            .navigationTitle("Pair with a Mac")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .overlay(alignment: .bottom) { statusBar }
        }
    }

    // MARK: - Scan

    private var scanner: some View {
        ZStack {
            if cameraState == .scanning {
                QRScannerView(
                    onFound: { code in handleScan(code) },
                    onState: { cameraState = $0 }
                )
                .ignoresSafeArea(edges: .bottom)
                .overlay(alignment: .top) {
                    Text("Point at the code in Silicon Optimizer → Settings → Silicon Buddy")
                        .font(.footnote)
                        .padding(10)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.top, 8)
                }
                .accessibilityLabel("Camera looking for a pairing code")
            } else {
                VStack(spacing: Theme.gap) {
                    Placeholder(
                        title: cameraState == .denied ? "Camera is off" : "No camera here",
                        message: cameraState == .denied
                            ? "Allow the camera in Settings, or use Advanced to type the address."
                            : "This device has no camera. Use Advanced to type the address in.",
                        systemImage: "camera.badge.ellipsis"
                    )
                    Button("Enter it by hand") { mode = .advanced }
                        .buttonStyle(.borderedProminent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private func handleScan(_ code: String) {
        do {
            let invite = try PairingInvite.parse(code)
            host = invite.host
            port = String(invite.port)
            status = .working
            Task {
                do {
                    try await app.pair(with: invite)
                    status = .paired(app.macDisplayName)
                    try? await Task.sleep(for: .milliseconds(700))
                    dismiss()
                } catch let error as TransportError where error.isMissingRoute {
                    mode = .advanced
                    status = .failed(
                        "This Mac doesn't support pairing yet. Enter its control token instead."
                    )
                } catch {
                    status = .failed(error.localizedDescription)
                    cameraState = .scanning
                }
            }
        } catch {
            status = .failed(error.localizedDescription)
        }
    }

    // MARK: - Advanced

    private var advancedForm: some View {
        Form {
            Section {
                LabeledContent("Host") {
                    TextField("100.x.y.z or 127.0.0.1", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .multilineTextAlignment(.trailing)
                }
                LabeledContent("Port") {
                    TextField("8788", text: $port)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                }
                LabeledContent("Token") {
                    SecureField("Control token", text: $token)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .multilineTextAlignment(.trailing)
                }
            } header: {
                Text("Silicon Optimizer")
            } footer: {
                Text(
                    "The Mac writes these to ~/Library/Application Support/SiliconOptimizer/"
                        + "control.json when it starts. On the iOS Simulator the Mac is 127.0.0.1; "
                        + "on a device it is the Mac's tailnet address."
                )
            }

            Section {
                Button {
                    connect()
                } label: {
                    HStack {
                        Text("Connect")
                        Spacer()
                        if status == .working { ProgressView().controlSize(.small) }
                    }
                }
                .disabled(host.isEmpty || token.isEmpty || Int(port) == nil || status == .working)
            }

            if app.isPaired {
                Section {
                    Button("Forget this Mac", role: .destructive) {
                        app.forget()
                        status = .idle
                    }
                }
            }
        }
    }

    private func connect() {
        guard let portNumber = Int(port), (1...65535).contains(portNumber) else {
            status = .failed("That port isn't a number between 1 and 65535.")
            return
        }
        status = .working
        Task {
            let candidate = ServerConfig(
                host: host.trimmingCharacters(in: .whitespaces),
                port: portNumber,
                token: token.trimmingCharacters(in: .whitespaces)
            )
            // Prove it works before storing it: a saved address that does not answer is
            // worse than no address at all.
            let probe = ConnectivityProbe(transport: ControlClient(config: candidate))
            let result = await probe.check()
            switch result {
            case .ready:
                do {
                    try app.connect(candidate)
                    await app.refreshReachability()
                    if let name = try? await ControlClient(config: candidate).node().name {
                        app.noteMacName(name)
                    }
                    status = .paired(app.macDisplayName)
                    try? await Task.sleep(for: .milliseconds(500))
                    dismiss()
                } catch {
                    status = .failed("Couldn't save the token: \(error.localizedDescription)")
                }
            default:
                status = .failed(result.detail)
            }
        }
    }

    // MARK: - Status

    @ViewBuilder
    private var statusBar: some View {
        switch status {
        case .idle, .working:
            EmptyView()
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle")
                .font(.footnote)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.thinMaterial)
                .transition(.move(edge: .bottom))
        case .paired(let name):
            Label("Paired with \(name)", systemImage: "checkmark.circle.fill")
                .font(.footnote)
                .foregroundStyle(.green)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.thinMaterial)
        }
    }
}
