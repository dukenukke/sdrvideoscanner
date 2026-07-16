package com.example.sdrvideoscanner

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface WaterfallSource {
    suspend fun connect()
    suspend fun disconnect()
    fun frames(): Flow<WaterfallFrame>
}

interface WaterfallFrameParser {
    fun parse(payload: ByteArray, metadata: WaterfallFrameMetadata): WaterfallFrame?
}

data class WaterfallFrameMetadata(
    val centerFrequencyHz: Long,
    val sampleRateHz: Long,
    val bandwidthHz: Long?,
    val sequenceNumber: Long?,
    val receiveTimestampNs: Long,
)

class MaiaFloat32WaterfallFrameParser : WaterfallFrameParser {
    override fun parse(payload: ByteArray, metadata: WaterfallFrameMetadata): WaterfallFrame? {
        if (payload.isEmpty() || payload.size % FLOAT_BYTES != 0) {
            return null
        }
        val bins = payload.size / FLOAT_BYTES
        if (bins <= 0 || bins > MAX_REASONABLE_FFT_BINS) {
            return null
        }
        val powerDb = FloatArray(bins)
        var offset = 0
        for (index in 0 until bins) {
            val bits = (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                ((payload[offset + 2].toInt() and 0xFF) shl 16) or
                ((payload[offset + 3].toInt() and 0xFF) shl 24)
            val linear = Float.fromBits(bits)
            if (!linear.isFinite() || linear < 0.0f) {
                return null
            }
            powerDb[index] = if (linear <= 0.0f) MIN_POWER_DB else (10.0 * kotlin.math.log10(linear.toDouble())).toFloat()
            offset += FLOAT_BYTES
        }
        return WaterfallFrame(
            centerFrequencyHz = metadata.centerFrequencyHz,
            sampleRateHz = metadata.sampleRateHz,
            bandwidthHz = metadata.bandwidthHz,
            sequenceNumber = metadata.sequenceNumber,
            timestampNs = metadata.receiveTimestampNs,
            powerDb = powerDb,
        )
    }

    companion object {
        private const val FLOAT_BYTES = 4
        private const val MAX_REASONABLE_FFT_BINS = 1 shl 20
        private const val MIN_POWER_DB = -200.0f
    }
}

class FakeWaterfallSource : WaterfallSource {
    private val frames = MutableSharedFlow<WaterfallFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    var connected: Boolean = false
        private set

    override suspend fun connect() {
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override fun frames(): Flow<WaterfallFrame> = frames.asSharedFlow()

    fun tryEmit(frame: WaterfallFrame): Boolean = frames.tryEmit(frame)
}

object WaterfallFrameValidator {
    fun isValidForRetune(frame: WaterfallFrame, centerFrequencyHz: Long, retuneStartNs: Long): Boolean {
        return frame.centerFrequencyHz == centerFrequencyHz &&
            frame.timestampNs >= retuneStartNs &&
            frame.binCount > 0 &&
            frame.sampleRateHz > 0
    }
}
