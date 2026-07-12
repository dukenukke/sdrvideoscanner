#include "AnalogVideoDecoder.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <limits>
#include <sstream>
#include <string>
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

std::size_t nearestSyncIndexForSample(
        const std::vector<std::size_t>& syncStarts,
        double targetSample,
        std::size_t maxUsableStart) {
    if (syncStarts.empty()) {
        return 0;
    }

    const auto boundedMax = std::min(maxUsableStart, syncStarts.size() - 1U);
    if (targetSample <= 0.0) {
        return 0;
    }

    const auto target = static_cast<std::size_t>(std::round(targetSample));
    auto it = std::lower_bound(syncStarts.begin(), syncStarts.begin() + static_cast<std::ptrdiff_t>(boundedMax + 1U), target);
    std::size_t best = 0;
    auto bestDelta = std::numeric_limits<double>::max();
    if (it != syncStarts.begin() + static_cast<std::ptrdiff_t>(boundedMax + 1U)) {
        best = static_cast<std::size_t>(std::distance(syncStarts.begin(), it));
        bestDelta = std::fabs(static_cast<double>(*it) - targetSample);
    }
    if (it != syncStarts.begin()) {
        auto previous = it;
        --previous;
        const auto previousIndex = static_cast<std::size_t>(std::distance(syncStarts.begin(), previous));
        const auto previousDelta = std::fabs(static_cast<double>(*previous) - targetSample);
        if (previousDelta < bestDelta) {
            best = previousIndex;
        }
    }
    return std::min(best, boundedMax);
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

std::string edgeListDiagnostic(const std::vector<std::size_t>& edges) {
    std::ostringstream message;
    for (std::size_t index = 0; index < edges.size(); ++index) {
        if (index > 0U) {
            message << ",";
        }
        message << edges[index];
    }
    return message.str();
}

void skippedEdges(
        const std::vector<std::size_t>& detectedEdges,
        const std::vector<std::size_t>& acceptedEdges,
        std::vector<std::size_t>& skipped) {
    skipped.clear();
    skipped.reserve(detectedEdges.size());
    for (const auto edge : detectedEdges) {
        const auto accepted = std::find(
                acceptedEdges.begin(),
                acceptedEdges.end(),
                edge) != acceptedEdges.end();
        if (!accepted) {
            skipped.push_back(edge);
        }
    }
}

void absoluteEdgesToTimelineOffsets(
        const std::vector<double>& absoluteEdges,
        double firstSample,
        std::size_t sampleCount,
        std::vector<std::size_t>& offsets) {
    offsets.clear();
    if (sampleCount == 0) {
        return;
    }
    offsets.reserve(absoluteEdges.size());
    const double lastSample = firstSample + static_cast<double>(sampleCount);
    for (const auto absoluteEdge : absoluteEdges) {
        if (absoluteEdge < firstSample || absoluteEdge > lastSample) {
            continue;
        }
        offsets.push_back(static_cast<std::size_t>(
                std::round(absoluteEdge - firstSample)));
    }
}

bool isNearAnyEdge(
        double absoluteSample,
        const std::vector<double>& absoluteEdges,
        double toleranceSamples) {
    for (const auto edge : absoluteEdges) {
        if (std::fabs(edge - absoluteSample) <= toleranceSamples) {
            return true;
        }
    }
    return false;
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

std::size_t samplesPerLineAtRate(std::uint64_t sampleRateHz, double lineRateHz) {
    if (sampleRateHz == 0 || lineRateHz <= 0.0) {
        return 0;
    }

    return static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(sampleRateHz) / lineRateHz)));
}

double syncLowPassCutoffHz(std::uint64_t sampleRateHz, double configuredCutoffHz) {
    if (sampleRateHz == 0) {
        return 0.0;
    }

    constexpr double kDefaultSyncCutoffHz = 1200000.0;
    constexpr double kMinimumSyncCutoffHz = 500000.0;
    constexpr double kMaximumSyncCutoffHz = 1500000.0;
    const double requested = configuredCutoffHz > 1.0
            ? configuredCutoffHz
            : kDefaultSyncCutoffHz;
    const double boundedForSync = std::clamp(
            requested,
            kMinimumSyncCutoffHz,
            kMaximumSyncCutoffHz);
    const double nyquistSafeCutoff = static_cast<double>(sampleRateHz) * 0.42;
    return std::max(1.0, std::min(boundedForSync, nyquistSafeCutoff));
}

