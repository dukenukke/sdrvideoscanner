package com.example.sdrvideoscanner

import android.util.Log

interface IRfFrontendController {
    fun selectBandProfile(profileId: String?): Boolean
    fun selectAntennaPath(profileId: String?): Boolean
    fun selectBpfPath(profileId: String?): Boolean
    fun setLnaEnabled(enabled: Boolean): Boolean
    fun selectMixerProfile(profileId: String?): Boolean
}

class DummyRfFrontendController(
    private val logTag: String = "SDRVideoScanner.RfFrontend",
) : IRfFrontendController {
    override fun selectBandProfile(profileId: String?): Boolean {
        Log.i(logTag, "selectBandProfile profile=${profileId ?: "none"}")
        return true
    }

    override fun selectAntennaPath(profileId: String?): Boolean {
        Log.i(logTag, "selectAntennaPath profile=${profileId ?: "none"}")
        return true
    }

    override fun selectBpfPath(profileId: String?): Boolean {
        Log.i(logTag, "selectBpfPath profile=${profileId ?: "none"}")
        return true
    }

    override fun setLnaEnabled(enabled: Boolean): Boolean {
        Log.i(logTag, "setLnaEnabled enabled=$enabled")
        return true
    }

    override fun selectMixerProfile(profileId: String?): Boolean {
        Log.i(logTag, "selectMixerProfile profile=${profileId ?: "none"}")
        return true
    }
}

class Rp2040SerialRfFrontendController : IRfFrontendController {
    override fun selectBandProfile(profileId: String?): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun selectAntennaPath(profileId: String?): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun selectBpfPath(profileId: String?): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun setLnaEnabled(enabled: Boolean): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }

    override fun selectMixerProfile(profileId: String?): Boolean {
        TODO("RP2040 USB-serial RF frontend control is not implemented yet")
    }
}
