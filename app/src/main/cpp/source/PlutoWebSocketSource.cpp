#include "PlutoWebSocketSource.h"

#include <algorithm>
#include <chrono>
#include <limits>
#include <sstream>
#include <utility>
#include <vector>

#include <android/log.h>

namespace sdr {
namespace {

constexpr const char* kLogTag = "SDRVideoScanner.PlutoWs";

JavaVM* gJavaVm = nullptr;

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

void logInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
}

void logWarn(const std::string& message) {
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message.c_str());
}

struct JniEnv {
    JNIEnv* env = nullptr;
    bool attached = false;

    ~JniEnv() {
        if (attached && gJavaVm != nullptr) {
            gJavaVm->DetachCurrentThread();
        }
    }
};

JniEnv currentJniEnv() {
    JniEnv result;
    if (gJavaVm == nullptr) {
        return result;
    }

    void* env = nullptr;
    const auto getResult = gJavaVm->GetEnv(&env, JNI_VERSION_1_6);
    if (getResult == JNI_OK) {
        result.env = static_cast<JNIEnv*>(env);
        return result;
    }

    if (getResult == JNI_EDETACHED &&
        gJavaVm->AttachCurrentThread(&result.env, nullptr) == JNI_OK) {
        result.attached = true;
    }
    return result;
}

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string javaExceptionMessage(JNIEnv* env, const char* action) {
    if (!env->ExceptionCheck()) {
        return {};
    }
    env->ExceptionDescribe();
    env->ExceptionClear();
    return std::string(action) + " raised a Java exception";
}

SourceStatus javaStringStatus(jstring error, JNIEnv* env, const char* action) {
    if (const auto exception = javaExceptionMessage(env, action); !exception.empty()) {
        return makeStatus(SampleSourceError::OpenFailed, exception);
    }
    if (error == nullptr) {
        return {};
    }
    const auto message = jstringToString(env, error);
    env->DeleteLocalRef(error);
    if (message.empty()) {
        return {};
    }
    return makeStatus(SampleSourceError::OpenFailed, message);
}

SourceStatus callJavaStringMethod(jobject transport, const char* methodName) {
    if (transport == nullptr) {
        return makeStatus(SampleSourceError::OpenFailed, "Android WebSocket transport object is not set");
    }

    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return makeStatus(SampleSourceError::OpenFailed, "Android WebSocket transport is unavailable: JavaVM is not set");
    }

    auto* cls = jni.env->GetObjectClass(transport);
    if (cls == nullptr) {
        const auto exception = javaExceptionMessage(jni.env, "GetObjectClass PlutoWebSocketTransport");
        return makeStatus(SampleSourceError::OpenFailed, exception.empty() ? "Android WebSocket transport class was not found" : exception);
    }

    auto* method = jni.env->GetMethodID(cls, methodName, "()Ljava/lang/String;");
    if (method == nullptr) {
        jni.env->DeleteLocalRef(cls);
        const auto exception = javaExceptionMessage(jni.env, methodName);
        return makeStatus(
                SampleSourceError::OpenFailed,
                exception.empty()
                        ? std::string("Android WebSocket transport method was not found: ") + methodName
                        : exception);
    }

    auto* error = static_cast<jstring>(jni.env->CallObjectMethod(transport, method));
    jni.env->DeleteLocalRef(cls);
    return javaStringStatus(error, jni.env, methodName);
}

void callJavaVoidMethod(jobject transport, const char* methodName) {
    if (transport == nullptr) {
        return;
    }

    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return;
    }

    auto* cls = jni.env->GetObjectClass(transport);
    if (cls == nullptr) {
        javaExceptionMessage(jni.env, "GetObjectClass PlutoWebSocketTransport");
        return;
    }
    auto* method = jni.env->GetMethodID(cls, methodName, "()V");
    if (method != nullptr) {
        jni.env->CallVoidMethod(transport, method);
        javaExceptionMessage(jni.env, methodName);
    } else {
        javaExceptionMessage(jni.env, methodName);
    }
    jni.env->DeleteLocalRef(cls);
}

