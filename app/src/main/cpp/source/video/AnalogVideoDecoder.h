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
};

class AnalogVideoDecoder {
public:
    explicit AnalogVideoDecoder(AnalogVideoDecoderConfig config);

    VideoFrame decodeOneFrame(ISampleSource& source);

private:
    std::size_t frameSampleCount();
    std::size_t samplesPerLine() const;
    std::size_t lockedFastFieldStartSyncIndex(
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
    std::vector<std::uint8_t> video_;
    double fastFieldSampleRemainder_ = 0.0;
    bool fastFieldStartLocked_ = false;
    std::size_t fastFieldStartSyncIndex_ = 0;
};

}  // namespace sdr
