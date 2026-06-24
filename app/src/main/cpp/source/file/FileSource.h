#pragma once

#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

#include "../ISampleSource.h"

namespace sdr {

class FileSource final : public ISampleSource {
public:
    FileSource(
            std::string path,
            std::uint32_t sampleRateHz,
            SampleEncoding encoding = SampleEncoding::Cs16);
    ~FileSource() override;

    SourceStatus open() override;
    void close() override;
    bool isOpen() const override;
    SampleFormat format() const override;
    SourceStatus seekSamples(std::uint64_t sampleOffset);
    ReadResult read(SampleBuffer& buffer, std::size_t maxSamples) override;

private:
    std::size_t bytesPerIqPair() const;
    std::string path_;
    SampleFormat format_;
    std::ifstream file_;
    std::vector<std::int8_t> cs8ReadScratch_;
};

}  // namespace sdr
