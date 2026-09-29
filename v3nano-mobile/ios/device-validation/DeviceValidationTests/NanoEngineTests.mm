// Real on-device inference test for v3 Nano's four core ONNX graphs, mirroring
// OnnxV3NanoEngine.infer() in src/vieneu/v3nano.py. Not a mock: loads the real graphs
// bundled as app resources and runs them through the ONNX Runtime C++ API
// (onnxruntime.xcframework) on the FULL smoke+stress corpus (v3nano-mobile/eval/corpus/,
// exported via v3nano-mobile/host/export_corpus_tensors.py).
//
// This is a validation harness (docs/_supermeo/_summary/002), not the mobile SDK.
//
// Split into one test method PER PROVIDER (testProviderCPU / testProviderCoreML) instead
// of one method looping over both: running CPU+CoreML back-to-back in a single process on
// a real device (iPhone 11, 4GB RAM) caused an early crash ("connection was invalidated",
// no NaN/exception logged) consistent with an iOS jetsam OOM kill — 4 graphs x 2 provider
// session sets in one process pushed past the per-app memory limit on a real device (the
// Simulator has no such limit, which is why this didn't reproduce there). Splitting keeps
// each xcodebuild test invocation to one provider's worth of live ORT sessions.
//
// Also logs progress after EVERY case (not just after a whole provider finishes) so a
// crash — if it still happens — pinpoints which case/run triggered it instead of leaving
// zero evidence like the combined run did.
//
// Ort::Value has move-only ownership and Session::Run() consumes its input array by
// value construction, so each Run() below is fed freshly-built Ort::Value tensors —
// wrapping the same underlying std::vector<float>/<int64_t> buffers (which live for the
// whole test) rather than trying to reuse an already-moved-from Value.
//
// The Objective-C wrapper (onnxruntime-swift-package-manager) does not expose a BOOL
// tensor element type as of 1.24.2 (github issues #31, #41), so this harness calls the
// C++ API directly instead.

#import <XCTest/XCTest.h>
#import <onnxruntime/onnxruntime_cxx_api.h>
#import <onnxruntime/coreml_provider_factory.h>
#import <math.h>
#import <vector>
#import <random>
#import <map>
#import <string>
#import <algorithm>

@interface NanoEngineTests : XCTestCase
@end

@implementation NanoEngineTests

- (NSData *)dataForResource:(NSString *)relativePath {
    NSString *path = [[NSBundle mainBundle] pathForResource:relativePath ofType:nil];
    if (!path) {
        path = [[NSBundle bundleForClass:[self class]] pathForResource:relativePath ofType:nil];
    }
    XCTAssertNotNil(path, @"resource not found: %@", relativePath);
    NSData *data = [NSData dataWithContentsOfFile:path];
    XCTAssertNotNil(data, @"could not read resource: %@", relativePath);
    return data;
}

- (NSString *)pathForResource:(NSString *)relativePath {
    NSString *path = [[NSBundle mainBundle] pathForResource:relativePath ofType:nil];
    if (!path) {
        path = [[NSBundle bundleForClass:[self class]] pathForResource:relativePath ofType:nil];
    }
    XCTAssertNotNil(path, @"resource not found: %@", relativePath);
    return path;
}

- (std::vector<float>)floatVectorFromData:(NSData *)data {
    const float *p = (const float *)data.bytes;
    return std::vector<float>(p, p + data.length / 4);
}

- (std::vector<int64_t>)int64VectorFromData:(NSData *)data {
    const int64_t *p = (const int64_t *)data.bytes;
    return std::vector<int64_t>(p, p + data.length / sizeof(int64_t));
}

