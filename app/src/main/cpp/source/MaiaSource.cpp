#include "MaiaSource.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstring>
#include <cstdlib>
#include <limits>
#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include <android/log.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

namespace sdr {
namespace {

constexpr const char* kLogTag = "SDRVideoScanner.Maia";
constexpr const char* kRecorderPath = "/api/recorder";
constexpr const char* kIqDataPath = "/api/datasources/maiasdr/maiasdr/recording/iq-data";
constexpr const char* kRecorderBody =
        R"({"mode":"IQ8bit","prepend_timestamp":false,"maximum_duration":0})";
constexpr std::size_t kMaxHeaderBytes = 64U * 1024U;

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

SourceStatus callAndroidHttpStringMethod(jobject transport, const char* methodName) {
    if (transport == nullptr) {
        return makeStatus(SampleSourceError::OpenFailed, "Android HTTP transport object is not set");
    }

    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return makeStatus(SampleSourceError::OpenFailed, "Android HTTP transport is unavailable: JavaVM is not set");
    }

    auto* cls = jni.env->GetObjectClass(transport);
    if (cls == nullptr) {
        const auto exception = javaExceptionMessage(jni.env, "GetObjectClass MaiaHttpTransport");
        return makeStatus(SampleSourceError::OpenFailed, exception.empty() ? "Android HTTP transport class was not found" : exception);
    }

    auto* method = jni.env->GetMethodID(cls, methodName, "()Ljava/lang/String;");
    if (method == nullptr) {
        jni.env->DeleteLocalRef(cls);
        const auto exception = javaExceptionMessage(jni.env, methodName);
        return makeStatus(
                SampleSourceError::OpenFailed,
                exception.empty()
                        ? std::string("Android HTTP transport method was not found: ") + methodName
                        : exception);
    }

    auto* error = static_cast<jstring>(jni.env->CallObjectMethod(transport, method));
    jni.env->DeleteLocalRef(cls);
    return javaStringStatus(error, jni.env, methodName);
}

void callAndroidHttpVoidMethod(jobject transport, const char* methodName) {
    if (transport == nullptr) {
        return;
    }

    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return;
    }

