#include "FrameAssembler.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <vector>

namespace sdr {
namespace {

std::size_t maxUsableStartSyncIndexForActiveLines(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        std::size_t activeOffset,
        std::size_t samplesPerActiveLine,
        std::size_t requiredLineCount) {
    if (video.empty() || syncStarts.empty() || requiredLineCount == 0) {
        return static_cast<std::size_t>(-1);
    }

    const auto activeEnd = activeOffset + samplesPerActiveLine;
    std::size_t usableSyncCount = 0;
    for (std::size_t index = 0; index < syncStarts.size(); ++index) {
        if (syncStarts[index] + activeEnd <= video.size()) {
            usableSyncCount = index + 1U;
            continue;
        }

        if (syncStarts[index] >= video.size()) {
            break;
        }
    }

    if (usableSyncCount < requiredLineCount) {
        return static_cast<std::size_t>(-1);
    }
    return usableSyncCount - requiredLineCount;
}

}  // namespace

const char* videoStandardName(VideoStandard standard) {
    switch (standard) {
        case VideoStandard::PAL625_25FPS:
            return "PAL625_25FPS";
        case VideoStandard::NTSC_525_30FPS:
            return "NTSC_525_30FPS";
        case VideoStandard::AUTO:
            return "AUTO";
    }
    return "UNKNOWN";
}

VideoTiming timingForStandard(VideoStandard standard) {
    VideoTiming timing;
    timing.standard = standard;
    switch (standard) {
        case VideoStandard::PAL625_25FPS:
            timing.frameWidth = 768;
            timing.totalLines = 625;
            timing.visibleLines = 576;
            timing.interlaced = true;
            timing.frameRateHz = 25.0;
            timing.lineRateHz = 15625.0;
            break;
        case VideoStandard::NTSC_525_30FPS:
            timing.frameWidth = 640;
            timing.totalLines = 525;
            timing.visibleLines = 480;
            timing.interlaced = true;
            timing.frameRateHz = 30000.0 / 1001.0;
            timing.lineRateHz = 15734.0;
            break;
        case VideoStandard::AUTO:
            timing = timingForStandard(VideoStandard::PAL625_25FPS);
            timing.standard = VideoStandard::AUTO;
            break;
    }
    return timing;
}

FrameAssembler::FrameAssembler(VideoTiming timing)
        : timing_(timing) {}

VideoFrame FrameAssembler::assembleFromSync(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::uint64_t sampleRateHz) const {
    VideoFrame frame;
    frame.width = timing_.frameWidth;
    frame.height = timing_.visibleLines;
    frame.pixels.assign(static_cast<std::size_t>(frame.width) * frame.height, 0);
    frame.syncLocked = true;
    frame.detectedSyncCount = syncStarts.size();
    frame.selectedStandard = videoStandardName(timing_.standard);
    frame.lineRateHz = timing_.lineRateHz;
    frame.samplesPerLine = samplesPerLine(sampleRateHz);
    frame.totalLines = timing_.totalLines;
    frame.visibleLines = timing_.visibleLines;

    const auto lineLengthSamples = samplesPerLine(sampleRateHz);
    const auto activeOffset = activeStartOffset(sampleRateHz);
    const auto samplesPerActiveLine = activeSamples(sampleRateHz, lineLengthSamples);
    if (shouldBobInterlaced()) {
        const auto startSyncIndex = chooseInterlacedStartSyncIndex(
                video,
                syncStarts,
                frameSyncEdges,
                activeOffset,
                samplesPerActiveLine);
        for (std::uint32_t outputLine = 0; outputLine < frame.height; ++outputLine) {
            const auto fieldLine = outputLine / 2U;
            const auto syncIndex = startSyncIndex + static_cast<std::size_t>(fieldLine);
            if (syncIndex >= syncStarts.size()) {
                break;
            }

            const auto lineStart = syncStarts[syncIndex] + activeOffset;
            copyResampledLine(video, lineStart, samplesPerActiveLine, frame, outputLine);
        }
    } else {
        std::uint32_t outputLine = 0;
        for (const auto syncStart : syncStarts) {
            if (outputLine >= frame.height) {
                break;
            }

            const auto lineStart = syncStart + activeOffset;
            copyResampledLine(video, lineStart, samplesPerActiveLine, frame, outputLine);
            ++outputLine;
        }
    }

    frame.message = shouldBobInterlaced()
            ? "assembled from detected horizontal sync; interlaced bob field preview"
            : "assembled from detected horizontal sync";
    return frame;
}

VideoFrame FrameAssembler::assembleFieldPreviewFromSync(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::size_t startSyncIndex,
        std::uint64_t sampleRateHz) const {
    VideoFrame frame;
    frame.width = timing_.frameWidth;
    frame.height = timing_.visibleLines;
    frame.pixels.assign(static_cast<std::size_t>(frame.width) * frame.height, 0);
    frame.syncLocked = !syncStarts.empty();
    frame.detectedSyncCount = syncStarts.size();
    frame.selectedStandard = videoStandardName(timing_.standard);
    frame.lineRateHz = timing_.lineRateHz;
    frame.samplesPerLine = samplesPerLine(sampleRateHz);
    frame.totalLines = timing_.totalLines;
    frame.visibleLines = timing_.visibleLines;

    const auto lineLengthSamples = samplesPerLine(sampleRateHz);
    const auto activeOffset = activeStartOffset(sampleRateHz);
    const auto samplesPerActiveLine = activeSamples(sampleRateHz, lineLengthSamples);
    const auto activeEnd = activeOffset + samplesPerActiveLine;
    const auto referenceFieldStarts = shouldBobInterlaced()
            ? chooseReferenceBobFieldStarts(
                    video,
                    syncStarts,
                    frameSyncEdges,
                    sampleRateHz,
                    activeEnd)
            : std::vector<std::size_t>{};
    const auto fieldSpanSyncIndices = referenceFieldStarts.empty() && shouldBobInterlaced()
            ? chooseFieldSpanSyncIndices(
                    video,
                    syncStarts,
                    frameSyncEdges,
                    startSyncIndex,
                    sampleRateHz,
                    activeOffset,
                    samplesPerActiveLine)
            : std::vector<std::size_t>{};
    for (std::uint32_t outputLine = 0; outputLine < frame.height; ++outputLine) {
        const auto sourceLine = shouldBobInterlaced() ? (outputLine / 2U) : outputLine;
        if (!referenceFieldStarts.empty()) {
            const auto sourceIndex = std::min<std::size_t>(
                    static_cast<std::size_t>(sourceLine),
                    referenceFieldStarts.size() - 1U);
            const auto nextIndex = std::min<std::size_t>(sourceIndex + 1U, referenceFieldStarts.size() - 1U);
            const auto lineStart = referenceFieldStarts[sourceIndex] + activeOffset;
            if ((outputLine & 1U) == 0U || nextIndex == sourceIndex) {
                copyResampledLine(video, lineStart, samplesPerActiveLine, frame, outputLine);
            } else {
                const auto nextLineStart = referenceFieldStarts[nextIndex] + activeOffset;
                auto* output = frame.pixels.data() + (static_cast<std::size_t>(outputLine) * frame.width);
                const auto availableA = lineStart < video.size()
                        ? std::min(samplesPerActiveLine, video.size() - lineStart)
                        : 0U;
                const auto availableB = nextLineStart < video.size()
                        ? std::min(samplesPerActiveLine, video.size() - nextLineStart)
                        : 0U;
                if (availableA == 0U || availableB == 0U) {
                    continue;
                }
                if (frame.width == 1U || availableA == 1U || availableB == 1U) {
                    const auto value = static_cast<std::uint8_t>(
                            (static_cast<unsigned>(video[lineStart]) +
                             static_cast<unsigned>(video[nextLineStart])) /
                            2U);
                    std::fill(output, output + frame.width, value);
                    continue;
                }
                const double scaleA = static_cast<double>(availableA - 1U) /
                        static_cast<double>(frame.width - 1U);
                const double scaleB = static_cast<double>(availableB - 1U) /
                        static_cast<double>(frame.width - 1U);
                for (std::uint32_t x = 0; x < frame.width; ++x) {
                    const double sourcePositionA = static_cast<double>(x) * scaleA;
                    const auto leftA = static_cast<std::size_t>(sourcePositionA);
                    const auto rightA = std::min<std::size_t>(leftA + 1U, availableA - 1U);
                    const double fractionA = sourcePositionA - static_cast<double>(leftA);
                    const double valueA =
                            (static_cast<double>(video[lineStart + leftA]) * (1.0 - fractionA)) +
                            (static_cast<double>(video[lineStart + rightA]) * fractionA);

                    const double sourcePositionB = static_cast<double>(x) * scaleB;
                    const auto leftB = static_cast<std::size_t>(sourcePositionB);
                    const auto rightB = std::min<std::size_t>(leftB + 1U, availableB - 1U);
                    const double fractionB = sourcePositionB - static_cast<double>(leftB);
                    const double valueB =
                            (static_cast<double>(video[nextLineStart + leftB]) * (1.0 - fractionB)) +
                            (static_cast<double>(video[nextLineStart + rightB]) * fractionB);

                    output[x] = static_cast<std::uint8_t>(
                            std::clamp((valueA + valueB) * 0.5, 0.0, 255.0) + 0.5);
                }
            }
            continue;
        }

        const auto syncIndex = !fieldSpanSyncIndices.empty()
                ? fieldSpanSyncIndices[std::min<std::size_t>(
                        static_cast<std::size_t>(sourceLine),
                        fieldSpanSyncIndices.size() - 1U)]
                : startSyncIndex + static_cast<std::size_t>(sourceLine);
        if (syncIndex >= syncStarts.size()) {
            break;
        }

        const auto lineStart = syncStarts[syncIndex] + activeOffset;
        copyResampledLine(video, lineStart, samplesPerActiveLine, frame, outputLine);
    }

    frame.message = shouldBobInterlaced()
            ? (!referenceFieldStarts.empty()
                    ? "fast playback field preview from reference frame-sync spans; interlaced bob"
                    : !fieldSpanSyncIndices.empty()
                    ? "fast playback field preview from bounded frame-sync span; interlaced bob"
                    : "fast playback field preview from horizontal sync; interlaced bob")
            : "fast playback frame preview from horizontal sync";
    return frame;
}

std::size_t FrameAssembler::chooseFieldPreviewStartSyncIndex(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::uint64_t sampleRateHz) const {
    if (!shouldBobInterlaced()) {
        return 0;
    }

    const auto lineLengthSamples = samplesPerLine(sampleRateHz);
    const auto activeOffset = activeStartOffset(sampleRateHz);
    const auto samplesPerActiveLine = activeSamples(sampleRateHz, lineLengthSamples);
    const auto frameSyncStart = chooseInterlacedStartFromFrameSync(
            video,
            syncStarts,
            frameSyncEdges,
            activeOffset,
            samplesPerActiveLine);
    if (frameSyncStart != static_cast<std::size_t>(-1)) {
        return frameSyncStart;
    }

    const auto verticalBlankingStart = chooseInterlacedStartFromVerticalBlanking(
            video,
            syncStarts,
            activeOffset,
            samplesPerActiveLine);
    if (verticalBlankingStart != static_cast<std::size_t>(-1)) {
        return verticalBlankingStart;
    }

    return chooseInterlacedStartSyncIndex(
            video,
            syncStarts,
            frameSyncEdges,
            activeOffset,
            samplesPerActiveLine);
}

VideoFrame FrameAssembler::assembleRawRaster(
        const std::vector<std::uint8_t>& video,
        std::uint64_t sampleRateHz,
        std::size_t detectedSyncCount) const {
    VideoFrame frame;
    frame.width = timing_.frameWidth;
    frame.height = timing_.visibleLines;
    frame.pixels.assign(static_cast<std::size_t>(frame.width) * frame.height, 0);
    frame.syncLocked = false;
    frame.detectedSyncCount = detectedSyncCount;
    frame.selectedStandard = videoStandardName(timing_.standard);
    frame.lineRateHz = timing_.lineRateHz;
    frame.samplesPerLine = samplesPerLine(sampleRateHz);
    frame.totalLines = timing_.totalLines;
    frame.visibleLines = timing_.visibleLines;

    const auto lineLengthSamples = samplesPerLine(sampleRateHz);
    for (std::uint32_t outputLine = 0; outputLine < frame.height; ++outputLine) {
        const auto vbiSkip = timing_.totalLines > 560 ? 18U : 12U;
        const auto sourceLine = shouldBobInterlaced()
                ? (vbiSkip + (outputLine / 2U))
                : (vbiSkip + outputLine);
        const auto lineStart = static_cast<std::size_t>(sourceLine) * lineLengthSamples;
        copyResampledLine(video, lineStart, lineLengthSamples, frame, outputLine);
    }

    frame.message = shouldBobInterlaced()
            ? "raw standard-timed raster fallback; interlaced bob field preview"
            : "raw standard-timed raster fallback";
    return frame;
}

std::size_t FrameAssembler::samplesPerLine(std::uint64_t sampleRateHz) const {
    return static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(sampleRateHz) /
                                     timing_.lineRateHz)));
}

