#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "source/IQMetadata.h"
#include "source/analyzer/SpectrumAnalyzer.h"
#include "source/file/FileSource.h"
#include "source/video/AnalogVideoDecoder.h"

namespace {

constexpr std::size_t kDiagnosticBlockSamples = 4096;
constexpr std::uint64_t kBytesPerIqSample = sizeof(std::int16_t) * 2;
constexpr double kCs16FullScale = 32768.0;

struct AnalogPlaybackSession {
    std::unique_ptr<sdr::FileSource> source;
    std::unique_ptr<sdr::AnalogVideoDecoder> decoder;
    std::uint64_t sampleRateHz = 0;
    sdr::VideoStandard standard = sdr::VideoStandard::NTSC_525_30FPS;
    double playbackFrameRateHz = 0.0;
    std::uint64_t frameIndex = 0;
    std::uint64_t totalSampleCount = 0;
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
    const auto iqSampleCount = sizeBytes / kBytesPerIqSample;
    const auto trailingBytes = sizeBytes % kBytesPerIqSample;

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
    sdr::FileSource source(filePath, sourceSampleRate);
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
               << "frame: " << frame.width << "x" << frame.height << "\n"
               << "decoder: " << frame.message;
    return diagnostic.str();
}

jbyteArray frameToJByteArray(JNIEnv* env, const sdr::VideoFrame& frame) {
    if (!frame.valid()) {
        return env->NewByteArray(0);
    }

    const auto diagnostic = videoFrameDiagnostic(frame);
    constexpr std::size_t kHeaderBytes = sizeof(std::uint32_t) * 3;
    const auto packetSize = kHeaderBytes + frame.pixels.size() + diagnostic.size();
    if (packetSize > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return env->NewByteArray(0);
    }

    std::vector<std::uint8_t> packet;
    packet.reserve(packetSize);
    writeLittleEndianUint32(frame.width, packet);
    writeLittleEndianUint32(frame.height, packet);
    writeLittleEndianUint32(static_cast<std::uint32_t>(diagnostic.size()), packet);
    packet.insert(packet.end(), frame.pixels.begin(), frame.pixels.end());
    packet.insert(packet.end(), diagnostic.begin(), diagnostic.end());

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
            return 30.0;
        case sdr::VideoStandard::PAL625_25FPS:
            return 25.0;
        case sdr::VideoStandard::AUTO:
        default:
            return 0.0;
    }
}

sdr::VideoFrame decodeAnalogVideoFrameForStandard(
        const std::string& filePath,
        std::uint64_t sampleRateHz,
        sdr::VideoStandard standard,
        std::uint64_t frameIndex) {
    const auto sourceSampleRate =
            sampleRateHz <=
                            static_cast<std::uint64_t>(
                                    std::numeric_limits<std::uint32_t>::max())
                    ? static_cast<std::uint32_t>(sampleRateHz)
                    : 0;

    sdr::FileSource source(filePath, sourceSampleRate);
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
        sdr::VideoStandard requestedStandard,
        std::uint64_t frameIndex) {
    if (!hasSampleRateHz || sampleRateHz == 0) {
        sdr::VideoFrame frame;
        frame.message = "sample_rate_hz metadata is required";
        return frame;
    }

    if (requestedStandard != sdr::VideoStandard::AUTO) {
        return decodeAnalogVideoFrameForStandard(filePath, sampleRateHz, requestedStandard, frameIndex);
    }

    auto palFrame = decodeAnalogVideoFrameForStandard(
            filePath,
            sampleRateHz,
            sdr::VideoStandard::PAL625_25FPS,
            frameIndex);
    auto ntscFrame = decodeAnalogVideoFrameForStandard(
            filePath,
            sampleRateHz,
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
        sdr::VideoStandard requestedStandard) {
    if (requestedStandard != sdr::VideoStandard::AUTO) {
        return requestedStandard;
    }

    const auto palFrame = decodeAnalogVideoFrameForStandard(
            filePath,
            sampleRateHz,
            sdr::VideoStandard::PAL625_25FPS,
            0);
    const auto ntscFrame = decodeAnalogVideoFrameForStandard(
            filePath,
            sampleRateHz,
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
    session->standard = choosePlaybackStandard(filePath, sampleRateHz, requestedStandard);
    session->playbackFrameRateHz = playbackFrameRateForStandard(session->standard);
    session->totalSampleCount = fileSizeBytes(filePath) / kBytesPerIqSample;
    session->source = std::make_unique<sdr::FileSource>(filePath, sourceSampleRate);

    const auto openStatus = session->source->open();
    if (!openStatus.ok()) {
        return nullptr;
    }

    sdr::AnalogVideoDecoderConfig config;
    config.sampleRateHz = sampleRateHz;
    config.analysisRateHz = 1500000;
    config.readBlockSamples = 262144;
    config.fastFieldPreview = true;
    config.detectFrameSyncInFastPreview = true;
    config.fastPreviewFieldStride = 2;
    config.timing = sdr::timingForStandard(session->standard);
    if (session->playbackFrameRateHz > 0.0) {
        config.timing.frameRateHz = session->playbackFrameRateHz;
    }
    session->decoder = std::make_unique<sdr::AnalogVideoDecoder>(config);

    return session.release();
}

sdr::VideoFrame decodeNextPlaybackFrame(AnalogPlaybackSession& session) {
    auto frame = session.decoder->decodeOneFrame(*session.source);
    if (!frame.valid()) {
        const auto seekStatus = session.source->seekSamples(0);
        session.frameIndex = 0;
        if (seekStatus.ok()) {
            frame = session.decoder->decodeOneFrame(*session.source);
        }
    }

    if (frame.valid()) {
        std::ostringstream message;
        message << frame.message
                << "; playback_session=sequential"
                << "; playback_frame_rate_hz=" << session.playbackFrameRateHz
                << "; playback_frame_index=" << session.frameIndex;
        frame.message = message.str();
        ++session.frameIndex;
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

}  // namespace

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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_sdrvideoscanner_MainActivity_decodeAnalogVideoFrame(
        JNIEnv* env,
        jobject /* this */,
        jstring path,
        jboolean hasSampleRateHz,
        jlong sampleRateHz,
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
            videoStandardFromJInt(videoStandard));
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

extern "C" JNIEXPORT void JNICALL
Java_com_example_sdrvideoscanner_MainActivity_closeAnalogVideoPlaybackSession(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong sessionHandle) {
    auto* session = reinterpret_cast<AnalogPlaybackSession*>(sessionHandle);
    delete session;
}