std::size_t applyFrameReadMultiplier(std::size_t sampleCount, double multiplier) {
    if (sampleCount == 0) {
        return 0;
    }

    const double boundedMultiplier = std::clamp(multiplier, 1.0, 4.0);
    return static_cast<std::size_t>(
            std::max(1.0, std::round(static_cast<double>(sampleCount) * boundedMultiplier)));
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

    const auto lowPassStart = DecodeClock::now();
    const auto syncCutoffHz = syncLowPassCutoffHz(videoSampleRateHz, config_.cutoffHz);
    lowPassFilter_.filter(
            videoBaseband_,
            videoSampleRateHz,
            syncCutoffHz,
            syncBaseband_);
    timings.lowPassMs = elapsedMs(lowPassStart, DecodeClock::now());

    const auto normalizeStart = DecodeClock::now();
    normalizer_.normalize(videoBaseband_, video_);
    normalizer_.normalize(syncBaseband_, syncVideo_);
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
                    syncVideo_,
                    videoSampleRateHz,
                    config_.timing.lineRateHz,
                    maxSyncs)
            : syncDetector_.detectHorizontalSyncs(
                    syncVideo_,
                    videoSampleRateHz,
                    config_.timing.lineRateHz,
                    maxSyncs);
    timings.syncMs = elapsedMs(syncStart, DecodeClock::now());

    appendSyncHistory(syncVideo_, videoSampleRateHz);
    appendVerticalEdgeCandidates(
            syncDetection.frameSyncEdges,
            videoSampleRateHz,
            syncDetection.frameSyncQuality);
    strictSelectedFrameSyncEdges_.clear();
    strictFrameSyncEdges(
            syncDetection.frameSyncEdges,
            videoSampleRateHz,
            strictSelectedFrameSyncEdges_);
    const bool strictTrackingActive = strictVEdgeLocked_ || strictVEdgeMissCount_ > 0U;
    const auto& frameSyncEdgesForAssembly = !strictSelectedFrameSyncEdges_.empty()
            ? strictSelectedFrameSyncEdges_
            : strictTrackingActive
            ? emptyFrameSyncEdges_
            : syncDetection.frameSyncEdges;
    skippedEdges(
            syncDetection.frameSyncEdges,
            strictSelectedFrameSyncEdges_,
            skippedFrameSyncEdges_);
    updateTimelineEdgeDiagnostics(videoSampleRateHz);

    if (syncDetection.syncIsHigh) {
        for (auto& sample : video_) {
            sample = static_cast<std::uint8_t>(255U - sample);
        }
    }
    const auto videoLineSamples = samplesPerLineAtRate(videoSampleRateHz, config_.timing.lineRateHz);
    const auto expectedHorizontalSyncs = videoLineSamples > 0U
            ? static_cast<double>(video_.size()) / static_cast<double>(videoLineSamples)
            : 0.0;
    lastHSyncMissingRate_ = expectedHorizontalSyncs > 1.0
            ? std::clamp(
                    (expectedHorizontalSyncs - static_cast<double>(syncDetection.syncStarts.size())) /
                            expectedHorizontalSyncs,
                    0.0,
                    1.0)
            : 0.0;

    const auto minimumUsefulSyncs = config_.fastFieldPreview
            ? static_cast<std::size_t>(
                    std::max(8.0,
                             std::round(static_cast<double>(config_.timing.visibleLines) * 0.20)))
            : static_cast<std::size_t>(
                    std::max(8.0,
                             std::round(static_cast<double>(config_.timing.totalLines) * 0.25)));
    if (syncDetection.syncStarts.size() >= minimumUsefulSyncs) {
        std::size_t fieldStartSyncIndex = 0;
        std::size_t fieldCandidateStartSyncIndex = 0;
        const bool useLockedFieldStart = config_.fastFieldPreview || config_.timing.interlaced;
        const auto assembleStart = DecodeClock::now();
        auto frame = useLockedFieldStart
                ? [&]() {
                    auto candidateStartSyncIndex = static_cast<std::size_t>(-1);
                    if (!strictSelectedFrameSyncEdges_.empty()) {
                        candidateStartSyncIndex =
                                frameAssembler_.chooseFieldPreviewStartFromFrameSyncEdge(
                                        video_,
                                        syncDetection.syncStarts,
                                        strictSelectedFrameSyncEdges_.front(),
                                        videoSampleRateHz);
                    }
                    if (candidateStartSyncIndex == static_cast<std::size_t>(-1)) {
                        candidateStartSyncIndex = frameAssembler_.chooseFieldPreviewStartSyncIndex(
                                    video_,
                                    syncDetection.syncStarts,
                                    frameSyncEdgesForAssembly,
                                    videoSampleRateHz,
                                    fieldStartSyncIndex_,
                                    fieldStartLocked_);
                    }
                    fieldCandidateStartSyncIndex = candidateStartSyncIndex;
                    const auto hadPreviousFieldLock = fieldStartLocked_;
                    const auto previousLockedStartSyncIndex = fieldStartSyncIndex_;
                    fieldStartSyncIndex = sampleLockedFieldStartSyncIndex(
                            syncDetection.syncStarts,
                            frameSyncEdgesForAssembly,
                            candidateStartSyncIndex,
                            videoSampleRateHz,
                            video_.size());
                    auto selectedStartSyncIndex = fieldStartSyncIndex;
                    auto frame = assembleBestFieldPreviewFrame(
                            video_,
                            syncDetection.syncStarts,
                            frameSyncEdgesForAssembly,
                            fieldStartSyncIndex,
                            candidateStartSyncIndex,
                            videoSampleRateHz,
                            selectedStartSyncIndex);
                    fieldStartSyncIndex = selectedStartSyncIndex;
                    if (isBadSequentialFieldFrame(frame)) {
                        fieldStartSyncIndex = previousLockedStartSyncIndex;
                        if (hadPreviousFieldLock) {
                            auto preservedLockFrame = frameAssembler_.assembleFieldPreviewFromSync(
                                    video_,
                                    syncDetection.syncStarts,
                                    frameSyncEdgesForAssembly,
                                    previousLockedStartSyncIndex,
                                    videoSampleRateHz);
                            if (preservedLockFrame.valid() &&
                                !isBadSequentialFieldFrame(preservedLockFrame)) {
                                preservedLockFrame.message +=
                                        "; rejected bad sequential field; displayed preserved lock";
                                frame = std::move(preservedLockFrame);
                                acceptVerticalSampleStart(syncDetection.syncStarts, fieldStartSyncIndex);
                            } else {
                                frame.message +=
                                        "; rejected bad sequential field; preserved lock also bad; relock requested";
                                resetVerticalSampleLock();
                            }
                        } else {
                            frame.message += "; rejected bad sequential field; lock not committed";
                            resetVerticalSampleLock();
                        }
                    } else {
                        acceptVerticalSampleStart(syncDetection.syncStarts, fieldStartSyncIndex);
                    }
                    return frame;
                }()
                : frameAssembler_.assembleFromSync(
                        video_,
                        syncDetection.syncStarts,
                        frameSyncEdgesForAssembly,
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
                << "; strict_frame_sync_edges=" << strictSelectedFrameSyncEdges_.size()
                << "; timeline_samples=" << syncHistorySizeSamples_
                << "; timeline_strict_edges=" << edgeListDiagnostic(timelineStrictEdges_)
                << "; timeline_skipped_edges=" << edgeListDiagnostic(timelineSkippedEdges_)
                << "; frame_sync_quality=" << syncDetection.frameSyncQuality
                << "; sync_polarity=" << (syncDetection.syncIsHigh ? "high" : "low")
                << "; sync_threshold=" << static_cast<int>(syncDetection.threshold)
                << "; sync_score=" << syncDetection.score
                << "; line_stability=" << syncDetection.lineStabilityScore;
        if (useLockedFieldStart) {
            message << "; field_start_sync=" << fieldStartSyncIndex
                    << "; field_start_candidate=" << fieldCandidateStartSyncIndex
                    << "; double_image_score=" << frame.doubleImageScore
                    << "; assembly_path=" << frame.assemblyPath
                    << "; field_start_line_offset=" << lastFieldStartLineOffset_
                    << "; field_start_locked=" << (fieldStartLocked_ ? "yes" : "no")
                    << "; field_start_relock_count=" << fieldStartRejectCount_
                    << "; v_sample_locked=" << (verticalSampleLock_ ? "yes" : "no")
                    << "; strict_v_locked=" << (strictVEdgeLocked_ ? "yes" : "no")
                    << "; strict_v_chain=" << lastStrictVEdgeChainLength_
                    << "; strict_v_misses=" << strictVEdgeMissCount_
                    << "; strict_v_edge_sample=" << lastStrictVEdgeSample_
                    << "; strict_v_interval_err=" << lastStrictVEdgeIntervalError_
                    << "; sync_history_samples=" << syncHistorySizeSamples_
                    << "; v_relock_count=" << verticalRelockCount_
                    << "; v_residual_samples=" << lastVEdgeResidualSamples_
                    << "; v_edge_sample=" << lastVEdgeSample_
                    << "; v_edge_quality=" << lastVEdgeQuality_
                    << "; field_period_err=" << lastFieldPeriodError_
                    << "; h_sync_missing_rate=" << lastHSyncMissingRate_;
            if (config_.fastFieldPreview) {
                message << "; field_stride=" << std::max<std::size_t>(1U, config_.fastPreviewFieldStride);
            }
        }
        message << "; frame_read_guard_lines=" << frameReadGuardLines()
                << "; sync_lpf_cutoff_hz=" << syncCutoffHz
                << timingDiagnostic(
                timings,
                sourceSamplesRead,
                videoBaseband_.size(),
                decimation,
                videoSampleRateHz);
        frame.message = message.str();
        videoSampleCursor_ += static_cast<double>(video_.size());
        return frame;
    }

    const auto assembleStart = DecodeClock::now();
    auto frame = frameAssembler_.assembleRawRaster(
            video_,
            videoSampleRateHz,
            syncDetection.syncStarts.size());
    resetVerticalSampleLock();
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
            << "; strict_frame_sync_edges=" << strictSelectedFrameSyncEdges_.size()
            << "; timeline_samples=" << syncHistorySizeSamples_
            << "; timeline_strict_edges=" << edgeListDiagnostic(timelineStrictEdges_)
            << "; timeline_skipped_edges=" << edgeListDiagnostic(timelineSkippedEdges_)
            << "; frame_sync_quality=" << syncDetection.frameSyncQuality
            << "; sync_polarity=" << (syncDetection.syncIsHigh ? "high" : "low")
            << "; sync_threshold=" << static_cast<int>(syncDetection.threshold)
            << "; sync_score=" << syncDetection.score
            << "; line_stability=" << syncDetection.lineStabilityScore;
    message << "; frame_read_guard_lines=" << frameReadGuardLines()
            << "; sync_lpf_cutoff_hz=" << syncCutoffHz
            << "; double_image_score=" << frame.doubleImageScore
            << "; assembly_path=" << frame.assemblyPath
            << "; h_sync_missing_rate=" << lastHSyncMissingRate_
            << timingDiagnostic(
            timings,
            sourceSamplesRead,
            videoBaseband_.size(),
            decimation,
            videoSampleRateHz);
    frame.message = message.str();
    videoSampleCursor_ += static_cast<double>(video_.size());
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
            return applyFrameReadMultiplier(sampleCount, config_.liveFrameReadMultiplier);
        }

        if (config_.timing.lineRateHz > 0.0) {
            const auto guardLines = fieldStartLocked_ ? 0.0 : 80.0;
            const auto frameWithGuardLines = static_cast<double>(config_.timing.totalLines) + guardLines;
            const auto sampleCount = static_cast<std::size_t>(
                    std::max(1.0,
                             std::round((static_cast<double>(config_.sampleRateHz) *
                                         frameWithGuardLines) /
                                        config_.timing.lineRateHz)));
            return applyFrameReadMultiplier(sampleCount, config_.liveFrameReadMultiplier);
        }
    }

    const auto byFrameRate = static_cast<std::size_t>(
            std::round(static_cast<double>(config_.sampleRateHz) / config_.timing.frameRateHz));
    const auto byLineRate = samplesPerLine() * static_cast<std::size_t>(config_.timing.totalLines);
    const auto nominalFrameSamples = std::min(byFrameRate, byLineRate);
    const auto playbackChunkSamples = static_cast<std::size_t>(
            std::round(static_cast<double>(config_.sampleRateHz) * 0.045));
    return applyFrameReadMultiplier(
            std::max<std::size_t>(1, std::max(nominalFrameSamples, playbackChunkSamples)),
            config_.liveFrameReadMultiplier);
}