// One flow-matching Euler inference, matching OnnxV3NanoEngine.infer (src/vieneu/v3nano.py).
// `sway` warps the Euler time schedule: tg = u + sway*(cos(pi/2*u) - 1 + u) — NOT a no-op
// when nonzero, so it must be applied to both t and dt, not just passed through as u.
- (void)runInferenceWithTextSession:(Ort::Session &)sText
                        durSession:(Ort::Session &)sDur
                         veSession:(Ort::Session &)sVe
                        decSession:(Ort::Session &)sDec
                          memInfo:(Ort::MemoryInfo &)memInfo
                               ids:(const std::vector<int64_t> &)ids
                             spk:(const std::vector<float> &)spk
                           style:(const std::vector<float> &)style
                         nullSpk:(const std::vector<float> &)nullSpk
                       nullStyle:(const std::vector<float> &)nullStyle
                           padId:(int64_t)padId
                           bosId:(int64_t)bosId
                           eosId:(int64_t)eosId
                             fps:(double)fps
                           steps:(int)steps
                             cfg:(double)cfg
                            sway:(double)sway
                        outWavLen:(size_t *)outWavLen
                       outHasNan:(BOOL *)outHasNan
                        outGenMs:(double *)outGenMs
                          outWav:(std::vector<float> *)outWav {
    NSUInteger L = ids.size();
    std::vector<int64_t> idsShape = {1, (int64_t)L};
    std::vector<int64_t> styleShape = {1, 50, 256};
    std::vector<int64_t> spkShape = {1, 192};
    std::vector<int64_t> maskShape = {1, (int64_t)L};
    std::vector<int64_t> nullIdsShape = {1, 2};

    std::vector<uint8_t> mask(L);
    for (NSUInteger i = 0; i < L; i++) mask[i] = (ids[i] != padId) ? 1 : 0;
    std::vector<int64_t> nullIds = {bosId, eosId};
    std::vector<uint8_t> nullMask = {1, 1};

    const char *textInNames[] = {"ids", "style"};
    const char *textOutNames[] = {"ctx"};
    const char *durInNames[] = {"ctx", "ctx_mask", "spk"};
    const char *durOutNames[] = {"log_seconds"};
    const char *veInNames[] = {"x", "t", "ctx", "ctx_mask", "spk", "style"};
    const char *veOutNames[] = {"v"};
    const char *decInNames[] = {"x"};
    const char *decOutNames[] = {"wav"};

    auto runTextEncoder = [&](const std::vector<int64_t> &idsVec, const std::vector<int64_t> &idsShp,
                               const std::vector<float> &styleVec) -> std::vector<float> {
        Ort::Value idsT = Ort::Value::CreateTensor<int64_t>(memInfo, const_cast<int64_t *>(idsVec.data()), idsVec.size(),
                                                             idsShp.data(), idsShp.size());
        Ort::Value styleT = Ort::Value::CreateTensor<float>(memInfo, const_cast<float *>(styleVec.data()), styleVec.size(),
                                                              styleShape.data(), styleShape.size());
        Ort::Value inputs[] = {std::move(idsT), std::move(styleT)};
        auto out = sText.Run(Ort::RunOptions{nullptr}, textInNames, inputs, 2, textOutNames, 1);
        float *p = out[0].GetTensorMutableData<float>();
        size_t n = out[0].GetTensorTypeAndShapeInfo().GetElementCount();
        return std::vector<float>(p, p + n);
    };

    NSTimeInterval t0 = [NSDate timeIntervalSinceReferenceDate];

    std::vector<float> ctx = runTextEncoder(ids, idsShape, style);
    int64_t ctxD = (int64_t)(ctx.size() / L);
    std::vector<int64_t> ctxShape = {1, (int64_t)L, ctxD};
    std::vector<float> nullCtx = runTextEncoder(nullIds, nullIdsShape, nullStyle);
    std::vector<int64_t> nullCtxShape = {1, 2, ctxD};

    Ort::Value ctxT = Ort::Value::CreateTensor<float>(memInfo, ctx.data(), ctx.size(), ctxShape.data(), ctxShape.size());
    Ort::Value maskT = Ort::Value::CreateTensor(memInfo, mask.data(), mask.size(), maskShape.data(), maskShape.size(),
                                                 ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL);
    Ort::Value spkT = Ort::Value::CreateTensor<float>(memInfo, const_cast<float *>(spk.data()), spk.size(), spkShape.data(), spkShape.size());
    Ort::Value durInputs[] = {std::move(ctxT), std::move(maskT), std::move(spkT)};
    auto durOut = sDur.Run(Ort::RunOptions{nullptr}, durInNames, durInputs, 3, durOutNames, 1);
    float logS = durOut[0].GetTensorMutableData<float>()[0];
    double secs = fmin(exp((double)logS), 15.0);
    int64_t T = fmax(2, llround(secs * fps));

    const int64_t C = 144;
    std::vector<float> x((size_t)(C * T));
    std::mt19937 rng(0);
    std::normal_distribution<float> gauss(0.0f, 1.0f);
    for (auto &v : x) v = gauss(rng);
    std::vector<int64_t> xShape = {1, C, T};

    int n = steps;
    std::vector<double> u(n + 1);
    for (int i = 0; i <= n; i++) u[i] = (double)i / n;
    std::vector<double> tg(n + 1);
    for (int i = 0; i <= n; i++) tg[i] = u[i] + sway * (cos(M_PI / 2.0 * u[i]) - 1.0 + u[i]);

    auto runVectorEstimator = [&](const std::vector<float> &xVec, float tVal,
                                   const std::vector<float> &ctxVec, const std::vector<int64_t> &ctxShp,
                                   const std::vector<uint8_t> &maskVec, const std::vector<int64_t> &maskShp,
                                   const std::vector<float> &spkVec,
                                   const std::vector<float> &styleVec) -> std::vector<float> {
        std::vector<int64_t> tShape = {1};
        Ort::Value xT = Ort::Value::CreateTensor<float>(memInfo, const_cast<float *>(xVec.data()), xVec.size(),
                                                          xShape.data(), xShape.size());
        Ort::Value tT = Ort::Value::CreateTensor<float>(memInfo, &tVal, 1, tShape.data(), tShape.size());
        Ort::Value cT = Ort::Value::CreateTensor<float>(memInfo, const_cast<float *>(ctxVec.data()), ctxVec.size(),
                                                          ctxShp.data(), ctxShp.size());
        Ort::Value mT = Ort::Value::CreateTensor(memInfo, const_cast<uint8_t *>(maskVec.data()), maskVec.size(),
                                                  maskShp.data(), maskShp.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL);
        Ort::Value sT = Ort::Value::CreateTensor<float>(memInfo, const_cast<float *>(spkVec.data()), spkVec.size(),
                                                         spkShape.data(), spkShape.size());
        Ort::Value stT = Ort::Value::CreateTensor<float>(memInfo, const_cast<float *>(styleVec.data()), styleVec.size(),
                                                           styleShape.data(), styleShape.size());
        Ort::Value inputs[] = {std::move(xT), std::move(tT), std::move(cT), std::move(mT), std::move(sT), std::move(stT)};
        auto out = sVe.Run(Ort::RunOptions{nullptr}, veInNames, inputs, 6, veOutNames, 1);
        float *p = out[0].GetTensorMutableData<float>();
        size_t cnt = out[0].GetTensorTypeAndShapeInfo().GetElementCount();
        return std::vector<float>(p, p + cnt);
    };

    for (int i = 0; i < n; i++) {
        float tVal = (float)tg[i];
        std::vector<float> v = runVectorEstimator(x, tVal, ctx, ctxShape, mask, maskShape, spk, style);
        std::vector<float> vFinal;
        if (cfg > 0) {
            std::vector<float> vu = runVectorEstimator(x, tVal, nullCtx, nullCtxShape, nullMask, nullIdsShape, nullSpk, nullStyle);
            vFinal.resize(v.size());
            for (size_t j = 0; j < v.size(); j++) vFinal[j] = vu[j] + (float)cfg * (v[j] - vu[j]);
        } else {
            vFinal = v;
        }
        float dt = (float)(tg[i + 1] - tg[i]);
        for (size_t j = 0; j < x.size(); j++) x[j] = x[j] + dt * vFinal[j];
    }

    Ort::Value xFinalTensor = Ort::Value::CreateTensor<float>(memInfo, x.data(), x.size(), xShape.data(), xShape.size());
    Ort::Value decInputs[] = {std::move(xFinalTensor)};
    auto decOut = sDec.Run(Ort::RunOptions{nullptr}, decInNames, decInputs, 1, decOutNames, 1);
    float *wav = decOut[0].GetTensorMutableData<float>();
    size_t wavLen = decOut[0].GetTensorTypeAndShapeInfo().GetElementCount();

    *outGenMs = ([NSDate timeIntervalSinceReferenceDate] - t0) * 1000.0;
    BOOL hasNan = NO;
    for (size_t i = 0; i < wavLen; i++) {
        if (isnan(wav[i]) || isinf(wav[i])) { hasNan = YES; break; }
    }
    *outHasNan = hasNan;
    *outWavLen = wavLen;
    if (outWav != nullptr) {
        outWav->assign(wav, wav + wavLen);
    }
}

