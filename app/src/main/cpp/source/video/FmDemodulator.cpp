#include "FmDemodulator.h"

#include <algorithm>
#include <cmath>

namespace sdr {
namespace {

constexpr float kPi = 3.14159265358979323846F;
constexpr float kQuarterPi = kPi * 0.25F;
constexpr float kThreeQuarterPi = kPi * 0.75F;

float fastAtan2(float y, float x) {
    const float absY = std::fabs(y) + 1.0e-12F;
    float angle = 0.0F;
    if (x >= 0.0F) {
        const float r = (x - absY) / (x + absY);
        angle = kQuarterPi - (kQuarterPi * r);
    } else {
        const float r = (x + absY) / (absY - x);
        angle = kThreeQuarterPi - (kQuarterPi * r);
    }

    return y < 0.0F ? -angle : angle;
}

}  // namespace

void FmDemodulator::reset() {
    hasPrevious_ = false;
    previousI_ = 0;
    previousQ_ = 0;
    samplesUntilNextOutput_ = 0;
}

void FmDemodulator::appendDiscriminator(
        const SampleBuffer& samples,
        std::size_t samplesRead,
        std::vector<float>& video) {
    if (samplesRead == 0) {
        return;
    }

    const auto startIndex = hasPrevious_ ? 0U : 1U;
    if (!hasPrevious_) {
        previousI_ = samples.i(0);
        previousQ_ = samples.q(0);
        hasPrevious_ = true;
    }

    const auto outputStart = video.size();
    video.resize(outputStart + (samplesRead - startIndex));

    std::size_t outIndex = outputStart;
    for (std::size_t index = startIndex; index < samplesRead; ++index) {
        const float i1 = static_cast<float>(samples.i(index));
        const float q1 = static_cast<float>(samples.q(index));
        const float i0 = static_cast<float>(previousI_);
        const float q0 = static_cast<float>(previousQ_);
        const float imag = (q1 * i0) - (i1 * q0);
        const float real = (i1 * i0) + (q1 * q0);
        video[outIndex++] = fastAtan2(imag, real);
        previousI_ = samples.i(index);
        previousQ_ = samples.q(index);
    }
}

void FmDemodulator::appendDecimatedDiscriminator(
        const SampleBuffer& samples,
        std::size_t samplesRead,
        std::size_t decimation,
        std::vector<float>& video) {
    if (samplesRead == 0) {
        return;
    }

    decimation = std::max<std::size_t>(1U, decimation);
    const auto outputStart = video.size();
    video.reserve(outputStart + ((samplesRead + decimation - 1U) / decimation));

    std::size_t index = 0;
    if (!hasPrevious_) {
        if (samplesRead == 1U) {
            previousI_ = samples.i(0);
            previousQ_ = samples.q(0);
            hasPrevious_ = true;
            return;
        }
        previousI_ = samples.i(0);
        previousQ_ = samples.q(0);
        hasPrevious_ = true;
        index = 1U;
    }

    if (samplesUntilNextOutput_ > 0U) {
        const auto samplesAvailable = samplesRead - index;
        if (samplesUntilNextOutput_ >= samplesAvailable) {
            samplesUntilNextOutput_ -= samplesAvailable;
            previousI_ = samples.i(samplesRead - 1U);
            previousQ_ = samples.q(samplesRead - 1U);
            return;
        }
        index += samplesUntilNextOutput_;
        samplesUntilNextOutput_ = 0U;
    }

    while (index < samplesRead) {
        const float i1 = static_cast<float>(samples.i(index));
        const float q1 = static_cast<float>(samples.q(index));
        const float i0 = index == 0U
                ? static_cast<float>(previousI_)
                : static_cast<float>(samples.i(index - 1U));
        const float q0 = index == 0U
                ? static_cast<float>(previousQ_)
                : static_cast<float>(samples.q(index - 1U));

        const float imag = (q1 * i0) - (i1 * q0);
        const float real = (i1 * i0) + (q1 * q0);
        video.push_back(fastAtan2(imag, real));
        index += decimation;
    }

    const auto overshoot = index - samplesRead;
    samplesUntilNextOutput_ = overshoot == 0U ? 0U : decimation - overshoot;
    previousI_ = samples.i(samplesRead - 1U);
    previousQ_ = samples.q(samplesRead - 1U);
}

}  // namespace sdr