double AnalogVideoDecoder::frameReadGuardLines() const {
    if (!config_.timing.interlaced || config_.fastFieldPreview) {
        return 0.0;
    }
    return fieldStartLocked_ ? 0.0 : 80.0;
}

void AnalogVideoDecoder::resetVerticalSampleLock() {
    fieldStartLocked_ = false;
    fieldStartSyncIndex_ = 0;
    pendingFieldStartSyncIndex_ = 0;
    fieldStartRejectCount_ = 0;
    verticalSampleLock_ = false;
    lockedVEdgeSample_ = 0.0;
    pendingVEdgeSample_ = 0.0;
    lockedActiveStartSample_ = 0.0;
    pendingActiveStartSample_ = 0.0;
    lastVEdgeResidualSamples_ = 0.0;
    lastFieldStartLineOffset_ = 0.0;
    lastVEdgeSample_ = 0.0;
    lastVEdgeQuality_ = 0.0;
    lastFieldPeriodError_ = 0.0;
    verticalRelockCount_ = 0;
    resetVerticalEdgeHistory();
}

void AnalogVideoDecoder::resetVerticalEdgeHistory() {
    verticalEdgeHistory_.clear();
    strictVEdgeLocked_ = false;
    strictLockedVEdgeSample_ = 0.0;
    lastStrictVEdgeSample_ = 0.0;
    lastStrictVEdgeIntervalError_ = 0.0;
    lastStrictVEdgeChainLength_ = 0;
    strictVEdgeMissCount_ = 0;
    strictTimelineEdgeSamples_.clear();
    timelineStrictEdges_.clear();
    timelineSkippedEdges_.clear();
}

