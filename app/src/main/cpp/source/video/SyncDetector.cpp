#include "SyncDetector.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace sdr {
namespace {

std::size_t samplesForSeconds(std::uint64_t sampleRateHz, double seconds) {
    return static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(sampleRateHz) * seconds)));
}

std::uint8_t percentileInPlace(std::vector<std::uint8_t>& values, double fraction) {
    if (values.empty()) {
        return 0;
    }

    const auto index = static_cast<std::size_t>(
            std::clamp(fraction, 0.0, 1.0) * static_cast<double>(values.size() - 1));
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(index), values.end());
    return values[index];
}

void percentilePairInPlace(
        std::vector<float>& values,
        double fractionA,
        double fractionB,
        float& percentileA,
        float& percentileB) {
    if (values.empty()) {
        percentileA = 0.0F;
        percentileB = 0.0F;
        return;
    }

    auto indexA = static_cast<std::size_t>(
            std::clamp(fractionA, 0.0, 1.0) * static_cast<double>(values.size() - 1));
    auto indexB = static_cast<std::size_t>(
            std::clamp(fractionB, 0.0, 1.0) * static_cast<double>(values.size() - 1));
    if (indexB < indexA) {
        std::swap(indexA, indexB);
        std::swap(fractionA, fractionB);
    }

    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(indexA), values.end());
    percentileA = values[indexA];
    std::nth_element(
            values.begin() + static_cast<std::ptrdiff_t>(indexA),
            values.begin() + static_cast<std::ptrdiff_t>(indexB),
            values.end());
    percentileB = values[indexB];
}

std::vector<std::uint8_t> smoothVideo(
        const std::vector<std::uint8_t>& video,
        std::uint64_t sampleRateHz,
        double windowSeconds) {
    const auto windowSamples = static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(sampleRateHz) * windowSeconds)));
    if (video.empty() || windowSamples <= 1) {
        return video;
    }

    std::vector<std::uint8_t> smoothed(video.size());
    std::uint64_t sum = 0;
    std::size_t start = 0;
    for (std::size_t index = 0; index < video.size(); ++index) {
        sum += video[index];
        while ((index - start + 1) > windowSamples) {
            sum -= video[start];
            ++start;
        }

        smoothed[index] = static_cast<std::uint8_t>(
                sum / static_cast<std::uint64_t>(index - start + 1));
    }

    return smoothed;
}

std::vector<float> centeredFloatVideo(
        const std::vector<std::uint8_t>& video,
        std::vector<std::uint8_t>& scratch) {
    std::vector<float> centered(video.size());
    scratch = video;
    const auto median = static_cast<float>(percentileInPlace(scratch, 0.50));
    for (std::size_t index = 0; index < video.size(); ++index) {
        centered[index] = static_cast<float>(video[index]) - median;
    }
    return centered;
}

float percentileFloat(std::vector<float> values, double fraction) {
    if (values.empty()) {
        return 0.0F;
    }

    const auto index = static_cast<std::size_t>(
            std::clamp(fraction, 0.0, 1.0) * static_cast<double>(values.size() - 1));
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(index), values.end());
    return values[index];
}

std::vector<float> movingAverageFloat(
        const std::vector<float>& values,
        std::uint64_t sampleRateHz,
        double windowSeconds) {
    auto windowSamples = static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(sampleRateHz) * windowSeconds)));
    if ((windowSamples & 1U) == 0U) {
        ++windowSamples;
    }
    if (values.empty() || windowSamples <= 1) {
        return values;
    }

    std::vector<float> smoothed(values.size());
    const auto halfWindow = windowSamples / 2U;
    double sum = 0.0;
    std::size_t right = 0;
    for (; right <= halfWindow && right < values.size(); ++right) {
        sum += values[right];
    }

    for (std::size_t index = 0; index < values.size(); ++index) {
        if (index > halfWindow) {
            sum -= values[index - halfWindow - 1U];
        }
        while (right < values.size() && right <= index + halfWindow) {
            sum += values[right];
            ++right;
        }

        const auto left = index > halfWindow ? index - halfWindow : 0U;
        const auto count = right - left;
        smoothed[index] = static_cast<float>(sum / static_cast<double>(std::max<std::size_t>(1U, count)));
    }
    return smoothed;
}

struct SyncPulseRun {
    std::size_t start = 0;
    std::size_t end = 0;
};

struct FrameSyncCandidate {
    std::size_t sample = 0;
    double score = 0.0;
};

struct FrameSyncDetection {
    std::vector<std::size_t> edges;
    double quality = 0.0;
};

std::vector<std::uint8_t> normalizedSyncLowVideo(
        const std::vector<std::uint8_t>& smoothed,
        bool syncIsHigh) {
    std::vector<std::uint8_t> normalized(smoothed.size());
    for (std::size_t index = 0; index < smoothed.size(); ++index) {
        normalized[index] = syncIsHigh
                ? static_cast<std::uint8_t>(255U - smoothed[index])
                : smoothed[index];
    }
    return normalized;
}

