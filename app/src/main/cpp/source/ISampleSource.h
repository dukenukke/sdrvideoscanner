#pragma once

#include <cstddef>
#include <string>

#include "SampleBuffer.h"
#include "SampleFormat.h"

namespace sdr {

enum class SampleSourceError {
    None,
    InvalidArgument,
    OpenFailed,
    NotOpen,
    ReadFailed
};

struct SourceStatus {
    SampleSourceError error = SampleSourceError::None;
    std::string message;

    bool ok() const {
        return error == SampleSourceError::None;
    }
};

struct ReadResult {
    std::size_t samplesRead = 0;
    bool endOfStream = false;
    SampleSourceError error = SampleSourceError::None;
    std::string message;

    bool ok() const {
        return error == SampleSourceError::None;
    }
};

class ISampleSource {
public:
    virtual ~ISampleSource() = default;

    virtual SourceStatus open() = 0;
    virtual void close() = 0;
    virtual bool isOpen() const = 0;
    virtual SampleFormat format() const = 0;
    virtual ReadResult read(SampleBuffer& buffer, std::size_t maxSamples) = 0;
};

}  // namespace sdr
