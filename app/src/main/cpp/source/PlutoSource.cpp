#include "PlutoSource.h"

#include <algorithm>
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdint>
#include <limits>
#include <memory>
#include <sstream>
#include <utility>

#ifdef SDRVIDEOSCANNER_HAVE_LIBIIO
#if __has_include(<iio/iio.h>)
#include <iio/iio.h>
#else
#include <iio.h>
#endif
#endif

namespace sdr {
    namespace {

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

#ifdef SDRVIDEOSCANNER_HAVE_LIBIIO

        constexpr std::size_t kCs8BytesPerIqPair =
                sizeof(std::int8_t) * SampleBuffer::kValuesPerIqSample;
        constexpr std::size_t kCs16BytesPerIqPair =
                sizeof(std::int16_t) * SampleBuffer::kValuesPerIqSample;
        constexpr int kIpIioTimeoutMs = 3000;

        std::string iioErrorMessage(int error) {
            char message[160] = {};
            iio_strerror(error, message, sizeof(message));
            return message;
        }

        std::string readFileAndClose(FILE* file) {
            if (file == nullptr) return {};
            std::string content;
            if (std::fflush(file) == 0 && std::fseek(file, 0, SEEK_SET) == 0) {
                char buffer[256] = {};
                while (true) {
                    const auto bytesRead = std::fread(buffer, 1, sizeof(buffer), file);
                    if (bytesRead == 0) break;
                    content.append(buffer, bytesRead);
                }
            }
            std::fclose(file);
            return content;
        }

        bool isIpUri(const std::string& uri) { return uri.rfind("ip:", 0) == 0; }
        bool isUsbUri(const std::string& uri) { return uri.rfind("usb:", 0) == 0; }

        std::string iioContextErrorMessage(const std::string& uri, int error, const std::string& libiioLog) {
            std::ostringstream diagnostic;
            diagnostic << (error == 0 ? std::string("unknown error") : iioErrorMessage(error));
            if (error != 0) {
                diagnostic << " (" << error << ")";
            }
            if (isIpUri(uri) && (error == -EPERM || error == EPERM)) {
                diagnostic << ". Android ip: IIO capture requires android.permission.INTERNET in the APK manifest";
            }
            if (isUsbUri(uri) && (error == -EACCES || error == EACCES || error == -EPERM || error == EPERM)) {
                diagnostic << ". Android usb: IIO capture requires UsbManager permission and libiio built with libusb support";
            }
            if (!libiioLog.empty()) {
                diagnostic << "\nlibiio_log:\n" << libiioLog;
            }
            return diagnostic.str();
        }

        bool writeLongLongAttr(iio_channel* channel, const char* name, long long value) {
            if (channel == nullptr) return false;
            const auto* attr = iio_channel_find_attr(channel, name);
            return attr != nullptr && iio_attr_write_longlong(attr, value) >= 0;
        }

        bool writeStringAttr(iio_channel* channel, const char* name, const char* value) {
            if (channel == nullptr) return false;
            const auto* attr = iio_channel_find_attr(channel, name);
            return attr != nullptr && iio_attr_write_string(attr, value) >= 0;
        }

        iio_channel* findFirstChannel(iio_device* device, const char* firstName, const char* fallbackName) {
            auto* channel = iio_device_find_channel(device, firstName, false);
            return channel != nullptr ? channel : iio_device_find_channel(device, fallbackName, false);
        }

        std::string channelLabel(iio_channel* channel) {
            if (!channel) return "null";
            std::ostringstream label;
            label << "id=" << (iio_channel_get_id(channel) ? iio_channel_get_id(channel) : "null")
                  << ", name=" << (iio_channel_get_name(channel) ? iio_channel_get_name(channel) : "null");

            const auto* fmt = iio_channel_get_data_format(channel);
            if (fmt) {
                label << ", bits=" << fmt->bits
                      << ", length=" << fmt->length
                      << ", signed=" << (fmt->is_signed ? "true" : "false");
            }
            return label.str();
        }

        bool computeTuneFrequency(
                std::uint64_t centerFrequencyHz,
                std::int64_t loOffsetHz,
                long long& tuneFrequencyHz) {
            if (loOffsetHz < 0) {
                const auto magnitude = static_cast<std::uint64_t>(-(loOffsetHz + 1)) + 1U;
                if (magnitude >= centerFrequencyHz) return false;
                tuneFrequencyHz = static_cast<long long>(centerFrequencyHz - magnitude);
                return true;
            }
            const auto offset = static_cast<std::uint64_t>(loOffsetHz);
            if (centerFrequencyHz > static_cast<std::uint64_t>(std::numeric_limits<long long>::max()) - offset) {
                return false;
            }
            tuneFrequencyHz = static_cast<long long>(centerFrequencyHz + offset);
            return tuneFrequencyHz > 0;
        }