std::string javaLastError(jobject transport) {
    if (transport == nullptr) {
        return "Android WebSocket transport object is not set";
    }

    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return "Android WebSocket transport is unavailable: JavaVM is not set";
    }

    auto* cls = jni.env->GetObjectClass(transport);
    if (cls == nullptr) {
        const auto exception = javaExceptionMessage(jni.env, "GetObjectClass PlutoWebSocketTransport");
        return exception.empty() ? "Android WebSocket transport class was not found" : exception;
    }
    auto* method = jni.env->GetMethodID(cls, "lastError", "()Ljava/lang/String;");
    if (method == nullptr) {
        jni.env->DeleteLocalRef(cls);
        const auto exception = javaExceptionMessage(jni.env, "lastError");
        return exception.empty() ? "Android WebSocket transport lastError method was not found" : exception;
    }
    auto* error = static_cast<jstring>(jni.env->CallObjectMethod(transport, method));
    jni.env->DeleteLocalRef(cls);
    if (const auto exception = javaExceptionMessage(jni.env, "lastError"); !exception.empty()) {
        return exception;
    }
    const auto result = jstringToString(jni.env, error);
    if (error != nullptr) {
        jni.env->DeleteLocalRef(error);
    }
    return result;
}

ReadResult readJavaTransport(
        jobject transport,
        jmethodID readMethod,
        jbyteArray readBuffer,
        std::vector<std::uint8_t>& destination,
        std::size_t targetBytes) {
    if (transport == nullptr) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Android WebSocket transport object is not set");
    }
    if (readMethod == nullptr || readBuffer == nullptr) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Android WebSocket transport read cache is not initialized");
    }
    if (targetBytes > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Pluto WebSocket read target is too large");
    }

    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Android WebSocket transport is unavailable: JavaVM is not set");
    }

    const auto bytesRead = jni.env->CallIntMethod(
            transport,
            readMethod,
            readBuffer,
            static_cast<jint>(targetBytes));

    if (const auto exception = javaExceptionMessage(jni.env, "read"); !exception.empty()) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, exception);
    }
    if (bytesRead < 0) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, javaLastError(transport));
    }
    if (bytesRead == 0) {
        return makeReadResult(0, true, SampleSourceError::ReadFailed, "Pluto WebSocket stream closed");
    }
    if ((bytesRead % 2) != 0) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Malformed Pluto WebSocket CS8 stream: incomplete I/Q pair");
    }

    destination.resize(static_cast<std::size_t>(bytesRead));
    jni.env->GetByteArrayRegion(
            readBuffer,
            0,
            bytesRead,
            reinterpret_cast<jbyte*>(destination.data()));

    if (const auto exception = javaExceptionMessage(jni.env, "GetByteArrayRegion"); !exception.empty()) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, exception);
    }

    return makeReadResult(static_cast<std::size_t>(bytesRead) / 2U, false, SampleSourceError::None, {});
}

}  // namespace

struct PlutoWebSocketSource::Impl {
    std::vector<std::uint8_t> readScratch;
    jmethodID readMethod = nullptr;
    jbyteArray javaReadBuffer = nullptr;
    std::size_t javaReadBufferBytes = 0;
    bool streamOpen = false;
    std::uint64_t bytesReceived = 0;
    std::uint64_t samplesReceived = 0;
    std::uint64_t dropoutCount = 0;
    std::chrono::steady_clock::time_point statsWindowStart = std::chrono::steady_clock::now();
};

void setPlutoWebSocketSourceJavaVm(JavaVM* javaVm) {
    gJavaVm = javaVm;
}