std::size_t FrameAssembler::activeStartOffset(std::uint64_t sampleRateHz) const {
    return static_cast<std::size_t>(
            std::max(0.0, std::round(static_cast<double>(samplesPerLine(sampleRateHz)) * 0.18)));
}

std::size_t FrameAssembler::activeSamples(
        std::uint64_t sampleRateHz,
        std::size_t samplesPerLine) const {
    const auto start = activeStartOffset(sampleRateHz);
    const auto end = static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(samplesPerLine) * 0.92)));
    if (end <= start) {
        return 1U;
    }
    return std::max<std::size_t>(1, std::min(end - start, samplesPerLine - std::min(start, samplesPerLine)));
}

std::size_t FrameAssembler::chooseInterlacedStartSyncIndex(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::size_t activeOffset,
        std::size_t samplesPerActiveLine) const {
    const auto sourceLineCount = interlacedSourceLineCount();
    if (!shouldBobInterlaced() || sourceLineCount == 0 || syncStarts.empty()) {
        return 0;
    }

    const auto maxUsableStart = maxUsableStartSyncIndexForActiveLines(
            video,
            syncStarts,
            activeOffset,
            samplesPerActiveLine,
            sourceLineCount);
    if (maxUsableStart == static_cast<std::size_t>(-1)) {
        return 0;
    }

    constexpr auto kNoFrameSyncStart = static_cast<std::size_t>(-1);
    const auto frameSyncStart = chooseInterlacedStartFromFrameSync(
            video,
            syncStarts,
            frameSyncEdges,
            activeOffset,
            samplesPerActiveLine);
    if (frameSyncStart != kNoFrameSyncStart) {
        return frameSyncStart;
    }

    std::size_t bestStart = 0;
    double bestScore = -1.0;
    std::vector<double> activities;
    activities.reserve(sourceLineCount);
    for (std::size_t candidate = 0; candidate <= maxUsableStart; ++candidate) {
        activities.clear();
        double activitySum = 0.0;
        for (std::size_t line = 0; line < sourceLineCount; ++line) {
            const auto syncIndex = candidate + line;
            const auto lineStart = syncStarts[syncIndex] + activeOffset;
            const auto activity = lineActivity(video, lineStart, samplesPerActiveLine);
            activitySum += activity;
            activities.push_back(activity);
        }

        if (activities.empty()) {
            continue;
        }

        auto sortedActivities = activities;
        std::sort(sortedActivities.begin(), sortedActivities.end());
        const auto lowActivityIndex = static_cast<std::size_t>(
                std::min<double>(
                        static_cast<double>(sortedActivities.size() - 1U),
                        std::floor(static_cast<double>(sortedActivities.size() - 1U) * 0.20)));
        const auto medianActivityIndex = sortedActivities.size() / 2U;
        const double lowActivity = sortedActivities[lowActivityIndex];
        const double medianActivity = sortedActivities[medianActivityIndex];
        const double meanActivity = activitySum / static_cast<double>(activities.size());

        // A field candidate that crosses VBI has good average activity but a weak low-percentile score.
        const double score = (lowActivity * 2.0) + medianActivity + (meanActivity * 0.25);
        if (score > bestScore) {
            bestScore = score;
            bestStart = candidate;
        }
    }

    return bestStart;
}