void AnalogVideoDecoder::updateTimelineEdgeDiagnostics(std::uint64_t videoSampleRateHz) {
    absoluteEdgesToTimelineOffsets(
            strictTimelineEdgeSamples_,
            syncHistoryFirstSample_,
            syncHistorySizeSamples_,
            timelineStrictEdges_);

    timelineSkippedEdges_.clear();
    if (syncHistorySizeSamples_ == 0) {
        return;
    }

    const auto lineSamples = samplesPerLineAtRate(videoSampleRateHz, config_.timing.lineRateHz);
    const double toleranceSamples = std::max(1.0, static_cast<double>(lineSamples));
    const double lastSample = syncHistoryFirstSample_ + static_cast<double>(syncHistorySizeSamples_);
    timelineSkippedEdges_.reserve(verticalEdgeHistory_.size());
    for (const auto& candidate : verticalEdgeHistory_) {
        if (candidate.absoluteSample < syncHistoryFirstSample_ ||
            candidate.absoluteSample > lastSample ||
            isNearAnyEdge(candidate.absoluteSample, strictTimelineEdgeSamples_, toleranceSamples)) {
            continue;
        }
        timelineSkippedEdges_.push_back(static_cast<std::size_t>(
                std::round(candidate.absoluteSample - syncHistoryFirstSample_)));
    }
}

void AnalogVideoDecoder::appendSyncHistory(
        const std::vector<std::uint8_t>& syncVideo,
        std::uint64_t videoSampleRateHz) {
    if (syncVideo.empty() || videoSampleRateHz == 0 || config_.timing.frameRateHz <= 0.0) {
        return;
    }

    constexpr double kHistoryFrames = 4.0;
    const auto wantedCapacity = static_cast<std::size_t>(
            std::max<double>(
                    static_cast<double>(syncVideo.size()),
                    std::round((static_cast<double>(videoSampleRateHz) *
                                kHistoryFrames) /
                               config_.timing.frameRateHz)));
    if (wantedCapacity == 0) {
        return;
    }
    if (syncHistoryCapacitySamples_ != wantedCapacity) {
        syncHistoryRing_.assign(wantedCapacity, 0);
        syncHistoryCapacitySamples_ = wantedCapacity;
        syncHistoryStartSample_ = 0;
        syncHistorySizeSamples_ = 0;
        syncHistoryFirstSample_ = videoSampleCursor_;
        resetVerticalEdgeHistory();
    }

    const auto skippedInputSamples = syncVideo.size() > syncHistoryCapacitySamples_
            ? syncVideo.size() - syncHistoryCapacitySamples_
            : 0U;
    const auto incomingSamples = syncVideo.size() - skippedInputSamples;
    if (incomingSamples == 0) {
        return;
    }

    const auto requiredDropSamples =
            incomingSamples > (syncHistoryCapacitySamples_ - syncHistorySizeSamples_)
                    ? incomingSamples - (syncHistoryCapacitySamples_ - syncHistorySizeSamples_)
                    : 0U;
    if (requiredDropSamples > 0) {
        syncHistoryStartSample_ =
                (syncHistoryStartSample_ + requiredDropSamples) % syncHistoryCapacitySamples_;
        syncHistorySizeSamples_ -= std::min(requiredDropSamples, syncHistorySizeSamples_);
        syncHistoryFirstSample_ += static_cast<double>(requiredDropSamples);
    }
    if (syncHistorySizeSamples_ == 0) {
        syncHistoryFirstSample_ = videoSampleCursor_ + static_cast<double>(skippedInputSamples);
    }

    std::size_t writeSample =
            (syncHistoryStartSample_ + syncHistorySizeSamples_) % syncHistoryCapacitySamples_;
    std::size_t copiedSamples = 0;
    while (copiedSamples < incomingSamples) {
        const auto spanSamples = std::min(
                incomingSamples - copiedSamples,
                syncHistoryCapacitySamples_ - writeSample);
        const auto sourceOffset = skippedInputSamples + copiedSamples;
        std::copy(
                syncVideo.begin() + static_cast<std::ptrdiff_t>(sourceOffset),
                syncVideo.begin() + static_cast<std::ptrdiff_t>(sourceOffset + spanSamples),
                syncHistoryRing_.begin() + static_cast<std::ptrdiff_t>(writeSample));
        syncHistorySizeSamples_ += spanSamples;
        copiedSamples += spanSamples;
        writeSample = (writeSample + spanSamples) % syncHistoryCapacitySamples_;
    }
}

void AnalogVideoDecoder::appendVerticalEdgeCandidates(
        const std::vector<std::size_t>& frameSyncEdges,
        std::uint64_t videoSampleRateHz,
        double quality) {
    if (frameSyncEdges.empty()) {
        return;
    }

    const auto lineSamples = std::max<std::size_t>(
            1U,
            samplesPerLineAtRate(videoSampleRateHz, config_.timing.lineRateHz));
    for (const auto edge : frameSyncEdges) {
        const double absoluteSample = videoSampleCursor_ + static_cast<double>(edge);
        const auto duplicate = std::find_if(
                verticalEdgeHistory_.begin(),
                verticalEdgeHistory_.end(),
                [&](const VerticalEdgeCandidate& candidate) {
                    return std::fabs(candidate.absoluteSample - absoluteSample) <=
                            static_cast<double>(lineSamples);
                });
        if (duplicate != verticalEdgeHistory_.end()) {
            duplicate->quality = std::max(duplicate->quality, quality);
            continue;
        }
        verticalEdgeHistory_.push_back({absoluteSample, quality});
    }

    const double oldestKeptSample = syncHistorySizeSamples_ == 0
            ? videoSampleCursor_
            : syncHistoryFirstSample_;
    verticalEdgeHistory_.erase(
            std::remove_if(
                    verticalEdgeHistory_.begin(),
                    verticalEdgeHistory_.end(),
                    [&](const VerticalEdgeCandidate& candidate) {
                        return candidate.absoluteSample < oldestKeptSample;
                    }),
            verticalEdgeHistory_.end());
}

