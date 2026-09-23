import SwiftUI

/// Everything the Mac can run: on its disk, in the catalog, or in the cloud.
public struct ModelsView: View {
    @Environment(AppModel.self) private var app
    @State private var model = ModelsModel()
    @State private var detail: ControlAPI.CatalogModel?

    public init() {}

    public var body: some View {
        @Bindable var model = model
        List {
            if let job = model.job {
                Section { JobRow(job: job) }
            }
            switch model.section {
            case .installed: installedSection
            case .catalog: catalogSection
            case .cloud: cloudSection
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.canvas)
        .navigationTitle("Models")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $model.search, placement: .navigationBarDrawer, prompt: "Search models")
        .refreshable { await model.refresh(using: app.transport) }
        .safeAreaInset(edge: .top) {
            Picker("Section", selection: $model.section) {
                ForEach(ModelsModel.Section.allCases) { section in
                    Text(section.rawValue).tag(section)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(Theme.canvas)
        }
        .task { await model.refresh(using: app.transport) }
        .onChange(of: app.connectionGeneration) { _, _ in
            model.reset()
            Task { await model.refresh(using: app.transport) }
        }
        .sheet(item: $detail) { entry in
            NavigationStack {
                CatalogDetailView(entry: entry, canControl: app.canControl) { quantization in
                    detail = nil
                    Task { await model.install(model: entry, quantization: quantization, using: app.transport) }
                }
            }
        }
        .alert(
            "Something went wrong",
            isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.clearError() } })
        ) {
            Button("OK", role: .cancel) { model.clearError() }
        } message: {
            Text(model.error ?? "")
        }
        .overlay {
            if model.installed.isEmpty, model.catalog.isEmpty, !model.isLoading {
                Placeholder(
                    title: "Nothing to show",
                    message: app.isPaired
                        ? "The Mac didn't answer. Pull to refresh."
                        : "Pair with a Mac first.",
                    systemImage: "square.stack.3d.up.slash"
                )
            }
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var installedSection: some View {
        if let loadedID = model.status?.loadedModelID {
            Section("Loaded") {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.status?.loadedModelName ?? loadedID)
                            .font(.headline)
                        Text(model.status?.state ?? "")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if app.canControl {
                        Button("Unload", role: .destructive) {
                            Task { await model.unload(using: app.transport) }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .disabled(model.job != nil)
                    }
                }
            }
        }
        Section {
            ForEach(model.filteredInstalled) { entry in
                InstalledRow(
                    entry: entry,
                    isLoaded: model.isLoaded(entry.id),
                    isBusy: model.job?.modelID == entry.id,
                    canControl: app.canControl,
                    download: app.events.download(forModel: entry.id)
                ) {
                    Task { await model.load(modelID: entry.id, using: app.transport) }
                }
            }
        } header: {
            Text("\(model.filteredInstalled.count) on disk")
        } footer: {
            Text(
                app.canControl
                    ? "Loading a model takes over the Mac's memory budget; the Mac unloads "
                        + "the previous one first."
                    : app.scope.explanation
            )
        }
    }