    auto* cls = jni.env->GetObjectClass(transport);
    if (cls == nullptr) {
        javaExceptionMessage(jni.env, "GetObjectClass MaiaHttpTransport");
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

std::string androidHttpLastError(jobject transport) {
    if (transport == nullptr) {
        return "Android HTTP transport object is not set";
    }

    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return "Android HTTP transport is unavailable: JavaVM is not set";
    }

    auto* cls = jni.env->GetObjectClass(transport);
    if (cls == nullptr) {
        const auto exception = javaExceptionMessage(jni.env, "GetObjectClass MaiaHttpTransport");
        return exception.empty() ? "Android HTTP transport class was not found" : exception;
    }
    auto* method = jni.env->GetMethodID(cls, "lastError", "()Ljava/lang/String;");
    if (method == nullptr) {
        jni.env->DeleteLocalRef(cls);
        const auto exception = javaExceptionMessage(jni.env, "lastError");
        return exception.empty() ? "Android HTTP transport lastError method was not found" : exception;
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

ReadResult readAndroidHttpTransport(
        jobject transport,
        std::vector<std::uint8_t>& destination,
        std::size_t targetBytes) {
    if (transport == nullptr) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Android HTTP transport object is not set");
    }
    if (targetBytes > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Maia read target is too large for Android HTTP transport");
    }

    auto jni = currentJniEnv();
    if (jni.env == nullptr) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Android HTTP transport is unavailable: JavaVM is not set");
    }

    auto* cls = jni.env->GetObjectClass(transport);
    if (cls == nullptr) {
        const auto exception = javaExceptionMessage(jni.env, "GetObjectClass MaiaHttpTransport");
        return makeReadResult(
                0,
                false,
                SampleSourceError::ReadFailed,
                exception.empty() ? "Android HTTP transport class was not found" : exception);
    }
    auto* method = jni.env->GetMethodID(cls, "read", "([BI)I");
    if (method == nullptr) {
        jni.env->DeleteLocalRef(cls);
        const auto exception = javaExceptionMessage(jni.env, "read");
        return makeReadResult(
                0,
                false,
                SampleSourceError::ReadFailed,
                exception.empty() ? "Android HTTP transport read method was not found" : exception);
    }

    auto* buffer = jni.env->NewByteArray(static_cast<jsize>(targetBytes));
    if (buffer == nullptr) {
        jni.env->DeleteLocalRef(cls);
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Failed to allocate Android HTTP read buffer");
    }

    const auto bytesRead = jni.env->CallIntMethod(
            transport,
            method,
            buffer,
            static_cast<jint>(targetBytes));
    jni.env->DeleteLocalRef(cls);

    if (const auto exception = javaExceptionMessage(jni.env, "read"); !exception.empty()) {
        jni.env->DeleteLocalRef(buffer);
        return makeReadResult(0, false, SampleSourceError::ReadFailed, exception);
    }
    if (bytesRead < 0) {
        jni.env->DeleteLocalRef(buffer);
        return makeReadResult(0, false, SampleSourceError::ReadFailed, androidHttpLastError(transport));
    }
    if (bytesRead == 0) {
        jni.env->DeleteLocalRef(buffer);
        return makeReadResult(0, true, SampleSourceError::ReadFailed, "Maia Android HTTP stream closed");
    }
    if ((bytesRead % 2) != 0) {
        jni.env->DeleteLocalRef(buffer);
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Malformed Maia CS8 stream: Android HTTP read returned an incomplete I/Q pair");
    }

    destination.resize(static_cast<std::size_t>(bytesRead));
    jni.env->GetByteArrayRegion(
            buffer,
            0,
            bytesRead,
            reinterpret_cast<jbyte*>(destination.data()));
    jni.env->DeleteLocalRef(buffer);

    if (const auto exception = javaExceptionMessage(jni.env, "GetByteArrayRegion"); !exception.empty()) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, exception);
    }

    return makeReadResult(static_cast<std::size_t>(bytesRead) / 2U, false, SampleSourceError::None, {});
}

std::string errnoMessage(const char* action) {
    std::ostringstream message;
    message << action << ": " << std::strerror(errno) << " (" << errno << ")";
    return message.str();
}

std::string socketAddressToString(const sockaddr* address, socklen_t length) {
    char host[NI_MAXHOST] = {};
    char service[NI_MAXSERV] = {};
    const auto result = getnameinfo(
            address,
            length,
            host,
            sizeof(host),
            service,
            sizeof(service),
            NI_NUMERICHOST | NI_NUMERICSERV);
    if (result != 0) {
        return std::string("getnameinfo failed: ") + gai_strerror(result);
    }
    return std::string(host) + ":" + service;
}

std::string boundSocketDiagnostic(int fd, std::uint64_t networkHandle) {
    std::ostringstream message;
    message << "android_network_handle=" << networkHandle;

    sockaddr_storage local{};
    socklen_t localLength = sizeof(local);
    if (getsockname(fd, reinterpret_cast<sockaddr*>(&local), &localLength) == 0) {
        message << ", local=" << socketAddressToString(reinterpret_cast<sockaddr*>(&local), localLength);
    } else {
        message << ", local_error=" << errnoMessage("getsockname");
    }

    sockaddr_storage remote{};
    socklen_t remoteLength = sizeof(remote);
    if (getpeername(fd, reinterpret_cast<sockaddr*>(&remote), &remoteLength) == 0) {
        message << ", remote=" << socketAddressToString(reinterpret_cast<sockaddr*>(&remote), remoteLength);
    } else {
        message << ", remote_error=" << errnoMessage("getpeername");
    }

    return message.str();
}

