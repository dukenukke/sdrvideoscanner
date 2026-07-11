#include "FrameAssembler.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <numeric>
#include <vector>

namespace sdr {
namespace {

constexpr std::size_t kMaxLockedFieldPhaseDistanceLines = 6U;

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
            timing.lineRateHz = timing.frameRateHz * static_cast<double>(timing.totalLines);
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
        auto referenceBobFieldStarts = chooseReferenceBobFieldStarts(
                video,
                syncStarts,
                frameSyncEdges,
                startSyncIndex,
                sampleRateHz,
                activeOffset + samplesPerActiveLine);
        if (referenceBobFieldStarts.size() < interlacedSourceLineCount()) {
            referenceBobFieldStarts.clear();
        }
        for (std::uint32_t outputLine = 0; outputLine < frame.height; ++outputLine) {
            const auto fieldLine = outputLine / 2U;
            const auto sourceLineIndex = static_cast<std::size_t>(fieldLine);
            if (!referenceBobFieldStarts.empty() &&
                sourceLineIndex < referenceBobFieldStarts.size()) {
                const auto lineStart = referenceBobFieldStarts[sourceLineIndex] + activeOffset;
                copyResampledLine(video, lineStart, samplesPerActiveLine, frame, outputLine);
                continue;
            }

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

    frame.assemblyPath = shouldBobInterlaced()
            ? "full_sync_bob"
            : "full_sync";
    frame.doubleImageScore = doubleImageScore(frame);
    frame.message = shouldBobInterlaced()
            ? "assembled from detected horizontal sync; frame-sync bounded interlaced bob field preview"
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
    const auto sourceLineCount = shouldBobInterlaced() ? interlacedSourceLineCount() : 0U;
    auto fieldSpanSyncIndices = shouldBobInterlaced()
            ? chooseFieldSpanSyncIndices(
                    video,
                    syncStarts,
                    frameSyncEdges,
                    startSyncIndex,
                    sampleRateHz,
                    activeOffset,
                    samplesPerActiveLine)
            : std::vector<std::size_t>{};
    if (fieldSpanSyncIndices.size() < sourceLineCount) {
        fieldSpanSyncIndices.clear();
    }
    auto referenceBobFieldStarts = shouldBobInterlaced()
            ? chooseReferenceBobFieldStarts(
                    video,
                    syncStarts,
                    frameSyncEdges,
                    startSyncIndex,
                    sampleRateHz,
                    activeOffset + samplesPerActiveLine)
            : std::vector<std::size_t>{};
    if (referenceBobFieldStarts.size() < sourceLineCount) {
        referenceBobFieldStarts.clear();
    }
    for (std::uint32_t outputLine = 0; outputLine < frame.height; ++outputLine) {
        const auto sourceLine = shouldBobInterlaced() ? (outputLine / 2U) : outputLine;
        const auto sourceLineIndex = static_cast<std::size_t>(sourceLine);
        if (!referenceBobFieldStarts.empty() &&
            sourceLineIndex < referenceBobFieldStarts.size()) {
            const auto lineStart = referenceBobFieldStarts[sourceLineIndex] + activeOffset;
            copyResampledLine(video, lineStart, samplesPerActiveLine, frame, outputLine);
            continue;
        }

        const auto syncIndex = !fieldSpanSyncIndices.empty() &&
                        sourceLineIndex < fieldSpanSyncIndices.size()
                ? fieldSpanSyncIndices[sourceLineIndex]
                : startSyncIndex + sourceLineIndex;
        if (syncIndex >= syncStarts.size()) {
            break;
        }
        const auto lineStart = syncStarts[syncIndex] + activeOffset;
        copyResampledLine(video, lineStart, samplesPerActiveLine, frame, outputLine);
    }

    frame.assemblyPath = shouldBobInterlaced()
            ? (!referenceBobFieldStarts.empty()
                    ? "pll"
                    : !fieldSpanSyncIndices.empty()
                    ? "span"
                    : "sequential")
            : "fast_preview";
    frame.doubleImageScore = doubleImageScore(frame);
    frame.message = shouldBobInterlaced()
            ? (!referenceBobFieldStarts.empty()
                    ? "field preview from frame-sync bounded line PLL; interlaced bob"
                    : !fieldSpanSyncIndices.empty()
                    ? "field preview from bounded frame-sync span; interlaced bob"
                    : "field preview from locked horizontal sync; interlaced bob")
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
    const auto activeWindowStart = chooseInterlacedStartFromActiveWindow(
            video,
            syncStarts,
            activeOffset,
            samplesPerActiveLine);
    if (activeWindowStart != static_cast<std::size_t>(-1)) {
        return activeWindowStart;
    }

    const auto verticalBlankingStart = chooseInterlacedStartFromVerticalBlanking(
            video,
            syncStarts,
            activeOffset,
            samplesPerActiveLine);
    if (verticalBlankingStart != static_cast<std::size_t>(-1)) {
        return verticalBlankingStart;
    }

    const auto frameSyncStart = chooseInterlacedStartFromFrameSync(
            video,
            syncStarts,
            frameSyncEdges,
            activeOffset,
            samplesPerActiveLine);
    if (frameSyncStart != static_cast<std::size_t>(-1)) {
        return frameSyncStart;
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

    frame.assemblyPath = "raw";
    frame.doubleImageScore = doubleImageScore(frame);
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

    const auto activeWindowStart = chooseInterlacedStartFromActiveWindow(
            video,
            syncStarts,
            activeOffset,
            samplesPerActiveLine);
    if (activeWindowStart != static_cast<std::size_t>(-1)) {
        return activeWindowStart;
    }

    return 0;
}

std::size_t FrameAssembler::chooseInterlacedStartFromActiveWindow(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        std::size_t activeOffset,
        std::size_t samplesPerActiveLine) const {
    const auto sourceLineCount = interlacedSourceLineCount();
    if (!shouldBobInterlaced() || sourceLineCount == 0 || syncStarts.empty()) {
        return static_cast<std::size_t>(-1);
    }

    const auto requiredSyncSpan = sourceLineCount - 1U;
    if (syncStarts.size() <= requiredSyncSpan) {
        return static_cast<std::size_t>(-1);
    }

    std::size_t bestStart = 0;
    double bestScore = -1.0;
    const auto maxStart = syncStarts.size() - requiredSyncSpan;
    std::vector<double> activities;
    activities.reserve(sourceLineCount);
    for (std::size_t candidate = 0; candidate < maxStart; ++candidate) {
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
    const auto maxUsableStart = syncStarts.size() > sourceLineCount
            ? syncStarts.size() - sourceLineCount
            : 0U;
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
    if (syncStarts.size() <= kWindowLines + kPostBlankingGuardLines + sourceLineCount) {
        return static_cast<std::size_t>(-1);
    }

    std::size_t bestWindowStart = 0;
    double bestScore = -1.0;
    const auto maxWindowStart = syncStarts.size() - kWindowLines - kPostBlankingGuardLines - sourceLineCount;
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
    if (!shouldBobInterlaced() || syncStarts.empty() || frameSyncEdges.empty()) {
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
    for (std::size_t span = 0; span < frameSyncEdges.size(); ++span) {
        const auto spanStart = frameSyncEdges[span];
        const auto spanEnd = span + 1U < frameSyncEdges.size()
                ? frameSyncEdges[span + 1U]
                : spanStart + static_cast<std::size_t>(
                        std::round(static_cast<double>(fieldLineCount) *
                                   static_cast<double>(lineLengthSamples)));
        if (spanEnd <= spanStart + lineLengthSamples) {
            continue;
        }
        if (spanEnd > video.size()) {
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
        const auto maxUsableStart = syncStarts.size() > sourceLineCount
                ? syncStarts.size() - sourceLineCount
                : 0U;
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
        if (phaseDistance > kMaxLockedFieldPhaseDistanceLines) {
            continue;
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
        std::size_t preferredStartSyncIndex,
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

    const auto roundedFieldLineCount = static_cast<std::size_t>(
            std::max(1.0, std::round(fieldLineCount)));
    const auto fieldVbiSkip = static_cast<std::size_t>(timing_.totalLines > 560 ? 18U : 10U);
    const auto wantedRows = static_cast<std::size_t>(timing_.visibleLines / 2U);
    const auto minimumRows = std::max<std::size_t>(
            40U,
            static_cast<std::size_t>(std::round(static_cast<double>(wantedRows) * 0.70)));
    const auto boundedPreferredStartSyncIndex = std::min(
            preferredStartSyncIndex,
            syncStarts.size() - 1U);

    std::vector<std::size_t> bestStarts;
    auto bestPhaseDistance = static_cast<std::size_t>(-1);
    double bestQuality = -1.0;
    for (std::size_t span = 0; span + 1U < frameSyncEdges.size(); ++span) {
        const auto spanStart = frameSyncEdges[span];
        const auto spanEnd = frameSyncEdges[span + 1U];
        if (spanEnd <= spanStart || spanEnd > video.size()) {
            continue;
        }

        const auto spanLines =
                static_cast<double>(spanEnd - spanStart) / static_cast<double>(lineLengthSamples);
        if (spanLines < fieldLineCount * 0.70 || spanLines > fieldLineCount * 1.30) {
            continue;
        }

        double quality = 0.0;
        auto starts = lineStartsInSpan(
                video,
                syncStarts,
                static_cast<double>(lineLengthSamples),
                activeEnd,
                spanStart,
                spanEnd,
                fieldVbiSkip,
                quality);
        if (starts.size() < minimumRows) {
            continue;
        }

        const auto firstSync = std::lower_bound(syncStarts.begin(), syncStarts.end(), starts.front());
        if (firstSync == syncStarts.end()) {
            continue;
        }

        const auto firstSyncIndex = static_cast<std::size_t>(
                std::distance(syncStarts.begin(), firstSync));
        auto phaseDistance = static_cast<std::size_t>(std::abs(
                static_cast<long long>(firstSyncIndex) -
                static_cast<long long>(boundedPreferredStartSyncIndex)));
        const auto shiftedForward = firstSyncIndex + roundedFieldLineCount;
        phaseDistance = std::min<std::size_t>(
                phaseDistance,
                static_cast<std::size_t>(std::abs(
                        static_cast<long long>(shiftedForward) -
                        static_cast<long long>(boundedPreferredStartSyncIndex))));
        if (firstSyncIndex >= roundedFieldLineCount) {
            const auto shiftedBackward = firstSyncIndex - roundedFieldLineCount;
            phaseDistance = std::min<std::size_t>(
                    phaseDistance,
                    static_cast<std::size_t>(std::abs(
                        static_cast<long long>(shiftedBackward) -
                        static_cast<long long>(boundedPreferredStartSyncIndex))));
        }
        if (phaseDistance > kMaxLockedFieldPhaseDistanceLines) {
            continue;
        }

        const bool betterPhase = phaseDistance < bestPhaseDistance;
        const bool samePhaseBetterQuality =
                phaseDistance == bestPhaseDistance && quality > bestQuality;
        const bool closePhaseMoreRows =
                phaseDistance <= bestPhaseDistance + 1U &&
                starts.size() > bestStarts.size() &&
                quality >= bestQuality * 0.85;
        if (bestStarts.empty() || betterPhase || samePhaseBetterQuality || closePhaseMoreRows) {
            bestPhaseDistance = phaseDistance;
            bestQuality = quality;
            bestStarts = std::move(starts);
        }
    }

    if (bestStarts.empty()) {
        return empty;
    }
    if (bestStarts.size() > wantedRows) {
        bestStarts.resize(wantedRows);
    }
    return bestStarts;
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

double FrameAssembler::doubleImageScore(const VideoFrame& frame) const {
    if (!frame.valid() || frame.height < 80U || frame.width < 16U) {
        return 0.0;
    }

    const auto height = static_cast<std::size_t>(frame.height);
    const auto width = static_cast<std::size_t>(frame.width);
    std::vector<double> rowMeans(height, 0.0);
    std::vector<double> rowActivities(height, 0.0);
    for (std::size_t y = 0; y < height; ++y) {
        const auto* row = frame.pixels.data() + (y * width);
        double sum = 0.0;
        double activity = 0.0;
        for (std::size_t x = 0; x < width; ++x) {
            sum += static_cast<double>(row[x]);
            if (x > 0U) {
                activity += std::fabs(static_cast<double>(row[x]) - static_cast<double>(row[x - 1U]));
            }
        }
        rowMeans[y] = sum / static_cast<double>(width);
        rowActivities[y] = activity / static_cast<double>(std::max<std::size_t>(1U, width - 1U));
    }

    const double mean = std::accumulate(rowMeans.begin(), rowMeans.end(), 0.0) /
            static_cast<double>(rowMeans.size());
    double variance = 0.0;
    for (const auto value : rowMeans) {
        const double centered = value - mean;
        variance += centered * centered;
    }
    variance = std::max(variance, 1.0);

    double bestCorrelation = 0.0;
    const auto minLag = static_cast<std::size_t>(std::round(static_cast<double>(height) * 0.40));
    const auto maxLag = static_cast<std::size_t>(std::round(static_cast<double>(height) * 0.60));
    for (std::size_t lag = minLag; lag <= maxLag && lag + 8U < height; ++lag) {
        double numerator = 0.0;
        double leftEnergy = 0.0;
        double rightEnergy = 0.0;
        for (std::size_t y = 0; y + lag < height; ++y) {
            const double left = rowMeans[y] - mean;
            const double right = rowMeans[y + lag] - mean;
            numerator += left * right;
            leftEnergy += left * left;
            rightEnergy += right * right;
        }
        const double denominator = std::sqrt(std::max(1.0, leftEnergy * rightEnergy));
        bestCorrelation = std::max(bestCorrelation, numerator / denominator);
    }

    constexpr std::size_t kBand = 8U;
    const auto mid = height / 2U;
    const auto beforeStart = mid > (kBand * 2U) ? mid - (kBand * 2U) : 0U;
    const auto beforeEnd = mid > kBand ? mid - kBand : mid;
    const auto afterStart = std::min(height, mid + kBand);
    const auto afterEnd = std::min(height, mid + (kBand * 2U));
    auto averageRange = [](const std::vector<double>& values, std::size_t start, std::size_t end) {
        if (start >= end || start >= values.size()) {
            return 0.0;
        }
        end = std::min(end, values.size());
        return std::accumulate(values.begin() + static_cast<std::ptrdiff_t>(start),
                               values.begin() + static_cast<std::ptrdiff_t>(end),
                               0.0) /
                static_cast<double>(end - start);
    };
    const double midMeanJump = std::fabs(
            averageRange(rowMeans, beforeStart, beforeEnd) -
            averageRange(rowMeans, afterStart, afterEnd));
    const double midActivityJump = std::fabs(
            averageRange(rowActivities, beforeStart, beforeEnd) -
            averageRange(rowActivities, afterStart, afterEnd));

    auto sortedMeans = rowMeans;
    std::sort(sortedMeans.begin(), sortedMeans.end());
    const double lowMean = sortedMeans[sortedMeans.size() / 5U];
    const double medianMean = sortedMeans[sortedMeans.size() / 2U];
    const double darkThreshold = lowMean + ((medianMean - lowMean) * 0.35);
    std::size_t darkToBrightTransitions = 0;
    std::size_t centralDarkRuns = 0;
    bool inDarkRun = false;
    std::size_t darkRunLength = 0;
    std::size_t runStart = 0;
    for (std::size_t y = 0; y < rowMeans.size(); ++y) {
        const auto value = rowMeans[y];
        const bool dark = value <= darkThreshold;
        if (dark) {
            if (!inDarkRun) {
                runStart = y;
            }
            inDarkRun = true;
            ++darkRunLength;
            continue;
        }
        if (inDarkRun && darkRunLength >= 3U) {
            ++darkToBrightTransitions;
            const auto runCenter = runStart + (darkRunLength / 2U);
            if (runCenter > height / 8U && runCenter < (height * 7U) / 8U) {
                ++centralDarkRuns;
            }
        }
        inDarkRun = false;
        darkRunLength = 0;
    }
    if (inDarkRun && darkRunLength >= 3U) {
        const auto runCenter = runStart + (darkRunLength / 2U);
        if (runCenter > height / 8U && runCenter < (height * 7U) / 8U) {
            ++centralDarkRuns;
        }
    }

    const double correlationScore = std::max(0.0, bestCorrelation - 0.45) * 150.0;
    const double discontinuityScore = std::clamp((midMeanJump - 12.0) * 1.8, 0.0, 40.0) +
            std::clamp((midActivityJump - 3.0) * 1.5, 0.0, 25.0);
    const double transitionScore = darkToBrightTransitions > 1U
            ? std::min(45.0, static_cast<double>(darkToBrightTransitions - 1U) * 24.0)
            : 0.0;
    const double centralBandScore = centralDarkRuns > 0U
            ? std::min(45.0, static_cast<double>(centralDarkRuns) * 24.0)
            : 0.0;
    return std::clamp(correlationScore + discontinuityScore + transitionScore + centralBandScore, 0.0, 100.0);
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
