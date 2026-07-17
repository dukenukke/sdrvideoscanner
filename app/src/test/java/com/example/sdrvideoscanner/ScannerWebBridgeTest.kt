package com.example.sdrvideoscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerWebBridgeTest {
    @Test
    fun scanConfigValidationAcceptsBoundedValues() {
        val validated = ScannerWebCommandValidator.validateScanConfig(
            """
            {
              "sampleRateHz": 30720000,
              "rfBandwidthHz": 18000000,
              "frequencyStepHz": 15000000,
              "usableSpanHz": 15000000,
              "retuneTimeoutMs": 1000,
              "ranges": [
                {
                  "id": "band_5g8",
                  "startFrequencyHz": 4900000000,
                  "endFrequencyHz": 6000000000,
                  "enabled": true,
                  "rfPathId": "band_5g8",
                  "priority": 9
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(30_720_000L, validated.config.sampleRateHz)
        assertEquals(10.0, validated.config.waterfallFrameRateFps, 0.0001)
        assertEquals(1, validated.ranges?.size)
        assertEquals("band_5g8", validated.ranges?.first()?.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scanConfigValidationRejectsUnsafeValues() {
        ScannerWebCommandValidator.validateScanConfig("""{"sampleRateHz":999999999999}""")
    }

    @Test
    fun signalIdValidationRejectsBadInput() {
        assertEquals(123L, ScannerWebCommandValidator.parseSignalId("123"))
        assertNull(ScannerWebCommandValidator.parseSignalId("-1"))
        assertNull(ScannerWebCommandValidator.parseSignalId("abc"))
    }

    @Test
    fun scanConfigValidationPersistsRequestedWaterfallFps() {
        val validated = ScannerWebCommandValidator.validateScanConfig(
            """{"waterfallFrameRateFps":7,"retuneTimeoutMs":600}""",
        )

        assertEquals(7.0, validated.config.waterfallFrameRateFps, 0.0001)
    }

    @Test
    fun retuneTimeoutValidationUsesWaterfallFrameRateAndSafetyMargin() {
        val sevenFps = MaiaScanConfig(
            waterfallFrameRateFps = 7.0,
            discardedFramesAfterRetune = 2,
            loSettlingMs = 5,
            retuneTimeoutSafetyMarginMs = 75,
            retuneTimeoutMs = 600,
        )
        val tenFps = sevenFps.copy(waterfallFrameRateFps = 10.0, retuneTimeoutMs = 500)
        val twentyFps = sevenFps.copy(waterfallFrameRateFps = 20.0, retuneTimeoutMs = 250)

        assertEquals(509L, MaiaScanTiming.minimumRetuneTimeoutMs(sevenFps))
        MaiaScanTiming.validateRetuneTimeout(sevenFps)
        MaiaScanTiming.validateRetuneTimeout(tenFps)
        MaiaScanTiming.validateRetuneTimeout(twentyFps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scanConfigValidationRejectsImpossibleRetuneTimeout() {
        ScannerWebCommandValidator.validateScanConfig(
            """{"waterfallFrameRateFps":7,"discardedFramesAfterRetune":2,"loSettlingMs":5,"retuneTimeoutMs":250}""",
        )
    }

    @Test
    fun snapshotSerializationContainsVersionedSignalFields() {
        val record = DetectedSignalRecord(
            channel = KnownChannel("5.8 GHz", "R5", 5_806_000_000L, SignalType.ANALOG, "band_5g8"),
            signalType = SignalType.ANALOG,
            rssiDbfs = -42.0,
            direction = DirectionEstimate.APPROACHING,
            previewFrame = null,
            lastSeenTimestampMs = 2_000L,
            confidence = 0.92,
            diagnostic = "test",
            measuredFrequencyHz = 5_805_400_000L,
            nominalFrequencyHz = 5_806_000_000L,
            frequencyOffsetHz = -600_000L,
            peakPowerDb = -42.0f,
            noiseFloorDb = -91.0f,
            snrDb = 18.0f,
            occupiedBandwidthHz = 6_300_000L,
            firstSeenTimestampMs = 1_000L,
            detectionCount = 3,
            status = "confirmed",
            scanRangeId = "band_5g8",
        )
        val snapshot = ScannerWebSnapshot(
            scannerState = MaiaScannerState.FAST_SCAN.name,
            operatingMode = SdrOperatingMode.MAIA_SCAN.name,
            activeScanRange = "band_5g8",
            currentLoFrequencyHz = 5_805_000_000L,
            scanWindowIndex = 2,
            scanWindowTotal = 10,
            statistics = ScannerStatistics(receivedFftFrames = 42),
            noiseFloorDb = -91.0f,
            retuneLatencyMs = 8.4,
            confirmedSignals = listOf(record),
        ).toJson()

        assertEquals(1, snapshot.getInt("schemaVersion"))
        assertEquals("MAIA_SCAN", snapshot.getString("operatingMode"))
        val signal = snapshot.getJSONArray("confirmedSignals").getJSONObject(0)
        assertEquals("5805400000", signal.getString("id"))
        assertEquals(5_805_400_000L, signal.getLong("measuredFrequencyHz"))
        assertEquals(-600_000L, signal.getLong("frequencyOffsetHz"))
        assertEquals("ANALOG", signal.getString("signalType"))
    }

    @Test
    fun transitionPolicyAllowsOnlyAnalogVideoLocks() {
        val analog = DetectedSignalRecord(
            channel = KnownChannel("5.8", "R5", 5_806_000_000L, SignalType.ANALOG),
            signalType = SignalType.ANALOG,
            rssiDbfs = -42.0,
            direction = DirectionEstimate.UNKNOWN,
            previewFrame = null,
            lastSeenTimestampMs = 1L,
            confidence = 0.9,
            diagnostic = "",
        )
        val unknown = analog.copy(signalType = SignalType.WIDEBAND_UNKNOWN)

        assertTrue(ScannerModeTransitionPolicy.lockAllowed(analog))
        assertFalse(ScannerModeTransitionPolicy.lockAllowed(unknown))
        assertEquals(ScannerUiMode.SCANNING, ScannerModeTransitionPolicy.afterShowSpectrum(ScannerUiMode.VIDEO))
        assertEquals(ScannerUiMode.SCANNING, ScannerModeTransitionPolicy.afterReleaseSignal())
    }
}
