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
    val iqConfig: ScannerWebIqConfig? = null,
    val iqConfigDefaults: ScannerWebIqConfig? = null,
    val iqConfigurationStatus: String? = null,
    val iqConfigurationErrors: ScannerWebFieldErrors? = null,
    val decoderStatus: ScannerWebDecoderStatus? = null,
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
        .put("iqConfig", iqConfig?.toJson())
        .put("iqConfigDefaults", iqConfigDefaults?.toJson())
        .put("iqConfigurationStatus", iqConfigurationStatus)
        .put("iqConfigurationErrors", iqConfigurationErrors?.toJson())
        .put("decoderStatus", decoderStatus?.toJson())

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class ScannerWebDecoderStatus(
    val decodedFormat: String?,
    val frameRateFps: Double?,
    val syncLocked: Boolean?,
    val syncScore: Double?,
    val lineStabilityScore: Double?,
    val frameSyncEdges: Int?,
    val configuredIqSampleRateHz: Long?,
    val measuredIqSampleRateHz: Long?,
    val sourceSampleRateHz: Long?,
    val frameIndex: Long,
    val diagnostic: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("decodedFormat", decodedFormat)
        .put("frameRateFps", frameRateFps)
        .put("syncLocked", syncLocked)
        .put("syncScore", syncScore)
        .put("lineStabilityScore", lineStabilityScore)
        .put("frameSyncEdges", frameSyncEdges)
        .put("configuredIqSampleRateHz", configuredIqSampleRateHz)
        .put("measuredIqSampleRateHz", measuredIqSampleRateHz)
        .put("sourceSampleRateHz", sourceSampleRateHz)
        .put("frameIndex", frameIndex)
        .put("diagnostic", diagnostic)
}

data class ValidatedScanCommandConfig(
    val config: MaiaScanConfig,
    val ranges: List<ScanRange>?,
)

data class ScannerWebIqConfig(
    val iqSource: String,
    val maiaHost: String,
    val maiaPort: Int,
    val plutoWebSocketPath: String,
    val plutoWebSocketReceiveBufferMs: Int,
    val sampleFormat: String,
    val sampleRateHz: Long,
    val centerFrequencyHz: Long,
    val rfBandwidthHz: Long,
    val gainDb: Double,
    val captureDurationSec: Double,
    val loOffsetHz: Long,
    val hardwareIqCorrection: Boolean,
    val hardwareBbdcCorrection: Boolean,
    val hardwareRfdcCorrection: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("iqSource", iqSource)
        .put("maiaHost", maiaHost)
        .put("maiaPort", maiaPort)
        .put("plutoWebSocketPath", plutoWebSocketPath)
        .put("plutoWebSocketReceiveBufferMs", plutoWebSocketReceiveBufferMs)
        .put("sampleFormat", sampleFormat)
        .put("sampleRateHz", sampleRateHz)
        .put("centerFrequencyHz", centerFrequencyHz)
        .put("rfBandwidthHz", rfBandwidthHz)
        .put("gainDb", gainDb)
        .put("captureDurationSec", captureDurationSec)
        .put("loOffsetHz", loOffsetHz)
        .put("actualLoFrequencyHz", centerFrequencyHz + loOffsetHz)
        .put("hardwareIqCorrection", hardwareIqCorrection)
        .put("hardwareBbdcCorrection", hardwareBbdcCorrection)
        .put("hardwareRfdcCorrection", hardwareRfdcCorrection)
}

data class ScannerWebFieldErrors(
    val message: String,
    val fieldErrors: Map<String, String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("message", message)
        .put("fieldErrors", JSONObject().also { json ->
            fieldErrors.forEach { (field, error) -> json.put(field, error) }
        })
}

