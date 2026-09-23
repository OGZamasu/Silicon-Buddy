import SwiftUI

/// Three ways to reach a Mac: scan the code it shows, type that same code in, or — from
/// the Simulator on the Mac itself — use the Mac's own control token.
///
/// Scan and Enter code both end at `POST /buddy/pair`, and they are how an iPhone or iPad
/// gets in. The token in the Mac's control.json is accepted on the Mac's loopback listener
/// and nowhere else, so over the tailnet it is refused whatever a device does with it.
/// Developer stays for the Simulator, which shares the Mac's loopback, and says so.
public struct PairingView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    @State private var mode: Mode = .scan
    @State private var cameraState: QRScannerView.CameraState = .scanning
    @State private var host = ""
    @State private var code = ""
    @State private var codePort = String(PairingInvite.defaultPort)
    @State private var developerHost = ""
    @State private var developerPort = ""
    @State private var token = ""
    @State private var status: Status = .idle
    @State private var confirmingReplacement = false

    enum Mode: String, CaseIterable, Identifiable {
        case scan = "Scan"
        case code = "Enter code"
        /// The Mac's own control token, which the Mac takes only from itself.
        case developer = "Developer"
        var id: String { rawValue }
    }

    enum Status: Equatable {
        case idle
        case working
        case failed(String)
        case paired(String)
    }

    /// What a Mac without `POST /buddy/pair` gets told, however its code was spent.
    static let macTooOldForCodes =
        "That Mac is too old for pairing codes. Update Silicon Optimizer on it, then pair again."

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
                case .code: codeForm
                case .developer: developerForm
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
            .onAppear {
                if host.isEmpty, let current = app.config { host = current.host }
            }
            .confirmationDialog(
                "Replace \(app.macDisplayName)?",
                isPresented: $confirmingReplacement,
                titleVisibility: .visible
            ) {
                // Only the form on screen can have asked, so it is the one that goes on.
                Button("Replace with \(mode == .developer ? developerHost : host)", role: .destructive) {
                    if mode == .developer { connect() } else { pairTyped() }
                }
                Button("Keep \(app.macDisplayName)", role: .cancel) {}
            } message: {
                Text(
                    "This device will stop talking to \(app.macDisplayName) and its token "
                        + "will be deleted from this device."
                )
            }
        }
    }

    // MARK: - Scan

    private var scanner: some View {
        ZStack {
            if cameraState == .scanning {
                QRScannerView(
                    onFound: { code in handleScan(code) },
                    onState: { state in
                        // A camera refused before now is reported while the scanner is
                        // still being built, so the change waits for that to finish.
                        Task { @MainActor in
                            cameraState = state
                            guard state == .denied else { return }
                            // Straight to the form that works without it, saying why.
                            mode = .code
                            status = .failed("Camera denied. Type the code your Mac shows instead.")
                        }
                    }
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
                            ? "Allow the camera in Settings, or type the code your Mac shows under the QR."
                            : "This device has no camera. Type the code your Mac shows under the QR.",
                        systemImage: "camera.badge.ellipsis"
                    )
                    Button("Enter the code by hand") { mode = .code }
                        .buttonStyle(.borderedProminent)
                        .foregroundStyle(Theme.onAccent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    /// A scanned code is a claim about which machine to trust, made by whoever printed
    /// the QR. It goes to the confirmation sheet, which names the host and — when a Mac
    /// is already paired — asks a second time before replacing it.
    private func handleScan(_ code: String) {
        do {
            let invite = try PairingInvite.parse(code)
            host = invite.host
            codePort = String(invite.port)
            app.pendingInvite = invite
            dismiss()
        } catch {
            status = .failed(error.localizedDescription)
            cameraState = .scanning
        }
    }

    // MARK: - Enter code

    private var codeForm: some View {
        Form {
            Section {
                LabeledContent("Code") {
                    TextField("123 456", text: $code)
                        .keyboardType(.numberPad)
                        .font(.body.monospacedDigit())
                        .multilineTextAlignment(.trailing)
                        .onChange(of: code) { _, typed in
                            let digits = PairingInvite.asciiDigits(typed, keepSpaces: true)
                            if digits != typed { code = digits }
                        }
                }
                LabeledContent("Address") {
                    TextField("100.x.y.z", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .multilineTextAlignment(.trailing)
                        .onChange(of: host) { _, typed in offer(pastedLink: typed) }
                }
                LabeledContent("Port") {
                    TextField(String(PairingInvite.defaultPort), text: $codePort)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .onChange(of: codePort) { _, typed in
                            let digits = PairingInvite.asciiDigits(typed)
                            if digits != typed { codePort = digits }
                        }
                }
            } header: {
                Text("The code on your Mac")
            } footer: {
                Text(
                    "In Silicon Optimizer, open Settings → Silicon Buddy and choose Pair a "
                        + "device. Type the six-digit code it shows and the address beside it; "
                        + "the port is \(String(PairingInvite.defaultPort)) unless your Mac says "
                        + "otherwise. A pairing link pasted into the address field is shown to you "
                        + "to confirm, as a scanned code is."
                )
            }

            Section {
                Button {
                    if app.isPaired, !PairingInvite.isLink(host) {
                        confirmingReplacement = true
                    } else {
                        pairTyped()
                    }
                } label: {
                    HStack {
                        Text(app.isPaired ? "Replace this Mac…" : "Pair")
                        Spacer()
                        if status == .working { ProgressView().controlSize(.small) }
                    }
                }
                .disabled(
                    host.trimmingCharacters(in: .whitespaces).isEmpty
                        || code.trimmingCharacters(in: .whitespaces).isEmpty
                        || status == .working
                )
            }

            forgetSection
        }
    }

    /// A pasted link is not something the person typed: whoever made it chose its host. So
    /// it goes where a scanned code goes — the confirmation, with its warning — and this
    /// form's Pair never sees it.
    private func offer(pastedLink text: String) {
        do {
            guard try app.holdPastedLink(text) else { return }
            dismiss()
        } catch {
            status = .failed(error.localizedDescription)
        }
    }

    /// A typed code goes where a scanned one does — `AppModel.pair(with:)`, and from there
    /// `POST /buddy/pair` — held to the same host rule. It skips the scan's confirmation,
    /// which is there because whoever printed a QR chose its host; here the person holding
    /// the device typed it. Replacing a paired Mac still asks first. A link left in the
    /// address field is the exception, and goes to that confirmation instead.
    private func pairTyped() {
        if PairingInvite.isLink(host) {
            offer(pastedLink: host)
            return
        }
        let invite: PairingInvite
        do {
            invite = try PairingInvite.typed(address: host, code: code, port: codePort)
        } catch {
            status = .failed(error.localizedDescription)
            return
        }
        status = .working
        Task {
            do {
                try await app.pair(with: invite)
                status = .paired(app.macDisplayName)
                try? await Task.sleep(for: .milliseconds(500))
                dismiss()
            } catch let error as TransportError where error.isMissingRoute {
                status = .failed(Self.macTooOldForCodes)
            } catch {
                status = .failed(error.localizedDescription)
            }
        }
    }

    // MARK: - Developer

    private var developerForm: some View {
        Form {
            Section {
                LabeledContent("Host") {
                    TextField("127.0.0.1", text: $developerHost)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .multilineTextAlignment(.trailing)
                }
                LabeledContent("Port") {
                    TextField("From control.json", text: $developerPort)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .onChange(of: developerPort) { _, typed in
                            let digits = PairingInvite.asciiDigits(typed)
                            if digits != typed { developerPort = digits }
                        }
                }
                LabeledContent("Token") {
                    SecureField("Control token", text: $token)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .multilineTextAlignment(.trailing)
                }
            } header: {
                Text("Local Simulator and development only")
            } footer: {
                Text(
                    "This is the Mac's own control token, from ~/Library/Application Support/"
                        + "SiliconOptimizer/control.json. The Mac accepts it only on its local "
                        + "listener, so it works from the iOS Simulator on that Mac (127.0.0.1, "
                        + "the port in control.json) and never from an iPhone or iPad over "
                        + "Tailscale. To pair a device, use Scan or Enter code."
                )
            }

            Section {
                Button {
                    if app.isPaired {
                        confirmingReplacement = true
                    } else {
                        connect()
                    }
                } label: {
                    HStack {
                        Text(app.isPaired ? "Replace this Mac…" : "Connect")
                        Spacer()
                        if status == .working { ProgressView().controlSize(.small) }
                    }
                }
                .disabled(
                    developerHost.isEmpty || token.isEmpty || Int(developerPort) == nil
                        || status == .working
                )
            }

            forgetSection
        }
    }

    @ViewBuilder
    private var forgetSection: some View {
        if app.isPaired {
            Section {
                Button("Forget this Mac", role: .destructive) {
                    app.forget()
                    status = .idle
                }
            }
        }
    }

    private func connect() {
        guard let portNumber = Int(developerPort), (1...65535).contains(portNumber) else {
            status = .failed("That port isn't a number between 1 and 65535.")
            return
        }
        let trimmedHost = developerHost.trimmingCharacters(in: .whitespaces)
        guard TailnetHost.isAllowed(trimmedHost) else {
            status = .failed(TailnetHost.explanation)
            return
        }
        status = .working
        Task {
            let candidate = ServerConfig(
                host: trimmedHost,
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
