#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace sdr {

class SampleBuffer {
public:
    static constexpr std::size_t kValuesPerIqSample = 2;

    void resizeSamples(std::size_t iqPairCount) {
        interleavedIq_.resize(iqPairCount * kValuesPerIqSample);
    }

    void clear() {
        interleavedIq_.clear();
    }

    std::size_t sampleCount() const {
        return interleavedIq_.size() / kValuesPerIqSample;
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

    std::int16_t i(std::size_t sampleIndex) const {
        return interleavedIq_[sampleIndex * kValuesPerIqSample];
    }

    std::int16_t q(std::size_t sampleIndex) const {
        return interleavedIq_[sampleIndex * kValuesPerIqSample + 1];
    }

private:
    std::vector<std::int16_t> interleavedIq_;
};

}  // namespace sdr