        struct PayloadLayout {
            std::size_t bytesPerIqPair = 0;
        };

        PayloadLayout choosePayloadLayout(
                bool requestedCs8,
                std::size_t configuredStrideBytes,
                std::size_t payloadBytes,
                std::size_t requestedBlockSamples) {
            if (!requestedCs8) {
                return PayloadLayout{kCs16BytesPerIqPair};
            }

            if (requestedBlockSamples > 0) {
                if (payloadBytes == requestedBlockSamples * kCs8BytesPerIqPair) {
                    return PayloadLayout{kCs8BytesPerIqPair};
                }
                if (payloadBytes == requestedBlockSamples * kCs16BytesPerIqPair) {
                    return PayloadLayout{kCs16BytesPerIqPair};
                }
            }

            if (configuredStrideBytes == kCs8BytesPerIqPair) {
                return PayloadLayout{kCs8BytesPerIqPair};
            }
            if (configuredStrideBytes == kCs16BytesPerIqPair) {
                return PayloadLayout{kCs16BytesPerIqPair};
            }

            return {};
        }

#endif
    } // namespace

    struct PlutoSource::Impl {
#ifdef SDRVIDEOSCANNER_HAVE_LIBIIO
        FILE* contextLog = nullptr;
        iio_context* context = nullptr;
        iio_device* phy = nullptr;
        iio_device* rx = nullptr;
        iio_channel* rxI = nullptr;
        iio_channel* rxQ = nullptr;
        iio_channels_mask* channelsMask = nullptr;
        iio_buffer* rxBuffer = nullptr;
        iio_stream* stream = nullptr;
        const iio_block* currentBlock = nullptr;
        const char* currentPtr = nullptr;
        const char* currentEnd = nullptr;
        std::size_t currentStepBytes = 0;
        std::size_t sampleStrideBytes = 0;
        std::size_t requestedBlockSamples = 0;
#endif
    };

    PlutoSource::PlutoSource(PlutoSourceConfig config)
            : config_(std::move(config)),
              impl_(std::make_unique<Impl>()) {
        format_.encoding = config_.sampleEncoding;
        format_.sampleRateHz = static_cast<std::uint32_t>(
                std::min<std::uint64_t>(config_.sampleRateHz, UINT32_MAX));
        format_.channelCount = 1;
    }

    PlutoSource::~PlutoSource() {
        close();
    }

