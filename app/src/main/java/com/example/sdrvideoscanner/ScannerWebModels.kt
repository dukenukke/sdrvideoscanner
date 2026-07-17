package com.example.sdrvideoscanner

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class ScannerEventRecord(
    val type: String,
    val message: String,
    val timestampMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("message", message)
        .put("timestampMs", timestampMs)
}

data class ScannerWebSnapshot(
    val scannerState: String,
    val operatingMode: String,
    val activeScanRange: String?,
    val currentLoFrequencyHz: Long?,
    val scanWindowIndex: Int,
    val scanWindowTotal: Int,
    val statistics: ScannerStatistics,
    val noiseFloorDb: Float?,
    val retuneLatencyMs: Double?,
    val confirmedSignals: List<DetectedSignalRecord>,
    val pendingCandidates: List<SpectralCandidate> = emptyList(),
    val diagnostics: String? = null,
    val events: List<ScannerEventRecord> = emptyList(),
    val requestedWaterfallFrameRateFps: Double? = null,
    val actualWaterfallFrameRateFps: Double? = null,
    val spectrometerConfigurationStatus: String? = null,
    val websocketConnectionStatus: String? = null,
    val effectiveRetuneTimeoutMs: Long? = null,
    val staleFramesToDiscard: Int? = null,
    val latestInitializationError: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("scannerState", scannerState)
        .put("operatingMode", operatingMode)
        .put("activeScanRange", activeScanRange)
        .put("currentLoFrequencyHz", currentLoFrequencyHz)
        .put("scanWindowIndex", scanWindowIndex)
        .put("scanWindowTotal", scanWindowTotal)
        .put("statistics", statistics.toJson())
        .put("noiseFloorDb", noiseFloorDb)
        .put("retuneLatencyMs", retuneLatencyMs)
        .put("confirmedSignals", JSONArray().also { array ->
            confirmedSignals.forEach { array.put(it.toWebJson()) }
        })
        .put("pendingCandidates", JSONArray().also { array ->
            pendingCandidates.forEach { array.put(it.toWebJson()) }
        })
        .put("diagnostics", diagnostics)
        .put("events", JSONArray().also { array ->
            events.forEach { array.put(it.toJson()) }
        })
        .put("requestedWaterfallFrameRateFps", requestedWaterfallFrameRateFps)
        .put("actualWaterfallFrameRateFps", actualWaterfallFrameRateFps)
        .put("spectrometerConfigurationStatus", spectrometerConfigurationStatus)
        .put("websocketConnectionStatus", websocketConnectionStatus)
        .put("effectiveRetuneTimeoutMs", effectiveRetuneTimeoutMs)
        .put("staleFramesToDiscard", staleFramesToDiscard)
        .put("latestInitializationError", latestInitializationError)

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class ValidatedScanCommandConfig(
    val config: MaiaScanConfig,
    val ranges: List<ScanRange>?,
)

object ScannerWebCommandValidator {
    fun parseSignalId(value: String?): Long? {
        return value?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    }

    fun validateScanConfig(jsonText: String): ValidatedScanCommandConfig {
        val json = JSONObject(jsonText.ifBlank { "{}" })
        val defaults = MaiaScanConfig()
        val config = MaiaScanConfig(
            sampleRateHz = json.optLongInRange("sampleRateHz", defaults.sampleRateHz, 1_000_000L, 61_440_000L),
            rfBandwidthHz = json.optLongInRange("rfBandwidthHz", defaults.rfBandwidthHz, 1_000_000L, 56_000_000L),
            frequencyStepHz = json.optLongInRange("frequencyStepHz", defaults.frequencyStepHz, 100_000L, 100_000_000L),
            usableSpanHz = json.optLongInRange("usableSpanHz", defaults.usableSpanHz, 100_000L, 61_440_000L),
            loSettlingMs = json.optLongInRange("loSettlingMs", defaults.loSettlingMs, 0L, 2_000L),
            discardedFramesAfterRetune = json.optIntInRange(
                "discardedFramesAfterRetune",
                defaults.discardedFramesAfterRetune,
                0,
                32,
            ),
            fastMeasurementMs = json.optLongInRange("fastMeasurementMs", defaults.fastMeasurementMs, 1L, 2_000L),
            candidateMeasurementMs = json.optLongInRange("candidateMeasurementMs", defaults.candidateMeasurementMs, 1L, 5_000L),
            candidateRevisitCount = json.optIntInRange("candidateRevisitCount", defaults.candidateRevisitCount, 1, 10),
            requiredPositiveRevisits = json.optIntInRange("requiredPositiveRevisits", defaults.requiredPositiveRevisits, 1, 10),
            retuneTimeoutMs = json.optLongInRange("retuneTimeoutMs", defaults.retuneTimeoutMs, 10L, 10_000L),
            retuneTimeoutSafetyMarginMs = json.optLongInRange(
                "retuneTimeoutSafetyMarginMs",
                defaults.retuneTimeoutSafetyMarginMs,
                0L,
                5_000L,
            ),
            waterfallFrameRateFps = json.optDoubleInRange(
                "waterfallFrameRateFps",
                defaults.waterfallFrameRateFps,
                1.0,
                120.0,
            ),
            defaultRfPathSettlingMs = json.optLongInRange("defaultRfPathSettlingMs", defaults.defaultRfPathSettlingMs, 0L, 1_000L),
            minSnrDb = json.optFloatInRange("minSnrDb", defaults.minSnrDb, 0.0f, 80.0f),
            minOccupiedBandwidthHz = json.optLongInRange(
                "minOccupiedBandwidthHz",
                defaults.minOccupiedBandwidthHz,
                0L,
                100_000_000L,
            ),
            maxOccupiedBandwidthHz = json.optLongInRange(
                "maxOccupiedBandwidthHz",
                defaults.maxOccupiedBandwidthHz,
                1L,
                100_000_000L,
            ),
            minAdjacentBins = json.optIntInRange("minAdjacentBins", defaults.minAdjacentBins, 1, 512),
            dcExclusionBins = json.optIntInRange("dcExclusionBins", defaults.dcExclusionBins, 0, 512),
            mergeFrequencyToleranceHz = json.optLongInRange(
                "mergeFrequencyToleranceHz",
                defaults.mergeFrequencyToleranceHz,
                0L,
                100_000_000L,
            ),
            fpvMatchToleranceHz = json.optLongInRange("fpvMatchToleranceHz", defaults.fpvMatchToleranceHz, 0L, 100_000_000L),
        )
        require(config.usableSpanHz <= config.sampleRateHz) { "usableSpanHz must be <= sampleRateHz" }
        require(config.minOccupiedBandwidthHz <= config.maxOccupiedBandwidthHz) {
            "minOccupiedBandwidthHz must be <= maxOccupiedBandwidthHz"
        }
        MaiaScanTiming.validateRetuneTimeout(config)
        val ranges = json.optJSONArray("ranges")?.let { parseRanges(it) }
        return ValidatedScanCommandConfig(config, ranges)
    }

    private fun parseRanges(array: JSONArray): List<ScanRange> {
        val ranges = mutableListOf<ScanRange>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val id = item.optString("id").trim()
            require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "invalid range id" }
            val start = item.optLongInRange("startFrequencyHz", 0L, 1L, 10_000_000_000L)
            val end = item.optLongInRange("endFrequencyHz", 0L, 1L, 10_000_000_000L)
            require(end > start) { "range endFrequencyHz must be greater than startFrequencyHz" }
            ranges.add(
                ScanRange(
                    id = id,
                    startFrequencyHz = start,
                    endFrequencyHz = end,
                    enabled = item.optBoolean("enabled", true),
                    rfPathId = item.optString("rfPathId").trim().takeIf { it.isNotBlank() },
                    priority = item.optInt("priority", 0),
                ),
            )
        }
        return ranges
    }
}