std::string androidNetworkErrorMessage(const char* action, int result) {
    std::ostringstream message;
    message << action << " failed";
    if (result != 0) {
        message << ": result=" << result;
    }
    if (errno != 0) {
        message << ", errno=" << errno << " (" << std::strerror(errno) << ")";
    }
    return message.str();
}

std::string lowerAscii(std::string value) {
    for (auto& ch : value) {
        if (ch >= 'A' && ch <= 'Z') {
            ch = static_cast<char>(ch - 'A' + 'a');
        }
    }
    return value;
}

int waitForFd(int fd, short events, int timeoutMs) {
    pollfd pfd{};
    pfd.fd = fd;
    pfd.events = events;
    while (true) {
        const auto result = poll(&pfd, 1, timeoutMs);
        if (result < 0 && errno == EINTR) {
            continue;
        }
        return result;
    }
}

bool setNonBlocking(int fd, bool nonBlocking) {
    const auto flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) {
        return false;
    }
    const auto nextFlags = nonBlocking ? (flags | O_NONBLOCK) : (flags & ~O_NONBLOCK);
    return fcntl(fd, F_SETFL, nextFlags) == 0;
}

void closeFd(int& fd) {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

bool appendFromPending(std::vector<std::uint8_t>& pending, std::uint8_t* destination, std::size_t& copied, std::size_t target) {
    if (pending.empty() || copied >= target) {
        return copied == target;
    }

    const auto take = std::min(target - copied, pending.size());
    std::copy_n(pending.begin(), take, destination + copied);
    pending.erase(pending.begin(), pending.begin() + static_cast<std::ptrdiff_t>(take));
    copied += take;
    return copied == target;
}

struct HttpResponse {
    int statusCode = 0;
    std::string headers;
    std::vector<std::uint8_t> bodyPrefix;
    bool chunked = false;
};

class HttpConnection {
public:
    ~HttpConnection() {
        close();
    }

    SourceStatus connectTo(const MaiaSourceConfig& config) {
        close();

        addrinfo hints{};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;

        const auto port = std::to_string(config.port);
        addrinfo* results = nullptr;
        const auto gaiResult = getaddrinfo(config.host.c_str(), port.c_str(), &hints, &results);
        if (gaiResult != 0) {
            return makeStatus(
                    SampleSourceError::OpenFailed,
                    std::string("Maia DNS/host lookup failed: ") + gai_strerror(gaiResult));
        }
        std::unique_ptr<addrinfo, decltype(&freeaddrinfo)> resultsGuard(results, freeaddrinfo);

        std::string lastError = "no address candidates";
        for (auto* addr = results; addr != nullptr; addr = addr->ai_next) {
            fd_ = socket(addr->ai_family, addr->ai_socktype, addr->ai_protocol);
            if (fd_ < 0) {
                lastError = errnoMessage("socket");
                continue;
            }

            if (!setNonBlocking(fd_, true)) {
                lastError = errnoMessage("fcntl nonblock");
                close();
                continue;
            }

            const auto connectResult = connect(fd_, addr->ai_addr, addr->ai_addrlen);
            if (connectResult == 0 || errno == EINPROGRESS) {
                const auto waitResult = waitForFd(fd_, POLLOUT, config.connectTimeoutMs);
                if (waitResult > 0) {
                    int error = 0;
                    socklen_t errorSize = sizeof(error);
                    if (getsockopt(fd_, SOL_SOCKET, SO_ERROR, &error, &errorSize) == 0 && error == 0) {
                        setNonBlocking(fd_, false);
                        logInfo("Maia native fallback HTTP socket connected");
                        return {};
                    }
                    errno = error;
                    lastError = errnoMessage("connect");
                } else if (waitResult == 0) {
                    lastError = "connect timeout after " + std::to_string(config.connectTimeoutMs) + " ms";
                } else {
                    lastError = errnoMessage("poll connect");
                }
            } else {
                lastError = errnoMessage("connect");
            }

            close();
        }

        return makeStatus(
                SampleSourceError::OpenFailed,
                "Maia HTTP connection failed to " + config.host + ":" + std::to_string(config.port) + ": " + lastError);
    }

    void close() {
        closeFd(fd_);
        pending_.clear();
        chunkRemaining_ = 0;
    }

    bool isOpen() const {
        return fd_ >= 0;
    }

    SourceStatus writeAll(const std::string& request, int timeoutMs) {
        const char* data = request.data();
        std::size_t written = 0;
        while (written < request.size()) {
            const auto waitResult = waitForFd(fd_, POLLOUT, timeoutMs);
            if (waitResult == 0) {
                return makeStatus(SampleSourceError::OpenFailed, "HTTP write timeout");
            }
            if (waitResult < 0) {
                return makeStatus(SampleSourceError::OpenFailed, errnoMessage("poll write"));
            }
            const auto result = send(fd_, data + written, request.size() - written, MSG_NOSIGNAL);
            if (result < 0) {
                if (errno == EINTR) {
                    continue;
                }
                return makeStatus(SampleSourceError::OpenFailed, errnoMessage("send"));
            }
            if (result == 0) {
                return makeStatus(SampleSourceError::OpenFailed, "HTTP socket closed during write");
            }
            written += static_cast<std::size_t>(result);
        }
        return {};
    }

    SourceStatus readResponseHeaders(int timeoutMs, HttpResponse& response) {
        std::vector<std::uint8_t> received;
        received.reserve(4096);
        char buffer[2048] = {};

        while (received.size() < kMaxHeaderBytes) {
            const auto waitResult = waitForFd(fd_, POLLIN, timeoutMs);
            if (waitResult == 0) {
                return makeStatus(SampleSourceError::OpenFailed, "HTTP response header timeout");
            }
            if (waitResult < 0) {
                return makeStatus(SampleSourceError::OpenFailed, errnoMessage("poll read headers"));
            }

            const auto result = recv(fd_, buffer, sizeof(buffer), 0);
            if (result < 0) {
                if (errno == EINTR) {
                    continue;
                }
                return makeStatus(SampleSourceError::OpenFailed, errnoMessage("recv headers"));
            }
            if (result == 0) {
                return makeStatus(SampleSourceError::OpenFailed, "HTTP connection closed before headers");
            }

            received.insert(received.end(), buffer, buffer + result);
            const std::string text(received.begin(), received.end());
            const auto headerEnd = text.find("\r\n\r\n");
            if (headerEnd != std::string::npos) {
                response.headers = text.substr(0, headerEnd + 4U);
                response.bodyPrefix.assign(
                        received.begin() + static_cast<std::ptrdiff_t>(headerEnd + 4U),
                        received.end());
                pending_ = response.bodyPrefix;
                parseHeaders(response);
                return {};
            }
        }

        return makeStatus(SampleSourceError::OpenFailed, "HTTP response headers exceeded limit");
    }

    ReadResult readBody(std::uint8_t* destination, std::size_t targetBytes, int timeoutMs, bool chunked) {
        std::size_t copied = 0;
        if (chunked) {
            return readChunked(destination, targetBytes, timeoutMs);
        }

        appendFromPending(pending_, destination, copied, targetBytes);
        while (copied < targetBytes) {
            const auto waitResult = waitForFd(fd_, POLLIN, timeoutMs);
            if (waitResult == 0) {
                return makeReadResult(copied / 2U, false, SampleSourceError::ReadFailed, "Maia stream read timeout");
            }
            if (waitResult < 0) {
                return makeReadResult(copied / 2U, false, SampleSourceError::ReadFailed, errnoMessage("poll stream"));
            }

            const auto result = recv(fd_, destination + copied, targetBytes - copied, 0);
            if (result < 0) {
                if (errno == EINTR) {
                    continue;
                }
                return makeReadResult(copied / 2U, false, SampleSourceError::ReadFailed, errnoMessage("recv stream"));
            }
            if (result == 0) {
                return makeReadResult(copied / 2U, true, SampleSourceError::ReadFailed, "Maia stream closed");
            }
            copied += static_cast<std::size_t>(result);
        }
        return makeReadResult(copied / 2U, false, SampleSourceError::None, {});
    }

private:
    static void parseHeaders(HttpResponse& response) {
        std::istringstream stream(response.headers);
        std::string statusLine;
        std::getline(stream, statusLine);
        std::istringstream statusStream(statusLine);
        std::string httpVersion;
        statusStream >> httpVersion >> response.statusCode;
        response.chunked = lowerAscii(response.headers).find("transfer-encoding: chunked") != std::string::npos;
    }

    ReadResult readChunked(std::uint8_t* destination, std::size_t targetBytes, int timeoutMs) {
        std::size_t copied = 0;
        while (copied < targetBytes) {
            if (chunkRemaining_ == 0) {
                auto chunkStatus = readNextChunkSize(timeoutMs);
                if (!chunkStatus.ok()) {
                    return makeReadResult(copied / 2U, false, chunkStatus.error, chunkStatus.message);
                }
                if (chunkRemaining_ == 0) {
                    return makeReadResult(copied / 2U, true, SampleSourceError::ReadFailed, "Maia chunked stream ended");
                }
            }

            if (pending_.empty()) {
                char temp[4096] = {};
                const auto waitResult = waitForFd(fd_, POLLIN, timeoutMs);
                if (waitResult == 0) {
                    return makeReadResult(copied / 2U, false, SampleSourceError::ReadFailed, "Maia chunked stream timeout");
                }
                if (waitResult < 0) {
                    return makeReadResult(copied / 2U, false, SampleSourceError::ReadFailed, errnoMessage("poll chunk"));
                }
                const auto result = recv(fd_, temp, sizeof(temp), 0);
                if (result <= 0) {
                    return makeReadResult(copied / 2U, result == 0, SampleSourceError::ReadFailed, result == 0 ? "Maia chunked stream closed" : errnoMessage("recv chunk"));
                }
                pending_.insert(pending_.end(), temp, temp + result);
            }

            const auto take = std::min({targetBytes - copied, chunkRemaining_, pending_.size()});
            std::copy_n(pending_.begin(), take, destination + copied);
            pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(take));
            copied += take;
            chunkRemaining_ -= take;

            if (chunkRemaining_ == 0) {
                auto crlfStatus = discardChunkCrlf(timeoutMs);
                if (!crlfStatus.ok()) {
                    return makeReadResult(copied / 2U, false, crlfStatus.error, crlfStatus.message);
                }
            }
        }
        return makeReadResult(copied / 2U, false, SampleSourceError::None, {});
    }

    SourceStatus fillPendingUntil(const std::string& marker, int timeoutMs) {
        while (true) {
            const std::string text(pending_.begin(), pending_.end());
            if (text.find(marker) != std::string::npos) {
                return {};
            }
            char temp[1024] = {};
            const auto waitResult = waitForFd(fd_, POLLIN, timeoutMs);
            if (waitResult == 0) {
                return makeStatus(SampleSourceError::ReadFailed, "Maia chunk header timeout");
            }
            if (waitResult < 0) {
                return makeStatus(SampleSourceError::ReadFailed, errnoMessage("poll chunk header"));
            }
            const auto result = recv(fd_, temp, sizeof(temp), 0);
            if (result <= 0) {
                return makeStatus(SampleSourceError::ReadFailed, result == 0 ? "Maia chunk header stream closed" : errnoMessage("recv chunk header"));
            }
            pending_.insert(pending_.end(), temp, temp + result);
        }
    }

    SourceStatus readNextChunkSize(int timeoutMs) {
        auto status = fillPendingUntil("\r\n", timeoutMs);
        if (!status.ok()) {
            return status;
        }

        const std::string text(pending_.begin(), pending_.end());
        const auto lineEnd = text.find("\r\n");
        if (lineEnd == std::string::npos) {
            return makeStatus(SampleSourceError::ReadFailed, "Malformed chunk header");
        }
        const auto line = text.substr(0, lineEnd);
        char* end = nullptr;
        errno = 0;
        const auto size = std::strtoull(line.c_str(), &end, 16);
        if (errno != 0 || end == line.c_str()) {
            return makeStatus(SampleSourceError::ReadFailed, "Malformed chunk size: " + line);
        }
        pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(lineEnd + 2U));
        chunkRemaining_ = static_cast<std::size_t>(size);
        return {};
    }

    SourceStatus discardChunkCrlf(int timeoutMs) {
        auto status = fillPendingUntil("\r\n", timeoutMs);
        if (!status.ok()) {
            return status;
        }
        if (pending_.size() < 2U || pending_[0] != '\r' || pending_[1] != '\n') {
            return makeStatus(SampleSourceError::ReadFailed, "Malformed chunk terminator");
        }
        pending_.erase(pending_.begin(), pending_.begin() + 2);
        return {};
    }

    int fd_ = -1;
    std::vector<std::uint8_t> pending_;
    std::size_t chunkRemaining_ = 0;
};