    SourceStatus PlutoSource::open() {
#ifndef SDRVIDEOSCANNER_HAVE_LIBIIO
        return makeStatus(SampleSourceError::OpenFailed, "PlutoSource requires libiio");
#else
        close();

        if (config_.uri.empty() || config_.sampleRateHz == 0 || config_.rfBandwidthHz == 0) {
            return makeStatus(SampleSourceError::InvalidArgument, "Missing required parameters");
        }

        const bool useEightBitSamples = (config_.sampleEncoding == SampleEncoding::Cs8);

        auto* logFile = tmpfile();
        iio_context_params params = {};
        params.err = logFile;
        params.out = logFile;
        params.log_level = LEVEL_DEBUG;
        params.stderr_level = LEVEL_DEBUG;
        params.timestamp_level = LEVEL_NOLOG;
        params.timeout_ms = isIpUri(config_.uri) ? kIpIioTimeoutMs : IIO_TIMEOUT_BACKEND;

        impl_->context = iio_create_context(logFile == nullptr ? nullptr : &params, config_.uri.c_str());
        const auto contextError = iio_err(impl_->context);
        if (!impl_->context || contextError != 0) {
            const auto libiioLog = readFileAndClose(logFile);
            const auto message = iioContextErrorMessage(config_.uri, contextError, libiioLog);
            impl_->context = nullptr;
            return makeStatus(SampleSourceError::OpenFailed, "iio_create_context failed: " + message);
        }
        if (isIpUri(config_.uri)) {
            iio_context_set_timeout(impl_->context, kIpIioTimeoutMs);
        }
        impl_->contextLog = logFile;

        impl_->phy = iio_context_find_device(impl_->context, "ad9361-phy");
        impl_->rx  = iio_context_find_device(impl_->context, "cf-ad9361-lpc");

        if (!impl_->phy || !impl_->rx) {
            close();
            return makeStatus(SampleSourceError::OpenFailed, "ad9361-phy or cf-ad9361-lpc not found");
        }

        impl_->rxI = findFirstChannel(impl_->rx, "voltage0", "voltage0_i");
        impl_->rxQ = findFirstChannel(impl_->rx, "voltage1", "voltage0_q");

        if (!impl_->rxI || (!useEightBitSamples && !impl_->rxQ)) {
            close();
            return makeStatus(SampleSourceError::OpenFailed, "I/Q channels not found");
        }

        auto* phyI = iio_device_find_channel(impl_->phy, "voltage0", false);
        auto* lo   = iio_device_find_channel(impl_->phy, "altvoltage0", true);

        long long tuneFrequencyHz = 0;
        computeTuneFrequency(config_.centerFrequencyHz, config_.loOffsetHz, tuneFrequencyHz);

        if (!writeLongLongAttr(phyI, "sampling_frequency", static_cast<long long>(config_.sampleRateHz)) ||
            !writeLongLongAttr(phyI, "rf_bandwidth", static_cast<long long>(config_.rfBandwidthHz)) ||
            !writeStringAttr(phyI, "gain_control_mode", "manual") ||
            !writeLongLongAttr(phyI, "hardwaregain", static_cast<long long>(config_.gainDb)) ||
            !writeLongLongAttr(lo, "frequency", tuneFrequencyHz)) {
            close();
            return makeStatus(SampleSourceError::OpenFailed, "Failed to configure AD9361");
        }

        impl_->channelsMask = iio_create_channels_mask(iio_device_get_channels_count(impl_->rx));
        iio_channel_enable(impl_->rxI, impl_->channelsMask);
        if (useEightBitSamples) {
            if (impl_->rxQ != nullptr) {
                iio_channel_disable(impl_->rxQ, impl_->channelsMask);
            }
        } else {
            iio_channel_enable(impl_->rxQ, impl_->channelsMask);
        }

        const auto sampleSize = iio_device_get_sample_size(impl_->rx, impl_->channelsMask);
        if (sampleSize <= 0) {
            close();
            return makeStatus(
                    SampleSourceError::OpenFailed,
                    "iio_device_get_sample_size failed: " +
                            iioErrorMessage(static_cast<int>(sampleSize)));
        }
        impl_->sampleStrideBytes = static_cast<std::size_t>(sampleSize);

        const auto bufferSamples = std::max<std::size_t>(1024U, config_.bufferSamples);
        const auto streamBlockCount = std::max<std::size_t>(1U, config_.streamBlockCount);
        impl_->requestedBlockSamples = bufferSamples;
        impl_->rxBuffer = iio_device_get_buffer(impl_->rx, 0);   // Original style
        const auto bufferError = iio_err(impl_->rxBuffer);
        if (!impl_->rxBuffer || bufferError != 0) {
            impl_->rxBuffer = nullptr;
            close();
            return makeStatus(
                    SampleSourceError::OpenFailed,
                    "iio_device_get_buffer failed: " +
                            (bufferError == 0 ? std::string("unknown error")
                                              : iioErrorMessage(bufferError)));
        }

        impl_->stream = iio_buffer_create_stream(
                impl_->rxBuffer,
                streamBlockCount,
                bufferSamples,
                impl_->channelsMask);
        const auto streamError = iio_err(impl_->stream);
        if (!impl_->stream || streamError != 0) {
            impl_->stream = nullptr;
            close();
            return makeStatus(
                    SampleSourceError::OpenFailed,
                    "iio_buffer_create_stream failed: " +
                            (streamError == 0 ? std::string("unknown error")
                                              : iioErrorMessage(streamError)));
        }

        std::ostringstream message;
        message << "Opened (CS" << (useEightBitSamples ? 8 : 16)
                << ", buffer_samples=" << bufferSamples
                << ", stream_blocks=" << streamBlockCount
                << ", configured_iq_stride_bytes=" << impl_->sampleStrideBytes
                << ", channel_mode=" << (useEightBitSamples ? "tezuka_single_voltage0_packed_iq" : "voltage0_voltage1")
                << ")";
        return makeStatus(SampleSourceError::None, message.str());
#endif
    }

    void PlutoSource::close() {
#ifdef SDRVIDEOSCANNER_HAVE_LIBIIO
        if (impl_->stream) {
            iio_stream_destroy(impl_->stream);
            impl_->stream = nullptr;
        }
        impl_->rxBuffer = nullptr;
        if (impl_->channelsMask) {
            iio_channels_mask_destroy(impl_->channelsMask);
            impl_->channelsMask = nullptr;
        }
        impl_->currentBlock = nullptr;
        impl_->currentPtr = nullptr;
        impl_->currentEnd = nullptr;
        impl_->currentStepBytes = 0;
        impl_->sampleStrideBytes = 0;
        impl_->requestedBlockSamples = 0;
        impl_->rxI = impl_->rxQ = nullptr;
        impl_->rx = impl_->phy = nullptr;
        if (impl_->context) {
            iio_context_destroy(impl_->context);
            impl_->context = nullptr;
        }
        if (impl_->contextLog) {
            std::fclose(impl_->contextLog);
            impl_->contextLog = nullptr;
        }
#endif
    }

    bool PlutoSource::isOpen() const {
#ifdef SDRVIDEOSCANNER_HAVE_LIBIIO
        return impl_->context != nullptr && impl_->stream != nullptr;
#else
        return false;
#endif
    }

