# SDRVideoScanner Debug Context

Date saved: 2026-07-12
Branch: `feature/adaptive_gain__control`
Workspace: `D:\Projects\DroneDetector\CODE\SDRVideoScanner`

## Current Goal

Port the rock-solid frame sync behavior from `feature/cs8_iq_scan` into `feature/adaptive_gain__control`, while preserving the project architecture:

- Decoder must stay source-agnostic.
- IQ sources must feed the same decoder through `ISampleSource`.
- Kotlin only chooses/configures the source and calls JNI.
- `native-lib.cpp` should remain session/JNI glue, not DSP business logic.

## Current Status

- WebSocket CS8 path works fine after restoring the known-good decoder mode.
- IIO path was still vertically broken in the user's last test before the final IIO-specific patch.
- IIO-specific patch has been applied and builds, but has not yet been runtime-validated by the user.

Expected next runtime check for IIO:

- `decoder_mode=fast_field_preview`
- `playback_source=pluto_iio_usb_cs8_live`
- `timing_video_sample_rate_hz` should be around `1562500` for 25 MHz input, not `12500000`.

If IIO is still broken, capture the frame diagnostic text/panel from one bad IIO frame and compare these fields against WebSocket:

- `decoder_mode`
- `playback_source`
- `timing_video_sample_rate_hz`
- `timing_decimation`
- `syncs`
- `frame_sync_edges`
- `sync_score`
- `line_stability`
- `field_start_sync`
- `field_start_candidate`
- `field_start_locked`

## Important User Correction

The user explicitly corrected the direction:

> Port the video decoder algorithm, not the WebSocket source.

So WebSocket/IIO selection is only source selection for the same decoder algorithm. Do not couple decoder behavior to WebSocket directly except where native session config already identifies source kind for analysis-rate/read-block settings.

## Major Changes Made Today

### 1. Stable Decoder Algorithm Port

Replaced the experimental vertical-lock/ring/timeline decoder implementation with the simpler known-good implementation from `feature/cs8_iq_scan`:

- `app/src/main/cpp/source/video/AnalogVideoDecoder.cpp`
- `app/src/main/cpp/source/video/AnalogVideoDecoder.h`
- `app/src/main/cpp/source/video/FrameAssembler.cpp`
- `app/src/main/cpp/source/video/FrameAssembler.h`
- `app/src/main/cpp/source/video/SyncDetector.cpp`
- `app/src/main/cpp/source/video/SyncDetector.h`

Compatibility retained:

- `AnalogVideoDecoderConfig::liveFrameReadMultiplier` was kept because adaptive `native-lib.cpp` still assigns it.
- `VideoFrame` still contains `doubleImageScore` and `assemblyPath` because adaptive native diagnostics print them. They are effectively compatibility/no-op for the stable decoder path unless filled elsewhere.

### 2. Restored Known-Good Decoder Mode

Root cause of both WebSocket and IIO being broken after the first port:

- `native-lib.cpp` was configuring playback/probe sessions with `config.fastFieldPreview = false`.
- The known-good branch used `fastFieldPreview = true`.

Current state:

- `app/src/main/cpp/native-lib.cpp`
- `makePlaybackDecoderConfig(...)`
- `config.fastFieldPreview = true`
- `config.detectFrameSyncInFastPreview = true`
- `config.fastPreviewFieldStride = 2U`

After this patch, user reported WebSocket works fine.

### 3. IQ Source Selection Added

Setup IQ dialog now has source selection:

- `IIO`
- `WebSocket`

Files:

- `app/src/main/res/layout/dialog_pluto_iq_config.xml`
- `app/src/main/java/com/example/sdrvideoscanner/MainActivity.kt`

Added:

- `PlutoIqSource` enum with `IIO` and `WEBSOCKET`.
- `PlutoIqConfig.iqSource`.
- Preference key `PREF_IQ_SOURCE`.
- Diagnostics include `iq_source`.
- Main menu CS8 playback follows selected source.
- Scanner follows selected source.

Current scanner behavior:

- `PlutoIqSource.WEBSOCKET` routes to `probeChannelForSignalViaWebSocket(...)`.
- `PlutoIqSource.IIO` uses the existing adaptive IP IIO probe path.
- Both feed frames through native playback sessions and the same decoder algorithm.

### 4. IIO-Specific Fixes Applied After WebSocket Worked But IIO Did Not

