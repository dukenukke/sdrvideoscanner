#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include <jni.h>

#include "ISampleSource.h"

namespace sdr {

struct MaiaSourceConfig {
    std::string host = "192.168.2.1";
    std::uint16_t port = 80;
    std::uint32_t sampleRateHz = 10000000;
    std::size_t bufferSamples = 32768;
    int connectTimeoutMs = 3000;
    int readTimeoutMs = 3000;
    int reconnectAttempts = 1;
    jobject androidHttpTransport = nullptr;
};

void setMaiaSourceJavaVm(JavaVM* javaVm);

class MaiaSource final : public ISampleSource {
public:
    explicit MaiaSource(MaiaSourceConfig config);
    ~MaiaSource() override;

    SourceStatus open() override;
    void close() override;
    bool isOpen() const override;
    SampleFormat format() const override;
    ReadResult read(SampleBuffer& buffer, std::size_t maxSamples) override;

private:
    SourceStatus configureRecorder();
    SourceStatus openStream();
    SourceStatus reconnectStream();

    MaiaSourceConfig config_;
    SampleFormat format_;

    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace sdr
