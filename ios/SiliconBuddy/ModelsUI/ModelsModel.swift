import Foundation
import Observation

/// The model list: what is on the Mac's disk, what the catalog offers, and what is
/// running somewhere else.
@MainActor
@Observable
public final class ModelsModel {
    public enum Section: String, CaseIterable, Identifiable, Sendable {
        case installed = "Installed"
        case catalog = "Catalog"
        case cloud = "Cloud"
        public var id: String { rawValue }
    }

    /// What a long-running model operation is doing, so the row can say so.
    public struct Job: Equatable, Sendable {
        public enum Kind: String, Sendable { case load, unload, install }
        public var modelID: String
        public var kind: Kind
        public var message: String
        /// 0–1 when the Mac tells us enough to know; nil while it is indeterminate.
        public var fraction: Double?
    }

    public private(set) var installed: [ControlAPI.InstalledModel] = []
    public private(set) var catalog: [ControlAPI.CatalogModel] = []
    public private(set) var status: ControlAPI.Status?
    public private(set) var isLoading = false
    public private(set) var job: Job?
    public private(set) var error: String?
    public var search = ""
    public var section: Section = .installed

    private var poller: Task<Void, Never>?

    public init() {}

    public func refresh(using transport: (any ControlTransport)?) async {
        guard let transport else { return }
        isLoading = true
        defer { isLoading = false }
        async let installedTask = transport.installed()
        async let catalogTask = transport.catalog(category: nil, onlyRunnable: false)
        async let statusTask = transport.status()
        let newInstalled = try? await installedTask
        let newCatalog = try? await catalogTask
        let newStatus = try? await statusTask
        if newInstalled == nil, newCatalog == nil {
            error = "Couldn't read the model list."
        } else {
            error = nil
        }
        if let newInstalled { installed = newInstalled }
        if let newCatalog { catalog = newCatalog }
        if let newStatus { status = newStatus }
    }

    // MARK: - Filtering

    public var filteredInstalled: [ControlAPI.InstalledModel] {
        guard !search.isEmpty else { return installed }
        return installed.filter { $0.matches(search) }
    }

    /// Catalog entries that are not cloud-hosted, newest-first by the Mac's own order
    /// with anything featured lifted to the top.
    public var filteredCatalog: [ControlAPI.CatalogModel] {
        let local = catalog.filter { !$0.isCloud }
        let matched = search.isEmpty ? local : local.filter { $0.matches(search) }
        return matched.sorted { lhs, rhs in
            if (lhs.featured ?? false) != (rhs.featured ?? false) { return lhs.featured ?? false }
            return lhs.rating > rhs.rating
        }
    }

    /// Cloud providers, once the Mac's catalog carries them. Empty until then, and the
    /// section says so rather than pretending the feature is missing from this app.
    public var filteredCloud: [ControlAPI.CatalogModel] {
        let cloud = catalog.filter(\.isCloud)
        return search.isEmpty ? cloud : cloud.filter { $0.matches(search) }
    }

    public var categories: [String] {
        Array(Set(catalog.map(\.category))).sorted()
    }

    public func isLoaded(_ id: String) -> Bool {
        guard let loaded = status?.loadedModelID else { return false }
        // An installed id carries its quantization ("model@Q4_K_M"); the status may
        // report either spelling.
        return loaded == id || loaded.hasPrefix(id + "@") || id.hasPrefix(loaded + "@")
    }

    // MARK: - Operations

    public func load(
        modelID: String, quantization: String? = nil, using transport: (any ControlTransport)?
    ) async {
        guard let transport else { return }
        job = Job(modelID: modelID, kind: .load, message: "Loading…", fraction: nil)
        defer { job = nil }
        do {
            let result = try await transport.load(
                ControlAPI.LoadRequest(modelID: modelID, quantization: quantization)
            )
            status = result
            await refresh(using: transport)
        } catch {
            self.error = (error as? TransportError)?.localizedDescription
                ?? error.localizedDescription
        }
    }

