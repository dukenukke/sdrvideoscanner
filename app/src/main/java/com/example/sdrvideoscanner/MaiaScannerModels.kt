package com.example.sdrvideoscanner

import kotlin.math.abs

data class MaiaScanConfig(
    val sampleRateHz: Long = 30_720_000L,
    val rfBandwidthHz: Long = 18_000_000L,
    val frequencyStepHz: Long = 15_000_000L,
    val usableSpanHz: Long = 15_000_000L,
    val loSettlingMs: Long = 5L,
    val discardedFramesAfterRetune: Int = 2,
    val fastMeasurementMs: Long = 10L,
    val candidateMeasurementMs: Long = 75L,
    val candidateRevisitCount: Int = 3,
    val requiredPositiveRevisits: Int = 2,
    val retuneTimeoutMs: Long = 1_000L,
    val defaultRfPathSettlingMs: Long = 3L,
    val minSnrDb: Float = 8.0f,
    val minOccupiedBandwidthHz: Long = 2_000_000L,
    val maxOccupiedBandwidthHz: Long = 12_000_000L,
    val minAdjacentBins: Int = 3,
    val dcExclusionBins: Int = 3,
    val mergeFrequencyToleranceHz: Long = 3_000_000L,
    val fpvMatchToleranceHz: Long = 2_000_000L,
)

data class ScanRange(
    val id: String,
    val startFrequencyHz: Long,
    val endFrequencyHz: Long,
    val enabled: Boolean,
    val rfPathId: String?,
    val priority: Int = 0,
)

data class ScanWindow(
    val rangeId: String,
    val centerFrequencyHz: Long,
    val reliableStartFrequencyHz: Long,
    val reliableEndFrequencyHz: Long,
    val rfPathId: String?,
)

data class WaterfallFrame(
    val centerFrequencyHz: Long,
    val sampleRateHz: Long,
    val bandwidthHz: Long?,
    val sequenceNumber: Long?,
    val timestampNs: Long,
    val powerDb: FloatArray,
) {
    val binCount: Int get() = powerDb.size
}

data class SpectrumMeasurement(
    val centerFrequencyHz: Long,
    val startFrequencyHz: Long,
    val endFrequencyHz: Long,
    val binWidthHz: Double,
    val meanPowerDb: FloatArray,
    val maxPowerDb: FloatArray,
    val noiseFloorDb: Float,
    val frameCount: Int,
    val startedAtNs: Long,
    val finishedAtNs: Long,
    val peakFrequencyHz: Long,
    val peakPowerDb: Float,
    val integratedPowerDb: Float,
    val occupiedBandwidthHz: Long,
    val spectralCentroidHz: Long,
    val stabilityDb: Float,
)

data class SpectralCandidate(
    val rawCenterFrequencyHz: Long,
    val peakFrequencyHz: Long,
    val spectralCentroidHz: Long,
    val startFrequencyHz: Long,
    val endFrequencyHz: Long,
    val occupiedBandwidthHz: Long,
    val peakPowerDb: Float,
    val integratedPowerDb: Float,
    val noiseFloorDb: Float,
    val snrDb: Float,
    val firstSeenNs: Long,
    val lastSeenNs: Long,
    val sourceScanCenterHz: Long,
    val confidence: Float,
    val scanRangeId: String,
    val detectionCount: Int = 1,
)

data class FpvChannel(
    val bandName: String,
    val channelName: String,
    val nominalFrequencyHz: Long,
    val expectedSignalType: SignalType = SignalType.UNKNOWN,
)

data class FpvChannelMatch(
    val bandName: String,
    val channelName: String,
    val nominalFrequencyHz: Long,
    val measuredFrequencyHz: Long,
    val offsetHz: Long,
    val withinTolerance: Boolean,
)

data class SignalClassification(
    val type: SignalType,
    val confidence: Float,
    val reasons: List<String>,
)

enum class MaiaScannerState {
    STOPPED,
    CONNECTING,
    CONFIGURING,
    FAST_SCAN,
    RETUNING,
    SETTLING,
    COLLECTING,
    ANALYZING,
    CANDIDATE_REVISIT,
    PAUSED,
    ERROR,
}

enum class SdrOperatingMode {
    IDLE,
    MAIA_SCAN,
    VIDEO_DECODE,
}

data class ScannerStatistics(
    val completedScanCycles: Long = 0,
    val completedScanSteps: Long = 0,
    val failedScanSteps: Long = 0,
    val websocketReconnects: Long = 0,
    val receivedFftFrames: Long = 0,
    val droppedFftFrames: Long = 0,
    val discardedRetuneFrames: Long = 0,
    val candidatesDetected: Long = 0,
    val candidatesConfirmed: Long = 0,
    val averageRetuneLatencyMs: Double = 0.0,
    val averageStepDurationMs: Double = 0.0,
    val lastCycleDurationMs: Long = 0,
)

data class ConfirmedSignal(
    val candidate: SpectralCandidate,
    val channelMatch: FpvChannelMatch?,
    val classification: SignalClassification,
)

object DefaultMaiaScanRanges {
    val ranges: List<ScanRange> = listOf(
        ScanRange("band_1g2", 1_200_000_000L, 1_400_000_000L, enabled = true, rfPathId = "band_1g2", priority = 10),
        ScanRange("band_2g4", 2_300_000_000L, 2_500_000_000L, enabled = true, rfPathId = "band_2g4", priority = 5),
        ScanRange("band_3g3", 3_200_000_000L, 3_500_000_000L, enabled = true, rfPathId = "band_3g3", priority = 8),
        ScanRange("band_4g9_6g0", 4_900_000_000L, 6_000_000_000L, enabled = true, rfPathId = "band_5g8", priority = 9),
    )
}

fun SpectralCandidate.overlapsOrNear(other: SpectralCandidate, toleranceHz: Long): Boolean {
    val overlaps = startFrequencyHz <= other.endFrequencyHz + toleranceHz &&
        other.startFrequencyHz <= endFrequencyHz + toleranceHz
    val centroidClose = abs(spectralCentroidHz - other.spectralCentroidHz) <= toleranceHz
    return overlaps || centroidClose
}