std::vector<SyncPulseRun> detectPulseRuns(
        const std::vector<std::uint8_t>& smoothed,
        std::uint8_t threshold,
        bool highPulse,
        std::size_t minRunSamples) {
    std::vector<SyncPulseRun> runs;
    std::size_t index = 0;
    while (index < smoothed.size()) {
        const bool active = highPulse ? smoothed[index] >= threshold : smoothed[index] <= threshold;
        if (!active) {
            ++index;
            continue;
        }

        const auto runStart = index;
        while (index < smoothed.size()) {
            const bool stillActive = highPulse ? smoothed[index] >= threshold : smoothed[index] <= threshold;
            if (!stillActive) {
                break;
            }
            ++index;
        }
        if (index - runStart >= minRunSamples) {
            runs.push_back({runStart, index});
        }
    }
    return runs;
}

double sampleMean(
        const std::vector<std::uint8_t>& video,
        std::size_t start,
        std::size_t length) {
    if (start >= video.size() || length == 0) {
        return 255.0;
    }
    const auto available = std::min(length, video.size() - start);
    if (available == 0) {
        return 255.0;
    }
    constexpr std::size_t kSamples = 64U;
    const auto sampleCount = std::min(kSamples, available);
    double sum = 0.0;
    for (std::size_t index = 0; index < sampleCount; ++index) {
        const auto offset = (static_cast<std::uint64_t>(index) * available) / sampleCount;
        sum += static_cast<double>(video[start + static_cast<std::size_t>(offset)]);
    }
    return sum / static_cast<double>(sampleCount);
}

double sampleActivity(
        const std::vector<std::uint8_t>& video,
        std::size_t start,
        std::size_t length) {
    if (start >= video.size() || length == 0) {
        return 0.0;
    }
    const auto available = std::min(length, video.size() - start);
    if (available < 2U) {
        return 0.0;
    }
    constexpr std::size_t kSamples = 64U;
    const auto sampleCount = std::min(kSamples, available);
    double sum = 0.0;
    double sumSquares = 0.0;
    for (std::size_t index = 0; index < sampleCount; ++index) {
        const auto offset = (static_cast<std::uint64_t>(index) * available) / sampleCount;
        const double value = static_cast<double>(video[start + static_cast<std::size_t>(offset)]);
        sum += value;
        sumSquares += value * value;
    }
    const double mean = sum / static_cast<double>(sampleCount);
    return (sumSquares / static_cast<double>(sampleCount)) - (mean * mean);
}

double blankingScoreAfter(
        const std::vector<std::uint8_t>& normalized,
        std::size_t edgeSample,
        std::size_t lineSamples) {
    if (lineSamples == 0 || edgeSample >= normalized.size()) {
        return 0.0;
    }

    constexpr std::size_t kFirstProbeLine = 3U;
    constexpr std::size_t kProbeLines = 12U;
    const auto activeOffset = static_cast<std::size_t>(std::round(static_cast<double>(lineSamples) * 0.18));
    const auto activeLength = static_cast<std::size_t>(std::round(static_cast<double>(lineSamples) * 0.60));
    double darknessSum = 0.0;
    double flatnessSum = 0.0;
    std::size_t used = 0;
    for (std::size_t line = 0; line < kProbeLines; ++line) {
        const auto start = edgeSample + ((kFirstProbeLine + line) * lineSamples) + activeOffset;
        if (start >= normalized.size()) {
            break;
        }
        const double mean = sampleMean(normalized, start, activeLength);
        const double activity = sampleActivity(normalized, start, activeLength);
        darknessSum += std::clamp((150.0 - mean) / 120.0, 0.0, 1.0);
        flatnessSum += 1.0 / (1.0 + (activity / 220.0));
        ++used;
    }

    if (used < 4U) {
        return 0.0;
    }
    return ((darknessSum / static_cast<double>(used)) * 0.65) +
           ((flatnessSum / static_cast<double>(used)) * 0.35);
}

double intervalScore(
        const std::vector<FrameSyncCandidate>& candidates,
        std::size_t candidateIndex,
        double fieldPeriodSamples) {
    if (candidates.size() < 2U || fieldPeriodSamples <= 1.0) {
        return 0.55;
    }

    double best = 0.0;
    const double minPeriod = fieldPeriodSamples * 0.85;
    const double maxPeriod = fieldPeriodSamples * 1.15;
    const double tolerance = fieldPeriodSamples * 0.15;
    for (std::size_t index = 0; index < candidates.size(); ++index) {
        if (index == candidateIndex) {
            continue;
        }
        const double distance = std::fabs(
                static_cast<double>(candidates[index].sample) -
                static_cast<double>(candidates[candidateIndex].sample));
        if (distance < minPeriod || distance > maxPeriod) {
            continue;
        }
        best = std::max(best, 1.0 - (std::fabs(distance - fieldPeriodSamples) / tolerance));
    }
    return best;
}