std::size_t FrameAssembler::chooseInterlacedStartFromFrameSync(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::size_t activeOffset,
        std::size_t samplesPerActiveLine) const {
    if (syncStarts.empty() || frameSyncEdges.empty()) {
        return static_cast<std::size_t>(-1);
    }

    const auto sourceLineCount = interlacedSourceLineCount();
    const auto maxUsableStart = maxUsableStartSyncIndexForActiveLines(
            video,
            syncStarts,
            activeOffset,
            samplesPerActiveLine,
            sourceLineCount);
    if (maxUsableStart == static_cast<std::size_t>(-1)) {
        return static_cast<std::size_t>(-1);
    }
    auto bestStart = static_cast<std::size_t>(-1);
    double bestScore = -1.0;
    for (const auto frameEdge : frameSyncEdges) {
        const auto targetSync = std::lower_bound(syncStarts.begin(), syncStarts.end(), frameEdge);
        if (targetSync == syncStarts.end()) {
            continue;
        }

        const auto edgeSyncIndex = static_cast<std::size_t>(std::distance(syncStarts.begin(), targetSync));
        const auto candidate = chooseActiveStartNearFieldSync(
                video,
                syncStarts,
                edgeSyncIndex,
                activeOffset,
                samplesPerActiveLine,
                maxUsableStart);
        if (candidate == static_cast<std::size_t>(-1)) {
            continue;
        }

        const double score = activeWindowScore(
                video,
                syncStarts,
                candidate,
                activeOffset,
                samplesPerActiveLine,
                candidate);
        if (score > bestScore) {
            bestScore = score;
            bestStart = candidate;
        }
    }

    return bestStart;
}

