#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "source/IQMetadata.h"
#include "source/ISampleSource.h"
#include "source/MaiaSource.h"
#include "source/PlutoSource.h"
#include "source/PlutoWebSocketSource.h"
#include "source/analyzer/SpectrumAnalyzer.h"
#include "source/file/FileSource.h"
#include "source/video/AnalogVideoDecoder.h"

#ifdef SDRVIDEOSCANNER_HAVE_LIBIIO
#if __has_include(<iio/iio.h>)
#include <iio/iio.h>
#else
#include <iio.h>
#endif
#endif

namespace {

constexpr std::size_t kDiagnosticBlockSamples = 4096;
constexpr std::size_t kFilePlaybackReadBlockSamples = 262144;
constexpr std::size_t kLivePlaybackReadBlockSamples = 32768;
constexpr std::size_t kPlutoLivePlaybackBufferSamples = 262144;
constexpr std::size_t kPlutoLiveSpectrumBufferSamples = 1024;
constexpr std::size_t kPlutoLiveStreamBlockCount = 4;
constexpr std::size_t kPlutoCaptureBufferSamples = 32768;
constexpr std::size_t kPlutoCaptureStreamBlockCount = 4;
constexpr std::size_t kPlutoMetricsReadBlockSamples = 32768;
constexpr std::size_t kPlutoMetricsStreamBlockCount = 2;
constexpr std::uint64_t kDefaultPlaybackAnalysisRateHz = 1500000;
constexpr std::uint64_t kWebSocketCs8PlaybackAnalysisRateHz = 10000000;
constexpr double kDefaultPlaybackVideoCutoffHz = 5000000.0;
constexpr double kWebSocketCs8PlaybackVideoCutoffHz = 2200000.0;
constexpr double kCs16FullScale = 32768.0;

std::mutex gLastNativeErrorMutex;
std::string gLastNativeError;

void setLastNativeError(std::string message) {
    std::lock_guard<std::mutex> lock(gLastNativeErrorMutex);
    gLastNativeError = std::move(message);
}

std::string consumeLastNativeError() {
    std::lock_guard<std::mutex> lock(gLastNativeErrorMutex);
    auto message = gLastNativeError;
    gLastNativeError.clear();
    return message;
}

struct AnalogPlaybackSession {
    std::unique_ptr<sdr::ISampleSource> source;
    std::unique_ptr<sdr::AnalogVideoDecoder> decoder;
    std::uint64_t sampleRateHz = 0;
    sdr::VideoStandard standard = sdr::VideoStandard::NTSC_525_30FPS;
    double playbackFrameRateHz = 0.0;
    std::uint64_t frameIndex = 0;
    std::uint64_t totalSampleCount = 0;
    bool loopAtEndOfStream = false;
    std::string sessionKind = "unknown";
};

struct SpectrumViewSession {
    explicit SpectrumViewSession(std::size_t fftSize)
            : analyzer(fftSize),
              waterfallPixels(waterfallWidth * waterfallHeight, 0),
              spectrumBinsDbfs(fftSize) {}

    std::unique_ptr<sdr::ISampleSource> source;
    sdr::SpectrumAnalyzer analyzer;
    sdr::SampleBuffer buffer;
    std::vector<float> spectrumBinsDbfs;
    std::vector<std::uint8_t> waterfallPixels;
    std::uint64_t sampleRateHz = 0;
    std::uint64_t centerFrequencyHz = 0;
    std::uint64_t frameIndex = 0;
    std::string sessionKind = "unknown";

    static constexpr std::uint32_t spectrumWidth = 512;
    static constexpr std::uint32_t spectrumHeight = 96;
    static constexpr std::uint32_t waterfallWidth = spectrumWidth;
    static constexpr std::uint32_t waterfallHeight = 160;
};

std::uint64_t fileSizeBytes(const std::string& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        return 0;
    }

    const auto size = file.tellg();
    if (size < 0) {
        return 0;
    }

    return static_cast<std::uint64_t>(size);
}

std::uint64_t bytesPerIqSample(sdr::SampleEncoding encoding) {
    return encoding == sdr::SampleEncoding::Cs8
            ? sizeof(std::int8_t) * 2U
            : sizeof(std::int16_t) * 2U;
}

sdr::SampleEncoding sampleEncodingFromJInt(jint value) {
    if (value == 1) {
        return sdr::SampleEncoding::Cs8;
    }
    return sdr::SampleEncoding::Cs16;
}

sdr::SampleEncoding sampleEncodingFromMetadata(const std::string& format) {
    if (format == "CS8" || format == "cs8") {
        return sdr::SampleEncoding::Cs8;
    }
    return sdr::SampleEncoding::Cs16;
}

const char* sampleEncodingName(sdr::SampleEncoding encoding) {
    if (encoding == sdr::SampleEncoding::Cs8) {
        return "CS8";
    }
    return "CS16";
}

bool isLivePlaybackSourceKind(const std::string& sessionKind) {
    return sessionKind != "file";
}

std::string errorDiagnostic(const std::string& path, const std::string& message) {
    std::ostringstream diagnostic;
    diagnostic << "CS16 diagnostic\n"
               << "path: " << path << "\n"
               << "error: " << message;
    return diagnostic.str();
}

std::string pathFromJString(JNIEnv* env, jstring path, std::string& error) {
    if (path == nullptr) {
        error = "path is null";
        return {};
    }

    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    if (rawPath == nullptr) {
        error = "failed to read path from JVM";
        return {};
    }

    std::string filePath(rawPath);
    env->ReleaseStringUTFChars(path, rawPath);
    return filePath;
}

std::string stringFromJString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* rawValue = env->GetStringUTFChars(value, nullptr);
    if (rawValue == nullptr) {
        return {};
    }

    std::string result(rawValue);
    env->ReleaseStringUTFChars(value, rawValue);
    return result;
}

std::uint64_t positiveJLongOrZero(jlong value) {
    return value > 0 ? static_cast<std::uint64_t>(value) : 0;
}

const char* optionalText(const std::string& value) {
    return value.empty() ? "unavailable" : value.c_str();
}

void appendMetadataDiagnostic(std::ostringstream& diagnostic, const sdr::IQMetadata& metadata) {
    diagnostic << "\nmetadata sidecar: ";
    if (!metadata.sidecarFound) {
        diagnostic << "missing"
                   << "\nmetadata path: " << metadata.sidecarPath;
        return;
    }

    diagnostic << (metadata.loaded ? "loaded" : "not loaded")
               << "\nmetadata path: " << metadata.sidecarPath;

    if (!metadata.parseError.empty()) {
        diagnostic << "\nmetadata parse error: " << metadata.parseError;
        return;
    }

    diagnostic << "\nmetadata format: " << optionalText(metadata.format)
               << "\nmetadata endianness: " << optionalText(metadata.endianness);

    if (metadata.hasSampleRateHz) {
        diagnostic << "\nmetadata sample_rate_hz: " << metadata.sampleRateHz;
    } else {
        diagnostic << "\nmetadata sample_rate_hz: unavailable";
    }

    if (metadata.hasCenterFrequencyHz) {
        diagnostic << "\nmetadata center_frequency_hz: " << metadata.centerFrequencyHz;
    } else {
        diagnostic << "\nmetadata center_frequency_hz: unavailable";
    }

    if (metadata.hasRfBandwidthHz) {
        diagnostic << "\nmetadata rf_bandwidth_hz: " << metadata.rfBandwidthHz;
    } else {
        diagnostic << "\nmetadata rf_bandwidth_hz: unavailable";
    }

    if (metadata.hasGainDb) {
        diagnostic << "\nmetadata gain_db: " << metadata.gainDb;
    } else {
        diagnostic << "\nmetadata gain_db: unavailable";
    }

    diagnostic << "\nmetadata source: " << optionalText(metadata.source)
               << "\nmetadata device: " << optionalText(metadata.device);

    if (metadata.hasDurationSec) {
        diagnostic << "\nmetadata duration_sec: " << metadata.durationSec;
    } else {
        diagnostic << "\nmetadata duration_sec: unavailable";
    }

    if (!metadata.format.empty() && metadata.format != "CS16" && metadata.format != "cs16") {
        diagnostic << "\nwarning: metadata format is not CS16; FileSource still reads CS16";
    }
    if (!metadata.endianness.empty() &&
        metadata.endianness != "little" &&
        metadata.endianness != "Little" &&
        metadata.endianness != "LITTLE") {
        diagnostic << "\nwarning: metadata endianness is not little; FileSource still reads little-endian CS16";
    }
}

std::string diagnoseFirstBlock(const std::string& filePath) {
    const auto sizeBytes = fileSizeBytes(filePath);
    const auto iqSampleCount = sizeBytes / bytesPerIqSample(sdr::SampleEncoding::Cs16);
    const auto trailingBytes = sizeBytes % bytesPerIqSample(sdr::SampleEncoding::Cs16);

    sdr::FileSource source(filePath, 0);
    const auto openStatus = source.open();
    if (!openStatus.ok()) {
        return errorDiagnostic(filePath, openStatus.message);
    }

    sdr::SampleBuffer buffer;
    const auto readResult = source.read(buffer, kDiagnosticBlockSamples);
    source.close();

    if (!readResult.ok()) {
        return errorDiagnostic(filePath, readResult.message);
    }

    std::ostringstream diagnostic;
    diagnostic << "CS16 diagnostic\n"
               << "path: " << filePath << "\n"
               << "file size bytes: " << sizeBytes << "\n"
               << "IQ sample count: " << iqSampleCount;

    if (trailingBytes != 0) {
        diagnostic << "\nwarning: " << trailingBytes
                   << " trailing byte(s), file is not aligned to CS16 I/Q pairs";
    }

    diagnostic << "\nfirst block samples read: " << readResult.samplesRead;

    if (readResult.samplesRead == 0) {
        diagnostic << "\nempty file or no complete I/Q samples available";
        return diagnostic.str();
    }

    std::int16_t minI = std::numeric_limits<std::int16_t>::max();
    std::int16_t maxI = std::numeric_limits<std::int16_t>::min();
    std::int16_t minQ = std::numeric_limits<std::int16_t>::max();
    std::int16_t maxQ = std::numeric_limits<std::int16_t>::min();

    for (std::size_t sampleIndex = 0; sampleIndex < readResult.samplesRead; ++sampleIndex) {
        const auto i = buffer.i(sampleIndex);
        const auto q = buffer.q(sampleIndex);
        minI = std::min(minI, i);
        maxI = std::max(maxI, i);
        minQ = std::min(minQ, q);
        maxQ = std::max(maxQ, q);
    }

    diagnostic << "\nfirst I/Q: " << buffer.i(0) << ", " << buffer.q(0)
               << "\nmin/max I: " << minI << " / " << maxI
               << "\nmin/max Q: " << minQ << " / " << maxQ;

    return diagnostic.str();
}

