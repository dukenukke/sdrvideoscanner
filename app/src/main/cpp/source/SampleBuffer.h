#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace sdr {

class SampleBuffer {
public:
    void resizeSamples(std::size_t iqPairCount) {
        interleavedIq_.resize(iqPairCount * kValuesPerIqPair);
    }

    void clear() {
        interleavedIq_.clear();
    }

    std::size_t sampleCount() const {
        return interleavedIq_.size() / kValuesPerIqPair;
    }

    std::size_t valueCount() const {
        return interleavedIq_.size();
    }

    std::int16_t* data() {
        return interleavedIq_.data();
    }

    const std::int16_t* data() const {
        return interleavedIq_.data();
    }

private:
    static constexpr std::size_t kValuesPerIqPair = 2;

    std::vector<std::int16_t> interleavedIq_;
};

}  // namespace sdr
