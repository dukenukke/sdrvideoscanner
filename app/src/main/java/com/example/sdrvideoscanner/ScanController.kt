package com.example.sdrvideoscanner

import kotlin.math.abs

class ScanController(
    private val channels: List<KnownChannel>,
    private val rfFrontendController: IRfFrontendController,
    private val staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    var state: ScannerState = ScannerState.IDLE
        private set

    private val recordsByFrequency = linkedMapOf<Long, DetectedSignalRecord>()
    private var nextChannelIndex = 0

    fun startScanning() {
        state = ScannerState.SCANNING
    }

    fun startScanningNear(frequencyHz: Long) {
        nextChannelIndex = nearestChannelIndex(frequencyHz)
        state = ScannerState.SCANNING
    }

    fun lockPlaying() {
        state = ScannerState.LOCKED_PLAYING
    }

    fun backgroundScanWhilePlaying() {
        state = ScannerState.BACKGROUND_SCAN_WHILE_PLAYING
    }

    fun idle() {
        state = ScannerState.IDLE
    }

    fun nextChannel(excluding: KnownChannel? = null): KnownChannel? {
        if (channels.isEmpty()) {
            return null
        }

        repeat(channels.size) {
            val channel = channels[nextChannelIndex]
            nextChannelIndex = (nextChannelIndex + 1) % channels.size
            if (excluding == null || channel.centerFrequencyHz != excluding.centerFrequencyHz) {
                return channel
            }
        }
        return null
    }

    private fun nearestChannelIndex(frequencyHz: Long): Int {
        if (channels.isEmpty()) {
            return 0
        }
        return channels.indices.minBy { index ->
            abs(channels[index].centerFrequencyHz - frequencyHz)
        }
    }

    fun configureFrontend(channel: KnownChannel): Boolean {
        return rfFrontendController.selectBandProfile(channel.rfFrontendProfileId) &&
            rfFrontendController.selectAntennaPath(channel.rfFrontendProfileId) &&
            rfFrontendController.selectBpfPath(channel.rfFrontendProfileId) &&
            rfFrontendController.setLnaEnabled(true) &&
            rfFrontendController.selectMixerProfile(channel.rfFrontendProfileId)
    }

    fun applyProbeResult(channel: KnownChannel, result: SignalProbeResult): List<DetectedSignalRecord> {
        val now = clockMs()
        val previous = recordsByFrequency[channel.centerFrequencyHz]
        if (result.signalPresent) {
            val direction = estimateDirection(previous?.rssiDbfs, result.rssiDbfs)
            val record = DetectedSignalRecord(
                channel = channel,
                signalType = result.signalType,
                rssiDbfs = result.rssiDbfs,
                direction = direction,
                previewFrame = result.previewFrame ?: previous?.previewFrame,
                lastSeenTimestampMs = now,
                confidence = result.confidence,
                diagnostic = result.diagnostic,
            )
            recordsByFrequency[channel.centerFrequencyHz] = record
            state = ScannerState.CANDIDATE_DETECTED
        }
        expireStaleRecords(now)
        return records()
    }

    fun records(): List<DetectedSignalRecord> {
        return strongestRecordsByFrequencyCluster(recordsByFrequency.values.toList())
            .sortedWith(
                compareBy<DetectedSignalRecord> { it.channel.bandName }
                    .thenBy { it.channel.centerFrequencyHz },
            )
    }

    fun expireStaleRecords(nowMs: Long = clockMs()): List<DetectedSignalRecord> {
        val staleFrequencies = recordsByFrequency
            .filterValues { nowMs - it.lastSeenTimestampMs > staleTimeoutMs }
            .keys
            .toList()
        staleFrequencies.forEach { recordsByFrequency.remove(it) }
        return records()
    }

    private fun estimateDirection(previousRssi: Double?, currentRssi: Double?): DirectionEstimate {
        if (previousRssi == null || currentRssi == null) {
            return DirectionEstimate.UNKNOWN
        }
        val delta = currentRssi - previousRssi
        return when {
            abs(delta) < STABLE_RSSI_DELTA_DB -> DirectionEstimate.STABLE
            delta > 0.0 -> DirectionEstimate.APPROACHING
            else -> DirectionEstimate.RECEDING
        }
    }

    private fun strongestRecordsByFrequencyCluster(
        records: List<DetectedSignalRecord>,
    ): List<DetectedSignalRecord> {
        if (records.isEmpty()) {
            return emptyList()
        }

        val sortedRecords = records.sortedBy { it.channel.centerFrequencyHz }
        val result = mutableListOf<DetectedSignalRecord>()
        var cluster = mutableListOf<DetectedSignalRecord>()
        var previousFrequencyHz: Long? = null

        fun flushCluster() {
            if (cluster.isEmpty()) {
                return
            }
            result.add(
                cluster.maxWith(
                    compareBy<DetectedSignalRecord> { it.rssiDbfs ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.confidence }
                        .thenByDescending { it.lastSeenTimestampMs },
                ),
            )
            cluster = mutableListOf()
            previousFrequencyHz = null
        }

        for (record in sortedRecords) {
            val previousFrequency = previousFrequencyHz
            if (previousFrequency == null ||
                abs(record.channel.centerFrequencyHz - previousFrequency) <= CO_CHANNEL_CLUSTER_WIDTH_HZ
            ) {
                cluster.add(record)
            } else {
                flushCluster()
                cluster.add(record)
            }
            previousFrequencyHz = record.channel.centerFrequencyHz
        }
        flushCluster()
        return result
    }

    companion object {
        private const val DEFAULT_STALE_TIMEOUT_MS = 15_000L
        private const val STABLE_RSSI_DELTA_DB = 2.0
        private const val CO_CHANNEL_CLUSTER_WIDTH_HZ = 50_000_000L
    }
}