std::string diagnoseBlocks(const std::string& filePath) {
    sdr::FileSource source(filePath, 0);
    const auto openStatus = source.open();
    if (!openStatus.ok()) {
        return errorDiagnostic(filePath, openStatus.message);
    }

    sdr::SampleBuffer buffer;
    std::size_t blocksRead = 0;
    std::uint64_t totalSamplesRead = 0;
    bool eofReached = false;
    double powerSum = 0.0;
    std::int16_t minI = std::numeric_limits<std::int16_t>::max();
    std::int16_t maxI = std::numeric_limits<std::int16_t>::min();
    std::int16_t minQ = std::numeric_limits<std::int16_t>::max();
    std::int16_t maxQ = std::numeric_limits<std::int16_t>::min();

    while (true) {
        const auto readResult = source.read(buffer, kDiagnosticBlockSamples);
        eofReached = readResult.endOfStream;

        if (!readResult.ok()) {
            source.close();
            return errorDiagnostic(filePath, readResult.message);
        }

        if (readResult.samplesRead == 0) {
            break;
        }

        ++blocksRead;
        totalSamplesRead += readResult.samplesRead;

        for (std::size_t sampleIndex = 0; sampleIndex < readResult.samplesRead; ++sampleIndex) {
            const auto i = buffer.i(sampleIndex);
            const auto q = buffer.q(sampleIndex);
            minI = std::min(minI, i);
            maxI = std::max(maxI, i);
            minQ = std::min(minQ, q);
            maxQ = std::max(maxQ, q);

            const double normalizedI = static_cast<double>(i) / kCs16FullScale;
            const double normalizedQ = static_cast<double>(q) / kCs16FullScale;
            powerSum += (normalizedI * normalizedI + normalizedQ * normalizedQ) * 0.5;
        }

        if (readResult.endOfStream) {
            break;
        }
    }

    source.close();

    std::ostringstream diagnostic;
    diagnostic << "CS16 block diagnostic\n"
               << "path: " << filePath << "\n"
               << "blocks read: " << blocksRead << "\n"
               << "total IQ samples read: " << totalSamplesRead << "\n"
               << "EOF reached: " << (eofReached ? "yes" : "no");

    if (totalSamplesRead == 0) {
        diagnostic << "\nempty file or no complete I/Q samples available";
        return diagnostic.str();
    }

    diagnostic << "\nglobal min/max I: " << minI << " / " << maxI
               << "\nglobal min/max Q: " << minQ << " / " << maxQ;

    const double averagePower = powerSum / static_cast<double>(totalSamplesRead);
    if (averagePower <= 0.0) {
        diagnostic << "\naverage power: -inf dBFS";
    } else {
        diagnostic << "\naverage power: " << (10.0 * std::log10(averagePower)) << " dBFS";
    }

    return diagnostic.str();
}

std::size_t chooseFftSize(std::size_t sampleCount) {
    if (sampleCount >= 4096) {
        return 4096;
    }
    if (sampleCount >= 2048) {
        return 2048;
    }
    if (sampleCount >= 1024) {
        return 1024;
    }
    return 0;
}

std::string diagnoseSpectrum(const std::string& filePath, const sdr::IQMetadata& metadata) {
    const auto sourceSampleRate =
            metadata.hasSampleRateHz &&
                            metadata.sampleRateHz <=
                                    static_cast<std::uint64_t>(
                                            std::numeric_limits<std::uint32_t>::max())
                    ? static_cast<std::uint32_t>(metadata.sampleRateHz)
                    : 0;
    const auto encoding = sampleEncodingFromMetadata(metadata.format);
    sdr::FileSource source(filePath, sourceSampleRate, encoding);
    const auto openStatus = source.open();
    if (!openStatus.ok()) {
        return errorDiagnostic(filePath, openStatus.message);
    }

    sdr::SampleBuffer buffer;
    const auto readResult = source.read(buffer, kDiagnosticBlockSamples);
    source.close();

    if (!readResult.ok()) {
        return errorDiagnostic(filePath, readResult.message);
    }

    const auto fftSize = chooseFftSize(readResult.samplesRead);
    std::ostringstream diagnostic;
    diagnostic << "CS16 spectrum diagnostic\n"
               << "path: " << filePath << "\n"
               << "sample encoding: " << sampleEncodingName(encoding) << "\n"
               << "samples read: " << readResult.samplesRead;
    appendMetadataDiagnostic(diagnostic, metadata);

    if (fftSize == 0) {
        diagnostic << "\nerror: at least 1024 IQ samples are required for spectrum analysis";
        return diagnostic.str();
    }

    sdr::SpectrumAnalyzer analyzer(fftSize);
    const auto stats = analyzer.analyze(
            buffer,
            metadata.hasSampleRateHz ? metadata.sampleRateHz : 0,
            metadata.hasCenterFrequencyHz ? metadata.centerFrequencyHz : 0,
            metadata.hasCenterFrequencyHz);

    diagnostic << "\nFFT size: " << stats.fftSize
               << "\naverage noise floor: " << stats.averageNoiseFloorDbfs << " dBFS"
               << "\npeak bin index: " << stats.peakBinIndex
               << "\npeak level: " << stats.peakLevelDbfs << " dBFS"
               << "\nestimated occupied bandwidth: " << stats.occupiedBandwidthBins << " bins";

    if (stats.hasSampleRate) {
        diagnostic << "\nbin width Hz: " << stats.binWidthHz
                   << "\nestimated occupied bandwidth Hz: " << stats.occupiedBandwidthHz
                   << "\npeak frequency offset Hz: " << stats.peakFrequencyOffsetHz;
        if (stats.hasCenterFrequency) {
            diagnostic << "\npeak RF frequency Hz: " << stats.peakRfFrequencyHz;
        } else {
            diagnostic << "\npeak RF frequency Hz: unavailable; center_frequency_hz not provided";
        }
    } else {
        diagnostic << "\nbin width Hz: unavailable; sample_rate_hz not provided"
                   << "\nestimated occupied bandwidth Hz: unavailable; sample_rate_hz not provided"
                   << "\npeak frequency offset Hz: unavailable; sample_rate_hz not provided"
                   << "\npeak RF frequency Hz: unavailable; sample_rate_hz not provided";
    }

    return diagnostic.str();
}

void writeLittleEndianUint32(std::uint32_t value, std::vector<std::uint8_t>& output) {
    output.push_back(static_cast<std::uint8_t>(value & 0xFFU));
    output.push_back(static_cast<std::uint8_t>((value >> 8U) & 0xFFU));
    output.push_back(static_cast<std::uint8_t>((value >> 16U) & 0xFFU));
    output.push_back(static_cast<std::uint8_t>((value >> 24U) & 0xFFU));
}

std::string videoFrameDiagnostic(const sdr::VideoFrame& frame) {
    std::ostringstream diagnostic;
    diagnostic << "Analog FPV video frame decoded\n"
               << "selected standard: " << optionalText(frame.selectedStandard) << "\n"
               << "line_rate_hz: " << frame.lineRateHz << "\n"
               << "samples_per_line: " << frame.samplesPerLine << "\n"
               << "total_lines: " << frame.totalLines << "\n"
               << "visible_lines: " << frame.visibleLines << "\n"
               << "sync_locked: " << (frame.syncLocked ? "yes" : "no") << "\n"
               << "detected_syncs: " << frame.detectedSyncCount << "\n"
               << "detected_frame_sync_edges: " << frame.detectedFrameSyncCount << "\n"
               << "sync_polarity: " << (frame.syncIsHigh ? "high" : "low") << "\n"
               << "sync_threshold: " << static_cast<int>(frame.syncThreshold) << "\n"
               << "sync_score: " << frame.syncScore << "\n"
               << "line_stability_score: " << frame.lineStabilityScore << "\n"
               << "double_image_score: " << frame.doubleImageScore << "\n"
               << "assembly_path: " << optionalText(frame.assemblyPath) << "\n"
               << "frame: " << frame.width << "x" << frame.height << "\n"
               << "decoder: " << frame.message;
    return diagnostic.str();
}

std::uint8_t pixelPercentile(
        const std::array<std::uint32_t, 256>& histogram,
        std::size_t sampleCount,
        double fraction) {
    if (sampleCount == 0) {
        return 0;
    }

    const auto target = static_cast<std::size_t>(
            std::clamp(fraction, 0.0, 1.0) * static_cast<double>(sampleCount - 1U));
    std::size_t cumulative = 0;
    for (std::size_t value = 0; value < histogram.size(); ++value) {
        cumulative += histogram[value];
        if (cumulative > target) {
            return static_cast<std::uint8_t>(value);
        }
    }
    return 255;
}

std::vector<std::uint8_t> displayLeveledPixels(
        const sdr::VideoFrame& frame,
        std::uint8_t& blackLevel,
        std::uint8_t& whiteLevel) {
    blackLevel = 0;
    whiteLevel = 255;
    if (frame.pixels.empty()) {
        return {};
    }

    std::array<std::uint32_t, 256> histogram{};
    for (const auto pixel : frame.pixels) {
        ++histogram[pixel];
    }

    blackLevel = pixelPercentile(histogram, frame.pixels.size(), 0.05);
    whiteLevel = pixelPercentile(histogram, frame.pixels.size(), 0.995);
    if (whiteLevel <= blackLevel || static_cast<int>(whiteLevel) - static_cast<int>(blackLevel) < 24) {
        return frame.pixels;
    }

    constexpr float kOutputBlack = 8.0F;
    constexpr float kOutputWhite = 246.0F;
    const float scale = (kOutputWhite - kOutputBlack) /
            static_cast<float>(whiteLevel - blackLevel);

    std::vector<std::uint8_t> adjusted(frame.pixels.size());
    for (std::size_t index = 0; index < frame.pixels.size(); ++index) {
        const float value = (static_cast<float>(frame.pixels[index]) -
                             static_cast<float>(blackLevel)) * scale + kOutputBlack;
        adjusted[index] = static_cast<std::uint8_t>(
                std::clamp(value, 0.0F, 255.0F) + 0.5F);
    }
    return adjusted;
}

