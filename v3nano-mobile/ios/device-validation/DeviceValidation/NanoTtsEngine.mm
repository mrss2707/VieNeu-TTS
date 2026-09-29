// App-side TTS engine for the UI demo button. Same inference contract as
// NanoEngineTests.mm (text_encoder → duration → vector_estimator ×steps → codec_decoder,
// sway-warped Euler, JoinAudioChunks) but exposed to Swift as a blocking one-shot.
//
// Phone IDs are pre-exported (nano_test_input/demo) — on-device G2P is still a product gap.

#import "NanoTtsEngine.h"
#import <onnxruntime/onnxruntime_cxx_api.h>
#import <algorithm>
#import <cmath>
#import <map>
#import <memory>
#import <random>
#import <string>
#import <vector>

@interface NanoTtsEngineResult ()
@property(nonatomic, readwrite) NSData *pcmFloat32;
@property(nonatomic, readwrite) double audioSeconds;
@property(nonatomic, readwrite) double generationMs;
@property(nonatomic, readwrite) NSInteger nChunks;
@property(nonatomic, readwrite) BOOL hasNan;
@end

@implementation NanoTtsEngineResult
@end

static const double kEdgeThreshDb = -45.0;
static const double kEdgeWinS = 0.01;

static void EdgeSilence(const std::vector<float> &wav, int sr, size_t *outLead, size_t *outTail) {
    size_t nSamp = wav.size();
    size_t win = std::max((size_t)1, (size_t)(kEdgeWinS * sr));
    size_t nWin = nSamp / win;
    if (nWin == 0) {
        *outLead = nSamp;
        *outTail = 0;
        return;
    }
    double threshLinear = pow(10.0, kEdgeThreshDb / 20.0);
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
    if (first == -1) {
        *outLead = nSamp;
        *outTail = 0;
        return;
    }
    *outLead = (size_t)first * win;
    *outTail = nSamp - (size_t)(last + 1) * win;
}

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

static std::vector<float> JoinAudioChunks(const std::vector<std::vector<float>> &chunks, int sr,
                                          const std::vector<std::string> &gaps) {
    if (chunks.empty()) return {};
    if (chunks.size() == 1) return chunks[0];
    std::map<std::string, double> gapSilence = {{"para", 0.70}, {"sentence", 0.50}, {"minor", 0.30}};
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
        pos += pads[i - 1];
        std::copy(chunks[i].begin(), chunks[i].end(), out.begin() + pos);
        pos += chunks[i].size();
    }
    return out;
}

@interface NanoTtsEngine () {
    std::unique_ptr<Ort::Env> _env;
    std::unique_ptr<Ort::Session> _sText;
    std::unique_ptr<Ort::Session> _sDur;
    std::unique_ptr<Ort::Session> _sVe;
    std::unique_ptr<Ort::Session> _sDec;
    Ort::MemoryInfo *_memInfo;
    std::vector<float> _spk, _style, _nullSpk, _nullStyle;
    int64_t _padId, _bosId, _eosId;
    double _fps, _cfg, _sway;
    int _steps, _sampleRate;
    std::vector<int64_t> _demoIds;  // flattened per-chunk via _demoChunkOffsets
    std::vector<size_t> _demoChunkOffsets;
    std::vector<std::string> _demoGaps;
    int _demoNChunks;
}
@end

@implementation NanoTtsEngine

static NSError *MakeError(NSString *msg) {
    return [NSError errorWithDomain:@"VieNeu.NanoTts" code:1
                           userInfo:@{NSLocalizedDescriptionKey : msg}];
}

static std::vector<float> LoadFloats(NSBundle *bundle, NSString *rel, NSError **error) {
    NSString *path = [bundle pathForResource:rel ofType:nil];
    if (!path) {
        if (error) *error = MakeError([NSString stringWithFormat:@"missing %@", rel]);
        return {};
    }
    NSData *data = [NSData dataWithContentsOfFile:path];
    const float *p = (const float *)data.bytes;
    return std::vector<float>(p, p + data.length / 4);
}

static std::vector<int64_t> LoadInt64s(NSString *path) {
    NSData *data = [NSData dataWithContentsOfFile:path];
    const int64_t *p = (const int64_t *)data.bytes;
    return std::vector<int64_t>(p, p + data.length / sizeof(int64_t));
}

