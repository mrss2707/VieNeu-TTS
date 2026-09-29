// OrtEngineBridge.h — ORT C++ API wrapper (Objective-C++).
//
// Bắt buộc: ctx_mask kiểu BOOL — ObjC wrapper của onnxruntime-swift-package-manager
// (kể cả 1.24.2) KHÔNG expose ORTTensorElementDataTypeBool (GH issues #31, #41).
// Giải pháp đã chốt: Ort::Value::CreateTensor(..., ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL)
// trong .mm — xem device-validation/DeviceValidationTests/NanoEngineTests.mm (port 1:1).
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

NS_SWIFT_NAME(OrtEngine)
@interface VNMOrtEngine : NSObject

- (nullable instancetype)initWithModelDir:(NSString *)dir
                                    error:(NSError *_Nullable *_Nullable)error;

/// Sinh PCM 24 kHz mono float32 cho MỘT chunk phone ids.
/// speakerEmb: 192 floats · style: 50×256 floats (flatten 12800).
- (nullable NSData *)synthesizeWithPhoneIds:(NSArray<NSNumber *> *)phoneIds
                                 speakerEmb:(NSData *)speakerEmb
                                      style:(NSData *)style
                                       seed:(int64_t)seed
                                      error:(NSError *_Nullable *_Nullable)error;

@property (nonatomic, readonly) BOOL isLoaded;

@end

NS_ASSUME_NONNULL_END