jbyteArray frameToJByteArray(JNIEnv* env, const sdr::VideoFrame& frame) {
    if (!frame.valid()) {
        return env->NewByteArray(0);
    }

    std::uint8_t displayBlackLevel = 0;
    std::uint8_t displayWhiteLevel = 255;
    const auto displayPixels = displayLeveledPixels(
            frame,
            displayBlackLevel,
            displayWhiteLevel);

    std::ostringstream diagnostic;
    diagnostic << videoFrameDiagnostic(frame)
               << "\n"
               << "display_black_level: " << static_cast<int>(displayBlackLevel) << "\n"
               << "display_white_level: " << static_cast<int>(displayWhiteLevel);
    const auto diagnosticText = diagnostic.str();
    constexpr std::size_t kHeaderBytes = sizeof(std::uint32_t) * 3;
    const auto packetSize = kHeaderBytes + displayPixels.size() + diagnosticText.size();
    if (packetSize > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return env->NewByteArray(0);
    }

    std::vector<std::uint8_t> packet;
    packet.reserve(packetSize);
    writeLittleEndianUint32(frame.width, packet);
    writeLittleEndianUint32(frame.height, packet);
    writeLittleEndianUint32(static_cast<std::uint32_t>(diagnosticText.size()), packet);
    packet.insert(packet.end(), displayPixels.begin(), displayPixels.end());
    packet.insert(packet.end(), diagnosticText.begin(), diagnosticText.end());

    auto* result = env->NewByteArray(static_cast<jsize>(packet.size()));
    if (result == nullptr) {
        return nullptr;
    }

    env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(packet.size()),
            reinterpret_cast<const jbyte*>(packet.data()));
    return result;
}

std::uint8_t clampByte(int value) {
    return static_cast<std::uint8_t>(std::min(255, std::max(0, value)));
}

std::uint8_t dbfsToPixel(float dbfs) {
    constexpr float minDbfs = -95.0F;
    constexpr float maxDbfs = -15.0F;
    if (!std::isfinite(dbfs)) {
        return 0;
    }
    const float normalized = (dbfs - minDbfs) / (maxDbfs - minDbfs);
    return clampByte(static_cast<int>(std::round(normalized * 255.0F)));
}

float binForColumn(const std::vector<float>& bins, std::uint32_t column, std::uint32_t width) {
    const auto begin = static_cast<std::size_t>(
            (static_cast<std::uint64_t>(column) * bins.size()) / width);
    auto end = static_cast<std::size_t>(
            (static_cast<std::uint64_t>(column + 1U) * bins.size()) / width);
    end = std::max<std::size_t>(begin + 1U, std::min<std::size_t>(end, bins.size()));

    float peak = -std::numeric_limits<float>::infinity();
    for (std::size_t index = begin; index < end; ++index) {
        peak = std::max(peak, bins[index]);
    }
    return peak;
}

void setPixel(
        std::vector<std::uint8_t>& pixels,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t x,
        std::uint32_t y,
        std::uint8_t value) {
    if (x >= width || y >= height) {
        return;
    }
    pixels[static_cast<std::size_t>(y) * width + x] = value;
}

void drawSpectrumPanel(
        SpectrumViewSession& session,
        std::vector<std::uint8_t>& pixels,
        const std::vector<std::uint8_t>& fftRow,
        const sdr::SpectrumStats& stats) {
    constexpr auto width = SpectrumViewSession::spectrumWidth;
    constexpr auto height = SpectrumViewSession::spectrumHeight;

    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const bool horizontalGrid = (y % 24U) == 0U;
            const bool verticalGrid = (x % 64U) == 0U || x == (width / 2U);
            pixels[static_cast<std::size_t>(y) * width + x] =
                    horizontalGrid || verticalGrid ? 28 : 4;
        }
    }

    std::uint32_t previousY = height - 1U;
    for (std::uint32_t x = 0; x < width; ++x) {
        const auto value = fftRow[x];
        const auto y = static_cast<std::uint32_t>(
                (static_cast<std::uint64_t>(255U - value) * (height - 1U)) / 255U);
        const auto startY = std::min(previousY, y);
        const auto endY = std::max(previousY, y);
        for (std::uint32_t lineY = startY; lineY <= endY; ++lineY) {
            setPixel(pixels, width, height, x, lineY, 245);
        }
        if (y + 1U < height) {
            setPixel(pixels, width, height, x, y + 1U, 120);
        }
        previousY = y;
    }

    if (stats.hasSampleRate && session.sampleRateHz != 0) {
        const auto centerX = width / 2U;
        for (std::uint32_t y = 0; y < height; ++y) {
            setPixel(pixels, width, height, centerX, y, 180);
        }
    }
}

void pushWaterfallRow(SpectrumViewSession& session, const std::vector<std::uint8_t>& fftRow) {
    constexpr auto width = SpectrumViewSession::waterfallWidth;
    constexpr auto height = SpectrumViewSession::waterfallHeight;
    auto& waterfall = session.waterfallPixels;
    std::copy_backward(
            waterfall.begin(),
            waterfall.end() - static_cast<std::ptrdiff_t>(width),
            waterfall.end());
    std::copy(fftRow.begin(), fftRow.end(), waterfall.begin());
    (void)height;
}

