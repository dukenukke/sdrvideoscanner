#pragma once

#include <cstdint>

namespace sdr {

enum class SampleEncoding {
    Cs16,
    Cs8
};

struct SampleFormat {
    SampleEncoding encoding = SampleEncoding::Cs16;
    std::uint32_t sampleRateHz = 0;
    std::uint32_t channelCount = 1;
};

}  // namespace sdr