    public func unload(using transport: (any ControlTransport)?) async {
        guard let transport else { return }
        job = Job(modelID: status?.loadedModelID ?? "", kind: .unload, message: "Unloading…", fraction: nil)
        defer { job = nil }
        do {
            try await transport.unload()
            await refresh(using: transport)
        } catch {
            self.error = (error as? TransportError)?.localizedDescription
                ?? error.localizedDescription
        }
    }

    /// Starts a download and follows it by polling.
    ///
    /// `POST /install` answers as soon as the download starts, so progress has to be
    /// inferred: `/installed` shows the file growing, `/status` shows the app's own
    /// wording. When `GET /events` lands in M0 this becomes a fallback.
    public func install(
        model: ControlAPI.CatalogModel, quantization: String?,
        using transport: (any ControlTransport)?
    ) async {
        guard let transport else { return }
        let quant = quantization ?? model.recommendation?.quantization
        let expected = model.recommendation?.downloadBytes
        job = Job(modelID: model.id, kind: .install, message: "Asking the Mac…", fraction: nil)
        do {
            let message = try await transport.install(
                ControlAPI.LoadRequest(modelID: model.id, quantization: quant)
            )
            job = Job(modelID: model.id, kind: .install, message: message, fraction: nil)
            await followInstall(of: model.id, expecting: expected, using: transport)
        } catch {
            self.error = (error as? TransportError)?.localizedDescription
                ?? error.localizedDescription
            job = nil
        }
    }

    /// Polls until the model appears in `/installed` and stops growing.
    private func followInstall(
        of modelID: String, expecting expected: Int64?, using transport: any ControlTransport
    ) async {
        poller?.cancel()
        let task = Task { [weak self] in
            var settled = 0
            var lastSize: Int64 = -1
            for _ in 0..<600 { // Up to ~20 minutes at 2s.
                try? await Task.sleep(for: .seconds(2))
                if Task.isCancelled { return }
                guard let self else { return }
                let list = (try? await transport.installed()) ?? []
                let state = try? await transport.status()
                let entry = list.first { $0.id == modelID || $0.id.hasPrefix(modelID + "@") }
                let size = entry?.sizeOnDiskBytes ?? 0
                let fraction = expected.map { total in
                    total > 0 ? min(1, Double(size) / Double(total)) : 0
                }
                self.installed = list
                if let state { self.status = state }
                self.job = Job(
                    modelID: modelID, kind: .install,
                    message: size > 0
                        ? "\(Format.bytes(size)) of \(Format.bytes(expected)) downloaded"
                        : (state?.state ?? "Downloading…"),
                    fraction: fraction
                )
                if entry != nil, size == lastSize {
                    settled += 1
                    if settled >= 2 { break } // Two identical readings: it stopped growing.
                } else {
                    settled = 0
                }
                lastSize = size
            }
            guard let self else { return }
            self.job = nil
            await self.refresh(using: transport)
        }
        poller = task
        await task.value
    }

    public func cancelPolling() {
        poller?.cancel()
        poller = nil
        job = nil
    }

    public func clearError() { error = nil }
}

extension ControlAPI.InstalledModel {
    func matches(_ query: String) -> Bool {
        let needle = query.lowercased()
        return name.lowercased().contains(needle)
            || id.lowercased().contains(needle)
            || quantization.lowercased().contains(needle)
    }
}

extension ControlAPI.CatalogModel {
    func matches(_ query: String) -> Bool {
        let needle = query.lowercased()
        return name.lowercased().contains(needle)
            || id.lowercased().contains(needle)
            || author.lowercased().contains(needle)
            || summary.lowercased().contains(needle)
            || category.lowercased().contains(needle)
            || capabilities.contains { $0.lowercased().contains(needle) }
    }

    /// The Mac has no cloud entries yet; when it grows them, this is where they are
    /// recognised — by category or by an id that names a provider rather than a file.
    var isCloud: Bool {
        if category.lowercased() == "cloud" { return true }
        let providers = ["openai:", "anthropic:", "google:", "xai:", "groq:", "cloud:"]
        return providers.contains { id.lowercased().hasPrefix($0) }
    }

    /// What the Mac thinks this machine would do with it.
    var verdict: String? { recommendation?.plan.verdict }
}
