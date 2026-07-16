package com.example.sdrvideoscanner

import android.graphics.Bitmap

enum class SignalType {
    ANALOG,
    ANALOG_FPV,
    NARROWBAND,
    WIDEBAND_UNKNOWN,
    INTERFERENCE,
    DIGITAL,
    UNKNOWN,
}

enum class DirectionEstimate {
    APPROACHING,
    RECEDING,
    STABLE,
    UNKNOWN,
}

enum class ScannerState {
    IDLE,
    SCANNING,
    CANDIDATE_DETECTED,
    LOCKED_PLAYING,
    BACKGROUND_SCAN_WHILE_PLAYING,
    ERROR,
}

data class KnownChannel(
    val bandName: String,
    val channelName: String,
    val centerFrequencyHz: Long,
    val expectedSignalType: SignalType = SignalType.UNKNOWN,
    val rfFrontendProfileId: String? = null,
)

data class SignalProbeResult(
    val signalPresent: Boolean,
    val signalType: SignalType,
    val rssiDbfs: Double?,
    val confidence: Double,
    val previewFrame: Bitmap?,
    val diagnostic: String,
    val imageQuality: Double = 0.0,
    val sourceFailed: Boolean = false,
)

data class DetectedSignalRecord(
    val channel: KnownChannel,
    val signalType: SignalType,
    val rssiDbfs: Double?,
    val direction: DirectionEstimate,
    val previewFrame: Bitmap?,
    val lastSeenTimestampMs: Long,
    val confidence: Double,
    val diagnostic: String,
    val imageQuality: Double = 0.0,
    val measuredFrequencyHz: Long? = null,
    val nominalFrequencyHz: Long? = null,
    val frequencyOffsetHz: Long? = null,
    val peakPowerDb: Float? = null,
    val noiseFloorDb: Float? = null,
    val snrDb: Float? = null,
    val occupiedBandwidthHz: Long? = null,
    val firstSeenTimestampMs: Long? = null,
    val detectionCount: Int = 1,
    val status: String = "detected",
    val scanRangeId: String? = null,
)
