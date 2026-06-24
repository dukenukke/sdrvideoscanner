#include "VideoLowPassFilter.h"

#include <algorithm>
#include <cmath>

namespace sdr {
namespace {

constexpr float kPi = 3.14159265358979323846F;

}  // namespace

void VideoLowPassFilter::filter(
        const std::vector<float>& input,
        std::uint64_t sampleRateHz,
        double cutoffHz,
        std::vector<float>& output) const {
    output.clear();
    if (input.empty()) {
        return;
    }

    const float fs = static_cast<float>(sampleRateHz);
    const float cutoff = static_cast<float>(cutoffHz);
    if (!(cutoff > 1.0F) || cutoff >= 0.49F * fs) {
        output = input;
        return;
    }

    const auto stage1 = makeLowPassBiquad(fs, cutoff, 0.5411961F);
    const auto stage2 = makeLowPassBiquad(fs, cutoff, 1.3065630F);
    runBiquad(stage1, input, output);
    runBiquadInPlace(stage2, output);
}

VideoLowPassFilter::BiquadCoefficients VideoLowPassFilter::makeLowPassBiquad(
        float sampleRateHz,
        float cutoffHz,
        float q) {
    const float w0 = 2.0F * kPi * cutoffHz / sampleRateHz;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float alpha = sw / (2.0F * q);

    const float b0 = (1.0F - cw) * 0.5F;
    const float b1 = 1.0F - cw;
    const float b2 = (1.0F - cw) * 0.5F;
    const float a0 = 1.0F + alpha;
    const float a1 = -2.0F * cw;
    const float a2 = 1.0F - alpha;

    BiquadCoefficients coefficients;
    coefficients.b0 = b0 / a0;
    coefficients.b1 = b1 / a0;
    coefficients.b2 = b2 / a0;
    coefficients.a1 = a1 / a0;
    coefficients.a2 = a2 / a0;
    return coefficients;
}

void VideoLowPassFilter::runBiquad(
        const BiquadCoefficients& coefficients,
        const std::vector<float>& input,
        std::vector<float>& output) {
    output.resize(input.size());
    float x1 = 0.0F;
    float x2 = 0.0F;
    float y1 = 0.0F;
    float y2 = 0.0F;

    for (std::size_t index = 0; index < input.size(); ++index) {
        const float x0 = input[index];
        const float y0 =
                (coefficients.b0 * x0) +
                (coefficients.b1 * x1) +
                (coefficients.b2 * x2) -
                (coefficients.a1 * y1) -
                (coefficients.a2 * y2);
        output[index] = y0;
        x2 = x1;
        x1 = x0;
        y2 = y1;
        y1 = y0;
    }
}

void VideoLowPassFilter::runBiquadInPlace(
        const BiquadCoefficients& coefficients,
        std::vector<float>& samples) {
    float x1 = 0.0F;
    float x2 = 0.0F;
    float y1 = 0.0F;
    float y2 = 0.0F;

    for (auto& sample : samples) {
        const float x0 = sample;
        const float y0 =
                (coefficients.b0 * x0) +
                (coefficients.b1 * x1) +
                (coefficients.b2 * x2) -
                (coefficients.a1 * y1) -
                (coefficients.a2 * y2);
        sample = y0;
        x2 = x1;
        x1 = x0;
        y2 = y1;
        y1 = y0;
    }
}

}  // namespace sdr
