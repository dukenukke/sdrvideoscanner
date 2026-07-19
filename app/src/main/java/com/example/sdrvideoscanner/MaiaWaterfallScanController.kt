package com.example.sdrvideoscanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

class MaiaWaterfallScanController(
    private val source: WaterfallSource,
    private val tuner: IRadioTuner,
    private val rfPathController: RfPathController = NoOpRfPathController(),
    private val fpvChannelRepository: FpvChannelRepository = ChannelPlanFpvChannelRepository(),
    private val classifier: AnalogFpvHeuristicClassifier = AnalogFpvHeuristicClassifier(),
    private val onRadioMetadataChanged: (centerFrequencyHz: Long, sampleRateHz: Long, bandwidthHz: Long?) -> Unit = { _, _, _ -> },
    private val onStateChanged: (MaiaScannerState) -> Unit = {},
    private val onStatisticsChanged: (ScannerStatistics) -> Unit = {},
    private val onConfirmedSignals: (List<DetectedSignalRecord>) -> Unit = {},
    private val onScanWindowChanged: (window: ScanWindow, index: Int, total: Int) -> Unit = { _, _, _ -> },
    private val onMeasurement: (SpectrumMeasurement) -> Unit = {},
    private val onSpectrumFrame: (WaterfallFrame) -> Unit = {},
    private val onRuntimeStatusChanged: (MaiaScannerRuntimeStatus) -> Unit = {},
    private val onEvent: (type: String, message: String) -> Unit = { _, _ -> },
    private val clockNs: () -> Long = { System.nanoTime() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var job: Job? = null
    private var paused = false
    private var state = MaiaScannerState.STOPPED
    private var statistics = ScannerStatistics()
    private val confirmedSignals = linkedMapOf<Long, DetectedSignalRecord>()
    private var nextCandidateTableRescanNs: Long = 0L
    private var runtimeStatus = MaiaScannerRuntimeStatus(
        requestedWaterfallFrameRateFps = MaiaScanConfig().waterfallFrameRateFps,
        actualWaterfallFrameRateFps = null,
        spectrometerStatus = "idle",
        websocketStatus = "disconnected",
        effectiveRetuneTimeoutMs = MaiaScanConfig().retuneTimeoutMs,
        staleFramesToDiscard = MaiaScanConfig().discardedFramesAfterRetune,
    )

    fun start(config: MaiaScanConfig, ranges: List<ScanRange>) {
        stop()
        paused = false
        job = scope.launch {
            runScanner(config, ranges)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        paused = false
        setState(MaiaScannerState.STOPPED)
        scope.launch {
            source.disconnect()
        }
    }

    fun pause() {
        paused = true
        setState(MaiaScannerState.PAUSED)
    }

    fun resume() {
        paused = false
    }

    fun statistics(): ScannerStatistics = statistics

    private suspend fun runScanner(config: MaiaScanConfig, ranges: List<ScanRange>) {
        try {
            lifecycleMutex.withLock {
                runtimeStatus = MaiaScannerRuntimeStatus(
                    requestedWaterfallFrameRateFps = config.waterfallFrameRateFps,
                    actualWaterfallFrameRateFps = null,
                    spectrometerStatus = "pending",
                    websocketStatus = "disconnected",
                    effectiveRetuneTimeoutMs = config.retuneTimeoutMs,
                    staleFramesToDiscard = config.discardedFramesAfterRetune,
                )
                publishRuntimeStatus()
                val windows = MaiaScanPlanner.generateWindows(ranges, config)
                if (windows.isEmpty()) {
                    throw IllegalStateException("No enabled Maia scan windows")
                }
                val firstWindow = windows.first()
                onScanWindowChanged(firstWindow, 1, windows.size)
                onEvent("system", "HTTP configuration started")
                setState(MaiaScannerState.CONFIGURING_RADIO)
                setState(MaiaScannerState.CONFIGURING_SPECTROMETER)
                val configuration = tuner.configureForScan(config, firstWindow.centerFrequencyHz)
                val actualFps = configuration.actualWaterfallFrameRateFps ?: config.waterfallFrameRateFps
                MaiaScanTiming.validateRetuneTimeout(config, actualFps)
                runtimeStatus = runtimeStatus.copy(
                    actualWaterfallFrameRateFps = actualFps,
                    spectrometerStatus = configuration.spectrometerStatus,
                    effectiveRetuneTimeoutMs = config.retuneTimeoutMs,
                    latestInitializationError = null,
                )
                publishRuntimeStatus()
                onRadioMetadataChanged(firstWindow.centerFrequencyHz, config.sampleRateHz, config.rfBandwidthHz)
                onEvent("success", "Spectrometer configuration accepted")
                onEvent("system", "Waterfall FPS requested ${config.waterfallFrameRateFps}, actual $actualFps")
                setState(MaiaScannerState.CONNECTING_WATERFALL)
                runtimeStatus = runtimeStatus.copy(websocketStatus = "connecting")
                publishRuntimeStatus()
                source.connect()
                scanLoop(config, windows)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runtimeStatus = runtimeStatus.copy(
                spectrometerStatus = if (runtimeStatus.spectrometerStatus == "pending") "failed" else runtimeStatus.spectrometerStatus,
                websocketStatus = "disconnected",
                latestInitializationError = error.message ?: error.javaClass.name,
            )
            publishRuntimeStatus()
            onEvent("error", "Maia scanner initialization/configuration failed: ${error.message ?: error.javaClass.name}")
            setState(MaiaScannerState.ERROR)
        } finally {
            source.disconnect()
            runtimeStatus = runtimeStatus.copy(websocketStatus = "disconnected")
            publishRuntimeStatus()
            if (state != MaiaScannerState.ERROR) {
                setState(MaiaScannerState.STOPPED)
            }
        }
    }

    private suspend fun scanLoop(config: MaiaScanConfig, windows: List<ScanWindow>) {
        val detector = SpectralCandidateDetector(config)
        nextCandidateTableRescanNs = clockNs() + CANDIDATE_TABLE_RESCAN_INTERVAL_NS
        onEvent("success", "Scanning started or resumed")
        while (currentCoroutineContext().isActive && windows.isNotEmpty()) {
            val cycleStartNs = clockNs()
            val passCandidates = mutableListOf<SpectralCandidate>()
            setState(MaiaScannerState.FAST_SCAN)
            for ((index, window) in windows.withIndex()) {
                waitIfPaused()
                rescanCandidateTableIfDue(config)
                setState(MaiaScannerState.FAST_SCAN)
                onScanWindowChanged(window, index + 1, windows.size)
                val stepStartNs = clockNs()
                selectRfPathIfNeeded(window, config)
                val measurement = measureWindow(window, config, config.fastMeasurementMs)
                if (measurement == null) {
                    statistics = statistics.copy(failedScanSteps = statistics.failedScanSteps + 1)
                    onStatisticsChanged(statistics)
                    continue
                }
                onMeasurement(measurement)
                setState(MaiaScannerState.ANALYZING)
                passCandidates += detector.detect(measurement, window)
                statistics = statistics.copy(
                    completedScanSteps = statistics.completedScanSteps + 1,
                    candidatesDetected = statistics.candidatesDetected + passCandidates.size,
                    averageStepDurationMs = rollingAverage(
                        statistics.averageStepDurationMs,
                        statistics.completedScanSteps,
                        (clockNs() - stepStartNs) / 1_000_000.0,
                    ),
                )
                onStatisticsChanged(statistics)
            }

            val merged = CandidateMerger.merge(passCandidates, config.mergeFrequencyToleranceHz)
            val confirmed = revisitCandidates(merged, config)
            publishConfirmed(confirmed, config)
            statistics = statistics.copy(
                completedScanCycles = statistics.completedScanCycles + 1,
                candidatesConfirmed = statistics.candidatesConfirmed + confirmed.size,
                lastCycleDurationMs = ((clockNs() - cycleStartNs) / 1_000_000L),
            )
            onStatisticsChanged(statistics)
        }
    }

    private suspend fun rescanCandidateTableIfDue(config: MaiaScanConfig) {
        val nowNs = clockNs()
        if (confirmedSignals.isEmpty() || nowNs < nextCandidateTableRescanNs) {
            return
        }
        rescanConfirmedSignalTable(config)
        nextCandidateTableRescanNs = clockNs() + CANDIDATE_TABLE_RESCAN_INTERVAL_NS
    }

    private suspend fun rescanConfirmedSignalTable(config: MaiaScanConfig) {
        val records = confirmedSignals.values.toList()
        if (records.isEmpty()) {
            return
        }
        setState(MaiaScannerState.CANDIDATE_REVISIT)
        onEvent("system", "Interrupting sweep to rescan ${records.size} candidate table record(s)")
        val detector = SpectralCandidateDetector(config)
        var presentCount = 0
        for (record in records) {
            waitIfPaused()
            val window = scanWindowForRecord(record, config)
            selectRfPathIfNeeded(window, config)
            val measurement = measureWindow(window, config, config.candidateMeasurementMs)
            val matching = measurement
                ?.let { detector.detect(it, window) }
                .orEmpty()
                .filter { candidateMatchesRecord(it, record, config) }
                .maxWithOrNull(compareBy<SpectralCandidate> { it.snrDb }.thenBy { it.confidence })
            if (matching == null) {
                markCandidateNotPresent(record)
                continue
            }
            val match = fpvChannelRepository.findNearest(matching.rawCenterFrequencyHz, config.fpvMatchToleranceHz)
            val classification = classifier.classify(matching, match)
            val recordFrequencyHz = record.measuredFrequencyHz ?: record.channel.centerFrequencyHz
            updateConfirmedSignal(
                ConfirmedSignal(
                    candidate = matching.copy(
                        rawCenterFrequencyHz = recordFrequencyHz,
                        detectionCount = record.detectionCount + 1,
                    ),
                    channelMatch = match,
                    classification = classification,
                ),
                config,
            )
            presentCount += 1
        }
        onConfirmedSignals(confirmedSignals.values.toList())
        onEvent("success", "Candidate table rescan completed: $presentCount/${records.size} present")
    }

    private suspend fun selectRfPathIfNeeded(window: ScanWindow, config: MaiaScanConfig) {
        val path = window.rfPathId ?: return
        if (rfPathController.getCurrentPath() == path) {
            return
        }
        rfPathController.selectPath(path)
        delay(config.defaultRfPathSettlingMs)
    }

    private suspend fun measureWindow(
        window: ScanWindow,
        config: MaiaScanConfig,
        measurementMs: Long,
    ): SpectrumMeasurement? {
        setState(MaiaScannerState.RETUNING)
        val retuneStartNs = clockNs()
        tuner.tune(window.centerFrequencyHz)
        setState(MaiaScannerState.SETTLING)
        delay(config.loSettlingMs)
        onRadioMetadataChanged(window.centerFrequencyHz, config.sampleRateHz, config.rfBandwidthHz)

        val firstValid = waitForFirstValidFrame(window.centerFrequencyHz, retuneStartNs, config)
            ?: return null
        val retuneLatencyMs = (firstValid.timestampNs - retuneStartNs) / 1_000_000.0
        statistics = statistics.copy(
            averageRetuneLatencyMs = rollingAverage(
                statistics.averageRetuneLatencyMs,
                statistics.completedScanSteps + statistics.failedScanSteps,
                retuneLatencyMs,
            ),
        )

        setState(MaiaScannerState.COLLECTING)
        val aggregator = SpectrumAggregator(window.centerFrequencyHz, config.sampleRateHz)
        aggregator.add(firstValid)
        val endNs = clockNs() + measurementMs * 1_000_000L
        while (clockNs() < endNs && currentCoroutineContext().isActive) {
            val remainingMs = ((endNs - clockNs()) / 1_000_000L).coerceAtLeast(1L)
            val frame = withTimeoutOrNull(remainingMs) { source.frames().first() } ?: break
            statistics = statistics.copy(receivedFftFrames = statistics.receivedFftFrames + 1)
            if (isFrameValidForRetune(frame, window.centerFrequencyHz, retuneStartNs)) {
                aggregator.add(frame)
                onSpectrumFrame(frame)
            } else {
                statistics = statistics.copy(droppedFftFrames = statistics.droppedFftFrames + 1)
            }
        }
        return aggregator.toMeasurement()
    }

    private suspend fun waitForFirstValidFrame(
        centerFrequencyHz: Long,
        retuneStartNs: Long,
        config: MaiaScanConfig,
    ): WaterfallFrame? {
        val deadlineNs = clockNs() + config.retuneTimeoutMs * 1_000_000L
        var discarded = 0
        setState(MaiaScannerState.DISCARDING_STALE_FRAMES)
        while (clockNs() < deadlineNs && currentCoroutineContext().isActive) {
            val remainingMs = ((deadlineNs - clockNs()) / 1_000_000L).coerceAtLeast(1L)
            val frame = withTimeoutOrNull(remainingMs) { source.frames().first() } ?: return null
            statistics = statistics.copy(receivedFftFrames = statistics.receivedFftFrames + 1)
            if (!isFrameValidForRetune(frame, centerFrequencyHz, retuneStartNs)) {
                statistics = statistics.copy(droppedFftFrames = statistics.droppedFftFrames + 1)
                continue
            }
            if (discarded < config.discardedFramesAfterRetune) {
                discarded += 1
                statistics = statistics.copy(discardedRetuneFrames = statistics.discardedRetuneFrames + 1)
                continue
            }
            onEvent("success", "Stale-frame guard completed")
            onSpectrumFrame(frame)
            return frame
        }
        return null
    }

    private fun isFrameValidForRetune(frame: WaterfallFrame, centerFrequencyHz: Long, retuneStartNs: Long): Boolean {
        return WaterfallFrameValidator.isValidForRetune(frame, centerFrequencyHz, retuneStartNs)
    }

    private suspend fun revisitCandidates(
        candidates: List<SpectralCandidate>,
        config: MaiaScanConfig,
    ): List<ConfirmedSignal> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        setState(MaiaScannerState.CANDIDATE_REVISIT)
        val confirmed = mutableListOf<ConfirmedSignal>()
        val prioritized = candidates.sortedWith(compareByDescending<SpectralCandidate> { it.snrDb }.thenByDescending { it.confidence })
        for (candidate in prioritized) {
            waitIfPaused()
            if (candidate.occupiedBandwidthHz < config.minOccupiedBandwidthHz) {
                continue
            }
            var positives = 0
            var best = candidate
            repeat(config.candidateRevisitCount) {
                val window = ScanWindow(
                    rangeId = candidate.scanRangeId,
                    centerFrequencyHz = candidate.rawCenterFrequencyHz,
                    reliableStartFrequencyHz = candidate.rawCenterFrequencyHz - config.usableSpanHz / 2L,
                    reliableEndFrequencyHz = candidate.rawCenterFrequencyHz + config.usableSpanHz / 2L,
                    rfPathId = null,
                )
                val measurement = measureWindow(window, config, config.candidateMeasurementMs)
                val revisitCandidates = measurement?.let { SpectralCandidateDetector(config).detect(it, window) }.orEmpty()
                val matching = revisitCandidates.firstOrNull {
                    it.overlapsOrNear(candidate, config.mergeFrequencyToleranceHz)
                }
                if (matching != null) {
                    positives += 1
                    best = CandidateMerger.merge(listOf(best, matching), config.mergeFrequencyToleranceHz).first()
                }
            }
            if (RevisitVoting.isConfirmed(positives, config.candidateRevisitCount, config.requiredPositiveRevisits)) {
                val match = fpvChannelRepository.findNearest(best.rawCenterFrequencyHz, config.fpvMatchToleranceHz)
                val classification = classifier.classify(best.copy(detectionCount = maxOf(best.detectionCount, positives)), match)
                confirmed.add(ConfirmedSignal(best.copy(detectionCount = maxOf(best.detectionCount, positives)), match, classification))
            }
        }
        return confirmed
    }

    private fun publishConfirmed(signals: List<ConfirmedSignal>, config: MaiaScanConfig) {
        for (signal in signals) {
            updateConfirmedSignal(signal, config)
        }
        onConfirmedSignals(confirmedSignals.values.toList())
    }

    private fun updateConfirmedSignal(signal: ConfirmedSignal, config: MaiaScanConfig) {
        val match = signal.channelMatch
        val displayFrequency = if (match?.withinTolerance == true) match.nominalFrequencyHz else signal.candidate.rawCenterFrequencyHz
        val channel = KnownChannel(
            bandName = match?.bandName ?: signal.candidate.scanRangeId,
            channelName = match?.channelName ?: "Unaligned ${displayFrequency / 1_000_000} MHz",
            centerFrequencyHz = displayFrequency,
            expectedSignalType = signal.classification.type,
            rfFrontendProfileId = signal.candidate.scanRangeId,
        )
        val key = signal.candidate.rawCenterFrequencyHz
        val previous = confirmedSignals[key]
        val nowMs = System.currentTimeMillis()
        val firstSeenMs = previous?.firstSeenTimestampMs ?: signal.candidate.firstSeenNs / 1_000_000L
        val direction = estimateDirection(previous?.peakPowerDb?.toDouble() ?: previous?.rssiDbfs, signal.candidate.peakPowerDb.toDouble())
        confirmedSignals[key] = DetectedSignalRecord(
            channel = channel,
            signalType = if (signal.classification.type == SignalType.ANALOG_FPV) SignalType.ANALOG else signal.classification.type,
            rssiDbfs = signal.candidate.peakPowerDb.toDouble(),
            direction = direction,
            previewFrame = previous?.previewFrame,
            lastSeenTimestampMs = nowMs,
            confidence = signal.classification.confidence.toDouble(),
            diagnostic = buildDiagnostic(signal, config),
            imageQuality = signal.classification.confidence.toDouble(),
            measuredFrequencyHz = signal.candidate.rawCenterFrequencyHz,
            nominalFrequencyHz = match?.nominalFrequencyHz?.takeIf { match.withinTolerance },
            frequencyOffsetHz = match?.offsetHz,
            peakPowerDb = signal.candidate.peakPowerDb,
            noiseFloorDb = signal.candidate.noiseFloorDb,
            snrDb = signal.candidate.snrDb,
            occupiedBandwidthHz = signal.candidate.occupiedBandwidthHz,
            firstSeenTimestampMs = firstSeenMs,
            detectionCount = maxOf(previous?.detectionCount ?: 0, signal.candidate.detectionCount),
            status = "confirmed",
            scanRangeId = signal.candidate.scanRangeId,
        )
    }

    private fun markCandidateNotPresent(record: DetectedSignalRecord) {
        val key = record.measuredFrequencyHz ?: record.channel.centerFrequencyHz
        val diagnostic = if (record.diagnostic.contains("presence: not detected during periodic candidate-table rescan")) {
            record.diagnostic
        } else {
            record.diagnostic + "\npresence: not detected during periodic candidate-table rescan"
        }
        confirmedSignals[key] = record.copy(
            direction = DirectionEstimate.UNKNOWN,
            status = "not_present",
            diagnostic = diagnostic,
        )
    }

    private fun scanWindowForRecord(record: DetectedSignalRecord, config: MaiaScanConfig): ScanWindow {
        val centerHz = record.measuredFrequencyHz ?: record.channel.centerFrequencyHz
        return ScanWindow(
            rangeId = record.scanRangeId ?: record.channel.rfFrontendProfileId ?: record.channel.bandName,
            centerFrequencyHz = centerHz,
            reliableStartFrequencyHz = centerHz - config.usableSpanHz / 2L,
            reliableEndFrequencyHz = centerHz + config.usableSpanHz / 2L,
            rfPathId = record.channel.rfFrontendProfileId ?: record.scanRangeId,
        )
    }

    private fun candidateMatchesRecord(candidate: SpectralCandidate, record: DetectedSignalRecord, config: MaiaScanConfig): Boolean {
        val centerHz = record.measuredFrequencyHz ?: record.channel.centerFrequencyHz
        val probe = SpectralCandidate(
            rawCenterFrequencyHz = centerHz,
            peakFrequencyHz = centerHz,
            spectralCentroidHz = centerHz,
            startFrequencyHz = centerHz - config.mergeFrequencyToleranceHz,
            endFrequencyHz = centerHz + config.mergeFrequencyToleranceHz,
            occupiedBandwidthHz = record.occupiedBandwidthHz ?: 0L,
            peakPowerDb = record.peakPowerDb ?: 0.0f,
            integratedPowerDb = record.peakPowerDb ?: 0.0f,
            noiseFloorDb = record.noiseFloorDb ?: 0.0f,
            snrDb = record.snrDb ?: 0.0f,
            firstSeenNs = 0L,
            lastSeenNs = 0L,
            sourceScanCenterHz = centerHz,
            confidence = record.confidence.toFloat(),
            scanRangeId = record.scanRangeId ?: record.channel.bandName,
        )
        return candidate.overlapsOrNear(probe, config.mergeFrequencyToleranceHz)
    }

    private fun estimateDirection(previousPowerDb: Double?, currentPowerDb: Double?): DirectionEstimate {
        if (previousPowerDb == null || currentPowerDb == null) {
            return DirectionEstimate.UNKNOWN
        }
        val delta = currentPowerDb - previousPowerDb
        return when {
            abs(delta) < STABLE_RSSI_DELTA_DB -> DirectionEstimate.STABLE
            delta > 0.0 -> DirectionEstimate.APPROACHING
            else -> DirectionEstimate.RECEDING
        }
    }

    private fun buildDiagnostic(signal: ConfirmedSignal, config: MaiaScanConfig): String {
        return buildString {
            append("scanner_stage: maia_waterfall\n")
            append("raw_measured_frequency_hz: ${signal.candidate.rawCenterFrequencyHz}\n")
            append("peak_frequency_hz: ${signal.candidate.peakFrequencyHz}\n")
            append("occupied_bandwidth_hz: ${signal.candidate.occupiedBandwidthHz}\n")
            append("snr_db: ${signal.candidate.snrDb}\n")
            append("noise_floor_db: ${signal.candidate.noiseFloorDb}\n")
            append("classification: ${signal.classification.type}\n")
            append("classification_reasons: ${signal.classification.reasons.joinToString("; ")}\n")
            append("fpv_match_tolerance_hz: ${config.fpvMatchToleranceHz}\n")
            signal.channelMatch?.let {
                append("fpv_match: ${it.bandName}/${it.channelName}\n")
                append("nominal_frequency_hz: ${it.nominalFrequencyHz}\n")
                append("frequency_offset_hz: ${it.offsetHz}\n")
                append("within_tolerance: ${it.withinTolerance}\n")
            }
        }
    }

    private suspend fun waitIfPaused() {
        while (paused && currentCoroutineContext().isActive) {
            delay(25L)
        }
    }

    private fun setState(next: MaiaScannerState) {
        state = next
        onStateChanged(next)
    }

    private fun publishRuntimeStatus() {
        onRuntimeStatusChanged(runtimeStatus)
    }

    private fun rollingAverage(previousAverage: Double, previousCount: Long, next: Double): Double {
        if (previousCount <= 0L) {
            return next
        }
        return (previousAverage * previousCount.toDouble() + next) / (previousCount + 1L).toDouble()
    }

    companion object {
        private const val CANDIDATE_TABLE_RESCAN_INTERVAL_NS = 3_000_000_000L
        private const val STABLE_RSSI_DELTA_DB = 2.0
    }
}
