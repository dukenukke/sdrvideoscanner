#include "FileSource.h"

#include <limits>
#include <utility>

namespace sdr {
namespace {

constexpr std::size_t kBytesPerIqPair =
        sizeof(std::int16_t) * SampleBuffer::kValuesPerIqSample;

SourceStatus makeStatus(SampleSourceError error, std::string message) {
    return SourceStatus{error, std::move(message)};
}

ReadResult makeReadResult(
        std::size_t samplesRead,
        bool endOfStream,
        SampleSourceError error,
        std::string message) {
    return ReadResult{samplesRead, endOfStream, error, std::move(message)};
}

}  // namespace

FileSource::FileSource(std::string path, std::uint32_t sampleRateHz)
        : path_(std::move(path)) {
    format_.encoding = SampleEncoding::Cs16;
    format_.sampleRateHz = sampleRateHz;
    format_.channelCount = 1;
}

FileSource::~FileSource() {
    close();
}

SourceStatus FileSource::open() {
    if (path_.empty()) {
        return makeStatus(SampleSourceError::InvalidArgument, "IQ file path is empty");
    }

    close();
    file_.open(path_, std::ios::binary | std::ios::in);
    if (!file_.is_open()) {
        return makeStatus(SampleSourceError::OpenFailed, "Failed to open IQ file: " + path_);
    }

    return {};
}

void FileSource::close() {
    if (file_.is_open()) {
        file_.close();
    }
}

bool FileSource::isOpen() const {
    return file_.is_open();
}

SampleFormat FileSource::format() const {
    return format_;
}

SourceStatus FileSource::seekSamples(std::uint64_t sampleOffset) {
    if (!file_.is_open()) {
        return makeStatus(SampleSourceError::NotOpen, "IQ file is not open");
    }

    constexpr auto bytesPerIqPair = static_cast<std::uint64_t>(kBytesPerIqPair);
    if (sampleOffset > (std::numeric_limits<std::uint64_t>::max() / bytesPerIqPair)) {
        return makeStatus(SampleSourceError::InvalidArgument, "IQ sample seek offset is too large");
    }

    const auto byteOffset = sampleOffset * bytesPerIqPair;
    if (byteOffset > static_cast<std::uint64_t>(std::numeric_limits<std::streamoff>::max())) {
        return makeStatus(SampleSourceError::InvalidArgument, "IQ byte seek offset is too large");
    }

    file_.clear();
    file_.seekg(static_cast<std::streamoff>(byteOffset), std::ios::beg);
    if (!file_.good()) {
        return makeStatus(SampleSourceError::ReadFailed, "Failed to seek IQ file");
    }

    return {};
}

ReadResult FileSource::read(SampleBuffer& buffer, std::size_t maxSamples) {
    buffer.clear();

    if (!file_.is_open()) {
        return makeReadResult(0, false, SampleSourceError::NotOpen, "IQ file is not open");
    }

    if (maxSamples == 0) {
        return {};
    }

    const std::size_t maxReadableSamples =
            static_cast<std::size_t>(std::numeric_limits<std::streamsize>::max()) / kBytesPerIqPair;
    if (maxSamples > maxReadableSamples) {
        return makeReadResult(
                0,
                false,
                SampleSourceError::InvalidArgument,
                "Requested IQ sample count is too large");
    }

    buffer.resizeSamples(maxSamples);
    const auto requestedBytes = static_cast<std::streamsize>(maxSamples * kBytesPerIqPair);
    file_.read(reinterpret_cast<char*>(buffer.data()), requestedBytes);

    const auto bytesRead = file_.gcount();
    if (bytesRead == 0 && file_.eof()) {
        buffer.clear();
        return makeReadResult(0, true, SampleSourceError::None, {});
    }

    if (file_.bad()) {
        buffer.clear();
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Failed to read IQ file");
    }

    if ((bytesRead % static_cast<std::streamsize>(kBytesPerIqPair)) != 0) {
        buffer.clear();
        return makeReadResult(
                0,
                false,
                SampleSourceError::ReadFailed,
                "IQ file ended with an incomplete CS16 I/Q pair");
    }

    const auto samplesRead = static_cast<std::size_t>(bytesRead) / kBytesPerIqPair;
    buffer.resizeSamples(samplesRead);

    return makeReadResult(samplesRead, file_.eof(), SampleSourceError::None, {});
}

}  // namespace sdr
