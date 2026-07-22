package com.example.sdrvideoscanner

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

object MaiaScanPlanner {
    fun generateWindows(
        ranges: List<ScanRange>,
        config: MaiaScanConfig,
    ): List<ScanWindow> {
        require(config.frequencyStepHz > 0) { "frequencyStepHz must be positive" }
        require(config.usableSpanHz > 0) { "usableSpanHz must be positive" }
        require(config.usableSpanHz <= config.sampleRateHz) { "usableSpanHz must not exceed sampleRateHz" }

        return ranges
            .filter { it.enabled }
            .sortedWith(compareBy<ScanRange> { it.startFrequencyHz }.thenBy { it.endFrequencyHz }.thenBy { it.id })
            .flatMap { range -> generateRangeWindows(range, config) }
            .distinctBy { it.rangeId to it.centerFrequencyHz }
    }

    private fun generateRangeWindows(
        range: ScanRange,
        config: MaiaScanConfig,
    ): List<ScanWindow> {
        require(range.endFrequencyHz > range.startFrequencyHz) {
            "scan range ${range.id} must have endFrequencyHz > startFrequencyHz"
        }
        val halfSpan = config.usableSpanHz / 2L
        val width = range.endFrequencyHz - range.startFrequencyHz
        val step = minOf(config.frequencyStepHz, config.usableSpanHz)
        val windowCount = max(1, ceil(width.toDouble() / step.toDouble()).toInt())
        val windows = ArrayList<ScanWindow>(windowCount + 1)

        for (index in 0 until windowCount) {
            val reliableStart = range.startFrequencyHz + index * step
            val reliableEnd = minOf(range.endFrequencyHz, reliableStart + config.usableSpanHz)
            val center = ((reliableStart + reliableEnd) / 2.0).roundToLong()
            windows.add(
                ScanWindow(
                    rangeId = range.id,
                    centerFrequencyHz = center,
                    reliableStartFrequencyHz = max(range.startFrequencyHz, center - halfSpan),
                    reliableEndFrequencyHz = minOf(range.endFrequencyHz, center + halfSpan),
                    rfPathId = range.rfPathId,
                ),
            )
        }

        val last = windows.last()
        if (last.reliableEndFrequencyHz < range.endFrequencyHz) {
            val center = range.endFrequencyHz - halfSpan
            windows.add(
                ScanWindow(
                    rangeId = range.id,
                    centerFrequencyHz = center,
                    reliableStartFrequencyHz = max(range.startFrequencyHz, center - halfSpan),
                    reliableEndFrequencyHz = range.endFrequencyHz,
                    rfPathId = range.rfPathId,
                ),
            )
        }

        return windows
    }

    fun binFrequencyHz(frame: WaterfallFrame, binIndex: Int): Long {
        require(frame.binCount > 0) { "frame must contain at least one FFT bin" }
        require(binIndex in 0 until frame.binCount) { "binIndex out of range" }
        val binWidth = frame.sampleRateHz.toDouble() / frame.binCount.toDouble()
        val start = frame.centerFrequencyHz - frame.sampleRateHz / 2.0
        return (start + (binIndex + 0.5) * binWidth).roundToLong()
    }

    fun usableBinRange(frame: WaterfallFrame, config: MaiaScanConfig): IntRange {
        val edgeBins = edgeExclusionBins(frame.binCount, config.sampleRateHz, config.usableSpanHz)
        val start = edgeBins.coerceAtMost(frame.binCount)
        val end = (frame.binCount - edgeBins - 1).coerceAtLeast(start - 1)
        return start..end
    }

    fun edgeExclusionBins(binCount: Int, sampleRateHz: Long, usableSpanHz: Long): Int {
        require(binCount > 0) { "binCount must be positive" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(usableSpanHz in 1..sampleRateHz) { "usableSpanHz must be in 1..sampleRateHz" }
        val excludedFraction = (sampleRateHz - usableSpanHz).toDouble() / sampleRateHz.toDouble()
        return ((binCount * excludedFraction) / 2.0).roundToLong().toInt()
    }
}