double AnalogVideoDecoder::standardFieldPeriodSamples(std::uint64_t videoSampleRateHz) const {
    if (videoSampleRateHz == 0) {
        return 0.0;
    }

    double frameRateHz = config_.timing.frameRateHz;
    if (config_.timing.standard == VideoStandard::PAL625_25FPS) {
        frameRateHz = 25.0;
    } else if (config_.timing.standard == VideoStandard::NTSC_525_30FPS) {
        frameRateHz = 30000.0 / 1001.0;
    }
    if (frameRateHz <= 0.0) {
        return 0.0;
    }
    return static_cast<double>(videoSampleRateHz) / (frameRateHz * 2.0);
}

std::size_t AnalogVideoDecoder::strictFrameSyncEdges(
        const std::vector<std::size_t>& frameSyncEdges,
        std::uint64_t videoSampleRateHz,
        std::vector<std::size_t>& selectedFrameSyncEdges) {
    selectedFrameSyncEdges.clear();
    lastStrictVEdgeChainLength_ = 0;
    lastStrictVEdgeIntervalError_ = 0.0;
    strictTimelineEdgeSamples_.clear();
    if (!config_.timing.interlaced || frameSyncEdges.empty() || verticalEdgeHistory_.empty()) {
        return 0;
    }

    const double fieldPeriodSamples = standardFieldPeriodSamples(videoSampleRateHz);
    const auto lineSamples = samplesPerLineAtRate(videoSampleRateHz, config_.timing.lineRateHz);
    if (fieldPeriodSamples <= 1.0 || lineSamples == 0) {
        return 0;
    }

    const double frameStartSample = videoSampleCursor_;
    const double frameEndSample = frameStartSample + static_cast<double>(syncVideo_.size());
    const double lockedToleranceSamples = static_cast<double>(lineSamples) * 3.0;
    const double initialToleranceSamples = static_cast<double>(lineSamples) * 8.0;

    auto findCurrentEdgeNear = [&](double prediction, double tolerance, double& error) {
        auto bestEdge = static_cast<std::size_t>(-1);
        double bestError = tolerance + 1.0;
        for (const auto edge : frameSyncEdges) {
            const double absoluteEdge = frameStartSample + static_cast<double>(edge);
            const double candidateError = absoluteEdge - prediction;
            if (std::fabs(candidateError) < std::fabs(bestError) &&
                std::fabs(candidateError) <= tolerance) {
                bestError = candidateError;
                bestEdge = edge;
            }
        }
        error = bestError;
        return bestEdge;
    };

    auto findPreviousCandidate = [&](double target, double tolerance, double& error) {
        const VerticalEdgeCandidate* best = nullptr;
        double bestError = tolerance + 1.0;
        for (const auto& candidate : verticalEdgeHistory_) {
            if (candidate.absoluteSample >= target + tolerance ||
                candidate.absoluteSample >= frameEndSample) {
                continue;
            }
            const double candidateError = candidate.absoluteSample - target;
            if (std::fabs(candidateError) <= tolerance &&
                std::fabs(candidateError) < std::fabs(bestError)) {
                bestError = candidateError;
                best = &candidate;
            }
        }
        error = bestError;
        return best;
    };

    if (strictVEdgeLocked_) {
        auto bestEdge = static_cast<std::size_t>(-1);
        double bestError = lockedToleranceSamples + 1.0;
        for (const auto edge : frameSyncEdges) {
            const double absoluteEdge = frameStartSample + static_cast<double>(edge);
            const double periodsSinceLock =
                    std::round((absoluteEdge - strictLockedVEdgeSample_) / fieldPeriodSamples);
            if (periodsSinceLock < 1.0) {
                continue;
            }
            const double predictedEdge =
                    strictLockedVEdgeSample_ + (periodsSinceLock * fieldPeriodSamples);
            double error = 0.0;
            const auto candidateEdge = findCurrentEdgeNear(
                    predictedEdge,
                    lockedToleranceSamples,
                    error);
            if (candidateEdge != static_cast<std::size_t>(-1) &&
                std::fabs(error) < std::fabs(bestError)) {
                bestError = error;
                bestEdge = candidateEdge;
            }
        }
        if (bestEdge != static_cast<std::size_t>(-1)) {
            strictLockedVEdgeSample_ = frameStartSample + static_cast<double>(bestEdge);
            lastStrictVEdgeSample_ = strictLockedVEdgeSample_;
            lastStrictVEdgeIntervalError_ = bestError;
            lastStrictVEdgeChainLength_ = 1;
            strictTimelineEdgeSamples_.push_back(strictLockedVEdgeSample_);
            double previousEdge = strictLockedVEdgeSample_ - fieldPeriodSamples;
            for (std::size_t index = 0; index < 5U; ++index) {
                double previousError = 0.0;
                const auto* previous = findPreviousCandidate(
                        previousEdge,
                        initialToleranceSamples,
                        previousError);
                if (previous == nullptr) {
                    break;
                }
                strictTimelineEdgeSamples_.push_back(previous->absoluteSample);
                previousEdge = previous->absoluteSample - fieldPeriodSamples;
            }
            lastStrictVEdgeChainLength_ = strictTimelineEdgeSamples_.size();
            strictVEdgeMissCount_ = 0;
            selectedFrameSyncEdges.push_back(bestEdge);
            return selectedFrameSyncEdges.size();
        }

        ++strictVEdgeMissCount_;
        if (strictVEdgeMissCount_ >= 3U) {
            strictVEdgeLocked_ = false;
            strictLockedVEdgeSample_ = 0.0;
        }
    }

    struct ChainScore {
        std::size_t edge = static_cast<std::size_t>(-1);
        double absoluteEdge = 0.0;
        std::size_t length = 0;
        double meanError = std::numeric_limits<double>::max();
        double quality = 0.0;
        std::vector<double> chainEdges;
    };

    ChainScore best;
    for (const auto edge : frameSyncEdges) {
        const double absoluteEdge = frameStartSample + static_cast<double>(edge);
        std::size_t chainLength = 1;
        double totalError = 0.0;
        double qualitySum = 0.0;
        double probe = absoluteEdge;
        std::vector<double> chainEdges;
        chainEdges.push_back(absoluteEdge);
        for (std::size_t step = 0; step < 6U; ++step) {
            double error = 0.0;
            const auto* previous = findPreviousCandidate(
                    probe - fieldPeriodSamples,
                    initialToleranceSamples,
                    error);
            if (previous == nullptr) {
                break;
            }
            ++chainLength;
            totalError += std::fabs(error);
            qualitySum += previous->quality;
            probe = previous->absoluteSample;
            chainEdges.push_back(previous->absoluteSample);
        }

        if (chainLength < 3U) {
            continue;
        }
        const double meanError = totalError / static_cast<double>(chainLength - 1U);
        const double meanQuality = qualitySum / static_cast<double>(chainLength - 1U);
        const bool betterLength = chainLength > best.length;
        const bool sameLengthLowerError =
                chainLength == best.length && meanError < best.meanError;
        const bool closeErrorBetterQuality =
                chainLength == best.length &&
                std::fabs(meanError - best.meanError) <= static_cast<double>(lineSamples) &&
                meanQuality > best.quality;
        if (best.edge == static_cast<std::size_t>(-1) ||
            betterLength ||
            sameLengthLowerError ||
            closeErrorBetterQuality) {
            best.edge = edge;
            best.absoluteEdge = absoluteEdge;
            best.length = chainLength;
            best.meanError = meanError;
            best.quality = meanQuality;
            best.chainEdges = std::move(chainEdges);
        }
    }

    if (best.edge == static_cast<std::size_t>(-1)) {
        return 0;
    }

    strictVEdgeLocked_ = true;
    strictLockedVEdgeSample_ = best.absoluteEdge;
    lastStrictVEdgeSample_ = best.absoluteEdge;
    lastStrictVEdgeIntervalError_ = best.meanError;
    lastStrictVEdgeChainLength_ = best.length;
    strictTimelineEdgeSamples_ = std::move(best.chainEdges);
    strictVEdgeMissCount_ = 0;
    selectedFrameSyncEdges.push_back(best.edge);
    return selectedFrameSyncEdges.size();
}