jbyteArray spectrumFrameToJByteArray(
        JNIEnv* env,
        SpectrumViewSession& session,
        const sdr::SpectrumStats& stats) {
    constexpr auto width = SpectrumViewSession::spectrumWidth;
    constexpr auto spectrumHeight = SpectrumViewSession::spectrumHeight;
    constexpr auto waterfallHeight = SpectrumViewSession::waterfallHeight;
    constexpr auto height = spectrumHeight + waterfallHeight;

    std::vector<std::uint8_t> fftRow(width);
    for (std::uint32_t x = 0; x < width; ++x) {
        fftRow[x] = dbfsToPixel(binForColumn(session.spectrumBinsDbfs, x, width));
    }

    pushWaterfallRow(session, fftRow);

    std::vector<std::uint8_t> pixels(static_cast<std::size_t>(width) * height, 0);
    drawSpectrumPanel(session, pixels, fftRow, stats);
    std::copy(
            session.waterfallPixels.begin(),
            session.waterfallPixels.end(),
            pixels.begin() + static_cast<std::ptrdiff_t>(width * spectrumHeight));

    std::ostringstream diagnostic;
    diagnostic << "FFT / waterfall\n"
               << "source: " << session.sessionKind << "\n"
               << "frame_index: " << session.frameIndex << "\n"
               << "sample_rate_hz: " << session.sampleRateHz << "\n"
               << "center_frequency_hz: " << session.centerFrequencyHz << "\n"
               << "fft_size: " << stats.fftSize << "\n"
               << "bin_width_hz: " << stats.binWidthHz << "\n"
               << "noise_floor_dbfs: " << stats.averageNoiseFloorDbfs << "\n"
               << "peak_level_dbfs: " << stats.peakLevelDbfs << "\n"
               << "peak_offset_hz: " << stats.peakFrequencyOffsetHz << "\n"
               << "occupied_bandwidth_hz: " << stats.occupiedBandwidthHz;

    if (stats.hasCenterFrequency) {
        diagnostic << "\npeak_rf_frequency_hz: " << stats.peakRfFrequencyHz;
    }

    constexpr std::size_t kHeaderBytes = sizeof(std::uint32_t) * 3;
    const auto diagnosticText = diagnostic.str();
    const auto packetSize = kHeaderBytes + pixels.size() + diagnosticText.size();
    if (packetSize > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return env->NewByteArray(0);
    }

    std::vector<std::uint8_t> packet;
    packet.reserve(packetSize);
    writeLittleEndianUint32(width, packet);
    writeLittleEndianUint32(height, packet);
    writeLittleEndianUint32(static_cast<std::uint32_t>(diagnosticText.size()), packet);
    packet.insert(packet.end(), pixels.begin(), pixels.end());
    packet.insert(packet.end(), diagnosticText.begin(), diagnosticText.end());

    auto* result = env->NewByteArray(static_cast<jsize>(packet.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(packet.size()),
            reinterpret_cast<const jbyte*>(packet.data()));
    return result;
}

double standardSelectionScore(const sdr::VideoFrame& frame) {
    const double frameSyncScore = std::min(
            1.0,
            static_cast<double>(frame.detectedFrameSyncCount) / 2.0);
    return (frame.lineStabilityScore * 0.50) +
           (frame.syncScore * 0.35) +
           (frameSyncScore * 0.15);
}

bool shouldSelectNtscForAuto(
        const sdr::VideoFrame& palFrame,
        const sdr::VideoFrame& ntscFrame) {
    const double palScore = standardSelectionScore(palFrame);
    const double ntscScore = standardSelectionScore(ntscFrame);
    constexpr double kPalMustWinBy = 0.08;
    return ntscScore + kPalMustWinBy >= palScore;
}

double playbackFrameRateForStandard(sdr::VideoStandard standard) {
    switch (standard) {
        case sdr::VideoStandard::NTSC_525_30FPS:
        case sdr::VideoStandard::PAL625_25FPS:
            return sdr::timingForStandard(standard).frameRateHz;
        case sdr::VideoStandard::AUTO:
        default:
            return 0.0;
    }
}

sdr::AnalogVideoDecoderConfig makePlaybackDecoderConfig(
        std::uint64_t sampleRateHz,
        sdr::VideoStandard standard,
        double playbackFrameRateHz,
        const std::string& sessionKind) {
    sdr::AnalogVideoDecoderConfig config;
    config.sampleRateHz = sampleRateHz;
    const bool isIioCs8Live = sessionKind == "pluto_iio_usb_cs8_live";
    const bool isCs8Live = sessionKind == "pluto_websocket_cs8_live" ||
            isIioCs8Live;
    config.analysisRateHz = isCs8Live
            ? kWebSocketCs8PlaybackAnalysisRateHz
            : kDefaultPlaybackAnalysisRateHz;
    config.cutoffHz = isCs8Live
            ? kWebSocketCs8PlaybackVideoCutoffHz
            : kDefaultPlaybackVideoCutoffHz;
    config.readBlockSamples = isLivePlaybackSourceKind(sessionKind)
            ? kLivePlaybackReadBlockSamples
            : kFilePlaybackReadBlockSamples;
    config.fastFieldPreview = false;
    config.detectFrameSyncInFastPreview = true;
    config.fastPreviewFieldStride = 2U;
    config.liveFrameReadMultiplier = 1.0;
    config.timing = sdr::timingForStandard(standard);
    if (playbackFrameRateHz > 0.0) {
        config.timing.frameRateHz = playbackFrameRateHz;
    }
    return config;
}

void configurePlaybackDecoder(AnalogPlaybackSession& session) {
    auto config = makePlaybackDecoderConfig(
            session.sampleRateHz,
            session.standard,
            session.playbackFrameRateHz,
            session.sessionKind);
    session.decoder = std::make_unique<sdr::AnalogVideoDecoder>(config);
}

sdr::VideoFrame decodeLivePlaybackProbeFrame(
        sdr::ISampleSource& source,
        std::uint64_t sampleRateHz,
        sdr::VideoStandard standard,
        const std::string& sessionKind) {
    auto config = makePlaybackDecoderConfig(
            sampleRateHz,
            standard,
            playbackFrameRateForStandard(standard),
            sessionKind);
    sdr::AnalogVideoDecoder decoder(config);
    return decoder.decodeOneFrame(source);
}

sdr::VideoStandard chooseLivePlaybackStandard(
        sdr::ISampleSource& source,
        std::uint64_t sampleRateHz,
        const std::string& sessionKind) {
    const auto palFrame = decodeLivePlaybackProbeFrame(
            source,
            sampleRateHz,
            sdr::VideoStandard::PAL625_25FPS,
            sessionKind);
    const auto ntscFrame = decodeLivePlaybackProbeFrame(
            source,
            sampleRateHz,
            sdr::VideoStandard::NTSC_525_30FPS,
            sessionKind);

    return shouldSelectNtscForAuto(palFrame, ntscFrame)
            ? sdr::VideoStandard::NTSC_525_30FPS
            : sdr::VideoStandard::PAL625_25FPS;
}

sdr::VideoStandard livePlaybackStandardOrDefault(sdr::VideoStandard requestedStandard) {
    // Match the known-good CS8 live path from feature/cs8_iq_scan.
    // Explicit user selections can still override this later.
    return requestedStandard == sdr::VideoStandard::AUTO
            ? sdr::VideoStandard::NTSC_525_30FPS
            : requestedStandard;
}

sdr::VideoFrame decodeAnalogVideoFrameForStandard(
        const std::string& filePath,
        std::uint64_t sampleRateHz,
        sdr::SampleEncoding sampleEncoding,
        sdr::VideoStandard standard,
        std::uint64_t frameIndex) {
    const auto sourceSampleRate =
            sampleRateHz <=
                            static_cast<std::uint64_t>(
                                    std::numeric_limits<std::uint32_t>::max())
                    ? static_cast<std::uint32_t>(sampleRateHz)
                    : 0;

    sdr::FileSource source(filePath, sourceSampleRate, sampleEncoding);
    const auto openStatus = source.open();
    if (!openStatus.ok()) {
        sdr::VideoFrame frame;
        frame.message = openStatus.message;
        return frame;
    }

    sdr::AnalogVideoDecoderConfig config;
    config.sampleRateHz = sampleRateHz;
    config.timing = sdr::timingForStandard(standard);
    if (frameIndex != 0) {
        const auto startSample = static_cast<std::uint64_t>(
                std::round((static_cast<double>(sampleRateHz) * static_cast<double>(frameIndex)) /
                           config.timing.frameRateHz));
        const auto seekStatus = source.seekSamples(startSample);
        if (!seekStatus.ok()) {
            sdr::VideoFrame frame;
            frame.message = seekStatus.message;
            source.close();
            return frame;
        }
    }

    sdr::AnalogVideoDecoder decoder(config);
    auto frame = decoder.decodeOneFrame(source);
    source.close();
    return frame;
}

sdr::VideoFrame decodeAnalogVideoFrame(
        const std::string& filePath,
        bool hasSampleRateHz,
        std::uint64_t sampleRateHz,
        sdr::SampleEncoding sampleEncoding,
        sdr::VideoStandard requestedStandard,
        std::uint64_t frameIndex) {
    if (!hasSampleRateHz || sampleRateHz == 0) {
        sdr::VideoFrame frame;
        frame.message = "sample_rate_hz metadata is required";
        return frame;
    }

    if (requestedStandard != sdr::VideoStandard::AUTO) {
        return decodeAnalogVideoFrameForStandard(
                filePath,
                sampleRateHz,
                sampleEncoding,
                requestedStandard,
                frameIndex);
    }

    auto palFrame = decodeAnalogVideoFrameForStandard(
            filePath,
            sampleRateHz,
            sampleEncoding,
            sdr::VideoStandard::PAL625_25FPS,
            frameIndex);
    auto ntscFrame = decodeAnalogVideoFrameForStandard(
            filePath,
            sampleRateHz,
            sampleEncoding,
            sdr::VideoStandard::NTSC_525_30FPS,
            frameIndex);

    const bool chooseNtsc = shouldSelectNtscForAuto(palFrame, ntscFrame);
    auto selectedFrame = chooseNtsc ? ntscFrame : palFrame;
    std::ostringstream message;
    message << selectedFrame.message
            << "; AUTO compared PAL625_25FPS sync_score=" << palFrame.syncScore
            << ", line_stability=" << palFrame.lineStabilityScore
            << ", frame_sync_edges=" << palFrame.detectedFrameSyncCount
            << ", standard_score=" << standardSelectionScore(palFrame)
            << " vs NTSC_525_30FPS sync_score=" << ntscFrame.syncScore
            << ", line_stability=" << ntscFrame.lineStabilityScore
            << ", frame_sync_edges=" << ntscFrame.detectedFrameSyncCount
            << ", standard_score=" << standardSelectionScore(ntscFrame);
    selectedFrame.message = message.str();
    return selectedFrame;
}

sdr::VideoStandard choosePlaybackStandard(
        const std::string& filePath,
        std::uint64_t sampleRateHz,
        sdr::SampleEncoding sampleEncoding,
        sdr::VideoStandard requestedStandard) {
    if (requestedStandard != sdr::VideoStandard::AUTO) {
        return requestedStandard;
    }

    const auto palFrame = decodeAnalogVideoFrameForStandard(
            filePath,
            sampleRateHz,
            sampleEncoding,
            sdr::VideoStandard::PAL625_25FPS,
            0);
    const auto ntscFrame = decodeAnalogVideoFrameForStandard(
            filePath,
            sampleRateHz,
            sampleEncoding,
            sdr::VideoStandard::NTSC_525_30FPS,
            0);
    return shouldSelectNtscForAuto(palFrame, ntscFrame)
            ? sdr::VideoStandard::NTSC_525_30FPS
            : sdr::VideoStandard::PAL625_25FPS;
}

AnalogPlaybackSession* createAnalogPlaybackSession(
        const std::string& filePath,
        bool hasSampleRateHz,
        std::uint64_t sampleRateHz,
        sdr::SampleEncoding sampleEncoding,
        sdr::VideoStandard requestedStandard) {
    if (!hasSampleRateHz || sampleRateHz == 0) {
        return nullptr;
    }

    const auto sourceSampleRate =
            sampleRateHz <=
                            static_cast<std::uint64_t>(
                                    std::numeric_limits<std::uint32_t>::max())
                    ? static_cast<std::uint32_t>(sampleRateHz)
                    : 0;

    auto session = std::make_unique<AnalogPlaybackSession>();
    session->sampleRateHz = sampleRateHz;
    session->standard = choosePlaybackStandard(filePath, sampleRateHz, sampleEncoding, requestedStandard);
    session->playbackFrameRateHz = playbackFrameRateForStandard(session->standard);
    session->totalSampleCount = fileSizeBytes(filePath) / bytesPerIqSample(sampleEncoding);
    session->loopAtEndOfStream = true;
    session->sessionKind = "file";
    session->source = std::make_unique<sdr::FileSource>(filePath, sourceSampleRate, sampleEncoding);

    const auto openStatus = session->source->open();
    if (!openStatus.ok()) {
        return nullptr;
    }

    configurePlaybackDecoder(*session);

    return session.release();
}

AnalogPlaybackSession* createPlutoPlaybackSession(
        const std::string& uri,
        std::uint64_t sampleRateHz,
        std::uint64_t centerFrequencyHz,
        std::uint64_t rfBandwidthHz,
        double gainDb,
        sdr::SampleEncoding sampleEncoding,
        std::int64_t loOffsetHz,
        bool hardwareIqCorrection,
        bool hardwareBbdcCorrection,
        bool hardwareRfdcCorrection,
        sdr::VideoStandard requestedStandard) {
    if (sampleRateHz == 0 || centerFrequencyHz == 0 || rfBandwidthHz == 0) {
        return nullptr;
    }

    sdr::PlutoSourceConfig config;
    config.uri = uri.empty() ? "usb:" : uri;
    config.sampleRateHz = sampleRateHz;
    config.centerFrequencyHz = centerFrequencyHz;
    config.rfBandwidthHz = rfBandwidthHz;
    config.gainDb = gainDb;
    config.sampleEncoding = sampleEncoding;
    config.loOffsetHz = loOffsetHz;
    config.hardwareIqCorrection = hardwareIqCorrection;
    config.hardwareBbdcCorrection = hardwareBbdcCorrection;
    config.hardwareRfdcCorrection = hardwareRfdcCorrection;
    config.bufferSamples = kPlutoLivePlaybackBufferSamples;
    config.streamBlockCount = kPlutoLiveStreamBlockCount;

    auto source = std::make_unique<sdr::PlutoSource>(config);
    const auto openStatus = source->open();
    if (!openStatus.ok()) {
        setLastNativeError(openStatus.message);
        return nullptr;
    }

    auto session = std::make_unique<AnalogPlaybackSession>();
    session->sampleRateHz = sampleRateHz;
    session->sessionKind = sampleEncoding == sdr::SampleEncoding::Cs8
            ? "pluto_iio_usb_cs8_live"
            : "pluto_usb_live";
    session->standard = livePlaybackStandardOrDefault(requestedStandard);
    session->playbackFrameRateHz = playbackFrameRateForStandard(session->standard);
    session->loopAtEndOfStream = false;
    session->source = std::move(source);
    configurePlaybackDecoder(*session);
    return session.release();
}

SpectrumViewSession* createPlutoSpectrumSession(
        const std::string& uri,
        std::uint64_t sampleRateHz,
        std::uint64_t centerFrequencyHz,
        std::uint64_t rfBandwidthHz,
        double gainDb,
        sdr::SampleEncoding sampleEncoding,
        std::int64_t loOffsetHz,
        bool hardwareIqCorrection,
        bool hardwareBbdcCorrection,
        bool hardwareRfdcCorrection) {
    if (sampleRateHz == 0 || centerFrequencyHz == 0 || rfBandwidthHz == 0) {
        return nullptr;
    }

    sdr::PlutoSourceConfig config;
    config.uri = uri.empty() ? "usb:" : uri;
    config.sampleRateHz = sampleRateHz;
    config.centerFrequencyHz = centerFrequencyHz;
    config.rfBandwidthHz = rfBandwidthHz;
    config.gainDb = gainDb;
    config.sampleEncoding = sampleEncoding;
    config.loOffsetHz = loOffsetHz;
    config.hardwareIqCorrection = hardwareIqCorrection;
    config.hardwareBbdcCorrection = hardwareBbdcCorrection;
    config.hardwareRfdcCorrection = hardwareRfdcCorrection;
    config.bufferSamples = kPlutoLiveSpectrumBufferSamples;
    config.streamBlockCount = kPlutoLiveStreamBlockCount;

    auto source = std::make_unique<sdr::PlutoSource>(config);
    const auto openStatus = source->open();
    if (!openStatus.ok()) {
        setLastNativeError(openStatus.message);
        return nullptr;
    }

    auto session = std::make_unique<SpectrumViewSession>(1024);
    session->sampleRateHz = sampleRateHz;
    session->centerFrequencyHz = centerFrequencyHz;
    session->sessionKind = "pluto_usb_live";
    session->source = std::move(source);
    return session.release();
}

std::string probePlutoIqMetrics(
        const std::string& uri,
        std::uint64_t sampleRateHz,
        std::uint64_t centerFrequencyHz,
        std::uint64_t rfBandwidthHz,
        double gainDb,
        sdr::SampleEncoding sampleEncoding,
        std::int64_t loOffsetHz,
        bool hardwareIqCorrection,
        bool hardwareBbdcCorrection,
        bool hardwareRfdcCorrection,
        double windowMs) {
    if (sampleRateHz == 0 || centerFrequencyHz == 0 || rfBandwidthHz == 0 || windowMs <= 0.0) {
        return "Pluto IQ metrics\nstatus: failed\nerror: invalid parameters";
    }

    sdr::PlutoSourceConfig config;
    config.uri = uri.empty() ? "usb:" : uri;
    config.sampleRateHz = sampleRateHz;
    config.centerFrequencyHz = centerFrequencyHz;
    config.rfBandwidthHz = rfBandwidthHz;
    config.gainDb = gainDb;
    config.sampleEncoding = sampleEncoding;
    config.loOffsetHz = loOffsetHz;
    config.hardwareIqCorrection = hardwareIqCorrection;
    config.hardwareBbdcCorrection = hardwareBbdcCorrection;
    config.hardwareRfdcCorrection = hardwareRfdcCorrection;
    config.bufferSamples = kPlutoMetricsReadBlockSamples;
    config.streamBlockCount = kPlutoMetricsStreamBlockCount;

    sdr::PlutoSource source(config);
    const auto openStatus = source.open();
    if (!openStatus.ok()) {
        setLastNativeError(openStatus.message);
        return "Pluto IQ metrics\nstatus: failed\nerror: " + openStatus.message;
    }

    const auto requestedSamples = static_cast<std::size_t>(
            std::max<double>(1.0, (static_cast<double>(sampleRateHz) * windowMs) / 1000.0));
    sdr::SampleBuffer buffer;
    std::vector<float> normalizedPowers;
    normalizedPowers.reserve(std::min<std::size_t>(requestedSamples, sampleRateHz / 10U));

    std::size_t totalSamples = 0;
    double powerSum = 0.0;
    double peakPower = 0.0;
    while (totalSamples < requestedSamples) {
        const auto samplesToRead = std::min<std::size_t>(
                kPlutoMetricsReadBlockSamples,
                requestedSamples - totalSamples);
        const auto readResult = source.read(buffer, samplesToRead);
        if (!readResult.ok()) {
            setLastNativeError(readResult.message);
            return "Pluto IQ metrics\nstatus: failed\nerror: " + readResult.message;
        }
        if (readResult.samplesRead == 0) {
            break;
        }

        for (std::size_t index = 0; index < readResult.samplesRead; ++index) {
            const double i = static_cast<double>(buffer.i(index)) / kCs16FullScale;
            const double q = static_cast<double>(buffer.q(index)) / kCs16FullScale;
            const double power = ((i * i) + (q * q)) * 0.5;
            peakPower = std::max(peakPower, power);
            powerSum += power;
            normalizedPowers.push_back(static_cast<float>(power));
        }
        totalSamples += readResult.samplesRead;
        if (readResult.endOfStream) {
            break;
        }
    }

    if (totalSamples == 0 || normalizedPowers.empty()) {
        return "Pluto IQ metrics\nstatus: failed\nerror: no samples read";
    }

    const auto noiseCount = std::max<std::size_t>(1U, normalizedPowers.size() / 10U);
    std::nth_element(
            normalizedPowers.begin(),
            normalizedPowers.begin() + static_cast<std::ptrdiff_t>(noiseCount - 1U),
            normalizedPowers.end());
    double noisePowerSum = 0.0;
    for (std::size_t index = 0; index < noiseCount; ++index) {
        noisePowerSum += normalizedPowers[index];
    }

    constexpr double kMinPower = 1.0e-20;
    const double rmsPower = powerSum / static_cast<double>(totalSamples);
    const double noisePower = noisePowerSum / static_cast<double>(noiseCount);
    const double peakDbfs = 10.0 * std::log10(std::max(peakPower, kMinPower));
    const double rmsDbfs = 10.0 * std::log10(std::max(rmsPower, kMinPower));
    const double noiseFloorDbfs = 10.0 * std::log10(std::max(noisePower, kMinPower));
    const double snrDb = rmsDbfs - noiseFloorDbfs;

    std::ostringstream diagnostic;
    diagnostic << "Pluto IQ metrics\n"
               << "status: ok\n"
               << "uri: " << config.uri << "\n"
               << "sample_format: " << sampleEncodingName(sampleEncoding) << "\n"
               << "sample_rate_hz: " << sampleRateHz << "\n"
               << "center_frequency_hz: " << centerFrequencyHz << "\n"
               << "rf_bandwidth_hz: " << rfBandwidthHz << "\n"
               << "gain_db: " << gainDb << "\n"
               << "window_ms: " << windowMs << "\n"
               << "samples_read: " << totalSamples << "\n"
               << "peak_dbfs: " << peakDbfs << "\n"
               << "rms_dbfs: " << rmsDbfs << "\n"
               << "noise_floor_dbfs: " << noiseFloorDbfs << "\n"
               << "snr_db: " << snrDb;
    return diagnostic.str();
}

AnalogPlaybackSession* createMaiaPlaybackSession(
        JNIEnv* env,
        const std::string& host,
        std::uint32_t port,
        std::uint64_t sampleRateHz,
        sdr::VideoStandard requestedStandard,
        jobject androidHttpTransport) {
    if (port == 0 || port > 65535U) {
        setLastNativeError("Maia playback requires a TCP port between 1 and 65535");
        return nullptr;
    }
    if (sampleRateHz == 0) {
        setLastNativeError("Maia playback requires a non-zero sample rate");
        return nullptr;
    }
    if (sampleRateHz > static_cast<std::uint64_t>(std::numeric_limits<std::uint32_t>::max())) {
        setLastNativeError("Maia sample rate is too large");
        return nullptr;
    }
    if (androidHttpTransport == nullptr) {
        setLastNativeError("Maia playback requires an Android HTTP transport");
        return nullptr;
    }

    sdr::MaiaSourceConfig config;
    config.host = host.empty() ? "192.168.2.1" : host;
    config.port = static_cast<std::uint16_t>(port);
    config.sampleRateHz = static_cast<std::uint32_t>(sampleRateHz);
    config.bufferSamples = 32768;
    config.androidHttpTransport = env->NewGlobalRef(androidHttpTransport);
    if (config.androidHttpTransport == nullptr) {
        setLastNativeError("Failed to retain Maia Android HTTP transport");
        return nullptr;
    }

    auto source = std::make_unique<sdr::MaiaSource>(config);
    const auto openStatus = source->open();
    if (!openStatus.ok()) {
        setLastNativeError(openStatus.message);
        return nullptr;
    }

    auto session = std::make_unique<AnalogPlaybackSession>();
    session->sampleRateHz = sampleRateHz;
    session->sessionKind = "maia_http_cs8_live";
    session->standard = livePlaybackStandardOrDefault(requestedStandard);
    session->playbackFrameRateHz = playbackFrameRateForStandard(session->standard);
    session->loopAtEndOfStream = false;
    session->source = std::move(source);
    configurePlaybackDecoder(*session);
    return session.release();
}

SpectrumViewSession* createMaiaSpectrumSession(
        JNIEnv* env,
        const std::string& host,
        std::uint32_t port,
        std::uint64_t sampleRateHz,
        std::uint64_t centerFrequencyHz,
        jobject androidHttpTransport) {
    if (port == 0 || port > 65535U) {
        setLastNativeError("Maia spectrum requires a TCP port between 1 and 65535");
        return nullptr;
    }
    if (sampleRateHz == 0) {
        setLastNativeError("Maia spectrum requires a non-zero sample rate");
        return nullptr;
    }
    if (sampleRateHz > static_cast<std::uint64_t>(std::numeric_limits<std::uint32_t>::max())) {
        setLastNativeError("Maia sample rate is too large");
        return nullptr;
    }
    if (androidHttpTransport == nullptr) {
        setLastNativeError("Maia spectrum requires an Android HTTP transport");
        return nullptr;
    }

    sdr::MaiaSourceConfig config;
    config.host = host.empty() ? "192.168.2.1" : host;
    config.port = static_cast<std::uint16_t>(port);
    config.sampleRateHz = static_cast<std::uint32_t>(sampleRateHz);
    config.bufferSamples = 32768;
    config.androidHttpTransport = env->NewGlobalRef(androidHttpTransport);
    if (config.androidHttpTransport == nullptr) {
        setLastNativeError("Failed to retain Maia Android HTTP transport");
        return nullptr;
    }

    auto source = std::make_unique<sdr::MaiaSource>(config);
    const auto openStatus = source->open();
    if (!openStatus.ok()) {
        setLastNativeError(openStatus.message);
        return nullptr;
    }

    auto session = std::make_unique<SpectrumViewSession>(1024);
    session->sampleRateHz = sampleRateHz;
    session->centerFrequencyHz = centerFrequencyHz;
    session->sessionKind = "maia_http_cs8_live";
    session->source = std::move(source);
    return session.release();
}

AnalogPlaybackSession* createPlutoWebSocketPlaybackSession(
        JNIEnv* env,
        const std::string& host,
        std::uint32_t port,
        const std::string& path,
        std::uint64_t sampleRateHz,
        std::uint32_t receiveBufferMs,
        sdr::VideoStandard requestedStandard,
        jobject androidWebSocketTransport) {
    if (port == 0 || port > 65535U) {
        setLastNativeError("Pluto WebSocket playback requires a TCP port between 1 and 65535");
        return nullptr;
    }
    if (path.empty() || path.front() != '/') {
        setLastNativeError("Pluto WebSocket playback requires a path beginning with /");
        return nullptr;
    }
    if (sampleRateHz == 0) {
        setLastNativeError("Pluto WebSocket playback requires a non-zero sample rate");
        return nullptr;
    }
    if (sampleRateHz > static_cast<std::uint64_t>(std::numeric_limits<std::uint32_t>::max())) {
        setLastNativeError("Pluto WebSocket sample rate is too large");
        return nullptr;
    }
    if (androidWebSocketTransport == nullptr) {
        setLastNativeError("Pluto WebSocket playback requires an Android WebSocket transport");
        return nullptr;
    }

    sdr::PlutoWebSocketSourceConfig config;
    config.host = host.empty() ? "192.168.2.1" : host;
    config.port = static_cast<std::uint16_t>(port);
    config.path = path;
    config.sampleRateHz = static_cast<std::uint32_t>(sampleRateHz);
    config.bufferSamples = 32768;
    const auto clampedReceiveBufferMs = std::clamp<std::uint32_t>(receiveBufferMs, 50U, 150U);
    config.receiveBufferSamples = static_cast<std::size_t>(
            (sampleRateHz * clampedReceiveBufferMs) / 1000U);
    config.androidWebSocketTransport = env->NewGlobalRef(androidWebSocketTransport);
    if (config.androidWebSocketTransport == nullptr) {
        setLastNativeError("Failed to retain Pluto Android WebSocket transport");
        return nullptr;
    }

    auto source = std::make_unique<sdr::PlutoWebSocketSource>(config);
    const auto openStatus = source->open();
    if (!openStatus.ok()) {
        setLastNativeError(openStatus.message);
        return nullptr;
    }

    auto session = std::make_unique<AnalogPlaybackSession>();
    session->sampleRateHz = sampleRateHz;
    session->sessionKind = "pluto_websocket_cs8_live";
    session->standard = livePlaybackStandardOrDefault(requestedStandard);
    session->playbackFrameRateHz = playbackFrameRateForStandard(session->standard);
    session->loopAtEndOfStream = false;
    session->source = std::move(source);
    configurePlaybackDecoder(*session);
    return session.release();
}

SpectrumViewSession* createPlutoWebSocketSpectrumSession(
        JNIEnv* env,
        const std::string& host,
        std::uint32_t port,
        const std::string& path,
        std::uint64_t sampleRateHz,
        std::uint64_t centerFrequencyHz,
        std::uint32_t receiveBufferMs,
        jobject androidWebSocketTransport) {
    if (port == 0 || port > 65535U) {
        setLastNativeError("Pluto WebSocket spectrum requires a TCP port between 1 and 65535");
        return nullptr;
    }
    if (path.empty() || path.front() != '/') {
        setLastNativeError("Pluto WebSocket spectrum requires a path beginning with /");
        return nullptr;
    }
    if (sampleRateHz == 0) {
        setLastNativeError("Pluto WebSocket spectrum requires a non-zero sample rate");
        return nullptr;
    }
    if (sampleRateHz > static_cast<std::uint64_t>(std::numeric_limits<std::uint32_t>::max())) {
        setLastNativeError("Pluto WebSocket sample rate is too large");
        return nullptr;
    }
    if (androidWebSocketTransport == nullptr) {
        setLastNativeError("Pluto WebSocket spectrum requires an Android WebSocket transport");
        return nullptr;
    }

    sdr::PlutoWebSocketSourceConfig config;
    config.host = host.empty() ? "192.168.2.1" : host;
    config.port = static_cast<std::uint16_t>(port);
    config.path = path;
    config.sampleRateHz = static_cast<std::uint32_t>(sampleRateHz);
    config.bufferSamples = 32768;
    const auto clampedReceiveBufferMs = std::clamp<std::uint32_t>(receiveBufferMs, 50U, 150U);
    config.receiveBufferSamples = static_cast<std::size_t>(
            (sampleRateHz * clampedReceiveBufferMs) / 1000U);
    config.androidWebSocketTransport = env->NewGlobalRef(androidWebSocketTransport);
    if (config.androidWebSocketTransport == nullptr) {
        setLastNativeError("Failed to retain Pluto Android WebSocket transport");
        return nullptr;
    }

    auto source = std::make_unique<sdr::PlutoWebSocketSource>(config);
    const auto openStatus = source->open();
    if (!openStatus.ok()) {
        setLastNativeError(openStatus.message);
        return nullptr;
    }

    auto session = std::make_unique<SpectrumViewSession>(1024);
    session->sampleRateHz = sampleRateHz;
    session->centerFrequencyHz = centerFrequencyHz;
    session->sessionKind = "pluto_websocket_cs8_live";
    session->source = std::move(source);
    return session.release();
}

sdr::SpectrumStats readNextSpectrumStats(SpectrumViewSession& session, bool& ok) {
    ok = false;
    sdr::SpectrumStats emptyStats;
    if (session.source == nullptr) {
        return emptyStats;
    }

    const auto readResult = session.source->read(session.buffer, session.analyzer.fftSize());
    if (!readResult.ok() || readResult.samplesRead < session.analyzer.fftSize()) {
        return emptyStats;
    }

    auto stats = session.analyzer.analyze(
            session.buffer,
            session.sampleRateHz,
            session.centerFrequencyHz,
            session.centerFrequencyHz != 0);
    session.analyzer.copyShiftedDbfsBins(session.spectrumBinsDbfs);
    ++session.frameIndex;
    ok = true;
    return stats;
}

sdr::VideoFrame decodeNextPlaybackFrame(AnalogPlaybackSession& session) {
    auto frame = session.decoder->decodeOneFrame(*session.source);
    if (!frame.valid() && session.loopAtEndOfStream) {
        auto* fileSource = dynamic_cast<sdr::FileSource*>(session.source.get());
        const auto seekStatus = fileSource != nullptr
                ? fileSource->seekSamples(0)
                : sdr::SourceStatus{sdr::SampleSourceError::InvalidArgument, "playback source is not seekable"};
        session.frameIndex = 0;
        if (seekStatus.ok()) {
            frame = session.decoder->decodeOneFrame(*session.source);
        }
    }

    if (frame.valid()) {
        std::ostringstream message;
        message << frame.message
                << "; playback_session=sequential"
                << "; playback_source=" << session.sessionKind
                << "; playback_frame_rate_hz=" << session.playbackFrameRateHz
                << "; playback_frame_index=" << session.frameIndex;
        if (session.sessionKind == "pluto_usb_live" ||
            session.sessionKind == "pluto_iio_usb_cs8_live" ||
            session.sessionKind == "pluto_websocket_cs8_live") {
            message << "; live_auto_standard="
                    << (session.standard == sdr::VideoStandard::NTSC_525_30FPS ? "NTSC525" : "PAL625");
        }
        frame.message = message.str();
        ++session.frameIndex;
    } else {
        std::ostringstream message;
        message << "decoder returned invalid frame"
                << "; playback_source=" << session.sessionKind
                << "; playback_frame_index=" << session.frameIndex;
        if (!frame.message.empty()) {
            message << "; decoder_error=" << frame.message;
        }
        setLastNativeError(message.str());
    }
    return frame;
}

sdr::VideoStandard videoStandardFromJInt(jint standard) {
    switch (standard) {
        case 1:
            return sdr::VideoStandard::PAL625_25FPS;
        case 2:
            return sdr::VideoStandard::NTSC_525_30FPS;
        case 0:
        default:
            return sdr::VideoStandard::AUTO;
    }
}

std::string capturePlutoIqToFile(
        const std::string& outputPath,
        const std::string& uri,
        std::uint64_t sampleRateHz,
        std::uint64_t centerFrequencyHz,
        std::uint64_t rfBandwidthHz,
        double gainDb,
        sdr::SampleEncoding sampleEncoding,
        std::int64_t loOffsetHz,
        bool hardwareIqCorrection,
        bool hardwareBbdcCorrection,
        bool hardwareRfdcCorrection,
        double durationSec) {
    if (outputPath.empty()) {
        return "Pluto IQ capture\nstatus: failed\nerror: output path is empty";
    }
    if (sampleRateHz == 0 || centerFrequencyHz == 0 || rfBandwidthHz == 0 || durationSec <= 0.0) {
        return "Pluto IQ capture\nstatus: failed\nerror: invalid capture parameters";
    }

    sdr::PlutoSourceConfig config;
    config.uri = uri.empty() ? "usb:" : uri;
    config.sampleRateHz = sampleRateHz;
    config.centerFrequencyHz = centerFrequencyHz;
    config.rfBandwidthHz = rfBandwidthHz;
    config.gainDb = gainDb;
    config.sampleEncoding = sampleEncoding;
    config.loOffsetHz = loOffsetHz;
    config.hardwareIqCorrection = hardwareIqCorrection;
    config.hardwareBbdcCorrection = hardwareBbdcCorrection;
    config.hardwareRfdcCorrection = hardwareRfdcCorrection;
    config.bufferSamples = kPlutoCaptureBufferSamples;
    config.streamBlockCount = kPlutoCaptureStreamBlockCount;

    sdr::PlutoSource source(config);
    const auto openStatus = source.open();
    if (!openStatus.ok()) {
        std::ostringstream diagnostic;
        diagnostic << "Pluto IQ capture\n"
                   << "status: failed\n"
                   << "uri: " << config.uri << "\n"
                   << "error: " << openStatus.message;
        return diagnostic.str();
    }

    std::ofstream output(outputPath, std::ios::binary | std::ios::out | std::ios::trunc);
    if (!output.is_open()) {
        source.close();
        return "Pluto IQ capture\nstatus: failed\nerror: failed to open output file";
    }

    const auto targetSamples = static_cast<std::uint64_t>(
            std::max(1.0, std::round(static_cast<double>(sampleRateHz) * durationSec)));
    std::uint64_t writtenSamples = 0;
    sdr::SampleBuffer buffer;
    std::vector<std::int8_t> cs8WriteScratch;
    if (sampleEncoding == sdr::SampleEncoding::Cs8) {
        cs8WriteScratch.resize(config.bufferSamples * sdr::SampleBuffer::kValuesPerIqSample);
    }
    while (writtenSamples < targetSamples) {
        const auto requested = static_cast<std::size_t>(
                std::min<std::uint64_t>(config.bufferSamples, targetSamples - writtenSamples));
        const auto readResult = source.read(buffer, requested);
        if (!readResult.ok()) {
            output.close();
            source.close();
            std::ostringstream diagnostic;
            diagnostic << "Pluto IQ capture\n"
                       << "status: failed\n"
                       << "uri: " << config.uri << "\n"
                       << "samples_written: " << writtenSamples << "\n"
                       << "error: " << readResult.message;
            return diagnostic.str();
        }
        if (readResult.samplesRead == 0) {
            break;
        }

        if (sampleEncoding == sdr::SampleEncoding::Cs8) {
            for (std::size_t index = 0; index < buffer.valueCount(); ++index) {
                cs8WriteScratch[index] = static_cast<std::int8_t>(buffer.data()[index] >> 8);
            }
            output.write(
                    reinterpret_cast<const char*>(cs8WriteScratch.data()),
                    static_cast<std::streamsize>(buffer.valueCount() * sizeof(std::int8_t)));
        } else {
            output.write(
                    reinterpret_cast<const char*>(buffer.data()),
                    static_cast<std::streamsize>(
                            buffer.valueCount() * sizeof(std::int16_t)));
        }
        if (!output.good()) {
            output.close();
            source.close();
            return "Pluto IQ capture\nstatus: failed\nerror: failed while writing output file";
        }
        writtenSamples += readResult.samplesRead;
    }

    output.close();
    source.close();

    std::ostringstream diagnostic;
    diagnostic << "Pluto IQ capture\n"
               << "status: captured\n"
               << "uri: " << config.uri << "\n"
               << "output: " << outputPath << "\n"
               << "format: " << sampleEncodingName(sampleEncoding) << "\n"
               << "sample_rate_hz: " << sampleRateHz << "\n"
               << "center_frequency_hz: " << centerFrequencyHz << "\n"
               << "rf_bandwidth_hz: " << rfBandwidthHz << "\n"
               << "lo_offset_hz: " << loOffsetHz << "\n"
               << "actual_lo_frequency_hz: "
               << (static_cast<std::int64_t>(centerFrequencyHz) + loOffsetHz) << "\n"
               << "gain_db: " << gainDb << "\n"
               << "hardware_iq_correction: " << (hardwareIqCorrection ? "true" : "false") << "\n"
               << "hardware_bbdc_correction: " << (hardwareBbdcCorrection ? "true" : "false") << "\n"
               << "hardware_rfdc_correction: " << (hardwareRfdcCorrection ? "true" : "false") << "\n"
               << "duration_sec: " << durationSec << "\n"
               << "samples_written: " << writtenSamples;
    return diagnostic.str();
}

std::string iioBackendDiagnostic() {
#ifndef SDRVIDEOSCANNER_HAVE_LIBIIO
    return "libiio: unavailable\nusb_backend: unavailable\nerror: APK was built without SDRVIDEOSCANNER_HAVE_LIBIIO";
#else
    std::ostringstream diagnostic;
    diagnostic << "libiio: available\n"
               << "usb_backend: " << (iio_has_backend(nullptr, "usb") ? "available" : "unavailable") << "\n"
               << "backends:";
    const auto backendCount = iio_get_builtin_backends_count();
    for (unsigned int index = 0; index < backendCount; ++index) {
        const auto* backend = iio_get_builtin_backend(index);
        if (backend != nullptr) {
            diagnostic << (index == 0 ? " " : ", ") << backend;
        }
    }
    if (backendCount == 0) {
        diagnostic << " none";
    }
    return diagnostic.str();
#endif
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    sdr::setMaiaSourceJavaVm(vm);
    sdr::setPlutoWebSocketSourceJavaVm(vm);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdrvideoscanner_MainActivity_diagnoseCs16File(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    std::string pathError;
    const auto filePath = pathFromJString(env, path, pathError);
    if (!pathError.empty()) {
        const auto diagnostic = errorDiagnostic("", pathError);
        return env->NewStringUTF(diagnostic.c_str());
    }

    const auto diagnostic = diagnoseFirstBlock(filePath);
    return env->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdrvideoscanner_MainActivity_diagnoseCs16FileBlocks(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    std::string pathError;
    const auto filePath = pathFromJString(env, path, pathError);
    if (!pathError.empty()) {
        const auto diagnostic = errorDiagnostic("", pathError);
        return env->NewStringUTF(diagnostic.c_str());
    }

    const auto diagnostic = diagnoseBlocks(filePath);
    return env->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdrvideoscanner_MainActivity_diagnoseCs16Spectrum(
        JNIEnv* env,
        jobject /* this */,
        jstring path,
        jboolean metadataSidecarFound,
        jboolean metadataLoaded,
        jstring metadataPath,
        jstring metadataParseError,
        jstring format,
        jstring endianness,
        jboolean hasSampleRateHz,
        jlong sampleRateHz,
        jboolean hasCenterFrequencyHz,
        jlong centerFrequencyHz,
        jboolean hasRfBandwidthHz,
        jlong rfBandwidthHz,
        jboolean hasGainDb,
        jdouble gainDb,
        jstring sourceName,
        jstring device,
        jboolean hasDurationSec,
        jdouble durationSec) {
    std::string pathError;
    const auto filePath = pathFromJString(env, path, pathError);
    if (!pathError.empty()) {
        const auto diagnostic = errorDiagnostic("", pathError);
        return env->NewStringUTF(diagnostic.c_str());
    }

    sdr::IQMetadata metadata;
    metadata.sidecarFound = metadataSidecarFound == JNI_TRUE;
    metadata.loaded = metadataLoaded == JNI_TRUE;
    metadata.sidecarPath = stringFromJString(env, metadataPath);
    metadata.parseError = stringFromJString(env, metadataParseError);
    metadata.format = stringFromJString(env, format);
    metadata.endianness = stringFromJString(env, endianness);
    metadata.hasSampleRateHz = hasSampleRateHz == JNI_TRUE;
    metadata.sampleRateHz = positiveJLongOrZero(sampleRateHz);
    metadata.hasCenterFrequencyHz = hasCenterFrequencyHz == JNI_TRUE;
    metadata.centerFrequencyHz = positiveJLongOrZero(centerFrequencyHz);
    metadata.hasRfBandwidthHz = hasRfBandwidthHz == JNI_TRUE;
    metadata.rfBandwidthHz = positiveJLongOrZero(rfBandwidthHz);
    metadata.hasGainDb = hasGainDb == JNI_TRUE;
    metadata.gainDb = static_cast<double>(gainDb);
    metadata.source = stringFromJString(env, sourceName);
    metadata.device = stringFromJString(env, device);
    metadata.hasDurationSec = hasDurationSec == JNI_TRUE;
    metadata.durationSec = static_cast<double>(durationSec);

    const auto diagnostic = diagnoseSpectrum(filePath, metadata);
    return env->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdrvideoscanner_MainActivity_capturePlutoIqToFile(
        JNIEnv* env,
        jobject /* this */,
        jstring outputPath,
        jstring uri,
        jlong sampleRateHz,
        jlong centerFrequencyHz,
        jlong rfBandwidthHz,
        jdouble gainDb,
        jint sampleFormat,
        jlong loOffsetHz,
        jboolean hardwareIqCorrection,
        jboolean hardwareBbdcCorrection,
        jboolean hardwareRfdcCorrection,
        jdouble durationSec) {
    std::string pathError;
    const auto filePath = pathFromJString(env, outputPath, pathError);
    if (!pathError.empty()) {
        const auto diagnostic = "Pluto IQ capture\nstatus: failed\nerror: " + pathError;
        return env->NewStringUTF(diagnostic.c_str());
    }

    const auto uriValue = stringFromJString(env, uri);
    const auto diagnostic = capturePlutoIqToFile(
            filePath,
            uriValue,
            positiveJLongOrZero(sampleRateHz),
            positiveJLongOrZero(centerFrequencyHz),
            positiveJLongOrZero(rfBandwidthHz),
            static_cast<double>(gainDb),
            sampleEncodingFromJInt(sampleFormat),
            static_cast<std::int64_t>(loOffsetHz),
            hardwareIqCorrection == JNI_TRUE,
            hardwareBbdcCorrection == JNI_TRUE,
            hardwareRfdcCorrection == JNI_TRUE,
            static_cast<double>(durationSec));
    return env->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdrvideoscanner_MainActivity_probePlutoIqMetrics(
        JNIEnv* env,
        jobject /* this */,
        jstring uri,
        jlong sampleRateHz,
        jlong centerFrequencyHz,
        jlong rfBandwidthHz,
        jdouble gainDb,
        jint sampleFormat,
        jlong loOffsetHz,
        jboolean hardwareIqCorrection,
        jboolean hardwareBbdcCorrection,
        jboolean hardwareRfdcCorrection,
        jdouble windowMs) {
    const auto uriValue = stringFromJString(env, uri);
    const auto diagnostic = probePlutoIqMetrics(
            uriValue,
            positiveJLongOrZero(sampleRateHz),
            positiveJLongOrZero(centerFrequencyHz),
            positiveJLongOrZero(rfBandwidthHz),
            static_cast<double>(gainDb),
            sampleEncodingFromJInt(sampleFormat),
            static_cast<std::int64_t>(loOffsetHz),
            hardwareIqCorrection == JNI_TRUE,
            hardwareBbdcCorrection == JNI_TRUE,
            hardwareRfdcCorrection == JNI_TRUE,
            static_cast<double>(windowMs));
    return env->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sdrvideoscanner_MainActivity_isPlutoCaptureAvailable(
        JNIEnv*,
        jobject /* this */) {
#ifdef SDRVIDEOSCANNER_HAVE_LIBIIO
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sdrvideoscanner_MainActivity_isPlutoUsbCaptureAvailable(
        JNIEnv*,
        jobject /* this */) {
#ifdef SDRVIDEOSCANNER_HAVE_LIBIIO
    return iio_has_backend(nullptr, "usb") ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdrvideoscanner_MainActivity_getIioBackendDiagnostic(
        JNIEnv* env,
        jobject /* this */) {
    const auto diagnostic = iioBackendDiagnostic();
    return env->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdrvideoscanner_MainActivity_consumeLastNativeError(
        JNIEnv* env,
        jobject /* this */) {
    const auto error = consumeLastNativeError();
    return env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_sdrvideoscanner_MainActivity_decodeAnalogVideoFrame(
        JNIEnv* env,
        jobject /* this */,
        jstring path,
        jboolean hasSampleRateHz,
        jlong sampleRateHz,
        jint sampleFormat,
        jint videoStandard,
        jlong frameIndex) {
    std::string pathError;
    const auto filePath = pathFromJString(env, path, pathError);
    if (!pathError.empty()) {
        return env->NewByteArray(0);
    }

    const auto frame = decodeAnalogVideoFrame(
            filePath,
            hasSampleRateHz == JNI_TRUE,
            positiveJLongOrZero(sampleRateHz),
            sampleEncodingFromJInt(sampleFormat),
            videoStandardFromJInt(videoStandard),
            positiveJLongOrZero(frameIndex));
    return frameToJByteArray(env, frame);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdrvideoscanner_MainActivity_createAnalogVideoPlaybackSession(
        JNIEnv* env,
        jobject /* this */,
        jstring path,
        jboolean hasSampleRateHz,
        jlong sampleRateHz,
        jint sampleFormat,
        jint videoStandard) {
    std::string pathError;
    const auto filePath = pathFromJString(env, path, pathError);
    if (!pathError.empty()) {
        return 0;
    }

    auto* session = createAnalogPlaybackSession(
            filePath,
            hasSampleRateHz == JNI_TRUE,
            positiveJLongOrZero(sampleRateHz),
            sampleEncodingFromJInt(sampleFormat),
            videoStandardFromJInt(videoStandard));
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdrvideoscanner_MainActivity_createPlutoAnalogVideoPlaybackSession(
        JNIEnv* env,
        jobject /* this */,
        jstring uri,
        jlong sampleRateHz,
        jlong centerFrequencyHz,
        jlong rfBandwidthHz,
        jdouble gainDb,
        jint sampleFormat,
        jlong loOffsetHz,
        jboolean hardwareIqCorrection,
        jboolean hardwareBbdcCorrection,
        jboolean hardwareRfdcCorrection,
        jint videoStandard) {
    const auto uriValue = stringFromJString(env, uri);
    auto* session = createPlutoPlaybackSession(
            uriValue,
            positiveJLongOrZero(sampleRateHz),
            positiveJLongOrZero(centerFrequencyHz),
            positiveJLongOrZero(rfBandwidthHz),
            static_cast<double>(gainDb),
            sampleEncodingFromJInt(sampleFormat),
            static_cast<std::int64_t>(loOffsetHz),
            hardwareIqCorrection == JNI_TRUE,
            hardwareBbdcCorrection == JNI_TRUE,
            hardwareRfdcCorrection == JNI_TRUE,
            videoStandardFromJInt(videoStandard));
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdrvideoscanner_MainActivity_createPlutoSpectrumSession(
        JNIEnv* env,
        jobject /* this */,
        jstring uri,
        jlong sampleRateHz,
        jlong centerFrequencyHz,
        jlong rfBandwidthHz,
        jdouble gainDb,
        jint sampleFormat,
        jlong loOffsetHz,
        jboolean hardwareIqCorrection,
        jboolean hardwareBbdcCorrection,
        jboolean hardwareRfdcCorrection) {
    const auto uriValue = stringFromJString(env, uri);
    auto* session = createPlutoSpectrumSession(
            uriValue,
            positiveJLongOrZero(sampleRateHz),
            positiveJLongOrZero(centerFrequencyHz),
            positiveJLongOrZero(rfBandwidthHz),
            static_cast<double>(gainDb),
            sampleEncodingFromJInt(sampleFormat),
            static_cast<std::int64_t>(loOffsetHz),
            hardwareIqCorrection == JNI_TRUE,
            hardwareBbdcCorrection == JNI_TRUE,
            hardwareRfdcCorrection == JNI_TRUE);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdrvideoscanner_MainActivity_createMaiaAnalogVideoPlaybackSession(
        JNIEnv* env,
        jobject /* this */,
        jstring host,
        jint port,
        jlong sampleRateHz,
        jint videoStandard,
        jobject androidHttpTransport) {
    const auto hostValue = stringFromJString(env, host);
    auto* session = createMaiaPlaybackSession(
            env,
            hostValue,
            static_cast<std::uint32_t>(std::max(port, 0)),
            positiveJLongOrZero(sampleRateHz),
            videoStandardFromJInt(videoStandard),
            androidHttpTransport);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdrvideoscanner_MainActivity_createMaiaSpectrumSession(
        JNIEnv* env,
        jobject /* this */,
        jstring host,
        jint port,
        jlong sampleRateHz,
        jlong centerFrequencyHz,
        jobject androidHttpTransport) {
    const auto hostValue = stringFromJString(env, host);
    auto* session = createMaiaSpectrumSession(
            env,
            hostValue,
            static_cast<std::uint32_t>(std::max(port, 0)),
            positiveJLongOrZero(sampleRateHz),
            positiveJLongOrZero(centerFrequencyHz),
            androidHttpTransport);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdrvideoscanner_MainActivity_createPlutoWebSocketAnalogVideoPlaybackSession(
        JNIEnv* env,
        jobject /* this */,
        jstring host,
        jint port,
        jstring path,
        jlong sampleRateHz,
        jint receiveBufferMs,
        jint videoStandard,
        jobject androidWebSocketTransport) {
    const auto hostValue = stringFromJString(env, host);
    const auto pathValue = stringFromJString(env, path);
    auto* session = createPlutoWebSocketPlaybackSession(
            env,
            hostValue,
            static_cast<std::uint32_t>(std::max(port, 0)),
            pathValue,
            positiveJLongOrZero(sampleRateHz),
            static_cast<std::uint32_t>(std::max(receiveBufferMs, 0)),
            videoStandardFromJInt(videoStandard),
            androidWebSocketTransport);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdrvideoscanner_MainActivity_createPlutoWebSocketSpectrumSession(
        JNIEnv* env,
        jobject /* this */,
        jstring host,
        jint port,
        jstring path,
        jlong sampleRateHz,
        jlong centerFrequencyHz,
        jint receiveBufferMs,
        jobject androidWebSocketTransport) {
    const auto hostValue = stringFromJString(env, host);
    const auto pathValue = stringFromJString(env, path);
    auto* session = createPlutoWebSocketSpectrumSession(
            env,
            hostValue,
            static_cast<std::uint32_t>(std::max(port, 0)),
            pathValue,
            positiveJLongOrZero(sampleRateHz),
            positiveJLongOrZero(centerFrequencyHz),
            static_cast<std::uint32_t>(std::max(receiveBufferMs, 0)),
            androidWebSocketTransport);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_sdrvideoscanner_MainActivity_decodeNextAnalogVideoPlaybackFrame(
        JNIEnv* env,
        jobject /* this */,
        jlong sessionHandle) {
    auto* session = reinterpret_cast<AnalogPlaybackSession*>(sessionHandle);
    if (session == nullptr || session->source == nullptr || session->decoder == nullptr) {
        return env->NewByteArray(0);
    }

    const auto frame = decodeNextPlaybackFrame(*session);
    return frameToJByteArray(env, frame);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_sdrvideoscanner_MainActivity_decodeNextSpectrumFrame(
        JNIEnv* env,
        jobject /* this */,
        jlong sessionHandle) {
    auto* session = reinterpret_cast<SpectrumViewSession*>(sessionHandle);
    if (session == nullptr || session->source == nullptr) {
        return env->NewByteArray(0);
    }

    bool ok = false;
    const auto stats = readNextSpectrumStats(*session, ok);
    if (!ok) {
        return env->NewByteArray(0);
    }
    return spectrumFrameToJByteArray(env, *session, stats);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sdrvideoscanner_MainActivity_closeAnalogVideoPlaybackSession(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong sessionHandle) {
    auto* session = reinterpret_cast<AnalogPlaybackSession*>(sessionHandle);
    delete session;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sdrvideoscanner_MainActivity_closeSpectrumSession(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong sessionHandle) {
    auto* session = reinterpret_cast<SpectrumViewSession*>(sessionHandle);
    delete session;
}
