#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>

#include "source/IQMetadata.h"
#include "source/analyzer/SpectrumAnalyzer.h"
#include "source/file/FileSource.h"

namespace {

constexpr std::size_t kDiagnosticBlockSamples = 4096;
constexpr std::uint64_t kBytesPerIqSample = sizeof(std::int16_t) * 2;
constexpr double kCs16FullScale = 32768.0;

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