PlutoWebSocketSource::PlutoWebSocketSource(PlutoWebSocketSourceConfig config)
        : config_(std::move(config)),
          impl_(std::make_unique<Impl>()) {
    format_.encoding = SampleEncoding::Cs8;
    format_.sampleRateHz = config_.sampleRateHz;
    format_.channelCount = 1;
}

PlutoWebSocketSource::~PlutoWebSocketSource() {
    close();
    if (config_.androidWebSocketTransport != nullptr) {
        auto jni = currentJniEnv();
        if (jni.env != nullptr) {
            jni.env->DeleteGlobalRef(config_.androidWebSocketTransport);
        }
        config_.androidWebSocketTransport = nullptr;
    }
}

SourceStatus PlutoWebSocketSource::open() {
    close();
    if (config_.host.empty()) {
        return makeStatus(SampleSourceError::InvalidArgument, "Pluto WebSocket host is empty");
    }
    if (config_.port == 0) {
        return makeStatus(SampleSourceError::InvalidArgument, "Pluto WebSocket port must be set");
    }
    if (config_.path.empty() || config_.path.front() != '/') {
        return makeStatus(SampleSourceError::InvalidArgument, "Pluto WebSocket path must start with /");
    }
    if (config_.sampleRateHz == 0) {
        return makeStatus(SampleSourceError::InvalidArgument, "Pluto WebSocket sample rate must be set");
    }
    if (config_.androidWebSocketTransport == nullptr) {
        return makeStatus(SampleSourceError::InvalidArgument, "Pluto WebSocket requires an Android WebSocket transport");
    }

    const auto status = callJavaStringMethod(config_.androidWebSocketTransport, "openStream");
    if (!status.ok()) {
        return status;
    }
    const auto readBufferBytes = std::max<std::size_t>(
            config_.bufferSamples,
            1024U) * SampleBuffer::kValuesPerIqSample;
    const auto cacheStatus = initializeReadCache(readBufferBytes);
    if (!cacheStatus.ok()) {
        callJavaVoidMethod(config_.androidWebSocketTransport, "close");
        return cacheStatus;
    }
    impl_->readScratch.resize(readBufferBytes);
    impl_->streamOpen = true;
    impl_->statsWindowStart = std::chrono::steady_clock::now();
    impl_->bytesReceived = 0;
    impl_->samplesReceived = 0;
    impl_->dropoutCount = 0;
    return {};
}

SourceStatus PlutoWebSocketSource::initializeReadCache(std::size_t targetBytes) {
    if (targetBytes > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return makeStatus(SampleSourceError::InvalidArgument, "Pluto WebSocket read buffer target is too large");
    }
    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return makeStatus(SampleSourceError::OpenFailed, "Android WebSocket transport is unavailable: JavaVM is not set");
    }
    auto* cls = jni.env->GetObjectClass(config_.androidWebSocketTransport);
    if (cls == nullptr) {
        const auto exception = javaExceptionMessage(jni.env, "GetObjectClass PlutoWebSocketTransport");
        return makeStatus(
                SampleSourceError::OpenFailed,
                exception.empty() ? "Android WebSocket transport class was not found" : exception);
    }
    impl_->readMethod = jni.env->GetMethodID(cls, "read", "([BI)I");
    jni.env->DeleteLocalRef(cls);
    if (impl_->readMethod == nullptr) {
        const auto exception = javaExceptionMessage(jni.env, "read");
        return makeStatus(
                SampleSourceError::OpenFailed,
                exception.empty() ? "Android WebSocket transport read method was not found" : exception);
    }
    auto* localBuffer = jni.env->NewByteArray(static_cast<jsize>(targetBytes));
    if (localBuffer == nullptr) {
        return makeStatus(SampleSourceError::OpenFailed, "Failed to allocate reusable Android WebSocket read buffer");
    }
    impl_->javaReadBuffer = static_cast<jbyteArray>(jni.env->NewGlobalRef(localBuffer));
    jni.env->DeleteLocalRef(localBuffer);
    if (impl_->javaReadBuffer == nullptr) {
        return makeStatus(SampleSourceError::OpenFailed, "Failed to retain reusable Android WebSocket read buffer");
    }
    impl_->javaReadBufferBytes = targetBytes;
    return {};
}

