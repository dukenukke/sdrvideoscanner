# SDRVideoScanner Debug Context

Current focus: unstable analog FPV frame syncing using CS8 IQ samples captured by the application. The intent is to debug deterministically from `FileSource` replay while keeping the live Pluto/WebSocket pipeline using the same decoder path.

## Development Rules In Effect

- `native-lib.cpp` should remain a thin JNI/session wrapper.
- DSP and decoder logic belongs in reusable C++17 blocks under `app/src/main/cpp/source/...`.
- IQ sources implement `ISampleSource`; `FileSource` is the reference deterministic replay source.
- Decoder must not know where IQ samples originate.
- Avoid dynamic allocation in the real-time loop where practical; reuse buffers.
- Do not change project directory structure without approval.

## Frame Sync Enhancement Status

From the prior recommended sequence:

- D: Sample-domain vertical lock is mostly implemented in `AnalogVideoDecoder` via `lockedActiveStartSample_`, `pendingActiveStartSample_`, `videoSampleCursor_`, and nearest-H-sync mapping only for frame assembly.
- F: Multi-field/double-image rejection is implemented via `FrameAssembler::doubleImageScore`, alternate start candidates, preserved-lock fallback, and sequential bad-frame rejection.
- G: Soft vertical tracking is implemented: <=2 lines hold, 3-12 lines move 1 line/frame, >12 lines relock after 3 stable candidates.
- E: Partially implemented. `SyncDetector` has an FPV-generic frame-sync cluster detector using long sync runs, period scoring, and post-edge blanking score. It is not yet a true PAL/NTSC equalizing/serration template correlator.
- H: Mostly implemented. Start priority is frame-sync/V first, vertical blanking second, locked start third, full active-window search only when no lock exists.
- I: Implemented. Active start around a V-edge scans roughly lines 8..40 for first active lines.
- K: Not implemented. `VideoLowPassFilter` exists but is not applied before normalization/sync.

## Important Finding

Likely bug: `AnalogVideoDecoder::sampleLockedFieldStartSyncIndex()` works in decimated video-sample coordinates (`video_`, `syncStarts`, `frameSyncEdges`, `videoSampleCursor_`), but calls `samplesPerLine()`, which currently returns input-IQ-rate samples from `config_.sampleRateHz`.

This is wrong when CS8 file playback decimates high-rate captures to the default analysis rate (for example 10 MHz -> 1.5 MHz path). Residual thresholds and one-line correction steps become too large by the decimation factor. This can make vertical lock sticky/jumpy even though D/F/G are present.

Fix direction: compute the line length inside sample-domain vertical tracking from `videoSampleRateHz / timing.lineRateHz`, or add a helper that accepts the effective video sample rate. Keep diagnostics in video-sample units unless explicitly converted.

## Useful Files

- `app/src/main/cpp/source/video/AnalogVideoDecoder.cpp`
- `app/src/main/cpp/source/video/AnalogVideoDecoder.h`
- `app/src/main/cpp/source/video/FrameAssembler.cpp`
- `app/src/main/cpp/source/video/SyncDetector.cpp`
- `app/src/main/cpp/source/video/VideoLowPassFilter.cpp`
- `app/src/main/cpp/source/file/FileSource.cpp`
- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/java/com/example/sdrvideoscanner/MainActivity.kt`

## Debugging From IDE

CS8 replay through Android Studio is possible and preferred for this issue.

Use the app's playback-session path, not only one-frame decode, because vertical lock state persists across calls only in the session:

- `createAnalogVideoPlaybackSession(...)`
- `decodeNextAnalogVideoPlaybackFrame(...)`
- `decodeNextPlaybackFrame(...)`
- `AnalogVideoDecoder::decodeOneFrame(...)`

Breakpoints worth setting:

- `AnalogVideoDecoder::decodeOneFrame`
- `AnalogVideoDecoder::sampleLockedFieldStartSyncIndex`
- `FrameAssembler::chooseFieldPreviewStartSyncIndex`
- `SyncDetector::detectFrameSyncEdges`
- `FrameAssembler::doubleImageScore`

Caveat: `app/src/main/cpp/CMakeLists.txt` currently applies `-O3` globally, including debug builds. LLDB breakpoints may hit, but stepping/local variables can be unreliable. For serious native debugging, use Debug `-O0 -g` and keep Release optimized.

## Diagnostics To Watch

The decoder already emits many of the needed values in frame diagnostics:

- `field_start_sync`
- `field_start_candidate`
- `double_image_score`
- `assembly_path`
- `field_start_line_offset`
- `field_start_locked`
- `v_sample_locked`
- `v_relock_count`
- `v_residual_samples`
- `v_edge_sample`
- `v_edge_quality`
- `field_period_err`
- `h_sync_missing_rate`

Next useful capture/debug question: after fixing the decimated-rate line length, check whether instability is due to bad V-edge candidates, sticky sample lock, or double-image alternate selection.
