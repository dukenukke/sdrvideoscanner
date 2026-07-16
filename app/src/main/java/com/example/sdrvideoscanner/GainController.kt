package com.example.sdrvideoscanner

import android.util.Log
import java.util.Locale
import kotlin.math.abs

enum class GainControlMode {
    SCAN,
    ACQUISITION,
    TRACK,
}

data class GainControllerConfig(
    val rfFrontendMode: RfFrontendMode = RfFrontendMode.DIRECT_PLUTO,
    val enableExternalFrontend: Boolean? = null,
    val enableLnaBypass: Boolean = false,
    val defaultLnaState: LnaState = LnaState.ON,
    val scanPlutoGainDb: Double = 38.0,
    val scanAttenuationDb: AttenuationDb = AttenuationDb.DB_0,
    val targetPeakDbfs: Double = -10.0,
    val safePeakMinDbfs: Double = -14.0,
    val safePeakMaxDbfs: Double = -7.0,
    val hardOverloadDbfs: Double = -3.0,
    val weakPeakDbfs: Double = -28.0,
    val trackDeadbandMinDbfs: Double = -14.0,
    val trackDeadbandMaxDbfs: Double = -8.0,
    val preferredPlutoGainMinDb: Double = 25.0,
    val preferredPlutoGainMaxDb: Double = 55.0,
    val lowPlutoGainLimitDb: Double = 20.0,
    val highPlutoGainLimitDb: Double = 60.0,
    val minRfSwitchIntervalMs: Long = 500L,
    val signalLostTimeoutMs: Long = 450L,
    val rfSettleDelayMs: Long = 5L,
    val maxAcquisitionIterations: Int = 5,
    val videoCandidateConfidence: Double = 0.35,
    val stableSyncConfidence: Double = 0.45,
)

data class GainMetrics(
    val peakDbfs: Double,
    val rmsDbfs: Double,
    val noiseFloorDbfs: Double,
    val snrDb: Double,
    val videoConfidence: Double,
    val syncConfidence: Double,
)

data class GainControllerSnapshot(
    val mode: GainControlMode,
    val rfFrontendMode: RfFrontendMode,
    val manualGainControl: Boolean,
    val frequencyHz: Long?,
    val band: String?,
    val muxChannel: String?,
    val plutoGainDb: Double,
    val lnaState: LnaState?,
    val attenuationDb: AttenuationDb?,
    val peakDbfs: Double?,
    val rmsDbfs: Double?,
    val noiseFloorDbfs: Double?,
    val snrDb: Double?,
    val videoConfidence: Double?,
    val syncConfidence: Double?,
    val lastReason: String,
)

