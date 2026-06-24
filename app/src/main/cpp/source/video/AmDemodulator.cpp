#include "AmDemodulator.h"

#include <cmath>

namespace sdr {
namespace {

constexpr float kCs16FullScale = 32768.0F;

}  // namespace

void AmDemodulator::appendEnvelope(
        const SampleBuffer& samples,
        std::size_t samplesRead,
        std::vector<float>& envelope) const {
    const auto start = envelope.size();
    envelope.resize(start + samplesRead);

    for (std::size_t index = 0; index < samplesRead; ++index) {
        const float i = static_cast<float>(samples.i(index)) / kCs16FullScale;
        const float q = static_cast<float>(samples.q(index)) / kCs16FullScale;
        envelope[start + index] = std::sqrt((i * i) + (q * q));
    }
}

}  // namespace sdr