// ── Multi-chunk audio join, ported from src/vieneu_utils/core_utils.py ──────────────
// (edge_silence / pause_pad_samples / join_audio_chunks silence_ps branch). Only the
// audio-joining side is ported — chunk *splitting* (normalize_to_chunks_v3_with_gaps)
// already ran in v3nano-mobile/host/export_corpus_tensors.py, which emits chunks_meta.json
// + chunk<k>/ids.bin per case. See docs/_supermeo/_summary/001-v3-nano-mobile.md §0.8.

static const double kEdgeThreshDb = -45.0;
static const double kEdgeWinS = 0.01;

static std::map<std::string, double> V3GapSilence() {
    return {{"para", 0.70}, {"sentence", 0.50}, {"minor", 0.30}};
}

// Ported from edge_silence() in core_utils.py: (lead, tail) sample counts of silence
// at the start/end of `wav`, via a 10ms-window mean|x| envelope below threshDb.
static void EdgeSilence(const std::vector<float> &wav, int sr, size_t *outLead, size_t *outTail,
                         double threshDb = kEdgeThreshDb, double winS = kEdgeWinS) {
    size_t nSamp = wav.size();
    size_t win = std::max((size_t)1, (size_t)(winS * sr));
    size_t nWin = nSamp / win;
    if (nWin == 0) { *outLead = nSamp; *outTail = 0; return; }
    double threshLinear = pow(10.0, threshDb / 20.0);
    long first = -1, last = -1;
    for (size_t w = 0; w < nWin; w++) {
        double sum = 0.0;
        size_t base = w * win;
        for (size_t j = 0; j < win; j++) sum += fabsf(wav[base + j]);
        double mean = sum / (double)win;
        if (mean > threshLinear) {
            if (first == -1) first = (long)w;
            last = (long)w;
        }
    }
    if (first == -1) { *outLead = nSamp; *outTail = 0; return; }
    *outLead = (size_t)first * win;
    *outTail = nSamp - (size_t)(last + 1) * win;
}

