package com.example.sdrvideoscanner

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

class SpectrumAggregator(
    private val centerFrequencyHz: Long,
    private val sampleRateHz: Long,
) {
    private var sumPowerDb = FloatArray(0)
    private var maxPowerDb = FloatArray(0)
    private var previousMeanDb = FloatArray(0)
    private var stabilityAccumulator = 0.0
    private var stabilitySamples = 0
    private var startedAtNs = 0L
    private var finishedAtNs = 0L
    var frameCount: Int = 0
        private set

    fun add(frame: WaterfallFrame) {
        require(frame.centerFrequencyHz == centerFrequencyHz) { "frame center frequency changed" }
        require(frame.sampleRateHz == sampleRateHz) { "frame sample rate changed" }
        if (sumPowerDb.isEmpty()) {
            sumPowerDb = FloatArray(frame.binCount)
            maxPowerDb = FloatArray(frame.binCount) { Float.NEGATIVE_INFINITY }
            previousMeanDb = FloatArray(frame.binCount)
            startedAtNs = frame.timestampNs
        }
        require(frame.binCount == sumPowerDb.size) { "FFT bin count changed" }

        for (index in frame.powerDb.indices) {
            val value = frame.powerDb[index]
            sumPowerDb[index] += value
            if (value > maxPowerDb[index]) {
                maxPowerDb[index] = value
            }
            if (frameCount > 0) {
                val previous = previousMeanDb[index]
                stabilityAccumulator += abs(value - previous).toDouble()
                stabilitySamples += 1
            }
            previousMeanDb[index] = value
        }
        finishedAtNs = frame.timestampNs
        frameCount += 1
    }

    fun toMeasurement(): SpectrumMeasurement? {
        if (frameCount == 0 || sumPowerDb.isEmpty()) {
            return null
        }
        val mean = FloatArray(sumPowerDb.size)
        var peakIndex = 0
        var peakPower = Float.NEGATIVE_INFINITY
        for (index in mean.indices) {
            mean[index] = sumPowerDb[index] / frameCount.toFloat()
            if (maxPowerDb[index] > peakPower) {
                peakPower = maxPowerDb[index]
                peakIndex = index
            }
        }
        val binWidthHz = sampleRateHz.toDouble() / mean.size.toDouble()
        val noiseFloor = SpectrumMath.noiseFloorDb(mean)
        val active = SpectrumMath.activeBinGroups(
            powerDb = mean,
            noiseFloorDb = noiseFloor,
            thresholdDb = DEFAULT_ACTIVE_THRESHOLD_DB,
            minAdjacentBins = 1,
            excludedBins = 0..-1,
        ).flatten()
        val occupiedBandwidthHz = (active.size * binWidthHz).roundToLong()
        val centroid = SpectrumMath.spectralCentroidHz(centerFrequencyHz, sampleRateHz, mean, active.ifEmpty { mean.indices.toList() })
        val integrated = SpectrumMath.integratedPowerDb(mean, active.ifEmpty { mean.indices.toList() })
        return SpectrumMeasurement(
            centerFrequencyHz = centerFrequencyHz,
            startFrequencyHz = (centerFrequencyHz - sampleRateHz / 2.0).roundToLong(),
            endFrequencyHz = (centerFrequencyHz + sampleRateHz / 2.0).roundToLong(),
            binWidthHz = binWidthHz,
            meanPowerDb = mean,
            maxPowerDb = maxPowerDb.copyOf(),
            noiseFloorDb = noiseFloor,
            frameCount = frameCount,
            startedAtNs = startedAtNs,
            finishedAtNs = finishedAtNs,
            peakFrequencyHz = SpectrumMath.binFrequencyHz(centerFrequencyHz, sampleRateHz, mean.size, peakIndex),
            peakPowerDb = peakPower,
            integratedPowerDb = integrated,
            occupiedBandwidthHz = occupiedBandwidthHz,
            spectralCentroidHz = centroid,
            stabilityDb = if (stabilitySamples == 0) 0.0f else (stabilityAccumulator / stabilitySamples).toFloat(),
        )
    }

    companion object {
        private const val DEFAULT_ACTIVE_THRESHOLD_DB = 6.0f
    }
}

object SpectrumMath {
    fun noiseFloorDb(powerDb: FloatArray): Float {
        if (powerDb.isEmpty()) {
            return Float.NEGATIVE_INFINITY
        }
        val sorted = powerDb.copyOf()
        sorted.sort()
        val lowerCount = max(1, (sorted.size * 0.40f).toInt())
        var sum = 0.0
        for (index in 0 until lowerCount) {
            sum += sorted[index].toDouble()
        }
        return (sum / lowerCount).toFloat()
    }