std::string httpHostHeader(const MaiaSourceConfig& config) {
    if (config.port == 80) {
        return config.host;
    }
    return config.host + ":" + std::to_string(config.port);
}

std::string makePatchRequest(const MaiaSourceConfig& config) {
    std::ostringstream request;
    request << "PATCH " << kRecorderPath << " HTTP/1.1\r\n"
            << "Host: " << httpHostHeader(config) << "\r\n"
            << "User-Agent: SDRVideoScanner/1.0\r\n"
            << "Content-Type: application/json\r\n"
            << "Accept: application/json,*/*\r\n"
            << "Connection: close\r\n"
            << "Content-Length: " << std::strlen(kRecorderBody) << "\r\n\r\n"
            << kRecorderBody;
    return request.str();
}

std::string makeGetRequest(const MaiaSourceConfig& config) {
    std::ostringstream request;
    request << "GET " << kIqDataPath << " HTTP/1.1\r\n"
            << "Host: " << httpHostHeader(config) << "\r\n"
            << "User-Agent: SDRVideoScanner/1.0\r\n"
            << "Accept: application/octet-stream,*/*\r\n"
            << "Connection: keep-alive\r\n\r\n";
    return request.str();
}

}  // namespace

struct MaiaSource::Impl {
    HttpConnection stream;
    std::vector<std::uint8_t> readScratch;
    std::uint64_t bytesReceived = 0;
    std::uint64_t samplesReceived = 0;
    std::uint64_t dropoutCount = 0;
    bool streamChunked = false;
    bool androidHttpStreamOpen = false;
    std::chrono::steady_clock::time_point statsWindowStart = std::chrono::steady_clock::now();
};