// Ported from pause_pad_samples() in core_utils.py: zero-samples needed between
// prevWav and nextWav so the real pause (prev tail silence + pad + next lead
// silence) reaches pauseS seconds; 0 if already long enough.
static size_t PausePadSamples(const std::vector<float> &prevWav, const std::vector<float> &nextWav,
                               int sr, double pauseS) {
    size_t leadPrev, tail;
    EdgeSilence(prevWav, sr, &leadPrev, &tail);
    if (leadPrev == prevWav.size()) tail = prevWav.size();
    size_t leadNext, tailNextUnused;
    EdgeSilence(nextWav, sr, &leadNext, &tailNextUnused);
    long pad = (long)(pauseS * sr) - (long)tail - (long)leadNext;
    return pad > 0 ? (size_t)pad : 0;
}

// Ported from join_audio_chunks(..., silence_ps=...) in core_utils.py — the v3
// silence_ps branch only (v1/v2 fixed-silence/crossfade branches are not used by
// v3 Nano and are not ported). `gaps` entries map via V3GapSilence() (default
// "sentence" for an unknown gap type, matching gaps_to_silence() in Python).
static std::vector<float> JoinAudioChunks(const std::vector<std::vector<float>> &chunks, int sr,
                                           const std::vector<std::string> &gaps) {
    if (chunks.empty()) return {};
    if (chunks.size() == 1) return chunks[0];
    std::map<std::string, double> gapSilence = V3GapSilence();
    std::vector<size_t> pads(chunks.size() - 1);
    size_t total = chunks[0].size();
    for (size_t i = 1; i < chunks.size(); i++) {
        std::string gapType = (i - 1 < gaps.size()) ? gaps[i - 1] : "sentence";
        double pauseS = gapSilence.count(gapType) ? gapSilence[gapType] : gapSilence["sentence"];
        size_t pad = PausePadSamples(chunks[i - 1], chunks[i], sr, pauseS);
        pads[i - 1] = pad;
        total += pad + chunks[i].size();
    }
    std::vector<float> out(total, 0.0f);
    size_t pos = chunks[0].size();
    std::copy(chunks[0].begin(), chunks[0].end(), out.begin());
    for (size_t i = 1; i < chunks.size(); i++) {
        pos += pads[i - 1]; // zeros already default-initialized
        std::copy(chunks[i].begin(), chunks[i].end(), out.begin() + pos);
        pos += chunks[i].size();
    }
    return out;
}