    fun activeBinGroups(
        powerDb: FloatArray,
        noiseFloorDb: Float,
        thresholdDb: Float,
        minAdjacentBins: Int,
        excludedBins: IntRange,
    ): List<IntRange> {
        val groups = mutableListOf<IntRange>()
        var start: Int? = null
        for (index in powerDb.indices) {
            val active = index !in excludedBins && powerDb[index] - noiseFloorDb >= thresholdDb
            if (active && start == null) {
                start = index
            } else if (!active && start != null) {
                if (index - start >= minAdjacentBins) {
                    groups.add(start until index)
                }
                start = null
            }
        }
        val openStart = start
        if (openStart != null && powerDb.size - openStart >= minAdjacentBins) {
            groups.add(openStart until powerDb.size)
        }
        return groups
    }

    fun binFrequencyHz(centerFrequencyHz: Long, sampleRateHz: Long, binCount: Int, binIndex: Int): Long {
        val binWidth = sampleRateHz.toDouble() / binCount.toDouble()
        val start = centerFrequencyHz - sampleRateHz / 2.0
        return (start + (binIndex + 0.5) * binWidth).roundToLong()
    }

    fun spectralCentroidHz(
        centerFrequencyHz: Long,
        sampleRateHz: Long,
        powerDb: FloatArray,
        bins: List<Int>,
    ): Long {
        if (bins.isEmpty()) {
            return centerFrequencyHz
        }
        var weightedSum = 0.0
        var weightTotal = 0.0
        for (bin in bins) {
            val linear = 10.0.pow(powerDb[bin].toDouble() / 10.0)
            weightedSum += binFrequencyHz(centerFrequencyHz, sampleRateHz, powerDb.size, bin).toDouble() * linear
            weightTotal += linear
        }
        return if (weightTotal <= 0.0) centerFrequencyHz else (weightedSum / weightTotal).roundToLong()
    }

    fun integratedPowerDb(powerDb: FloatArray, bins: List<Int>): Float {
        if (bins.isEmpty()) {
            return Float.NEGATIVE_INFINITY
        }
        var linear = 0.0
        for (bin in bins) {
            linear += 10.0.pow(powerDb[bin].toDouble() / 10.0)
        }
        return (10.0 * log10(linear)).toFloat()
    }
}

class SpectralCandidateDetector(
    private val config: MaiaScanConfig,
) {
    fun detect(measurement: SpectrumMeasurement, window: ScanWindow): List<SpectralCandidate> {
        val frame = WaterfallFrame(
            centerFrequencyHz = measurement.centerFrequencyHz,
            sampleRateHz = (measurement.binWidthHz * measurement.meanPowerDb.size).roundToLong(),
            bandwidthHz = null,
            sequenceNumber = null,
            timestampNs = measurement.finishedAtNs,
            powerDb = measurement.meanPowerDb,
        )
        val usableRange = MaiaScanPlanner.usableBinRange(frame, config)
        val dcCenter = measurement.meanPowerDb.size / 2
        val dcExcluded = (dcCenter - config.dcExclusionBins)..(dcCenter + config.dcExclusionBins)
        val excluded = (0 until measurement.meanPowerDb.size).filter { it !in usableRange || it in dcExcluded }.toSet()
        val groups = SpectrumMath.activeBinGroups(
            powerDb = measurement.meanPowerDb,
            noiseFloorDb = measurement.noiseFloorDb,
            thresholdDb = config.minSnrDb,
            minAdjacentBins = config.minAdjacentBins,
            excludedBins = 0..-1,
        ).mapNotNull { group ->
            val usableBins = group.filter { it !in excluded }
            if (usableBins.size >= config.minAdjacentBins) usableBins else null
        }

        return groups.mapNotNull { bins ->
            val start = SpectrumMath.binFrequencyHz(measurement.centerFrequencyHz, frame.sampleRateHz, frame.binCount, bins.first())
            val end = SpectrumMath.binFrequencyHz(measurement.centerFrequencyHz, frame.sampleRateHz, frame.binCount, bins.last())
            val bandwidth = max(0L, end - start)
            val peakBin = bins.maxBy { measurement.maxPowerDb[it] }
            val peakPower = measurement.maxPowerDb[peakBin]
            val snr = peakPower - measurement.noiseFloorDb
            if (bandwidth !in config.minOccupiedBandwidthHz..config.maxOccupiedBandwidthHz || snr < config.minSnrDb) {
                return@mapNotNull null
            }
            val centroid = SpectrumMath.spectralCentroidHz(measurement.centerFrequencyHz, frame.sampleRateHz, measurement.meanPowerDb, bins)
            SpectralCandidate(
                rawCenterFrequencyHz = centroid,
                peakFrequencyHz = SpectrumMath.binFrequencyHz(measurement.centerFrequencyHz, frame.sampleRateHz, frame.binCount, peakBin),
                spectralCentroidHz = centroid,
                startFrequencyHz = start,
                endFrequencyHz = end,
                occupiedBandwidthHz = bandwidth,
                peakPowerDb = peakPower,
                integratedPowerDb = SpectrumMath.integratedPowerDb(measurement.meanPowerDb, bins),
                noiseFloorDb = measurement.noiseFloorDb,
                snrDb = snr,
                firstSeenNs = measurement.startedAtNs,
                lastSeenNs = measurement.finishedAtNs,
                sourceScanCenterHz = measurement.centerFrequencyHz,
                confidence = confidence(snr, bandwidth),
                scanRangeId = window.rangeId,
            )
        }
    }

    private fun confidence(snrDb: Float, bandwidthHz: Long): Float {
        val snrScore = (snrDb / 25.0f).coerceIn(0.0f, 1.0f)
        val analogWidthScore = when {
            bandwidthHz in 4_000_000L..9_000_000L -> 1.0f
            bandwidthHz < config.minOccupiedBandwidthHz || bandwidthHz > config.maxOccupiedBandwidthHz -> 0.0f
            else -> 0.65f
        }
        return (0.65f * snrScore + 0.35f * analogWidthScore).coerceIn(0.0f, 1.0f)
    }
}

