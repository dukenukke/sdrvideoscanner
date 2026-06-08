# Architecture

## Product Direction

This project must evolve into a video signal scanner for analog FPV video, not only an IQ file decoder.

The scanner should support direct and mixed RF coverage for:

- 1.2 GHz
- 3.3 GHz
- 4.9 GHz
- 5.8 GHz
- Optional 6.2 GHz and 7.1 GHz through an external mixer and PLL

Primary workflow:

- Scan configured frequencies for analog video signal signatures.
- When a candidate signal is detected, lock to that frequency.
- Start the video decode/render path only after lock.
- When the user presses Skip, stop the current lock and resume scanning from the next scheduled frequency.
- Support manual tune.
- Support IQ recording from the active source.

## High-Level Pipeline

```text
Kotlin UI
  |
JNI / Native API
  |
ScanController
  |
  +-- BandPlan
  +-- FrequencyScheduler
  +-- IRadioTuner
  |     +-- PlutoDirectTuner
  |     +-- ExternalMixerPllTuner
  |
  +-- ISampleSource
  |     +-- FileSource
  |     +-- PlutoSource
  |
  +-- VideoSignatureDetector
  |
  +-- DSP Pipeline
  |
  +-- VideoDecoder
  |
  +-- VideoRenderer
```

Current MVP scope only implements raw CS16 file reading through `FileSource`. Pluto tuning, DSP, video signature detection, and video decoding are future stages.

## Core Components

### ScanController

Owns scanner state and coordinates tuning, sample acquisition, signal detection, decoder startup, skip, manual tune, and IQ recording.

Responsibilities:

- Start and stop scanning.
- Request the next frequency from `FrequencyScheduler`.
- Ask `IRadioTuner` to tune hardware when using a live radio source.
- Read IQ blocks from `ISampleSource`.
- Feed pre-decode IQ blocks into `VideoSignatureDetector`.
- Promote a detected candidate into locked state.
- Start decoder/render flow only after candidate lock.
- Handle Skip by stopping decoder flow and resuming scan from the next frequency.
- Handle Manual Tune by bypassing normal schedule selection while preserving the same detector/decoder flow.
- Coordinate IQ recording from the currently active `ISampleSource`.

### BandPlan

Describes what frequencies are valid scan targets and how each band should be scanned.

Responsibilities:

- Define supported bands: 1.2, 3.3, 4.9, 5.8, optional 6.2/7.1 GHz.
- Store frequency ranges, channel lists, step sizes, dwell times, and labels.
- Mark whether a band is direct-tune or requires an external mixer/PLL.
- Provide per-band constraints, such as minimum step size or tuner limits.
- Keep regional/product-specific frequency lists separate from scanner control logic.

### FrequencyScheduler

Converts `BandPlan` data and user scan settings into an ordered sequence of tune requests.

Responsibilities:

- Iterate through enabled bands and channels.
- Resume from the next frequency after Skip.
- Support scan policies such as channel-list scan, stepped sweep, priority bands, and manual insertion.
- Track dwell timeout per frequency.
- Avoid embedding tuner hardware details.

### VideoSignatureDetector

Analyzes IQ blocks for analog FPV video-like signal signatures before decoder startup.

Responsibilities:

- Consume `SampleBuffer` blocks from `ISampleSource`.
- Estimate whether a block contains a candidate video signal.
- Return signal confidence, coarse metrics, and lock hints.
- Remain independent from tuner implementation.
- Remain separate from full DSP/video decoder implementation.

Future detector inputs may include power envelope, bandwidth estimate, sync-like periodicity, AM/FM video characteristics, or other analog FPV-specific features. These are architecture placeholders only, not current implementation requirements.

### IRadioTuner

Abstracts frequency control from sample acquisition and decoder logic.

Responsibilities:

- Tune to requested RF frequency.
- Report whether tuning is direct or mixer-assisted.
- Expose current effective RF frequency.
- Validate requested frequency against tuner capabilities.
- Hide Pluto-specific and external PLL-specific control details from `ScanController`.

### PlutoDirectTuner

Direct tuner implementation for frequencies the Pluto can tune without external conversion.

