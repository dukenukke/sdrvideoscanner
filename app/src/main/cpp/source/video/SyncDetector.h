#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace sdr {

struct SyncDetectionResult {
    std::vector<std::size_t> syncStarts;
    std::vector<std::size_t> frameSyncEdges;
    bool syncIsHigh = false;
    std::uint8_t threshold = 0;
    double score = 0.0;
    double lineStabilityScore = 0.0;
    double frameSyncQuality = 0.0;
};

class SyncDetector {
public:
    SyncDetectionResult detectHorizontalSyncs(
            const std::vector<std::uint8_t>& video,
            std::uint64_t sampleRateHz,
            double lineRateHz,
            std::size_t maxSyncs) const;

    SyncDetectionResult detectHorizontalSyncsFast(
            const std::vector<std::uint8_t>& video,
            std::uint64_t sampleRateHz,
            double lineRateHz,
            std::size_t maxSyncs) const;
};

}  // namespace sdr
