#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "../SampleBuffer.h"

namespace sdr {

class FmDemodulator {
public:
    void reset();
    void appendDiscriminator(
            const SampleBuffer& samples,
            std::size_t samplesRead,
            std::vector<float>& video);
    void appendDecimatedDiscriminator(
            const SampleBuffer& samples,
            std::size_t samplesRead,
            std::size_t decimation,
            std::vector<float>& video);

private:
    bool hasPrevious_ = false;
    std::int16_t previousI_ = 0;
    std::int16_t previousQ_ = 0;
    std::size_t samplesUntilNextOutput_ = 0;
};

}  // namespace sdr