FrameSyncDetection detectFrameSyncEdges(
        const std::vector<std::uint8_t>& video,
        std::uint64_t sampleRateHz,
        std::size_t lineSamples,
        std::size_t expectedLineCount,
        bool syncIsHigh,
        std::uint8_t threshold) {
    FrameSyncDetection detection;
    if (video.size() < samplesForSeconds(sampleRateHz, 0.018) ||
        sampleRateHz == 0 ||
        lineSamples == 0 ||
        expectedLineCount == 0) {
        return detection;
    }

    const auto smoothed = smoothVideo(video, sampleRateHz, 0.75e-6);
    const auto normalized = normalizedSyncLowVideo(smoothed, syncIsHigh);
    const auto minRunSamples = samplesForSeconds(sampleRateHz, 1.7e-6);
    const auto longRunSamples = samplesForSeconds(sampleRateHz, 13.0e-6);
    const auto clusterWindowSamples = static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(lineSamples) * 3.2)));
    const double fieldPeriodSamples =
            static_cast<double>(lineSamples) * (static_cast<double>(expectedLineCount) / 2.0);
    auto runs = detectPulseRuns(smoothed, threshold, syncIsHigh, minRunSamples);
    if (runs.empty()) {
        return detection;
    }

    std::vector<FrameSyncCandidate> candidates;
    std::size_t index = 0;
    while (index < runs.size()) {
        const auto clusterStart = runs[index].start;
        auto clusterEnd = runs[index].end;
        std::size_t longRuns = 0;
        std::size_t totalSyncSamples = 0;
        std::size_t runCount = 0;
        while (index < runs.size() && runs[index].start <= clusterStart + clusterWindowSamples) {
            const auto length = runs[index].end - runs[index].start;
            if (length >= longRunSamples) {
                ++longRuns;
            }
            totalSyncSamples += length;
            clusterEnd = std::max(clusterEnd, runs[index].end);
            ++runCount;
            ++index;
        }

        if (runCount < 2U && longRuns == 0U) {
            continue;
        }

        const double longRunScore = std::min(1.0, static_cast<double>(longRuns) / 3.0);
        const double density = static_cast<double>(totalSyncSamples) /
                static_cast<double>(std::max<std::size_t>(1U, clusterEnd - clusterStart));
        const double densityScore = std::clamp((density - 0.04) / 0.18, 0.0, 1.0);
        const double blankingScore = blankingScoreAfter(normalized, clusterStart, lineSamples);
        const double score = (longRunScore * 0.42) + (densityScore * 0.23) + (blankingScore * 0.35);
        if (score >= 0.28) {
            candidates.push_back({clusterStart, score});
        }
    }

    for (std::size_t candidateIndex = 0; candidateIndex < candidates.size(); ++candidateIndex) {
        const double periodScore = intervalScore(candidates, candidateIndex, fieldPeriodSamples);
        const double combinedScore = (candidates[candidateIndex].score * 0.72) + (periodScore * 0.28);
        if (combinedScore < 0.34) {
            continue;
        }
        if (!detection.edges.empty() &&
            candidates[candidateIndex].sample < detection.edges.back() + (lineSamples * 8U)) {
            if (combinedScore > detection.quality) {
                detection.edges.back() = candidates[candidateIndex].sample;
            }
        } else {
            detection.edges.push_back(candidates[candidateIndex].sample);
        }
        detection.quality = std::max(detection.quality, combinedScore);
    }

    return detection;
}

std::vector<std::size_t> detectRuns(
        const std::vector<std::uint8_t>& video,
        std::uint8_t threshold,
        bool highPulse,
        std::size_t minRunSamples,
        std::size_t maxRunSamples,
        std::size_t minSyncSpacing,
        std::size_t maxSyncs) {
    std::vector<std::size_t> syncStarts;
    std::size_t index = 0;
    while (index < video.size() && syncStarts.size() < maxSyncs) {
        const bool active = highPulse ? (video[index] >= threshold) : (video[index] <= threshold);
        if (!active) {
            ++index;
            continue;
        }

        const auto runStart = index;
        while (index < video.size()) {
            const bool stillActive =
                    highPulse ? (video[index] >= threshold) : (video[index] <= threshold);
            if (!stillActive) {
                break;
            }
            ++index;
        }

        const auto runLength = index - runStart;
        if (runLength < minRunSamples || runLength > maxRunSamples) {
            continue;
        }

        if (!syncStarts.empty() && runStart < syncStarts.back() + minSyncSpacing) {
            continue;
        }

        syncStarts.push_back(runStart);
    }

    return syncStarts;
}

