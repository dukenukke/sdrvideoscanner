package com.example.sdrvideoscanner

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiaScannerCoreTest {
    @Test
    fun scanWindowsCoverFullRangeWithoutGaps() {
        val config = MaiaScanConfig(frequencyStepHz = 15_000_000L, usableSpanHz = 15_000_000L)
        val range = ScanRange("test", 100_000_000L, 134_000_000L, enabled = true, rfPathId = null)

        val windows = MaiaScanPlanner.generateWindows(listOf(range), config)

        assertEquals(range.startFrequencyHz, windows.first().reliableStartFrequencyHz)
        assertEquals(range.endFrequencyHz, windows.last().reliableEndFrequencyHz)
        windows.zipWithNext().forEach { (left, right) ->
            assertTrue(left.reliableEndFrequencyHz >= right.reliableStartFrequencyHz)
        }
    }

    @Test
    fun fftBinToFrequencyUsesBinCenter() {
        val frame = WaterfallFrame(
            centerFrequencyHz = 100_000_000L,
            sampleRateHz = 40_000_000L,
            bandwidthHz = null,
            sequenceNumber = 1,
            timestampNs = 1,
            powerDb = FloatArray(4),
        )

        assertEquals(85_000_000L, MaiaScanPlanner.binFrequencyHz(frame, 0))
        assertEquals(115_000_000L, MaiaScanPlanner.binFrequencyHz(frame, 3))
    }

    @Test
    fun edgeExclusionDerivesFromUsableSpan() {
        val excluded = MaiaScanPlanner.edgeExclusionBins(
            binCount = 4096,
            sampleRateHz = 30_720_000L,
            usableSpanHz = 15_000_000L,
        )

        assertTrue(excluded > 0)
        assertTrue(excluded < 2048)
    }

    @Test
    fun noiseFloorIgnoresStrongUpperTail() {
        val spectrum = FloatArray(100) { if (it < 90) 10.0f else 80.0f }

        val noise = SpectrumMath.noiseFloorDb(spectrum)

        assertEquals(10.0f, noise, 0.001f)
    }

    @Test
    fun activeBinsAreGroupedAndRespectMinimumWidth() {
        val spectrum = floatArrayOf(0f, 0f, 10f, 11f, 12f, 0f, 9f, 0f)

        val groups = SpectrumMath.activeBinGroups(
            powerDb = spectrum,
            noiseFloorDb = 0f,
            thresholdDb = 8f,
            minAdjacentBins = 2,
            excludedBins = 0..-1,
        )

        assertEquals(listOf(2..4), groups)
    }

    @Test
    fun occupiedWidebandCandidateIsDetectedFromSpectrumEnergy() {
        val config = MaiaScanConfig(
            sampleRateHz = 32_000_000L,
            usableSpanHz = 15_000_000L,
            minSnrDb = 8.0f,
            minOccupiedBandwidthHz = 2_000_000L,
            maxOccupiedBandwidthHz = 12_000_000L,
            minAdjacentBins = 3,
        )
        val frame = WaterfallFrame(
            centerFrequencyHz = 5_800_000_000L,
            sampleRateHz = 32_000_000L,
            bandwidthHz = 18_000_000L,
            sequenceNumber = 1,
            timestampNs = 1_000,
            powerDb = FloatArray(64) { index -> if (index in 28..39) 30.0f else 0.0f },
        )
        val aggregator = SpectrumAggregator(frame.centerFrequencyHz, frame.sampleRateHz)
        aggregator.add(frame)
        val measurement = requireNotNull(aggregator.toMeasurement())
        val window = ScanWindow("test", frame.centerFrequencyHz, 5_792_500_000L, 5_807_500_000L, null)

        val candidates = SpectralCandidateDetector(config).detect(measurement, window)

        assertEquals(1, candidates.size)
        assertTrue(candidates.first().occupiedBandwidthHz >= 2_000_000L)
    }

    @Test
    fun candidatesMergeAcrossOverlappingWindows() {
        val a = candidate(5_800_000_000L, 5_797_000_000L, 5_803_000_000L)
        val b = candidate(5_801_000_000L, 5_798_000_000L, 5_804_000_000L)

        val merged = CandidateMerger.merge(listOf(a, b), toleranceHz = 1_000_000L)

        assertEquals(1, merged.size)
        assertEquals(2, merged.first().detectionCount)
    }

    @Test
    fun staleFramesAreRejectedAfterRetune() {
        val retuneNs = 1_000L
        val staleTime = frame(centerHz = 200_000_000L, timestampNs = 999L)
        val staleFrequency = frame(centerHz = 199_000_000L, timestampNs = 1_001L)
        val valid = frame(centerHz = 200_000_000L, timestampNs = 1_001L)

        assertFalse(WaterfallFrameValidator.isValidForRetune(staleTime, 200_000_000L, retuneNs))
        assertFalse(WaterfallFrameValidator.isValidForRetune(staleFrequency, 200_000_000L, retuneNs))
        assertTrue(WaterfallFrameValidator.isValidForRetune(valid, 200_000_000L, retuneNs))
    }

    @Test
    fun revisitVotingRequiresConfiguredPositiveCount() {
        assertTrue(RevisitVoting.isConfirmed(2, 3, 2))
        assertFalse(RevisitVoting.isConfirmed(1, 3, 2))
    }

    @Test
    fun nearestFpvChannelHonorsToleranceAndPreservesRawFrequency() {
        val repo = ChannelPlanFpvChannelRepository(
            listOf(KnownChannel("5.8", "A1", 5_865_000_000L, SignalType.ANALOG, "band_5g8")),
        )

        val matched = requireNotNull(repo.findNearest(5_866_000_000L, toleranceHz = 2_000_000L))
        val unaligned = requireNotNull(repo.findNearest(5_870_000_001L, toleranceHz = 2_000_000L))

        assertTrue(matched.withinTolerance)
        assertEquals(5_866_000_000L, matched.measuredFrequencyHz)
        assertFalse(unaligned.withinTolerance)
        assertEquals(5_870_000_001L, unaligned.measuredFrequencyHz)
    }

    @Test
    fun scannerStopDisconnectsFakeSource() = runBlocking {
        val source = FakeWaterfallSource()
        val controller = MaiaWaterfallScanController(
            source = source,
            tuner = FakeTuner(),
            clockNs = { System.nanoTime() },
        )

        controller.start(
            MaiaScanConfig(retuneTimeoutMs = 20L, loSettlingMs = 1L, discardedFramesAfterRetune = 0),
            listOf(ScanRange("test", 100_000_000L, 110_000_000L, enabled = true, rfPathId = null)),
        )
        delay(20L)
        controller.stop()
        delay(20L)

        assertFalse(source.connected)
    }

    private fun frame(centerHz: Long, timestampNs: Long): WaterfallFrame {
        return WaterfallFrame(
            centerFrequencyHz = centerHz,
            sampleRateHz = 10_000_000L,
            bandwidthHz = null,
            sequenceNumber = null,
            timestampNs = timestampNs,
            powerDb = floatArrayOf(0f, 1f, 2f),
        )
    }

    private fun candidate(centerHz: Long, startHz: Long, endHz: Long): SpectralCandidate {
        return SpectralCandidate(
            rawCenterFrequencyHz = centerHz,
            peakFrequencyHz = centerHz,
            spectralCentroidHz = centerHz,
            startFrequencyHz = startHz,
            endFrequencyHz = endHz,
            occupiedBandwidthHz = endHz - startHz,
            peakPowerDb = 30f,
            integratedPowerDb = 40f,
            noiseFloorDb = 0f,
            snrDb = 30f,
            firstSeenNs = 1,
            lastSeenNs = 2,
            sourceScanCenterHz = centerHz,
            confidence = 0.8f,
            scanRangeId = "test",
        )
    }

    private class FakeTuner : IRadioTuner {
        private var frequencyHz: Long? = null

        override suspend fun configure(sampleRateHz: Long, rfBandwidthHz: Long) = Unit

        override suspend fun tune(centerFrequencyHz: Long) {
            frequencyHz = centerFrequencyHz
        }

        override suspend fun currentFrequencyHz(): Long? = frequencyHz
    }
}