void setMaiaSourceJavaVm(JavaVM* javaVm) {
    gJavaVm = javaVm;
}

MaiaSource::MaiaSource(MaiaSourceConfig config)
        : config_(std::move(config)),
          impl_(std::make_unique<Impl>()) {
    format_.encoding = SampleEncoding::Cs8;
    format_.sampleRateHz = config_.sampleRateHz;
    format_.channelCount = 1;
}

MaiaSource::~MaiaSource() {
    close();
    if (config_.androidHttpTransport != nullptr) {
        auto jni = currentJniEnv();
        if (jni.env != nullptr) {
            jni.env->DeleteGlobalRef(config_.androidHttpTransport);
        }
        config_.androidHttpTransport = nullptr;
    }
}

SourceStatus MaiaSource::open() {
    close();
    if (config_.host.empty()) {
        return makeStatus(SampleSourceError::InvalidArgument, "Maia host is empty");
    }
    if (config_.sampleRateHz == 0) {
        return makeStatus(SampleSourceError::InvalidArgument, "Maia sample rate must be set");
    }

    auto recorderStatus = configureRecorder();
    if (!recorderStatus.ok()) {
        return recorderStatus;
    }
    return openStream();
}

void MaiaSource::close() {
    if (config_.androidHttpTransport != nullptr && impl_->androidHttpStreamOpen) {
        callAndroidHttpVoidMethod(config_.androidHttpTransport, "close");
        impl_->androidHttpStreamOpen = false;
    }
    impl_->stream.close();
    impl_->readScratch.clear();
}

