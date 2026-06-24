#pragma once

#include <cstddef>
#include <vector>

#include "../SampleBuffer.h"

namespace sdr {

class AmDemodulator {
public:
    void appendEnvelope(
            const SampleBuffer& samples,
            std::size_t samplesRead,
            std::vector<float>& envelope) const;
};

}  // namespace sdr
