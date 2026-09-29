#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// One-shot TTS for a pre-exported multi-chunk case (phone IDs already on disk).
/// Mirrors NanoEngineTests inference + JoinAudioChunks; not the product SDK.
@interface NanoTtsEngineResult : NSObject
@property(nonatomic, readonly) NSData *pcmFloat32;
@property(nonatomic, readonly) double audioSeconds;
@property(nonatomic, readonly) double generationMs;
@property(nonatomic, readonly) NSInteger nChunks;
@property(nonatomic, readonly) BOOL hasNan;
@end

@interface NanoTtsEngine : NSObject

/// Load 4 graphs + demo assets from the main bundle.
/// Returns nil and fills error on failure.
- (nullable instancetype)initWithBundle:(NSBundle *)bundle
                                  error:(NSError **)error;

/// Synthesize the bundled demo paragraph. Blocking — call off the main thread.
- (nullable NanoTtsEngineResult *)synthesizeDemo:(NSError **)error;

/// Write float32 PCM as 16-bit WAV at 24 kHz. Returns file URL.
- (nullable NSURL *)writeWavFromResult:(NanoTtsEngineResult *)result
                                 error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
