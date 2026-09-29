import Foundation
import VieNeuTTSCore

/// Public entry — phác thảo 05-sdk-ios.md §5.5. Builder mirror Android.
public final class VieNeuTts {
    public struct GenerationProfile: Sendable {
        public var steps: Int
        public var sway: Double
        public var cfg: Double
        public var trebleFilterEnabled: Bool
        public var maxCharsPerChunk: Int

        public init(
            steps: Int = 8,
            sway: Double = -1.0,
            cfg: Double = 3.0,
            trebleFilterEnabled: Bool = true,
            maxCharsPerChunk: Int = 140
        ) {
            self.steps = steps
            self.sway = sway
            self.cfg = cfg
            self.trebleFilterEnabled = trebleFilterEnabled
            self.maxCharsPerChunk = maxCharsPerChunk
        }
    }

    public enum Provider: Sendable {
        case cpu
    }

    public enum EngineError: Error, Sendable {
        case modelChecksum
        case modelCorrupt
        case compat(String)
        case notLoaded
        case invalidInput(String)
    }

    private let profile: GenerationProfile
    private let provider: Provider

    public init(profile: GenerationProfile = .init(), provider: Provider = .cpu) {
        self.profile = profile
        self.provider = provider
    }

    /// Chunk text bằng TextSegmenter port (C++ core) rồi synthesize từng chunk.
    public func enqueue(_ text: String) throws -> Data {
        // TODO(Sprint 3): TextSegmenter → PhoneEncoder → OrtEngine → ChunkJoiner
        throw EngineError.notLoaded
    }
}