std::size_t FrameAssembler::chooseInterlacedStartFromVerticalBlanking(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        std::size_t activeOffset,
        std::size_t samplesPerActiveLine) const {
    const auto sourceLineCount = interlacedSourceLineCount();
    if (!shouldBobInterlaced() || syncStarts.size() <= sourceLineCount + 12U) {
        return static_cast<std::size_t>(-1);
    }

    constexpr std::size_t kWindowLines = 8;
    constexpr std::size_t kPostBlankingGuardLines = 4;
    const auto maxUsableStart = maxUsableStartSyncIndexForActiveLines(
            video,
            syncStarts,
            activeOffset,
            samplesPerActiveLine,
            sourceLineCount);
    if (maxUsableStart == static_cast<std::size_t>(-1) ||
        maxUsableStart <= kWindowLines + kPostBlankingGuardLines) {
        return static_cast<std::size_t>(-1);
    }

    std::size_t bestWindowStart = 0;
    double bestScore = -1.0;
    const auto maxWindowStart = maxUsableStart - kWindowLines - kPostBlankingGuardLines;
    for (std::size_t candidate = 0; candidate <= maxWindowStart; ++candidate) {
        double meanSum = 0.0;
        double activitySum = 0.0;
        for (std::size_t line = 0; line < kWindowLines; ++line) {
            const auto lineStart = syncStarts[candidate + line] + activeOffset;
            meanSum += lineMean(video, lineStart, samplesPerActiveLine);
            activitySum += lineActivity(video, lineStart, samplesPerActiveLine);
        }

        const double mean = meanSum / static_cast<double>(kWindowLines);
        const double activity = activitySum / static_cast<double>(kWindowLines);
        const double darknessScore = 255.0 - mean;
        const double flatnessScore = 80.0 / (1.0 + (activity / 256.0));
        const double score = darknessScore + flatnessScore;
        if (score > bestScore) {
            bestScore = score;
            bestWindowStart = candidate;
        }
    }

    return bestWindowStart + kWindowLines + kPostBlankingGuardLines;
}

