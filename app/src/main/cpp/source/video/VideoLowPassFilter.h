#pragma once

#include <cstdint>
#include <vector>

namespace sdr {

class VideoLowPassFilter {
public:
    void filter(
            const std::vector<float>& input,
            std::uint64_t sampleRateHz,
            double cutoffHz,
            std::vector<float>& output) const;

private:
    struct BiquadCoefficients {
        float b0 = 1.0F;
        float b1 = 0.0F;
        float b2 = 0.0F;
        float a1 = 0.0F;
        float a2 = 0.0F;
    };

    static BiquadCoefficients makeLowPassBiquad(float sampleRateHz, float cutoffHz, float q);
    static void runBiquad(
            const BiquadCoefficients& coefficients,
            const std::vector<float>& input,
            std::vector<float>& output);
    static void runBiquadInPlace(
            const BiquadCoefficients& coefficients,
            std::vector<float>& samples);
};

}  // namespace sdr
