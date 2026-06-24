#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include <jni.h>

#include "ISampleSource.h"

namespace sdr {

// ISampleSource adapter for plutorx_ws. The Android transport owns the WebSocket
// handshake and frame parsing; this source only receives raw CS8 I/Q bytes and
// expands them into the shared int16 SampleBuffer representation.
struct PlutoWebSocketSourceConfig {
    std::string host = "192.168.2.1";
    std::uint16_t port = 7682;
    std::string path = "/iq";
    std::uint32_t sampleRateHz = 25000000;
    std::size_t bufferSamples = 32768;
    jobject androidWebSocketTransport = nullptr;
};

void setPlutoWebSocketSourceJavaVm(JavaVM* javaVm);

class PlutoWebSocketSource final : public ISampleSource {
public:
    explicit PlutoWebSocketSource(PlutoWebSocketSourceConfig config);
    ~PlutoWebSocketSource() override;

    SourceStatus open() override;
    void close() override;
    bool isOpen() const override;
    SampleFormat format() const override;
    ReadResult read(SampleBuffer& buffer, std::size_t maxSamples) override;

private:
    PlutoWebSocketSourceConfig config_;
    SampleFormat format_;

    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace sdr