std::vector<std::size_t> FrameAssembler::chooseFieldSpanSyncIndices(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::size_t preferredStartSyncIndex,
        std::uint64_t sampleRateHz,
        std::size_t activeOffset,
        std::size_t samplesPerActiveLine) const {
    std::vector<std::size_t> bestIndices;
    if (!shouldBobInterlaced() || syncStarts.empty() || frameSyncEdges.size() < 2U) {
        return bestIndices;
    }

    const auto lineLengthSamples = samplesPerLine(sampleRateHz);
    const auto sourceLineCount = interlacedSourceLineCount();
    const auto fieldLineCount = static_cast<std::size_t>(
            std::max(1.0, std::round(timing_.lineRateHz / (timing_.frameRateHz * 2.0))));
    const auto minimumUsefulLines = std::max<std::size_t>(
            40U,
            static_cast<std::size_t>(std::round(static_cast<double>(sourceLineCount) * 0.85)));

    auto bestPhaseDistance = static_cast<std::size_t>(-1);
    for (std::size_t span = 0; span + 1U < frameSyncEdges.size(); ++span) {
        const auto spanStart = frameSyncEdges[span];
        const auto spanEnd = frameSyncEdges[span + 1U];
        if (spanEnd <= spanStart + lineLengthSamples) {
            continue;
        }

        const auto spanLines = static_cast<double>(spanEnd - spanStart) /
                static_cast<double>(lineLengthSamples);
        if (spanLines < static_cast<double>(fieldLineCount) * 0.70 ||
            spanLines > static_cast<double>(fieldLineCount) * 1.30) {
            continue;
        }

        const auto edgeSync = std::lower_bound(syncStarts.begin(), syncStarts.end(), spanStart);
        if (edgeSync == syncStarts.end()) {
            continue;
        }
        const auto edgeSyncIndex = static_cast<std::size_t>(std::distance(syncStarts.begin(), edgeSync));
        const auto maxUsableStart = maxUsableStartSyncIndexForActiveLines(
                video,
                syncStarts,
                activeOffset,
                samplesPerActiveLine,
                sourceLineCount);
        if (maxUsableStart == static_cast<std::size_t>(-1)) {
            continue;
        }
        const auto activeStartSyncIndex = chooseActiveStartNearFieldSync(
                video,
                syncStarts,
                edgeSyncIndex,
                activeOffset,
                samplesPerActiveLine,
                maxUsableStart);
        if (activeStartSyncIndex == static_cast<std::size_t>(-1) ||
            activeStartSyncIndex >= syncStarts.size() ||
            syncStarts[activeStartSyncIndex] >= spanEnd) {
            continue;
        }
        auto firstSync = syncStarts.begin() + static_cast<std::ptrdiff_t>(activeStartSyncIndex);

        std::vector<std::size_t> indices;
        indices.reserve(sourceLineCount);
        for (auto it = firstSync; it != syncStarts.end(); ++it) {
            const auto syncStart = *it;
            if (syncStart >= spanEnd) {
                break;
            }
            if (syncStart + activeOffset + samplesPerActiveLine >= spanEnd) {
                break;
            }

            indices.push_back(static_cast<std::size_t>(std::distance(syncStarts.begin(), it)));
            if (indices.size() >= sourceLineCount) {
                break;
            }
        }

        if (indices.size() < minimumUsefulLines) {
            continue;
        }

        auto phaseDistance = static_cast<std::size_t>(std::abs(
                static_cast<long long>(indices.front()) -
                static_cast<long long>(preferredStartSyncIndex)));
        if (fieldLineCount > 1U) {
            const auto shiftedForward = indices.front() + fieldLineCount;
            phaseDistance = std::min<std::size_t>(
                    phaseDistance,
                    static_cast<std::size_t>(std::abs(
                            static_cast<long long>(shiftedForward) -
                            static_cast<long long>(preferredStartSyncIndex))));
            if (indices.front() >= fieldLineCount) {
                const auto shiftedBackward = indices.front() - fieldLineCount;
                phaseDistance = std::min<std::size_t>(
                        phaseDistance,
                        static_cast<std::size_t>(std::abs(
                                static_cast<long long>(shiftedBackward) -
                                static_cast<long long>(preferredStartSyncIndex))));
            }
        }

        const bool betterPhase = phaseDistance < bestPhaseDistance;
        const bool closePhaseAndMoreLines =
                phaseDistance <= bestPhaseDistance + 2U && indices.size() > bestIndices.size();
        if (bestIndices.empty() || betterPhase || closePhaseAndMoreLines) {
            bestPhaseDistance = phaseDistance;
            bestIndices = std::move(indices);
        }
    }

    return bestIndices;
}