bool MaiaSource::isOpen() const {
    if (config_.androidHttpTransport != nullptr) {
        return impl_->androidHttpStreamOpen;
    }
    return impl_->stream.isOpen();
}

SampleFormat MaiaSource::format() const {
    return format_;
}

ReadResult MaiaSource::read(SampleBuffer& buffer, std::size_t maxSamples) {
    buffer.clear();
    if (!isOpen()) {
        return makeReadResult(0, false, SampleSourceError::NotOpen, "Maia source is not open");
    }
    if (maxSamples == 0) {
        return {};
    }

    const auto targetBytes = maxSamples * SampleBuffer::kValuesPerIqSample;
    impl_->readScratch.resize(targetBytes);
    auto readResult = config_.androidHttpTransport != nullptr
            ? readAndroidHttpTransport(config_.androidHttpTransport, impl_->readScratch, targetBytes)
            : impl_->stream.readBody(
                    impl_->readScratch.data(),
                    targetBytes,
                    config_.readTimeoutMs,
                    impl_->streamChunked);

    if (!readResult.ok() || readResult.samplesRead == 0) {
        ++impl_->dropoutCount;
        logWarn("Maia read dropout: " + readResult.message);
        const auto reconnectStatus = reconnectStream();
        if (!reconnectStatus.ok()) {
            return makeReadResult(0, false, reconnectStatus.error, reconnectStatus.message);
        }
        impl_->readScratch.resize(targetBytes);
        readResult = config_.androidHttpTransport != nullptr
                ? readAndroidHttpTransport(config_.androidHttpTransport, impl_->readScratch, targetBytes)
                : impl_->stream.readBody(
                        impl_->readScratch.data(),
                        targetBytes,
                        config_.readTimeoutMs,
                        impl_->streamChunked);
    }

    const auto completeBytes = readResult.samplesRead * SampleBuffer::kValuesPerIqSample;
    if (completeBytes == 0 || (completeBytes % SampleBuffer::kValuesPerIqSample) != 0) {
        return makeReadResult(0, false, SampleSourceError::ReadFailed, "Malformed Maia CS8 stream: incomplete I/Q pair");
    }

    buffer.resizeSamples(readResult.samplesRead);
    for (std::size_t index = 0; index < readResult.samplesRead * SampleBuffer::kValuesPerIqSample; ++index) {
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
        message << "Maia stream: " << static_cast<std::uint64_t>(bytesPerSec)
                << " B/s, estimated_sample_rate=" << static_cast<std::uint64_t>(samplesPerSec)
                << " sps, dropouts=" << impl_->dropoutCount;
        logInfo(message.str());
        impl_->bytesReceived = 0;
        impl_->samplesReceived = 0;
        impl_->statsWindowStart = now;
    }

    return makeReadResult(readResult.samplesRead, false, SampleSourceError::None, {});
}

