import XCTest
@testable import VieNeuTTS

/// Contract API Swift — lock defaults D1 / error mapping (không cần ORT).
final class VieNeuTtsApiTests: XCTestCase {

    func testGenerationProfileDefaultsMatchLockedD1() {
        let p = VieNeuTts.GenerationProfile()
        XCTAssertEqual(p.steps, 8)
        XCTAssertEqual(p.sway, -1.0)
        XCTAssertEqual(p.cfg, 3.0)
        XCTAssertTrue(p.trebleFilterEnabled)
        XCTAssertEqual(p.maxCharsPerChunk, 140)
    }

    func testEnqueueBeforeLoadThrowsNotLoaded() {
        let tts = VieNeuTts()
        XCTAssertThrowsError(try tts.enqueue("Xin chào.")) { err in
            guard let e = err as? VieNeuTts.EngineError else {
                return XCTFail("wrong error type \(err)")
            }
            if case .notLoaded = e { /* ok */ } else {
                XCTFail("expected notLoaded, got \(e)")
            }
        }
    }
}
