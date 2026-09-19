import Foundation

/// The Mac app's control API, mirrored field for field.
///
/// The source of truth lives in the Silicon Optimizer repository
/// (`Sources/SiliconControl/ControlAPI.swift`). Nothing here may drift from it: the
/// fixtures in `contract/` are real responses from a running Mac, and
/// `ContractRoundTripTests` fails the build if a field goes missing on this side.
///
/// Optionality is copied exactly, including the fields the Mac marks optional so an
/// older client keeps decoding. Anything the Mac can omit must be optional here.
public enum ControlAPI {

    // MARK: - Handshake

    /// What the Mac writes to `~/Library/Application Support/SiliconOptimizer/control.json`.
    /// Only reachable from the Mac itself; the phone gets the same pair of facts from
    /// pairing instead.
    public struct Handshake: Codable, Sendable, Equatable {
        public var port: Int
        public var pid: Int32?
        public var token: String
        public var version: String

        public init(port: Int, pid: Int32? = nil, token: String, version: String) {
            self.port = port
            self.pid = pid
            self.token = token
            self.version = version
        }
    }

    // MARK: - Responses

    public struct Profile: Codable, Sendable, Equatable {
        public var chip: String
        public var generation: String
        public var totalMemoryBytes: Int64
        public var modelBudgetBytes: Int64
        public var performanceCores: Int
        public var efficiencyCores: Int
        public var gpuCores: Int
        public var neuralEngineCores: Int
        public var memoryBandwidthGBps: Double
        public var diskFreeBytes: Int64

        public init(
            chip: String, generation: String, totalMemoryBytes: Int64, modelBudgetBytes: Int64,
            performanceCores: Int, efficiencyCores: Int, gpuCores: Int, neuralEngineCores: Int,
            memoryBandwidthGBps: Double, diskFreeBytes: Int64
        ) {
            self.chip = chip
            self.generation = generation
            self.totalMemoryBytes = totalMemoryBytes
            self.modelBudgetBytes = modelBudgetBytes
            self.performanceCores = performanceCores
            self.efficiencyCores = efficiencyCores
            self.gpuCores = gpuCores
            self.neuralEngineCores = neuralEngineCores
            self.memoryBandwidthGBps = memoryBandwidthGBps
            self.diskFreeBytes = diskFreeBytes
        }
    }

    public struct Metrics: Codable, Sendable, Equatable {
        public var memoryUsedBytes: Int64
        public var memoryWiredBytes: Int64
        public var memoryTotalBytes: Int64
        public var swapUsedBytes: Int64
        public var gpuUtilization: Double
        public var cpuUtilization: Double
        public var memoryPressure: String

        public init(
            memoryUsedBytes: Int64, memoryWiredBytes: Int64, memoryTotalBytes: Int64,
            swapUsedBytes: Int64, gpuUtilization: Double, cpuUtilization: Double,
            memoryPressure: String
        ) {
            self.memoryUsedBytes = memoryUsedBytes
            self.memoryWiredBytes = memoryWiredBytes
            self.memoryTotalBytes = memoryTotalBytes
            self.swapUsedBytes = swapUsedBytes
            self.gpuUtilization = gpuUtilization
            self.cpuUtilization = cpuUtilization
            self.memoryPressure = memoryPressure
        }
    }

    public struct CatalogModel: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var name: String
        public var author: String
        public var license: String
        public var summary: String
        public var category: String
        public var parameters: String
        public var activeParameters: String?
        public var isMoE: Bool
        public var capabilities: [String]
        public var rating: Int
        public var maxContext: Int
        public var quantizations: [String]
        public var recommendation: Recommendation?
        public var featured: Bool?
        public var runtimeNote: String?

