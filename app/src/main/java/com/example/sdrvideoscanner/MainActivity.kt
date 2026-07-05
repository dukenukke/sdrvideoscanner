package com.example.sdrvideoscanner

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.sdrvideoscanner.databinding.ActivityMainBinding
import com.example.sdrvideoscanner.databinding.DialogPlutoIqConfigBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private lateinit var plutoMonitorThread: HandlerThread
    private lateinit var plutoMonitorHandler: Handler
    private val usbOtgPowerController: UsbOtgPowerController = RootSysfsUsbOtgPowerController()
    @Volatile
    private var plutoMonitorRunning = false
    @Volatile
    private var plutoRecoveryInProgress = false
    @Volatile
    private var stoppingForPlutoRecovery = false
    private var plutoMissingSinceMs: Long? = null
    private var plutoSessionRecovery: PlutoSessionRecovery? = null
    @Volatile
    private var playbackRunning = false
    @Volatile
    private var playbackSessionHandle = 0L
    @Volatile
    private var spectrumRunning = false
    @Volatile
    private var spectrumSessionHandle = 0L
    private var playbackFrameIndex = 0L
    private var spectrumFrameIndex = 0L
    private var liveUsbConnection: UsbDeviceConnection? = null
    private var spectrumUsbConnection: UsbDeviceConnection? = null
    private var reusableFramePixels = IntArray(0)
    private var pendingPlutoCapturePath: String? = null
    private var pendingPlutoUsbAction: PlutoUsbAction? = null
    private var pendingPlutoVideoStandard = VideoStandard.AUTO
    private var pendingPlutoIqConfig = defaultPlutoIqConfig()
    private var plutoIqConfig = defaultPlutoIqConfig()
    private var activeMode = ActiveMode.NONE
    private val scanController = ScanController(ChannelPlan.knownChannels, DummyRfFrontendController())
    @Volatile
    private var scannerRunning = false
    private var selectedPlaybackChannel: KnownChannel? = null
    private var currentScannerChannel: KnownChannel? = null
    private var statsVisible = false
    private var usbPermissionReceiverRegistered = false
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) {
                return
            }

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val path = pendingPlutoCapturePath
            val action = pendingPlutoUsbAction
            val standard = pendingPlutoVideoStandard
            val config = pendingPlutoIqConfig
            pendingPlutoCapturePath = null
            pendingPlutoUsbAction = null
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (granted && action != null && device != null) {
                when (action) {
                    PlutoUsbAction.CAPTURE_TO_FILE -> {
                        if (path != null) {
                            startPlutoUsbCapture(path, device, config)
                        }
                    }
                    PlutoUsbAction.LIVE_PLAYBACK -> startPlutoUsbPlayback(device, standard, config)
                    PlutoUsbAction.SPECTRUM_VIEW -> startPlutoUsbSpectrum(device, config)
                    PlutoUsbAction.WEB_SOCKET_PLAYBACK -> startPlutoWebSocketPlayback(standard, config)
                    PlutoUsbAction.WEB_SOCKET_SPECTRUM -> startPlutoWebSocketSpectrum(config)
                    PlutoUsbAction.SCANNER -> startScannerModeAfterPreflight(config)
                }
            } else {
                binding.sampleText.text = "Pluto USB permission denied.\n\n" +
                    "callback_device: ${device?.let { usbDeviceLabel(it) } ?: "missing"}\n" +
                    "pending_action: ${action ?: "missing"}\n" +
                    "pending_capture_path: ${path ?: "none"}\n\n" +
                    plutoUsbDiagnosticText()
            }
        }
    }
    private val plutoMonitorRunnable = object : Runnable {
        override fun run() {
            if (!plutoMonitorRunning) {
                return
            }
            runCatching {
                checkPlutoConnectionAndRecoverIfNeeded()
            }.onFailure { error ->
                Log.w(LOG_TAG, "Pluto connection monitor failed", error)
            }
            if (plutoMonitorRunning) {
                plutoMonitorHandler.postDelayed(this, PLUTO_CONNECTION_CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        plutoMonitorThread = HandlerThread("PlutoConnectionMonitor").apply { start() }
        plutoMonitorHandler = Handler(plutoMonitorThread.looper)
        startPlutoConnectionMonitor()
        applySystemBarInsets()
        plutoIqConfig = loadPlutoIqConfigDefaults()
        pendingPlutoIqConfig = plutoIqConfig

        val defaultIqPath = defaultIqPathForConfig()
        val defaultMetadataPath = metadataFileFor(defaultIqPath).absolutePath
        binding.sampleText.text = "Place ${File(defaultIqPath).name} at:\n$defaultIqPath\n\nOptional metadata:\n$defaultMetadataPath"
        binding.currentMenuLabel.text = "Selected: Pluto WS CS8"
        updateStatsVisibility()
        if (!isPlutoCaptureAvailable()) {
            binding.sampleText.text = "Pluto capture is not compiled into this APK.\n\n" +
                "Add Android libiio headers/libraries, then rebuild.\n\n" +
                "Replay file path:\n$defaultIqPath\n\nOptional metadata:\n$defaultMetadataPath"
        } else if (!isPlutoUsbCaptureAvailable()) {
            binding.sampleText.text = "Pluto direct USB capture is not available in this APK.\n\n" +
                plutoUsbDiagnosticText() + "\n\n" +
                "Rebuild/package libiio with the USB backend and libusb support.\n\n" +
                "Replay file path:\n$defaultIqPath\n\nOptional metadata:\n$defaultMetadataPath"
        }
        binding.mainMenuButton.setOnClickListener {
            showMainMenu()
        }
        binding.toggleStatsButton.setOnClickListener {
            statsVisible = !statsVisible
            updateStatsVisibility()
        }
        binding.currentFrequencyLabel.setOnClickListener {
            showCenterFrequencyDialog()
        }
        binding.scanButton.setOnClickListener {
            if (scannerRunning) {
                stopScannerMode()
            } else {
                startScannerMode()
            }
        }
        binding.nextButton.setOnClickListener {
            skipCurrentSignalAndResumeScan()
        }
        updateCurrentFrequencyLabel(plutoIqConfig.centerFrequencyHz)
        binding.videoFrameContainer.visibility = View.GONE
        renderSignalTable()
        updateScannerUi()
    }

    private fun applySystemBarInsets() {
        val topSpacingPx = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top + topSpacingPx, 0, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun showMainMenu() {
        PopupMenu(this, binding.mainMenuButton).apply {
            menu.add(0, MENU_PLAY_PLUTO_WS_CS8, 0, "Play Pluto WS CS8")
            menu.add(0, MENU_PLAY_PLUTO_USB_CS16, 1, "Play Pluto USB CS16")
            menu.add(0, MENU_STOP, 2, "Stop")
            menu.add(0, MENU_SETUP_IQ, 3, "Setup IQ")
            menu.add(0, MENU_TUNE_FREQUENCY, 4, "Tune Frequency")
            menu.add(0, MENU_RECORD_IQ, 5, "Record IQ")
            menu.add(0, MENU_PLUTO_WS_FFT, 6, "Pluto WS FFT")
            menu.add(0, MENU_PLAY_AUTO_FILE, 7, "Play AUTO file")
            menu.add(0, MENU_DECODE_AUTO_FILE, 8, "Decode AUTO file")
            setOnMenuItemClickListener { item ->
                runMainMenuAction(item.itemId, item.title.toString())
                true
            }
            show()
        }
    }

    private fun runMainMenuAction(actionId: Int, title: String) {
        setSelectedMenuItem(title)
        when (actionId) {
            MENU_PLAY_PLUTO_WS_CS8 -> {
                stopScannerMode()
                stopSpectrum()
                stopPlayback()
                activeMode = ActiveMode.PLUTO_WS_PLAYBACK
                preparePlutoWebSocketPlayback(VideoStandard.AUTO, plutoIqConfig)
            }
            MENU_PLAY_PLUTO_USB_CS16 -> {
                stopScannerMode()
                stopSpectrum()
                stopPlayback()
                activeMode = ActiveMode.PLUTO_USB_PLAYBACK
                preparePlutoUsbPlayback(VideoStandard.AUTO, plutoIqConfig.forcedSampleFormat(IqSampleFormat.CS16))
            }
            MENU_STOP -> {
                stopPlayback()
                stopSpectrum()
                stopScannerMode()
            }
            MENU_SETUP_IQ -> showPlutoIqConfigDialog()
            MENU_TUNE_FREQUENCY -> showCenterFrequencyDialog()
            MENU_RECORD_IQ -> {
                stopScannerMode()
                stopPlayback()
                stopSpectrum()
                activeMode = ActiveMode.RECORD_IQ
                preparePlutoUsbCapture(defaultIqPathForConfig(), plutoIqConfig)
            }
            MENU_PLUTO_WS_FFT -> {
                stopScannerMode()
                stopPlayback()
                stopSpectrum()
                activeMode = ActiveMode.PLUTO_WS_SPECTRUM
                preparePlutoWebSocketSpectrum(plutoIqConfig)
            }
            MENU_PLAY_AUTO_FILE -> {
                stopScannerMode()
                stopSpectrum()
                val path = defaultIqPathForConfig()
                val metadata = loadIqMetadata(path)
                activeMode = ActiveMode.FILE_PLAYBACK
                startPlayback(path, metadata, VideoStandard.AUTO)
            }
            MENU_DECODE_AUTO_FILE -> {
                stopScannerMode()
                stopPlayback()
                stopSpectrum()
                val path = defaultIqPathForConfig()
                val metadata = loadIqMetadata(path)
                activeMode = ActiveMode.FILE_FRAME
                decodeAndDisplayFrame(path, metadata, VideoStandard.AUTO, frameIndex = 0L)
            }
        }
    }

    private fun setSelectedMenuItem(title: String) {
        binding.currentMenuLabel.text = "Selected: $title"
    }

    private fun updateStatsVisibility() {
        binding.statsScroll.visibility = if (statsVisible) View.VISIBLE else View.GONE
        binding.toggleStatsButton.text = if (statsVisible) "Hide Stats" else "Show Stats"
    }

    private fun showCenterFrequencyDialog() {
        val inputLayout = TextInputLayout(this).apply {
            hint = "Center frequency Hz"
            helperText = "Example: 5885000000 for 5.885 GHz"
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                0,
            )
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(plutoIqConfig.centerFrequencyHz.toString())
            selectAll()
            setSingleLine(true)
        }
        inputLayout.addView(input)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Tune center frequency")
            .setMessage("Current: ${formatFrequency(plutoIqConfig.centerFrequencyHz)}")
            .setView(inputLayout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                inputLayout.error = null
                val frequencyHz = readPositiveLong(input, inputLayout, "Center frequency") ?: return@setOnClickListener
                applyCenterFrequency(frequencyHz)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun applyCenterFrequency(frequencyHz: Long) {
        val previousMode = activeMode
        val restartPlayback = playbackRunning && previousMode == ActiveMode.PLUTO_WS_PLAYBACK
        val restartSpectrum = spectrumRunning && previousMode == ActiveMode.PLUTO_WS_SPECTRUM
        plutoIqConfig = plutoIqConfig.copy(centerFrequencyHz = frequencyHz)
        pendingPlutoIqConfig = pendingPlutoIqConfig.copy(centerFrequencyHz = frequencyHz)
        updateCurrentFrequencyLabel(frequencyHz)

        when {
            restartPlayback -> {
                stopPlayback()
                activeMode = ActiveMode.PLUTO_WS_PLAYBACK
                binding.sampleText.text = "Center frequency changed to ${formatFrequency(frequencyHz)}.\n\nRestarting Pluto WebSocket playback."
                preparePlutoWebSocketPlayback(VideoStandard.AUTO, plutoIqConfig)
            }
            restartSpectrum -> {
                stopSpectrum()
                activeMode = ActiveMode.PLUTO_WS_SPECTRUM
                binding.sampleText.text = "Center frequency changed to ${formatFrequency(frequencyHz)}.\n\nRestarting Pluto WebSocket FFT / waterfall."
                preparePlutoWebSocketSpectrum(plutoIqConfig)
            }
            else -> {
                binding.sampleText.text = "Center frequency changed to ${formatFrequency(frequencyHz)}.\n\n" +
                    "New value applies to the next live playback, FFT, or IQ recording session."
            }
        }
    }

    private fun updateCurrentFrequencyLabel(frequencyHz: Long?) {
        binding.currentFrequencyLabel.text = frequencyHz?.let { formatFrequency(it) } ?: "Freq: --"
    }

    private fun formatFrequency(frequencyHz: Long): String {
        return when {
            frequencyHz >= 1_000_000_000L -> {
                String.format(Locale.US, "%.3f GHz", frequencyHz.toDouble() / 1_000_000_000.0)
            }
            frequencyHz >= 1_000_000L -> {
                String.format(Locale.US, "%.3f MHz", frequencyHz.toDouble() / 1_000_000.0)
            }
            frequencyHz >= 1_000L -> {
                String.format(Locale.US, "%.3f kHz", frequencyHz.toDouble() / 1_000.0)
            }
            else -> "$frequencyHz Hz"
        }
    }

    private fun startScannerMode() {
        selectedPlaybackChannel = null
        stopSpectrum()
        stopPlayback()
        if (requestPlutoUsbPermissionForNetworkIfNeeded(
                action = PlutoUsbAction.SCANNER,
                standard = VideoStandard.AUTO,
                config = plutoIqConfig,
                description = "scanner",
            )
        ) {
            return
        }
        startScannerModeAfterPreflight(plutoIqConfig)
    }

    private fun startScannerModeAfterPreflight(config: PlutoIqConfig) {
        plutoIqConfig = config
        pendingPlutoIqConfig = config
        scannerRunning = true
        updateSleepBlocker()
        scanController.startScanningNear(config.centerFrequencyHz)
        binding.videoFrameContainer.visibility = View.GONE
        binding.videoFrameImage.setImageDrawable(null)
        updateScannerUi()
        scheduleNextScanStep(0L)
    }

    private fun stopScannerMode() {
        scannerRunning = false
        selectedPlaybackChannel = null
        currentScannerChannel = null
        scanController.idle()
        updateSleepBlocker()
        updateScannerUi()
    }

    private fun skipCurrentSignalAndResumeScan() {
        selectedPlaybackChannel = null
        currentScannerChannel = null
        stopPlayback()
        scannerRunning = true
        updateSleepBlocker()
        scanController.startScanningNear(plutoIqConfig.centerFrequencyHz)
        binding.videoFrameContainer.visibility = View.GONE
        binding.nextButton.isEnabled = false
        updateScannerUi()
        scheduleNextScanStep(0L)
    }

    private fun updateScannerUi() {
        binding.scanButton.text = if (scannerRunning && selectedPlaybackChannel == null) {
            "Stop Scan"
        } else {
            "Scan"
        }
        binding.nextButton.isEnabled = selectedPlaybackChannel != null
        binding.scannerStateLabel.text = buildString {
            append("Scanner: ")
            append(scanController.state.name.lowercase(Locale.US))
            statusLineChannel()?.let { channel ->
                append(" | ")
                append(channel.bandName)
                append(" / ")
                append(channel.channelName)
                append(" / ")
                append(formatFrequency(channel.centerFrequencyHz))
            }
            append(" | records: ")
            append(scanController.records().size)
        }
    }

    private fun updateSleepBlocker() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateSleepBlocker() }
            return
        }

        val shouldBlockSleep = scannerRunning ||
            playbackRunning ||
            spectrumRunning ||
            activeMode == ActiveMode.RECORD_IQ
        if (shouldBlockSleep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun statusLineChannel(): KnownChannel? {
        return when (scanController.state) {
            ScannerState.SCANNING,
            ScannerState.CANDIDATE_DETECTED,
            ScannerState.BACKGROUND_SCAN_WHILE_PLAYING -> currentScannerChannel
            ScannerState.LOCKED_PLAYING -> selectedPlaybackChannel
            ScannerState.IDLE -> null
        }
    }

    private fun scheduleNextScanStep(delayMs: Long = SCAN_STEP_DELAY_MS) {
        mainHandler.postDelayed({
            if (!scannerRunning || selectedPlaybackChannel != null) {
                return@postDelayed
            }
            runScanStep()
        }, delayMs)
    }

    private fun runScanStep() {
        val channel = scanController.nextChannel() ?: return
        currentScannerChannel = channel
        scanController.startScanning()
        updateScannerUi()
        decodeExecutor.execute {
            val spectrumResult = probeChannelForSpectrum(channel)
            val result = if (spectrumResult.signalPresent) {
                val videoResult = probeChannelForSignal(channel, spectrumResult)
                if (videoResult.signalPresent) videoResult else spectrumResult.toSignalProbeResult()
            } else {
                spectrumResult.toSignalProbeResult()
            }
            scanController.applyProbeResult(channel, result)
            mainHandler.post {
                scanController.expireStaleRecords()
                renderSignalTable()
                updateLastProbeDiagnostic(channel, result)
                updateScannerUi()
                if (scannerRunning && selectedPlaybackChannel == null) {
                    scheduleNextScanStep()
                }
            }
        }
    }

    private fun probeChannelForSpectrum(channel: KnownChannel): SpectrumProbeResult {
        if (!scanController.configureFrontend(channel)) {
            return SpectrumProbeResult(
                signalPresent = false,
                rssiDbfs = null,
                confidence = 0.0,
                diagnostic = "RF frontend configuration failed for ${channel.channelName}",
            )
        }

        val config = configForChannel(channel)
        val primeDiagnostic = primePlutoUsbForWebSocket(config)
        Thread.sleep(SCANNER_FFT_RETUNE_SETTLE_MS)
        val ethernetSelection = findEthernetNetworkWithDiagnostics()
        val ethernetNetwork = ethernetSelection.network
        if (ethernetNetwork == null || ethernetNetwork.networkHandle == 0L) {
            return SpectrumProbeResult(
                signalPresent = false,
                rssiDbfs = null,
                confidence = 0.0,
                diagnostic = "$primeDiagnostic\n${ethernetSelection.diagnostics}",
            )
        }

        val transport = PlutoWaterfallTransport.create(
            network = ethernetNetwork,
            host = config.maiaHost,
            port = PLUTO_WATERFALL_PORT,
            path = PLUTO_WATERFALL_PATH,
            connectTimeoutMs = PLUTO_WS_CONNECT_TIMEOUT_MS,
            readTimeoutMs = PLUTO_WS_READ_TIMEOUT_MS,
        )
        val openError = transport.openStream()
        if (openError != null) {
            transport.close()
            return SpectrumProbeResult(
                signalPresent = false,
                rssiDbfs = null,
                confidence = 0.0,
                diagnostic = "$primeDiagnostic\nwaterfall_open_failed: $openError",
            )
        }

        return try {
            repeat(SCANNER_FFT_DISCARD_FRAME_COUNT) {
                transport.readBinaryFrame()
            }

            var bestStats: WaterfallFrameStats? = null
            repeat(SCANNER_FFT_PROBE_FRAME_COUNT) {
                val payload = transport.readBinaryFrame()
                val stats = payload?.let { analyzeWaterfallFrame(it) }
                if (stats != null && (bestStats == null || stats.confidence > bestStats.confidence)) {
                    bestStats = stats
                }
            }

            val stats = bestStats
            if (stats == null) {
                SpectrumProbeResult(
                    signalPresent = false,
                    rssiDbfs = null,
                    confidence = 0.0,
                    diagnostic = "$primeDiagnostic\nwaterfall_probe_failed: ${transport.lastError().ifBlank { "no binary waterfall frames" }}",
                )
            } else {
                val detected = stats.confidence >= SCANNER_FFT_MIN_CONFIDENCE &&
                    stats.peak >= SCANNER_FFT_MIN_PEAK &&
                    stats.contrast >= SCANNER_FFT_MIN_CONTRAST
                SpectrumProbeResult(
                    signalPresent = detected,
                    rssiDbfs = if (detected) estimateRssiDbfs(stats.confidence) else null,
                    confidence = stats.confidence,
                    diagnostic = buildString {
                        append(primeDiagnostic)
                        append("\nscanner_stage: waterfall_fft")
                        append("\nwaterfall_url: ws://${config.maiaHost}$PLUTO_WATERFALL_PATH")
                        append("\nwaterfall_payload_bytes: ${stats.byteCount}")
                        append("\nwaterfall_peak: ${stats.peak}")
                        append("\nwaterfall_mean: ${String.format(Locale.US, "%.2f", stats.mean)}")
                        append("\nwaterfall_contrast: ${String.format(Locale.US, "%.2f", stats.contrast)}")
                        append("\nwaterfall_active_bins: ${stats.activeBins}")
                        append("\nwaterfall_confidence: ${String.format(Locale.US, "%.3f", stats.confidence)}")
                        append("\nwaterfall_signal_present: $detected")
                    },
                )
            }
        } finally {
            transport.close()
        }
    }

    private fun analyzeWaterfallFrame(payload: ByteArray): WaterfallFrameStats? {
        if (payload.isEmpty()) {
            return null
        }

        var peak = 0
        var sum = 0L
        for (byte in payload) {
            val value = byte.toInt() and 0xFF
            peak = maxOf(peak, value)
            sum += value.toLong()
        }
        val mean = sum.toDouble() / payload.size.toDouble()
        val contrast = peak.toDouble() - mean
        var activeBins = 0
        val activeThreshold = mean + SCANNER_FFT_ACTIVE_BIN_OFFSET
        for (byte in payload) {
            if ((byte.toInt() and 0xFF).toDouble() >= activeThreshold) {
                activeBins += 1
            }
        }

        val contrastScore = ((contrast - SCANNER_FFT_MIN_CONTRAST) / SCANNER_FFT_CONTRAST_SCORE_RANGE)
            .coerceIn(0.0, 1.0)
        val peakScore = ((peak.toDouble() - SCANNER_FFT_MIN_PEAK) / SCANNER_FFT_PEAK_SCORE_RANGE)
            .coerceIn(0.0, 1.0)
        val occupancyScore = (activeBins.toDouble() / payload.size.toDouble() / SCANNER_FFT_ACTIVE_FRACTION_TARGET)
            .coerceIn(0.0, 1.0)
        val confidence = (contrastScore * 0.55 + peakScore * 0.30 + occupancyScore * 0.15)
            .coerceIn(0.0, 1.0)

        return WaterfallFrameStats(
            byteCount = payload.size,
            peak = peak,
            mean = mean,
            contrast = contrast,
            activeBins = activeBins,
            confidence = confidence,
        )
    }

    private fun probeChannelForSignal(
        channel: KnownChannel,
    ): SignalProbeResult {
        return probeChannelForSignal(
            channel,
            SpectrumProbeResult(
                signalPresent = true,
                rssiDbfs = null,
                confidence = 0.0,
                diagnostic = "scanner_stage: direct_video_probe",
            ),
        )
    }

    private fun probeChannelForSignal(
        channel: KnownChannel,
        spectrumResult: SpectrumProbeResult,
    ): SignalProbeResult {
        if (!scanController.configureFrontend(channel)) {
            return SignalProbeResult(
                signalPresent = false,
                signalType = SignalType.UNKNOWN,
                rssiDbfs = spectrumResult.rssiDbfs,
                confidence = spectrumResult.confidence,
                previewFrame = null,
                diagnostic = spectrumResult.diagnostic +
                    "\nvideo_probe_skipped: RF frontend configuration failed for ${channel.channelName}",
            )
        }

        val config = configForChannel(channel)
        val primeDiagnostic = primePlutoUsbForWebSocket(config)
        Thread.sleep(SCANNER_VIDEO_RETUNE_SETTLE_MS)
        val ethernetSelection = findEthernetNetworkWithDiagnostics()
        val ethernetNetwork = ethernetSelection.network
        if (ethernetNetwork == null || ethernetNetwork.networkHandle == 0L) {
            return SignalProbeResult(
                signalPresent = false,
                signalType = SignalType.UNKNOWN,
                rssiDbfs = spectrumResult.rssiDbfs,
                confidence = spectrumResult.confidence,
                previewFrame = null,
                diagnostic = "${spectrumResult.diagnostic}\n$primeDiagnostic\n${ethernetSelection.diagnostics}",
            )
        }

        val transport = PlutoWebSocketTransport.create(
            network = ethernetNetwork,
            host = config.maiaHost,
            port = config.maiaPort,
            path = config.plutoWebSocketPath,
            connectTimeoutMs = PLUTO_WS_CONNECT_TIMEOUT_MS,
            readTimeoutMs = PLUTO_WS_READ_TIMEOUT_MS,
        )
        val sessionHandle = createPlutoWebSocketAnalogVideoPlaybackSession(
            host = config.maiaHost,
            port = config.maiaPort,
            path = config.plutoWebSocketPath,
            sampleRateHz = config.sampleRateHz,
            receiveBufferMs = config.plutoWebSocketReceiveBufferMs,
            videoStandard = VideoStandard.AUTO.nativeValue,
            androidWebSocketTransport = transport,
        )
        if (sessionHandle == 0L) {
            val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
            transport.close()
            return SignalProbeResult(
                signalPresent = false,
                signalType = SignalType.UNKNOWN,
                rssiDbfs = spectrumResult.rssiDbfs,
                confidence = spectrumResult.confidence,
                previewFrame = null,
                diagnostic = "${spectrumResult.diagnostic}\n$primeDiagnostic\nprobe_session_failed: $nativeError",
            )
        }

        return try {
            repeat(SCANNER_PROBE_DISCARD_FRAME_COUNT) {
                decodeNextAnalogVideoPlaybackFrame(sessionHandle)
            }
            var bestFrame: DecodedFrame? = null
            var bestDiagnostic = ""
            var bestConfidence = 0.0
            var detectedFrameCount = 0

            fun scoreNextFrame() {
                val frame = grayscaleFramePacketToBitmap(
                    packet = decodeNextAnalogVideoPlaybackFrame(sessionHandle),
                    includeDiagnostic = true,
                )
                val diagnostic = frame?.diagnostic.orEmpty()
                val confidence = analogConfidence(diagnostic)
                if (isAnalogVideoDetected(diagnostic)) {
                    detectedFrameCount += 1
                }
                if (confidence >= bestConfidence) {
                    bestFrame = frame
                    bestDiagnostic = diagnostic
                    bestConfidence = confidence
                }
            }

            repeat(SCANNER_FAST_PROBE_FRAME_COUNT) {
                scoreNextFrame()
            }

            val needsConfirmation = detectedFrameCount > 0 ||
                bestConfidence >= SCANNER_CONFIRMATION_CONFIDENCE
            if (needsConfirmation) {
                repeat(SCANNER_CONFIRM_PROBE_FRAME_COUNT) {
                    scoreNextFrame()
                }
            }

            val scoredFrameCount = SCANNER_FAST_PROBE_FRAME_COUNT +
                if (needsConfirmation) SCANNER_CONFIRM_PROBE_FRAME_COUNT else 0
            val analogDetected = detectedFrameCount >= SCANNER_REQUIRED_DETECTED_FRAME_COUNT &&
                bestConfidence >= SCANNER_MIN_ANALOG_CONFIDENCE
            SignalProbeResult(
                signalPresent = analogDetected,
                signalType = if (analogDetected) SignalType.ANALOG else SignalType.UNKNOWN,
                rssiDbfs = if (analogDetected) estimateRssiDbfs(bestConfidence) else spectrumResult.rssiDbfs,
                confidence = if (analogDetected) bestConfidence else spectrumResult.confidence,
                previewFrame = if (analogDetected) bestFrame?.bitmap else null,
                diagnostic = "${spectrumResult.diagnostic}\n$primeDiagnostic\nscanner_stage: video_decode\nscanner_detected_frames: $detectedFrameCount\nscanner_scored_frames: $scoredFrameCount\n$bestDiagnostic",
            )
        } finally {
            closeAnalogVideoPlaybackSession(sessionHandle)
        }
    }

    private fun confirmFrequencyCandidate(
        initialChannel: KnownChannel,
        initialResult: SignalProbeResult,
    ): Pair<KnownChannel, SignalProbeResult> {
        if (!initialResult.signalPresent) {
            return initialChannel to initialResult
        }

        var bestChannel = initialChannel
        var bestResult = initialResult
        var confirmedHits = 1
        val diagnostics = StringBuilder(initialResult.diagnostic)
        diagnostics.append("\nscanner_confirmation_initial_hz: ${initialChannel.centerFrequencyHz}")

        for (channel in nearbyConfirmationChannels(initialChannel)) {
            val result = probeChannelForSignal(channel)
            diagnostics.append(
                "\nscanner_confirmation_probe: ${channel.channelName} ${channel.centerFrequencyHz}Hz " +
                    "present=${result.signalPresent} confidence=${String.format(Locale.US, "%.3f", result.confidence)}",
            )
            if (result.signalPresent) {
                confirmedHits += 1
            }
            if (isBetterFrequencyCandidate(channel, result, bestChannel, bestResult)) {
                bestChannel = channel
                bestResult = result
            }
        }

        val confirmed = confirmedHits >= SCANNER_MIN_CONFIRMATION_HITS
        val mergedDiagnostic = buildString {
            append(bestResult.diagnostic)
            append("\nscanner_frequency_confirmation_hits: $confirmedHits")
            append("\nscanner_frequency_confirmation_required: $SCANNER_MIN_CONFIRMATION_HITS")
            append("\nscanner_frequency_confirmed: $confirmed")
            append("\nscanner_frequency_selected_hz: ${bestChannel.centerFrequencyHz}")
            append("\nscanner_frequency_initial_hz: ${initialChannel.centerFrequencyHz}")
            append("\n")
            append(diagnostics)
        }

        if (!confirmed) {
            return bestChannel to bestResult.copy(
                signalPresent = false,
                signalType = SignalType.UNKNOWN,
                rssiDbfs = null,
                previewFrame = null,
                diagnostic = mergedDiagnostic,
            )
        }

        return bestChannel to bestResult.copy(diagnostic = mergedDiagnostic)
    }

    private fun isBetterFrequencyCandidate(
        candidateChannel: KnownChannel,
        candidateResult: SignalProbeResult,
        bestChannel: KnownChannel,
        bestResult: SignalProbeResult,
    ): Boolean {
        if (!candidateResult.signalPresent) {
            return false
        }

        val confidenceDelta = candidateResult.confidence - bestResult.confidence
        if (confidenceDelta > SCANNER_FREQUENCY_SWITCH_MARGIN) {
            return true
        }
        if (confidenceDelta < -SCANNER_FREQUENCY_SWITCH_MARGIN) {
            return false
        }

        val candidateDetectedFrames = diagnosticNumber(candidateResult.diagnostic, "scanner_detected_frames") ?: 0.0
        val bestDetectedFrames = diagnosticNumber(bestResult.diagnostic, "scanner_detected_frames") ?: 0.0
        if (candidateDetectedFrames != bestDetectedFrames) {
            return candidateDetectedFrames > bestDetectedFrames
        }

        val candidateSyncScore = diagnosticNumber(candidateResult.diagnostic, "sync_score") ?: 0.0
        val bestSyncScore = diagnosticNumber(bestResult.diagnostic, "sync_score") ?: 0.0
        if (kotlin.math.abs(candidateSyncScore - bestSyncScore) > SCANNER_SCORE_TIE_MARGIN) {
            return candidateSyncScore > bestSyncScore
        }

        val candidateLineStability = diagnosticNumber(candidateResult.diagnostic, "line_stability")
            ?: diagnosticNumber(candidateResult.diagnostic, "line_stability_score")
            ?: 0.0
        val bestLineStability = diagnosticNumber(bestResult.diagnostic, "line_stability")
            ?: diagnosticNumber(bestResult.diagnostic, "line_stability_score")
            ?: 0.0
        if (kotlin.math.abs(candidateLineStability - bestLineStability) > SCANNER_SCORE_TIE_MARGIN) {
            return candidateLineStability > bestLineStability
        }

        return candidateChannel.centerFrequencyHz < bestChannel.centerFrequencyHz
    }

    private fun nearbyConfirmationChannels(channel: KnownChannel): List<KnownChannel> {
        return ChannelPlan.knownChannels
            .asSequence()
            .filter { it.centerFrequencyHz != channel.centerFrequencyHz }
            .filter {
                it.rfFrontendProfileId == channel.rfFrontendProfileId &&
                    kotlin.math.abs(it.centerFrequencyHz - channel.centerFrequencyHz) <= SCANNER_CONFIRMATION_WINDOW_HZ
            }
            .sortedWith(
                compareBy<KnownChannel> { kotlin.math.abs(it.centerFrequencyHz - channel.centerFrequencyHz) }
                    .thenBy { it.centerFrequencyHz },
            )
            .take(SCANNER_MAX_CONFIRMATION_CHANNELS)
            .toList()
    }

    private fun configForChannel(channel: KnownChannel): PlutoIqConfig {
        return plutoIqConfig.copy(centerFrequencyHz = channel.centerFrequencyHz)
    }

    private fun isAnalogVideoDetected(diagnostic: String): Boolean {
        val syncLocked = diagnostic.contains("sync_locked: yes", ignoreCase = true) ||
            diagnostic.contains("sync_locked=yes", ignoreCase = true)
        val syncCount = diagnosticNumber(diagnostic, "syncs")
            ?: diagnosticNumber(diagnostic, "detected_syncs")
            ?: 0.0
        val frameSyncEdges = diagnosticNumber(diagnostic, "frame_sync_edges")
            ?: diagnosticNumber(diagnostic, "detected_frame_sync_edges")
            ?: 0.0
        val syncScore = diagnosticNumber(diagnostic, "sync_score") ?: 0.0
        val lineStability = diagnosticNumber(diagnostic, "line_stability")
            ?: diagnosticNumber(diagnostic, "line_stability_score")
            ?: 0.0
        val strongSync = syncCount >= 120.0 && syncScore >= 0.10 && lineStability >= 0.08
        val frameSyncLock = frameSyncEdges >= 2.0 && syncCount >= 80.0 && syncScore >= 0.08
        return syncLocked || strongSync || frameSyncLock
    }

    private fun analogConfidence(diagnostic: String): Double {
        val syncLocked = diagnostic.contains("sync_locked: yes", ignoreCase = true) ||
            diagnostic.contains("sync_locked=yes", ignoreCase = true)
        val syncCount = diagnosticNumber(diagnostic, "syncs")
            ?: diagnosticNumber(diagnostic, "detected_syncs")
            ?: 0.0
        val frameSyncEdges = diagnosticNumber(diagnostic, "frame_sync_edges")
            ?: diagnosticNumber(diagnostic, "detected_frame_sync_edges")
            ?: 0.0
        val syncScore = diagnosticNumber(diagnostic, "sync_score") ?: 0.0
        val lineStability = diagnosticNumber(diagnostic, "line_stability")
            ?: diagnosticNumber(diagnostic, "line_stability_score")
            ?: 0.0
        val base = if (syncLocked) 0.65 else 0.0
        val frameSyncBoost = (frameSyncEdges / 4.0).coerceIn(0.0, 1.0) * 0.15
        val syncCountScore = (syncCount / 260.0).coerceIn(0.0, 1.0) * 0.20
        return (base + frameSyncBoost + syncCountScore + syncScore * 0.25 + lineStability * 0.20)
            .coerceIn(0.0, 1.0)
    }

    private fun estimateRssiDbfs(confidence: Double): Double {
        return -95.0 + confidence.coerceIn(0.0, 1.0) * 45.0
    }

    private fun updateLastProbeDiagnostic(channel: KnownChannel, result: SignalProbeResult) {
        val diagnostic = result.diagnostic
        val syncCount = diagnosticNumber(diagnostic, "syncs")
            ?: diagnosticNumber(diagnostic, "detected_syncs")
        val frameSyncEdges = diagnosticNumber(diagnostic, "frame_sync_edges")
            ?: diagnosticNumber(diagnostic, "detected_frame_sync_edges")
        val syncScore = diagnosticNumber(diagnostic, "sync_score")
        val lineStability = diagnosticNumber(diagnostic, "line_stability")
            ?: diagnosticNumber(diagnostic, "line_stability_score")
        val syncLocked = diagnostic.contains("sync_locked: yes", ignoreCase = true) ||
            diagnostic.contains("sync_locked=yes", ignoreCase = true)
        val detectedFrames = diagnosticNumber(diagnostic, "scanner_detected_frames")
        val scoredFrames = diagnosticNumber(diagnostic, "scanner_scored_frames")
        binding.sampleText.text = buildString {
            append("last_probe: ${channel.bandName} / ${channel.channelName} / ${formatFrequency(channel.centerFrequencyHz)}")
            append("\npresent: ${result.signalPresent}")
            append("\nconfidence: ${String.format(Locale.US, "%.3f", result.confidence)}")
            append("\ndetected_frames: ${detectedFrames?.toInt() ?: -1}/${scoredFrames?.toInt() ?: -1}")
            append("\nsync_locked: $syncLocked")
            append("\nsyncs: ${syncCount?.toInt() ?: -1}")
            append("\nframe_sync_edges: ${frameSyncEdges?.toInt() ?: -1}")
            append("\nsync_score: ${syncScore?.let { String.format(Locale.US, "%.4f", it) } ?: "--"}")
            append("\nline_stability: ${lineStability?.let { String.format(Locale.US, "%.4f", it) } ?: "--"}")
        }
    }

    private fun diagnosticNumber(diagnostic: String, key: String): Double? {
        val regex = Regex("""\b${Regex.escape(key)}\s*[:=]\s*(-?[0-9]+(?:\.[0-9]+)?)""")
        return regex.find(diagnostic)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun renderSignalTable() {
        val records = scanController.records()
        binding.detectedSignalsContainer.removeAllViews()
        if (records.isEmpty()) {
            binding.detectedSignalsContainer.addView(
                TextView(this).apply {
                    text = if (scannerRunning) {
                        "Scanning known channels..."
                    } else {
                        "No signals detected yet. Press Scan."
                    }
                    textSize = 14f
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                },
            )
            return
        }

        records.forEach { record ->
            binding.detectedSignalsContainer.addView(signalRecordRow(record))
        }
    }

    private fun signalRecordRow(record: DetectedSignalRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(24, 28, 30))
            alpha = if (record.signalType == SignalType.ANALOG) 1.0f else 0.45f
            isClickable = record.signalType == SignalType.ANALOG
            isFocusable = record.signalType == SignalType.ANALOG
            setOnClickListener {
                if (record.signalType == SignalType.ANALOG) {
                    playDetectedAnalogSignal(record)
                }
            }
        }

        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(72)).apply {
                marginEnd = dp(10)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(8, 8, 8))
            contentDescription = "Preview ${record.channel.channelName}"
            if (record.previewFrame != null) {
                setImageBitmap(record.previewFrame)
            }
        }
        row.addView(preview)

        row.addView(
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(Color.WHITE)
                textSize = 13f
                text = buildString {
                    append(record.channel.bandName)
                    append(" / ")
                    append(record.channel.channelName)
                    append("\n")
                    append(formatFrequency(record.channel.centerFrequencyHz))
                    append(" | ")
                    append(record.signalType.name.lowercase(Locale.US))
                    append("\nRSSI: ")
                    append(record.rssiDbfs?.let { String.format(Locale.US, "%.1f dBFS", it) } ?: "--")
                    append(" | Dir: ")
                    append(record.direction.name.lowercase(Locale.US))
                    append(" | C: ")
                    append(String.format(Locale.US, "%.2f", record.confidence))
                }
            },
        )

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(
                View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(6),
                    )
                },
            )
        }
        return wrapper
    }

    private fun playDetectedAnalogSignal(record: DetectedSignalRecord) {
        selectedPlaybackChannel = record.channel
        currentScannerChannel = null
        scannerRunning = true
        scanController.lockPlaying()
        updateCurrentFrequencyLabel(record.channel.centerFrequencyHz)
        updateScannerUi()
        binding.videoFrameContainer.visibility = View.VISIBLE
        record.previewFrame?.let { setVideoFrameBitmap(it) }
        startSelectedChannelPlayback(record.channel)
        scheduleBackgroundScanWhilePlaying()
    }

    private fun startSelectedChannelPlayback(channel: KnownChannel) {
        stopSpectrum()
        stopPlayback()
        selectedPlaybackChannel = channel
        scanController.lockPlaying()
        activeMode = ActiveMode.PLUTO_WS_PLAYBACK
        updateScannerUi()
        startPlutoWebSocketPlayback(VideoStandard.AUTO, configForChannel(channel))
    }

    private fun scheduleBackgroundScanWhilePlaying() {
        mainHandler.postDelayed({
            val selected = selectedPlaybackChannel ?: return@postDelayed
            if (!scannerRunning || !playbackRunning) {
                return@postDelayed
            }
            runBackgroundScanWindow(selected)
        }, PLAYBACK_SCAN_PLAY_WINDOW_MS)
    }

    private fun runBackgroundScanWindow(selected: KnownChannel) {
        scanController.backgroundScanWhilePlaying()
        currentScannerChannel = null
        updateScannerUi()
        stopPlayback()
        decodeExecutor.execute {
            val endTimeMs = System.currentTimeMillis() + PLAYBACK_SCAN_BACKGROUND_WINDOW_MS
            var scannedChannels = 0
            while (System.currentTimeMillis() < endTimeMs && scannedChannels < BACKGROUND_SCAN_MAX_CHANNELS) {
                val channel = scanController.nextChannel(excluding = selected) ?: break
                mainHandler.post {
                    currentScannerChannel = channel
                    updateScannerUi()
                }
                val result = probeChannelForSignal(channel)
                scanController.applyProbeResult(channel, result)
                scannedChannels += 1
                mainHandler.post {
                    updateLastProbeDiagnostic(channel, result)
                }
            }
            mainHandler.post {
                currentScannerChannel = null
                scanController.expireStaleRecords()
                renderSignalTable()
                if (selectedPlaybackChannel?.centerFrequencyHz == selected.centerFrequencyHz) {
                    scanController.lockPlaying()
                    updateScannerUi()
                    binding.videoFrameContainer.visibility = View.VISIBLE
                    startPlutoWebSocketPlayback(VideoStandard.AUTO, configForChannel(selected))
                    scheduleBackgroundScanWhilePlaying()
                } else {
                    updateScannerUi()
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        stopPlutoConnectionMonitor()
        stopPlayback()
        stopSpectrum()
        activeMode = ActiveMode.NONE
        scannerRunning = false
        updateSleepBlocker()
        if (usbPermissionReceiverRegistered) {
            unregisterReceiver(usbPermissionReceiver)
            usbPermissionReceiverRegistered = false
        }
        decodeExecutor.shutdownNow()
        plutoMonitorThread.quitSafely()
        super.onDestroy()
    }

    private fun startPlutoConnectionMonitor() {
        if (plutoMonitorRunning) {
            return
        }
        plutoMonitorRunning = true
        plutoMonitorHandler.post(plutoMonitorRunnable)
    }

    private fun stopPlutoConnectionMonitor() {
        plutoMonitorRunning = false
        if (::plutoMonitorHandler.isInitialized) {
            plutoMonitorHandler.removeCallbacksAndMessages(null)
        }
    }

    @Synchronized
    private fun rememberPlutoSession(mode: ActiveMode, standard: VideoStandard, config: PlutoIqConfig) {
        plutoSessionRecovery = PlutoSessionRecovery(mode, standard, config)
        plutoMissingSinceMs = null
        plutoRecoveryInProgress = false
    }

    @Synchronized
    private fun clearPlutoSessionIfManualStop(mode: ActiveMode) {
        if (stoppingForPlutoRecovery) {
            return
        }
        if (plutoSessionRecovery?.mode == mode) {
            plutoSessionRecovery = null
            plutoMissingSinceMs = null
            plutoRecoveryInProgress = false
        }
    }

    @Synchronized
    private fun currentPlutoSessionRecovery(): PlutoSessionRecovery? = plutoSessionRecovery

    private fun checkPlutoConnectionAndRecoverIfNeeded() {
        val snapshot = currentPlutoSessionRecovery()
        if (snapshot == null) {
            synchronized(this) {
                plutoMissingSinceMs = null
                plutoRecoveryInProgress = false
            }
            return
        }

        val status = plutoConnectionStatus(snapshot)
        val active = isPlutoSessionActive(snapshot)
        val nowMs = SystemClock.elapsedRealtime()

        if (status.connected) {
            synchronized(this) {
                plutoMissingSinceMs = null
            }
            if (!active && !plutoRecoveryInProgress) {
                mainHandler.post {
                    if (currentPlutoSessionRecovery() == snapshot && !isPlutoSessionActive(snapshot)) {
                        binding.sampleText.text = "Pluto connection restored. Restarting ${snapshot.mode.label()}."
                        restartPlutoSession(snapshot)
                    }
                }
            }
            return
        }

        val missingSince = synchronized(this) {
            val existing = plutoMissingSinceMs
            if (existing == null) {
                plutoMissingSinceMs = nowMs
                nowMs
            } else {
                existing
            }
        }
        val absentMs = nowMs - missingSince
        if (absentMs < PLUTO_CONNECTION_ABSENT_POWER_CYCLE_MS || plutoRecoveryInProgress) {
            return
        }

        plutoRecoveryInProgress = true
        Log.w(LOG_TAG, "Pluto absent for ${absentMs}ms; starting USB OTG recovery: ${status.diagnostic}")
        recoverPlutoAfterLongAbsence(snapshot, status, absentMs)
    }

    private fun plutoConnectionStatus(snapshot: PlutoSessionRecovery): PlutoConnectionStatus {
        return when (snapshot.mode) {
            ActiveMode.PLUTO_USB_PLAYBACK,
            ActiveMode.PLUTO_USB_SPECTRUM -> directUsbPlutoConnectionStatus()
            ActiveMode.PLUTO_WS_PLAYBACK,
            ActiveMode.PLUTO_WS_SPECTRUM -> webSocketPlutoConnectionStatus()
            else -> PlutoConnectionStatus(connected = true, diagnostic = "pluto_monitor: mode ${snapshot.mode} is not monitored")
        }
    }

    private fun directUsbPlutoConnectionStatus(): PlutoConnectionStatus {
        val usbManager = getSystemService(UsbManager::class.java)
        val device = findPlutoUsbDevice(usbManager)
            ?: return PlutoConnectionStatus(
                connected = false,
                diagnostic = "pluto_usb: device not found",
            )
        if (!usbManager.hasPermission(device)) {
            return PlutoConnectionStatus(
                connected = false,
                diagnostic = "pluto_usb: Android USB permission missing for ${usbDeviceLabel(device)}",
            )
        }
        if (findPlutoIioInterface(device) == null) {
            return PlutoConnectionStatus(
                connected = false,
                diagnostic = "pluto_usb: IIO interface missing for ${usbDeviceLabel(device)}",
            )
        }
        return PlutoConnectionStatus(
            connected = true,
            diagnostic = "pluto_usb: connected ${usbDeviceLabel(device)}",
        )
    }

    private fun webSocketPlutoConnectionStatus(): PlutoConnectionStatus {
        val ethernetSelection = findEthernetNetworkWithDiagnostics()
        if (ethernetSelection.network == null || ethernetSelection.network.networkHandle == 0L) {
            return PlutoConnectionStatus(
                connected = false,
                diagnostic = "pluto_ws: Android Ethernet network missing\n${ethernetSelection.diagnostics}",
            )
        }
        return PlutoConnectionStatus(
            connected = true,
            diagnostic = "pluto_ws: Ethernet network ${networkLabel(ethernetSelection.network)}",
        )
    }

    private fun isPlutoSessionActive(snapshot: PlutoSessionRecovery): Boolean {
        return when (snapshot.mode) {
            ActiveMode.PLUTO_USB_PLAYBACK,
            ActiveMode.PLUTO_WS_PLAYBACK -> playbackRunning
            ActiveMode.PLUTO_USB_SPECTRUM,
            ActiveMode.PLUTO_WS_SPECTRUM -> spectrumRunning
            else -> false
        }
    }

    private fun recoverPlutoAfterLongAbsence(
        snapshot: PlutoSessionRecovery,
        status: PlutoConnectionStatus,
        absentMs: Long,
    ) {
        runOnMainThreadBlocking {
            if (currentPlutoSessionRecovery() != snapshot) {
                return@runOnMainThreadBlocking
            }
            binding.sampleText.text = "Pluto absent for ${absentMs / 1000}s. Power cycling USB OTG.\n\n${status.diagnostic}"
            stopPlutoSessionForRecovery(snapshot)
        }
        Thread.sleep(PLUTO_SESSION_CLOSE_GRACE_MS)

        val result = usbOtgPowerController.powerCycleUsbOtg(
            powerOffMs = PLUTO_OTG_POWER_OFF_MS,
            settleMs = PLUTO_OTG_POWER_ON_SETTLE_MS,
        )

        mainHandler.post {
            plutoRecoveryInProgress = false
            synchronized(this) {
                plutoMissingSinceMs = null
            }
            if (currentPlutoSessionRecovery() != snapshot) {
                return@post
            }
            binding.sampleText.text = buildString {
                append("Pluto USB OTG recovery finished.\n")
                append("power_cycle_success: ${result.success}\n")
                append(result.message)
                append("\n\nRestarting ${snapshot.mode.label()}.")
            }
            restartPlutoSession(snapshot)
        }
    }

    private fun runOnMainThreadBlocking(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        latch.await(PLUTO_MAIN_THREAD_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopPlutoSessionForRecovery(snapshot: PlutoSessionRecovery) {
        stoppingForPlutoRecovery = true
        try {
            when (snapshot.mode) {
                ActiveMode.PLUTO_USB_PLAYBACK,
                ActiveMode.PLUTO_WS_PLAYBACK -> stopPlayback()
                ActiveMode.PLUTO_USB_SPECTRUM,
                ActiveMode.PLUTO_WS_SPECTRUM -> stopSpectrum()
                else -> Unit
            }
            synchronized(this) {
                plutoSessionRecovery = snapshot
            }
        } finally {
            stoppingForPlutoRecovery = false
        }
    }

    private fun restartPlutoSession(snapshot: PlutoSessionRecovery) {
        when (snapshot.mode) {
            ActiveMode.PLUTO_USB_PLAYBACK -> preparePlutoUsbPlayback(snapshot.standard, snapshot.config)
            ActiveMode.PLUTO_USB_SPECTRUM -> preparePlutoUsbSpectrum(snapshot.config)
            ActiveMode.PLUTO_WS_PLAYBACK -> preparePlutoWebSocketPlayback(snapshot.standard, snapshot.config)
            ActiveMode.PLUTO_WS_SPECTRUM -> preparePlutoWebSocketSpectrum(snapshot.config)
            else -> Unit
        }
    }

    private fun handlePlutoRuntimeFailure(title: String, details: String) {
        val snapshot = currentPlutoSessionRecovery()
        if (snapshot == null) {
            binding.sampleText.text = "$title\n\n$details"
            return
        }

        stopPlutoSessionForRecovery(snapshot)
        synchronized(this) {
            plutoSessionRecovery = snapshot
            plutoMissingSinceMs = SystemClock.elapsedRealtime()
        }
        binding.sampleText.text = "$title\n\n$details\n\nPluto monitor will retry when the connection returns. " +
            "If it remains absent for ${PLUTO_CONNECTION_ABSENT_POWER_CYCLE_MS / 1000}s, USB OTG power-cycle recovery will run."
    }

    private fun metadataFileFor(iqPath: String): File {
        val iqFile = File(iqPath)
        val dotIndex = iqFile.name.lastIndexOf('.')
        val stem = if (dotIndex > 0) iqFile.name.substring(0, dotIndex) else iqFile.name
        val metadataName = "$stem.json"
        val parent = iqFile.parentFile
        return if (parent != null) File(parent, metadataName) else File(metadataName)
    }

    private fun defaultIqPathForConfig(): String {
        val fileName = when (plutoIqConfig.sampleFormat) {
            IqSampleFormat.CS16 -> "input.cs16"
            IqSampleFormat.CS8 -> "input.cs8"
        }
        return File(getExternalFilesDir(null), fileName).absolutePath
    }

    private fun loadIqMetadata(iqPath: String): IQMetadata {
        val metadataFile = metadataFileFor(iqPath)
        if (!metadataFile.exists()) {
            return IQMetadata(
                sidecarPath = metadataFile.absolutePath,
                format = formatFromFileExtension(iqPath),
            )
        }

        return runCatching {
            val json = JSONObject(metadataFile.readText())
            IQMetadata(
                sidecarPath = metadataFile.absolutePath,
                sidecarFound = true,
                format = json.optStringOrNull("format") ?: formatFromFileExtension(iqPath),
                endianness = json.optStringOrNull("endianness"),
                sampleRateHz = json.optLongOrNull("sample_rate_hz"),
                centerFrequencyHz = json.optLongOrNull("center_frequency_hz"),
                rfBandwidthHz = json.optLongOrNull("rf_bandwidth_hz"),
                loOffsetHz = json.optLongOrNull("lo_offset_hz"),
                actualLoFrequencyHz = json.optLongOrNull("actual_lo_frequency_hz"),
                gainDb = json.optDoubleOrNull("gain_db"),
                hardwareIqCorrection = json.optBooleanOrNull("hardware_iq_correction"),
                hardwareBbdcCorrection = json.optBooleanOrNull("hardware_bbdc_correction"),
                hardwareRfdcCorrection = json.optBooleanOrNull("hardware_rfdc_correction"),
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

    private fun livePlutoMetadata(
        uri: String,
        requestedStandard: VideoStandard,
        config: PlutoIqConfig,
    ): IQMetadata {
        return IQMetadata(
            sidecarPath = "live",
            sidecarFound = true,
            format = config.sampleFormat.metadataValue,
            endianness = "little",
            sampleRateHz = config.sampleRateHz,
            centerFrequencyHz = config.centerFrequencyHz,
            rfBandwidthHz = config.rfBandwidthHz,
            loOffsetHz = config.loOffsetHz,
            actualLoFrequencyHz = config.actualLoFrequencyHz(),
            gainDb = config.gainDb,
            hardwareIqCorrection = config.hardwareIqCorrection,
            hardwareBbdcCorrection = config.hardwareBbdcCorrection,
            hardwareRfdcCorrection = config.hardwareRfdcCorrection,
            source = "pluto_iio_usb_live",
            device = "PlutoSDR $uri",
            requestedVideoStandard = requestedStandard,
        )
    }

    private fun liveMaiaMetadata(
        requestedStandard: VideoStandard,
        config: PlutoIqConfig,
    ): IQMetadata {
        return IQMetadata(
            sidecarPath = "live",
            sidecarFound = true,
            format = IqSampleFormat.CS8.metadataValue,
            endianness = "little",
            sampleRateHz = config.sampleRateHz,
            centerFrequencyHz = config.centerFrequencyHz,
            rfBandwidthHz = config.rfBandwidthHz,
            source = "maia_http_cs8_live",
            device = "Maia SDR http://${config.maiaEndpoint()}",
            requestedVideoStandard = requestedStandard,
        )
    }

    private fun livePlutoWebSocketMetadata(
        requestedStandard: VideoStandard,
        config: PlutoIqConfig,
    ): IQMetadata {
        return IQMetadata(
            sidecarPath = "live",
            sidecarFound = true,
            format = IqSampleFormat.CS8.metadataValue,
            endianness = "iqiq",
            sampleRateHz = config.sampleRateHz,
            centerFrequencyHz = config.centerFrequencyHz,
            rfBandwidthHz = config.rfBandwidthHz,
            source = "pluto_websocket_cs8_live",
            device = "PlutoSDR ws://${config.plutoWebSocketEndpoint()}${config.plutoWebSocketPath}",
            requestedVideoStandard = requestedStandard,
        )
    }

    private fun formatFromFileExtension(iqPath: String): String? {
        return when (File(iqPath).extension.lowercase()) {
            "cs8" -> "CS8"
            "cs16" -> "CS16"
            else -> null
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

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return runCatching { getBoolean(name) }.getOrNull()
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
        updateCurrentFrequencyLabel(metadata.centerFrequencyHz ?: plutoIqConfig.centerFrequencyHz)
        val frame = decodeFrame(path, metadata, standard, frameIndex)
        if (frame == null) {
            binding.videoFrameImage.setImageDrawable(null)
            binding.sampleText.text = metadata.toDiagnosticText() +
                "\n\nAnalog FPV video frame decode failed. sample_rate_hz metadata is required and input.cs16 must contain enough IQ samples."
            return
        }

        setVideoFrameBitmap(frame.bitmap)
        binding.sampleText.text = metadata.toDiagnosticText() + "\n\n" + frame.diagnostic
    }

    private fun setVideoFrameBitmap(bitmap: Bitmap) {
        binding.videoFrameContainer.visibility = View.VISIBLE
        binding.videoFrameImage.setImageBitmap(bitmap)
        binding.videoFrameImage.post {
            val availableWidth = binding.videoFrameImage.width
            if (availableWidth <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
                return@post
            }
            val targetHeight = ((availableWidth.toLong() * bitmap.height.toLong()) / bitmap.width.toLong())
                .coerceAtLeast((260 * resources.displayMetrics.density).toLong())
                .toInt()
            val params = binding.videoFrameImage.layoutParams
            if (params.height != targetHeight) {
                params.height = targetHeight
                binding.videoFrameImage.layoutParams = params
            }
        }
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
            sampleFormat = metadata.sampleFormat().nativeValue,
            videoStandard = standard.nativeValue,
            frameIndex = frameIndex,
        )
        return grayscaleFramePacketToBitmap(packet)
    }

    private fun startPlayback(path: String, metadata: IQMetadata, standard: VideoStandard) {
        stopPlayback()
        activeMode = ActiveMode.FILE_PLAYBACK
        updateCurrentFrequencyLabel(metadata.centerFrequencyHz ?: plutoIqConfig.centerFrequencyHz)
        playbackRunning = true
        updateSleepBlocker()
        playbackFrameIndex = 0L
        schedulePlaybackFrame(path, metadata, standard)
    }

    private fun stopPlayback() {
        val previousMode = activeMode
        playbackRunning = false
        if (activeMode.isPlaybackMode()) {
            activeMode = ActiveMode.NONE
        }
        clearPlutoSessionIfManualStop(previousMode)
        updateSleepBlocker()
        mainHandler.removeCallbacksAndMessages(null)
        val sessionToClose = playbackSessionHandle
        playbackSessionHandle = 0L
        val usbConnectionToClose = liveUsbConnection
        liveUsbConnection = null
        if (sessionToClose != 0L || usbConnectionToClose != null) {
            decodeExecutor.execute {
                if (sessionToClose != 0L) {
                    closeAnalogVideoPlaybackSession(sessionToClose)
                }
                usbConnectionToClose?.close()
            }
        }
    }

    private fun stopSpectrum() {
        val previousMode = activeMode
        spectrumRunning = false
        if (activeMode.isSpectrumMode()) {
            activeMode = ActiveMode.NONE
        }
        clearPlutoSessionIfManualStop(previousMode)
        updateSleepBlocker()
        mainHandler.removeCallbacksAndMessages(null)
        val sessionToClose = spectrumSessionHandle
        spectrumSessionHandle = 0L
        val usbConnectionToClose = spectrumUsbConnection
        spectrumUsbConnection = null
        if (sessionToClose != 0L || usbConnectionToClose != null) {
            decodeExecutor.execute {
                if (sessionToClose != 0L) {
                    closeSpectrumSession(sessionToClose)
                }
                usbConnectionToClose?.close()
            }
        }
    }

    private fun schedulePlaybackFrame(
        path: String,
        metadata: IQMetadata,
        standard: VideoStandard,
        createFileSessionIfNeeded: Boolean = true,
    ) {
        if (!playbackRunning) {
            return
        }

        val frameIndex = playbackFrameIndex
        decodeExecutor.execute {
            var sessionHandle = playbackSessionHandle
            if (sessionHandle == 0L) {
                if (!createFileSessionIfNeeded) {
                    mainHandler.post {
                        if (!playbackRunning) {
                            return@post
                        }
                        stopPlayback()
                        binding.sampleText.text = metadata.toDiagnosticText() +
                            "\n\nPlayback stopped: live native playback session is not open."
                    }
                    return@execute
                }
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
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    if (activeMode.isPlutoPlaybackMode()) {
                        handlePlutoRuntimeFailure(
                            title = "Pluto playback lost connection at frame $frameIndex.",
                            details = metadata.toDiagnosticText() + "\n\nnative_error: $nativeError",
                        )
                    } else {
                        stopPlayback()
                        binding.sampleText.text = metadata.toDiagnosticText() +
                            "\n\nPlayback stopped: decoder returned no frame at index $frameIndex\n\n" +
                            "native_error: $nativeError"
                    }
                    return@post
                }

                setVideoFrameBitmap(frame.bitmap)
                if ((frameIndex % PLAYBACK_DIAGNOSTIC_EVERY_FRAMES) == 0L) {
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nplayback_frame_index: $frameIndex\n" + frame.diagnostic
                }
                playbackFrameIndex = frameIndex + 1L
                mainHandler.postDelayed(
                    { schedulePlaybackFrame(path, metadata, standard, createFileSessionIfNeeded) },
                    PLAYBACK_DELAY_MS,
                )
            }
        }
    }

    private fun scheduleSpectrumFrame() {
        if (!spectrumRunning) {
            return
        }

        val frameIndex = spectrumFrameIndex
        decodeExecutor.execute {
            val sessionHandle = spectrumSessionHandle
            if (sessionHandle == 0L) {
                mainHandler.post {
                    if (!spectrumRunning) {
                        return@post
                    }
                    stopSpectrum()
                    binding.sampleText.text = "FFT / waterfall stopped: native spectrum session is not open."
                }
                return@execute
            }

            val frame = grayscaleFramePacketToBitmap(
                packet = decodeNextSpectrumFrame(sessionHandle),
                includeDiagnostic = (frameIndex % SPECTRUM_DIAGNOSTIC_EVERY_FRAMES) == 0L,
            )
            mainHandler.post {
                if (!spectrumRunning) {
                    return@post
                }
                if (frame == null) {
                    if (activeMode.isPlutoSpectrumMode()) {
                        handlePlutoRuntimeFailure(
                            title = "Pluto FFT / waterfall lost connection at frame $frameIndex.",
                            details = "native_error: ${consumeLastNativeError().ifBlank { "unavailable" }}",
                        )
                    } else {
                        stopSpectrum()
                        binding.sampleText.text = "FFT / waterfall stopped: native spectrum frame was empty."
                    }
                    return@post
                }

                setVideoFrameBitmap(frame.bitmap)
                if ((frameIndex % SPECTRUM_DIAGNOSTIC_EVERY_FRAMES) == 0L) {
                    binding.sampleText.text = frame.diagnostic
                }
                spectrumFrameIndex = frameIndex + 1L
                mainHandler.postDelayed(
                    { scheduleSpectrumFrame() },
                    SPECTRUM_DELAY_MS,
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
            sampleFormat = metadata.sampleFormat().nativeValue,
            videoStandard = standard.nativeValue,
        )
    }

    private fun preparePlutoUsbCapture(
        path: String,
        config: PlutoIqConfig = plutoIqConfig,
    ) {
        if (!isPlutoUsbCaptureAvailable()) {
            binding.sampleText.text = "Pluto direct USB capture is not available in this APK.\n\n" +
                plutoUsbDiagnosticText() + "\n\n" +
                "Rebuild/package libiio with the USB backend and libusb support."
            return
        }

        val usbManager = getSystemService(UsbManager::class.java)
        val plutoDevice = findPlutoUsbDevice(usbManager)
        if (plutoDevice == null) {
            binding.sampleText.text = "Pluto USB device was not found.\n\n" +
                connectedUsbDevicesDiagnostic(usbManager) + "\n\n" +
                plutoUsbDiagnosticText()
            return
        }

        if (usbManager.hasPermission(plutoDevice)) {
            startPlutoUsbCapture(path, plutoDevice, config)
            return
        }

        pendingPlutoCapturePath = path
        pendingPlutoUsbAction = PlutoUsbAction.CAPTURE_TO_FILE
        pendingPlutoIqConfig = config
        registerUsbPermissionReceiver()
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            plutoDevice.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            usbPermissionPendingIntentFlags(),
        )
        binding.sampleText.text = "Requesting Android USB permission for PlutoSDR:\n" +
            usbDeviceLabel(plutoDevice)
        usbManager.requestPermission(plutoDevice, permissionIntent)
    }

    private fun preparePlutoUsbPlayback(
        standard: VideoStandard,
        config: PlutoIqConfig,
    ) {
        if (!isPlutoUsbCaptureAvailable()) {
            binding.sampleText.text = "Pluto direct USB playback is not available in this APK.\n\n" +
                plutoUsbDiagnosticText() + "\n\n" +
                "Rebuild/package libiio with the USB backend and libusb support."
            return
        }

        val usbManager = getSystemService(UsbManager::class.java)
        val plutoDevice = findPlutoUsbDevice(usbManager)
        if (plutoDevice == null) {
            binding.sampleText.text = "Pluto USB device was not found.\n\n" +
                connectedUsbDevicesDiagnostic(usbManager) + "\n\n" +
                plutoUsbDiagnosticText()
            return
        }

        if (usbManager.hasPermission(plutoDevice)) {
            startPlutoUsbPlayback(plutoDevice, standard, config)
            return
        }

        pendingPlutoCapturePath = null
        pendingPlutoUsbAction = PlutoUsbAction.LIVE_PLAYBACK
        pendingPlutoVideoStandard = standard
        pendingPlutoIqConfig = config
        registerUsbPermissionReceiver()
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            plutoDevice.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            usbPermissionPendingIntentFlags(),
        )
        binding.sampleText.text = "Requesting Android USB permission for PlutoSDR live playback:\n" +
            usbDeviceLabel(plutoDevice)
        usbManager.requestPermission(plutoDevice, permissionIntent)
    }

    private fun preparePlutoUsbSpectrum(config: PlutoIqConfig) {
        if (!isPlutoUsbCaptureAvailable()) {
            binding.sampleText.text = "Pluto direct USB spectrum view is not available in this APK.\n\n" +
                plutoUsbDiagnosticText() + "\n\n" +
                "Rebuild/package libiio with the USB backend and libusb support."
            return
        }

        val usbManager = getSystemService(UsbManager::class.java)
        val plutoDevice = findPlutoUsbDevice(usbManager)
        if (plutoDevice == null) {
            binding.sampleText.text = "Pluto USB device was not found.\n\n" +
                connectedUsbDevicesDiagnostic(usbManager) + "\n\n" +
                plutoUsbDiagnosticText()
            return
        }

        if (usbManager.hasPermission(plutoDevice)) {
            startPlutoUsbSpectrum(plutoDevice, config)
            return
        }

        pendingPlutoCapturePath = null
        pendingPlutoUsbAction = PlutoUsbAction.SPECTRUM_VIEW
        pendingPlutoIqConfig = config
        registerUsbPermissionReceiver()
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            plutoDevice.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            usbPermissionPendingIntentFlags(),
        )
        binding.sampleText.text = "Requesting Android USB permission for PlutoSDR spectrum view:\n" +
            usbDeviceLabel(plutoDevice)
        usbManager.requestPermission(plutoDevice, permissionIntent)
    }

    private fun startPlutoUsbCapture(
        path: String,
        device: UsbDevice,
        config: PlutoIqConfig,
    ) {
        activeMode = ActiveMode.RECORD_IQ
        updateSleepBlocker()
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        val usbManager = getSystemService(UsbManager::class.java)
        val iioInterface = findPlutoIioInterface(device)
        if (iioInterface == null) {
            activeMode = ActiveMode.NONE
            updateSleepBlocker()
            binding.sampleText.text = "Pluto USB IIO interface was not found.\n\n" +
                usbDeviceLabel(device) + "\n" +
                usbInterfacesDiagnostic(device)
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            activeMode = ActiveMode.NONE
            updateSleepBlocker()
            binding.sampleText.text = "Failed to open Pluto USB device after Android permission grant.\n\n" +
                usbDeviceLabel(device) + "\n" +
                usbInterfacesDiagnostic(device) + "\n\n" +
                plutoUsbDiagnosticText()
            return
        }

        val fd = connection.fileDescriptor
        if (fd < 0) {
            connection.close()
            activeMode = ActiveMode.NONE
            updateSleepBlocker()
            binding.sampleText.text = "Android returned an invalid Pluto USB file descriptor: $fd\n\n" +
                usbDeviceLabel(device)
            return
        }

        capturePlutoIq(
            path,
            "usb:fd:$fd",
            connection,
            config,
        )
    }

    private fun startPlutoUsbPlayback(
        device: UsbDevice,
        standard: VideoStandard,
        config: PlutoIqConfig,
    ) {
        activeMode = ActiveMode.PLUTO_USB_PLAYBACK
        rememberPlutoSession(ActiveMode.PLUTO_USB_PLAYBACK, standard, config)
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        val usbManager = getSystemService(UsbManager::class.java)
        val iioInterface = findPlutoIioInterface(device)
        if (iioInterface == null) {
            binding.sampleText.text = "Pluto USB IIO interface was not found.\n\n" +
                usbDeviceLabel(device) + "\n" +
                usbInterfacesDiagnostic(device)
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            binding.sampleText.text = "Failed to open Pluto USB device after Android permission grant.\n\n" +
                usbDeviceLabel(device) + "\n" +
                usbInterfacesDiagnostic(device) + "\n\n" +
                plutoUsbDiagnosticText()
            return
        }

        val fd = connection.fileDescriptor
        if (fd < 0) {
            connection.close()
            binding.sampleText.text = "Android returned an invalid Pluto USB file descriptor: $fd\n\n" +
                usbDeviceLabel(device)
            return
        }

        val uri = "usb:fd:$fd"
        val metadata = livePlutoMetadata(uri, standard, config)
        playbackRunning = true
        updateSleepBlocker()
        playbackFrameIndex = 0L
        binding.sampleText.text = "Opening Pluto live playback:\n$uri"
        decodeExecutor.execute {
            val sessionHandle = createPlutoAnalogVideoPlaybackSession(
                uri = uri,
                sampleRateHz = config.sampleRateHz,
                centerFrequencyHz = config.centerFrequencyHz,
                rfBandwidthHz = config.rfBandwidthHz,
                gainDb = config.gainDb,
                sampleFormat = config.sampleFormat.nativeValue,
                loOffsetHz = config.loOffsetHz,
                hardwareIqCorrection = config.hardwareIqCorrection,
                hardwareBbdcCorrection = config.hardwareBbdcCorrection,
                hardwareRfdcCorrection = config.hardwareRfdcCorrection,
                videoStandard = standard.nativeValue,
            )
            mainHandler.post {
                if (!playbackRunning) {
                    if (sessionHandle != 0L) {
                        decodeExecutor.execute {
                            closeAnalogVideoPlaybackSession(sessionHandle)
                            connection.close()
                        }
                    } else {
                        connection.close()
                    }
                    return@post
                }

                if (sessionHandle == 0L) {
                    playbackRunning = false
                    updateSleepBlocker()
                    connection.close()
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nPlayback stopped: native Pluto live playback session could not be created.\n\n" +
                        "native_error: $nativeError\n\n" +
                        plutoUsbDiagnosticText()
                    return@post
                }

                playbackSessionHandle = sessionHandle
                liveUsbConnection = connection
                binding.sampleText.text = metadata.toDiagnosticText() +
                    "\n\nPluto live playback started."
                schedulePlaybackFrame(
                    path = "",
                    metadata = metadata,
                    standard = standard,
                    createFileSessionIfNeeded = false,
                )
            }
        }
    }

    private fun startPlutoUsbSpectrum(device: UsbDevice, config: PlutoIqConfig) {
        activeMode = ActiveMode.PLUTO_USB_SPECTRUM
        rememberPlutoSession(ActiveMode.PLUTO_USB_SPECTRUM, VideoStandard.AUTO, config)
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        val usbManager = getSystemService(UsbManager::class.java)
        val iioInterface = findPlutoIioInterface(device)
        if (iioInterface == null) {
            binding.sampleText.text = "Pluto USB IIO interface was not found.\n\n" +
                usbDeviceLabel(device) + "\n" +
                usbInterfacesDiagnostic(device)
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            binding.sampleText.text = "Failed to open Pluto USB device after Android permission grant.\n\n" +
                usbDeviceLabel(device) + "\n" +
                usbInterfacesDiagnostic(device) + "\n\n" +
                plutoUsbDiagnosticText()
            return
        }

        val fd = connection.fileDescriptor
        if (fd < 0) {
            connection.close()
            binding.sampleText.text = "Android returned an invalid Pluto USB file descriptor: $fd\n\n" +
                usbDeviceLabel(device)
            return
        }

        val uri = "usb:fd:$fd"
        spectrumRunning = true
        updateSleepBlocker()
        spectrumFrameIndex = 0L
        binding.sampleText.text = "Opening Pluto FFT / waterfall:\n$uri"
        decodeExecutor.execute {
            val sessionHandle = createPlutoSpectrumSession(
                uri = uri,
                sampleRateHz = config.sampleRateHz,
                centerFrequencyHz = config.centerFrequencyHz,
                rfBandwidthHz = config.rfBandwidthHz,
                gainDb = config.gainDb,
                sampleFormat = config.sampleFormat.nativeValue,
                loOffsetHz = config.loOffsetHz,
                hardwareIqCorrection = config.hardwareIqCorrection,
                hardwareBbdcCorrection = config.hardwareBbdcCorrection,
                hardwareRfdcCorrection = config.hardwareRfdcCorrection,
            )
            mainHandler.post {
                if (!spectrumRunning) {
                    if (sessionHandle != 0L) {
                        decodeExecutor.execute {
                            closeSpectrumSession(sessionHandle)
                            connection.close()
                        }
                    } else {
                        connection.close()
                    }
                    return@post
                }

                if (sessionHandle == 0L) {
                    spectrumRunning = false
                    updateSleepBlocker()
                    connection.close()
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    binding.sampleText.text = "FFT / waterfall stopped: native Pluto spectrum session could not be created.\n\n" +
                        "native_error: $nativeError\n\n" +
                        config.toDiagnosticText() + "\n\n" +
                        plutoUsbDiagnosticText()
                    return@post
                }

                spectrumSessionHandle = sessionHandle
                spectrumUsbConnection = connection
                binding.sampleText.text = "FFT / waterfall started.\n" + config.toDiagnosticText()
                scheduleSpectrumFrame()
            }
        }
    }

    private fun testMaiaConnection(config: PlutoIqConfig) {
        binding.sampleText.text = "Testing Maia Ethernet connection:\nhttp://${config.maiaEndpoint()}$MAIA_RECORDER_PATH"
        decodeExecutor.execute {
            val report = StringBuilder()
            report.append("Maia Ethernet connection test\n")
            report.append("url: http://${config.maiaEndpoint()}$MAIA_RECORDER_PATH\n")
            report.append("connect_timeout_ms: $MAIA_CONNECT_TIMEOUT_MS\n")
            report.append("read_timeout_ms: $MAIA_READ_TIMEOUT_MS\n\n")

            val selection = findEthernetNetworkWithDiagnostics()
            report.append(selection.diagnostics)
            val ethernetNetwork = selection.network
            if (ethernetNetwork == null) {
                report.append("\nTest not executed: no Ethernet network was found.")
            } else {
                val openConnectionResult = testMaiaWithOpenConnection(ethernetNetwork, config)
                report.append("\n\n")
                report.append(openConnectionResult.diagnostics)
            }

            mainHandler.post {
                binding.sampleText.text = report.toString()
            }
        }
    }

    private fun findEthernetNetworkWithDiagnostics(): EthernetNetworkSelection {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val diagnostics = StringBuilder()
        val activeNetwork = connectivityManager.activeNetwork
        val networks = connectivityManager.allNetworks
        var ethernetNetwork: Network? = null

        diagnostics.append("Android network diagnostics\n")
        diagnostics.append("active_network: ${networkLabel(activeNetwork)}\n")
        diagnostics.append("available_network_count: ${networks.size}\n")

        networks.forEachIndexed { index, network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)
            appendNetworkDiagnostics(
                output = diagnostics,
                index = index,
                network = network,
                capabilities = capabilities,
                linkProperties = linkProperties,
            )
            if (ethernetNetwork == null &&
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            ) {
                ethernetNetwork = network
            }
        }

        diagnostics.append("\nethernet_network_found: ${ethernetNetwork != null}\n")
        diagnostics.append("ethernet_network: ${networkLabel(ethernetNetwork)}\n")
        ethernetNetwork?.let { network ->
            diagnostics.append("ethernet_network_handle: ${network.networkHandle}\n")
            connectivityManager.getLinkProperties(network)?.interfaceName?.let { interfaceName ->
                diagnostics.append("ethernet_interface_name: $interfaceName\n")
            }
        }

        logMultiline(diagnostics.toString())
        return EthernetNetworkSelection(ethernetNetwork, diagnostics.toString())
    }

    private fun appendNetworkDiagnostics(
        output: StringBuilder,
        index: Int,
        network: Network,
        capabilities: NetworkCapabilities?,
        linkProperties: LinkProperties?,
    ) {
        output.append("\nnetwork[$index]: ${networkLabel(network)}\n")
        output.append("  handle: ${network.networkHandle}\n")
        output.append("  capabilities: ${capabilities ?: "unavailable"}\n")
        output.append("  has_ethernet_transport: ${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true}\n")
        output.append("  link_properties: ${linkProperties ?: "unavailable"}\n")
        output.append("  interface_name: ${linkProperties?.interfaceName ?: "unavailable"}\n")
        output.append("  ipv4_addresses: ${ipv4Addresses(linkProperties).ifEmpty { "none" }}\n")
        output.append("  routes: ${linkProperties?.routes?.joinToString(separator = " | ") ?: "unavailable"}\n")
        output.append("  dns_servers: ${linkProperties?.dnsServers?.joinToString(separator = ", ") ?: "unavailable"}\n")
    }

    private fun ipv4Addresses(linkProperties: LinkProperties?): String {
        return linkProperties
            ?.linkAddresses
            ?.mapNotNull { linkAddress ->
                val address = linkAddress.address
                if (address is Inet4Address) {
                    "${address.hostAddress}/${linkAddress.prefixLength}"
                } else {
                    null
                }
            }
            ?.joinToString(separator = ", ")
            .orEmpty()
    }

    private fun testMaiaWithOpenConnection(
        network: Network,
        config: PlutoIqConfig,
    ): MaiaHttpConnectionTestResult {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val url = URL("http://${config.maiaEndpoint()}$MAIA_RECORDER_PATH")
        val diagnostics = StringBuilder()
        diagnostics.append("Network.openConnection test\n")
        diagnostics.append("active_network: ${networkLabel(connectivityManager.activeNetwork)}\n")
        diagnostics.append("bound_network: ${networkLabel(network)}\n")
        diagnostics.append("bound_network_handle: ${network.networkHandle}\n")
        diagnostics.append("socket_local_address: unavailable through HttpURLConnection\n")
        diagnostics.append("socket_remote_address: unavailable through HttpURLConnection\n")

        var connection: HttpURLConnection? = null
        return try {
            connection = network.openConnection(url) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = MAIA_CONNECT_TIMEOUT_MS
            connection.readTimeout = MAIA_READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doInput = true
            connection.setRequestProperty("Accept", "application/json,*/*")
            connection.setRequestProperty("Connection", "close")

            val responseCode = connection.responseCode
            diagnostics.append("http_response_code: $responseCode\n")
            diagnostics.append("http_response_headers: ${httpHeadersText(connection)}\n")
            diagnostics.append("response_body:\n")
            diagnostics.append(readHttpBody(connection))
            MaiaHttpConnectionTestResult(connectionEstablished = true, diagnostics = diagnostics.toString())
        } catch (error: SocketTimeoutException) {
            diagnostics.append("timeout: true\n")
            diagnostics.append("exception_message: ${error.message ?: error.javaClass.name}\n")
            diagnostics.append("exception_stack_trace:\n${stackTraceText(error)}")
            MaiaHttpConnectionTestResult(connectionEstablished = false, diagnostics = diagnostics.toString())
        } catch (error: Exception) {
            diagnostics.append("timeout: false\n")
            diagnostics.append("exception_message: ${error.message ?: error.javaClass.name}\n")
            diagnostics.append("exception_stack_trace:\n${stackTraceText(error)}")
            MaiaHttpConnectionTestResult(connectionEstablished = false, diagnostics = diagnostics.toString())
        } finally {
            connection?.disconnect()
        }
    }

    private fun readHttpBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            connection.errorStream
        } else {
            connection.inputStream
        }
        return stream?.use { input ->
            readLimited(input, MAIA_TEST_RESPONSE_LIMIT_BYTES).toString(Charsets.UTF_8)
        }.orEmpty()
    }

    private fun httpHeadersText(connection: HttpURLConnection): String {
        return connection.headerFields.entries.joinToString(separator = "; ") { (name, values) ->
            "${name ?: "status"}=${values.joinToString(separator = ",")}"
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() < maxBytes) {
            val maxRead = minOf(buffer.size, maxBytes - output.size())
            val count = input.read(buffer, 0, maxRead)
            if (count <= 0) {
                break
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun networkLabel(network: Network?): String {
        return network?.toString() ?: "none"
    }

    private fun stackTraceText(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun logMultiline(message: String) {
        message.lineSequence().forEach { line ->
            Log.i(LOG_TAG, line)
        }
    }

    private fun startMaiaPlayback(standard: VideoStandard, config: PlutoIqConfig) {
        val metadata = liveMaiaMetadata(standard, config)
        activeMode = ActiveMode.MAIA_PLAYBACK
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        playbackRunning = true
        updateSleepBlocker()
        playbackFrameIndex = 0L
        binding.sampleText.text = "Opening Maia CS8 live playback:\nhttp://${config.maiaEndpoint()}"
        decodeExecutor.execute {
            val ethernetSelection = findEthernetNetworkWithDiagnostics()
            val ethernetNetwork = ethernetSelection.network
            if (ethernetNetwork == null || ethernetNetwork.networkHandle == 0L) {
                mainHandler.post {
                    playbackRunning = false
                    updateSleepBlocker()
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nPlayback stopped: Android Ethernet network was not found.\n\n" +
                        ethernetSelection.diagnostics
                }
                return@execute
            }

            val transport = MaiaHttpTransport.create(
                network = ethernetNetwork,
                host = config.maiaHost,
                port = config.maiaPort,
                connectTimeoutMs = MAIA_CONNECT_TIMEOUT_MS,
                readTimeoutMs = MAIA_READ_TIMEOUT_MS,
            )
            val sessionHandle = createMaiaAnalogVideoPlaybackSession(
                host = config.maiaHost,
                port = config.maiaPort,
                sampleRateHz = config.sampleRateHz,
                videoStandard = standard.nativeValue,
                androidHttpTransport = transport,
            )
            mainHandler.post {
                if (!playbackRunning) {
                    if (sessionHandle != 0L) {
                        decodeExecutor.execute {
                            closeAnalogVideoPlaybackSession(sessionHandle)
                        }
                    } else {
                        decodeExecutor.execute {
                            transport.close()
                        }
                    }
                    return@post
                }

                if (sessionHandle == 0L) {
                    playbackRunning = false
                    updateSleepBlocker()
                    decodeExecutor.execute {
                        transport.close()
                    }
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nPlayback stopped: native Maia CS8 playback session could not be created.\n\n" +
                        "native_error: $nativeError\n\n" +
                        ethernetSelection.diagnostics
                    return@post
                }

                playbackSessionHandle = sessionHandle
                binding.sampleText.text = metadata.toDiagnosticText() +
                    "\n\nMaia CS8 live playback started.\n" +
                    "android_ethernet_network: ${networkLabel(ethernetNetwork)}\n" +
                    "android_ethernet_network_handle: ${ethernetNetwork.networkHandle}"
                schedulePlaybackFrame(
                    path = "",
                    metadata = metadata,
                    standard = standard,
                    createFileSessionIfNeeded = false,
                )
            }
        }
    }

    private fun startMaiaSpectrum(config: PlutoIqConfig) {
        activeMode = ActiveMode.MAIA_SPECTRUM
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        spectrumRunning = true
        updateSleepBlocker()
        spectrumFrameIndex = 0L
        binding.sampleText.text = "Opening Maia CS8 FFT / waterfall:\nhttp://${config.maiaEndpoint()}"
        decodeExecutor.execute {
            val ethernetSelection = findEthernetNetworkWithDiagnostics()
            val ethernetNetwork = ethernetSelection.network
            if (ethernetNetwork == null || ethernetNetwork.networkHandle == 0L) {
                mainHandler.post {
                    spectrumRunning = false
                    updateSleepBlocker()
                    binding.sampleText.text = "FFT / waterfall stopped: Android Ethernet network was not found.\n\n" +
                        ethernetSelection.diagnostics
                }
                return@execute
            }

            val transport = MaiaHttpTransport.create(
                network = ethernetNetwork,
                host = config.maiaHost,
                port = config.maiaPort,
                connectTimeoutMs = MAIA_CONNECT_TIMEOUT_MS,
                readTimeoutMs = MAIA_READ_TIMEOUT_MS,
            )
            val sessionHandle = createMaiaSpectrumSession(
                host = config.maiaHost,
                port = config.maiaPort,
                sampleRateHz = config.sampleRateHz,
                centerFrequencyHz = config.centerFrequencyHz,
                androidHttpTransport = transport,
            )
            mainHandler.post {
                if (!spectrumRunning) {
                    if (sessionHandle != 0L) {
                        decodeExecutor.execute {
                            closeSpectrumSession(sessionHandle)
                        }
                    } else {
                        decodeExecutor.execute {
                            transport.close()
                        }
                    }
                    return@post
                }

                if (sessionHandle == 0L) {
                    spectrumRunning = false
                    updateSleepBlocker()
                    decodeExecutor.execute {
                        transport.close()
                    }
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    binding.sampleText.text = "FFT / waterfall stopped: native Maia CS8 spectrum session could not be created.\n\n" +
                        "native_error: $nativeError\n\n" +
                        liveMaiaMetadata(VideoStandard.AUTO, config).toDiagnosticText() + "\n\n" +
                        ethernetSelection.diagnostics
                    return@post
                }

                spectrumSessionHandle = sessionHandle
                binding.sampleText.text = "Maia CS8 FFT / waterfall started.\n" +
                    liveMaiaMetadata(VideoStandard.AUTO, config).toDiagnosticText() + "\n" +
                    "android_ethernet_network: ${networkLabel(ethernetNetwork)}\n" +
                    "android_ethernet_network_handle: ${ethernetNetwork.networkHandle}"
                scheduleSpectrumFrame()
            }
        }
    }

    private fun preparePlutoWebSocketPlayback(
        standard: VideoStandard,
        config: PlutoIqConfig,
    ) {
        if (requestPlutoUsbPermissionForNetworkIfNeeded(
                action = PlutoUsbAction.WEB_SOCKET_PLAYBACK,
                standard = standard,
                config = config,
                description = "Pluto WebSocket playback",
            )
        ) {
            return
        }
        startPlutoWebSocketPlayback(standard, config)
    }

    private fun preparePlutoWebSocketSpectrum(config: PlutoIqConfig) {
        if (requestPlutoUsbPermissionForNetworkIfNeeded(
                action = PlutoUsbAction.WEB_SOCKET_SPECTRUM,
                standard = VideoStandard.AUTO,
                config = config,
                description = "Pluto WebSocket FFT / waterfall",
            )
        ) {
            return
        }
        startPlutoWebSocketSpectrum(config)
    }

    private fun requestPlutoUsbPermissionForNetworkIfNeeded(
        action: PlutoUsbAction,
        standard: VideoStandard,
        config: PlutoIqConfig,
        description: String,
    ): Boolean {
        val usbManager = getSystemService(UsbManager::class.java)
        val plutoDevice = findPlutoUsbDevice(usbManager) ?: return false
        if (usbManager.hasPermission(plutoDevice)) {
            return false
        }

        pendingPlutoCapturePath = null
        pendingPlutoUsbAction = action
        pendingPlutoVideoStandard = standard
        pendingPlutoIqConfig = config
        registerUsbPermissionReceiver()
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            plutoDevice.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            usbPermissionPendingIntentFlags(),
        )
        binding.sampleText.text = "Requesting Android USB permission for $description:\n" +
            usbDeviceLabel(plutoDevice) + "\n\n" +
            "The WebSocket IQ stream is still read from ws://${config.plutoWebSocketEndpoint()}${config.plutoWebSocketPath}; " +
            "this permission enables Android's Pluto USB network path on some devices."
        usbManager.requestPermission(plutoDevice, permissionIntent)
        return true
    }

    private fun startPlutoWebSocketPlayback(standard: VideoStandard, config: PlutoIqConfig) {
        val metadata = livePlutoWebSocketMetadata(standard, config)
        activeMode = ActiveMode.PLUTO_WS_PLAYBACK
        rememberPlutoSession(ActiveMode.PLUTO_WS_PLAYBACK, standard, config)
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        playbackRunning = true
        updateSleepBlocker()
        playbackFrameIndex = 0L
        binding.sampleText.text = "Opening Pluto WebSocket CS8 live playback:\nws://${config.plutoWebSocketEndpoint()}${config.plutoWebSocketPath}"
        decodeExecutor.execute {
            val primeDiagnostic = primePlutoUsbForWebSocket(config)
            val ethernetSelection = findEthernetNetworkWithDiagnostics()
            val ethernetNetwork = ethernetSelection.network
            if (ethernetNetwork == null || ethernetNetwork.networkHandle == 0L) {
                mainHandler.post {
                    playbackRunning = false
                    updateSleepBlocker()
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nPlayback stopped: Android Ethernet network was not found.\n\n" +
                        primeDiagnostic + "\n\n" +
                        ethernetSelection.diagnostics
                }
                return@execute
            }

            val transport = PlutoWebSocketTransport.create(
                network = ethernetNetwork,
                host = config.maiaHost,
                port = config.maiaPort,
                path = config.plutoWebSocketPath,
                connectTimeoutMs = PLUTO_WS_CONNECT_TIMEOUT_MS,
                readTimeoutMs = PLUTO_WS_READ_TIMEOUT_MS,
            )
            val sessionHandle = createPlutoWebSocketAnalogVideoPlaybackSession(
                host = config.maiaHost,
                port = config.maiaPort,
                path = config.plutoWebSocketPath,
                sampleRateHz = config.sampleRateHz,
                receiveBufferMs = config.plutoWebSocketReceiveBufferMs,
                videoStandard = standard.nativeValue,
                androidWebSocketTransport = transport,
            )
            mainHandler.post {
                if (!playbackRunning) {
                    if (sessionHandle != 0L) {
                        decodeExecutor.execute {
                            closeAnalogVideoPlaybackSession(sessionHandle)
                        }
                    } else {
                        decodeExecutor.execute {
                            transport.close()
                        }
                    }
                    return@post
                }

                if (sessionHandle == 0L) {
                    playbackRunning = false
                    updateSleepBlocker()
                    decodeExecutor.execute {
                        transport.close()
                    }
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nPlayback stopped: native Pluto WebSocket playback session could not be created.\n\n" +
                        primeDiagnostic + "\n\n" +
                        "native_error: $nativeError\n\n" +
                        ethernetSelection.diagnostics
                    return@post
                }

                playbackSessionHandle = sessionHandle
                binding.sampleText.text = metadata.toDiagnosticText() +
                    "\n\nPluto WebSocket CS8 live playback started.\n" +
                    primeDiagnostic + "\n" +
                    "android_ethernet_network: ${networkLabel(ethernetNetwork)}\n" +
                    "android_ethernet_network_handle: ${ethernetNetwork.networkHandle}"
                schedulePlaybackFrame(
                    path = "",
                    metadata = metadata,
                    standard = standard,
                    createFileSessionIfNeeded = false,
                )
            }
        }
    }

    private fun primePlutoUsbForWebSocket(config: PlutoIqConfig): String {
        val usbManager = getSystemService(UsbManager::class.java)
        val plutoDevice = findPlutoUsbDevice(usbManager)
            ?: return "pluto_usb_prime: skipped, Pluto USB device not found"
        if (!usbManager.hasPermission(plutoDevice)) {
            return "pluto_usb_prime: skipped, Android USB permission is not granted"
        }
        val iioInterface = findPlutoIioInterface(plutoDevice)
            ?: return "pluto_usb_prime: skipped, Pluto USB IIO interface was not found"
        val connection = usbManager.openDevice(plutoDevice)
            ?: return "pluto_usb_prime: failed, could not open Pluto USB device"
        return try {
            val fd = connection.fileDescriptor
            if (fd < 0) {
                "pluto_usb_prime: failed, invalid Android USB file descriptor $fd"
            } else {
                val primeConfig = config.forcedSampleFormat(IqSampleFormat.CS16)
                val sessionHandle = createPlutoAnalogVideoPlaybackSession(
                    uri = "usb:fd:$fd",
                    sampleRateHz = primeConfig.sampleRateHz,
                    centerFrequencyHz = primeConfig.centerFrequencyHz,
                    rfBandwidthHz = primeConfig.rfBandwidthHz,
                    gainDb = primeConfig.gainDb,
                    sampleFormat = primeConfig.sampleFormat.nativeValue,
                    loOffsetHz = primeConfig.loOffsetHz,
                    hardwareIqCorrection = primeConfig.hardwareIqCorrection,
                    hardwareBbdcCorrection = primeConfig.hardwareBbdcCorrection,
                    hardwareRfdcCorrection = primeConfig.hardwareRfdcCorrection,
                    videoStandard = VideoStandard.AUTO.nativeValue,
                )
                if (sessionHandle == 0L) {
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    "pluto_usb_prime: failed, native CS16 libiio session could not be created; native_error: $nativeError"
                } else {
                    closeAnalogVideoPlaybackSession(sessionHandle)
                    "pluto_usb_prime: ok, configured ${formatFrequency(primeConfig.centerFrequencyHz)} through ${iioInterface.usbInterface.name ?: "IIO"}"
                }
            }
        } finally {
            connection.close()
        }
    }

    private fun startPlutoWebSocketSpectrum(config: PlutoIqConfig) {
        activeMode = ActiveMode.PLUTO_WS_SPECTRUM
        rememberPlutoSession(ActiveMode.PLUTO_WS_SPECTRUM, VideoStandard.AUTO, config)
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        spectrumRunning = true
        updateSleepBlocker()
        spectrumFrameIndex = 0L
        binding.sampleText.text = "Opening Pluto WebSocket CS8 FFT / waterfall:\nws://${config.plutoWebSocketEndpoint()}${config.plutoWebSocketPath}"
        decodeExecutor.execute {
            val ethernetSelection = findEthernetNetworkWithDiagnostics()
            val ethernetNetwork = ethernetSelection.network
            if (ethernetNetwork == null || ethernetNetwork.networkHandle == 0L) {
                mainHandler.post {
                    spectrumRunning = false
                    updateSleepBlocker()
                    binding.sampleText.text = "FFT / waterfall stopped: Android Ethernet network was not found.\n\n" +
                        ethernetSelection.diagnostics
                }
                return@execute
            }

            val transport = PlutoWebSocketTransport.create(
                network = ethernetNetwork,
                host = config.maiaHost,
                port = config.maiaPort,
                path = config.plutoWebSocketPath,
                connectTimeoutMs = PLUTO_WS_CONNECT_TIMEOUT_MS,
                readTimeoutMs = PLUTO_WS_READ_TIMEOUT_MS,
            )
            val sessionHandle = createPlutoWebSocketSpectrumSession(
                host = config.maiaHost,
                port = config.maiaPort,
                path = config.plutoWebSocketPath,
                sampleRateHz = config.sampleRateHz,
                centerFrequencyHz = config.centerFrequencyHz,
                receiveBufferMs = config.plutoWebSocketReceiveBufferMs,
                androidWebSocketTransport = transport,
            )
            mainHandler.post {
                if (!spectrumRunning) {
                    if (sessionHandle != 0L) {
                        decodeExecutor.execute {
                            closeSpectrumSession(sessionHandle)
                        }
                    } else {
                        decodeExecutor.execute {
                            transport.close()
                        }
                    }
                    return@post
                }

                if (sessionHandle == 0L) {
                    spectrumRunning = false
                    updateSleepBlocker()
                    decodeExecutor.execute {
                        transport.close()
                    }
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    binding.sampleText.text = "FFT / waterfall stopped: native Pluto WebSocket spectrum session could not be created.\n\n" +
                        "native_error: $nativeError\n\n" +
                        livePlutoWebSocketMetadata(VideoStandard.AUTO, config).toDiagnosticText() + "\n\n" +
                        ethernetSelection.diagnostics
                    return@post
                }

                spectrumSessionHandle = sessionHandle
                binding.sampleText.text = "Pluto WebSocket CS8 FFT / waterfall started.\n" +
                    livePlutoWebSocketMetadata(VideoStandard.AUTO, config).toDiagnosticText() + "\n" +
                    "android_ethernet_network: ${networkLabel(ethernetNetwork)}\n" +
                    "android_ethernet_network_handle: ${ethernetNetwork.networkHandle}"
                scheduleSpectrumFrame()
            }
        }
    }

    private fun usbPermissionPendingIntentFlags(): Int {
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag
    }

    private fun registerUsbPermissionReceiver() {
        if (usbPermissionReceiverRegistered) {
            return
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
        usbPermissionReceiverRegistered = true
    }

    private fun findPlutoUsbDevice(usbManager: UsbManager): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == PLUTO_USB_VENDOR_ID && device.productId == PLUTO_USB_PRODUCT_ID
        }
    }

    private fun findPlutoIioInterface(device: UsbDevice): UsbInterfaceSelection? {
        val interfaces = (0 until device.interfaceCount).map { index ->
            UsbInterfaceSelection(index, device.getInterface(index))
        }
        return interfaces.firstOrNull { selection ->
            selection.usbInterface.name.equals("IIO", ignoreCase = true)
        } ?: interfaces.firstOrNull { selection ->
            selection.usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                hasBulkEndpointPair(selection.usbInterface)
        } ?: interfaces.firstOrNull { selection ->
            hasBulkEndpointPair(selection.usbInterface)
        }
    }

    private fun hasBulkEndpointPair(usbInterface: UsbInterface): Boolean {
        var hasBulkIn = false
        var hasBulkOut = false
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue
            }
            if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                hasBulkIn = true
            } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                hasBulkOut = true
            }
        }
        return hasBulkIn && hasBulkOut
    }

    private fun connectedUsbDevicesDiagnostic(usbManager: UsbManager): String {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            return "connected_usb_devices: none"
        }

        return buildString {
            append("connected_usb_devices:")
            devices.forEach { device ->
                append("\n")
                append(usbDeviceLabel(device))
            }
        }
    }

    private fun usbDeviceLabel(device: UsbDevice): String {
        return "vid=0x${device.vendorId.toString(16).padStart(4, '0')} " +
            "pid=0x${device.productId.toString(16).padStart(4, '0')} " +
            "name=${device.deviceName}"
    }

    private fun usbInterfacesDiagnostic(device: UsbDevice): String {
        return buildString {
            append("usb_interfaces:")
            if (device.interfaceCount == 0) {
                append(" none")
                return@buildString
            }
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                append("\n")
                append("index=$index ")
                append("id=${usbInterface.id} ")
                append("name=${usbInterface.name ?: "null"} ")
                append("class=${usbInterface.interfaceClass} ")
                append("subclass=${usbInterface.interfaceSubclass} ")
                append("protocol=${usbInterface.interfaceProtocol} ")
                append("endpoints=${usbInterface.endpointCount}")
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    append(" ep$endpointIndex=")
                    append(if (endpoint.direction == UsbConstants.USB_DIR_IN) "in" else "out")
                    append("/")
                    append(if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "bulk" else "type${endpoint.type}")
                    append("/0x${endpoint.address.toString(16)}")
                }
            }
        }
    }

    private fun plutoUsbDiagnosticText(): String {
        return getIioBackendDiagnostic()
    }

    private fun capturePlutoIq(
        path: String,
        uri: String,
        usbConnection: UsbDeviceConnection,
        config: PlutoIqConfig,
    ) {
        binding.sampleText.text = "Capturing Pluto IQ to:\n$path"
        decodeExecutor.execute {
            val diagnostic = try {
                capturePlutoIqToFile(
                    outputPath = path,
                    uri = uri,
                    sampleRateHz = config.sampleRateHz,
                    centerFrequencyHz = config.centerFrequencyHz,
                    rfBandwidthHz = config.rfBandwidthHz,
                    gainDb = config.gainDb,
                    sampleFormat = config.sampleFormat.nativeValue,
                    loOffsetHz = config.loOffsetHz,
                    hardwareIqCorrection = config.hardwareIqCorrection,
                    hardwareBbdcCorrection = config.hardwareBbdcCorrection,
                    hardwareRfdcCorrection = config.hardwareRfdcCorrection,
                    durationSec = config.captureDurationSec,
                )
            } finally {
                usbConnection.close()
            }
            val captured = diagnostic.contains("status: captured")
            if (captured) {
                writeCaptureMetadata(path, uri, config)
            }
            mainHandler.post {
                activeMode = ActiveMode.NONE
                updateSleepBlocker()
                binding.sampleText.text = if (captured) {
                    diagnostic + "\n\nmetadata path: ${metadataFileFor(path).absolutePath}"
                } else {
                    diagnostic
                }
            }
        }
    }

    private fun writeCaptureMetadata(iqPath: String, uri: String, config: PlutoIqConfig) {
        val json = JSONObject()
            .put("format", config.sampleFormat.metadataValue)
            .put("endianness", "little")
            .put("sample_rate_hz", config.sampleRateHz)
            .put("center_frequency_hz", config.centerFrequencyHz)
            .put("rf_bandwidth_hz", config.rfBandwidthHz)
            .put("lo_offset_hz", config.loOffsetHz)
            .put("actual_lo_frequency_hz", config.actualLoFrequencyHz())
            .put("gain_db", config.gainDb)
            .put("hardware_iq_correction", config.hardwareIqCorrection)
            .put("hardware_bbdc_correction", config.hardwareBbdcCorrection)
            .put("hardware_rfdc_correction", config.hardwareRfdcCorrection)
            .put("source", "pluto_iio_usb")
            .put("device", "PlutoSDR")
            .put("uri", uri)
            .put("duration_sec", config.captureDurationSec)
        metadataFileFor(iqPath).writeText(json.toString(2))
    }

    private fun showPlutoIqConfigDialog() {
        val dialogBinding = DialogPlutoIqConfigBinding.inflate(layoutInflater)
        populatePlutoIqConfigDialog(dialogBinding, plutoIqConfig)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("IQ stream setup")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Defaults", null)
            .setPositiveButton("Apply", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                populatePlutoIqConfigDialog(dialogBinding, defaultPlutoIqConfig())
            }
            dialogBinding.saveDefaultsButton.setOnClickListener {
                val parsed = readPlutoIqConfigFromDialog(dialogBinding) ?: return@setOnClickListener
                plutoIqConfig = parsed
                updateCurrentFrequencyLabel(parsed.centerFrequencyHz)
                savePlutoIqConfigDefaults(parsed)
                binding.sampleText.text = "Pluto IQ defaults saved:\n" + parsed.toDiagnosticText()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = readPlutoIqConfigFromDialog(dialogBinding) ?: return@setOnClickListener
                plutoIqConfig = parsed
                updateCurrentFrequencyLabel(parsed.centerFrequencyHz)
                binding.sampleText.text = "Pluto IQ setup updated:\n" + parsed.toDiagnosticText()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun populatePlutoIqConfigDialog(
        dialogBinding: DialogPlutoIqConfigBinding,
        config: PlutoIqConfig,
    ) {
        dialogBinding.maiaHostInput.setText(config.maiaHost)
        dialogBinding.maiaPortInput.setText(config.maiaPort.toString())
        dialogBinding.plutoWsPathInput.setText(config.plutoWebSocketPath)
        dialogBinding.plutoWsReceiveBufferInput.setText(config.plutoWebSocketReceiveBufferMs.toString())
        dialogBinding.centerFrequencyInput.setText(config.centerFrequencyHz.toString())
        when (config.sampleFormat) {
            IqSampleFormat.CS16 -> dialogBinding.sampleFormatCs16Radio.isChecked = true
            IqSampleFormat.CS8 -> dialogBinding.sampleFormatCs8Radio.isChecked = true
        }
        dialogBinding.sampleRateInput.setText(config.sampleRateHz.toString())
        dialogBinding.rfBandwidthInput.setText(config.rfBandwidthHz.toString())
        dialogBinding.gainInput.setText(config.gainDb.toString())
        dialogBinding.captureDurationInput.setText(config.captureDurationSec.toString())
        dialogBinding.loOffsetInput.setText(config.loOffsetHz.toString())
        dialogBinding.iqCorrectionCheckbox.isChecked = config.hardwareIqCorrection
        dialogBinding.bbdcCorrectionCheckbox.isChecked = config.hardwareBbdcCorrection
        dialogBinding.rfdcCorrectionCheckbox.isChecked = config.hardwareRfdcCorrection
        clearPlutoIqConfigDialogErrors(dialogBinding)
    }

    private fun readPlutoIqConfigFromDialog(
        dialogBinding: DialogPlutoIqConfigBinding,
    ): PlutoIqConfig? {
        clearPlutoIqConfigDialogErrors(dialogBinding)
        val maiaHost = readHost(
            dialogBinding.maiaHostInput,
            dialogBinding.maiaHostLayout,
            "Pluto WebSocket host",
        )
        val maiaPort = readTcpPort(
            dialogBinding.maiaPortInput,
            dialogBinding.maiaPortLayout,
            "Pluto WebSocket port",
        )
        val plutoWebSocketPath = readWebSocketPath(
            dialogBinding.plutoWsPathInput,
            dialogBinding.plutoWsPathLayout,
            "Pluto WebSocket path",
        )
        val plutoWebSocketReceiveBufferMs = readBoundedInt(
            dialogBinding.plutoWsReceiveBufferInput,
            dialogBinding.plutoWsReceiveBufferLayout,
            "Pluto WS receive buffer",
            PLUTO_WS_RECEIVE_BUFFER_MIN_MS,
            PLUTO_WS_RECEIVE_BUFFER_MAX_MS,
        )
        val centerFrequencyHz = readPositiveLong(
            dialogBinding.centerFrequencyInput,
            dialogBinding.centerFrequencyLayout,
            "Center frequency",
        )
        val sampleRateHz = readPositiveLong(
            dialogBinding.sampleRateInput,
            dialogBinding.sampleRateLayout,
            "Sample rate",
        )
        val rfBandwidthHz = readPositiveLong(
            dialogBinding.rfBandwidthInput,
            dialogBinding.rfBandwidthLayout,
            "RF bandwidth",
        )
        val gainDb = readDouble(
            dialogBinding.gainInput,
            dialogBinding.gainLayout,
            "Gain",
        )
        val captureDurationSec = readPositiveDouble(
            dialogBinding.captureDurationInput,
            dialogBinding.captureDurationLayout,
            "Capture duration",
        )
        val loOffsetHz = readLong(
            dialogBinding.loOffsetInput,
            dialogBinding.loOffsetLayout,
            "LO offset",
        )

        if (maiaHost == null ||
            maiaPort == null ||
            plutoWebSocketPath == null ||
            plutoWebSocketReceiveBufferMs == null ||
            centerFrequencyHz == null ||
            sampleRateHz == null ||
            rfBandwidthHz == null ||
            gainDb == null ||
            captureDurationSec == null ||
            loOffsetHz == null
        ) {
            return null
        }

        return PlutoIqConfig(
            maiaHost = maiaHost,
            maiaPort = maiaPort,
            plutoWebSocketPath = plutoWebSocketPath,
            plutoWebSocketReceiveBufferMs = plutoWebSocketReceiveBufferMs,
            sampleFormat = when {
                dialogBinding.sampleFormatCs8Radio.isChecked -> IqSampleFormat.CS8
                else -> IqSampleFormat.CS16
            },
            sampleRateHz = sampleRateHz,
            centerFrequencyHz = centerFrequencyHz,
            rfBandwidthHz = rfBandwidthHz,
            gainDb = gainDb,
            captureDurationSec = captureDurationSec,
            loOffsetHz = loOffsetHz,
            hardwareIqCorrection = dialogBinding.iqCorrectionCheckbox.isChecked,
            hardwareBbdcCorrection = dialogBinding.bbdcCorrectionCheckbox.isChecked,
            hardwareRfdcCorrection = dialogBinding.rfdcCorrectionCheckbox.isChecked,
        )
    }

    private fun clearPlutoIqConfigDialogErrors(dialogBinding: DialogPlutoIqConfigBinding) {
        dialogBinding.maiaHostLayout.error = null
        dialogBinding.maiaPortLayout.error = null
        dialogBinding.plutoWsPathLayout.error = null
        dialogBinding.plutoWsReceiveBufferLayout.error = null
        dialogBinding.centerFrequencyLayout.error = null
        dialogBinding.sampleRateLayout.error = null
        dialogBinding.rfBandwidthLayout.error = null
        dialogBinding.gainLayout.error = null
        dialogBinding.captureDurationLayout.error = null
        dialogBinding.loOffsetLayout.error = null
    }

    private fun readHost(
        input: TextInputEditText,
        layout: TextInputLayout,
        label: String,
    ): String? {
        val value = input.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) {
            layout.error = "$label is required"
            return null
        }
        if (value.contains("://") || value.contains("/") || value.contains(":")) {
            layout.error = "Enter only the host name or IP address"
            return null
        }
        return value
    }

    private fun readWebSocketPath(
        input: TextInputEditText,
        layout: TextInputLayout,
        label: String,
    ): String? {
        val value = input.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) {
            layout.error = "$label is required"
            return null
        }
        if (!value.startsWith("/")) {
            layout.error = "$label must start with /"
            return null
        }
        if (value.contains(" ") || value.contains("://")) {
            layout.error = "Enter only the path, for example /iq"
            return null
        }
        return value
    }

    private fun readTcpPort(
        input: TextInputEditText,
        layout: TextInputLayout,
        label: String,
    ): Int? {
        val value = normalizedNumberText(input).toIntOrNull()
        if (value == null) {
            layout.error = "$label must be an integer"
            return null
        }
        if (value !in 1..65535) {
            layout.error = "$label must be between 1 and 65535"
            return null
        }
        return value
    }

    private fun readBoundedInt(
        input: TextInputEditText,
        layout: TextInputLayout,
        label: String,
        minValue: Int,
        maxValue: Int,
    ): Int? {
        val value = normalizedNumberText(input).toIntOrNull()
        if (value == null) {
            layout.error = "$label must be an integer"
            return null
        }
        if (value !in minValue..maxValue) {
            layout.error = "$label must be between $minValue and $maxValue"
            return null
        }
        return value
    }

    private fun readLong(
        input: TextInputEditText,
        layout: TextInputLayout,
        label: String,
    ): Long? {
        val value = normalizedNumberText(input).toLongOrNull()
        if (value == null) {
            layout.error = "$label must be an integer"
            return null
        }
        return value
    }

    private fun readPositiveLong(
        input: TextInputEditText,
        layout: TextInputLayout,
        label: String,
    ): Long? {
        val value = normalizedNumberText(input).toLongOrNull()
        if (value == null || value <= 0L) {
            layout.error = "$label must be a positive integer"
            return null
        }
        return value
    }

    private fun readDouble(
        input: TextInputEditText,
        layout: TextInputLayout,
        label: String,
    ): Double? {
        val value = normalizedNumberText(input).toDoubleOrNull()
        if (value == null || !value.isFinite()) {
            layout.error = "$label must be a valid number"
            return null
        }
        return value
    }

    private fun readPositiveDouble(
        input: TextInputEditText,
        layout: TextInputLayout,
        label: String,
    ): Double? {
        val value = readDouble(input, layout, label) ?: return null
        if (value <= 0.0) {
            layout.error = "$label must be positive"
            return null
        }
        return value
    }

    private fun normalizedNumberText(input: TextInputEditText): String {
        return input.text?.toString()
            ?.trim()
            ?.replace("_", "")
            ?.replace(",", "")
            .orEmpty()
    }

    private fun defaultPlutoIqConfig(): PlutoIqConfig {
        return PlutoIqConfig(
            maiaHost = MAIA_DEFAULT_HOST,
            maiaPort = PLUTO_WS_DEFAULT_PORT,
            plutoWebSocketPath = PLUTO_WS_DEFAULT_PATH,
            plutoWebSocketReceiveBufferMs = PLUTO_WS_RECEIVE_BUFFER_DEFAULT_MS,
            sampleFormat = IqSampleFormat.CS16,
            sampleRateHz = PLUTO_SAMPLE_RATE_HZ,
            centerFrequencyHz = PLUTO_CENTER_FREQUENCY_HZ,
            rfBandwidthHz = PLUTO_RF_BANDWIDTH_HZ,
            gainDb = PLUTO_GAIN_DB,
            captureDurationSec = PLUTO_CAPTURE_DURATION_SEC,
            loOffsetHz = PLUTO_LO_OFFSET_HZ,
            hardwareIqCorrection = PLUTO_HARDWARE_IQ_CORRECTION,
            hardwareBbdcCorrection = PLUTO_HARDWARE_BBDC_CORRECTION,
            hardwareRfdcCorrection = PLUTO_HARDWARE_RFDC_CORRECTION,
        )
    }

    private fun loadPlutoIqConfigDefaults(): PlutoIqConfig {
        val defaults = defaultPlutoIqConfig()
        val preferences = getSharedPreferences(PLUTO_PREFS_NAME, Context.MODE_PRIVATE)
        return PlutoIqConfig(
            maiaHost = preferences.getString(PREF_MAIA_HOST, defaults.maiaHost)?.trim()?.takeIf { it.isNotBlank() }
                ?: defaults.maiaHost,
            maiaPort = preferences.getInt(PREF_PLUTO_WS_PORT, defaults.maiaPort).takeIf { it in 1..65535 }
                ?: defaults.maiaPort,
            plutoWebSocketPath = preferences.getString(PREF_PLUTO_WS_PATH, defaults.plutoWebSocketPath)
                ?.trim()
                ?.takeIf { it.startsWith("/") && !it.contains(" ") && !it.contains("://") }
                ?: defaults.plutoWebSocketPath,
            plutoWebSocketReceiveBufferMs = preferences.getInt(
                PREF_PLUTO_WS_RECEIVE_BUFFER_MS,
                defaults.plutoWebSocketReceiveBufferMs,
            ).coerceIn(PLUTO_WS_RECEIVE_BUFFER_MIN_MS, PLUTO_WS_RECEIVE_BUFFER_MAX_MS),
            sampleFormat = IqSampleFormat.fromMetadataValue(
                preferences.getString(PREF_SAMPLE_FORMAT, defaults.sampleFormat.metadataValue),
            ),
            sampleRateHz = preferences.getLong(PREF_SAMPLE_RATE_HZ, defaults.sampleRateHz),
            centerFrequencyHz = preferences.getLong(PREF_CENTER_FREQUENCY_HZ, defaults.centerFrequencyHz),
            rfBandwidthHz = preferences.getLong(PREF_RF_BANDWIDTH_HZ, defaults.rfBandwidthHz),
            gainDb = Double.fromBits(preferences.getLong(PREF_GAIN_DB_BITS, defaults.gainDb.toBits())),
            captureDurationSec = Double.fromBits(
                preferences.getLong(PREF_CAPTURE_DURATION_SEC_BITS, defaults.captureDurationSec.toBits()),
            ),
            loOffsetHz = preferences.getLong(PREF_LO_OFFSET_HZ, defaults.loOffsetHz),
            hardwareIqCorrection = preferences.getBoolean(
                PREF_HARDWARE_IQ_CORRECTION,
                defaults.hardwareIqCorrection,
            ),
            hardwareBbdcCorrection = preferences.getBoolean(
                PREF_HARDWARE_BBDC_CORRECTION,
                defaults.hardwareBbdcCorrection,
            ),
            hardwareRfdcCorrection = preferences.getBoolean(
                PREF_HARDWARE_RFDC_CORRECTION,
                defaults.hardwareRfdcCorrection,
            ),
        )
    }

    private fun savePlutoIqConfigDefaults(config: PlutoIqConfig) {
        getSharedPreferences(PLUTO_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MAIA_HOST, config.maiaHost)
            .putInt(PREF_PLUTO_WS_PORT, config.maiaPort)
            .putString(PREF_PLUTO_WS_PATH, config.plutoWebSocketPath)
            .putInt(PREF_PLUTO_WS_RECEIVE_BUFFER_MS, config.plutoWebSocketReceiveBufferMs)
            .putString(PREF_SAMPLE_FORMAT, config.sampleFormat.metadataValue)
            .putLong(PREF_SAMPLE_RATE_HZ, config.sampleRateHz)
            .putLong(PREF_CENTER_FREQUENCY_HZ, config.centerFrequencyHz)
            .putLong(PREF_RF_BANDWIDTH_HZ, config.rfBandwidthHz)
            .putLong(PREF_GAIN_DB_BITS, config.gainDb.toBits())
            .putLong(PREF_CAPTURE_DURATION_SEC_BITS, config.captureDurationSec.toBits())
            .putLong(PREF_LO_OFFSET_HZ, config.loOffsetHz)
            .putBoolean(PREF_HARDWARE_IQ_CORRECTION, config.hardwareIqCorrection)
            .putBoolean(PREF_HARDWARE_BBDC_CORRECTION, config.hardwareBbdcCorrection)
            .putBoolean(PREF_HARDWARE_RFDC_CORRECTION, config.hardwareRfdcCorrection)
            .apply()
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
        val loOffsetHz: Long? = null,
        val actualLoFrequencyHz: Long? = null,
        val gainDb: Double? = null,
        val hardwareIqCorrection: Boolean? = null,
        val hardwareBbdcCorrection: Boolean? = null,
        val hardwareRfdcCorrection: Boolean? = null,
        val source: String? = null,
        val device: String? = null,
        val durationSec: Double? = null,
        val requestedVideoStandard: VideoStandard? = null,
    ) {
        fun sampleFormat(): IqSampleFormat {
            return IqSampleFormat.fromMetadataValue(format)
        }

        fun toDiagnosticText(): String {
            return buildString {
                append("metadata path: $sidecarPath")
                source?.let { append("\nsource: $it") }
                device?.let { append("\ndevice: $it") }
                format?.let { append("\nformat: $it") }
                sampleRateHz?.let { append("\nsample_rate_hz: $it") }
                centerFrequencyHz?.let { append("\ncenter_frequency_hz: $it") }
                rfBandwidthHz?.let { append("\nrf_bandwidth_hz: $it") }
                loOffsetHz?.let { append("\nlo_offset_hz: $it") }
                actualLoFrequencyHz?.let { append("\nactual_lo_frequency_hz: $it") }
                gainDb?.let { append("\ngain_db: $it") }
                hardwareIqCorrection?.let { append("\nhardware_iq_correction: $it") }
                hardwareBbdcCorrection?.let { append("\nhardware_bbdc_correction: $it") }
                hardwareRfdcCorrection?.let { append("\nhardware_rfdc_correction: $it") }
                requestedVideoStandard?.let {
                    append("\nrequested_video_standard: $it")
                    if (it == VideoStandard.AUTO) {
                        append("\nlive_auto_note: live USB cannot rewind for PAL/NTSC comparison; native session decodes NTSC525")
                    }
                }
            }
        }
    }

    private data class DecodedFrame(
        val bitmap: Bitmap,
        val diagnostic: String,
    )

    private data class SpectrumProbeResult(
        val signalPresent: Boolean,
        val rssiDbfs: Double?,
        val confidence: Double,
        val diagnostic: String,
    ) {
        fun toSignalProbeResult(): SignalProbeResult {
            return SignalProbeResult(
                signalPresent = signalPresent,
                signalType = SignalType.UNKNOWN,
                rssiDbfs = rssiDbfs,
                confidence = confidence,
                previewFrame = null,
                diagnostic = diagnostic,
            )
        }
    }

    private data class WaterfallFrameStats(
        val byteCount: Int,
        val peak: Int,
        val mean: Double,
        val contrast: Double,
        val activeBins: Int,
        val confidence: Double,
    )

    private data class EthernetNetworkSelection(
        val network: Network?,
        val diagnostics: String,
    )

    private data class MaiaHttpConnectionTestResult(
        val connectionEstablished: Boolean,
        val diagnostics: String,
    )

    private data class UsbInterfaceSelection(
        val index: Int,
        val usbInterface: UsbInterface,
    )

    private data class PlutoConnectionStatus(
        val connected: Boolean,
        val diagnostic: String,
    )

    private data class PlutoSessionRecovery(
        val mode: ActiveMode,
        val standard: VideoStandard,
        val config: PlutoIqConfig,
    )

    private data class PlutoIqConfig(
        val maiaHost: String,
        val maiaPort: Int,
        val plutoWebSocketPath: String,
        val plutoWebSocketReceiveBufferMs: Int,
        val sampleFormat: IqSampleFormat,
        val sampleRateHz: Long,
        val centerFrequencyHz: Long,
        val rfBandwidthHz: Long,
        val gainDb: Double,
        val captureDurationSec: Double,
        val loOffsetHz: Long,
        val hardwareIqCorrection: Boolean,
        val hardwareBbdcCorrection: Boolean,
        val hardwareRfdcCorrection: Boolean,
    ) {
        fun actualLoFrequencyHz(): Long {
            return centerFrequencyHz + loOffsetHz
        }

        fun forcedSampleFormat(format: IqSampleFormat): PlutoIqConfig {
            return copy(sampleFormat = format)
        }

        fun maiaEndpoint(): String {
            return if (maiaPort == 80) {
                maiaHost
            } else {
                "$maiaHost:$maiaPort"
            }
        }

        fun plutoWebSocketEndpoint(): String {
            return "$maiaHost:$maiaPort"
        }

        fun toDiagnosticText(): String {
            return buildString {
                append("pluto_websocket_endpoint: ws://${plutoWebSocketEndpoint()}$plutoWebSocketPath")
                append("\npluto_ws_receive_buffer_ms: $plutoWebSocketReceiveBufferMs")
                append("\nformat: ${sampleFormat.metadataValue}")
                append("\nsample_rate_hz: $sampleRateHz")
                append("\ncenter_frequency_hz: $centerFrequencyHz")
                append("\nrf_bandwidth_hz: $rfBandwidthHz")
                append("\nlo_offset_hz: $loOffsetHz")
                append("\nactual_lo_frequency_hz: ${actualLoFrequencyHz()}")
                append("\ngain_db: $gainDb")
                append("\ncapture_duration_sec: $captureDurationSec")
                append("\nhardware_iq_correction: $hardwareIqCorrection")
                append("\nhardware_bbdc_correction: $hardwareBbdcCorrection")
                append("\nhardware_rfdc_correction: $hardwareRfdcCorrection")
            }
        }
    }

    private enum class VideoStandard(val nativeValue: Int) {
        AUTO(0),
        PAL625_25FPS(1),
        NTSC_525_30FPS(2),
    }

    private enum class IqSampleFormat(val nativeValue: Int, val metadataValue: String) {
        CS16(0, "CS16"),
        CS8(1, "CS8");

        companion object {
            fun fromMetadataValue(value: String?): IqSampleFormat {
                return when (value?.trim()?.uppercase()) {
                    "CS8" -> CS8
                    else -> CS16
                }
            }
        }
    }

    private enum class PlutoUsbAction {
        CAPTURE_TO_FILE,
        LIVE_PLAYBACK,
        SPECTRUM_VIEW,
        WEB_SOCKET_PLAYBACK,
        WEB_SOCKET_SPECTRUM,
        SCANNER,
    }

    private enum class ActiveMode {
        NONE,
        FILE_PLAYBACK,
        FILE_FRAME,
        RECORD_IQ,
        PLUTO_USB_PLAYBACK,
        PLUTO_USB_SPECTRUM,
        PLUTO_WS_PLAYBACK,
        PLUTO_WS_SPECTRUM,
        MAIA_PLAYBACK,
        MAIA_SPECTRUM;

        fun isPlaybackMode(): Boolean {
            return this == FILE_PLAYBACK ||
                this == PLUTO_USB_PLAYBACK ||
                this == PLUTO_WS_PLAYBACK ||
                this == MAIA_PLAYBACK
        }

        fun isSpectrumMode(): Boolean {
            return this == PLUTO_USB_SPECTRUM ||
                this == PLUTO_WS_SPECTRUM ||
                this == MAIA_SPECTRUM
        }

        fun isPlutoPlaybackMode(): Boolean {
            return this == PLUTO_USB_PLAYBACK || this == PLUTO_WS_PLAYBACK
        }

        fun isPlutoSpectrumMode(): Boolean {
            return this == PLUTO_USB_SPECTRUM || this == PLUTO_WS_SPECTRUM
        }

        fun label(): String {
            return when (this) {
                PLUTO_USB_PLAYBACK -> "Pluto USB playback"
                PLUTO_USB_SPECTRUM -> "Pluto USB FFT / waterfall"
                PLUTO_WS_PLAYBACK -> "Pluto WebSocket playback"
                PLUTO_WS_SPECTRUM -> "Pluto WebSocket FFT / waterfall"
                else -> name.lowercase(Locale.US)
            }
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

    /**
     * Captures raw CS16 IQ from PlutoSDR through libiio into a replayable file.
     */
    external fun capturePlutoIqToFile(
        outputPath: String,
        uri: String,
        sampleRateHz: Long,
        centerFrequencyHz: Long,
        rfBandwidthHz: Long,
        gainDb: Double,
        sampleFormat: Int,
        loOffsetHz: Long,
        hardwareIqCorrection: Boolean,
        hardwareBbdcCorrection: Boolean,
        hardwareRfdcCorrection: Boolean,
        durationSec: Double,
    ): String

    external fun isPlutoCaptureAvailable(): Boolean

    external fun isPlutoUsbCaptureAvailable(): Boolean

    external fun getIioBackendDiagnostic(): String

    external fun consumeLastNativeError(): String

    /**
     * Decodes one analog FPV video frame and returns width/height plus 8-bit grayscale pixels.
     */
    external fun decodeAnalogVideoFrame(
        path: String,
        hasSampleRateHz: Boolean,
        sampleRateHz: Long,
        sampleFormat: Int,
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
        sampleFormat: Int,
        videoStandard: Int,
    ): Long

    /**
     * Opens a sequential native decoder backed directly by Pluto USB I/Q.
     */
    external fun createPlutoAnalogVideoPlaybackSession(
        uri: String,
        sampleRateHz: Long,
        centerFrequencyHz: Long,
        rfBandwidthHz: Long,
        gainDb: Double,
        sampleFormat: Int,
        loOffsetHz: Long,
        hardwareIqCorrection: Boolean,
        hardwareBbdcCorrection: Boolean,
        hardwareRfdcCorrection: Boolean,
        videoStandard: Int,
    ): Long

    /**
     * Opens a sequential native FFT/waterfall session backed directly by Pluto USB I/Q.
     */
    external fun createPlutoSpectrumSession(
        uri: String,
        sampleRateHz: Long,
        centerFrequencyHz: Long,
        rfBandwidthHz: Long,
        gainDb: Double,
        sampleFormat: Int,
        loOffsetHz: Long,
        hardwareIqCorrection: Boolean,
        hardwareBbdcCorrection: Boolean,
        hardwareRfdcCorrection: Boolean,
    ): Long

    /**
     * Opens a sequential native decoder backed by Maia SDR HTTP CS8 I/Q.
     */
    external fun createMaiaAnalogVideoPlaybackSession(
        host: String,
        port: Int,
        sampleRateHz: Long,
        videoStandard: Int,
        androidHttpTransport: MaiaHttpTransport,
    ): Long

    /**
     * Opens a native FFT/waterfall session backed by Maia SDR HTTP CS8 I/Q.
     */
    external fun createMaiaSpectrumSession(
        host: String,
        port: Int,
        sampleRateHz: Long,
        centerFrequencyHz: Long,
        androidHttpTransport: MaiaHttpTransport,
    ): Long

    /**
     * Opens a sequential native decoder backed by plutorx_ws CS8 I/Q frames.
     */
    external fun createPlutoWebSocketAnalogVideoPlaybackSession(
        host: String,
        port: Int,
        path: String,
        sampleRateHz: Long,
        receiveBufferMs: Int,
        videoStandard: Int,
        androidWebSocketTransport: PlutoWebSocketTransport,
    ): Long

    /**
     * Opens a native FFT/waterfall session backed by plutorx_ws CS8 I/Q frames.
     */
    external fun createPlutoWebSocketSpectrumSession(
        host: String,
        port: Int,
        path: String,
        sampleRateHz: Long,
        centerFrequencyHz: Long,
        receiveBufferMs: Int,
        androidWebSocketTransport: PlutoWebSocketTransport,
    ): Long

    /**
     * Decodes the next frame from an existing native playback session.
     */
    external fun decodeNextAnalogVideoPlaybackFrame(sessionHandle: Long): ByteArray

    /**
     * Renders the next FFT/waterfall frame from an existing native spectrum session.
     */
    external fun decodeNextSpectrumFrame(sessionHandle: Long): ByteArray

    /**
     * Closes a native playback session.
     */
    external fun closeAnalogVideoPlaybackSession(sessionHandle: Long)

    /**
     * Closes a native spectrum session.
     */
    external fun closeSpectrumSession(sessionHandle: Long)

    companion object {
        private const val LOG_TAG = "SDRVideoScanner.Main"
        private const val FRAME_PACKET_HEADER_BYTES = 12
        private const val PLAYBACK_DELAY_MS = 1L
        private const val PLAYBACK_DIAGNOSTIC_EVERY_FRAMES = 10L
        private const val SCAN_STEP_DELAY_MS = 100L
        private const val SCANNER_FFT_RETUNE_SETTLE_MS = 150L
        private const val SCANNER_VIDEO_RETUNE_SETTLE_MS = 800L
        private const val SCANNER_FFT_DISCARD_FRAME_COUNT = 2
        private const val SCANNER_FFT_PROBE_FRAME_COUNT = 4
        private const val SCANNER_FFT_MIN_PEAK = 45
        private const val SCANNER_FFT_MIN_CONTRAST = 18.0
        private const val SCANNER_FFT_MIN_CONFIDENCE = 0.35
        private const val SCANNER_FFT_ACTIVE_BIN_OFFSET = 12.0
        private const val SCANNER_FFT_CONTRAST_SCORE_RANGE = 48.0
        private const val SCANNER_FFT_PEAK_SCORE_RANGE = 150.0
        private const val SCANNER_FFT_ACTIVE_FRACTION_TARGET = 0.08
        private const val SCANNER_PROBE_DISCARD_FRAME_COUNT = 25
        private const val SCANNER_FAST_PROBE_FRAME_COUNT = 3
        private const val SCANNER_CONFIRM_PROBE_FRAME_COUNT = 5
        private const val SCANNER_CONFIRMATION_CONFIDENCE = 0.35
        private const val SCANNER_REQUIRED_DETECTED_FRAME_COUNT = 4
        private const val SCANNER_MIN_ANALOG_CONFIDENCE = 0.50
        private const val SCANNER_CONFIRMATION_WINDOW_HZ = 50_000_000L
        private const val SCANNER_MAX_CONFIRMATION_CHANNELS = 8
        private const val SCANNER_MIN_CONFIRMATION_HITS = 2
        private const val SCANNER_FREQUENCY_SWITCH_MARGIN = 0.03
        private const val SCANNER_SCORE_TIE_MARGIN = 0.02
        private const val PLAYBACK_SCAN_PLAY_WINDOW_MS = 8_000L
        private const val PLAYBACK_SCAN_BACKGROUND_WINDOW_MS = 2_000L
        private const val BACKGROUND_SCAN_MAX_CHANNELS = 2
        private const val SPECTRUM_DELAY_MS = 40L
        private const val SPECTRUM_DIAGNOSTIC_EVERY_FRAMES = 8L
        private const val PLUTO_CONNECTION_CHECK_INTERVAL_MS = 5_000L
        private const val PLUTO_CONNECTION_ABSENT_POWER_CYCLE_MS = 30_000L
        private const val PLUTO_OTG_POWER_OFF_MS = 2_000L
        private const val PLUTO_OTG_POWER_ON_SETTLE_MS = 5_000L
        private const val PLUTO_SESSION_CLOSE_GRACE_MS = 500L
        private const val PLUTO_MAIN_THREAD_STOP_TIMEOUT_MS = 5_000L
        private const val MAIA_RECORDER_PATH = "/api/recorder"
        private const val MAIA_CONNECT_TIMEOUT_MS = 3000
        private const val MAIA_READ_TIMEOUT_MS = 3000
        private const val MAIA_TEST_RESPONSE_LIMIT_BYTES = 64 * 1024
        private const val PLUTO_WS_CONNECT_TIMEOUT_MS = 3000
        private const val PLUTO_WS_READ_TIMEOUT_MS = 3000
        private const val PLUTO_WATERFALL_PORT = 80
        private const val PLUTO_WATERFALL_PATH = "/waterfall"
        private const val PLUTO_WS_RECEIVE_BUFFER_MIN_MS = 50
        private const val PLUTO_WS_RECEIVE_BUFFER_MAX_MS = 150
        private const val PLUTO_WS_RECEIVE_BUFFER_DEFAULT_MS = 120
        private const val MENU_PLAY_PLUTO_WS_CS8 = 1
        private const val MENU_STOP = 2
        private const val MENU_SETUP_IQ = 3
        private const val MENU_RECORD_IQ = 4
        private const val MENU_PLUTO_WS_FFT = 5
        private const val MENU_PLAY_AUTO_FILE = 6
        private const val MENU_DECODE_AUTO_FILE = 7
        private const val MENU_TUNE_FREQUENCY = 8
        private const val MENU_PLAY_PLUTO_USB_CS16 = 9
        private const val ACTION_USB_PERMISSION = "com.example.sdrvideoscanner.USB_PERMISSION"
        private const val PLUTO_USB_VENDOR_ID = 0x0456
        private const val PLUTO_USB_PRODUCT_ID = 0xb673
        private const val PLUTO_SAMPLE_RATE_HZ = 25_000_000L
        private const val PLUTO_CENTER_FREQUENCY_HZ = 5_885_000_000L
        private const val PLUTO_RF_BANDWIDTH_HZ = 20_000_000L
        private const val PLUTO_GAIN_DB = 46.0
        private const val PLUTO_CAPTURE_DURATION_SEC = 0.5
        private const val PLUTO_LO_OFFSET_HZ = 0L
        private const val PLUTO_HARDWARE_IQ_CORRECTION = true
        private const val PLUTO_HARDWARE_BBDC_CORRECTION = true
        private const val PLUTO_HARDWARE_RFDC_CORRECTION = true
        private const val MAIA_DEFAULT_HOST = "192.168.2.1"
        private const val MAIA_DEFAULT_PORT = 80
        private const val PLUTO_WS_DEFAULT_PORT = 7682
        private const val PLUTO_WS_DEFAULT_PATH = "/iq"
        private const val PLUTO_PREFS_NAME = "pluto_iq_setup"
        private const val PREF_MAIA_HOST = "maia_host"
        private const val PREF_PLUTO_WS_PORT = "pluto_ws_port"
        private const val PREF_PLUTO_WS_PATH = "pluto_ws_path"
        private const val PREF_PLUTO_WS_RECEIVE_BUFFER_MS = "pluto_ws_receive_buffer_ms"
        private const val PREF_SAMPLE_FORMAT = "sample_format"
        private const val PREF_SAMPLE_RATE_HZ = "sample_rate_hz"
        private const val PREF_CENTER_FREQUENCY_HZ = "center_frequency_hz"
        private const val PREF_RF_BANDWIDTH_HZ = "rf_bandwidth_hz"
        private const val PREF_GAIN_DB_BITS = "gain_db_bits"
        private const val PREF_CAPTURE_DURATION_SEC_BITS = "capture_duration_sec_bits"
        private const val PREF_LO_OFFSET_HZ = "lo_offset_hz"
        private const val PREF_HARDWARE_IQ_CORRECTION = "hardware_iq_correction"
        private const val PREF_HARDWARE_BBDC_CORRECTION = "hardware_bbdc_correction"
        private const val PREF_HARDWARE_RFDC_CORRECTION = "hardware_rfdc_correction"

        // Used to load the 'sdrvideoscanner' library on application startup.
        init {
            System.loadLibrary("sdrvideoscanner")
        }
    }
}