SourceStatus MaiaSource::configureRecorder() {
    if (config_.androidHttpTransport != nullptr) {
        auto status = callAndroidHttpStringMethod(config_.androidHttpTransport, "configureRecorder");
        if (status.ok()) {
            logInfo("Maia recorder configured through Android Network.openConnection transport");
        }
        return status;
    }

    HttpConnection control;
    auto status = control.connectTo(config_);
    if (!status.ok()) {
        return status;
    }
    status = control.writeAll(makePatchRequest(config_), config_.connectTimeoutMs);
    if (!status.ok()) {
        return status;
    }

    HttpResponse response;
    status = control.readResponseHeaders(config_.readTimeoutMs, response);
    if (!status.ok()) {
        return status;
    }

    std::ostringstream log;
    log << "Maia recorder PATCH response status=" << response.statusCode
        << ", headers=" << response.headers;
    if (!response.bodyPrefix.empty()) {
        const auto bodyTextSize = std::min<std::size_t>(response.bodyPrefix.size(), 512U);
        log << ", body_prefix_bytes=" << response.bodyPrefix.size()
            << ", body_prefix="
            << std::string(response.bodyPrefix.begin(), response.bodyPrefix.begin() + static_cast<std::ptrdiff_t>(bodyTextSize));
    }
    logInfo(log.str());

    if (response.statusCode < 200 || response.statusCode >= 300) {
        return makeStatus(SampleSourceError::OpenFailed, "Maia recorder configuration HTTP error: " + std::to_string(response.statusCode));
    }
    return {};
}

