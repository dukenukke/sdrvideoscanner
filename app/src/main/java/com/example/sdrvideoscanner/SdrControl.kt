package com.example.sdrvideoscanner

interface IRadioTuner {
    suspend fun configure(sampleRateHz: Long, rfBandwidthHz: Long)
    suspend fun configureForScan(config: MaiaScanConfig, initialCenterFrequencyHz: Long): MaiaRadioConfigurationResult {
        configure(config.sampleRateHz, config.rfBandwidthHz)
        tune(initialCenterFrequencyHz)
        return MaiaRadioConfigurationResult(
            requestedWaterfallFrameRateFps = config.waterfallFrameRateFps,
            actualWaterfallFrameRateFps = config.waterfallFrameRateFps,
            spectrometerStatus = "not_applicable",
        )
    }
    suspend fun tune(centerFrequencyHz: Long)
    suspend fun currentFrequencyHz(): Long?
}

interface RfPathController {
    suspend fun selectPath(pathId: String)
    suspend fun getCurrentPath(): String?
}

class NoOpRfPathController : RfPathController {
    private var currentPath: String? = null

    override suspend fun selectPath(pathId: String) {
        currentPath = pathId
    }

    override suspend fun getCurrentPath(): String? = currentPath
}

class RfControllerPathAdapter(
    private val rfController: RfController,
) : RfPathController {
    private var currentPath: String? = null

    override suspend fun selectPath(pathId: String) {
        rfController.setBand(pathId)
        rfController.setMuxChannel(pathId)
        currentPath = pathId
    }

    override suspend fun getCurrentPath(): String? = currentPath
}

class SdrModeCoordinator {
    private var currentMode: SdrOperatingMode = SdrOperatingMode.IDLE

    @Synchronized
    fun tryAcquire(mode: SdrOperatingMode): Boolean {
        if (currentMode != SdrOperatingMode.IDLE && currentMode != mode) {
            return false
        }
        currentMode = mode
        return true
    }

    @Synchronized
    fun release(mode: SdrOperatingMode) {
        if (currentMode == mode) {
            currentMode = SdrOperatingMode.IDLE
        }
    }

    @Synchronized
    fun mode(): SdrOperatingMode = currentMode
}
