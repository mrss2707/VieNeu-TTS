// swift-tools-version:5.9
// VieNeuTTS iOS SDK — Sprint 3 scaffold (05-sdk-ios.md §5.2).
// Binary ORT: pin onnxruntime.xcframework qua SPM binary target (không commit weights).
import PackageDescription

let package = Package(
    name: "VieNeuTTS",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "VieNeuTTS", targets: ["VieNeuTTS"]),
        .library(name: "VieNeuTTSCore", targets: ["VieNeuTTSCore"]),
    ],
    targets: [
        // Swift public API — VieNeuTts / Reader / VoiceManager
        .target(
            name: "VieNeuTTS",
            dependencies: ["VieNeuTTSCore"]
        ),
        // Objective-C++ ORT C++ API bridge (BOOL ctx_mask) + chunk pipeline port
        .target(
            name: "VieNeuTTSCore",
            dependencies: [],
            publicHeadersPath: "include"
        ),
        .testTarget(
            name: "VieNeuTTSCoreTests",
            dependencies: ["VieNeuTTSCore"]
        ),
        .testTarget(
            name: "VieNeuTTSTests",
            dependencies: ["VieNeuTTS"]
        ),
    ]
)