SourceStatus MaiaSource::openStream() {
    if (config_.androidHttpTransport != nullptr) {
        auto status = callAndroidHttpStringMethod(config_.androidHttpTransport, "openStream");
        if (!status.ok()) {
            impl_->androidHttpStreamOpen = false;
            return status;
        }
        impl_->androidHttpStreamOpen = true;
        impl_->streamChunked = false;
        impl_->statsWindowStart = std::chrono::steady_clock::now();
        impl_->bytesReceived = 0;
        impl_->samplesReceived = 0;
        logInfo("Maia IQ CS8 stream connected through Android Network.openConnection transport to " + config_.host + ":" + std::to_string(config_.port));
        return {};
    }

    auto status = impl_->stream.connectTo(config_);
    if (!status.ok()) {
        return status;
    }
    status = impl_->stream.writeAll(makeGetRequest(config_), config_.connectTimeoutMs);
    if (!status.ok()) {
        impl_->stream.close();
        return status;
    }

    HttpResponse response;
    status = impl_->stream.readResponseHeaders(config_.readTimeoutMs, response);
    if (!status.ok()) {
        impl_->stream.close();
        return status;
    }

    std::ostringstream log;
    log << "Maia IQ stream GET response status=" << response.statusCode
        << ", chunked=" << (response.chunked ? "true" : "false")
        << ", body_prefix_bytes=" << response.bodyPrefix.size();
    logInfo(log.str());

    if (response.statusCode < 200 || response.statusCode >= 300) {
        impl_->stream.close();
        return makeStatus(SampleSourceError::OpenFailed, "Maia IQ stream HTTP error: " + std::to_string(response.statusCode));
    }

    impl_->streamChunked = response.chunked;
    impl_->statsWindowStart = std::chrono::steady_clock::now();
    impl_->bytesReceived = 0;
    impl_->samplesReceived = 0;
    logInfo("Maia IQ CS8 stream connected to " + config_.host + ":" + std::to_string(config_.port));
    return {};
}

SourceStatus MaiaSource::reconnectStream() {
    if (config_.androidHttpTransport != nullptr) {
        callAndroidHttpVoidMethod(config_.androidHttpTransport, "close");
        impl_->androidHttpStreamOpen = false;
    } else {
        impl_->stream.close();
    }
    SourceStatus lastStatus{SampleSourceError::OpenFailed, "Maia reconnect not attempted"};
    const auto attempts = std::max(0, config_.reconnectAttempts);
    for (int attempt = 0; attempt < attempts; ++attempt) {
        logWarn("Attempting Maia stream reconnect " + std::to_string(attempt + 1) + "/" + std::to_string(attempts));
        lastStatus = openStream();
        if (lastStatus.ok()) {
            return {};
        }
    }
    return makeStatus(SampleSourceError::ReadFailed, "Maia stream reconnect failed: " + lastStatus.message);
}

}  // namespace sdr
