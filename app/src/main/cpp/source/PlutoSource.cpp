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
#include <thread>

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

// CRITICAL FOR TEZUKA: Restart iiod to force CS8 mode
        bool restartIiod() {
            system("killall iiod 2>/dev/null || true");
            system("pkill -9 iiod 2>/dev/null || true");
            std::this_thread::sleep_for(std::chrono::milliseconds(800));
            return true;
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

        bool writeDeviceStringAttr(iio_device* device, const char* name, const char* value) {
            if (device == nullptr) return false;
            const auto* attr = iio_device_find_attr(device, name);
            return attr != nullptr && iio_attr_write_string(attr, value) >= 0;
        }

        bool writeOptionalStringAttr(iio_channel* channel, const char* name, const char* value) {
            if (channel == nullptr) return false;
            const auto* attr = iio_channel_find_attr(channel, name);
            return attr != nullptr && iio_attr_write_string(attr, value) >= 0;
        }

        iio_channel* findFirstChannel(iio_device* device, const char* firstName, const char* fallbackName) {
            auto* channel = iio_device_find_channel(device, firstName, false);
            return channel != nullptr ? channel : iio_device_find_channel(device, fallbackName, false);
        }

        bool forceEightBitDataFormat(iio_channel* channel, SampleEncoding encoding) {
            if (!channel) return false;
            const auto* currentFormat = iio_channel_get_data_format(channel);
            if (!currentFormat) return false;

            auto* mutableFormat = const_cast<iio_data_format*>(currentFormat);
            mutableFormat->length = 8;
            mutableFormat->bits = 8;
            mutableFormat->shift = 0;
            mutableFormat->is_signed = (encoding == SampleEncoding::Cs8);
            mutableFormat->is_fully_defined = true;
            if (mutableFormat->repeat == 0) mutableFormat->repeat = 1;
            return true;
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
        std::size_t sampleStrideBytes = 0;
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

        if (useEightBitSamples) {
            restartIiod();
        }

        auto* logFile = tmpfile();
        iio_context_params params = {};
        params.err = logFile;
        params.out = logFile;
        params.log_level = LEVEL_DEBUG;
        params.stderr_level = LEVEL_DEBUG;
        params.timestamp_level = LEVEL_NOLOG;

        impl_->context = iio_create_context(logFile == nullptr ? nullptr : &params, config_.uri.c_str());
        const auto contextError = iio_err(impl_->context);
        if (!impl_->context || contextError != 0) {
            const auto libiioLog = readFileAndClose(logFile);
            const auto message = iioContextErrorMessage(config_.uri, contextError, libiioLog);
            impl_->context = nullptr;
            return makeStatus(SampleSourceError::OpenFailed, "iio_create_context failed: " + message);
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

        if (!impl_->rxI || !impl_->rxQ) {
            close();
            return makeStatus(SampleSourceError::OpenFailed, "I/Q channels not found");
        }

        if (useEightBitSamples) {
            writeDeviceStringAttr(impl_->rx, "format", "s8/8");
            forceEightBitDataFormat(impl_->rxI, config_.sampleEncoding);
            forceEightBitDataFormat(impl_->rxQ, config_.sampleEncoding);
            writeOptionalStringAttr(impl_->rxI, "raw", "1");
            writeOptionalStringAttr(impl_->rxQ, "raw", "1");
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
        iio_channel_enable(impl_->rxQ, impl_->channelsMask);

        const auto sampleSize = iio_device_get_sample_size(impl_->rx, impl_->channelsMask);
        impl_->sampleStrideBytes = static_cast<std::size_t>(sampleSize);

        const auto bufferSamples = std::max<std::size_t>(1024U, config_.bufferSamples);
        const auto streamBlockCount = std::max<std::size_t>(1U, config_.streamBlockCount);
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
                << ", stream_blocks=" << streamBlockCount << ")";
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
        impl_->sampleStrideBytes = 0;
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

        const auto* block = iio_stream_get_next_block(impl_->stream);
        const auto blockError = iio_err(block);
        if (!block || blockError != 0) {
            return makeReadResult(
                    0,
                    false,
                    SampleSourceError::ReadFailed,
                    "iio_stream_get_next_block failed: " +
                            (blockError == 0 ? std::string("unknown error")
                                             : iioErrorMessage(blockError)));
        }

        const auto* ptr = static_cast<const char*>(iio_block_first(block, impl_->rxI));
        const auto* end = static_cast<const char*>(iio_block_end(block));
        if (!ptr || !end || ptr >= end) {
            return makeReadResult(0, false, SampleSourceError::ReadFailed, "Empty block");
        }

        std::size_t step = impl_->sampleStrideBytes;
        if (step == 0) {
            return makeReadResult(0, false, SampleSourceError::ReadFailed, "Invalid Pluto sample stride");
        }

        const bool isCs8 = (config_.sampleEncoding == SampleEncoding::Cs8);
        bool cs8FromCs16Payload = false;
        if (isCs8) {
            constexpr auto kCs16SampleBytes =
                    sizeof(std::int16_t) * SampleBuffer::kValuesPerIqSample;
            step = kCs16SampleBytes;
            cs8FromCs16Payload = true;
        }

        const auto available = static_cast<std::size_t>((end - ptr) / step);
        const auto samplesToCopy = std::min(maxSamples, available);

        buffer.resizeSamples(samplesToCopy);

        for (std::size_t i = 0; i < samplesToCopy && ptr < end; ++i, ptr += step) {
            if (isCs8) {
                if (cs8FromCs16Payload) {
                    const auto* iq = reinterpret_cast<const std::int8_t*>(ptr);
                    buffer.data()[i * 2]     = static_cast<std::int16_t>(iq[1]) << 8;
                    buffer.data()[i * 2 + 1] = static_cast<std::int16_t>(iq[3]) << 8;
                } else {
                    const auto* iq = reinterpret_cast<const std::int8_t*>(ptr);
                    buffer.data()[i * 2]     = static_cast<std::int16_t>(iq[0]) << 8;
                    buffer.data()[i * 2 + 1] = static_cast<std::int16_t>(iq[1]) << 8;
                }
            } else {
                const auto* iq = reinterpret_cast<const std::int16_t*>(ptr);
                buffer.data()[i * 2]     = iq[0];
                buffer.data()[i * 2 + 1] = iq[1];
            }
        }

        return makeReadResult(samplesToCopy, false, SampleSourceError::None, {});
    }

} // namespace sdr
