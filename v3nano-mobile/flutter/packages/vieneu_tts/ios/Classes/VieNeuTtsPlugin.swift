import Flutter
import UIKit

/// Flutter plugin host iOS (06 §6.5) — scaffold.
/// Phụ thuộc SPM VieNeuTTS (xem ../../ios/sdk/) — KHÔNG port engine sang Dart.
public class VieNeuTtsPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        // TODO(Pigeon): MethodChannel + EventChannel → VieNeuTts Swift SDK
    }
}
