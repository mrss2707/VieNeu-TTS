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
@property(nonatomic, readonly) double physFootprintMb;  // after synth (task_vm_info.phys_footprint)
@property(nonatomic, readonly) double graphBytesOnDisk;
@end

@interface NanoTtsEngine : NSObject

/// On-disk size of `bundle/<graphDirName>` in bytes (0 if missing).
+ (double)graphDirBytesOnDisk:(NSBundle *)bundle name:(NSString *)graphDirName;

/// Current process phys_footprint in MB (Instruments "Memory").
+ (double)currentPhysFootprintMb;

/// Load 4 graphs from `bundle/<graphDirName>/` + demo assets (voice My_Duyen).
/// `steps` overrides global_meta.json (use 8 for FP16, 12 for INT8 A/B).
/// Returns nil and fills error on failure.
- (nullable instancetype)initWithBundle:(NSBundle *)bundle
                            graphDirName:(NSString *)graphDirName
                                   steps:(NSInteger)steps
                                   error:(NSError **)error;

/// Load 4 graphs + demo assets from the main bundle (nano_graphs/, steps from meta).
- (nullable instancetype)initWithBundle:(NSBundle *)bundle
                                  error:(NSError **)error;

@property(nonatomic, readonly) double graphBytesOnDisk;
@property(nonatomic, readonly) NSInteger steps;

/// Synthesize the bundled demo paragraph. Blocking — call off the main thread.
/// `noiseFloorEnabled` toggles the rev-3 downward expander (A/B with the same
/// comfort EQ + soft-clip).
- (nullable NanoTtsEngineResult *)synthesizeDemo:(NSError **)error;
- (nullable NanoTtsEngineResult *)synthesizeDemoWithNoiseFloor:(BOOL)noiseFloorEnabled
                                                         error:(NSError **)error;

/// Write float32 PCM as 16-bit WAV at 24 kHz. Returns file URL.
- (nullable NSURL *)writeWavFromResult:(NanoTtsEngineResult *)result
                                 error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