std::vector<std::size_t> FrameAssembler::chooseReferenceBobFieldStarts(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::uint64_t sampleRateHz,
        std::size_t activeEnd) const {
    std::vector<std::size_t> empty;
    if (!shouldBobInterlaced() || frameSyncEdges.size() < 2U || syncStarts.empty()) {
        return empty;
    }

    const auto lineLengthSamples = samplesPerLine(sampleRateHz);
    const auto fieldLineCount = static_cast<double>(lineLengthSamples) > 0.0
            ? timing_.lineRateHz / (timing_.frameRateHz * 2.0)
            : 0.0;
    if (fieldLineCount <= 0.0) {
        return empty;
    }

    const auto fieldVbiSkip = static_cast<std::size_t>(timing_.totalLines > 560 ? 18U : 10U);
    const auto latestStart = frameSyncEdges[frameSyncEdges.size() - 2U];
    const auto latestEnd = frameSyncEdges[frameSyncEdges.size() - 1U];
    if (latestEnd <= latestStart || latestEnd > video.size()) {
        return empty;
    }
    const auto latestSpanLines =
            static_cast<double>(latestEnd - latestStart) / static_cast<double>(lineLengthSamples);
    if (latestSpanLines < fieldLineCount * 0.70 || latestSpanLines > fieldLineCount * 1.30) {
        return empty;
    }

    double latestQuality = 0.0;
    auto latestStarts = lineStartsInSpan(
            video,
            syncStarts,
            static_cast<double>(lineLengthSamples),
            activeEnd,
            latestStart,
            latestEnd,
            fieldVbiSkip,
            latestQuality);
    if (latestStarts.size() < 40U) {
        return empty;
    }

    if (frameSyncEdges.size() >= 4U) {
        const auto previousStart = frameSyncEdges[frameSyncEdges.size() - 3U];
        const auto previousEnd = frameSyncEdges[frameSyncEdges.size() - 2U];
        const auto previousSpanLines =
                previousEnd > previousStart
                        ? static_cast<double>(previousEnd - previousStart) /
                                  static_cast<double>(lineLengthSamples)
                        : 0.0;
        if (previousSpanLines >= fieldLineCount * 0.70 &&
            previousSpanLines <= fieldLineCount * 1.30) {
            double previousQuality = 0.0;
            auto previousStarts = lineStartsInSpan(
                    video,
                    syncStarts,
                    static_cast<double>(lineLengthSamples),
                    activeEnd,
                    previousStart,
                    previousEnd,
                    fieldVbiSkip,
                    previousQuality);
            if (previousStarts.size() >= 40U &&
                !(latestQuality >= 0.75 || latestQuality >= previousQuality * 0.9)) {
                latestStarts = std::move(previousStarts);
            }
        }
    }

    const auto wantedRows = static_cast<std::size_t>(timing_.visibleLines / 2U);
    if (latestStarts.size() > wantedRows) {
        latestStarts.resize(wantedRows);
    }
    return latestStarts;
}

