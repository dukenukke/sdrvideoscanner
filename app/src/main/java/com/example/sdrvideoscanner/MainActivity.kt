package com.example.sdrvideoscanner

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.sdrvideoscanner.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var playbackRunning = false
    @Volatile
    private var playbackSessionHandle = 0L
    private var playbackFrameIndex = 0L
    private var reusableFramePixels = IntArray(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val defaultIqPath = File(getExternalFilesDir(null), "input.cs16").absolutePath
        val defaultMetadataPath = metadataFileFor(defaultIqPath).absolutePath
        binding.sampleText.text = "Place input.cs16 at:\n$defaultIqPath\n\nOptional metadata:\n$defaultMetadataPath"
        binding.diagnoseButton.setOnClickListener {
            val metadata = loadIqMetadata(defaultIqPath)
            binding.sampleText.text = metadata.toDiagnosticText() + "\n\n" + diagnoseCs16FileBlocks(defaultIqPath)
        }
        binding.spectrumButton.setOnClickListener {
            val metadata = loadIqMetadata(defaultIqPath)
            binding.sampleText.text = diagnoseCs16SpectrumWithMetadata(defaultIqPath, metadata)
        }
        binding.decodeFrameButton.setOnClickListener {
            stopPlayback()
            val metadata = loadIqMetadata(defaultIqPath)
            decodeAndDisplayFrame(defaultIqPath, metadata, VideoStandard.AUTO, frameIndex = 0L)
        }
        binding.decodePalButton.setOnClickListener {
            stopPlayback()
            val metadata = loadIqMetadata(defaultIqPath)
            decodeAndDisplayFrame(defaultIqPath, metadata, VideoStandard.PAL625_25FPS, frameIndex = 0L)
        }
        binding.decodeNtscButton.setOnClickListener {
            stopPlayback()
            val metadata = loadIqMetadata(defaultIqPath)
            decodeAndDisplayFrame(defaultIqPath, metadata, VideoStandard.NTSC_525_30FPS, frameIndex = 0L)
        }
        binding.playAutoButton.setOnClickListener {
            val metadata = loadIqMetadata(defaultIqPath)
            startPlayback(defaultIqPath, metadata, VideoStandard.AUTO)
        }
        binding.playNtscButton.setOnClickListener {
            val metadata = loadIqMetadata(defaultIqPath)
            startPlayback(defaultIqPath, metadata, VideoStandard.NTSC_525_30FPS)
        }
        binding.stopPlaybackButton.setOnClickListener {
            stopPlayback()
        }
    }

    override fun onDestroy() {
        stopPlayback()
        decodeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun metadataFileFor(iqPath: String): File {
        val iqFile = File(iqPath)
        val dotIndex = iqFile.name.lastIndexOf('.')
        val stem = if (dotIndex > 0) iqFile.name.substring(0, dotIndex) else iqFile.name
        val metadataName = "$stem.json"
        val parent = iqFile.parentFile
        return if (parent != null) File(parent, metadataName) else File(metadataName)
    }

    private fun loadIqMetadata(iqPath: String): IQMetadata {
        val metadataFile = metadataFileFor(iqPath)
        if (!metadataFile.exists()) {
            return IQMetadata(sidecarPath = metadataFile.absolutePath)
        }

        return runCatching {
            val json = JSONObject(metadataFile.readText())
            IQMetadata(
                sidecarPath = metadataFile.absolutePath,
                sidecarFound = true,
                format = json.optStringOrNull("format"),
                endianness = json.optStringOrNull("endianness"),
                sampleRateHz = json.optLongOrNull("sample_rate_hz"),
                centerFrequencyHz = json.optLongOrNull("center_frequency_hz"),
                rfBandwidthHz = json.optLongOrNull("rf_bandwidth_hz"),
                gainDb = json.optDoubleOrNull("gain_db"),
                source = json.optStringOrNull("source"),
                device = json.optStringOrNull("device"),
                durationSec = json.optDoubleOrNull("duration_sec"),
            )
        }.getOrElse { error ->
            IQMetadata(
                sidecarPath = metadataFile.absolutePath,
                sidecarFound = true,
                parseError = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return runCatching { getLong(name) }.getOrNull()
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return runCatching { getDouble(name) }.getOrNull()
    }

    private fun diagnoseCs16SpectrumWithMetadata(path: String, metadata: IQMetadata): String {
        val loaded = metadata.sidecarFound && metadata.parseError == null
        return diagnoseCs16Spectrum(
            path = path,
            metadataSidecarFound = metadata.sidecarFound,
            metadataLoaded = loaded,
            metadataPath = metadata.sidecarPath,
            metadataParseError = metadata.parseError.orEmpty(),
            format = metadata.format.orEmpty(),
            endianness = metadata.endianness.orEmpty(),
            hasSampleRateHz = loaded && metadata.sampleRateHz != null,
            sampleRateHz = metadata.sampleRateHz ?: 0L,
            hasCenterFrequencyHz = loaded && metadata.centerFrequencyHz != null,
            centerFrequencyHz = metadata.centerFrequencyHz ?: 0L,
            hasRfBandwidthHz = loaded && metadata.rfBandwidthHz != null,
            rfBandwidthHz = metadata.rfBandwidthHz ?: 0L,
            hasGainDb = loaded && metadata.gainDb != null,
            gainDb = metadata.gainDb ?: 0.0,
            source = metadata.source.orEmpty(),
            device = metadata.device.orEmpty(),
            hasDurationSec = loaded && metadata.durationSec != null,
            durationSec = metadata.durationSec ?: 0.0,
        )
    }

    private fun decodeAndDisplayFrame(
        path: String,
        metadata: IQMetadata,
        standard: VideoStandard,
        frameIndex: Long,
    ) {
        val frame = decodeFrame(path, metadata, standard, frameIndex)
        if (frame == null) {
            binding.videoFrameImage.setImageDrawable(null)
            binding.sampleText.text = metadata.toDiagnosticText() +
                "\n\nAnalog FPV video frame decode failed. sample_rate_hz metadata is required and input.cs16 must contain enough IQ samples."
            return
        }

        binding.videoFrameImage.setImageBitmap(frame.bitmap)
        binding.sampleText.text = metadata.toDiagnosticText() + "\n\n" + frame.diagnostic
    }

    private fun decodeFrame(
        path: String,
        metadata: IQMetadata,
        standard: VideoStandard,
        frameIndex: Long,
    ): DecodedFrame? {
        val loaded = metadata.sidecarFound && metadata.parseError == null
        val packet = decodeAnalogVideoFrame(
            path = path,
            hasSampleRateHz = loaded && metadata.sampleRateHz != null,
            sampleRateHz = metadata.sampleRateHz ?: 0L,
            videoStandard = standard.nativeValue,
            frameIndex = frameIndex,
        )
        return grayscaleFramePacketToBitmap(packet)
    }

    private fun startPlayback(path: String, metadata: IQMetadata, standard: VideoStandard) {
        stopPlayback()
        playbackRunning = true
        playbackFrameIndex = 0L
        schedulePlaybackFrame(path, metadata, standard)
    }

    private fun stopPlayback() {
        playbackRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        val sessionToClose = playbackSessionHandle
        playbackSessionHandle = 0L
        if (sessionToClose != 0L) {
            decodeExecutor.execute {
                closeAnalogVideoPlaybackSession(sessionToClose)
            }
        }
    }

    private fun schedulePlaybackFrame(path: String, metadata: IQMetadata, standard: VideoStandard) {
        if (!playbackRunning) {
            return
        }

        val frameIndex = playbackFrameIndex
        decodeExecutor.execute {
            var sessionHandle = playbackSessionHandle
            if (sessionHandle == 0L) {
                sessionHandle = createPlaybackSession(path, metadata, standard)
                if (sessionHandle == 0L) {
                    mainHandler.post {
                        if (!playbackRunning) {
                            return@post
                        }
                        stopPlayback()
                        binding.sampleText.text = metadata.toDiagnosticText() +
                            "\n\nPlayback stopped: native playback session could not be created."
                    }
                    return@execute
                }

                if (!playbackRunning) {
                    closeAnalogVideoPlaybackSession(sessionHandle)
                    return@execute
                }
                playbackSessionHandle = sessionHandle
            }

            val frame = grayscaleFramePacketToBitmap(
                packet = decodeNextAnalogVideoPlaybackFrame(sessionHandle),
                includeDiagnostic = (frameIndex % PLAYBACK_DIAGNOSTIC_EVERY_FRAMES) == 0L,
            )
            mainHandler.post {
                if (!playbackRunning) {
                    return@post
                }
                if (frame == null) {
                    stopPlayback()
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nPlayback stopped: decoder returned no frame at index $frameIndex"
                    return@post
                }

                binding.videoFrameImage.setImageBitmap(frame.bitmap)
                if ((frameIndex % PLAYBACK_DIAGNOSTIC_EVERY_FRAMES) == 0L) {
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nplayback_frame_index: $frameIndex\n" + frame.diagnostic
                }
                playbackFrameIndex = frameIndex + 1L
                mainHandler.postDelayed(
                    { schedulePlaybackFrame(path, metadata, standard) },
                    PLAYBACK_DELAY_MS,
                )
            }
        }
    }

    private fun createPlaybackSession(
        path: String,
        metadata: IQMetadata,
        standard: VideoStandard,
    ): Long {
        val loaded = metadata.sidecarFound && metadata.parseError == null
        return createAnalogVideoPlaybackSession(
            path = path,
            hasSampleRateHz = loaded && metadata.sampleRateHz != null,
            sampleRateHz = metadata.sampleRateHz ?: 0L,
            videoStandard = standard.nativeValue,
        )
    }

    private fun grayscaleFramePacketToBitmap(
        packet: ByteArray,
        includeDiagnostic: Boolean = true,
    ): DecodedFrame? {
        if (packet.size < FRAME_PACKET_HEADER_BYTES) {
            return null
        }

        val width = readLittleEndianInt(packet, 0)
        val height = readLittleEndianInt(packet, 4)
        val diagnosticLength = readLittleEndianInt(packet, 8)
        if (width <= 0 || height <= 0) {
            return null
        }
        if (diagnosticLength < 0) {
            return null
        }

        val pixelCountLong = width.toLong() * height.toLong()
        if (pixelCountLong > Int.MAX_VALUE) {
            return null
        }

        val pixelCount = pixelCountLong.toInt()
        if (packet.size != FRAME_PACKET_HEADER_BYTES + pixelCount + diagnosticLength) {
            return null
        }

        if (reusableFramePixels.size != pixelCount) {
            reusableFramePixels = IntArray(pixelCount)
        }
        for (index in 0 until pixelCount) {
            val gray = packet[FRAME_PACKET_HEADER_BYTES + index].toInt() and 0xFF
            reusableFramePixels[index] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.setPixels(reusableFramePixels, 0, width, 0, 0, width, height)
        }
        val diagnosticStart = FRAME_PACKET_HEADER_BYTES + pixelCount
        val diagnostic = if (includeDiagnostic) {
            String(packet, diagnosticStart, diagnosticLength, Charsets.UTF_8)
        } else {
            ""
        }
        return DecodedFrame(bitmap = bitmap, diagnostic = diagnostic)
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private data class IQMetadata(
        val sidecarPath: String,
        val sidecarFound: Boolean = false,
        val parseError: String? = null,
        val format: String? = null,
        val endianness: String? = null,
        val sampleRateHz: Long? = null,
        val centerFrequencyHz: Long? = null,
        val rfBandwidthHz: Long? = null,
        val gainDb: Double? = null,
        val source: String? = null,
        val device: String? = null,
        val durationSec: Double? = null,
    ) {
        fun toDiagnosticText(): String {
            return "metadata path: $sidecarPath"
        }
    }

    private data class DecodedFrame(
        val bitmap: Bitmap,
        val diagnostic: String,
    )

    private enum class VideoStandard(val nativeValue: Int) {
        AUTO(0),
        PAL625_25FPS(1),
        NTSC_525_30FPS(2),
    }

    /**
     * Opens a little-endian CS16 I/Q file and returns first-block diagnostics.
     */
    external fun diagnoseCs16File(path: String): String

    /**
     * Reads a little-endian CS16 I/Q file in blocks and returns aggregate diagnostics.
     */
    external fun diagnoseCs16FileBlocks(path: String): String

    /**
     * Reads a CS16 I/Q block, applies a Hann-windowed FFT, and returns spectrum diagnostics.
     */
    external fun diagnoseCs16Spectrum(
        path: String,
        metadataSidecarFound: Boolean,
        metadataLoaded: Boolean,
        metadataPath: String,
        metadataParseError: String,
        format: String,
        endianness: String,
        hasSampleRateHz: Boolean,
        sampleRateHz: Long,
        hasCenterFrequencyHz: Boolean,
        centerFrequencyHz: Long,
        hasRfBandwidthHz: Boolean,
        rfBandwidthHz: Long,
        hasGainDb: Boolean,
        gainDb: Double,
        source: String,
        device: String,
        hasDurationSec: Boolean,
        durationSec: Double,
    ): String

    /**
     * Decodes one analog FPV video frame and returns width/height plus 8-bit grayscale pixels.
     */
    external fun decodeAnalogVideoFrame(
        path: String,
        hasSampleRateHz: Boolean,
        sampleRateHz: Long,
        videoStandard: Int,
        frameIndex: Long,
    ): ByteArray

    /**
     * Opens a sequential native decoder for playback. The returned handle must be closed.
     */
    external fun createAnalogVideoPlaybackSession(
        path: String,
        hasSampleRateHz: Boolean,
        sampleRateHz: Long,
        videoStandard: Int,
    ): Long

    /**
     * Decodes the next frame from an existing native playback session.
     */
    external fun decodeNextAnalogVideoPlaybackFrame(sessionHandle: Long): ByteArray

    /**
     * Closes a native playback session.
     */
    external fun closeAnalogVideoPlaybackSession(sessionHandle: Long)

    companion object {
        private const val FRAME_PACKET_HEADER_BYTES = 12
        private const val PLAYBACK_DELAY_MS = 1L
        private const val PLAYBACK_DIAGNOSTIC_EVERY_FRAMES = 10L

        // Used to load the 'sdrvideoscanner' library on application startup.
        init {
            System.loadLibrary("sdrvideoscanner")
        }
    }
}