void PlutoWebSocketSource::close() {
    if (config_.androidWebSocketTransport != nullptr && impl_->streamOpen) {
        callJavaVoidMethod(config_.androidWebSocketTransport, "close");
    }
    impl_->streamOpen = false;
    impl_->readScratch.clear();
    impl_->readMethod = nullptr;
    impl_->javaReadBufferBytes = 0;
    if (impl_->javaReadBuffer != nullptr) {
        auto jni = currentJniEnv();
        if (jni.env != nullptr) {
            jni.env->DeleteGlobalRef(impl_->javaReadBuffer);
        }
        impl_->javaReadBuffer = nullptr;
    }
}

bool PlutoWebSocketSource::isOpen() const {
    return impl_->streamOpen;
}

SampleFormat PlutoWebSocketSource::format() const {
    return format_;
}

ReadResult PlutoWebSocketSource::read(SampleBuffer& buffer, std::size_t maxSamples) {
    buffer.clear();
    if (!isOpen()) {
        return makeReadResult(0, false, SampleSourceError::NotOpen, "Pluto WebSocket source is not open");
    }
    if (maxSamples == 0) {
        return {};
    }

    const auto targetBytes = maxSamples * SampleBuffer::kValuesPerIqSample;
    if (targetBytes > impl_->javaReadBufferBytes) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Pluto WebSocket read target exceeds reusable buffer");
    }
    impl_->readScratch.resize(targetBytes);
    auto readResult = readJavaTransport(
            config_.androidWebSocketTransport,
            impl_->readMethod,
            impl_->javaReadBuffer,
            impl_->readScratch,
            targetBytes);
    if (!readResult.ok() || readResult.samplesRead == 0) {
        ++impl_->dropoutCount;
        logWarn("Pluto WebSocket read dropout: " + readResult.message);
        return readResult;
    }

    const auto completeBytes = readResult.samplesRead * SampleBuffer::kValuesPerIqSample;
    if (completeBytes == 0 || (completeBytes % SampleBuffer::kValuesPerIqSample) != 0) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Malformed Pluto WebSocket CS8 stream: incomplete I/Q pair");
    }

    buffer.resizeSamples(readResult.samplesRead);
    // plutorx_ws sends signed CS8 bytes in I,Q,I,Q order.
    for (std::size_t index = 0; index < completeBytes; ++index) {
        const auto value = static_cast<std::int8_t>(impl_->readScratch[index]);
        buffer.data()[index] = static_cast<std::int16_t>(value) << 8;
    }

    impl_->bytesReceived += completeBytes;
    impl_->samplesReceived += readResult.samplesRead;
    const auto now = std::chrono::steady_clock::now();
    const auto elapsedSec = std::chrono::duration<double>(now - impl_->statsWindowStart).count();
    if (elapsedSec >= 1.0) {
        const auto bytesPerSec = static_cast<double>(impl_->bytesReceived) / elapsedSec;
        const auto samplesPerSec = static_cast<double>(impl_->samplesReceived) / elapsedSec;
        std::ostringstream message;
        message << "Pluto WebSocket stream: " << static_cast<std::uint64_t>(bytesPerSec)
                << " B/s, estimated_sample_rate=" << static_cast<std::uint64_t>(samplesPerSec)
                << " sps, dropouts=" << impl_->dropoutCount;
        logInfo(message.str());
        impl_->bytesReceived = 0;
        impl_->samplesReceived = 0;
        impl_->statsWindowStart = now;
    }

    return makeReadResult(readResult.samplesRead, false, SampleSourceError::None, {});
}

}  // namespace sdr