std::vector<std::size_t> FrameAssembler::lineStartsInSpan(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        double samplesPerLine,
        std::size_t activeEnd,
        std::size_t spanStart,
        std::size_t spanEnd,
        std::size_t vbiLines,
        double& quality) const {
    std::vector<std::size_t> starts;
    quality = 0.0;
    if (samplesPerLine <= 0.0 || spanEnd <= spanStart) {
        return starts;
    }

    const auto samplesPerLineRounded = static_cast<std::size_t>(
            std::max(1.0, std::round(samplesPerLine)));
    const auto margin = samplesPerLineRounded / 2U;
    std::vector<std::size_t> local;
    local.reserve(syncStarts.size());
    for (const auto edge : syncStarts) {
        if (edge + margin >= spanStart && edge < spanEnd) {
            local.push_back(edge);
        }
    }
    if (local.size() < 20U) {
        return starts;
    }

    const auto firstMin = spanStart + static_cast<std::size_t>(
            std::max(0.0, std::round(static_cast<double>(vbiLines) * samplesPerLine)));
    auto firstIt = std::lower_bound(local.begin(), local.end(), firstMin);
    double first = static_cast<double>(firstIt != local.end() ? *firstIt : local.front());
    const auto remaining = static_cast<double>(spanEnd) - first - static_cast<double>(activeEnd);
    const auto maxLines = static_cast<int>(remaining / samplesPerLine);
    if (maxLines <= 10) {
        return starts;
    }

    const auto search = static_cast<std::size_t>(
            std::max(2.0, 0.16 * static_cast<double>(samplesPerLineRounded)));
    const auto maxCorrection = std::max(1.0, 0.035 * samplesPerLine);
    double phase = first;
    std::size_t usedReal = 0;
    for (int line = 0; line < maxLines; ++line) {
        const auto found = findBestSyncNearPrediction(local, phase, search);
        double q = phase;
        if (found != static_cast<std::size_t>(-1)) {
            const double residual = static_cast<double>(found) - phase;
            q = phase + std::clamp(0.18 * residual, -maxCorrection, maxCorrection);
            ++usedReal;
        }

        if (q >= 0.0) {
            starts.push_back(static_cast<std::size_t>(std::round(q)));
        }
        phase = q + samplesPerLine;
    }

    if (starts.size() > 1U) {
        std::size_t kept = 1U;
        for (std::size_t i = 1U; i < starts.size(); ++i) {
            if (starts[i] > starts[kept - 1U] &&
                starts[i] - starts[kept - 1U] > static_cast<std::size_t>(0.45 * samplesPerLine)) {
                starts[kept++] = starts[i];
            }
        }
        starts.resize(kept);
    }

    starts.erase(
            std::remove_if(
                    starts.begin(),
                    starts.end(),
                    [&](std::size_t start) {
                        return start + activeEnd >= video.size();
                    }),
            starts.end());
    quality = starts.empty() ? 0.0 : static_cast<double>(usedReal) / static_cast<double>(starts.size());
    return starts;
}

std::size_t FrameAssembler::findBestSyncNearPrediction(
        const std::vector<std::size_t>& syncStarts,
        double prediction,
        std::size_t search) const {
    auto best = static_cast<std::size_t>(-1);
    auto bestDelta = std::numeric_limits<double>::max();
    const auto lower = prediction > static_cast<double>(search)
            ? static_cast<std::size_t>(prediction - static_cast<double>(search))
            : 0U;
    const auto upper = static_cast<std::size_t>(prediction + static_cast<double>(search));
    auto it = std::lower_bound(syncStarts.begin(), syncStarts.end(), lower);
    for (; it != syncStarts.end() && *it <= upper; ++it) {
        const double delta = std::fabs(static_cast<double>(*it) - prediction);
        if (delta < bestDelta) {
            bestDelta = delta;
            best = *it;
        }
    }
    return best;
}

std::size_t FrameAssembler::chooseActiveStartNearFieldSync(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        std::size_t edgeSyncIndex,
        std::size_t activeOffset,
        std::size_t samplesPerActiveLine,
        std::size_t maxUsableStart) const {
    if (!shouldBobInterlaced() || syncStarts.empty() || edgeSyncIndex >= syncStarts.size()) {
        return static_cast<std::size_t>(-1);
    }

    const auto nominalSkip = static_cast<std::size_t>(timing_.totalLines > 560 ? 22U : 19U);
    const auto minSkip = static_cast<std::size_t>(timing_.totalLines > 560 ? 14U : 8U);
    const auto maxSkip = static_cast<std::size_t>(timing_.totalLines > 560 ? 36U : 32U);
    const auto searchStart = std::min(edgeSyncIndex + minSkip, maxUsableStart);
    const auto searchEnd = std::min(edgeSyncIndex + maxSkip, maxUsableStart);
    if (searchStart > searchEnd) {
        return static_cast<std::size_t>(-1);
    }

    const auto nominalStart = std::min(edgeSyncIndex + nominalSkip, maxUsableStart);
    std::size_t bestStart = static_cast<std::size_t>(-1);
    double bestScore = -1.0;
    for (std::size_t candidate = searchStart; candidate <= searchEnd; ++candidate) {
        const double score = activeWindowScore(
                video,
                syncStarts,
                candidate,
                activeOffset,
                samplesPerActiveLine,
                nominalStart);
        if (score > bestScore) {
            bestScore = score;
            bestStart = candidate;
        }
    }

    return bestStart;
}