The user reported:

- WebSocket works fine: `screen-20260712-223911`.
- IIO still broken: `screen-20260712-223725`.

Likely IIO-only causes found and patched:

#### IIO live buffer size

Adaptive branch had:

- `kPlutoLivePlaybackBufferSamples = 262144`

Known-good branch had:

- `kPlutoLivePlaybackBufferSamples = 32768`

Current patch restored:

- `kPlutoLivePlaybackBufferSamples = 32768`

Reason: large IIO buffers can feed stale/pre-retune samples and make vertical lock unstable or laggy.

#### IIO analysis rate

Adaptive branch temporarily treated IIO CS8 like WebSocket CS8:

- WebSocket/IIO CS8 both used 10 MHz analysis.

Current patch changed this:

- Only `pluto_websocket_cs8_live` uses `kWebSocketCs8PlaybackAnalysisRateHz = 10000000` and cutoff `2200000`.
- IIO uses default playback analysis rate `1500000` and default cutoff.

Reason: the known-good WebSocket path is raw CS8 at 10 MHz. The IIO source path may not behave like that, especially if libiio gives CS16-shaped payloads or different effective payload layout.

#### CS8 request with CS16-shaped IIO payload

`PlutoSource` currently supports single-channel packed CS8 mode, but libiio can still return 4-byte IQ pairs. Before the patch, if CS8 was requested and payload step was 4 bytes, the code read:

- `iq[0]` as I
- `iq[1]` as Q

That incorrectly maps `I_low, I_high` as `I,Q` for CS16-shaped little-endian payloads.

Current patch tracks `cs8RequestUsingCs16Payload` and extracts:

- `iq[1]` as I high byte
- `iq[3]` as Q high byte

File:

- `app/src/main/cpp/source/PlutoSource.cpp`

## Build Verification

Last command run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Result:

- Build successful.

Also ran:

```powershell
git diff --check
```

Result:

- No whitespace errors.
- Only CRLF normalization warnings on copied C++ video files.

## Current Git State Notes

Known modified files include:

- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/cpp/source/PlutoSource.cpp`
- `app/src/main/cpp/source/video/AnalogVideoDecoder.cpp`
- `app/src/main/cpp/source/video/AnalogVideoDecoder.h`
- `app/src/main/cpp/source/video/FrameAssembler.cpp`
- `app/src/main/cpp/source/video/FrameAssembler.h`
- `app/src/main/cpp/source/video/SyncDetector.cpp`
- `app/src/main/cpp/source/video/SyncDetector.h`
- `app/src/main/java/com/example/sdrvideoscanner/MainActivity.kt`
- `app/src/main/res/layout/dialog_pluto_iq_config.xml`

Untracked but required by current layout:

- `app/src/main/java/com/example/sdrvideoscanner/EdgeTimelineView.kt`

`activity_main.xml` references `EdgeTimelineView`, so do not delete the untracked file unless the layout reference is also removed or replaced.

## Files To Inspect First Tomorrow

- `app/src/main/cpp/native-lib.cpp`
  - `makePlaybackDecoderConfig(...)`
  - `createPlutoPlaybackSession(...)`
  - `decodeNextPlaybackFrame(...)`

- `app/src/main/cpp/source/PlutoSource.cpp`
  - `choosePayloadLayout(...)`
  - `PlutoSource::open()`
  - `PlutoSource::read(...)`

- `app/src/main/java/com/example/sdrvideoscanner/MainActivity.kt`
  - `probeChannelForSignal(...)`
  - `probeChannelForSignalViaWebSocket(...)`
  - `startConfiguredPlutoCs8Playback(...)`
  - `openPlutoIpIioPlaybackSessionWithRetry(...)`

## Next Steps Tomorrow

1. Runtime-test IIO after the latest IIO-specific patch.
2. Confirm IIO diagnostics show `decoder_mode=fast_field_preview` and sane `timing_video_sample_rate_hz`.
3. If IIO remains unstable, compare one WebSocket-good diagnostic and one IIO-bad diagnostic side by side.
4. If IIO payload layout is still suspect, add a one-line native diagnostic from `PlutoSource::open()` or first read with:
   - configured stride
   - payload bytes
   - selected step bytes
   - whether `cs8RequestUsingCs16Payload` is active
5. Avoid further decoder changes until IIO source payload/timing is proven correct, because WebSocket now validates the shared decoder algorithm.
