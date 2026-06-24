#include "VideoNormalizer.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace sdr {
namespace {

constexpr std::size_t kHistogramBins = 512;

float histogramPercentile(
        const std::array<std::uint32_t, kHistogramBins>& histogram,
        std::size_t sampleCount,
        float minimum,
        float maximum,
        double fraction) {
    if (sampleCount == 0 || maximum <= minimum) {
        return minimum;
    }

    const auto target = static_cast<std::size_t>(
            std::clamp(fraction, 0.0, 1.0) * static_cast<double>(sampleCount - 1U));
    std::size_t cumulative = 0;
    for (std::size_t bin = 0; bin < histogram.size(); ++bin) {
        cumulative += histogram[bin];
        if (cumulative > target) {
            const float t = static_cast<float>(bin) /
                    static_cast<float>(histogram.size() - 1U);
            return minimum + ((maximum - minimum) * t);
        }
    }

    return maximum;
}

}  // namespace

void VideoNormalizer::normalize(
        const std::vector<float>& envelope,
        std::vector<std::uint8_t>& video) const {
    video.clear();
    video.resize(envelope.size());
    if (envelope.empty()) {
        return;
    }

    float minimum = 0.0F;
    float maximum = 0.0F;
    std::size_t finiteCount = 0;
    bool allFinite = true;
    for (const auto sample : envelope) {
        if (!std::isfinite(sample)) {
            allFinite = false;
            continue;
        }
        if (finiteCount == 0) {
            minimum = sample;
            maximum = sample;
        } else {
            minimum = std::min(minimum, sample);
            maximum = std::max(maximum, sample);
        }
        ++finiteCount;
    }

    if (finiteCount == 0 || maximum <= minimum) {
        std::fill(video.begin(), video.end(), static_cast<std::uint8_t>(0));
        return;
    }

    std::array<std::uint32_t, kHistogramBins> histogram{};
    const float binScale = static_cast<float>(kHistogramBins - 1U) / (maximum - minimum);
    if (allFinite) {
        for (const auto sample : envelope) {
            const auto scaled = static_cast<int>((sample - minimum) * binScale);
            const auto bin = static_cast<std::size_t>(
                    std::min<int>(
                            static_cast<int>(kHistogramBins - 1U),
                            std::max<int>(0, scaled)));
            ++histogram[bin];
        }
    } else {
        for (const auto sample : envelope) {
            if (!std::isfinite(sample)) {
                continue;
            }
            const auto scaled = static_cast<int>((sample - minimum) * binScale);
            const auto bin = static_cast<std::size_t>(
                    std::min<int>(
                            static_cast<int>(kHistogramBins - 1U),
                            std::max<int>(0, scaled)));
            ++histogram[bin];
        }
    }

    const float low = histogramPercentile(histogram, finiteCount, minimum, maximum, 0.01);
    const float high = histogramPercentile(histogram, finiteCount, minimum, maximum, 0.99);

    if (!std::isfinite(low) || !std::isfinite(high) || high <= low) {
        std::fill(video.begin(), video.end(), static_cast<std::uint8_t>(0));
        return;
    }

    const float scale = 255.0F / (high - low);
    if (allFinite) {
        for (std::size_t index = 0; index < envelope.size(); ++index) {
            const int normalized = static_cast<int>(((envelope[index] - low) * scale) + 0.5F);
            video[index] = static_cast<std::uint8_t>(
                    std::min(255, std::max(0, normalized)));
        }
    } else {
        for (std::size_t index = 0; index < envelope.size(); ++index) {
            if (!std::isfinite(envelope[index])) {
                video[index] = 0;
                continue;
            }
            const int normalized = static_cast<int>(((envelope[index] - low) * scale) + 0.5F);
            video[index] = static_cast<std::uint8_t>(
                    std::min(255, std::max(0, normalized)));
        }
    }
}

}  // namespace sdr