- (instancetype)initWithBundle:(NSBundle *)bundle error:(NSError **)error {
    self = [super init];
    if (!self) return nil;

    NSString *graphs = [bundle.resourcePath stringByAppendingPathComponent:@"nano_graphs"];
    NSString *demo = [bundle.resourcePath stringByAppendingPathComponent:@"nano_test_input/demo"];
    if (![[NSFileManager defaultManager] fileExistsAtPath:graphs]) {
        if (error) *error = MakeError(@"nano_graphs missing from bundle");
        return nil;
    }

    NSData *gmData = [NSData dataWithContentsOfFile:[demo stringByAppendingString:@"/global_meta.json"]];
    if (!gmData) {
        if (error) *error = MakeError(@"demo/global_meta.json missing");
        return nil;
    }
    NSDictionary *gm = [NSJSONSerialization JSONObjectWithData:gmData options:0 error:nil];
    _padId = [gm[@"pad_id"] longLongValue];
    _bosId = [gm[@"bos_id"] longLongValue];
    _eosId = [gm[@"eos_id"] longLongValue];
    _fps = [gm[@"flow_fps"] doubleValue];
    _steps = [gm[@"steps"] intValue];
    _cfg = [gm[@"cfg"] doubleValue];
    _sway = gm[@"sway"] ? [gm[@"sway"] doubleValue] : 0.0;
    _sampleRate = [gm[@"sample_rate"] intValue];

    NSString *voiceName = [[gm[@"voice"] stringByReplacingOccurrencesOfString:@" " withString:@"_"]
        stringByReplacingOccurrencesOfString:@"Đ" withString:@"D"];
    // demo export uses voices/Duc_Tri
    voiceName = @"Duc_Tri";
    _spk = LoadFloats(bundle, [NSString stringWithFormat:@"nano_test_input/demo/voices/%@/spk.bin", voiceName], error);
    _style = LoadFloats(bundle, [NSString stringWithFormat:@"nano_test_input/demo/voices/%@/style.bin", voiceName], error);
    _nullSpk = LoadFloats(bundle, @"nano_test_input/demo/null_spk.bin", error);
    _nullStyle = LoadFloats(bundle, @"nano_test_input/demo/null_style.bin", error);
    if (_spk.empty() || error) {
        if (error && *error) return nil;
    }

    NSString *caseDir = [demo stringByAppendingString:@"/cases/demo_para"];
    NSData *cmData = [NSData dataWithContentsOfFile:[caseDir stringByAppendingString:@"/chunks_meta.json"]];
    NSDictionary *cm = [NSJSONSerialization JSONObjectWithData:cmData options:0 error:nil];
    _demoNChunks = [cm[@"n_chunks"] intValue];
    for (NSString *g in cm[@"gaps"]) _demoGaps.push_back(std::string([g UTF8String]));
    _demoChunkOffsets.push_back(0);
    for (int k = 0; k < _demoNChunks; k++) {
        NSString *idsPath = [NSString stringWithFormat:@"%@/chunk%d/ids.bin", caseDir, k];
        std::vector<int64_t> ids = LoadInt64s(idsPath);
        _demoIds.insert(_demoIds.end(), ids.begin(), ids.end());
        _demoChunkOffsets.push_back(_demoIds.size());
    }

    try {
        _env = std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_WARNING, "vieneu-ui");
        Ort::SessionOptions so;
        so.SetIntraOpNumThreads(4);
        std::string gdir = [graphs UTF8String];
        _sText = std::make_unique<Ort::Session>(*_env, (gdir + "/text_encoder.onnx").c_str(), so);
        _sDur = std::make_unique<Ort::Session>(*_env, (gdir + "/duration_predictor.onnx").c_str(), so);
        _sVe = std::make_unique<Ort::Session>(*_env, (gdir + "/vector_estimator.onnx").c_str(), so);
        _sDec = std::make_unique<Ort::Session>(*_env, (gdir + "/codec_decoder.onnx").c_str(), so);
        static Ort::MemoryInfo memInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        _memInfo = &memInfo;
    } catch (const std::exception &ex) {
        if (error) *error = MakeError([NSString stringWithFormat:@"ORT init: %s", ex.what()]);
        return nil;
    }
    return self;
}

