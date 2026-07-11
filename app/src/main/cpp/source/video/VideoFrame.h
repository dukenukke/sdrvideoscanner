#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace sdr {

struct VideoFrame {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::vector<std::uint8_t> pixels;
    bool syncLocked = false;
    std::size_t detectedSyncCount = 0;
    std::size_t detectedFrameSyncCount = 0;
    bool syncIsHigh = false;
    std::uint8_t syncThreshold = 0;
    std::string selectedStandard;
    double lineRateHz = 0.0;
    std::size_t samplesPerLine = 0;
    std::uint32_t totalLines = 0;
    std::uint32_t visibleLines = 0;
    double syncScore = 0.0;
    double lineStabilityScore = 0.0;
    double doubleImageScore = 0.0;
    std::string assemblyPath;
    std::string message;

    bool valid() const {
        return width != 0 &&
               height != 0 &&
               pixels.size() == static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    }
};

}  // namespace sdr