enum class ScannerUiMode {
    IDLE,
    SCANNING,
    PAUSED,
    VIDEO,
}

object ScannerModeTransitionPolicy {
    fun lockAllowed(signal: DetectedSignalRecord?): Boolean {
        return signal != null && (signal.signalType == SignalType.ANALOG || signal.signalType == SignalType.ANALOG_FPV)
    }

    fun afterShowSpectrum(current: ScannerUiMode): ScannerUiMode {
        return if (current == ScannerUiMode.VIDEO) ScannerUiMode.SCANNING else current
    }

    fun afterReleaseSignal(): ScannerUiMode = ScannerUiMode.SCANNING
}

fun ScannerStatistics.toJson(): JSONObject = JSONObject()
    .put("completedScanCycles", completedScanCycles)
    .put("completedScanSteps", completedScanSteps)
    .put("failedScanSteps", failedScanSteps)
    .put("websocketReconnects", websocketReconnects)
    .put("receivedFftFrames", receivedFftFrames)
    .put("droppedFftFrames", droppedFftFrames)
    .put("discardedRetuneFrames", discardedRetuneFrames)
    .put("candidatesDetected", candidatesDetected)
    .put("candidatesConfirmed", candidatesConfirmed)
    .put("averageRetuneLatencyMs", averageRetuneLatencyMs)
    .put("averageStepDurationMs", averageStepDurationMs)
    .put("lastCycleDurationMs", lastCycleDurationMs)