- (void)runOneChunk:(const std::vector<int64_t> &)ids
                 spk:(const std::vector<float> &)spk
               style:(const std::vector<float> &)style
             nullSpk:(const std::vector<float> &)nullSpk
           nullStyle:(const std::vector<float> &)nullStyle
             outWav:(std::vector<float> *)outWav
            outMs:(double *)outMs
          outNan:(BOOL *)outNan {
    NSUInteger L = ids.size();
    std::vector<int64_t> idsShape = {1, (int64_t)L};
    std::vector<int64_t> styleShape = {1, 50, 256};
    std::vector<int64_t> spkShape = {1, 192};
    std::vector<int64_t> maskShape = {1, (int64_t)L};
    std::vector<int64_t> nullIdsShape = {1, 2};

    std::vector<uint8_t> mask(L);
    for (NSUInteger i = 0; i < L; i++) mask[i] = (ids[i] != _padId) ? 1 : 0;
    std::vector<int64_t> nullIds = {_bosId, _eosId};
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
        Ort::Value idsT = Ort::Value::CreateTensor<int64_t>(*_memInfo, const_cast<int64_t *>(idsVec.data()),
                                                            idsVec.size(), idsShp.data(), idsShp.size());
        Ort::Value styleT = Ort::Value::CreateTensor<float>(*_memInfo, const_cast<float *>(styleVec.data()),
                                                            styleVec.size(), styleShape.data(), styleShape.size());
        Ort::Value inputs[] = {std::move(idsT), std::move(styleT)};
        auto out = _sText->Run(Ort::RunOptions{nullptr}, textInNames, inputs, 2, textOutNames, 1);
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

    Ort::Value ctxT = Ort::Value::CreateTensor<float>(*_memInfo, ctx.data(), ctx.size(), ctxShape.data(), ctxShape.size());
    Ort::Value maskT = Ort::Value::CreateTensor(*_memInfo, mask.data(), mask.size(), maskShape.data(), maskShape.size(),
                                                ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL);
    Ort::Value spkT = Ort::Value::CreateTensor<float>(*_memInfo, const_cast<float *>(spk.data()), spk.size(),
                                                      spkShape.data(), spkShape.size());
    Ort::Value durInputs[] = {std::move(ctxT), std::move(maskT), std::move(spkT)};
    auto durOut = _sDur->Run(Ort::RunOptions{nullptr}, durInNames, durInputs, 3, durOutNames, 1);
    float logS = durOut[0].GetTensorMutableData<float>()[0];
    double secs = fmin(exp((double)logS), 15.0);
    int64_t T = fmax(2, llround(secs * _fps));

    const int64_t C = 144;
    std::vector<float> x((size_t)(C * T));
    std::mt19937 rng(0);
    std::normal_distribution<float> gauss(0.0f, 1.0f);
    for (auto &v : x) v = gauss(rng);
    std::vector<int64_t> xShape = {1, C, T};

    int n = _steps;
    std::vector<double> tg(n + 1);
    for (int i = 0; i <= n; i++) {
        double u = (double)i / n;
        tg[i] = u + _sway * (cos(M_PI / 2.0 * u) - 1.0 + u);
    }

    auto runVectorEstimator = [&](const std::vector<float> &xVec, float tVal,
                                  const std::vector<float> &ctxVec, const std::vector<int64_t> &ctxShp,
                                  const std::vector<uint8_t> &maskVec, const std::vector<int64_t> &maskShp,
                                  const std::vector<float> &spkVec,
                                  const std::vector<float> &styleVec) -> std::vector<float> {
        std::vector<int64_t> tShape = {1};
        Ort::Value xT = Ort::Value::CreateTensor<float>(*_memInfo, const_cast<float *>(xVec.data()), xVec.size(),
                                                        xShape.data(), xShape.size());
        Ort::Value tT = Ort::Value::CreateTensor<float>(*_memInfo, &tVal, 1, tShape.data(), tShape.size());
        Ort::Value cT = Ort::Value::CreateTensor<float>(*_memInfo, const_cast<float *>(ctxVec.data()), ctxVec.size(),
                                                        ctxShp.data(), ctxShp.size());
        Ort::Value mT = Ort::Value::CreateTensor(*_memInfo, const_cast<uint8_t *>(maskVec.data()), maskVec.size(),
                                                 maskShp.data(), maskShp.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL);
        Ort::Value sT = Ort::Value::CreateTensor<float>(*_memInfo, const_cast<float *>(spkVec.data()), spkVec.size(),
                                                        spkShape.data(), spkShape.size());
        Ort::Value stT = Ort::Value::CreateTensor<float>(*_memInfo, const_cast<float *>(styleVec.data()), styleVec.size(),
                                                         styleShape.data(), styleShape.size());
        Ort::Value inputs[] = {std::move(xT), std::move(tT), std::move(cT), std::move(mT), std::move(sT), std::move(stT)};
        auto out = _sVe->Run(Ort::RunOptions{nullptr}, veInNames, inputs, 6, veOutNames, 1);
        float *p = out[0].GetTensorMutableData<float>();
        size_t cnt = out[0].GetTensorTypeAndShapeInfo().GetElementCount();
        return std::vector<float>(p, p + cnt);
    };

    for (int i = 0; i < n; i++) {
        float tVal = (float)tg[i];
        std::vector<float> v = runVectorEstimator(x, tVal, ctx, ctxShape, mask, maskShape, spk, style);
        std::vector<float> vFinal;
        if (_cfg > 0) {
            std::vector<float> vu = runVectorEstimator(x, tVal, nullCtx, nullCtxShape, nullMask, nullIdsShape, nullSpk, nullStyle);
            vFinal.resize(v.size());
            for (size_t j = 0; j < v.size(); j++) vFinal[j] = vu[j] + (float)_cfg * (v[j] - vu[j]);
        } else {
            vFinal = v;
        }
        float dt = (float)(tg[i + 1] - tg[i]);
        for (size_t j = 0; j < x.size(); j++) x[j] = x[j] + dt * vFinal[j];
    }

    Ort::Value xFinal = Ort::Value::CreateTensor<float>(*_memInfo, x.data(), x.size(), xShape.data(), xShape.size());
    Ort::Value decInputs[] = {std::move(xFinal)};
    auto decOut = _sDec->Run(Ort::RunOptions{nullptr}, decInNames, decInputs, 1, decOutNames, 1);
    float *wav = decOut[0].GetTensorMutableData<float>();
    size_t wavLen = decOut[0].GetTensorTypeAndShapeInfo().GetElementCount();
    *outMs = ([NSDate timeIntervalSinceReferenceDate] - t0) * 1000.0;
    BOOL hasNan = NO;
    for (size_t i = 0; i < wavLen; i++) {
        if (std::isnan(wav[i]) || std::isinf(wav[i])) {
            hasNan = YES;
            break;
        }
    }
    *outNan = hasNan;
    outWav->assign(wav, wav + wavLen);
}

