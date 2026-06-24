#include "AnalogVideoDecoder.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <vector>

namespace sdr {
namespace {

using DecodeClock = std::chrono::steady_clock;

struct DecodeStageTimings {
    double sourceReadMs = 0.0;
    double demodMs = 0.0;
    double meanMs = 0.0;
    double lowPassMs = 0.0;
    double normalizeMs = 0.0;
    double syncMs = 0.0;
    double assembleMs = 0.0;
    double totalMs = 0.0;
};

double elapsedMs(DecodeClock::time_point start, DecodeClock::time_point end) {
    return std::chrono::duration<double, std::milli>(end - start).count();
}

std::string timingDiagnostic(
        const DecodeStageTimings& timings,
        std::size_t sourceSamplesRead,
        std::size_t videoSampleCount,
        std::size_t decimation,
        std::uint64_t videoSampleRateHz) {
    std::ostringstream message;
    message << std::fixed << std::setprecision(2)
            << "; timing_total_ms=" << timings.totalMs
            << "; timing_source_read_ms=" << timings.sourceReadMs
            << "; timing_fm_demod_ms=" << timings.demodMs
            << "; timing_mean_ms=" << timings.meanMs
            << "; timing_lowpass_ms=" << timings.lowPassMs
            << "; timing_normalize_ms=" << timings.normalizeMs
            << "; timing_sync_ms=" << timings.syncMs
            << "; timing_assemble_ms=" << timings.assembleMs
            << "; timing_source_samples=" << sourceSamplesRead
            << "; timing_video_samples=" << videoSampleCount
            << "; timing_decimation=" << decimation
            << "; timing_video_sample_rate_hz=" << videoSampleRateHz;
    return message.str();
}

void subtractMean(std::vector<float>& samples) {
    if (samples.empty()) {
        return;
    }

    double sum = 0.0;
    for (const auto sample : samples) {
        sum += sample;
    }

    const float mean = static_cast<float>(sum / static_cast<double>(samples.size()));
    for (auto& sample : samples) {
        sample -= mean;
    }
}

std::size_t decimationFactor(std::uint64_t inputRateHz, std::uint64_t analysisRateHz) {
    return static_cast<std::size_t>(
            std::max(1.0, std::floor(static_cast<double>(inputRateHz) /
                                     static_cast<double>(analysisRateHz))));
}

std::uint64_t decimatedRate(std::uint64_t inputRateHz, std::uint64_t analysisRateHz) {
    const auto decimation = static_cast<std::uint64_t>(
            decimationFactor(inputRateHz, analysisRateHz));
    return inputRateHz / decimation;
}

}  // namespace

AnalogVideoDecoder::AnalogVideoDecoder(AnalogVideoDecoderConfig config)
        : config_(config),
          frameAssembler_(config.timing) {}

VideoFrame AnalogVideoDecoder::decodeOneFrame(ISampleSource& source) {
    const auto decodeStart = DecodeClock::now();
    DecodeStageTimings timings;
    VideoFrame errorFrame;
    if (config_.sampleRateHz == 0) {
        errorFrame.message = "sample_rate_hz metadata is required";
        return errorFrame;
    }

    const auto targetSamples = frameSampleCount();
    if (targetSamples == 0) {
        errorFrame.message = "invalid analog video timing configuration";
        return errorFrame;
    }

    const auto decimation = decimationFactor(config_.sampleRateHz, config_.analysisRateHz);
    const auto videoSampleRateHz = decimatedRate(config_.sampleRateHz, config_.analysisRateHz);

    videoBaseband_.clear();
    videoBaseband_.reserve((targetSamples + decimation - 1U) / decimation);
    fmDemodulator_.reset();

    std::size_t sourceSamplesRead = 0;
    while (sourceSamplesRead < targetSamples) {
        const auto samplesToRead = std::min(config_.readBlockSamples, targetSamples - sourceSamplesRead);
        const auto readStart = DecodeClock::now();
        const auto readResult = source.read(sampleBuffer_, samplesToRead);
        const auto readEnd = DecodeClock::now();
        timings.sourceReadMs += elapsedMs(readStart, readEnd);
        if (!readResult.ok()) {
            timings.totalMs = elapsedMs(decodeStart, DecodeClock::now());
            errorFrame.message = readResult.message +
                    timingDiagnostic(
                            timings,
                            sourceSamplesRead,
                            videoBaseband_.size(),
                            decimation,
                            videoSampleRateHz);
            return errorFrame;
        }

        if (readResult.samplesRead == 0) {
            break;
        }

        const auto demodStart = DecodeClock::now();
        fmDemodulator_.appendDecimatedDiscriminator(
                sampleBuffer_,
                readResult.samplesRead,
                decimation,
                videoBaseband_);
        timings.demodMs += elapsedMs(demodStart, DecodeClock::now());
        sourceSamplesRead += readResult.samplesRead;
        if (readResult.endOfStream) {
            break;
        }
    }

    if (videoBaseband_.empty()) {
        timings.totalMs = elapsedMs(decodeStart, DecodeClock::now());
        errorFrame.message = "input did not contain complete IQ samples" +
                timingDiagnostic(
                        timings,
                        sourceSamplesRead,
                        videoBaseband_.size(),
                        decimation,
                        videoSampleRateHz);
        return errorFrame;
    }

    const auto meanStart = DecodeClock::now();
    subtractMean(videoBaseband_);
    timings.meanMs = elapsedMs(meanStart, DecodeClock::now());

    const auto normalizeStart = DecodeClock::now();
    normalizer_.normalize(videoBaseband_, video_);
    timings.normalizeMs = elapsedMs(normalizeStart, DecodeClock::now());

    const auto maxSyncs = config_.fastFieldPreview
            ? (std::max<std::size_t>(
                    static_cast<std::size_t>((config_.timing.visibleLines + 1U) / 2U) + 32U,
                    static_cast<std::size_t>(config_.timing.totalLines) *
                                    std::max<std::size_t>(1U, config_.fastPreviewFieldStride) /
                                    2U +
                            64U))
            : (static_cast<std::size_t>(config_.timing.totalLines) * 2U);
    const auto syncStart = DecodeClock::now();
    const auto syncDetection = config_.fastFieldPreview && !config_.detectFrameSyncInFastPreview
            ? syncDetector_.detectHorizontalSyncsFast(
                    video_,
                    videoSampleRateHz,
                    config_.timing.lineRateHz,
                    maxSyncs)
            : syncDetector_.detectHorizontalSyncs(
                    video_,
                    videoSampleRateHz,
                    config_.timing.lineRateHz,
                    maxSyncs);
    timings.syncMs = elapsedMs(syncStart, DecodeClock::now());

    if (syncDetection.syncIsHigh) {
        for (auto& sample : video_) {
            sample = static_cast<std::uint8_t>(255U - sample);
        }
    }

    const auto minimumUsefulSyncs = config_.fastFieldPreview
            ? static_cast<std::size_t>(
                    std::max(8.0,
                             std::round(static_cast<double>(config_.timing.visibleLines) * 0.20)))
            : static_cast<std::size_t>(
                    std::max(8.0,
                             std::round(static_cast<double>(config_.timing.totalLines) * 0.25)));
    if (syncDetection.syncStarts.size() >= minimumUsefulSyncs) {
        std::size_t fastFieldStartSyncIndex = 0;
        std::size_t fastFieldCandidateStartSyncIndex = 0;
        const auto assembleStart = DecodeClock::now();
        auto frame = config_.fastFieldPreview
                ? [&]() {
                    const auto candidateStartSyncIndex =
                            frameAssembler_.chooseFieldPreviewStartSyncIndex(
                                    video_,
                                    syncDetection.syncStarts,
                                    syncDetection.frameSyncEdges,
                                    videoSampleRateHz);
                    fastFieldCandidateStartSyncIndex = candidateStartSyncIndex;
                    fastFieldStartSyncIndex = lockedFastFieldStartSyncIndex(
                            candidateStartSyncIndex,
                            syncDetection.syncStarts.size());
                    return frameAssembler_.assembleFieldPreviewFromSync(
                        video_,
                        syncDetection.syncStarts,
                        syncDetection.frameSyncEdges,
                        fastFieldStartSyncIndex,
                        videoSampleRateHz);
                }()
                : frameAssembler_.assembleFromSync(
                        video_,
                        syncDetection.syncStarts,
                        syncDetection.frameSyncEdges,
                        videoSampleRateHz);
        timings.assembleMs = elapsedMs(assembleStart, DecodeClock::now());
        timings.totalMs = elapsedMs(decodeStart, DecodeClock::now());
        frame.syncIsHigh = syncDetection.syncIsHigh;
        frame.syncThreshold = syncDetection.threshold;
        frame.detectedFrameSyncCount = syncDetection.frameSyncEdges.size();
        frame.syncScore = syncDetection.score;
        frame.lineStabilityScore = syncDetection.lineStabilityScore;
        std::ostringstream message;
        message << frame.message
                << "; modulation=FM discriminator"
                << "; decoder_mode=" << (config_.fastFieldPreview ? "fast_field_preview" : "full_sync")
                << "; syncs=" << syncDetection.syncStarts.size()
                << "; frame_sync_edges=" << syncDetection.frameSyncEdges.size()
                << "; sync_polarity=" << (syncDetection.syncIsHigh ? "high" : "low")
                << "; sync_threshold=" << static_cast<int>(syncDetection.threshold)
                << "; sync_score=" << syncDetection.score
                << "; line_stability=" << syncDetection.lineStabilityScore;
        if (config_.fastFieldPreview) {
            message << "; field_start_sync=" << fastFieldStartSyncIndex
                    << "; field_start_candidate=" << fastFieldCandidateStartSyncIndex
                    << "; field_start_locked=" << (fastFieldStartLocked_ ? "yes" : "no")
                    << "; field_stride=" << std::max<std::size_t>(1U, config_.fastPreviewFieldStride);
        }
        message << timingDiagnostic(
                timings,
                sourceSamplesRead,
                videoBaseband_.size(),
                decimation,
                videoSampleRateHz);
        frame.message = message.str();
        return frame;
    }

    const auto assembleStart = DecodeClock::now();
    auto frame = frameAssembler_.assembleRawRaster(
            video_,
            videoSampleRateHz,
            syncDetection.syncStarts.size());
    timings.assembleMs = elapsedMs(assembleStart, DecodeClock::now());
    timings.totalMs = elapsedMs(decodeStart, DecodeClock::now());
    frame.syncIsHigh = syncDetection.syncIsHigh;
    frame.syncThreshold = syncDetection.threshold;
    frame.detectedFrameSyncCount = syncDetection.frameSyncEdges.size();
    frame.syncScore = syncDetection.score;
    frame.lineStabilityScore = syncDetection.lineStabilityScore;
    std::ostringstream message;
    message << frame.message
            << "; modulation=FM discriminator"
            << "; decoder_mode=" << (config_.fastFieldPreview ? "fast_field_preview" : "full_sync")
            << "; syncs=" << syncDetection.syncStarts.size()
            << "; frame_sync_edges=" << syncDetection.frameSyncEdges.size()
            << "; sync_polarity=" << (syncDetection.syncIsHigh ? "high" : "low")
            << "; sync_threshold=" << static_cast<int>(syncDetection.threshold)
            << "; sync_score=" << syncDetection.score
            << "; line_stability=" << syncDetection.lineStabilityScore;
    message << timingDiagnostic(
            timings,
            sourceSamplesRead,
            videoBaseband_.size(),
            decimation,
            videoSampleRateHz);
    frame.message = message.str();
    return frame;
}

std::size_t AnalogVideoDecoder::frameSampleCount() {
    if (config_.sampleRateHz == 0 ||
        config_.timing.frameRateHz <= 0.0 ||
        config_.timing.lineRateHz <= 0.0 ||
        config_.timing.totalLines == 0) {
        return 0;
    }

    if (config_.timing.interlaced) {
        if (config_.fastFieldPreview) {
            const auto fieldStride = std::max<std::size_t>(1U, config_.fastPreviewFieldStride);
            const double exactFieldSamples =
                    ((static_cast<double>(config_.sampleRateHz) *
                      static_cast<double>(fieldStride)) /
                     (config_.timing.frameRateHz * 2.0)) +
                    fastFieldSampleRemainder_;
            const auto sampleCount = static_cast<std::size_t>(
                    std::max(1.0, std::floor(exactFieldSamples)));
            fastFieldSampleRemainder_ = exactFieldSamples - static_cast<double>(sampleCount);
            return sampleCount;
        }

        if (config_.timing.lineRateHz > 0.0) {
            const auto frameWithGuardLines = static_cast<double>(config_.timing.totalLines) + 80.0;
            return static_cast<std::size_t>(
                    std::max(1.0,
                             std::round((static_cast<double>(config_.sampleRateHz) *
                                         frameWithGuardLines) /
                                        config_.timing.lineRateHz)));
        }
    }

    const auto byFrameRate = static_cast<std::size_t>(
            std::round(static_cast<double>(config_.sampleRateHz) / config_.timing.frameRateHz));
    const auto byLineRate = samplesPerLine() * static_cast<std::size_t>(config_.timing.totalLines);
    const auto nominalFrameSamples = std::min(byFrameRate, byLineRate);
    const auto playbackChunkSamples = static_cast<std::size_t>(
            std::round(static_cast<double>(config_.sampleRateHz) * 0.045));
    return std::max<std::size_t>(1, std::max(nominalFrameSamples, playbackChunkSamples));
}

std::size_t AnalogVideoDecoder::lockedFastFieldStartSyncIndex(
        std::size_t candidateStartSyncIndex,
        std::size_t detectedSyncCount) {
    if (!config_.fastFieldPreview || detectedSyncCount == 0) {
        return candidateStartSyncIndex;
    }

    if (config_.detectFrameSyncInFastPreview) {
        const auto sourceLineCount = static_cast<std::size_t>((config_.timing.visibleLines + 1U) / 2U);
        const auto maxUsableStart = detectedSyncCount > sourceLineCount
                ? detectedSyncCount - sourceLineCount
                : 0U;
        fastFieldStartSyncIndex_ = std::min(candidateStartSyncIndex, maxUsableStart);
        fastFieldStartLocked_ = true;
        return fastFieldStartSyncIndex_;
    }

    const auto sourceLineCount = static_cast<std::size_t>((config_.timing.visibleLines + 1U) / 2U);
    const auto maxUsableStart = detectedSyncCount > sourceLineCount
            ? detectedSyncCount - sourceLineCount
            : 0U;
    const auto boundedCandidate = std::min(candidateStartSyncIndex, maxUsableStart);

    if (!fastFieldStartLocked_) {
        fastFieldStartSyncIndex_ = boundedCandidate;
        fastFieldStartLocked_ = true;
        return fastFieldStartSyncIndex_;
    }

    const auto fieldLineCount = static_cast<std::size_t>(
            std::max(1.0, std::round(config_.timing.lineRateHz /
                                     (config_.timing.frameRateHz * 2.0))));
    auto nearestCandidate = boundedCandidate;
    if (fieldLineCount > 1U && nearestCandidate + fieldLineCount <= maxUsableStart) {
        const auto shiftedCandidate = nearestCandidate + fieldLineCount;
        if (std::abs(static_cast<long long>(shiftedCandidate) -
                     static_cast<long long>(fastFieldStartSyncIndex_)) <
            std::abs(static_cast<long long>(nearestCandidate) -
                     static_cast<long long>(fastFieldStartSyncIndex_))) {
            nearestCandidate = shiftedCandidate;
        }
    }
    if (fieldLineCount > 1U && nearestCandidate >= fieldLineCount) {
        const auto shiftedCandidate = nearestCandidate - fieldLineCount;
        if (std::abs(static_cast<long long>(shiftedCandidate) -
                     static_cast<long long>(fastFieldStartSyncIndex_)) <
            std::abs(static_cast<long long>(nearestCandidate) -
                     static_cast<long long>(fastFieldStartSyncIndex_))) {
            nearestCandidate = shiftedCandidate;
        }
    }

    constexpr auto kMaxAcceptedStartCorrection = static_cast<std::size_t>(8U);
    const auto delta = std::abs(static_cast<long long>(nearestCandidate) -
                                static_cast<long long>(fastFieldStartSyncIndex_));
    if (delta <= static_cast<long long>(kMaxAcceptedStartCorrection)) {
        fastFieldStartSyncIndex_ = std::min(nearestCandidate, maxUsableStart);
    }

    return fastFieldStartSyncIndex_;
}

std::size_t AnalogVideoDecoder::samplesPerLine() const {
    if (config_.sampleRateHz == 0 || config_.timing.lineRateHz <= 0.0) {
        return 0;
    }

    return static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(config_.sampleRateHz) /
                                     config_.timing.lineRateHz)));
}

}  // namespace sdr
