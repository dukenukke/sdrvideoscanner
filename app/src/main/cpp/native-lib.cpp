#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>

#include "source/file/FileSource.h"

namespace {

constexpr std::size_t kDiagnosticBlockSamples = 4096;
constexpr std::uint64_t kBytesPerIqSample = sizeof(std::int16_t) * 2;

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

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdrvideoscanner_MainActivity_diagnoseCs16File(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    if (path == nullptr) {
        const auto diagnostic = errorDiagnostic("", "path is null");
        return env->NewStringUTF(diagnostic.c_str());
    }

    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    if (rawPath == nullptr) {
        const auto diagnostic = errorDiagnostic("", "failed to read path from JVM");
        return env->NewStringUTF(diagnostic.c_str());
    }

    const std::string filePath(rawPath);
    env->ReleaseStringUTFChars(path, rawPath);

    const auto sizeBytes = fileSizeBytes(filePath);
    const auto iqSampleCount = sizeBytes / kBytesPerIqSample;
    const auto trailingBytes = sizeBytes % kBytesPerIqSample;

    sdr::FileSource source(filePath, 0);
    const auto openStatus = source.open();
    if (!openStatus.ok()) {
        const auto diagnostic = errorDiagnostic(filePath, openStatus.message);
        return env->NewStringUTF(diagnostic.c_str());
    }

    sdr::SampleBuffer buffer;
    const auto readResult = source.read(buffer, kDiagnosticBlockSamples);
    source.close();

    if (!readResult.ok()) {
        const auto diagnostic = errorDiagnostic(filePath, readResult.message);
        return env->NewStringUTF(diagnostic.c_str());
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
        const auto value = diagnostic.str();
        return env->NewStringUTF(value.c_str());
    }

    const auto* samples = buffer.data();
    std::int16_t minI = std::numeric_limits<std::int16_t>::max();
    std::int16_t maxI = std::numeric_limits<std::int16_t>::min();
    std::int16_t minQ = std::numeric_limits<std::int16_t>::max();
    std::int16_t maxQ = std::numeric_limits<std::int16_t>::min();

    for (std::size_t sampleIndex = 0; sampleIndex < readResult.samplesRead; ++sampleIndex) {
        const auto i = samples[sampleIndex * 2];
        const auto q = samples[sampleIndex * 2 + 1];
        minI = std::min(minI, i);
        maxI = std::max(maxI, i);
        minQ = std::min(minQ, q);
        maxQ = std::max(maxQ, q);
    }

    diagnostic << "\nfirst I/Q: " << samples[0] << ", " << samples[1]
               << "\nmin/max I: " << minI << " / " << maxI
               << "\nmin/max Q: " << minQ << " / " << maxQ;

    const auto value = diagnostic.str();
    return env->NewStringUTF(value.c_str());
}