- (NanoTtsEngineResult *)synthesizeDemo:(NSError **)error {
    if (_demoNChunks <= 0) {
        if (error) *error = MakeError(@"demo case empty");
        return nil;
    }
    std::vector<std::vector<float>> wavs;
    double totalMs = 0;
    BOOL nanAny = NO;
    for (int k = 0; k < _demoNChunks; k++) {
        size_t a = _demoChunkOffsets[k], b = _demoChunkOffsets[k + 1];
        std::vector<int64_t> ids(_demoIds.begin() + a, _demoIds.begin() + b);
        std::vector<float> wav;
        double ms = 0;
        BOOL nan = NO;
        try {
            [self runOneChunk:ids spk:_spk style:_style nullSpk:_nullSpk nullStyle:_nullStyle
                      outWav:&wav outMs:&ms outNan:&nan];
        } catch (const std::exception &ex) {
            if (error) *error = MakeError([NSString stringWithFormat:@"infer chunk %d: %s", k, ex.what()]);
            return nil;
        }
        wavs.push_back(std::move(wav));
        totalMs += ms;
        if (nan) nanAny = YES;
    }
    std::vector<float> joined = JoinAudioChunks(wavs, _sampleRate, _demoGaps);
    NanoTtsEngineResult *result = [NanoTtsEngineResult new];
    result.pcmFloat32 = [NSData dataWithBytes:joined.data() length:joined.size() * sizeof(float)];
    result.audioSeconds = (double)joined.size() / (double)_sampleRate;
    result.generationMs = totalMs;
    result.nChunks = _demoNChunks;
    result.hasNan = nanAny;
    return result;
}

- (NSURL *)writeWavFromResult:(NanoTtsEngineResult *)result error:(NSError **)error {
    NSData *pcm = result.pcmFloat32;
    size_t nSamp = pcm.length / sizeof(float);
    const float *src = (const float *)pcm.bytes;
    NSMutableData *wav = [NSMutableData dataWithCapacity:44 + nSamp * 2];
    uint32_t sr = (uint32_t)_sampleRate;
    uint16_t ch = 1, bps = 16;
    uint32_t byteRate = sr * ch * bps / 8;
    uint16_t blockAlign = (uint16_t)(ch * bps / 8);
    uint32_t dataBytes = (uint32_t)(nSamp * 2);
    uint32_t riffSize = 36 + dataBytes;
    uint32_t fmt = 16, audioFmt = 1;
    [wav appendBytes:"RIFF" length:4];
    [wav appendBytes:&riffSize length:4];
    [wav appendBytes:"WAVE" length:4];
    [wav appendBytes:"fmt " length:4];
    [wav appendBytes:&fmt length:4];
    [wav appendBytes:&audioFmt length:2];
    [wav appendBytes:&ch length:2];
    [wav appendBytes:&sr length:4];
    [wav appendBytes:&byteRate length:4];
    [wav appendBytes:&blockAlign length:2];
    [wav appendBytes:&bps length:2];
    [wav appendBytes:"data" length:4];
    [wav appendBytes:&dataBytes length:4];
    for (size_t i = 0; i < nSamp; i++) {
        float v = std::max(-1.0f, std::min(1.0f, src[i]));
        int16_t s = (int16_t)std::lrintf(v * 32767.0f);
        [wav appendBytes:&s length:2];
    }
    NSURL *url = [[NSURL fileURLWithPath:NSTemporaryDirectory()] URLByAppendingPathComponent:@"vieneu_demo.wav"];
    if (![wav writeToURL:url options:NSDataWritingAtomic error:error]) return nil;
    return url;
}

@end