    SampleFormat PlutoSource::format() const {
        return format_;
    }

    ReadResult PlutoSource::read(SampleBuffer& buffer, std::size_t maxSamples) {
        buffer.clear();
        if (!isOpen()) {
            return makeReadResult(0, false, SampleSourceError::NotOpen, "Not open");
        }

        if (maxSamples == 0) {
            return makeReadResult(0, false, SampleSourceError::None, {});
        }

        if (impl_->sampleStrideBytes == 0) {
            return makeReadResult(0, false, SampleSourceError::ReadFailed, "Invalid Pluto sample stride");
        }

        const bool isCs8 = (config_.sampleEncoding == SampleEncoding::Cs8);
        buffer.resizeSamples(maxSamples);

        std::size_t samplesCopied = 0;
        while (samplesCopied < maxSamples) {
            if (impl_->currentPtr == nullptr ||
                impl_->currentEnd == nullptr ||
                impl_->currentPtr + impl_->currentStepBytes > impl_->currentEnd) {
                impl_->currentBlock = iio_stream_get_next_block(impl_->stream);
                const auto blockError = iio_err(impl_->currentBlock);
                if (!impl_->currentBlock || blockError != 0) {
                    buffer.resizeSamples(samplesCopied);
                    return makeReadResult(
                            samplesCopied,
                            false,
                            SampleSourceError::ReadFailed,
                            "iio_stream_get_next_block failed: " +
                                    (blockError == 0 ? std::string("unknown error")
                                                     : iioErrorMessage(blockError)));
                }

                impl_->currentPtr = static_cast<const char*>(iio_block_first(impl_->currentBlock, impl_->rxI));
                impl_->currentEnd = static_cast<const char*>(iio_block_end(impl_->currentBlock));
                if (!impl_->currentPtr || !impl_->currentEnd || impl_->currentPtr >= impl_->currentEnd) {
                    buffer.resizeSamples(samplesCopied);
                    return makeReadResult(
                            samplesCopied,
                            false,
                            SampleSourceError::ReadFailed,
                            "Empty block");
                }

                const auto payloadBytes = static_cast<std::size_t>(impl_->currentEnd - impl_->currentPtr);
                const auto layout = choosePayloadLayout(
                        isCs8,
                        impl_->sampleStrideBytes,
                        payloadBytes,
                        impl_->requestedBlockSamples);
                impl_->currentStepBytes = layout.bytesPerIqPair;
                if (impl_->currentStepBytes == 0 || payloadBytes < impl_->currentStepBytes) {
                    buffer.resizeSamples(samplesCopied);
                    impl_->currentPtr = nullptr;
                    impl_->currentEnd = nullptr;
                    impl_->currentStepBytes = 0;
                    return makeReadResult(
                            samplesCopied,
                            false,
                            SampleSourceError::ReadFailed,
                            "Unsupported Pluto IQ payload layout");
                }
            }

            const auto availableInBlock =
                    static_cast<std::size_t>(impl_->currentEnd - impl_->currentPtr) /
                    impl_->currentStepBytes;
            const auto samplesToCopy = std::min(maxSamples - samplesCopied, availableInBlock);
            if (samplesToCopy == 0) {
                impl_->currentPtr = nullptr;
                impl_->currentEnd = nullptr;
                impl_->currentStepBytes = 0;
                continue;
            }

            auto* output = buffer.data() + (samplesCopied * SampleBuffer::kValuesPerIqSample);
            auto* ptr = impl_->currentPtr;
            if (isCs8) {
                for (std::size_t i = 0; i < samplesToCopy; ++i, ptr += impl_->currentStepBytes) {
                    const auto* iq = reinterpret_cast<const std::int8_t*>(ptr);
                    output[i * 2] = static_cast<std::int16_t>(iq[0]) << 8;
                    output[i * 2 + 1] = static_cast<std::int16_t>(iq[1]) << 8;
                }
            } else if (impl_->currentStepBytes == kCs16BytesPerIqPair) {
                std::copy_n(
                        reinterpret_cast<const std::int16_t*>(ptr),
                        samplesToCopy * SampleBuffer::kValuesPerIqSample,
                        output);
                ptr += samplesToCopy * impl_->currentStepBytes;
            } else {
                for (std::size_t i = 0; i < samplesToCopy; ++i, ptr += impl_->currentStepBytes) {
                    const auto* iq = reinterpret_cast<const std::int16_t*>(ptr);
                    output[i * 2] = iq[0];
                    output[i * 2 + 1] = iq[1];
                }
            }
            samplesCopied += samplesToCopy;
            impl_->currentPtr = ptr;
        }

        return makeReadResult(samplesCopied, false, SampleSourceError::None, {});
    }

} // namespace sdr
