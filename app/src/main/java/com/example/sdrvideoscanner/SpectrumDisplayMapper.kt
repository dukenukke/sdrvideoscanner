package com.example.sdrvideoscanner

data class DisplaySpectrumFrame(
    val centerFrequencyHz: Long,
    val displayedStartFrequencyHz: Long,
    val displayedEndFrequencyHz: Long,
    val sampleRateHz: Long,
    val usableSpanHz: Long,
    val sourceBinCount: Int,
    val displayedBinCount: Int,
    val firstDisplayedBin: Int,
    val lastDisplayedBin: Int,
    val powerDb: FloatArray,
)

object SpectrumDisplayMapper {
    fun toDisplayFrame(
        frame: WaterfallFrame,
        config: MaiaScanConfig,
        maxBins: Int,
    ): DisplaySpectrumFrame {
        require(maxBins > 0) { "maxBins must be positive" }
        val usableRange = MaiaScanPlanner.usableBinRange(frame, config)
        val cropped = crop(frame.powerDb, usableRange)
        val downsampled = downsampleSpectrum(cropped, maxBins)
        val halfUsableSpan = config.usableSpanHz / 2L
        return DisplaySpectrumFrame(
            centerFrequencyHz = frame.centerFrequencyHz,
            displayedStartFrequencyHz = frame.centerFrequencyHz - halfUsableSpan,
            displayedEndFrequencyHz = frame.centerFrequencyHz + halfUsableSpan,
            sampleRateHz = frame.sampleRateHz,
            usableSpanHz = config.usableSpanHz,
            sourceBinCount = frame.binCount,
            displayedBinCount = cropped.size,
            firstDisplayedBin = usableRange.first,
            lastDisplayedBin = usableRange.last,
            powerDb = downsampled,
        )
    }

    private fun crop(values: FloatArray, range: IntRange): FloatArray {
        if (range.isEmpty()) {
            return FloatArray(0)
        }
        val start = range.first.coerceIn(values.indices)
        val end = range.last.coerceIn(values.indices)
        if (end < start) {
            return FloatArray(0)
        }
        return values.copyOfRange(start, end + 1)
    }

    private fun downsampleSpectrum(values: FloatArray, maxBins: Int): FloatArray {
        if (values.size <= maxBins) {
            return values.copyOf()
        }
        val result = FloatArray(maxBins)
        for (index in result.indices) {
            val start = (index * values.size) / maxBins
            val end = (((index + 1) * values.size) / maxBins).coerceAtLeast(start + 1)
            var max = Float.NEGATIVE_INFINITY
            for (sourceIndex in start until end) {
                if (values[sourceIndex] > max) {
                    max = values[sourceIndex]
                }
            }
            result[index] = max
        }
        return result
    }
}
