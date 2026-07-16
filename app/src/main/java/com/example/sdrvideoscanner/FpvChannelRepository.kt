package com.example.sdrvideoscanner

import kotlin.math.abs

interface FpvChannelRepository {
    fun getAllChannels(): List<FpvChannel>
    fun findNearest(frequencyHz: Long, toleranceHz: Long): FpvChannelMatch?
}

class ChannelPlanFpvChannelRepository(
    private val channels: List<KnownChannel> = ChannelPlan.knownChannels,
) : FpvChannelRepository {
    private val fpvChannels = channels.map {
        FpvChannel(
            bandName = it.bandName,
            channelName = it.channelName,
            nominalFrequencyHz = it.centerFrequencyHz,
            expectedSignalType = it.expectedSignalType,
        )
    }

    override fun getAllChannels(): List<FpvChannel> = fpvChannels

    override fun findNearest(frequencyHz: Long, toleranceHz: Long): FpvChannelMatch? {
        val nearest = fpvChannels.minByOrNull { abs(it.nominalFrequencyHz - frequencyHz) }
            ?: return null
        val offsetHz = frequencyHz - nearest.nominalFrequencyHz
        return FpvChannelMatch(
            bandName = nearest.bandName,
            channelName = nearest.channelName,
            nominalFrequencyHz = nearest.nominalFrequencyHz,
            measuredFrequencyHz = frequencyHz,
            offsetHz = offsetHz,
            withinTolerance = abs(offsetHz) <= toleranceHz,
        )
    }
}
