// OrtEngineBridge.mm — scaffold Sprint 3.
// Port thân chính từ device-validation/DeviceValidationTests/NanoEngineTests.mm
// (đã PASS trên iOS Simulator + real device). Giữ một nguồn sự thật khi xong port:
// device-validation gọi lại core này (05-sdk-ios.md §5.2).
#import "OrtEngineBridge.h"

@implementation VNMOrtEngine {
    BOOL _loaded;
    NSString *_modelDir;
}

- (nullable instancetype)initWithModelDir:(NSString *)dir
                                    error:(NSError *_Nullable *_Nullable)error {
    self = [super init];
    if (self) {
        _modelDir = [dir copy];
        _loaded = NO;
        // TODO(Sprint 3): Ort::Env + 4 session createSession(path)
        // graphs: text_encoder / duration_predictor / vector_estimator / codec_decoder
        // BOOL ctx_mask qua ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL.
        if (error) {
            *error = [NSError errorWithDomain:@"com.vieneu.mobile"
                                         code:1
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"OrtEngineBridge scaffold — chưa bind ORT C++ (xem NanoEngineTests.mm)"}];
        }
        return nil;
    }
    return self;
}

- (BOOL)isLoaded {
    return _loaded;
}

- (nullable NSData *)synthesizeWithPhoneIds:(NSArray<NSNumber *> *)phoneIds
                                 speakerEmb:(NSData *)speakerEmb
                                      style:(NSData *)style
                                       seed:(int64_t)seed
                                      error:(NSError *_Nullable *_Nullable)error {
    if (error) {
        *error = [NSError errorWithDomain:@"com.vieneu.mobile"
                                     code:2
                                 userInfo:@{NSLocalizedDescriptionKey: @"synthesize chưa khả dụng trên scaffold"}];
    }
    return nil;
}

@end
