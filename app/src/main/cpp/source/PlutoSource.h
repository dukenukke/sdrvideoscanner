#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "ISampleSource.h"
#include "SampleFormat.h"

namespace sdr {

struct PlutoSourceConfig {
    std::string uri = "usb:";
    std::uint64_t sampleRateHz = 25000000;
    std::uint64_t centerFrequencyHz = 5885000000;
    std::uint64_t rfBandwidthHz = 20000000;
    double gainDb = 46.0;
    SampleEncoding sampleEncoding = SampleEncoding::Cs16;
    std::int64_t loOffsetHz = 0;
    bool hardwareIqCorrection = true;
    bool hardwareBbdcCorrection = true;
    bool hardwareRfdcCorrection = true;
    std::size_t bufferSamples = 4096;
    std::size_t streamBlockCount = 4;
};

class PlutoSource final : public ISampleSource {
public:
    explicit PlutoSource(PlutoSourceConfig config);
    ~PlutoSource() override;

    SourceStatus open() override;
    void close() override;
    bool isOpen() const override;
    SampleFormat format() const override;
    ReadResult read(SampleBuffer& buffer, std::size_t maxSamples) override;

private:
    PlutoSourceConfig config_;
    SampleFormat format_;

    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace sdr
