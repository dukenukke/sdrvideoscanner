#pragma once

#include <cstdint>
#include <string>

namespace sdr {

struct IQMetadata {
    bool sidecarFound = false;
    bool loaded = false;
    std::string sidecarPath;
    std::string parseError;

    std::string format;
    std::string endianness;
    bool hasSampleRateHz = false;
    std::uint64_t sampleRateHz = 0;
    bool hasCenterFrequencyHz = false;
    std::uint64_t centerFrequencyHz = 0;
    bool hasRfBandwidthHz = false;
    std::uint64_t rfBandwidthHz = 0;
    bool hasGainDb = false;
    double gainDb = 0.0;
    std::string source;
    std::string device;
    bool hasDurationSec = false;
    double durationSec = 0.0;
};

}  // namespace sdr
