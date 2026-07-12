#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "../ISampleSource.h"
#include "../SampleBuffer.h"
#include "AmDemodulator.h"
#include "FmDemodulator.h"
#include "FrameAssembler.h"
#include "SyncDetector.h"
#include "VideoFrame.h"
#include "VideoLowPassFilter.h"
#include "VideoNormalizer.h"

namespace sdr {

struct AnalogVideoDecoderConfig {
    std::uint64_t sampleRateHz = 0;
    std::uint64_t analysisRateHz = 2000000;
    double cutoffHz = 5000000.0;
    VideoTiming timing;
    std::size_t readBlockSamples = 32768;
    bool fastFieldPreview = false;
    bool detectFrameSyncInFastPreview = false;
    std::size_t fastPreviewFieldStride = 1;
    double liveFrameReadMultiplier = 1.0;
};

class AnalogVideoDecoder {
public:
    explicit AnalogVideoDecoder(AnalogVideoDecoderConfig config);

    VideoFrame decodeOneFrame(ISampleSource& source);

private:
    struct VerticalEdgeCandidate {
        double absoluteSample = 0.0;
        double quality = 0.0;
    };

    std::size_t frameSampleCount();
    double frameReadGuardLines() const;
    void resetVerticalSampleLock();
    void resetVerticalEdgeHistory();
    void appendSyncHistory(
            const std::vector<std::uint8_t>& syncVideo,
            std::uint64_t videoSampleRateHz);
    void appendVerticalEdgeCandidates(
            const std::vector<std::size_t>& frameSyncEdges,
            std::uint64_t videoSampleRateHz,
            double quality);
    double standardFieldPeriodSamples(std::uint64_t videoSampleRateHz) const;
    std::size_t strictFrameSyncEdges(
            const std::vector<std::size_t>& frameSyncEdges,
            std::uint64_t videoSampleRateHz,
            std::vector<std::size_t>& selectedFrameSyncEdges);
    void updateTimelineEdgeDiagnostics(std::uint64_t videoSampleRateHz);
    VideoFrame assembleBestFieldPreviewFrame(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::size_t preferredStartSyncIndex,
            std::size_t candidateStartSyncIndex,
            std::uint64_t videoSampleRateHz,
            std::size_t& selectedStartSyncIndex);
    void acceptVerticalSampleStart(
            const std::vector<std::size_t>& syncStarts,
            std::size_t selectedStartSyncIndex);
    bool isBadSequentialFieldFrame(const VideoFrame& frame) const;
    std::size_t samplesPerLine() const;
    std::size_t sampleLockedFieldStartSyncIndex(
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::size_t candidateStartSyncIndex,
            std::uint64_t videoSampleRateHz,
            std::size_t videoSampleCount);
    std::size_t lockedFieldStartSyncIndex(
            std::size_t candidateStartSyncIndex,
            std::size_t detectedSyncCount);

    AnalogVideoDecoderConfig config_;
    AmDemodulator demodulator_;
    FmDemodulator fmDemodulator_;
    VideoLowPassFilter lowPassFilter_;
    VideoNormalizer normalizer_;
    SyncDetector syncDetector_;
    FrameAssembler frameAssembler_;
    SampleBuffer sampleBuffer_;
    std::vector<float> videoBaseband_;
    std::vector<float> syncBaseband_;
    std::vector<std::uint8_t> video_;
    std::vector<std::uint8_t> syncVideo_;
    std::vector<std::uint8_t> syncHistoryRing_;
    std::vector<VerticalEdgeCandidate> verticalEdgeHistory_;
    std::vector<std::size_t> strictSelectedFrameSyncEdges_;
    std::vector<std::size_t> skippedFrameSyncEdges_;
    std::vector<double> strictTimelineEdgeSamples_;
    std::vector<std::size_t> timelineStrictEdges_;
    std::vector<std::size_t> timelineSkippedEdges_;
    std::vector<std::size_t> emptyFrameSyncEdges_;
    std::size_t syncHistoryCapacitySamples_ = 0;
    std::size_t syncHistoryStartSample_ = 0;
    std::size_t syncHistorySizeSamples_ = 0;
    double syncHistoryFirstSample_ = 0.0;
    bool strictVEdgeLocked_ = false;
    double strictLockedVEdgeSample_ = 0.0;
    double lastStrictVEdgeSample_ = 0.0;
    double lastStrictVEdgeIntervalError_ = 0.0;
    std::size_t lastStrictVEdgeChainLength_ = 0;
    std::size_t strictVEdgeMissCount_ = 0;
    double fastFieldSampleRemainder_ = 0.0;
    bool fieldStartLocked_ = false;
    std::size_t fieldStartSyncIndex_ = 0;
    std::size_t pendingFieldStartSyncIndex_ = 0;
    std::size_t fieldStartRejectCount_ = 0;
    bool verticalSampleLock_ = false;
    double lockedVEdgeSample_ = 0.0;
    double pendingVEdgeSample_ = 0.0;
    double lockedActiveStartSample_ = 0.0;
    double pendingActiveStartSample_ = 0.0;
    double videoSampleCursor_ = 0.0;
    double lastVEdgeResidualSamples_ = 0.0;
    double lastFieldStartLineOffset_ = 0.0;
    double lastVEdgeSample_ = 0.0;
    double lastVEdgeQuality_ = 0.0;
    double lastFieldPeriodError_ = 0.0;
    double lastHSyncMissingRate_ = 0.0;
    std::size_t verticalRelockCount_ = 0;
};

}  // namespace sdr