VideoFrame AnalogVideoDecoder::assembleBestFieldPreviewFrame(
        const std::vector<std::uint8_t>& video,
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::size_t preferredStartSyncIndex,
        std::size_t candidateStartSyncIndex,
        std::uint64_t videoSampleRateHz,
        std::size_t& selectedStartSyncIndex) {
    const auto sourceLineCount = static_cast<std::size_t>((config_.timing.visibleLines + 1U) / 2U);
    const auto maxUsableStart = syncStarts.size() > sourceLineCount
            ? syncStarts.size() - sourceLineCount
            : 0U;
    std::vector<std::size_t> candidates;
    candidates.reserve(10U);
    auto addCandidate = [&](long long candidate) {
        if (candidate < 0) {
            return;
        }
        const auto bounded = std::min<std::size_t>(
                static_cast<std::size_t>(candidate),
                maxUsableStart);
        if (std::find(candidates.begin(), candidates.end(), bounded) == candidates.end()) {
            candidates.push_back(bounded);
        }
    };

    const auto boundedPreferred = std::min(preferredStartSyncIndex, maxUsableStart);
    const auto boundedCandidate = std::min(candidateStartSyncIndex, maxUsableStart);
    addCandidate(static_cast<long long>(boundedPreferred));
    addCandidate(static_cast<long long>(boundedCandidate));

    const auto halfVisibleField = static_cast<long long>(std::max<std::size_t>(1U, sourceLineCount / 2U));
    const auto quarterVisibleField = static_cast<long long>(std::max<std::size_t>(1U, sourceLineCount / 4U));
    for (const auto base : {boundedPreferred, boundedCandidate}) {
        const auto signedBase = static_cast<long long>(base);
        addCandidate(signedBase - halfVisibleField);
        addCandidate(signedBase + halfVisibleField);
        addCandidate(signedBase - quarterVisibleField);
        addCandidate(signedBase + quarterVisibleField);
    }

    VideoFrame preferredFrame;
    bool havePreferred = false;
    VideoFrame candidateFrame;
    bool haveCandidate = false;
    VideoFrame bestFrame;
    std::size_t bestStart = boundedPreferred;
    double bestScore = std::numeric_limits<double>::max();
    for (const auto start : candidates) {
        auto frame = frameAssembler_.assembleFieldPreviewFromSync(
                video,
                syncStarts,
                frameSyncEdges,
                start,
                videoSampleRateHz);
        if (start == boundedPreferred) {
            preferredFrame = frame;
            havePreferred = true;
        }
        if (start == boundedCandidate) {
            candidateFrame = frame;
            haveCandidate = true;
        }
        const double phasePenalty =
                std::fabs(static_cast<double>(static_cast<long long>(start) -
                                              static_cast<long long>(boundedPreferred))) *
                0.06;
        const double score = frame.doubleImageScore + phasePenalty;
        if (!bestFrame.valid() || score < bestScore) {
            bestScore = score;
            bestStart = start;
            bestFrame = std::move(frame);
        }
    }

    if (!havePreferred) {
        preferredFrame = frameAssembler_.assembleFieldPreviewFromSync(
                video,
                syncStarts,
                frameSyncEdges,
                boundedPreferred,
                videoSampleRateHz);
    }

    constexpr double kBadDoubleImageScore = 28.0;
    constexpr double kRequiredImprovement = 4.0;
    const auto candidateDistance = static_cast<std::size_t>(std::abs(
            static_cast<long long>(boundedPreferred) -
            static_cast<long long>(boundedCandidate)));
    const bool preferredSequentialFarFromCandidate =
            preferredFrame.assemblyPath == "sequential" && candidateDistance > 12U;
    if (preferredSequentialFarFromCandidate &&
        haveCandidate &&
        candidateFrame.valid() &&
        candidateFrame.doubleImageScore <= preferredFrame.doubleImageScore + 15.0) {
        selectedStartSyncIndex = boundedCandidate;
        candidateFrame.message += "; sequential preferred start rejected for candidate";
        return candidateFrame;
    }
    if ((!preferredSequentialFarFromCandidate && preferredFrame.doubleImageScore < kBadDoubleImageScore) ||
        preferredFrame.doubleImageScore <= bestFrame.doubleImageScore + kRequiredImprovement) {
        selectedStartSyncIndex = boundedPreferred;
        return preferredFrame;
    }

    selectedStartSyncIndex = bestStart;
    bestFrame.message += "; double-image rejected preferred start";
    return bestFrame;
}

