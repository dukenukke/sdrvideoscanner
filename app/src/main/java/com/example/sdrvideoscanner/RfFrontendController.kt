package com.example.sdrvideoscanner

import android.util.Log

enum class RfFrontendMode {
    DIRECT_PLUTO,
    EXTERNAL_FRONTEND,
}

enum class LnaState {
    ON,
    BYPASS,
}

enum class AttenuationDb(val db: Int) {
    DB_0(0),
    DB_10(10),
    DB_20(20),
    DB_30(30);

    fun increase(): AttenuationDb = values().getOrElse(ordinal + 1) { DB_30 }

    fun decrease(): AttenuationDb = values().getOrElse(ordinal - 1) { DB_0 }

    companion object {
        fun fromDb(db: Int): AttenuationDb {
            return values().minBy { kotlin.math.abs(it.db - db) }
        }
    }
}

data class RfFrontendState(
    val lnaState: LnaState,
    val attenuationDb: AttenuationDb,
    val band: String?,
    val muxChannel: String?,
)

interface RfController {
    fun setBand(band: String?): Boolean
    fun setMuxChannel(channel: String?): Boolean
    fun setLnaState(state: LnaState): Boolean
    fun setAttenuation(db: AttenuationDb): Boolean
    fun applyState(state: RfFrontendState): Boolean
    fun getCurrentState(): RfFrontendState?
}

class NoOpRfController(
    private val debugLogging: Boolean = false,
    private val logTag: String = "SDRVideoScanner.RfNoOp",
) : RfController {
    private var currentState: RfFrontendState? = null

    override fun setBand(band: String?): Boolean {
        logDebug("setBand ignored band=${band ?: "none"}")
        return true
    }

    override fun setMuxChannel(channel: String?): Boolean {
        logDebug("setMuxChannel ignored channel=${channel ?: "none"}")
        return true
    }

    override fun setLnaState(state: LnaState): Boolean {
        logDebug("setLnaState ignored state=$state")
        return true
    }

    override fun setAttenuation(db: AttenuationDb): Boolean {
        logDebug("setAttenuation ignored att=${db.db}dB")
        return true
    }

    override fun applyState(state: RfFrontendState): Boolean {
        currentState = state
        logDebug("applyState ignored state=$state")
        return true
    }

    override fun getCurrentState(): RfFrontendState? = currentState

    private fun logDebug(message: String) {
        if (debugLogging) {
            Log.d(logTag, message)
        }
    }
}

class MockRfController(
    private val logTag: String = "SDRVideoScanner.RfMock",
) : RfController {
    private var currentState = RfFrontendState(
        lnaState = LnaState.ON,
        attenuationDb = AttenuationDb.DB_0,
        band = null,
        muxChannel = null,
    )

    override fun setBand(band: String?): Boolean {
        currentState = currentState.copy(band = band)
        Log.i(logTag, "setBand band=${band ?: "none"}")
        return true
    }

    override fun setMuxChannel(channel: String?): Boolean {
        currentState = currentState.copy(muxChannel = channel)
        Log.i(logTag, "setMuxChannel channel=${channel ?: "none"}")
        return true
    }

    override fun setLnaState(state: LnaState): Boolean {
        currentState = currentState.copy(lnaState = state)
        Log.i(logTag, "setLnaState state=$state")
        return true
    }

    override fun setAttenuation(db: AttenuationDb): Boolean {
        currentState = currentState.copy(attenuationDb = db)
        Log.i(logTag, "setAttenuation att=${db.db}dB")
        return true
    }

    override fun applyState(state: RfFrontendState): Boolean {
        val ok = setBand(state.band) &&
            setMuxChannel(state.muxChannel) &&
            setLnaState(state.lnaState) &&
            setAttenuation(state.attenuationDb)
        currentState = state
        Log.i(logTag, "applyState state=$state ok=$ok")
        return ok
    }

    override fun getCurrentState(): RfFrontendState = currentState
}

class UsbSerialRp2040RfController : RfController {
    override fun setBand(band: String?): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun setMuxChannel(channel: String?): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun setLnaState(state: LnaState): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun setAttenuation(db: AttenuationDb): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun applyState(state: RfFrontendState): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun getCurrentState(): RfFrontendState? {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }
}
