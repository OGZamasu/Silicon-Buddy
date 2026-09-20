import Foundation

/// `/ondevice/models`: the small models a phone runs by itself when the Mac is out of
/// reach, and the Mac's part in getting one onto it.
///
/// Types only, for now. The Android app runs these models as of M5; the iPad catches up
/// afterwards (llama.cpp's XCFramework runs the same GGUF), and until then these exist so
/// `ContractTests` holds this app to the shapes the Mac exports.
///
/// The phone never leaves the tailnet for them: the Mac fetches the pinned file from
/// Hugging Face, verifies it, and serves it with ranges; the phone checks the SHA-256
/// again itself. Every route is full scope only. Mirrored field for field from the Mac's
/// `PhoneModelsAPI.swift`.
public enum OnDeviceAPI {

    /// `GET /ondevice/models`.
    public struct PhoneModelList: Codable, Sendable, Equatable {
        public var models: [PhoneModel]

        public init(models: [PhoneModel]) { self.models = models }
    }

    /// One model a phone can run, pinned to exact bytes, and where the Mac's copy of it is.
    public struct PhoneModel: Codable, Sendable, Equatable, Identifiable {
        /// The catalogue key. The only thing a route takes; never a path.
        public var id: String
        public var label: String
        /// The one to offer first.
        public var isDefault: Bool
        public var sizeBytes: Int64
        /// Lower-case hex: the file route's `ETag`, and what the phone checks at the end.
        public var sha256: String
        public var licence: String
        public var source: Source
        public var onMac: OnMac
        public var recommended: Recommended
        public var measured: Measured?
        /// Larger and noticeably slower on the phone than the default.
        public var slowerOnPhone: Bool

        /// The `id` of the `download` frames on `/events` while the Mac fetches this one.
        public var downloadEventID: String { OnDeviceAPI.downloadEventPrefix + id }

        public struct Source: Codable, Sendable, Equatable {
            public var repo: String
            public var commit: String
            public var file: String
        }

        /// The Mac's own copy.
        public struct OnMac: Codable, Sendable, Equatable {
            /// `absent`, `downloading`, `ready` or `failed`.
            public var state: String
            /// While downloading: `fetching`, `checking` or `moving`.
            public var stage: String?
            /// How far that stage has got; on a failure that kept a partial, how much the Mac has.
            public var fraction: Double?
            /// Why it failed, in a sentence a phone can show.
            public var reason: String?
            /// `diskFull`, `checksumMismatch`, `network`, `server`, `interrupted`,
            /// `driveMissing` or `other`.
            public var failure: String?

            public var isReady: Bool { state == "ready" }
        }

        /// How to run it on the phone, from the Mac's measurements on a real one.
        public struct Recommended: Codable, Sendable, Equatable {
            public var threadsPrompt: Int
            public var threadsGenerate: Int
            public var contextLength: Int
            /// How much memory should be free before loading it.
            public var minFreeMemoryBytes: Int64
            /// The chat template is rendered with thinking on or off.
            public var thinking: Bool
        }

        /// What one benchmark on a real phone measured.
        public struct Measured: Codable, Sendable, Equatable {
            public var device: String
            public var runtime: String
            public var conditions: String
            public var tokensPerSecond: Double
            public var threadSweep: [ThreadSample]
            public var promptTokensPerSecond: Double
            public var secondsToFirstWord300: Double
            public var firstWordEstimated: Bool
            public var sustainedTokensPerSecond: Double?
            public var sustainedMeasured: Bool
            public var peakMemoryBytes: Int64
            public var peakMemoryContextTokens: Int

            public struct ThreadSample: Codable, Sendable, Equatable {
                public var threads: Int
                public var tokensPerSecond: Double
            }
        }
    }

    /// `download` frames on `/events` whose id starts with this are the Mac fetching a
    /// model for the phone; the Mac's own downloads never do.
    public static let downloadEventPrefix = "ondevice:"
}