bool AnalogVideoDecoder::isBadSequentialFieldFrame(const VideoFrame& frame) const {
    constexpr double kRejectSequentialDoubleImageScore = 28.0;
    return config_.timing.interlaced &&
           frame.assemblyPath == "sequential" &&
           frame.doubleImageScore > kRejectSequentialDoubleImageScore;
}

void AnalogVideoDecoder::acceptVerticalSampleStart(
        const std::vector<std::size_t>& syncStarts,
        std::size_t selectedStartSyncIndex) {
    if (syncStarts.empty()) {
        return;
    }
    const auto bounded = std::min(selectedStartSyncIndex, syncStarts.size() - 1U);
    const double acceptedSample = videoSampleCursor_ + static_cast<double>(syncStarts[bounded]);
    lockedActiveStartSample_ = acceptedSample;
    pendingActiveStartSample_ = acceptedSample;
    lockedVEdgeSample_ = acceptedSample;
    pendingVEdgeSample_ = acceptedSample;
    lastVEdgeSample_ = acceptedSample;
    fieldStartSyncIndex_ = bounded;
    pendingFieldStartSyncIndex_ = bounded;
    fieldStartLocked_ = true;
    verticalSampleLock_ = true;
}

std::size_t AnalogVideoDecoder::sampleLockedFieldStartSyncIndex(
        const std::vector<std::size_t>& syncStarts,
        const std::vector<std::size_t>& frameSyncEdges,
        std::size_t candidateStartSyncIndex,
        std::uint64_t videoSampleRateHz,
        std::size_t videoSampleCount) {
    if (!config_.timing.interlaced || syncStarts.empty() || videoSampleRateHz == 0) {
        return candidateStartSyncIndex;
    }
    (void)frameSyncEdges;

    const auto sourceLineCount = static_cast<std::size_t>((config_.timing.visibleLines + 1U) / 2U);
    const auto maxUsableStart = syncStarts.size() > sourceLineCount
            ? syncStarts.size() - sourceLineCount
            : 0U;
    const auto boundedCandidate = std::min(candidateStartSyncIndex, maxUsableStart);
    const auto lineSamples = std::max<std::size_t>(
            1U,
            samplesPerLineAtRate(videoSampleRateHz, config_.timing.lineRateHz));
    const double fieldPeriodSamples =
            static_cast<double>(videoSampleRateHz) / (config_.timing.frameRateHz * 2.0);
    const double frameStartSample = videoSampleCursor_;
    const double frameEndSample = frameStartSample + static_cast<double>(videoSampleCount);
    const double candidateActiveStartSample =
            frameStartSample + static_cast<double>(syncStarts[boundedCandidate]);

    if (!verticalSampleLock_) {
        lockedActiveStartSample_ = candidateActiveStartSample;
        pendingActiveStartSample_ = candidateActiveStartSample;
        lockedVEdgeSample_ = candidateActiveStartSample;
        pendingVEdgeSample_ = candidateActiveStartSample;
        verticalSampleLock_ = true;
        fieldStartLocked_ = true;
        fieldStartSyncIndex_ = boundedCandidate;
        pendingFieldStartSyncIndex_ = boundedCandidate;
        fieldStartRejectCount_ = 0;
        verticalRelockCount_ = 0;
        lastVEdgeResidualSamples_ = 0.0;
        lastFieldStartLineOffset_ = 0.0;
        lastVEdgeSample_ = lockedActiveStartSample_;
        lastVEdgeQuality_ = 1.0;
        lastFieldPeriodError_ = 0.0;
        return fieldStartSyncIndex_;
    }

    double predictedActiveStartSample = lockedActiveStartSample_;
    while (predictedActiveStartSample + (fieldPeriodSamples * 0.5) < frameStartSample) {
        predictedActiveStartSample += fieldPeriodSamples;
    }
    while (predictedActiveStartSample - (fieldPeriodSamples * 0.5) > frameEndSample) {
        predictedActiveStartSample -= fieldPeriodSamples;
    }

    double observedActiveStartSample = candidateActiveStartSample;
    while (observedActiveStartSample + (fieldPeriodSamples * 0.5) < predictedActiveStartSample) {
        observedActiveStartSample += fieldPeriodSamples;
    }
    while (observedActiveStartSample - (fieldPeriodSamples * 0.5) > predictedActiveStartSample) {
        observedActiveStartSample -= fieldPeriodSamples;
    }

    const double stableRelockWindowSamples = 2.0 * static_cast<double>(lineSamples);
    double selectedActiveStartSample = predictedActiveStartSample;

    const double residual = observedActiveStartSample - predictedActiveStartSample;
    lastVEdgeResidualSamples_ = residual;
    lastFieldStartLineOffset_ = residual / static_cast<double>(lineSamples);
    lastVEdgeQuality_ = std::clamp(1.0 - (std::fabs(lastFieldStartLineOffset_) / 24.0), 0.0, 1.0);
    lastFieldPeriodError_ = 0.0;
    if (std::fabs(lastFieldStartLineOffset_) <= 2.0) {
        lockedActiveStartSample_ = predictedActiveStartSample;
        pendingActiveStartSample_ = observedActiveStartSample;
        selectedActiveStartSample = lockedActiveStartSample_;
        verticalRelockCount_ = 0;
    } else if (std::fabs(lastFieldStartLineOffset_) <= 12.0) {
        const double step = residual > 0.0
                ? static_cast<double>(lineSamples)
                : -static_cast<double>(lineSamples);
        lockedActiveStartSample_ = predictedActiveStartSample + step;
        pendingActiveStartSample_ = observedActiveStartSample;
        selectedActiveStartSample = lockedActiveStartSample_;
        verticalRelockCount_ = 0;
    } else {
        const double pendingDelta = std::fabs(observedActiveStartSample - pendingActiveStartSample_);
        if (pendingDelta <= stableRelockWindowSamples) {
            ++verticalRelockCount_;
        } else {
            pendingActiveStartSample_ = observedActiveStartSample;
            verticalRelockCount_ = 1;
        }
        if (verticalRelockCount_ >= 3U) {
            lockedActiveStartSample_ = observedActiveStartSample;
            pendingActiveStartSample_ = observedActiveStartSample;
            selectedActiveStartSample = observedActiveStartSample;
            verticalRelockCount_ = 0;
        } else {
            lockedActiveStartSample_ = predictedActiveStartSample;
            selectedActiveStartSample = predictedActiveStartSample;
        }
    }
    lockedVEdgeSample_ = lockedActiveStartSample_;
    lastVEdgeSample_ = lockedActiveStartSample_;
    pendingVEdgeSample_ = pendingActiveStartSample_;

    double activeStartSample = selectedActiveStartSample - frameStartSample;
    while (activeStartSample < 0.0) {
        activeStartSample += fieldPeriodSamples;
    }
    while (activeStartSample > static_cast<double>(videoSampleCount) &&
           activeStartSample - fieldPeriodSamples >= 0.0) {
        activeStartSample -= fieldPeriodSamples;
    }

    fieldStartSyncIndex_ = nearestSyncIndexForSample(
            syncStarts,
            activeStartSample,
            maxUsableStart);
    fieldStartLocked_ = true;
    pendingFieldStartSyncIndex_ = fieldStartSyncIndex_;
    fieldStartRejectCount_ = verticalRelockCount_;
    return fieldStartSyncIndex_;
}

