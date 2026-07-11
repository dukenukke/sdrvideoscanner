#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "VideoFrame.h"

namespace sdr {

enum class VideoStandard {
    PAL625_25FPS,
    NTSC_525_30FPS,
    AUTO
};

struct VideoTiming {
    VideoStandard standard = VideoStandard::PAL625_25FPS;
    std::uint32_t frameWidth = 720;
    std::uint32_t totalLines = 625;
    std::uint32_t visibleLines = 625;
    bool interlaced = false;
    double frameRateHz = 25.0;
    double lineRateHz = 15625.0;
};

const char* videoStandardName(VideoStandard standard);
VideoTiming timingForStandard(VideoStandard standard);

class FrameAssembler {
public:
    explicit FrameAssembler(VideoTiming timing);

    VideoFrame assembleFromSync(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::uint64_t sampleRateHz) const;

    VideoFrame assembleFieldPreviewFromSync(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::size_t startSyncIndex,
            std::uint64_t sampleRateHz) const;

    std::size_t chooseFieldPreviewStartSyncIndex(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::uint64_t sampleRateHz,
            std::size_t lockedStartSyncIndex,
            bool hasLockedStart) const;

    VideoFrame assembleRawRaster(
            const std::vector<std::uint8_t>& video,
            std::uint64_t sampleRateHz,
            std::size_t detectedSyncCount) const;

private:
    std::size_t samplesPerLine(std::uint64_t sampleRateHz) const;
    std::size_t activeStartOffset(std::uint64_t sampleRateHz) const;
    std::size_t activeSamples(std::uint64_t sampleRateHz, std::size_t samplesPerLine) const;
    std::size_t chooseInterlacedStartSyncIndex(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::size_t activeOffset,
            std::size_t samplesPerActiveLine) const;
    std::size_t chooseInterlacedStartFromActiveWindow(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            std::size_t activeOffset,
            std::size_t samplesPerActiveLine) const;
    std::size_t chooseInterlacedStartFromFrameSync(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::size_t activeOffset,
            std::size_t samplesPerActiveLine) const;
    std::size_t chooseInterlacedStartFromVerticalBlanking(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            std::size_t activeOffset,
            std::size_t samplesPerActiveLine) const;
    std::vector<std::size_t> chooseFieldSpanSyncIndices(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::size_t preferredStartSyncIndex,
            std::uint64_t sampleRateHz,
            std::size_t activeOffset,
            std::size_t samplesPerActiveLine) const;
    std::vector<std::size_t> chooseReferenceBobFieldStarts(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            const std::vector<std::size_t>& frameSyncEdges,
            std::size_t preferredStartSyncIndex,
            std::uint64_t sampleRateHz,
            std::size_t activeEnd) const;
    std::vector<std::size_t> lineStartsInSpan(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            double samplesPerLine,
            std::size_t activeEnd,
            std::size_t spanStart,
            std::size_t spanEnd,
            std::size_t vbiLines,
            double& quality) const;
    double doubleImageScore(const VideoFrame& frame) const;
    std::size_t findBestSyncNearPrediction(
            const std::vector<std::size_t>& syncStarts,
            double prediction,
            std::size_t search) const;
    std::size_t chooseActiveStartNearFieldSync(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            std::size_t edgeSyncIndex,
            std::size_t activeOffset,
            std::size_t samplesPerActiveLine,
            std::size_t maxUsableStart) const;
    double activeWindowScore(
            const std::vector<std::uint8_t>& video,
            const std::vector<std::size_t>& syncStarts,
            std::size_t candidateSyncIndex,
            std::size_t activeOffset,
            std::size_t samplesPerActiveLine,
            std::size_t nominalSyncIndex) const;
    std::size_t interlacedSourceLineCount() const;
    double lineMean(
            const std::vector<std::uint8_t>& video,
            std::size_t sourceStart,
            std::size_t sourceLength) const;
    double lineActivity(
            const std::vector<std::uint8_t>& video,
            std::size_t sourceStart,
            std::size_t sourceLength) const;
    void copyResampledLine(
            const std::vector<std::uint8_t>& video,
            std::size_t sourceStart,
            std::size_t sourceLength,
            VideoFrame& frame,
            std::uint32_t outputLine) const;
    bool shouldBobInterlaced() const;

    VideoTiming timing_;
};

}  // namespace sdr