// Runs inference for every chunk of a (possibly multi-chunk) case, reading
// chunks_meta.json + chunk<k>/ids.bin under caseDir, then joins with JoinAudioChunks —
// mirrors V3NanoVieNeuTTS.infer()'s chunking+join path so long sentences (> max_chars=140)
// don't get force-fed to the flow model in one shot (root cause of the "nuốt chữ" bug —
// see §0.8). Single-chunk cases still take the same code path (chunk0/) so callers don't
// need a branch.
- (void)runInferenceMultiChunkWithCaseDir:(NSString *)caseDir
                               textSession:(Ort::Session &)sText
                                durSession:(Ort::Session &)sDur
                                 veSession:(Ort::Session &)sVe
                                decSession:(Ort::Session &)sDec
                                   memInfo:(Ort::MemoryInfo &)memInfo
                                       spk:(const std::vector<float> &)spk
                                     style:(const std::vector<float> &)style
                                   nullSpk:(const std::vector<float> &)nullSpk
                                 nullStyle:(const std::vector<float> &)nullStyle
                                     padId:(int64_t)padId
                                     bosId:(int64_t)bosId
                                     eosId:(int64_t)eosId
                                       fps:(double)fps
                                     steps:(int)steps
                                       cfg:(double)cfg
                                      sway:(double)sway
                                sampleRate:(int)sampleRate
                                   outWav:(std::vector<float> *)outWav
                                outGenMs:(double *)outGenMs
                               outHasNan:(BOOL *)outHasNan
                              outNChunks:(int *)outNChunks {
    NSDictionary *chunksMeta = [NSJSONSerialization JSONObjectWithData:[self dataForResource:[NSString stringWithFormat:@"%@/chunks_meta.json", caseDir]] options:0 error:nil];
    int nChunks = [chunksMeta[@"n_chunks"] intValue];
    NSArray *gapsArr = chunksMeta[@"gaps"];
    std::vector<std::string> gaps;
    for (NSString *g in gapsArr) gaps.push_back(std::string([g UTF8String]));

    std::vector<std::vector<float>> wavs;
    double totalGenMs = 0.0;
    BOOL nanAny = NO;
    for (int k = 0; k < nChunks; k++) {
        std::vector<int64_t> ids = [self int64VectorFromData:[self dataForResource:[NSString stringWithFormat:@"%@/chunk%d/ids.bin", caseDir, k]]];
        size_t wavLen; BOOL hasNan; double genMs;
        std::vector<float> wavBuf;
        [self runInferenceWithTextSession:sText durSession:sDur veSession:sVe decSession:sDec
                                   memInfo:memInfo ids:ids spk:spk style:style nullSpk:nullSpk nullStyle:nullStyle
                                     padId:padId bosId:bosId eosId:eosId fps:fps steps:steps cfg:cfg sway:sway
                                 outWavLen:&wavLen outHasNan:&hasNan outGenMs:&genMs
                                    outWav:&wavBuf];
        wavs.push_back(std::move(wavBuf));
        totalGenMs += genMs;
        if (hasNan) nanAny = YES;
    }
    *outWav = JoinAudioChunks(wavs, sampleRate, gaps);
    *outGenMs = totalGenMs;
    *outHasNan = nanAny;
    *outNChunks = nChunks;
}