std::size_t AnalogVideoDecoder::lockedFieldStartSyncIndex(
        std::size_t candidateStartSyncIndex,
        std::size_t detectedSyncCount) {
    if (!config_.timing.interlaced || detectedSyncCount == 0) {
        return candidateStartSyncIndex;
    }

    const auto sourceLineCount = static_cast<std::size_t>((config_.timing.visibleLines + 1U) / 2U);
    const auto maxUsableStart = detectedSyncCount > sourceLineCount
            ? detectedSyncCount - sourceLineCount
            : 0U;
    const auto boundedCandidate = std::min(candidateStartSyncIndex, maxUsableStart);

    if (!fieldStartLocked_) {
        fieldStartSyncIndex_ = boundedCandidate;
        pendingFieldStartSyncIndex_ = boundedCandidate;
        fieldStartLocked_ = true;
        fieldStartRejectCount_ = 0;
        return fieldStartSyncIndex_;
    }

    const auto fieldLineCount = static_cast<std::size_t>(
            std::max(1.0, std::round(config_.timing.lineRateHz /
                                     (config_.timing.frameRateHz * 2.0))));
    auto nearestCandidate = boundedCandidate;
    if (fieldLineCount > 1U && nearestCandidate + fieldLineCount <= maxUsableStart) {
        const auto shiftedCandidate = nearestCandidate + fieldLineCount;
        if (std::abs(static_cast<long long>(shiftedCandidate) -
                     static_cast<long long>(fieldStartSyncIndex_)) <
            std::abs(static_cast<long long>(nearestCandidate) -
                     static_cast<long long>(fieldStartSyncIndex_))) {
            nearestCandidate = shiftedCandidate;
        }
    }
    if (fieldLineCount > 1U && nearestCandidate >= fieldLineCount) {
        const auto shiftedCandidate = nearestCandidate - fieldLineCount;
        if (std::abs(static_cast<long long>(shiftedCandidate) -
                     static_cast<long long>(fieldStartSyncIndex_)) <
            std::abs(static_cast<long long>(nearestCandidate) -
                     static_cast<long long>(fieldStartSyncIndex_))) {
            nearestCandidate = shiftedCandidate;
        }
    }

    constexpr auto kStartCorrectionDeadband = static_cast<long long>(8);
    constexpr auto kRelockCorrection = static_cast<long long>(9);
    constexpr auto kStableRejectedCandidateWindow = static_cast<long long>(2);
    constexpr auto kRejectedCorrectionsBeforeRelock = static_cast<std::size_t>(20U);
    const auto delta = std::abs(static_cast<long long>(nearestCandidate) -
                                static_cast<long long>(fieldStartSyncIndex_));
    if (delta <= kStartCorrectionDeadband) {
        fieldStartRejectCount_ = 0;
        pendingFieldStartSyncIndex_ = nearestCandidate;
    } else if (delta >= kRelockCorrection) {
        const auto rejectedDelta = std::abs(static_cast<long long>(nearestCandidate) -
                                            static_cast<long long>(pendingFieldStartSyncIndex_));
        if (rejectedDelta <= kStableRejectedCandidateWindow) {
            ++fieldStartRejectCount_;
        } else {
            pendingFieldStartSyncIndex_ = nearestCandidate;
            fieldStartRejectCount_ = 1;
        }
        if (fieldStartRejectCount_ >= kRejectedCorrectionsBeforeRelock) {
            fieldStartSyncIndex_ = std::min(nearestCandidate, maxUsableStart);
            pendingFieldStartSyncIndex_ = fieldStartSyncIndex_;
            fieldStartRejectCount_ = 0;
        }
    }

    return std::min(fieldStartSyncIndex_, maxUsableStart);
}

std::size_t AnalogVideoDecoder::samplesPerLine() const {
    return samplesPerLineAtRate(config_.sampleRateHz, config_.timing.lineRateHz);
}

}  // namespace sdr
