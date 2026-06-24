#include "SyncDetector.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace sdr {
namespace {

std::size_t samplesForSeconds(std::uint64_t sampleRateHz, double seconds) {
    return static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(sampleRateHz) * seconds)));
}

std::uint8_t percentile(std::vector<std::uint8_t> values, double fraction) {
    if (values.empty()) {
        return 0;
    }

    const auto index = static_cast<std::size_t>(
            std::clamp(fraction, 0.0, 1.0) * static_cast<double>(values.size() - 1));
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(index), values.end());
    return values[index];
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

std::vector<float> centeredFloatVideo(const std::vector<std::uint8_t>& video) {
    std::vector<float> centered(video.size());
    const auto median = static_cast<float>(percentile(video, 0.50));
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

double meanFloat(const std::vector<float>& values) {
    if (values.empty()) {
        return 0.0;
    }

    double sum = 0.0;
    for (const auto value : values) {
        sum += value;
    }
    return sum / static_cast<double>(values.size());
}

double stddevFloat(const std::vector<float>& values, double mean) {
    if (values.empty()) {
        return 0.0;
    }

    double sumSquares = 0.0;
    for (const auto value : values) {
        const double delta = static_cast<double>(value) - mean;
        sumSquares += delta * delta;
    }
    return std::sqrt(sumSquares / static_cast<double>(values.size()));
}

std::vector<std::size_t> detectFrameSyncEdges(
        const std::vector<std::uint8_t>& video,
        std::uint64_t sampleRateHz) {
    std::vector<std::size_t> edges;
    if (video.size() < samplesForSeconds(sampleRateHz, 0.03) || sampleRateHz == 0) {
        return edges;
    }

    auto centered = centeredFloatVideo(video);
    auto smooth = movingAverageFloat(centered, sampleRateHz, 0.00035);
    const float median = percentileFloat(smooth, 0.50);
    const float negScore = std::fabs(percentileFloat(smooth, 0.01) - median);
    const float posScore = std::fabs(percentileFloat(smooth, 0.99) - median);

    std::vector<float> sync(smooth.size());
    for (std::size_t index = 0; index < smooth.size(); ++index) {
        sync[index] = (negScore >= posScore) ? -smooth[index] : smooth[index];
    }

    const float syncMedian = percentileFloat(sync, 0.50);
    for (auto& value : sync) {
        value -= syncMedian;
    }

    const auto sigma = stddevFloat(sync, meanFloat(sync)) + 1.0e-12;
    for (auto& value : sync) {
        value = static_cast<float>(static_cast<double>(value) / sigma);
    }

    const auto minDistance = samplesForSeconds(sampleRateHz, 0.012);
    std::size_t lastKept = 0;
    bool hasLast = false;
    std::size_t index = 1;
    while (index + 1U < sync.size()) {
        if (sync[index] < 0.8F) {
            ++index;
            continue;
        }

        const auto runStart = index;
        auto peakIndex = index;
        auto peakValue = sync[index];
        while (index + 1U < sync.size() && sync[index] >= 0.8F) {
            if (sync[index] > peakValue) {
                peakValue = sync[index];
                peakIndex = index;
            }
            ++index;
        }

        if (peakValue < 1.2F) {
            continue;
        }
        if (hasLast && peakIndex < lastKept + minDistance) {
            if (!edges.empty() && sync[lastKept] < peakValue) {
                edges.pop_back();
            } else {
                continue;
            }
        }

        const float threshold = peakValue * 0.45F;
        auto edge = peakIndex;
        const auto limit = edge > samplesForSeconds(sampleRateHz, 0.006)
                ? edge - samplesForSeconds(sampleRateHz, 0.006)
                : 0U;
        while (edge > 0 && edge > limit && sync[edge] > threshold) {
            --edge;
        }
        edges.push_back(edge);
        lastKept = peakIndex;
        hasLast = true;

        if (index == runStart) {
            ++index;
        }
    }

    return edges;
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
    const auto lowThreshold = percentile(smoothed, 0.08);
    const auto highThreshold = percentile(smoothed, 0.92);
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
        result.frameSyncEdges = detectFrameSyncEdges(video, sampleRateHz);
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
