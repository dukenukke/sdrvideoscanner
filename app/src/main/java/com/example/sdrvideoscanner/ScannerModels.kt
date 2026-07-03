package com.example.sdrvideoscanner

import android.graphics.Bitmap

enum class SignalType {
    ANALOG,
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
)
