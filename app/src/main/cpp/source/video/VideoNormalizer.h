#pragma once

#include <cstdint>
#include <vector>

namespace sdr {

class VideoNormalizer {
public:
    void normalize(const std::vector<float>& envelope, std::vector<std::uint8_t>& video) const;
};

}  // namespace sdr
