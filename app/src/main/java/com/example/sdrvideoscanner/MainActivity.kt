package com.example.sdrvideoscanner

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.example.sdrvideoscanner.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
            if (!sidecarFound) {
                return "IQ metadata sidecar: missing\nmetadata path: $sidecarPath"
            }
            if (parseError != null) {
                return "IQ metadata sidecar: parse error\nmetadata path: $sidecarPath\nerror: $parseError"
            }
            return listOf(
                "IQ metadata sidecar: loaded",
                "metadata path: $sidecarPath",
                "format: ${format ?: "unavailable"}",
                "endianness: ${endianness ?: "unavailable"}",
                "sample_rate_hz: ${sampleRateHz ?: "unavailable"}",
                "center_frequency_hz: ${centerFrequencyHz ?: "unavailable"}",
                "rf_bandwidth_hz: ${rfBandwidthHz ?: "unavailable"}",
                "gain_db: ${gainDb ?: "unavailable"}",
                "source: ${source ?: "unavailable"}",
                "device: ${device ?: "unavailable"}",
                "duration_sec: ${durationSec ?: "unavailable"}",
            ).joinToString(separator = "\n")
        }
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

    companion object {
        // Used to load the 'sdrvideoscanner' library on application startup.
        init {
            System.loadLibrary("sdrvideoscanner")
        }
    }
}