class GainController(
    private val config: GainControllerConfig,
    private val rfController: RfController,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val logTag: String = "SDRVideoScanner.Gain",
) {
    private var mode = GainControlMode.SCAN
    private var activeFrequencyHz: Long? = null
    private var activeBand: String? = null
    private var activeMuxChannel: String? = null
    private var plutoGainDb = config.scanPlutoGainDb
    private var rfState = initialRfState(null, null)
    private var lastRfSwitchMs = 0L
    private var acquisitionIterations = 0
    private var lastSignalSeenMs = clockMs()
    private var lastMetrics: GainMetrics? = null
    private var lastReason = "initialized"
    private var manualGainControl = false

    val currentPlutoGainDb: Double
        get() = plutoGainDb

    val rfFrontendMode: RfFrontendMode
        get() = effectiveRfFrontendMode()

    val isManualGainControl: Boolean
        get() = manualGainControl

    fun setManualGainControl(enabled: Boolean): GainControllerSnapshot {
        manualGainControl = enabled
        lastReason = if (enabled) "manual gain enabled" else "automatic gain enabled"
        return snapshot(lastReason)
    }

    fun adjustPlutoGain(deltaDb: Double): GainControllerSnapshot {
        val beforeGain = plutoGainDb
        plutoGainDb = clampPlutoGain(plutoGainDb + deltaDb)
        manualGainControl = true
        logDecision(
            metrics = lastMetrics,
            plutoBefore = beforeGain,
            plutoAfter = plutoGainDb,
            rfBefore = rfState,
            rfAfter = rfState,
            reason = "manual Pluto gain ${if (deltaDb >= 0.0) "+" else ""}${deltaDb.fmt()} dB",
        )
        return snapshot(lastReason)
    }

    fun prepareScanChannel(channel: KnownChannel): GainControllerSnapshot {
        mode = GainControlMode.SCAN
        acquisitionIterations = 0
        activeFrequencyHz = channel.centerFrequencyHz
        activeBand = channel.rfFrontendProfileId ?: channel.bandName
        activeMuxChannel = channel.channelName
        if (!manualGainControl) {
            plutoGainDb = config.scanPlutoGainDb
        }

        val nextRfState = initialRfState(activeBand, activeMuxChannel)
        val beforeRf = rfState
        val beforeGain = plutoGainDb
        applyRfStateIfExternal(nextRfState, "scan channel setup")
        logDecision(
            metrics = lastMetrics,
            plutoBefore = beforeGain,
            plutoAfter = plutoGainDb,
            rfBefore = beforeRf,
            rfAfter = rfState,
            reason = "scan channel setup",
        )
        return snapshot("scan channel setup")
    }

    fun tuneChannel(channel: KnownChannel, reason: String = "channel tune"): GainControllerSnapshot {
        activeFrequencyHz = channel.centerFrequencyHz
        activeBand = channel.rfFrontendProfileId ?: channel.bandName
        activeMuxChannel = channel.channelName
        val beforeGain = plutoGainDb
        val beforeRf = rfState
        val nextRfState = rfState.copy(
            band = activeBand,
            muxChannel = activeMuxChannel,
        )
        applyRfStateIfExternal(nextRfState, reason)
        logDecision(lastMetrics, beforeGain, plutoGainDb, beforeRf, rfState, reason)
        return snapshot(reason)
    }

    fun update(metrics: GainMetrics): GainControllerSnapshot {
        lastMetrics = metrics
        if (metrics.videoConfidence >= config.videoCandidateConfidence || metrics.syncConfidence >= config.stableSyncConfidence) {
            lastSignalSeenMs = clockMs()
        }
        if (manualGainControl) {
            return logAndSnapshot(metrics, plutoGainDb, plutoGainDb, rfState, rfState, "manual gain hold")
        }

        return when (mode) {
            GainControlMode.SCAN -> handleScanMetrics(metrics)
            GainControlMode.ACQUISITION -> handleAcquisitionMetrics(metrics)
            GainControlMode.TRACK -> handleTrackMetrics(metrics)
        }
    }

    fun snapshot(reason: String = lastReason): GainControllerSnapshot {
        val metrics = lastMetrics
        val external = effectiveRfFrontendMode() == RfFrontendMode.EXTERNAL_FRONTEND
        return GainControllerSnapshot(
            mode = mode,
            rfFrontendMode = effectiveRfFrontendMode(),
            manualGainControl = manualGainControl,
            frequencyHz = activeFrequencyHz,
            band = if (external) rfState.band else null,
            muxChannel = if (external) rfState.muxChannel else null,
            plutoGainDb = plutoGainDb,
            lnaState = if (external) rfState.lnaState else null,
            attenuationDb = if (external) rfState.attenuationDb else null,
            peakDbfs = metrics?.peakDbfs?.takeIf { it.isFinite() },
            rmsDbfs = metrics?.rmsDbfs?.takeIf { it.isFinite() },
            noiseFloorDbfs = metrics?.noiseFloorDbfs?.takeIf { it.isFinite() },
            snrDb = metrics?.snrDb?.takeIf { it.isFinite() },
            videoConfidence = metrics?.videoConfidence,
            syncConfidence = metrics?.syncConfidence,
            lastReason = reason,
        )
    }

    private fun handleScanMetrics(metrics: GainMetrics): GainControllerSnapshot {
        if (metrics.videoConfidence < config.videoCandidateConfidence) {
            return logAndSnapshot(metrics, plutoGainDb, plutoGainDb, rfState, rfState, "scan no candidate")
        }
        mode = GainControlMode.ACQUISITION
        acquisitionIterations = 0
        return handleAcquisitionMetrics(metrics)
    }

    private fun handleAcquisitionMetrics(metrics: GainMetrics): GainControllerSnapshot {
        acquisitionIterations += 1
        val beforeGain = plutoGainDb
        val beforeRf = rfState
        var reason = "acquisition stable"

        if (effectiveRfFrontendMode() == RfFrontendMode.DIRECT_PLUTO) {
            when {
                metrics.peakDbfs > config.hardOverloadDbfs -> {
                    plutoGainDb = clampPlutoGain(plutoGainDb - 8.0)
                    reason = "direct hard overload reduce Pluto gain"
                }
                metrics.peakDbfs > config.safePeakMaxDbfs -> {
                    plutoGainDb = clampPlutoGain(plutoGainDb - 6.0)
                    reason = "direct peak high reduce Pluto gain"
                }
                metrics.peakDbfs < config.weakPeakDbfs -> {
                    plutoGainDb = clampPlutoGain(plutoGainDb + 6.0)
                    reason = "direct weak signal increase Pluto gain"
                }
                metrics.peakDbfs in config.safePeakMinDbfs..config.safePeakMaxDbfs -> {
                    fineTunePlutoGain(metrics.peakDbfs)
                    mode = GainControlMode.TRACK
                    reason = "direct safe window enter track"
                }
            }
        } else {
            when {
                metrics.peakDbfs > config.hardOverloadDbfs -> {
                    reason = if (rfState.attenuationDb != AttenuationDb.DB_30) {
                        setAttenuation(rfState.attenuationDb.increase(), "hard overload")
                        "hard overload increase ATT"
                    } else if (config.enableLnaBypass && rfState.lnaState != LnaState.BYPASS) {
                        setLnaState(LnaState.BYPASS, "hard overload")
                        "hard overload bypass LNA"
                    } else {
                        plutoGainDb = clampPlutoGain(plutoGainDb - 8.0)
                        "hard overload reduce Pluto gain"
                    }
                }
                metrics.peakDbfs > config.safePeakMaxDbfs -> {
                    if (rfState.attenuationDb != AttenuationDb.DB_30) {
                        setAttenuation(rfState.attenuationDb.increase(), "peak high")
                        reason = "peak high increase ATT"
                    } else {
                        plutoGainDb = clampPlutoGain(plutoGainDb - 6.0)
                        reason = "peak high reduce Pluto gain"
                    }
                }
                metrics.peakDbfs < config.weakPeakDbfs -> {
                    if (rfState.attenuationDb != AttenuationDb.DB_0) {
                        setAttenuation(rfState.attenuationDb.decrease(), "weak signal")
                        reason = "weak signal reduce ATT"
                    } else {
                        plutoGainDb = clampPlutoGain(plutoGainDb + 6.0)
                        reason = "weak signal increase Pluto gain"
                    }
                }
                metrics.peakDbfs in config.safePeakMinDbfs..config.safePeakMaxDbfs -> {
                    fineTunePlutoGain(metrics.peakDbfs)
                    mode = GainControlMode.TRACK
                    reason = "safe window enter track"
                }
            }
        }

        if (mode != GainControlMode.TRACK && acquisitionIterations >= config.maxAcquisitionIterations) {
            mode = if (metrics.videoConfidence >= config.videoCandidateConfidence) {
                GainControlMode.TRACK
            } else {
                GainControlMode.SCAN
            }
            reason += if (mode == GainControlMode.TRACK) {
                "; max iterations but confidence sufficient"
            } else {
                "; max iterations low confidence return scan"
            }
        }

        return logAndSnapshot(metrics, beforeGain, plutoGainDb, beforeRf, rfState, reason)
    }

    private fun handleTrackMetrics(metrics: GainMetrics): GainControllerSnapshot {
        val beforeGain = plutoGainDb
        val beforeRf = rfState
        var reason = "track hold"

        if (clockMs() - lastSignalSeenMs > config.signalLostTimeoutMs) {
            mode = GainControlMode.SCAN
            if (!manualGainControl) {
                plutoGainDb = config.scanPlutoGainDb
            }
            reason = "signal lost return scan"
            return logAndSnapshot(metrics, beforeGain, plutoGainDb, beforeRf, rfState, reason)
        }

        val insideDeadband = metrics.peakDbfs in config.trackDeadbandMinDbfs..config.trackDeadbandMaxDbfs
        if (insideDeadband && metrics.syncConfidence >= config.stableSyncConfidence) {
            return logAndSnapshot(metrics, beforeGain, plutoGainDb, beforeRf, rfState, "track deadband stable")
        }

        when {
            metrics.peakDbfs > -6.0 -> {
                plutoGainDb = clampPlutoGain(plutoGainDb - 4.0)
                reason = "track peak high reduce Pluto gain"
                if (effectiveRfFrontendMode() == RfFrontendMode.EXTERNAL_FRONTEND &&
                    plutoGainDb < config.lowPlutoGainLimitDb &&
                    rfState.attenuationDb != AttenuationDb.DB_30 &&
                    canSwitchRf()
                ) {
                    setAttenuation(rfState.attenuationDb.increase(), "track peak high")
                    plutoGainDb = clampPlutoGain(plutoGainDb + 8.0)
                    reason = "track peak high increase ATT and compensate Pluto gain"
                }
            }
            metrics.peakDbfs < -22.0 -> {
                plutoGainDb = clampPlutoGain(plutoGainDb + 4.0)
                reason = "track peak weak increase Pluto gain"
                if (effectiveRfFrontendMode() == RfFrontendMode.EXTERNAL_FRONTEND &&
                    plutoGainDb > config.highPlutoGainLimitDb &&
                    rfState.attenuationDb != AttenuationDb.DB_0 &&
                    canSwitchRf()
                ) {
                    setAttenuation(rfState.attenuationDb.decrease(), "track weak signal")
                    plutoGainDb = clampPlutoGain(plutoGainDb - 8.0)
                    reason = "track weak reduce ATT and compensate Pluto gain"
                }
            }
            !insideDeadband -> {
                fineTunePlutoGain(metrics.peakDbfs, maxStepDb = 2.0)
                reason = "track fine tune Pluto gain"
            }
        }

        if (metrics.syncConfidence < config.stableSyncConfidence && abs(metrics.peakDbfs - config.targetPeakDbfs) <= 2.0) {
            plutoGainDb = clampPlutoGain(plutoGainDb + if (metrics.peakDbfs < config.targetPeakDbfs) 1.0 else -1.0)
            reason += "; sync confidence search"
        }

        return logAndSnapshot(metrics, beforeGain, plutoGainDb, beforeRf, rfState, reason)
    }

    private fun initialRfState(band: String?, muxChannel: String?): RfFrontendState {
        val lna = if (config.enableLnaBypass) config.defaultLnaState else LnaState.ON
        return RfFrontendState(
            lnaState = lna,
            attenuationDb = config.scanAttenuationDb,
            band = band,
            muxChannel = muxChannel,
        )
    }

    private fun applyRfStateIfExternal(state: RfFrontendState, reason: String) {
        rfState = state.copy(lnaState = allowedLnaState(state.lnaState))
        if (effectiveRfFrontendMode() != RfFrontendMode.EXTERNAL_FRONTEND) {
            return
        }
        rfController.applyState(rfState)
        markRfSwitched(reason)
    }

    private fun setAttenuation(attenuationDb: AttenuationDb, reason: String) {
        if (effectiveRfFrontendMode() != RfFrontendMode.EXTERNAL_FRONTEND || !canSwitchRf()) {
            return
        }
        rfState = rfState.copy(attenuationDb = attenuationDb)
        rfController.setAttenuation(attenuationDb)
        markRfSwitched(reason)
    }

    private fun setLnaState(lnaState: LnaState, reason: String) {
        if (effectiveRfFrontendMode() != RfFrontendMode.EXTERNAL_FRONTEND || !canSwitchRf()) {
            return
        }
        val allowed = allowedLnaState(lnaState)
        rfState = rfState.copy(lnaState = allowed)
        rfController.setLnaState(allowed)
        markRfSwitched(reason)
    }

    private fun markRfSwitched(reason: String) {
        lastRfSwitchMs = clockMs()
        if (config.rfSettleDelayMs > 0L) {
            Thread.sleep(config.rfSettleDelayMs)
        }
        Log.d(logTag, "rf_settle_ms=${config.rfSettleDelayMs} reason=$reason")
    }

    private fun fineTunePlutoGain(peakDbfs: Double, maxStepDb: Double = 3.0) {
        val delta = (config.targetPeakDbfs - peakDbfs).coerceIn(-maxStepDb, maxStepDb)
        plutoGainDb = clampPlutoGain(plutoGainDb + delta)
    }

    private fun clampPlutoGain(value: Double): Double {
        return value.coerceIn(0.0, 73.0)
    }

    private fun canSwitchRf(): Boolean {
        return clockMs() - lastRfSwitchMs >= config.minRfSwitchIntervalMs
    }

    private fun allowedLnaState(state: LnaState): LnaState {
        return if (state == LnaState.BYPASS && !config.enableLnaBypass) LnaState.ON else state
    }

    private fun effectiveRfFrontendMode(): RfFrontendMode {
        config.enableExternalFrontend?.let { enabled ->
            return if (enabled) RfFrontendMode.EXTERNAL_FRONTEND else RfFrontendMode.DIRECT_PLUTO
        }
        return config.rfFrontendMode
    }

    private fun logAndSnapshot(
        metrics: GainMetrics,
        plutoBefore: Double,
        plutoAfter: Double,
        rfBefore: RfFrontendState,
        rfAfter: RfFrontendState,
        reason: String,
    ): GainControllerSnapshot {
        logDecision(metrics, plutoBefore, plutoAfter, rfBefore, rfAfter, reason)
        return snapshot(reason)
    }

    private fun logDecision(
        metrics: GainMetrics?,
        plutoBefore: Double,
        plutoAfter: Double,
        rfBefore: RfFrontendState,
        rfAfter: RfFrontendState,
        reason: String,
    ) {
        lastReason = reason
        Log.i(
            logTag,
            buildString {
                append("mode=$mode")
                append(" frequency=$activeFrequencyHz")
                append(" band=${activeBand ?: "none"}")
                append(" peak_dbfs=${metrics?.peakDbfs?.fmt() ?: "n/a"}")
                append(" rms_dbfs=${metrics?.rmsDbfs?.fmt() ?: "n/a"}")
                append(" snr_db=${metrics?.snrDb?.fmt() ?: "n/a"}")
                append(" video_confidence=${metrics?.videoConfidence?.fmt() ?: "n/a"}")
                append(" sync_confidence=${metrics?.syncConfidence?.fmt() ?: "n/a"}")
                append(" pluto_gain_before=${plutoBefore.fmt()}")
                append(" pluto_gain_after=${plutoAfter.fmt()}")
                append(" att_before=${rfBefore.attenuationDb.db}")
                append(" att_after=${rfAfter.attenuationDb.db}")
                append(" lna_before=${rfBefore.lnaState}")
                append(" lna_after=${rfAfter.lnaState}")
                append(" reason=\"$reason\"")
            },
        )
    }

    private fun Double.fmt(): String {
        return if (isFinite()) String.format(Locale.US, "%.2f", this) else "n/a"
    }
}