double FrameAssembler::activeWindowScore(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        std::size_t candidateSyncIndex,
        std::size_t activeOffset,
        std::size_t samplesPerActiveLine,
        std::size_t nominalSyncIndex) const {
    if (candidateSyncIndex >= syncStarts.size()) {
        return -1.0;
    }

    constexpr std::size_t kProbeLines = 12;
    const auto availableLines = std::min(kProbeLines, syncStarts.size() - candidateSyncIndex);
    if (availableLines < 4U) {
        return -1.0;
    }

    std::vector<double> activities;
    activities.reserve(availableLines);
    double activitySum = 0.0;
    double meanSum = 0.0;
    for (std::size_t line = 0; line < availableLines; ++line) {
        const auto lineStart = syncStarts[candidateSyncIndex + line] + activeOffset;
        const double activity = lineActivity(video, lineStart, samplesPerActiveLine);
        activities.push_back(activity);
        activitySum += activity;
        meanSum += lineMean(video, lineStart, samplesPerActiveLine);
    }

    auto sortedActivities = activities;
    std::sort(sortedActivities.begin(), sortedActivities.end());
    const auto lowActivityIndex = static_cast<std::size_t>(
            std::floor(static_cast<double>(sortedActivities.size() - 1U) * 0.25));
    const auto medianActivityIndex = sortedActivities.size() / 2U;
    const double lowActivity = sortedActivities[lowActivityIndex];
    const double medianActivity = sortedActivities[medianActivityIndex];
    const double meanActivity = activitySum / static_cast<double>(availableLines);
    const double meanLevel = meanSum / static_cast<double>(availableLines);
    const double visibleLevelScore = std::min(meanLevel, 255.0 - meanLevel);
    const double nominalPenalty = std::abs(
            static_cast<double>(candidateSyncIndex) - static_cast<double>(nominalSyncIndex)) * 0.5;

    return (lowActivity * 2.0) +
           medianActivity +
           (meanActivity * 0.25) +
           (visibleLevelScore * 0.10) -
           nominalPenalty;
}

std::size_t FrameAssembler::interlacedSourceLineCount() const {
    return static_cast<std::size_t>((timing_.visibleLines + 1U) / 2U);
}

double FrameAssembler::lineMean(
        const std::vector<std::uint8_t>& video,
        std::size_t sourceStart,
        std::size_t sourceLength) const {
    if (sourceStart >= video.size() || sourceLength == 0) {
        return 255.0;
    }

    const auto available = std::min(sourceLength, video.size() - sourceStart);
    constexpr std::size_t kMeanSamples = 64;
    const auto sampleCount = std::min(kMeanSamples, available);
    if (sampleCount == 0) {
        return 255.0;
    }

    double sum = 0.0;
    for (std::size_t index = 0; index < sampleCount; ++index) {
        const auto sourceOffset =
                (static_cast<std::uint64_t>(index) * static_cast<std::uint64_t>(available)) /
                static_cast<std::uint64_t>(sampleCount);
        sum += static_cast<double>(video[sourceStart + static_cast<std::size_t>(sourceOffset)]);
    }

    return sum / static_cast<double>(sampleCount);
}

double FrameAssembler::lineActivity(
        const std::vector<std::uint8_t>& video,
        std::size_t sourceStart,
        std::size_t sourceLength) const {
    if (sourceStart >= video.size() || sourceLength == 0) {
        return 0.0;
    }

    const auto available = std::min(sourceLength, video.size() - sourceStart);
    constexpr std::size_t kActivitySamples = 64;
    const auto sampleCount = std::min(kActivitySamples, available);
    if (sampleCount < 2) {
        return 0.0;
    }

    double sum = 0.0;
    double sumSquares = 0.0;
    for (std::size_t index = 0; index < sampleCount; ++index) {
        const auto sourceOffset =
                (static_cast<std::uint64_t>(index) * static_cast<std::uint64_t>(available)) /
                static_cast<std::uint64_t>(sampleCount);
        const double value = static_cast<double>(video[sourceStart + static_cast<std::size_t>(sourceOffset)]);
        sum += value;
        sumSquares += value * value;
    }

    const double mean = sum / static_cast<double>(sampleCount);
    return (sumSquares / static_cast<double>(sampleCount)) - (mean * mean);
}

void FrameAssembler::copyResampledLine(
        const std::vector<std::uint8_t>& video,
        std::size_t sourceStart,
        std::size_t sourceLength,
        VideoFrame& frame,
        std::uint32_t outputLine) const {
    if (sourceStart >= video.size() || sourceLength == 0 || outputLine >= frame.height) {
        return;
    }

    const auto available = std::min(sourceLength, video.size() - sourceStart);
    auto* output = frame.pixels.data() + (static_cast<std::size_t>(outputLine) * frame.width);
    if (frame.width == 1U || available == 1U) {
        const auto value = video[sourceStart];
        std::fill(output, output + frame.width, value);
        return;
    }

    const double scale = static_cast<double>(available - 1U) /
            static_cast<double>(frame.width - 1U);
    for (std::uint32_t x = 0; x < frame.width; ++x) {
        const double sourcePosition = static_cast<double>(x) * scale;
        const auto left = static_cast<std::size_t>(sourcePosition);
        const auto right = std::min<std::size_t>(left + 1U, available - 1U);
        const double fraction = sourcePosition - static_cast<double>(left);
        const double value =
                (static_cast<double>(video[sourceStart + left]) * (1.0 - fraction)) +
                (static_cast<double>(video[sourceStart + right]) * fraction);
        output[x] = static_cast<std::uint8_t>(
                std::clamp(value, 0.0, 255.0) + 0.5);
    }
}

bool FrameAssembler::shouldBobInterlaced() const {
    return timing_.interlaced && timing_.visibleLines >= 2;
}

}  // namespace sdr