double lineStabilityScore(
        const std::vector<std::size_t>& syncStarts,
        std::size_t expectedSamplesPerLine) {
    if (syncStarts.size() < 2 || expectedSamplesPerLine == 0) {
        return 0.0;
    }

    double absoluteErrorSum = 0.0;
    for (std::size_t index = 1; index < syncStarts.size(); ++index) {
        const auto interval = syncStarts[index] - syncStarts[index - 1];
        absoluteErrorSum += std::abs(static_cast<double>(interval) -
                                     static_cast<double>(expectedSamplesPerLine));
    }

    const double meanAbsoluteError =
            absoluteErrorSum / static_cast<double>(syncStarts.size() - 1);
    const double normalizedError =
            meanAbsoluteError / std::max(1.0, static_cast<double>(expectedSamplesPerLine));
    return std::clamp(1.0 - (normalizedError * 10.0), 0.0, 1.0);
}

SyncDetectionResult makeResult(
        std::vector<std::size_t> syncStarts,
        bool syncIsHigh,
        std::uint8_t threshold,
        std::size_t expectedSamplesPerLine,
        std::size_t expectedLineCount) {
    SyncDetectionResult result;
    result.syncStarts = std::move(syncStarts);
    result.syncIsHigh = syncIsHigh;
    result.threshold = threshold;
    result.lineStabilityScore = lineStabilityScore(result.syncStarts, expectedSamplesPerLine);

    const double syncCoverage = expectedLineCount == 0
            ? 0.0
            : std::min(1.0,
                       static_cast<double>(result.syncStarts.size()) /
                               static_cast<double>(expectedLineCount));
    result.score = (syncCoverage * 0.65) + (result.lineStabilityScore * 0.35);
    return result;
}

SyncDetectionResult detectHorizontalSyncsInternal(
        const std::vector<std::uint8_t>& video,
        std::uint64_t sampleRateHz,
        double lineRateHz,
        std::size_t maxSyncs,
        bool detectFrameSync) {
    SyncDetectionResult emptyResult;
    if (video.empty() || sampleRateHz == 0 || lineRateHz <= 0.0 || maxSyncs == 0) {
        return emptyResult;
    }

    const auto lineSamples = static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(sampleRateHz) / lineRateHz)));
    const auto minRunSamples = samplesForSeconds(sampleRateHz, 2.0e-6);
    const auto maxRunSamples = samplesForSeconds(sampleRateHz, 12.0e-6);
    const auto minSyncSpacing = static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(lineSamples) * 0.55)));

    const auto smoothed = smoothVideo(video, sampleRateHz, 0.75e-6);
    auto thresholdScratch = smoothed;
    const auto lowThreshold = percentileInPlace(thresholdScratch, 0.08);
    const auto highThreshold = percentileInPlace(thresholdScratch, 0.92);
    const auto expectedLineCount = maxSyncs > 800U ? (maxSyncs / 2U) : maxSyncs;

    auto lowResult = makeResult(
            detectRuns(
                    smoothed,
                    lowThreshold,
                    false,
                    minRunSamples,
                    maxRunSamples,
                    minSyncSpacing,
                    maxSyncs),
            false,
            lowThreshold,
            lineSamples,
            expectedLineCount);
    auto highResult = makeResult(
            detectRuns(
                    smoothed,
                    highThreshold,
                    true,
                    minRunSamples,
                    maxRunSamples,
                    minSyncSpacing,
                    maxSyncs),
            true,
            highThreshold,
            lineSamples,
            expectedLineCount);

    auto result = highResult.score > lowResult.score ? highResult : lowResult;
    if (detectFrameSync) {
        const auto frameSync = detectFrameSyncEdges(
                video,
                sampleRateHz,
                lineSamples,
                expectedLineCount,
                result.syncIsHigh,
                result.threshold);
        result.frameSyncEdges = frameSync.edges;
        result.frameSyncQuality = frameSync.quality;
    }
    return result;
}

}  // namespace

SyncDetectionResult SyncDetector::detectHorizontalSyncs(
        const std::vector<std::uint8_t>& video,
        std::uint64_t sampleRateHz,
        double lineRateHz,
        std::size_t maxSyncs) const {
    return detectHorizontalSyncsInternal(video, sampleRateHz, lineRateHz, maxSyncs, true);
}

SyncDetectionResult SyncDetector::detectHorizontalSyncsFast(
        const std::vector<std::uint8_t>& video,
        std::uint64_t sampleRateHz,
        double lineRateHz,
        std::size_t maxSyncs) const {
    return detectHorizontalSyncsInternal(video, sampleRateHz, lineRateHz, maxSyncs, false);
}

}  // namespace sdr