class ScannerWebValidationException(
    val errors: ScannerWebFieldErrors,
) : IllegalArgumentException(errors.message)

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

    fun validateIqConfig(jsonText: String): ScannerWebIqConfig {
        val json = JSONObject(jsonText.ifBlank { "{}" })
        val errors = linkedMapOf<String, String>()
        fun fieldError(field: String, message: String) {
            errors.putIfAbsent(field, message)
        }

        val iqSource = json.stringValue("iqSource")
            ?.lowercase()
            ?.takeIf { it == "iio" || it == "websocket" }
            ?: run {
                fieldError("iqSource", "IQ source must be IIO or WebSocket")
                "websocket"
            }
        val sampleFormat = json.stringValue("sampleFormat")
            ?.uppercase()
            ?.takeIf { it == "CS16" || it == "CS8" }
            ?: run {
                fieldError("sampleFormat", "IQ sample format must be CS16 or CS8")
                "CS8"
            }
        val host = json.stringValue("maiaHost")
            ?.takeIf { it.isNotBlank() && !it.contains("://") && !it.contains("/") && !it.contains(":") }
            ?: run {
                fieldError("maiaHost", "Enter only the host name or IP address")
                ""
            }
        val path = json.stringValue("plutoWebSocketPath")
            ?.takeIf { it.startsWith("/") && !it.contains(" ") && !it.contains("://") }
            ?: run {
                fieldError("plutoWebSocketPath", "Enter only the path, for example /iq")
                ""
            }
        val port = json.intValue("maiaPort")
            ?.takeIf { it in 1..65535 }
            ?: run {
                fieldError("maiaPort", "Maia HTTPD IQ WebSocket port must be between 1 and 65535")
                0
            }
        val receiveBufferMs = json.intValue("plutoWebSocketReceiveBufferMs")
            ?.takeIf { it in 50..150 }
            ?: run {
                fieldError("plutoWebSocketReceiveBufferMs", "Maia IQ WebSocket receive buffer must be between 50 and 150 ms")
                0
            }
        val sampleRateHz = json.longValue("sampleRateHz")
            ?.takeIf { it > 0L }
            ?: run {
                fieldError("sampleRateHz", "Sample rate must be a positive integer")
                0L
            }
        val centerFrequencyHz = json.longValue("centerFrequencyHz")
            ?.takeIf { it > 0L }
            ?: run {
                fieldError("centerFrequencyHz", "Center frequency must be a positive integer")
                0L
            }
        val rfBandwidthHz = json.longValue("rfBandwidthHz")
            ?.takeIf { it > 0L }
            ?: run {
                fieldError("rfBandwidthHz", "RF bandwidth must be a positive integer")
                0L
            }
        val gainDb = json.doubleValue("gainDb")
            ?.takeIf { it.isFinite() }
            ?: run {
                fieldError("gainDb", "Gain must be a valid number")
                0.0
            }
        val captureDurationSec = json.doubleValue("captureDurationSec")
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: run {
                fieldError("captureDurationSec", "Capture duration must be positive")
                0.0
            }
        val loOffsetHz = json.longValue("loOffsetHz")
            ?: run {
                fieldError("loOffsetHz", "LO offset must be an integer")
                0L
            }

        if (errors.isNotEmpty()) {
            throw ScannerWebValidationException(
                ScannerWebFieldErrors(
                    message = "IQ settings contain invalid values",
                    fieldErrors = errors,
                ),
            )
        }

        return ScannerWebIqConfig(
            iqSource = iqSource,
            maiaHost = host,
            maiaPort = port,
            plutoWebSocketPath = path,
            plutoWebSocketReceiveBufferMs = receiveBufferMs,
            sampleFormat = sampleFormat,
            sampleRateHz = sampleRateHz,
            centerFrequencyHz = centerFrequencyHz,
            rfBandwidthHz = rfBandwidthHz,
            gainDb = gainDb,
            captureDurationSec = captureDurationSec,
            loOffsetHz = loOffsetHz,
            hardwareIqCorrection = json.optBoolean("hardwareIqCorrection", true),
            hardwareBbdcCorrection = json.optBoolean("hardwareBbdcCorrection", true),
            hardwareRfdcCorrection = json.optBoolean("hardwareRfdcCorrection", true),
        )
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

private fun JSONObject.optRequiredString(key: String): String {
    val value = optString(key).trim()
    require(value.isNotBlank()) { "$key is required" }
    return value
}

private fun JSONObject.optRequiredChoice(key: String, allowed: Set<String>): String {
    val value = optRequiredString(key)
    return allowed.firstOrNull { it.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("$key has unsupported value")
}

private fun JSONObject.stringValue(key: String): String? {
    return if (has(key) && !isNull(key)) optString(key).trim() else null
}

private fun JSONObject.longValue(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().replace("_", "").replace(",", "").toLongOrNull()
        else -> null
    }
}

private fun JSONObject.intValue(key: String): Int? {
    val value = longValue(key) ?: return null
    return value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

private fun JSONObject.doubleValue(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.trim().replace("_", "").replace(",", "").toDoubleOrNull()
        else -> null
    }
}