// Runs the full smoke+stress corpus for ONE provider in this process, logging progress
// after every case (not just at the end) so a crash pinpoints where it happened.
- (void)runFullCorpusForProviderRequested:(NSString *)providerRequested {
    NSDictionary *globalMeta = [NSJSONSerialization JSONObjectWithData:[self dataForResource:@"nano_test_input/corpus/global_meta.json"] options:0 error:nil];
    int64_t padId = [globalMeta[@"pad_id"] longLongValue];
    int64_t bosId = [globalMeta[@"bos_id"] longLongValue];
    int64_t eosId = [globalMeta[@"eos_id"] longLongValue];
    double fps = [globalMeta[@"flow_fps"] doubleValue];
    int steps = [globalMeta[@"steps"] intValue];
    double cfg = [globalMeta[@"cfg"] doubleValue];
    double sway = globalMeta[@"sway"] ? [globalMeta[@"sway"] doubleValue] : 0.0;
    int sampleRate = [globalMeta[@"sample_rate"] intValue];
    NSString *voiceName = [globalMeta[@"voice"] stringByReplacingOccurrencesOfString:@" " withString:@"_"];

    std::vector<float> spk = [self floatVectorFromData:[self dataForResource:[NSString stringWithFormat:@"nano_test_input/corpus/voices/%@/spk.bin", voiceName]]];
    std::vector<float> style = [self floatVectorFromData:[self dataForResource:[NSString stringWithFormat:@"nano_test_input/corpus/voices/%@/style.bin", voiceName]]];
    std::vector<float> nullSpk = [self floatVectorFromData:[self dataForResource:@"nano_test_input/corpus/null_spk.bin"]];
    std::vector<float> nullStyle = [self floatVectorFromData:[self dataForResource:@"nano_test_input/corpus/null_style.bin"]];

    NSDictionary *casesIndexJson = [NSJSONSerialization JSONObjectWithData:[self dataForResource:@"nano_test_input/corpus/cases_index.json"] options:0 error:nil];
    NSArray *allCaseKeys = casesIndexJson[@"cases"];
    NSMutableArray<NSString *> *smokeKeys = [NSMutableArray array];
    NSMutableArray<NSString *> *stressKeys = [NSMutableArray array];
    for (NSString *key in allCaseKeys) {
        if ([key hasPrefix:@"smoke_"]) [smokeKeys addObject:key];
        else [stressKeys addObject:key];
    }

    const int smokeRunsPerCase = 3;
    BOOL anyHardFail = NO;

    Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "nano-harness");
    Ort::SessionOptions so;
    so.SetIntraOpNumThreads(4);
    NSString *providerActual = @"CPU";
    if ([providerRequested isEqualToString:@"CoreML"]) {
        uint32_t coremlFlags = COREML_FLAG_USE_NONE;
        OrtStatus *status = OrtSessionOptionsAppendExecutionProvider_CoreML(so, coremlFlags);
        if (status == nullptr) {
            providerActual = @"CoreML";
        } else {
            NSLog(@"NanoEngineTests CoreML EP unavailable, falling back to CPU: %s", Ort::GetApi().GetErrorMessage(status));
            Ort::GetApi().ReleaseStatus(status);
        }
    }

    Ort::MemoryInfo memInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    NSTimeInterval t0 = [NSDate timeIntervalSinceReferenceDate];
    Ort::Session sText(env, [[self pathForResource:@"nano_graphs/text_encoder.onnx"] UTF8String], so);
    Ort::Session sDur(env, [[self pathForResource:@"nano_graphs/duration_predictor.onnx"] UTF8String], so);
    Ort::Session sVe(env, [[self pathForResource:@"nano_graphs/vector_estimator.onnx"] UTF8String], so);
    Ort::Session sDec(env, [[self pathForResource:@"nano_graphs/codec_decoder.onnx"] UTF8String], so);
    double loadMs = ([NSDate timeIntervalSinceReferenceDate] - t0) * 1000.0;
    NSLog(@"NanoEngineTests provider=%@ actual=%@ graph_load_ms=%.1f", providerRequested, providerActual, loadMs);

    NSMutableArray *smokeResults = [NSMutableArray array];
    int caseIdx = 0;
    for (NSString *caseKey in smokeKeys) {
        caseIdx++;
        NSString *caseDir = [NSString stringWithFormat:@"nano_test_input/corpus/cases/%@", caseKey];
        NSDictionary *meta = [NSJSONSerialization JSONObjectWithData:[self dataForResource:[NSString stringWithFormat:@"%@/chunk0/meta.json", caseDir]] options:0 error:nil];

        NSMutableArray *genMsList = [NSMutableArray array];
        BOOL nanAny = NO;
        double audioSeconds = 0.0;
        int nChunksSeen = 1;
        for (int run = 0; run < smokeRunsPerCase; run++) {
            std::vector<float> wav; double genMs; BOOL hasNan; int nChunks;
            [self runInferenceMultiChunkWithCaseDir:caseDir textSession:sText durSession:sDur veSession:sVe decSession:sDec
                                             memInfo:memInfo spk:spk style:style nullSpk:nullSpk nullStyle:nullStyle
                                               padId:padId bosId:bosId eosId:eosId fps:fps steps:steps cfg:cfg sway:sway
                                          sampleRate:sampleRate outWav:&wav outGenMs:&genMs outHasNan:&hasNan outNChunks:&nChunks];
            [genMsList addObject:@(genMs)];
            if (hasNan) nanAny = YES;
            audioSeconds = (double)wav.size() / (double)sampleRate;
            nChunksSeen = nChunks;
        }
        if (nanAny) anyHardFail = YES;
        [smokeResults addObject:@{
            @"case_id": meta[@"case_id"], @"category": meta[@"category"] ?: @"",
            @"n_chunks": @(nChunksSeen),
            @"generation_ms_per_run": genMsList, @"audio_seconds": @(audioSeconds), @"nan_or_inf": @(nanAny),
        }];
        NSLog(@"NanoEngineTests progress provider=%@ smoke %d/%lu case=%@ chunks=%d nan=%d",
              providerRequested, caseIdx, (unsigned long)smokeKeys.count, meta[@"case_id"], nChunksSeen, nanAny);
    }

    NSMutableArray *stressResults = [NSMutableArray array];
    caseIdx = 0;
    for (NSString *caseKey in stressKeys) {
        caseIdx++;
        NSString *caseDir = [NSString stringWithFormat:@"nano_test_input/corpus/cases/%@", caseKey];
        NSDictionary *meta = [NSJSONSerialization JSONObjectWithData:[self dataForResource:[NSString stringWithFormat:@"%@/chunk0/meta.json", caseDir]] options:0 error:nil];
        std::vector<float> wav; double genMs; BOOL hasNan; int nChunks;
        [self runInferenceMultiChunkWithCaseDir:caseDir textSession:sText durSession:sDur veSession:sVe decSession:sDec
                                         memInfo:memInfo spk:spk style:style nullSpk:nullSpk nullStyle:nullStyle
                                           padId:padId bosId:bosId eosId:eosId fps:fps steps:steps cfg:cfg sway:sway
                                      sampleRate:sampleRate outWav:&wav outGenMs:&genMs outHasNan:&hasNan outNChunks:&nChunks];
        if (hasNan) anyHardFail = YES;
        [stressResults addObject:@{
            @"case_id": meta[@"case_id"], @"category": meta[@"category"] ?: @"",
            @"n_chunks": @(nChunks),
            @"generation_ms": @(genMs), @"audio_seconds": @((double)wav.size() / (double)sampleRate), @"nan_or_inf": @(hasNan),
        }];
        NSLog(@"NanoEngineTests progress provider=%@ stress %d/%lu case=%@ nan=%d",
              providerRequested, caseIdx, (unsigned long)stressKeys.count, meta[@"case_id"], hasNan);
    }

    NSDictionary *result = @{
        @"device": @"physical device (see xcresult/devicectl for model) — real hardware, not Simulator",
        @"smoke_runs_per_case": @(smokeRunsPerCase),
        @"voice": globalMeta[@"voice"],
        @"steps": @(steps), @"cfg": @(cfg), @"sway": @(sway),
        @"any_hard_fail": @(anyHardFail),
        @"provider_requested": providerRequested,
        @"provider_actual": providerActual,
        @"fallback_to_cpu": @(![providerActual isEqualToString:providerRequested]),
        @"graph_load_ms": @(loadMs),
        @"smoke_cases": smokeResults,
        @"stress_cases": stressResults,
    };
    NSData *jsonData = [NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted error:nil];
    NSString *jsonStr = [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
    // NSLog truncates very long lines — split into chunks like the Android harness.
    NSUInteger chunkSize = 3500;
    NSUInteger idx = 0; NSUInteger chunkNum = 0;
    while (idx < jsonStr.length) {
        NSUInteger end = MIN(idx + chunkSize, jsonStr.length);
        NSLog(@"NanoEngineTests RESULT_JSON_%@_CHUNK_%lu=%@", providerRequested, (unsigned long)chunkNum, [jsonStr substringWithRange:NSMakeRange(idx, end - idx)]);
        idx = end; chunkNum++;
    }
    NSLog(@"NanoEngineTests RESULT_JSON_%@_CHUNKS_TOTAL=%lu", providerRequested, (unsigned long)chunkNum);

    XCTAssertFalse(anyHardFail, @"hard fail detected (NaN/Inf) for provider %@ — see console RESULT_JSON", providerRequested);
}

- (void)testProviderCPU {
    [self runFullCorpusForProviderRequested:@"CPU"];
}

- (void)testProviderCoreML {
    [self runFullCorpusForProviderRequested:@"CoreML"];
}

@end