    @ViewBuilder
    private var catalogSection: some View {
        Section("\(model.filteredCatalog.count) in the catalog") {
            ForEach(model.filteredCatalog) { entry in
                Button {
                    detail = entry
                } label: {
                    CatalogRow(entry: entry)
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private var cloudSection: some View {
        if model.filteredCloud.isEmpty {
            Section {
                Placeholder(
                    title: "No cloud models yet",
                    message: "Cloud providers appear here once the Mac's catalog lists them. "
                        + "Everything the Mac runs locally is under Installed and Catalog.",
                    systemImage: "cloud"
                )
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
        } else {
            Section("\(model.filteredCloud.count) in the cloud") {
                ForEach(model.filteredCloud) { entry in
                    Button { detail = entry } label: { CatalogRow(entry: entry) }
                        .buttonStyle(.plain)
                }
            }
        }
    }
}

struct InstalledRow: View {
    let entry: ControlAPI.InstalledModel
    let isLoaded: Bool
    let isBusy: Bool
    /// False for a chat-scope device: the Mac would answer 403, so the button is not
    /// offered in the first place.
    var canControl = true
    /// A download the Mac is pushing for this model, when there is one.
    var download: BuddyAPI.DownloadProgress?
    let load: () -> Void

    var body: some View {
        HStack(spacing: Theme.gap) {
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.name)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Pill(entry.quantization)
                    Text(Format.bytes(entry.sizeOnDiskBytes))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                    if entry.supportsVision { Pill("Vision", tint: .blue, filled: true) }
                    if isLoaded { Pill("Loaded", tint: .green, filled: true) }
                }
            }
            Spacer(minLength: 0)
            if let download {
                VStack(alignment: .trailing, spacing: 2) {
                    ProgressView(value: download.progress ?? 0)
                        .frame(width: 90)
                    Text(Format.bytes(download.bytesReceived))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Downloading \(entry.name)")
                .accessibilityValue(
                    download.progress.map { Format.percent($0) } ?? "in progress"
                )
            } else if isBusy {
                ProgressView().controlSize(.small)
            } else if !isLoaded, canControl {
                Button("Load", action: load)
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(entry.name), \(entry.quantization), \(Format.bytes(entry.sizeOnDiskBytes))"
                + (entry.supportsVision ? ", vision" : "")
                + (isLoaded ? ", loaded" : "")
                + (canControl ? "" : ", this device may not load models")
        )
        .accessibilityAction(named: "Load") { if !isLoaded, canControl { load() } }
    }
}

struct CatalogRow: View {
    let entry: ControlAPI.CatalogModel

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                Text(entry.name)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                if entry.featured == true {
                    Image(systemName: "star.fill")
                        .font(.caption2)
                        .foregroundStyle(.yellow)
                        .accessibilityHidden(true)
                }
                Spacer(minLength: 0)
                if let verdict = entry.verdict {
                    Pill(verdict, tint: verdict.verdictTint, filled: true)
                }
            }
            Text(entry.summary)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            HStack(spacing: 6) {
                Pill(entry.parameters)
                Pill(entry.category)
                if let download = entry.recommendation?.downloadBytes {
                    Text(Format.bytes(download))
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .monospacedDigit()
                }
                if let rate = entry.recommendation?.estimatedGenerationTokensPerSecond {
                    Text(Format.rate(rate))
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .monospacedDigit()
                }
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens the details, where you can install it.")
    }
}

struct JobRow: View {
    let job: ModelsModel.Job

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(job.kind.rawValue.capitalized)
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(job.modelID)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            if let fraction = job.fraction {
                ProgressView(value: fraction)
            } else {
                ProgressView().progressViewStyle(.linear)
            }
            Text(job.message)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(job.kind.rawValue) \(job.modelID): \(job.message)")
    }
}

/// The catalog entry in full: what it is, what it would cost this Mac, and the button
/// that starts the download.
struct CatalogDetailView: View {
    let entry: ControlAPI.CatalogModel
    var canControl = true
    let install: (String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var quantization: String?

    var body: some View {
        List {
            Section {
                Text(entry.summary).font(.callout)
                if let note = entry.runtimeNote {
                    Label(note, systemImage: "wrench.and.screwdriver")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("\(entry.author) · \(entry.license)")
            }

            Section("Capabilities") {
                FlowLayout(spacing: 6) {
                    ForEach(entry.capabilities, id: \.self) { capability in
                        Pill(capability, tint: .blue, filled: true)
                    }
                }
                StatRow {
                    Stat("Parameters", entry.parameters)
                    if let active = entry.activeParameters { Stat("Active", active) }
                    Stat("Context", "\(entry.maxContext / 1024)K")
                    Stat("Rating", String(repeating: "★", count: max(0, min(5, entry.rating))))
                }
            }

            if !entry.quantizations.isEmpty {
                Section("Quantization") {
                    Picker("Quantization", selection: $quantization) {
                        ForEach(entry.quantizations, id: \.self) { quant in
                            Text(quant).tag(String?.some(quant))
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
            }

            if let recommendation = entry.recommendation {
                Section("What this Mac would do") {
                    Text(recommendation.rationale).font(.callout)
                    StatRow {
                        Stat("Verdict", recommendation.plan.verdict,
                             tint: recommendation.plan.verdict.verdictTint)
                        Stat("Download", Format.bytes(recommendation.downloadBytes))
                        Stat("Speed", Format.rate(recommendation.estimatedGenerationTokensPerSecond))
                    }
                    MeterRow(
                        label: "Resident memory",
                        detail: "\(Format.bytes(recommendation.plan.residentBytes)) of "
                            + Format.bytes(recommendation.plan.budgetBytes),
                        fraction: recommendation.plan.budgetBytes > 0
                            ? Double(recommendation.plan.residentBytes)
                                / Double(recommendation.plan.budgetBytes)
                            : 0,
                        tint: recommendation.plan.verdict.verdictTint
                    )
                    ForEach(recommendation.plan.suggestions, id: \.title) { suggestion in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(suggestion.title).font(.subheadline.weight(.medium))
                            Text(suggestion.detail).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    ForEach(recommendation.plan.notes, id: \.self) { note in
                        Text(note).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle(entry.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Close") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                if canControl {
                    Button("Install") { install(quantization) }
                        .buttonStyle(.borderedProminent)
                        .foregroundStyle(Theme.onAccent)
                }
            }
        }
        .onAppear {
            quantization = entry.recommendation?.quantization ?? entry.quantizations.first
        }
    }
}

extension String {
    /// The Mac's verdict words, in colour.
    var verdictTint: Color {
        switch lowercased() {
        case let text where text.contains("comfortable"): .green
        case let text where text.contains("tight"): .orange
        case let text where text.contains("swap"), let text where text.contains("won't"): .red
        default: .secondary
        }
    }
}