object CandidateMerger {
    fun merge(candidates: List<SpectralCandidate>, toleranceHz: Long): List<SpectralCandidate> {
        val merged = mutableListOf<SpectralCandidate>()
        for (candidate in candidates.sortedBy { it.spectralCentroidHz }) {
            val index = merged.indexOfFirst { it.overlapsOrNear(candidate, toleranceHz) }
            if (index < 0) {
                merged.add(candidate)
            } else {
                merged[index] = mergePair(merged[index], candidate)
            }
        }
        return merged
    }

    private fun mergePair(a: SpectralCandidate, b: SpectralCandidate): SpectralCandidate {
        val weightA = max(1, a.detectionCount)
        val weightB = max(1, b.detectionCount)
        val total = weightA + weightB
        val centroid = ((a.spectralCentroidHz * weightA + b.spectralCentroidHz * weightB) / total.toDouble()).roundToLong()
        return a.copy(
            rawCenterFrequencyHz = centroid,
            peakFrequencyHz = if (a.peakPowerDb >= b.peakPowerDb) a.peakFrequencyHz else b.peakFrequencyHz,
            spectralCentroidHz = centroid,
            startFrequencyHz = minOf(a.startFrequencyHz, b.startFrequencyHz),
            endFrequencyHz = maxOf(a.endFrequencyHz, b.endFrequencyHz),
            occupiedBandwidthHz = maxOf(a.endFrequencyHz, b.endFrequencyHz) - minOf(a.startFrequencyHz, b.startFrequencyHz),
            peakPowerDb = maxOf(a.peakPowerDb, b.peakPowerDb),
            integratedPowerDb = maxOf(a.integratedPowerDb, b.integratedPowerDb),
            noiseFloorDb = minOf(a.noiseFloorDb, b.noiseFloorDb),
            snrDb = maxOf(a.snrDb, b.snrDb),
            firstSeenNs = minOf(a.firstSeenNs, b.firstSeenNs),
            lastSeenNs = maxOf(a.lastSeenNs, b.lastSeenNs),
            confidence = maxOf(a.confidence, b.confidence),
            detectionCount = total,
        )
    }
}

class AnalogFpvHeuristicClassifier {
    fun classify(candidate: SpectralCandidate, match: FpvChannelMatch?): SignalClassification {
        val reasons = mutableListOf<String>()
        var score = 0.0f
        if (candidate.occupiedBandwidthHz in 4_000_000L..9_000_000L) {
            score += 0.35f
            reasons.add("occupied bandwidth is analog-video-like")
        } else if (candidate.occupiedBandwidthHz < 1_000_000L) {
            return SignalClassification(SignalType.NARROWBAND, 0.8f, listOf("occupied bandwidth is narrow"))
        } else {
            score += 0.10f
            reasons.add("occupied bandwidth is wide but not typical FPV")
        }
        if (candidate.snrDb >= 12.0f) {
            score += 0.25f
            reasons.add("SNR is strong")
        }
        if (candidate.detectionCount >= 2) {
            score += 0.25f
            reasons.add("candidate repeated across measurements")
        }
        if (match?.withinTolerance == true) {
            score += 0.10f
            reasons.add("near known FPV channel")
        }
        val confidence = score.coerceIn(0.0f, 1.0f)
        val type = when {
            confidence >= 0.55f -> SignalType.ANALOG_FPV
            candidate.occupiedBandwidthHz >= 2_000_000L -> SignalType.WIDEBAND_UNKNOWN
            else -> SignalType.UNKNOWN
        }
        return SignalClassification(type, confidence, reasons.ifEmpty { listOf("insufficient waterfall features") })
    }
}

object RevisitVoting {
    fun isConfirmed(positives: Int, total: Int, requiredPositiveRevisits: Int): Boolean {
        return total > 0 && positives >= requiredPositiveRevisits
    }
}