        public init(
            id: String, name: String, author: String, license: String, summary: String,
            category: String, parameters: String, activeParameters: String?, isMoE: Bool,
            capabilities: [String], rating: Int, maxContext: Int, quantizations: [String],
            recommendation: Recommendation?, featured: Bool? = nil, runtimeNote: String? = nil
        ) {
            self.id = id
            self.name = name
            self.author = author
            self.license = license
            self.summary = summary
            self.category = category
            self.parameters = parameters
            self.activeParameters = activeParameters
            self.isMoE = isMoE
            self.capabilities = capabilities
            self.rating = rating
            self.maxContext = maxContext
            self.quantizations = quantizations
            self.recommendation = recommendation
            self.featured = featured
            self.runtimeNote = runtimeNote
        }
    }

    public struct Recommendation: Codable, Sendable, Equatable {
        public var quantization: String
        public var contextLength: Int
        public var expertSlots: Int?
        public var estimatedGenerationTokensPerSecond: Double
        public var estimatedPromptTokensPerSecond: Double
        public var downloadBytes: Int64
        public var plan: Plan
        public var rationale: String

        public init(
            quantization: String, contextLength: Int, expertSlots: Int?,
            estimatedGenerationTokensPerSecond: Double, estimatedPromptTokensPerSecond: Double,
            downloadBytes: Int64, plan: Plan, rationale: String
        ) {
            self.quantization = quantization
            self.contextLength = contextLength
            self.expertSlots = expertSlots
            self.estimatedGenerationTokensPerSecond = estimatedGenerationTokensPerSecond
            self.estimatedPromptTokensPerSecond = estimatedPromptTokensPerSecond
            self.downloadBytes = downloadBytes
            self.plan = plan
            self.rationale = rationale
        }
    }

    public struct Plan: Codable, Sendable, Equatable {
        public var verdict: String
        public var residentBytes: Int64
        public var budgetBytes: Int64
        public var weightsBytes: Int64
        public var expertsBytes: Int64
        public var kvCacheBytes: Int64
        public var computeBytes: Int64
        public var streamedFromDiskBytes: Int64
        public var suggestions: [Suggestion]
        public var notes: [String]

        public init(
            verdict: String, residentBytes: Int64, budgetBytes: Int64, weightsBytes: Int64,
            expertsBytes: Int64, kvCacheBytes: Int64, computeBytes: Int64,
            streamedFromDiskBytes: Int64, suggestions: [Suggestion], notes: [String]
        ) {
            self.verdict = verdict
            self.residentBytes = residentBytes
            self.budgetBytes = budgetBytes
            self.weightsBytes = weightsBytes
            self.expertsBytes = expertsBytes
            self.kvCacheBytes = kvCacheBytes
            self.computeBytes = computeBytes
            self.streamedFromDiskBytes = streamedFromDiskBytes
            self.suggestions = suggestions
            self.notes = notes
        }
    }

    public struct Suggestion: Codable, Sendable, Equatable {
        public var title: String
        public var detail: String
        public var savingBytes: Int64
        public var cost: String

        public init(title: String, detail: String, savingBytes: Int64, cost: String) {
            self.title = title
            self.detail = detail
            self.savingBytes = savingBytes
            self.cost = cost
        }
    }

    public struct InstalledModel: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var name: String
        public var quantization: String
        public var sizeOnDiskBytes: Int64
        public var isLoaded: Bool
        public var supportsVision: Bool

        public init(
            id: String, name: String, quantization: String, sizeOnDiskBytes: Int64,
            isLoaded: Bool, supportsVision: Bool
        ) {
            self.id = id
            self.name = name
            self.quantization = quantization
            self.sizeOnDiskBytes = sizeOnDiskBytes
            self.isLoaded = isLoaded
            self.supportsVision = supportsVision
        }
    }

    public struct Status: Codable, Sendable, Equatable {
        public var state: String
        public var loadedModelID: String?
        public var loadedModelName: String?
        public var contextLength: Int?
        public var expertStreaming: Bool
        public var lastGenerationTokensPerSecond: Double?
        public var activity: String?

        public init(
            state: String, loadedModelID: String? = nil, loadedModelName: String? = nil,
            contextLength: Int? = nil, expertStreaming: Bool = false,
            lastGenerationTokensPerSecond: Double? = nil, activity: String? = nil
        ) {
            self.state = state
            self.loadedModelID = loadedModelID
            self.loadedModelName = loadedModelName
            self.contextLength = contextLength
            self.expertStreaming = expertStreaming
            self.lastGenerationTokensPerSecond = lastGenerationTokensPerSecond
            self.activity = activity
        }

        /// True when a language model is resident and ready to answer.
        public var hasLoadedModel: Bool { loadedModelID != nil }
    }

    // MARK: - Requests

    public struct PlanRequest: Codable, Sendable, Equatable {
        public var modelID: String
        public var quantization: String?
        public var contextLength: Int?
        public var kvCachePrecision: String?
        public var flashAttention: Bool?
        public var expertSlots: Int?

        public init(
            modelID: String, quantization: String? = nil, contextLength: Int? = nil,
            kvCachePrecision: String? = nil, flashAttention: Bool? = nil,
            expertSlots: Int? = nil
        ) {
            self.modelID = modelID
            self.quantization = quantization
            self.contextLength = contextLength
            self.kvCachePrecision = kvCachePrecision
            self.flashAttention = flashAttention
            self.expertSlots = expertSlots
        }
    }

    public struct LoadRequest: Codable, Sendable, Equatable {
        /// Either an installed model id, or a catalog id plus quantization.
        public var modelID: String
        public var quantization: String?
        public var contextLength: Int?
        public var expertSlots: Int?
        public var directory: String?

        public init(
            modelID: String, quantization: String? = nil,
            contextLength: Int? = nil, expertSlots: Int? = nil, directory: String? = nil
        ) {
            self.modelID = modelID
            self.quantization = quantization
            self.contextLength = contextLength
            self.expertSlots = expertSlots
            self.directory = directory
        }
    }

    public struct ChatRequest: Codable, Sendable, Equatable {
        public struct Message: Codable, Sendable, Equatable {
            public var role: String
            public var content: String
            /// Base64 `data:` URLs. Only meaningful for vision models.
            public var images: [String]

            public init(role: String, content: String, images: [String] = []) {
                self.role = role
                self.content = content
                self.images = images
            }
        }
        public var messages: [Message]
        public var temperature: Double?
        public var maxTokens: Int?

        public init(messages: [Message], temperature: Double? = nil, maxTokens: Int? = nil) {
            self.messages = messages
            self.temperature = temperature
            self.maxTokens = maxTokens
        }
    }

    public struct ChatResponse: Codable, Sendable, Equatable {
        public var content: String
        public var reasoning: String?
        public var promptTokens: Int
        public var generatedTokens: Int
        public var tokensPerSecond: Double
        /// What the Mac's answer checking made of this reply, when it checked it. On
        /// the buffered route it comes back with the answer rather than afterwards.
        public var verification: BuddyAPI.Verdict?

        public init(
            content: String, reasoning: String?, promptTokens: Int,
            generatedTokens: Int, tokensPerSecond: Double,
            verification: BuddyAPI.Verdict? = nil
        ) {
            self.content = content
            self.reasoning = reasoning
            self.promptTokens = promptTokens
            self.generatedTokens = generatedTokens
            self.tokensPerSecond = tokensPerSecond
            self.verification = verification
        }
    }

    // MARK: - Image

    public struct ImageModel: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var name: String
        public var author: String
        public var license: String
        public var summary: String
        public var parameters: String
        public var blocks: Int
        public var defaultSteps: Int
        public var isGated: Bool
        public var recommendation: ImagePlan?

        public init(
            id: String, name: String, author: String, license: String, summary: String,
            parameters: String, blocks: Int, defaultSteps: Int, isGated: Bool,
            recommendation: ImagePlan?
        ) {
            self.id = id
            self.name = name
            self.author = author
            self.license = license
            self.summary = summary
            self.parameters = parameters
            self.blocks = blocks
            self.defaultSteps = defaultSteps
            self.isGated = isGated
            self.recommendation = recommendation
        }
    }

    public struct ImagePlan: Codable, Sendable, Equatable {
        public var width: Int
        public var height: Int
        public var steps: Int
        public var quantization: String
        public var peakBytes: Int64
        public var peakPhase: String
        public var budgetBytes: Int64
        public var verdict: String
        public var phases: [Phase]
        public var suggestions: [Suggestion]
        public var notes: [String]

        public struct Phase: Codable, Sendable, Equatable {
            public var name: String
            public var detail: String
            public var residentBytes: Int64
            public init(name: String, detail: String, residentBytes: Int64) {
                self.name = name
                self.detail = detail
                self.residentBytes = residentBytes
            }
        }

        public init(
            width: Int, height: Int, steps: Int, quantization: String,
            peakBytes: Int64, peakPhase: String, budgetBytes: Int64, verdict: String,
            phases: [Phase], suggestions: [Suggestion], notes: [String]
        ) {
            self.width = width
            self.height = height
            self.steps = steps
            self.quantization = quantization
            self.peakBytes = peakBytes
            self.peakPhase = peakPhase
            self.budgetBytes = budgetBytes
            self.verdict = verdict
            self.phases = phases
            self.suggestions = suggestions
            self.notes = notes
        }
    }

    public struct ImageRequest: Codable, Sendable, Equatable {
        public var prompt: String
        public var modelID: String?
        public var width: Int?
        public var height: Int?
        public var steps: Int?
        public var quantization: String?
        public var seed: Int?
        public var localOnly: Bool?
        public var initImagePath: String?
        public var initImageInfluence: Double?

        public init(
            prompt: String, modelID: String? = nil, width: Int? = nil, height: Int? = nil,
            steps: Int? = nil, quantization: String? = nil, seed: Int? = nil,
            initImagePath: String? = nil, initImageInfluence: Double? = nil,
            localOnly: Bool? = nil
        ) {
            self.prompt = prompt
            self.modelID = modelID
            self.width = width
            self.height = height
            self.steps = steps
            self.quantization = quantization
            self.seed = seed
            self.localOnly = localOnly
            self.initImagePath = initImagePath
            self.initImageInfluence = initImageInfluence
        }
    }

    public struct ImageResponse: Codable, Sendable, Equatable {
        public var path: String
        public var elapsedSeconds: Double
        public var peakMemoryBytes: Int64?
        public var predictedPeakBytes: Int64
        public var model: String
        public var warning: String?

        public init(
            path: String, elapsedSeconds: Double, peakMemoryBytes: Int64?,
            predictedPeakBytes: Int64, model: String, warning: String? = nil
        ) {
            self.path = path
            self.elapsedSeconds = elapsedSeconds
            self.peakMemoryBytes = peakMemoryBytes
            self.predictedPeakBytes = predictedPeakBytes
            self.model = model
            self.warning = warning
        }
    }

    // MARK: - Mesh

    public struct MeshModel: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var name: String
        public var author: String
        public var summary: String
        public var outputs: String
        public var typicalDuration: String
        public var peakBytes: Int64
        public var weightsBytes: Int64
        public var isInstalled: Bool
        public var installDetail: String

        public init(
            id: String, name: String, author: String, summary: String, outputs: String,
            typicalDuration: String, peakBytes: Int64, weightsBytes: Int64,
            isInstalled: Bool, installDetail: String
        ) {
            self.id = id
            self.name = name
            self.author = author
            self.summary = summary
            self.outputs = outputs
            self.typicalDuration = typicalDuration
            self.peakBytes = peakBytes
            self.weightsBytes = weightsBytes
            self.isInstalled = isInstalled
            self.installDetail = installDetail
        }
    }

    // MARK: - Video

    public struct VideoModel: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var name: String
        public var summary: String
        public var typicalDuration: String
        public var supportsImageInput: Bool
        public var supportedSeconds: [Int]
        public var available: Bool
        /// The node that would run it, when one is ready.
        public var node: String?
        /// Optional renderer controls; absent on older nodes.
        public var supportedParameters: [String]?

        public init(
            id: String, name: String, summary: String, typicalDuration: String,
            supportsImageInput: Bool, supportedSeconds: [Int], available: Bool,
            node: String?, supportedParameters: [String]? = nil
        ) {
            self.id = id
            self.name = name
            self.summary = summary
            self.typicalDuration = typicalDuration
            self.supportsImageInput = supportsImageInput
            self.supportedSeconds = supportedSeconds
            self.available = available
            self.node = node
            self.supportedParameters = supportedParameters
        }
    }

    public struct VideoQueueView: Codable, Sendable, Equatable {
        public struct Item: Codable, Sendable, Equatable, Identifiable {
            public var id: String
            public var batchID: String
            public var title: String
            public var prompt: String
            public var scene: Int
            public var variation: Int
            public var seed: UInt32?
            public var modelID: String
            public var seconds: Int
            public var resolution: String
            public var h3Turbo: Bool?
            public var h3Steps: Int?
            public var status: String
            public var nodeJobID: String?
            public var file: String?
            public var outputDirectory: String
            public var error: String?
            public var uncertainSubmission: Bool

            public init(
                id: String, batchID: String, title: String, prompt: String, scene: Int,
                variation: Int, seed: UInt32?, modelID: String, seconds: Int,
                resolution: String, h3Turbo: Bool?, status: String, nodeJobID: String?,
                file: String?, outputDirectory: String, error: String?,
                uncertainSubmission: Bool, h3Steps: Int? = nil
            ) {
                self.id = id
                self.batchID = batchID
                self.title = title
                self.prompt = prompt
                self.scene = scene
                self.variation = variation
                self.seed = seed
                self.modelID = modelID
                self.seconds = seconds
                self.resolution = resolution
                self.h3Turbo = h3Turbo
                self.h3Steps = h3Steps
                self.status = status
                self.nodeJobID = nodeJobID
                self.file = file
                self.outputDirectory = outputDirectory
                self.error = error
                self.uncertainSubmission = uncertainSubmission
            }
        }
        public var paused: Bool
        public var activeID: String?
        public var message: String?
        public var items: [Item]

        public init(paused: Bool, activeID: String?, message: String?, items: [Item]) {
            self.paused = paused
            self.activeID = activeID
            self.message = message
            self.items = items
        }
    }

    // MARK: - Swarm and node

    public struct SwarmView: Codable, Sendable, Equatable {
        /// Whether this Mac is listening on its tailnet address, and where. The phone
        /// is on the other end of exactly that socket, so "requested but not
        /// listening" is the difference between a Mac that is off and one that is
        /// broken.
        public struct Exposure: Codable, Sendable, Equatable {
            public var requested: Bool
            public var listening: Bool
            public var address: String?
            public var port: Int?
            public var problem: String?

            public init(
                requested: Bool, listening: Bool, address: String? = nil,
                port: Int? = nil, problem: String? = nil
            ) {
                self.requested = requested
                self.listening = listening
                self.address = address
                self.port = port
                self.problem = problem
            }
        }

        public struct Peer: Codable, Sendable, Equatable, Identifiable {
            public var name: String
            public var baseURL: String
            public var reachable: Bool
            public var error: String?
            public var capabilities: [Capability]

            public var id: String { baseURL }

            public init(
                name: String, baseURL: String, reachable: Bool,
                error: String?, capabilities: [Capability]
            ) {
                self.name = name
                self.baseURL = baseURL
                self.reachable = reachable
                self.error = error
                self.capabilities = capabilities
            }
        }

        public struct Capability: Codable, Sendable, Equatable, Identifiable {
            public var id: String
            public var kind: String
            public var ready: Bool

            public init(id: String, kind: String, ready: Bool) {
                self.id = id
                self.kind = kind
                self.ready = ready
            }
        }

        public var peers: [Peer]
        public var exposure: Exposure?
        public var polledSecondsAgo: Double?

        public init(
            peers: [Peer], exposure: Exposure? = nil, polledSecondsAgo: Double?
        ) {
            self.peers = peers
            self.exposure = exposure
            self.polledSecondsAgo = polledSecondsAgo
        }
    }

    /// `GET /v1/node` — how the Mac advertises itself to the swarm. Snake case on the
    /// wire: this is the cross-platform shape silicon-node speaks too.
    public struct NodeAdvertisement: Codable, Sendable, Equatable {
        public var name: String
        public var platform: String
        public var profile: MacProfile
        public var capabilities: [NodeCapability]
        public var metrics: NodeMetrics

        public init(
            name: String, platform: String, profile: MacProfile,
            capabilities: [NodeCapability], metrics: NodeMetrics
        ) {
            self.name = name
            self.platform = platform
            self.profile = profile
            self.capabilities = capabilities
            self.metrics = metrics
        }
    }

    public struct MacProfile: Codable, Sendable, Equatable {
        public var chip: String
        public var memoryGB: Double
        public var bandwidthGBps: Double
        public var gpuCores: Int

        enum CodingKeys: String, CodingKey {
            case chip
            case memoryGB = "memory_gb"
            case bandwidthGBps = "bandwidth_gbps"
            case gpuCores = "gpu_cores"
        }

        public init(chip: String, memoryGB: Double, bandwidthGBps: Double, gpuCores: Int) {
            self.chip = chip
            self.memoryGB = memoryGB
            self.bandwidthGBps = bandwidthGBps
            self.gpuCores = gpuCores
        }
    }

    public struct NodeCapability: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var kind: String
        public var ready: Bool
        public var peakGB: Double?
        public var typicalSeconds: Double?
        public var detail: String

        enum CodingKeys: String, CodingKey {
            case id, kind, ready, detail
            case peakGB = "peak_gb"
            case typicalSeconds = "typical_seconds"
        }

        public init(
            id: String, kind: String, ready: Bool, peakGB: Double?,
            typicalSeconds: Double?, detail: String
        ) {
            self.id = id
            self.kind = kind
            self.ready = ready
            self.peakGB = peakGB
            self.typicalSeconds = typicalSeconds
            self.detail = detail
        }
    }

    public struct NodeMetrics: Codable, Sendable, Equatable {
        public var queueDepth: Int
        public var headroomGB: Double
        public var gpuUtilPct: Int
        public var memoryUsedPct: Int

        enum CodingKeys: String, CodingKey {
            case queueDepth = "queue_depth"
            case headroomGB = "headroom_gb"
            case gpuUtilPct = "gpu_util_pct"
            case memoryUsedPct = "memory_used_pct"
        }

        public init(queueDepth: Int, headroomGB: Double, gpuUtilPct: Int, memoryUsedPct: Int) {
            self.queueDepth = queueDepth
            self.headroomGB = headroomGB
            self.gpuUtilPct = gpuUtilPct
            self.memoryUsedPct = memoryUsedPct
        }
    }

    // MARK: - Errors and small replies

    public struct ErrorResponse: Codable, Sendable, Equatable {
        public var error: String
        public init(error: String) { self.error = error }
    }

    /// `GET /health`, the one unauthenticated route.
    public struct Health: Codable, Sendable, Equatable {
        public var status: String
        public var version: String
        public init(status: String, version: String) {
            self.status = status
            self.version = version
        }
    }

    /// `POST /install` and `POST /unload` both answer `{"status": "…"}`.
    public struct StatusMessage: Codable, Sendable, Equatable {
        public var status: String
        public init(status: String) { self.status = status }
    }
}
