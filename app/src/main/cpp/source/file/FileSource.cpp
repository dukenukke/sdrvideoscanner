#include "FileSource.h"

#include <limits>
#include <utility>

namespace sdr {
namespace {

constexpr std::size_t kCs16BytesPerIqPair =
        sizeof(std::int16_t) * SampleBuffer::kValuesPerIqSample;
constexpr std::size_t kCs8BytesPerIqPair =
        sizeof(std::int8_t) * SampleBuffer::kValuesPerIqSample;

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

FileSource::FileSource(
        std::string path,
        std::uint32_t sampleRateHz,
        SampleEncoding encoding)
        : path_(std::move(path)) {
    format_.encoding = encoding;
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

    const auto bytesPerIqPair = static_cast<std::uint64_t>(this->bytesPerIqPair());
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

    const auto bytesPerPair = bytesPerIqPair();
    const std::size_t maxReadableSamples =
            static_cast<std::size_t>(std::numeric_limits<std::streamsize>::max()) / bytesPerPair;
    if (maxSamples > maxReadableSamples) {
        return makeReadResult(
                0,
                false,
                SampleSourceError::InvalidArgument,
                "Requested IQ sample count is too large");
    }

    buffer.resizeSamples(maxSamples);
    const auto requestedBytes = static_cast<std::streamsize>(maxSamples * bytesPerPair);
    if (format_.encoding == SampleEncoding::Cs8) {
        cs8ReadScratch_.resize(maxSamples * SampleBuffer::kValuesPerIqSample);
        file_.read(reinterpret_cast<char*>(cs8ReadScratch_.data()), requestedBytes);
    } else {
        file_.read(reinterpret_cast<char*>(buffer.data()), requestedBytes);
    }

    const auto bytesRead = file_.gcount();
    if (bytesRead == 0 && file_.eof()) {
        buffer.clear();
        return makeReadResult(0, true, SampleSourceError::None, {});
    }

    if (file_.bad()) {
        buffer.clear();
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Failed to read IQ file");
    }

    if ((bytesRead % static_cast<std::streamsize>(bytesPerPair)) != 0) {
        buffer.clear();
        return makeReadResult(
                0,
                false,
                SampleSourceError::ReadFailed,
                "IQ file ended with an incomplete I/Q pair");
    }

    const auto samplesRead = static_cast<std::size_t>(bytesRead) / bytesPerPair;
    buffer.resizeSamples(samplesRead);
    if (format_.encoding == SampleEncoding::Cs8) {
        for (std::size_t index = 0; index < samplesRead * SampleBuffer::kValuesPerIqSample; ++index) {
            buffer.data()[index] =
                    static_cast<std::int16_t>(static_cast<int>(cs8ReadScratch_[index]) * 256);
        }
    }

    return makeReadResult(samplesRead, file_.eof(), SampleSourceError::None, {});
}

std::size_t FileSource::bytesPerIqPair() const {
    return format_.encoding == SampleEncoding::Cs8
            ? kCs8BytesPerIqPair
            : kCs16BytesPerIqPair;
}

}  // namespace sdr