fun DetectedSignalRecord.toWebJson(): JSONObject {
    val measured = measuredFrequencyHz ?: channel.centerFrequencyHz
    val nominal = nominalFrequencyHz
    return JSONObject()
        .put("id", measured.toString())
        .put("measuredFrequencyHz", measured)
        .put("measuredFrequencyMHz", measured / 1_000_000.0)
        .put("nominalFrequencyHz", nominal)
        .put("nominalFrequencyMHz", nominal?.let { it / 1_000_000.0 })
        .put("bandName", channel.bandName)
        .put("channelName", channel.channelName)
        .put("frequencyOffsetHz", frequencyOffsetHz)
        .put("frequencyOffsetMHz", frequencyOffsetHz?.let { it / 1_000_000.0 })
        .put("signalType", signalType.name)
        .put("classificationConfidence", confidence)
        .put("peakPowerDb", peakPowerDb ?: rssiDbfs)
        .put("noiseFloorDb", noiseFloorDb)
        .put("snrDb", snrDb)
        .put("occupiedBandwidthHz", occupiedBandwidthHz)
        .put("occupiedBandwidthMHz", occupiedBandwidthHz?.let { it / 1_000_000.0 })
        .put("firstSeenTimestampMs", firstSeenTimestampMs)
        .put("lastSeenTimestampMs", lastSeenTimestampMs)
        .put("detectionCount", detectionCount)
        .put("status", status)
        .put("scanRange", scanRangeId)
        .put("direction", direction.name)
        .put("diagnostic", diagnostic)
}

fun SpectralCandidate.toWebJson(): JSONObject = JSONObject()
    .put("id", rawCenterFrequencyHz.toString())
    .put("measuredFrequencyHz", rawCenterFrequencyHz)
    .put("peakFrequencyHz", peakFrequencyHz)
    .put("spectralCentroidHz", spectralCentroidHz)
    .put("startFrequencyHz", startFrequencyHz)
    .put("endFrequencyHz", endFrequencyHz)
    .put("occupiedBandwidthHz", occupiedBandwidthHz)
    .put("peakPowerDb", peakPowerDb)
    .put("integratedPowerDb", integratedPowerDb)
    .put("noiseFloorDb", noiseFloorDb)
    .put("snrDb", snrDb)
    .put("confidence", (confidence * 100.0f).roundToInt())
    .put("firstSeenNs", firstSeenNs)
    .put("lastSeenNs", lastSeenNs)
    .put("sourceScanCenterHz", sourceScanCenterHz)
    .put("scanRange", scanRangeId)
    .put("detectionCount", detectionCount)

private fun JSONObject.optLongInRange(key: String, defaultValue: Long, min: Long, max: Long): Long {
    val value = if (has(key) && !isNull(key)) optLong(key) else defaultValue
    require(value in min..max) { "$key out of range" }
    return value
}

private fun JSONObject.optIntInRange(key: String, defaultValue: Int, min: Int, max: Int): Int {
    val value = if (has(key) && !isNull(key)) optInt(key) else defaultValue
    require(value in min..max) { "$key out of range" }
    return value
}

private fun JSONObject.optFloatInRange(key: String, defaultValue: Float, min: Float, max: Float): Float {
    val value = if (has(key) && !isNull(key)) optDouble(key).toFloat() else defaultValue
    require(value in min..max) { "$key out of range" }
    return value
}

private fun JSONObject.optDoubleInRange(key: String, defaultValue: Double, min: Double, max: Double): Double {
    val value = if (has(key) && !isNull(key)) optDouble(key) else defaultValue
    require(value.isFinite() && value in min..max) { "$key out of range" }
    return value
}