Responsibilities:

- Configure Pluto LO/frequency for direct RF coverage.
- Report tuning success/failure.
- Pair with `PlutoSource` for live IQ acquisition.

This is not part of the current MVP implementation.

### ExternalMixerPllTuner

Tuner implementation for frequencies that require external frequency conversion.

Responsibilities:

- Control external PLL/mixer configuration.
- Compute mixer LO settings for target RF frequency.
- Tune Pluto to the appropriate IF or converted frequency.
- Report both requested RF frequency and actual Pluto tune frequency.

This enables optional 6.2/7.1 GHz support and any future bands outside direct tuner range.

This is not part of the current MVP implementation.

### ISampleSource

Provides IQ samples to scanner, detector, recorder, and decoder stages.

Known implementations:

- `FileSource`: reads raw little-endian CS16 IQ files.
- `PlutoSource`: future live SDR source.

Important separation:

- `IRadioTuner` controls RF frequency.
- `ISampleSource` provides IQ samples.
- Some live sources may be configured together, but scanner logic should treat these as separate responsibilities.

### Decoder Interaction

The decoder should not control scanning or tuning.

Expected flow:

- During scan, IQ blocks go to `VideoSignatureDetector`.
- If no candidate is found before dwell timeout, scanner advances frequency.
- If a candidate is found, `ScanController` enters locked state.
- Decoder starts receiving IQ/sample data only after lock.
- Renderer displays decoded video while locked.
- Skip stops decoder/render flow and returns control to scheduler.

## Scan State Machine

```text
Idle
  |
  | Start Scan
  v
Scanning
  |
  | Tune request
  v
Tuning
  |
  | Tune OK
  v
Dwelling
  |
  | Candidate detected
  v
Candidate
  |
  | Lock confirmed
  v
Locked
  |
  | Skip
  v
Skipping
  |
  | Advance scheduler
  v
Scanning
```

Additional transitions:

- `Idle -> ManualTune` when the user manually selects a frequency.
- `ManualTune -> Candidate` if video-like signal is detected.
- `ManualTune -> Locked` if manual tune forces decoder startup.
- `Locked -> Recording` when IQ recording is enabled.
- `Recording -> Locked` when IQ recording stops.
- Any active state can transition to `Idle` on Stop.
- Any tuner/source error transitions to `Error`, then either `Idle` or `Scanning` depending on user action.

## State Responsibilities

- `Idle`: no active scan or decode.
- `Scanning`: scanner requests the next scheduled frequency.
- `Tuning`: tuner applies requested frequency or mixer/PLL settings.
- `Dwelling`: source samples are read for a bounded dwell window and inspected by detector.
- `Candidate`: detector has found a likely video signal; scanner may gather confirmation blocks.
- `Locked`: decoder/render path is active for the current frequency.
- `Skipping`: current lock is torn down and scheduler advances to the next frequency.
- `ManualTune`: user-selected frequency overrides scheduler.
- `Recording`: IQ recording is active while scan/manual/lock context remains known.
- `Error`: tuner, source, detector, or decoder error is surfaced without corrupting scheduler state.

## User Operations

### Scan

Starts scheduler-driven scan across enabled bands.

### Skip

Valid only when candidate or locked. Stops candidate confirmation or decoder/render flow and resumes scanning from the next scheduled frequency.

### Manual Tune

Tunes to a user-selected frequency using the same `IRadioTuner` abstraction. Manual tune can still use detector and decoder flow, but does not advance the normal scheduler unless explicitly requested.

### Record IQ

Records raw IQ from the active `ISampleSource`. Recording should be possible during scan dwell, manual tune, or locked video display.

## Design Constraints

- Scanning logic must remain separate from decoding.
- Tuning logic must remain separate from sample reading.
- `FileSource` remains valid for replaying IQ files produced by existing `video_decoder_c` / `video_analyzer_c` tooling.
- `PlutoSource` must not be required for file-based development and testing.
- External mixer/PLL support must be modeled as tuner behavior, not as a decoder concern.
- The decoder starts after lock, not during every frequency dwell.
- Architecture must allow adding bands and tuner backends without rewriting detector or decoder code.
