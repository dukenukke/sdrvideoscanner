#include "PlutoWebSocketSource.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <exception>
#include <limits>
#include <mutex>
#include <sstream>
#include <thread>
#include <utility>
#include <vector>

#include <android/log.h>

namespace sdr {
namespace {

constexpr const char* kLogTag = "SDRVideoScanner.PlutoWs";
constexpr std::size_t kDefaultReceiveBufferMs = 120;

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
    jmethodID readMethod = nullptr;
    jbyteArray javaReadBuffer = nullptr;
    std::size_t javaReadBufferBytes = 0;
    bool streamOpen = false;
    std::thread receiverThread;
    std::mutex receiveMutex;
    std::condition_variable receiveReady;
    std::vector<std::int16_t> receiveRing;
    std::size_t receiveCapacitySamples = 0;
    std::size_t receiveStartSample = 0;
    std::size_t receiveSizeSamples = 0;
    bool stopReceiver = false;
    bool receiverEnded = false;
    SampleSourceError receiverError = SampleSourceError::None;
    std::string receiverMessage;
    std::uint64_t bytesReceived = 0;
    std::uint64_t samplesReceived = 0;
    std::uint64_t staleSamplesDropped = 0;
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
    const auto defaultReceiveSamples = std::max<std::size_t>(
            config_.bufferSamples * 4U,
            (static_cast<std::size_t>(config_.sampleRateHz) * kDefaultReceiveBufferMs) / 1000U);
    impl_->receiveCapacitySamples = std::max<std::size_t>(
            config_.receiveBufferSamples == 0 ? defaultReceiveSamples : config_.receiveBufferSamples,
            config_.bufferSamples * 2U);
    impl_->receiveRing.assign(
            impl_->receiveCapacitySamples * SampleBuffer::kValuesPerIqSample,
            0);
    impl_->receiveStartSample = 0;
    impl_->receiveSizeSamples = 0;
    impl_->stopReceiver = false;
    impl_->receiverEnded = false;
    impl_->receiverError = SampleSourceError::None;
    impl_->receiverMessage.clear();
    impl_->streamOpen = true;
    impl_->statsWindowStart = std::chrono::steady_clock::now();
    impl_->bytesReceived = 0;
    impl_->samplesReceived = 0;
    impl_->staleSamplesDropped = 0;
    impl_->dropoutCount = 0;
    const auto receiverStatus = startReceiver();
    if (!receiverStatus.ok()) {
        close();
        return receiverStatus;
    }
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

SourceStatus PlutoWebSocketSource::startReceiver() {
    try {
        impl_->receiverThread = std::thread(&PlutoWebSocketSource::receiverLoop, this);
    } catch (const std::exception& error) {
        return makeStatus(
                SampleSourceError::OpenFailed,
                std::string("Failed to start Pluto WebSocket receiver thread: ") + error.what());
    }
    return {};
}

void PlutoWebSocketSource::stopReceiver() {
    {
        std::lock_guard<std::mutex> lock(impl_->receiveMutex);
        impl_->stopReceiver = true;
        impl_->receiveReady.notify_all();
    }
    if (config_.androidWebSocketTransport != nullptr && impl_->streamOpen) {
        callJavaVoidMethod(config_.androidWebSocketTransport, "interruptRead");
    }
    if (impl_->receiverThread.joinable()) {
        impl_->receiverThread.join();
    }
    if (config_.androidWebSocketTransport != nullptr && impl_->streamOpen) {
        callJavaVoidMethod(config_.androidWebSocketTransport, "close");
    }
}

void PlutoWebSocketSource::receiverLoop() {
    std::vector<std::uint8_t> readScratch;
    readScratch.resize(impl_->javaReadBufferBytes);

    while (true) {
        {
            std::lock_guard<std::mutex> lock(impl_->receiveMutex);
            if (impl_->stopReceiver) {
                break;
            }
        }

        auto readResult = readJavaTransport(
                config_.androidWebSocketTransport,
                impl_->readMethod,
                impl_->javaReadBuffer,
                readScratch,
                impl_->javaReadBufferBytes);

        {
            std::lock_guard<std::mutex> lock(impl_->receiveMutex);
            if (impl_->stopReceiver) {
                break;
            }
        }

        if (!readResult.ok() || readResult.samplesRead == 0) {
            ++impl_->dropoutCount;
            logWarn("Pluto WebSocket receiver stopped: " + readResult.message);
            {
                std::lock_guard<std::mutex> lock(impl_->receiveMutex);
                impl_->receiverEnded = true;
                impl_->receiverError = readResult.error;
                impl_->receiverMessage = readResult.message;
            }
            impl_->receiveReady.notify_all();
            break;
        }

        const auto completeBytes = readResult.samplesRead * SampleBuffer::kValuesPerIqSample;
        if (completeBytes == 0 || completeBytes > readScratch.size()) {
            ++impl_->dropoutCount;
            {
                std::lock_guard<std::mutex> lock(impl_->receiveMutex);
                impl_->receiverEnded = true;
                impl_->receiverError = SampleSourceError::ReadFailed;
                impl_->receiverMessage = "Malformed Pluto WebSocket CS8 stream: incomplete I/Q pair";
            }
            impl_->receiveReady.notify_all();
            break;
        }

        pushReceivedBytes(readScratch, readResult.samplesRead);

        impl_->bytesReceived += completeBytes;
        impl_->samplesReceived += readResult.samplesRead;
        const auto now = std::chrono::steady_clock::now();
        const auto elapsedSec = std::chrono::duration<double>(now - impl_->statsWindowStart).count();
        if (elapsedSec >= 1.0) {
            std::size_t bufferedSamples = 0;
            std::uint64_t staleDrops = 0;
            {
                std::lock_guard<std::mutex> lock(impl_->receiveMutex);
                bufferedSamples = impl_->receiveSizeSamples;
                staleDrops = impl_->staleSamplesDropped;
            }
            const auto bytesPerSec = static_cast<double>(impl_->bytesReceived) / elapsedSec;
            const auto samplesPerSec = static_cast<double>(impl_->samplesReceived) / elapsedSec;
            std::ostringstream message;
            message << "Pluto WebSocket stream: " << static_cast<std::uint64_t>(bytesPerSec)
                    << " B/s, estimated_sample_rate=" << static_cast<std::uint64_t>(samplesPerSec)
                    << " sps, buffered_samples=" << bufferedSamples
                    << ", stale_dropped_samples=" << staleDrops
                    << ", dropouts=" << impl_->dropoutCount;
            logInfo(message.str());
            impl_->bytesReceived = 0;
            impl_->samplesReceived = 0;
            impl_->statsWindowStart = now;
        }
    }

    {
        std::lock_guard<std::mutex> lock(impl_->receiveMutex);
        impl_->receiverEnded = true;
    }
    impl_->receiveReady.notify_all();
}

void PlutoWebSocketSource::pushReceivedBytes(
        const std::vector<std::uint8_t>& bytes,
        std::size_t sampleCount) {
    if (sampleCount == 0 || impl_->receiveCapacitySamples == 0) {
        return;
    }

    const auto incomingSamples = std::min(sampleCount, impl_->receiveCapacitySamples);
    const auto skippedIncomingSamples = sampleCount - incomingSamples;

    std::lock_guard<std::mutex> lock(impl_->receiveMutex);
    const auto requiredDropSamples = incomingSamples >
                    (impl_->receiveCapacitySamples - impl_->receiveSizeSamples)
            ? incomingSamples - (impl_->receiveCapacitySamples - impl_->receiveSizeSamples)
            : 0U;
    if (requiredDropSamples > 0) {
        impl_->receiveStartSample =
                (impl_->receiveStartSample + requiredDropSamples) % impl_->receiveCapacitySamples;
        impl_->receiveSizeSamples -= std::min(requiredDropSamples, impl_->receiveSizeSamples);
        impl_->staleSamplesDropped += requiredDropSamples;
    }
    impl_->staleSamplesDropped += skippedIncomingSamples;

    std::size_t writeSample =
            (impl_->receiveStartSample + impl_->receiveSizeSamples) % impl_->receiveCapacitySamples;
    std::size_t copiedSamples = 0;
    while (copiedSamples < incomingSamples) {
        const auto spanSamples = std::min(
                incomingSamples - copiedSamples,
                impl_->receiveCapacitySamples - writeSample);
        const auto sourceSample = skippedIncomingSamples + copiedSamples;
        const auto sourceValue = sourceSample * SampleBuffer::kValuesPerIqSample;
        auto* destination = impl_->receiveRing.data() +
                (writeSample * SampleBuffer::kValuesPerIqSample);
        for (std::size_t index = 0; index < spanSamples * SampleBuffer::kValuesPerIqSample; ++index) {
            const auto value = static_cast<std::int8_t>(bytes[sourceValue + index]);
            destination[index] = static_cast<std::int16_t>(value) << 8;
        }
        impl_->receiveSizeSamples += spanSamples;
        copiedSamples += spanSamples;
        writeSample = (writeSample + spanSamples) % impl_->receiveCapacitySamples;
    }
    impl_->receiveReady.notify_all();
}

ReadResult PlutoWebSocketSource::readFromReceiveBuffer(
        SampleBuffer& buffer,
        std::size_t maxSamples) {
    if (maxSamples > impl_->receiveCapacitySamples) {
        return makeReadResult(
                0,
                false,
                SampleSourceError::ReadFailed,
                "Pluto WebSocket read target exceeds native receive ring capacity");
    }

    std::unique_lock<std::mutex> lock(impl_->receiveMutex);
    impl_->receiveReady.wait(lock, [this, maxSamples] {
        return impl_->receiveSizeSamples >= maxSamples ||
                impl_->receiverEnded ||
                impl_->stopReceiver;
    });

    if (impl_->receiveSizeSamples == 0) {
        if (impl_->receiverError != SampleSourceError::None) {
            return makeReadResult(
                    0,
                    false,
                    impl_->receiverError,
                    impl_->receiverMessage.empty()
                            ? "Pluto WebSocket receiver failed"
                            : impl_->receiverMessage);
        }
        return makeReadResult(
                0,
                true,
                SampleSourceError::None,
                impl_->receiverMessage.empty()
                        ? "Pluto WebSocket stream closed"
                        : impl_->receiverMessage);
    }

    const auto samplesToRead = std::min(maxSamples, impl_->receiveSizeSamples);
    buffer.resizeSamples(samplesToRead);
    auto* destination = buffer.data();
    std::size_t copiedSamples = 0;
    while (copiedSamples < samplesToRead) {
        const auto spanSamples = std::min(
                samplesToRead - copiedSamples,
                impl_->receiveCapacitySamples - impl_->receiveStartSample);
        const auto valuesToCopy = spanSamples * SampleBuffer::kValuesPerIqSample;
        const auto* source = impl_->receiveRing.data() +
                (impl_->receiveStartSample * SampleBuffer::kValuesPerIqSample);
        std::copy(source, source + valuesToCopy,
                  destination + copiedSamples * SampleBuffer::kValuesPerIqSample);
        impl_->receiveStartSample =
                (impl_->receiveStartSample + spanSamples) % impl_->receiveCapacitySamples;
        impl_->receiveSizeSamples -= spanSamples;
        copiedSamples += spanSamples;
    }

    return makeReadResult(
            samplesToRead,
            impl_->receiverEnded && impl_->receiveSizeSamples == 0,
            SampleSourceError::None,
            {});
}

void PlutoWebSocketSource::close() {
    stopReceiver();
    impl_->streamOpen = false;
    impl_->receiveRing.clear();
    impl_->receiveCapacitySamples = 0;
    impl_->receiveStartSample = 0;
    impl_->receiveSizeSamples = 0;
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
    return readFromReceiveBuffer(buffer, maxSamples);
}

}  // namespace sdr
