# Maia Waterfall Scanner

## Pipeline

The Scan button starts the Maia waterfall scanner in `MainActivity` and stops any active spectrum or video playback mode first.

```text
Maia /waterfall WebSocket
  -> WaterfallFrameParser
  -> MaiaWaterfallScanController
  -> scan-window sweep
  -> retune settling and stale-frame discard
  -> SpectrumAggregator
  -> SpectralCandidateDetector
  -> CandidateMerger
  -> candidate revisit voting
  -> FPV channel association
  -> AnalogFpvHeuristicClassifier
  -> DetectedSignalRecord table
```

Raw IQ streaming and video decoding are not used while the waterfall scanner is running. Selecting a confirmed analog-like signal stops the waterfall scanner and hands tuning to the existing IQ/video playback path.

## Maia Protocol Boundary

`MaiaWaterfallWebSocketClient` connects to:

```text
ws://<pluto_ip>/waterfall
```

Current upstream Maia SDR sends each waterfall line as binary `float32` linear-power FFT bins. This is implemented by `MaiaFloat32WaterfallFrameParser`, which converts bins to dB and rejects malformed, incomplete, negative, or non-finite frames.

Protocol parsing is isolated behind `WaterfallFrameParser` so future Maia frame metadata or binary format changes can be handled without changing scanner logic.

## Scanner Timing Defaults

Defaults live in `MaiaScanConfig`:

- sample rate: `30_720_000`
- RF bandwidth: `18_000_000`
- frequency step: `15_000_000`
- usable FFT span: `15_000_000`
- LO settling: `5 ms`
- discarded FFT frames after retune: `2`
- fast measurement: `10 ms`
- candidate measurement: `75 ms`
- candidate revisits: `3`
- required positive revisits: `2`
- retune timeout: `1000 ms`
- FPV channel-match tolerance: `2_000_000 Hz`

`MainActivity.loadMaiaScanConfigDefaults()` reads overrides from the existing `pluto_iq_setup` shared preferences namespace using `maia_scan_*` keys.

## Scan Ranges

Defaults live in `DefaultMaiaScanRanges`:

- `band_1g2`: `1.2-1.4 GHz`
- `band_2g4`: `2.3-2.5 GHz`
- `band_3g3`: `3.2-3.5 GHz`
- `band_4g9_6g0`: `4.9-6.0 GHz`

Each range can be disabled with:

```text
maia_scan_range_<range_id>_enabled=false
```

Scan windows are generated from range boundaries, step size, and usable FFT span. They are not aligned to FPV channel tables.

## Retune Protection

For each scan window the controller:

1. selects RF path if needed;
2. sends Maia `/api/ad9361` retune;
3. waits `loSettlingMs`;
4. updates waterfall frame metadata for the requested LO;
5. rejects frames with wrong center frequency or pre-retune timestamps;
6. discards `discardedFramesAfterRetune`;
7. requires a valid frame before collecting measurement data;
8. fails the step after `retuneTimeoutMs` instead of blocking.

Because current Maia waterfall payloads do not carry LO metadata, stale-frame protection also relies on the configured post-retune discard count. The default timeout is intentionally longer than the nominal 250 ms example because Maia waterfall output rates can be below 10 frames/sec; two discarded frames plus one accepted frame need enough time to arrive.

## Detection Policy

Fast scanning is raw spectral detection only. Known FPV channel frequencies are used later as metadata:

```text
raw spectral detection
  -> candidate merge
  -> revisit
  -> raw center-frequency estimate
  -> nearest FPV channel lookup
  -> optional channel association
  -> classification
```

Candidates outside the FPV tolerance remain valid and are published as unaligned signals.

## Tests

`MaiaScannerCoreTest` covers scan-window generation, range coverage, FFT-bin frequency conversion, edge exclusion, robust noise-floor estimation, active-bin grouping, occupied wideband detection, candidate merging, stale-frame rejection, revisit voting, nearest FPV matching, raw frequency preservation, unaligned candidate handling, and scanner cancellation with `FakeWaterfallSource`.
