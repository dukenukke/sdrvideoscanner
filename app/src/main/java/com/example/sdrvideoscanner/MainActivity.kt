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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    private var playbackGeneration = 0L
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
    private var playbackPreviousBoundNetwork: Network? = null
    private var playbackBoundNetwork: Network? = null
    private var reusableFramePixels = IntArray(0)
    private var pendingPlutoCapturePath: String? = null
    private var pendingPlutoUsbAction: PlutoUsbAction? = null
    private var pendingPlutoVideoStandard = VideoStandard.AUTO
    private var pendingPlutoIqConfig = defaultPlutoIqConfig()
    private var plutoIqConfig = defaultPlutoIqConfig()
    private var activeMode = ActiveMode.NONE
    private val rfController: RfController = NoOpRfController(debugLogging = false)
    private val gainController = GainController(defaultGainControllerConfig(), rfController)
    private val scanController = ScanController(ChannelPlan.knownChannels)
    private var maiaWaterfallScanner: MaiaWaterfallScanController? = null
    private var maiaWaterfallState: MaiaScannerState = MaiaScannerState.STOPPED
    private var maiaWaterfallStatistics: ScannerStatistics = ScannerStatistics()
    private var scannerWebViewReady = false
    private var scannerWebSpectrumVisible = true
    private var scannerWebSelectedSignalId: Long? = null
    private var scannerWebCurrentLoFrequencyHz: Long? = null
    private var scannerWebCurrentSampleRateHz: Long? = null
    private var scannerWebCurrentBandwidthHz: Long? = null
    private var scannerWebActiveWindow: ScanWindow? = null
    private var scannerWebWindowIndex = 0
    private var scannerWebWindowTotal = 0
    private var scannerWebNoiseFloorDb: Float? = null
    private var scannerWebDiagnostics: String? = null
    private var scannerWebScanConfigOverride: MaiaScanConfig? = null
    private var scannerWebScanRangesOverride: List<ScanRange>? = null
    private val scannerWebEvents = ArrayDeque<ScannerEventRecord>()
    private var lastSpectrumWebUpdateNs = 0L
    @Volatile
    private var scannerRunning = false
    @Volatile
    private var scanSessionId = 0L
    private var selectedPlaybackChannel: KnownChannel? = null
    private var currentScannerChannel: KnownChannel? = null
    private var statsVisible = false
    private var lastPlutoWebSocketPrimeMs = 0L
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
                    PlutoUsbAction.IP_IIO_CAPTURE -> {
                        if (path != null) {
                            startPlutoIpIioCapture(path, config)
                        }
                    }
                    PlutoUsbAction.LIVE_PLAYBACK -> startPlutoUsbPlayback(device, standard, config)
                    PlutoUsbAction.IP_IIO_PLAYBACK -> startPlutoIpIioPlayback(standard, config)
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
        setupScannerWebView()
        plutoMonitorThread = HandlerThread("PlutoConnectionMonitor").apply { start() }
        plutoMonitorHandler = Handler(plutoMonitorThread.looper)
        startPlutoConnectionMonitor()
        applySystemBarInsets()
        plutoIqConfig = loadPlutoIqConfigDefaults()
        pendingPlutoIqConfig = plutoIqConfig

        val defaultIqPath = defaultIqPathForConfig()
        val defaultMetadataPath = metadataFileFor(defaultIqPath).absolutePath
        binding.sampleText.text = "Place ${File(defaultIqPath).name} at:\n$defaultIqPath\n\nOptional metadata:\n$defaultMetadataPath"
        binding.currentMenuLabel.text = "Selected: Pluto CS8 (${plutoIqConfig.iqSource.label})"
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
        binding.gainDownButton.setOnClickListener {
            adjustManualPlutoGain(-5.0)
        }
        binding.gainModeButton.setOnClickListener {
            toggleManualGainControl()
        }
        binding.gainUpButton.setOnClickListener {
            adjustManualPlutoGain(5.0)
        }
        updateCurrentFrequencyLabel(plutoIqConfig.centerFrequencyHz)
        binding.videoFrameContainer.visibility = View.GONE
        binding.edgeTimelineView.setTimeline(null)
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

    private fun setupScannerWebView() {
        val webView = binding.scannerWebView
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.isSaveEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                scannerWebViewReady = true
                appendScannerWebEvent("system", "Scanner dashboard loaded from local assets")
                emitScannerSnapshot()
            }
        }
        webView.addJavascriptInterface(
            ScannerWebBridge(scannerWebBridgeHandler()),
            "AndroidScanner",
        )
        webView.loadUrl(SCANNER_WEB_URL)
    }

    private fun scannerWebBridgeHandler(): ScannerWebBridge.Handler {
        return object : ScannerWebBridge.Handler {
            override fun startScan() = runOnMain {
                if (playbackRunning) {
                    releaseSignalToScanner()
                } else if (!scannerRunning) {
                    startScannerMode()
                }
                scannerWebSpectrumVisible = true
                appendScannerWebEvent("system", "Scan requested from dashboard")
                emitScannerSnapshot()
            }

            override fun pauseScan() = runOnMain {
                maiaWaterfallScanner?.pause()
                scannerRunning = false
                appendScannerWebEvent("warning", "Scan paused")
                emitScannerSnapshot()
            }

            override fun resumeScan() = runOnMain {
                if (maiaWaterfallScanner != null) {
                    scannerRunning = true
                    maiaWaterfallScanner?.resume()
                } else {
                    startScannerMode()
                }
                scannerWebSpectrumVisible = true
                appendScannerWebEvent("success", "Scan resumed")
                emitScannerSnapshot()
            }

            override fun stopScan() = runOnMain {
                stopScannerMode()
                appendScannerWebEvent("warning", "Scan stopped")
                emitScannerSnapshot()
            }

            override fun skipSignal() = runOnMain {
                releaseSignalToScanner()
                appendScannerWebEvent("warning", "Signal skipped; scanning resumed")
            }

            override fun selectSignal(id: Long) = runOnMain {
                scannerWebSelectedSignalId = id
                emitScannerSnapshot()
            }

            override fun lockSignal(id: Long) = runOnMain {
                scannerWebSelectedSignalId = id
                val record = signalRecordByWebId(id)
                if (!ScannerModeTransitionPolicy.lockAllowed(record)) {
                    appendScannerWebEvent("warning", "Video lock rejected for non-Analog-FPV signal")
                    emitScannerSnapshot()
                    return@runOnMain
                }
                scannerWebSpectrumVisible = false
                playDetectedAnalogSignal(record!!)
                appendScannerWebEvent("success", "Analog FPV signal locked for native video decode")
                emitScannerSnapshot()
            }

            override fun releaseSignal() = runOnMain {
                releaseSignalToScanner()
            }

            override fun showSpectrum() = runOnMain {
                scannerWebSpectrumVisible = true
                if (playbackRunning) {
                    releaseSignalToScanner()
                } else {
                    binding.videoFrameContainer.visibility = View.GONE
                    emitScannerSnapshot()
                }
            }

            override fun showVideo() = runOnMain {
                val id = scannerWebSelectedSignalId
                val record = id?.let { signalRecordByWebId(it) }
                if (!ScannerModeTransitionPolicy.lockAllowed(record)) {
                    appendScannerWebEvent("warning", "Decoded video is available only for Analog FPV signals")
                    emitScannerSnapshot()
                    return@runOnMain
                }
                scannerWebSpectrumVisible = false
                playDetectedAnalogSignal(record!!)
                emitScannerSnapshot()
            }

            override fun applyScanConfig(config: ValidatedScanCommandConfig) = runOnMain {
                scannerWebScanConfigOverride = config.config
                scannerWebScanRangesOverride = config.ranges
                appendScannerWebEvent("system", "Scan configuration applied from dashboard")
                if (scannerRunning || maiaWaterfallScanner != null) {
                    stopScannerMode()
                    startScannerMode()
                }
                emitScannerSnapshot()
            }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun showMainMenu() {
        PopupMenu(this, binding.mainMenuButton).apply {
            menu.add(0, MENU_PLAY_PLUTO_IIO_CS8, 0, "Play Pluto CS8 (${plutoIqConfig.iqSource.label})")
            menu.add(0, MENU_PLAY_PLUTO_USB_CS16, 1, "Play Pluto IP IIO CS16")
            menu.add(0, MENU_STOP, 2, "Stop")
            menu.add(0, MENU_SETUP_IQ, 3, "Setup IQ")
            menu.add(0, MENU_TUNE_FREQUENCY, 4, "Tune Frequency")
            menu.add(0, MENU_RECORD_IQ, 5, "Record IQ")
            menu.add(0, MENU_PLUTO_WS_FFT, 6, "Pluto WS FFT")
            menu.add(0, MENU_PLAY_AUTO_FILE, 7, "Play IQ file")
            menu.add(0, MENU_DECODE_AUTO_FILE, 8, "Decode IQ frame")
            menu.add(0, MENU_ABOUT, 9, "About")
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
            MENU_PLAY_PLUTO_IIO_CS8 -> {
                stopScannerMode()
                stopSpectrum()
                stopPlayback()
                startConfiguredPlutoCs8Playback(VideoStandard.AUTO, plutoIqConfig)
            }
            MENU_PLAY_PLUTO_USB_CS16 -> {
                stopScannerMode()
                stopSpectrum()
                stopPlayback()
                activeMode = ActiveMode.PLUTO_IP_PLAYBACK
                preparePlutoIpIioPlayback(VideoStandard.AUTO, plutoIqConfig.forcedSampleFormat(IqSampleFormat.CS16))
            }
            MENU_STOP -> {
                stopPlayback()
                stopSpectrum()
                stopScannerMode()
            }
            MENU_SETUP_IQ -> showPlutoIqConfigDialog()
            MENU_TUNE_FREQUENCY -> showCenterFrequencyDialog()
            MENU_RECORD_IQ -> {
                val captureConfig = captureConfigForCurrentSelection()
                stopScannerMode()
                stopPlayback()
                stopSpectrum()
                activeMode = ActiveMode.RECORD_IQ
                preparePlutoIpIioCapture(defaultIqPathForConfig(captureConfig), captureConfig)
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
            MENU_ABOUT -> showAboutDialog()
        }
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("About")
            .setMessage(
                "SDR Video Scanner\n\n" +
                    "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "Build: ${appBuildLabel()}",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setSelectedMenuItem(title: String) {
        binding.currentMenuLabel.text = "Selected: $title"
    }

    private fun updateStatsVisibility() {
        binding.statsScroll.visibility = if (statsVisible) View.VISIBLE else View.GONE
        binding.toggleStatsButton.text = if (statsVisible) "Hide Stats" else "Show Stats"
    }

    private fun updateSampleText(text: CharSequence, preserveStatsScroll: Boolean = false) {
        val previousScrollY = binding.statsScroll.scrollY
        binding.sampleText.text = text
        if (preserveStatsScroll && statsVisible) {
            binding.statsScroll.post {
                val maxScrollY = (binding.sampleText.height - binding.statsScroll.height).coerceAtLeast(0)
                binding.statsScroll.scrollTo(binding.statsScroll.scrollX, previousScrollY.coerceAtMost(maxScrollY))
            }
        }
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
        val activePlutoSession = currentPlutoSessionRecovery()
        val restartUsbPlayback = playbackRunning && previousMode == ActiveMode.PLUTO_USB_PLAYBACK
        val restartIpIioPlayback = playbackRunning && previousMode == ActiveMode.PLUTO_IP_PLAYBACK
        val restartWebSocketPlayback = playbackRunning && previousMode == ActiveMode.PLUTO_WS_PLAYBACK
        val restartSpectrum = spectrumRunning && previousMode == ActiveMode.PLUTO_WS_SPECTRUM
        plutoIqConfig = plutoIqConfig.copy(centerFrequencyHz = frequencyHz)
        pendingPlutoIqConfig = pendingPlutoIqConfig.copy(centerFrequencyHz = frequencyHz)
        updateCurrentFrequencyLabel(frequencyHz)

        when {
            restartUsbPlayback -> {
                val restartConfig = (activePlutoSession?.config ?: plutoIqConfig).copy(centerFrequencyHz = frequencyHz)
                stopPlayback()
                activeMode = ActiveMode.PLUTO_USB_PLAYBACK
                binding.sampleText.text = "Center frequency changed to ${formatFrequency(frequencyHz)}.\n\nRestarting Pluto IIO playback."
                preparePlutoUsbPlayback(activePlutoSession?.standard ?: VideoStandard.AUTO, restartConfig)
            }
            restartIpIioPlayback -> {
                val restartConfig = (activePlutoSession?.config ?: plutoIqConfig).copy(centerFrequencyHz = frequencyHz)
                stopPlayback()
                activeMode = ActiveMode.PLUTO_IP_PLAYBACK
                binding.sampleText.text = "Center frequency changed to ${formatFrequency(frequencyHz)}.\n\nRestarting Pluto IP IIO playback."
                preparePlutoIpIioPlayback(activePlutoSession?.standard ?: VideoStandard.AUTO, restartConfig)
            }
            restartWebSocketPlayback -> {
                val restartConfig = (activePlutoSession?.config ?: plutoIqConfig).copy(centerFrequencyHz = frequencyHz)
                stopPlayback()
                activeMode = ActiveMode.PLUTO_WS_PLAYBACK
                binding.sampleText.text = "Center frequency changed to ${formatFrequency(frequencyHz)}.\n\nRestarting Pluto WebSocket playback."
                preparePlutoWebSocketPlayback(activePlutoSession?.standard ?: VideoStandard.AUTO, restartConfig)
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
                description = "Pluto scanner",
            )
        ) {
            return
        }
        startScannerModeAfterPreflight(plutoIqConfig)
    }

    private fun startScannerModeAfterPreflight(config: PlutoIqConfig) {
        plutoIqConfig = config
        pendingPlutoIqConfig = config
        activeMode = ActiveMode.PLUTO_SCANNER
        rememberPlutoSession(ActiveMode.PLUTO_SCANNER, VideoStandard.AUTO, config)
        scanSessionId += 1L
        scannerRunning = true
        updateSleepBlocker()
        scanController.clearRecords()
        scanController.startScanningNear(config.centerFrequencyHz)
        binding.videoFrameContainer.visibility = View.GONE
        binding.edgeTimelineView.setTimeline(null)
        binding.videoFrameImage.setImageDrawable(null)
        binding.sampleText.text = scannerProgressText(
            title = "Scanner started",
            channel = null,
            config = config,
        )
        renderSignalTable()
        updateScannerUi()
        startMaiaWaterfallScanner(config)
    }

    private fun stopScannerMode() {
        val previousMode = activeMode
        stopMaiaWaterfallScanner()
        scanSessionId += 1L
        scannerRunning = false
        selectedPlaybackChannel = null
        currentScannerChannel = null
        if (activeMode == ActiveMode.PLUTO_SCANNER) {
            activeMode = ActiveMode.NONE
        }
        clearPlutoSessionIfManualStop(previousMode)
        scanController.idle()
        updateSleepBlocker()
        renderSignalTable()
        binding.sampleText.text = "Scanner stopped.\n\nPress Scan to start Maia waterfall scanning."
        updateScannerUi()
    }

    private fun skipCurrentSignalAndResumeScan() {
        selectedPlaybackChannel = null
        currentScannerChannel = null
        stopPlayback()
        activeMode = ActiveMode.PLUTO_SCANNER
        rememberPlutoSession(ActiveMode.PLUTO_SCANNER, VideoStandard.AUTO, plutoIqConfig)
        scanSessionId += 1L
        scannerRunning = true
        updateSleepBlocker()
        scanController.clearRecords()
        scanController.startScanningNear(plutoIqConfig.centerFrequencyHz)
        binding.videoFrameContainer.visibility = View.GONE
        binding.edgeTimelineView.setTimeline(null)
        binding.nextButton.isEnabled = false
        renderSignalTable()
        updateScannerUi()
        startMaiaWaterfallScanner(plutoIqConfig)
    }

    private fun updateScannerUi() {
        binding.scanButton.text = if (scannerRunning && selectedPlaybackChannel == null) {
            "Stop Scan"
        } else {
            "Scan"
        }
        binding.nextButton.isEnabled = selectedPlaybackChannel != null
        updateGainControls()
        binding.scannerStateLabel.text = buildString {
            append("Scanner: ")
            append(
                if (activeMode == ActiveMode.PLUTO_SCANNER && maiaWaterfallState != MaiaScannerState.STOPPED) {
                    maiaWaterfallState.name.lowercase(Locale.US)
                } else {
                    scanController.state.name.lowercase(Locale.US)
                },
            )
            statusLineChannel()?.let { channel ->
                append(" | ")
                append(channel.bandName)
                append(" / ")
                append(channel.channelName)
                append(" / ")
                append(formatFrequency(channel.centerFrequencyHz))
            }
        }
        updateGainDebugOverlay()
    }

    private fun appBuildLabel(): String {
        return "${BuildConfig.GIT_BRANCH} #${BuildConfig.BUILD_NUMBER} ${BuildConfig.GIT_SHA}"
    }

    private fun updateGainDebugOverlay() {
        val snapshot = gainController.snapshot()
        binding.gainDebugLabel.text = buildString {
            append("RF Frontend Mode: ")
            append(snapshot.rfFrontendMode.name)
            append(" | Mode: ")
            append(snapshot.mode.name)
            append(" | Gain Control: ")
            append(if (snapshot.manualGainControl) "MANUAL" else "AGC")
            append(" | Pluto Gain: ")
            append(String.format(Locale.US, "%.1f dB", snapshot.plutoGainDb))
            append(" | LNA: ")
            append(snapshot.lnaState?.name ?: "N/A")
            append(" | ATT: ")
            append(snapshot.attenuationDb?.let { "${it.db} dB" } ?: "N/A")
            append(" | BPF/MUX: ")
            append(
                if (snapshot.rfFrontendMode == RfFrontendMode.EXTERNAL_FRONTEND) {
                    "${snapshot.band ?: "--"}/${snapshot.muxChannel ?: "--"}"
                } else {
                    "N/A"
                },
            )
            append("\nPeak: ")
            append(formatNullableDb(snapshot.peakDbfs, "dBFS"))
            append(" | SNR: ")
            append(formatNullableDb(snapshot.snrDb, "dB"))
            append(" | Video: ")
            append(formatNullableUnit(snapshot.videoConfidence))
            append(" | Sync: ")
            append(formatNullableUnit(snapshot.syncConfidence))
        }
    }

    private fun formatNullableDb(value: Double?, unit: String): String {
        return value?.let { String.format(Locale.US, "%.1f %s", it, unit) } ?: "N/A"
    }

    private fun formatNullableUnit(value: Double?): String {
        return value?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"
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
            ScannerState.BACKGROUND_SCAN_WHILE_PLAYING,
            ScannerState.ERROR -> currentScannerChannel
            ScannerState.LOCKED_PLAYING -> selectedPlaybackChannel
            ScannerState.IDLE -> null
        }
    }

    private fun startMaiaWaterfallScanner(config: PlutoIqConfig) {
        val sessionId = scanSessionId
        binding.sampleText.text = "Starting Maia waterfall scanner:\nws://${config.maiaEndpoint()}/waterfall"
        decodeExecutor.execute {
            val networkSelection = findPlutoNetworkWithDiagnostics(config.maiaHost.ifBlank { MAIA_DEFAULT_HOST })
            val plutoNetwork = networkSelection.network
            if (plutoNetwork == null || plutoNetwork.networkHandle == 0L) {
                mainHandler.post {
                    if (sessionId != scanSessionId) {
                        return@post
                    }
                    scannerRunning = false
                    scanController.error()
                    binding.sampleText.text = "Maia waterfall scanner failed: Android Ethernet network was not found.\n\n" +
                        networkSelection.diagnostics
                    updateSleepBlocker()
                    updateScannerUi()
                }
                return@execute
            }

            val scanConfig = scannerWebScanConfigOverride ?: loadMaiaScanConfigDefaults()
            val scanRanges = scannerWebScanRangesOverride ?: loadMaiaScanRanges()
            val source = MaiaWaterfallWebSocketClient(
                network = plutoNetwork,
                host = config.maiaHost.ifBlank { MAIA_DEFAULT_HOST },
                port = config.maiaPort,
                path = "/waterfall",
                connectTimeoutMs = PLUTO_WS_CONNECT_TIMEOUT_MS,
                readTimeoutMs = PLUTO_WS_READ_TIMEOUT_MS,
                onReconnect = {
                    maiaWaterfallStatistics = maiaWaterfallStatistics.copy(
                        websocketReconnects = maiaWaterfallStatistics.websocketReconnects + 1,
                    )
                },
                onDroppedFrame = {
                    maiaWaterfallStatistics = maiaWaterfallStatistics.copy(
                        droppedFftFrames = maiaWaterfallStatistics.droppedFftFrames + 1,
                    )
                },
            )
            source.updateRadioMetadata(config.centerFrequencyHz, scanConfig.sampleRateHz, scanConfig.rfBandwidthHz)
            val apiClient = MaiaApiClient(
                host = config.maiaHost.ifBlank { MAIA_DEFAULT_HOST },
                port = config.maiaPort,
                network = plutoNetwork,
                connectTimeoutMs = MAIA_CONNECT_TIMEOUT_MS,
                readTimeoutMs = MAIA_READ_TIMEOUT_MS,
            )
            val controller = MaiaWaterfallScanController(
                source = source,
                tuner = MaiaHttpRadioTuner(apiClient),
                rfPathController = RfControllerPathAdapter(rfController),
                onRadioMetadataChanged = { centerHz, sampleRateHz, bandwidthHz ->
                    source.updateRadioMetadata(centerHz, sampleRateHz, bandwidthHz)
                    mainHandler.post {
                        if (sessionId != scanSessionId) {
                            return@post
                        }
                        scannerWebCurrentLoFrequencyHz = centerHz
                        scannerWebCurrentSampleRateHz = sampleRateHz
                        scannerWebCurrentBandwidthHz = bandwidthHz
                        emitScannerSnapshot()
                    }
                },
                onStateChanged = { state ->
                    mainHandler.post {
                        if (sessionId != scanSessionId) {
                            return@post
                        }
                        maiaWaterfallState = state
                        updateScannerUi()
                        emitScannerSnapshot()
                    }
                },
                onStatisticsChanged = { stats ->
                    mainHandler.post {
                        if (sessionId != scanSessionId) {
                            return@post
                        }
                        maiaWaterfallStatistics = stats
                        updateMaiaScannerDiagnostic(scanConfig, stats)
                        emitScannerSnapshot()
                    }
                },
                onConfirmedSignals = { records ->
                    mainHandler.post {
                        if (sessionId != scanSessionId) {
                            return@post
                        }
                        scanController.replaceRecords(records)
                        renderSignalTable()
                        updateScannerUi()
                        appendScannerWebEvent("success", "Confirmed signals updated: ${records.size}")
                        emitScannerSnapshot()
                    }
                },
                onScanWindowChanged = { window, index, total ->
                    mainHandler.post {
                        if (sessionId != scanSessionId) {
                            return@post
                        }
                        scannerWebActiveWindow = window
                        scannerWebWindowIndex = index
                        scannerWebWindowTotal = total
                        emitScannerSnapshot()
                    }
                },
                onMeasurement = { measurement ->
                    mainHandler.post {
                        if (sessionId != scanSessionId) {
                            return@post
                        }
                        scannerWebNoiseFloorDb = measurement.noiseFloorDb
                        emitScannerSnapshot()
                    }
                },
                onSpectrumFrame = { frame ->
                    maybeSendSpectrumFrameToWeb(sessionId, frame)
                },
            )
            mainHandler.post {
                if (sessionId != scanSessionId) {
                    controller.stop()
                    return@post
                }
                maiaWaterfallScanner = controller
                controller.start(scanConfig, scanRanges)
                binding.sampleText.text = "Maia waterfall scanner running.\n\n" + maiaScanConfigText(scanConfig)
            }
        }
    }

    private fun stopMaiaWaterfallScanner() {
        maiaWaterfallScanner?.stop()
        maiaWaterfallScanner = null
        maiaWaterfallState = MaiaScannerState.STOPPED
    }

    private fun updateMaiaScannerDiagnostic(config: MaiaScanConfig, stats: ScannerStatistics) {
        if (!scannerRunning || selectedPlaybackChannel != null || activeMode != ActiveMode.PLUTO_SCANNER) {
            return
        }
        binding.sampleText.text = buildString {
            append("Maia waterfall scanner\n")
            append("state: ${maiaWaterfallState.name.lowercase(Locale.US)}\n")
            append(maiaScanConfigText(config))
            append("\n\ncompleted_cycles: ${stats.completedScanCycles}")
            append("\ncompleted_steps: ${stats.completedScanSteps}")
            append("\nfailed_steps: ${stats.failedScanSteps}")
            append("\nreceived_fft_frames: ${stats.receivedFftFrames}")
            append("\ndropped_fft_frames: ${stats.droppedFftFrames}")
            append("\ndiscarded_retune_frames: ${stats.discardedRetuneFrames}")
            append("\nwebsocket_reconnects: ${stats.websocketReconnects}")
            append("\ncandidates_detected: ${stats.candidatesDetected}")
            append("\ncandidates_confirmed: ${stats.candidatesConfirmed}")
            append("\navg_retune_latency_ms: ${String.format(Locale.US, "%.2f", stats.averageRetuneLatencyMs)}")
            append("\navg_step_duration_ms: ${String.format(Locale.US, "%.2f", stats.averageStepDurationMs)}")
            append("\nlast_cycle_duration_ms: ${stats.lastCycleDurationMs}")
        }
    }

    private fun maiaScanConfigText(config: MaiaScanConfig): String {
        return buildString {
            append("sample_rate_hz: ${config.sampleRateHz}")
            append("\nrf_bandwidth_hz: ${config.rfBandwidthHz}")
            append("\nfrequency_step_hz: ${config.frequencyStepHz}")
            append("\nusable_span_hz: ${config.usableSpanHz}")
            append("\nlo_settling_ms: ${config.loSettlingMs}")
            append("\nretune_timeout_ms: ${config.retuneTimeoutMs}")
            append("\ndiscarded_frames_after_retune: ${config.discardedFramesAfterRetune}")
            append("\nfast_measurement_ms: ${config.fastMeasurementMs}")
            append("\ncandidate_measurement_ms: ${config.candidateMeasurementMs}")
            append("\ncandidate_revisits: ${config.requiredPositiveRevisits}/${config.candidateRevisitCount}")
        }
    }

    private fun emitScannerSnapshot() {
        if (!scannerWebViewReady) {
            return
        }
        val snapshot = buildScannerWebSnapshot()
        evaluateScannerJavascript("window.scannerUi && window.scannerUi.applySnapshot(${snapshot.toJson()});")
    }

    private fun buildScannerWebSnapshot(): ScannerWebSnapshot {
        return ScannerWebSnapshot(
            scannerState = when {
                playbackRunning -> "VIDEO_DECODE"
                maiaWaterfallState != MaiaScannerState.STOPPED -> maiaWaterfallState.name
                scannerRunning -> scanController.state.name
                else -> "STOPPED"
            },
            operatingMode = when {
                playbackRunning -> SdrOperatingMode.VIDEO_DECODE.name
                scannerRunning || maiaWaterfallScanner != null -> SdrOperatingMode.MAIA_SCAN.name
                else -> SdrOperatingMode.IDLE.name
            },
            activeScanRange = scannerWebActiveWindow?.rangeId,
            currentLoFrequencyHz = scannerWebCurrentLoFrequencyHz
                ?: selectedPlaybackChannel?.centerFrequencyHz
                ?: plutoIqConfig.centerFrequencyHz,
            scanWindowIndex = scannerWebWindowIndex,
            scanWindowTotal = scannerWebWindowTotal,
            statistics = maiaWaterfallStatistics,
            noiseFloorDb = scannerWebNoiseFloorDb,
            retuneLatencyMs = maiaWaterfallStatistics.averageRetuneLatencyMs,
            confirmedSignals = scanController.records(),
            diagnostics = scannerWebDiagnostics ?: binding.sampleText.text?.toString(),
            events = scannerWebEvents.toList(),
        )
    }

    private fun appendScannerWebEvent(type: String, message: String) {
        val event = ScannerEventRecord(
            type = type,
            message = message,
            timestampMs = System.currentTimeMillis(),
        )
        scannerWebEvents.addFirst(event)
        while (scannerWebEvents.size > SCANNER_WEB_MAX_EVENTS) {
            scannerWebEvents.removeLast()
        }
        if (scannerWebViewReady) {
            evaluateScannerJavascript("window.scannerUi && window.scannerUi.appendEvent(${event.toJson()});")
        }
    }

    private fun maybeSendSpectrumFrameToWeb(sessionId: Long, frame: WaterfallFrame) {
        if (!scannerWebSpectrumVisible || !scannerWebViewReady) {
            return
        }
        val nowNs = System.nanoTime()
        if (nowNs - lastSpectrumWebUpdateNs < SCANNER_WEB_SPECTRUM_THROTTLE_NS) {
            return
        }
        lastSpectrumWebUpdateNs = nowNs
        val downsampled = downsampleSpectrum(frame.powerDb, SCANNER_WEB_MAX_SPECTRUM_BINS)
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("centerFrequencyHz", frame.centerFrequencyHz)
            .put("sampleRateHz", frame.sampleRateHz)
            .put("bandwidthHz", frame.bandwidthHz)
            .put("sequenceNumber", frame.sequenceNumber)
            .put("timestampNs", frame.timestampNs)
            .put("binCount", frame.binCount)
            .put("powerDb", JSONArray().also { array ->
                downsampled.forEach { array.put(it.toDouble()) }
            })
        mainHandler.post {
            if (sessionId != scanSessionId || !scannerWebSpectrumVisible || !scannerWebViewReady) {
                return@post
            }
            evaluateScannerJavascript("window.scannerUi && window.scannerUi.updateSpectrum($json);")
        }
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

    private fun evaluateScannerJavascript(script: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { evaluateScannerJavascript(script) }
            return
        }
        if (!scannerWebViewReady) {
            return
        }
        binding.scannerWebView.evaluateJavascript(script, null)
    }

    private fun signalRecordByWebId(id: Long): DetectedSignalRecord? {
        return scanController.records().firstOrNull { (it.measuredFrequencyHz ?: it.channel.centerFrequencyHz) == id }
    }

    private fun releaseSignalToScanner() {
        stopPlayback()
        selectedPlaybackChannel = null
        scannerWebSpectrumVisible = true
        binding.videoFrameContainer.visibility = View.GONE
        if (!scannerRunning) {
            startScannerMode()
        } else {
            maiaWaterfallScanner?.resume()
        }
        appendScannerWebEvent("system", "Video released; scanning resumed")
        emitScannerSnapshot()
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
        val activeScanSessionId = scanSessionId
        currentScannerChannel = channel
        scanController.startScanning()
        updateScannerUi()
        val scanConfig = scannerConfigForChannel(channel)
        binding.sampleText.text = scannerProgressText(
            title = "Scanning live ${scanConfig.iqSource.label}",
            channel = channel,
            config = scanConfig,
        )
        decodeExecutor.execute {
            val initialResult = probeChannelForSignal(channel, ScannerProbeProfile.QUICK)
            val confirmed = if (initialResult.signalPresent) {
                confirmPositiveScanResult(channel, initialResult)
            } else {
                ScannerProbeSelection(channel, initialResult)
            }
            mainHandler.post {
                if (!scannerRunning ||
                    selectedPlaybackChannel != null ||
                    activeScanSessionId != scanSessionId) {
                    return@post
                }
                if (confirmed.result.sourceFailed) {
                    handleScannerPlutoRuntimeFailure(confirmed.channel, confirmed.result)
                    return@post
                }
                scanController.applyProbeResult(confirmed.channel, confirmed.result)
                scanController.expireStaleRecords()
                renderSignalTable()
                updateLastProbeDiagnostic(confirmed.channel, confirmed.result)
                updateScannerUi()
                if (scannerRunning && selectedPlaybackChannel == null) {
                    scheduleNextScanStep()
                }
            }
        }
    }

    private fun confirmPositiveScanResult(
        channel: KnownChannel,
        initialResult: SignalProbeResult,
    ): ScannerProbeSelection {
        if (initialResult.sourceFailed) {
            return ScannerProbeSelection(channel, initialResult)
        }
        var confirmationResult = probeChannelForSignal(channel, ScannerProbeProfile.DEEP_CONFIRM)
        if (confirmationResult.sourceFailed) {
            return ScannerProbeSelection(channel, confirmationResult)
        }
        var acquisitionPasses = 1
        while (
            confirmationResult.signalPresent &&
            gainController.snapshot().mode == GainControlMode.ACQUISITION &&
            acquisitionPasses < SCANNER_GAIN_ACQUISITION_CONFIRMATION_PASSES
        ) {
            confirmationResult = probeChannelForSignal(channel, ScannerProbeProfile.DEEP_CONFIRM)
            if (confirmationResult.sourceFailed) {
                return ScannerProbeSelection(channel, confirmationResult)
            }
            acquisitionPasses += 1
        }

        if (!confirmationResult.signalPresent) {
            return ScannerProbeSelection(
                channel = channel,
                result = initialResult.copy(
                    signalPresent = false,
                    signalType = SignalType.UNKNOWN,
                    rssiDbfs = null,
                    confidence = minOf(initialResult.confidence, confirmationResult.confidence),
                    previewFrame = null,
                    diagnostic = initialResult.diagnostic +
                        "\nscanner_positive_confirmation: no" +
                        "\nscanner_initial_confidence: ${String.format(Locale.US, "%.3f", initialResult.confidence)}" +
                        "\nscanner_confirmation_confidence: ${String.format(Locale.US, "%.3f", confirmationResult.confidence)}" +
                        "\nscanner_gain_acquisition_passes: $acquisitionPasses" +
                        "\nscanner_confirmation_diagnostic:\n${confirmationResult.diagnostic}",
                ),
            )
        }

        var bestSelection = ScannerProbeSelection(
            channel = channel,
            result = confirmationResult.copy(
                diagnostic = confirmationResult.diagnostic +
                    "\nscanner_positive_confirmation: yes" +
                    "\nscanner_initial_confidence: ${String.format(Locale.US, "%.3f", initialResult.confidence)}" +
                    "\nscanner_confirmation_confidence: ${String.format(Locale.US, "%.3f", confirmationResult.confidence)}" +
                    "\nscanner_gain_acquisition_passes: $acquisitionPasses",
            ),
        )

        for (nearbyChannel in nearbyAnalogChannels(channel)) {
            val nearbyResult = probeChannelForSignal(nearbyChannel, ScannerProbeProfile.DEEP_CONFIRM)
            if (nearbyResult.sourceFailed) {
                return ScannerProbeSelection(nearbyChannel, nearbyResult)
            }
            if (!nearbyResult.signalPresent) {
                continue
            }
            if (nearbyResult.imageQuality > bestSelection.result.imageQuality + SCANNER_NEARBY_CHANNEL_Q_SWITCH_MARGIN ||
                (nearbyResult.imageQuality >= bestSelection.result.imageQuality - SCANNER_NEARBY_CHANNEL_Q_TIE_MARGIN &&
                    nearbyChannel.centerFrequencyHz > bestSelection.channel.centerFrequencyHz)
            ) {
                bestSelection = ScannerProbeSelection(
                    channel = nearbyChannel,
                    result = nearbyResult.copy(
                        diagnostic = nearbyResult.diagnostic +
                            "\nscanner_positive_confirmation: yes" +
                            "\nscanner_refined_from_hz: ${channel.centerFrequencyHz}" +
                            "\nscanner_refined_from_q: ${String.format(Locale.US, "%.3f", confirmationResult.imageQuality)}" +
                            "\nscanner_refined_selected: yes",
                    ),
                )
            }
        }

        return bestSelection
    }

    private fun nearbyAnalogChannels(channel: KnownChannel): List<KnownChannel> {
        return ChannelPlan.knownChannels
            .asSequence()
            .filter { it !== channel }
            .filter { it.expectedSignalType == SignalType.ANALOG }
            .filter { it.rfFrontendProfileId == channel.rfFrontendProfileId }
            .filter { kotlin.math.abs(it.centerFrequencyHz - channel.centerFrequencyHz) <= SCANNER_NEARBY_CHANNEL_RADIUS_HZ }
            .sortedBy { kotlin.math.abs(it.centerFrequencyHz - channel.centerFrequencyHz) }
            .toList()
    }

    private fun probeChannelForSignal(
        channel: KnownChannel,
        profile: ScannerProbeProfile = ScannerProbeProfile.QUICK,
    ): SignalProbeResult {
        val channelGainSnapshot = if (profile == ScannerProbeProfile.QUICK) {
            gainController.prepareScanChannel(channel)
        } else {
            gainController.tuneChannel(channel, "confirmation channel tune")
        }
        val config = scannerConfigForChannel(channel).copy(gainDb = channelGainSnapshot.plutoGainDb)
        if (config.iqSource == PlutoIqSource.WEBSOCKET) {
            return probeChannelForSignalViaWebSocket(channel, profile, config)
        }
        val uri = plutoIioIpUri(config)
        val host = config.maiaHost.ifBlank { MAIA_DEFAULT_HOST }
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val networkSelection = waitForPlutoIioNetworkWithDiagnostics(host)
        val plutoNetwork = networkSelection.network
        if (plutoNetwork == null || plutoNetwork.networkHandle == 0L) {
            return scannerSourceFailureResult(
                uri = uri,
                config = config,
                reason = "Pluto IP IIO network was not found",
                diagnostic = networkSelection.diagnostics,
            )
        }

        val previousBoundNetwork = connectivityManager.boundNetworkForProcess
        if (!connectivityManager.bindProcessToNetwork(plutoNetwork)) {
            return scannerSourceFailureResult(
                uri = uri,
                config = config,
                reason = "bindProcessToNetwork failed for ${networkLabel(plutoNetwork)}",
                diagnostic = networkSelection.diagnostics,
            )
        }

        var sessionHandle = 0L
        return try {
            val iqMetricsDiagnostic = probePlutoIqMetrics(
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
                windowMs = scannerIqMetricsWindowMs(profile),
            )
            if (plutoNativeDiagnosticFailed(iqMetricsDiagnostic)) {
                val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                return scannerSourceFailureResult(
                    uri = uri,
                    config = config,
                    reason = "Pluto IQ metrics probe failed",
                    diagnostic = "$iqMetricsDiagnostic\nnative_error: $nativeError\n${networkSelection.diagnostics}",
                )
            }
            sessionHandle = createPlutoAnalogVideoPlaybackSession(
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
                videoStandard = VideoStandard.AUTO.nativeValue,
            )
            if (sessionHandle == 0L) {
                val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                return scannerSourceFailureResult(
                    uri = uri,
                    config = config,
                    reason = "native Pluto playback probe session could not be created",
                    diagnostic = "$iqMetricsDiagnostic\nprobe_session_failed: $nativeError\n${networkSelection.diagnostics}",
                )
            }

            Thread.sleep(SCANNER_RETUNE_SETTLE_MS)
            val discardedFrames = scannerProbeDiscardFrameCount(profile)
            repeat(discardedFrames) { discardIndex ->
                val packet = decodeNextAnalogVideoPlaybackFrame(sessionHandle)
                if (packet.isEmpty()) {
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    return scannerSourceFailureResult(
                        uri = uri,
                        config = config,
                        reason = "native Pluto playback probe returned no frame while discarding frame $discardIndex",
                        diagnostic = "$iqMetricsDiagnostic\nnative_error: $nativeError\n${networkSelection.diagnostics}",
                    )
                }
            }
            var bestFrame: DecodedFrame? = null
            var bestDiagnostic = ""
            var bestConfidence = 0.0
            var detectedFrameCount = 0
            var sourceFailureResult: SignalProbeResult? = null

            fun scoreNextFrame(frameIndex: Int): Boolean {
                val packet = decodeNextAnalogVideoPlaybackFrame(sessionHandle)
                if (packet.isEmpty()) {
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    sourceFailureResult = scannerSourceFailureResult(
                        uri = uri,
                        config = config,
                        reason = "native Pluto playback probe returned no frame at scored frame $frameIndex",
                        diagnostic = "$iqMetricsDiagnostic\nnative_error: $nativeError\n${networkSelection.diagnostics}",
                    )
                    return false
                }
                val frame = grayscaleFramePacketToBitmap(
                    packet = packet,
                    includeDiagnostic = true,
                )
                if (frame == null) {
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    sourceFailureResult = scannerSourceFailureResult(
                        uri = uri,
                        config = config,
                        reason = "native Pluto playback probe returned a malformed frame packet at scored frame $frameIndex",
                        diagnostic = "$iqMetricsDiagnostic\nnative_error: $nativeError\n${networkSelection.diagnostics}",
                    )
                    return false
                }
                val diagnostic = frame.diagnostic
                val confidence = analogConfidence(diagnostic)
                if (isAnalogVideoDetected(diagnostic)) {
                    detectedFrameCount += 1
                }
                if (confidence >= bestConfidence) {
                    bestFrame = frame
                    bestDiagnostic = diagnostic
                    bestConfidence = confidence
                }
                return true
            }

            repeat(scannerFastProbeFrameCount(profile)) { index ->
                if (!scoreNextFrame(index)) {
                    return sourceFailureResult ?: scannerSourceFailureResult(
                        uri = uri,
                        config = config,
                        reason = "native Pluto playback probe failed",
                        diagnostic = "$iqMetricsDiagnostic\n${networkSelection.diagnostics}",
                    )
                }
            }

            val needsConfirmation = detectedFrameCount > 0 ||
                bestConfidence >= SCANNER_CONFIRMATION_CONFIDENCE
            if (needsConfirmation) {
                repeat(scannerConfirmProbeFrameCount(profile)) { index ->
                    if (!scoreNextFrame(scannerFastProbeFrameCount(profile) + index)) {
                        return sourceFailureResult ?: scannerSourceFailureResult(
                            uri = uri,
                            config = config,
                            reason = "native Pluto playback confirmation probe failed",
                            diagnostic = "$iqMetricsDiagnostic\n${networkSelection.diagnostics}",
                        )
                    }
                }
            }

            val scoredFrameCount = scannerFastProbeFrameCount(profile) +
                if (needsConfirmation) scannerConfirmProbeFrameCount(profile) else 0
            val analogDetected = detectedFrameCount >= scannerRequiredDetectedFrameCount(profile) &&
                bestConfidence >= SCANNER_MIN_ANALOG_CONFIDENCE
            val previewQuality = bestFrame?.bitmap?.let { imageQualityScore(it) } ?: 0.0
            val result = SignalProbeResult(
                signalPresent = analogDetected,
                signalType = if (analogDetected) SignalType.ANALOG else SignalType.UNKNOWN,
                rssiDbfs = if (analogDetected) estimateRssiDbfs(bestConfidence) else null,
                confidence = bestConfidence,
                previewFrame = if (analogDetected) bestFrame?.bitmap else null,
                diagnostic = "scanner_stage: cs8_libiio_video_decode\nscanner_probe_profile: ${profile.name.lowercase(Locale.US)}\nscanner_uri: $uri\nscanner_sample_format: ${config.sampleFormat.metadataValue}\nscanner_iq_source: ${config.iqSource.preferenceValue}\nscanner_iio_transport: ip_iio\nscanner_android_network: ${networkLabel(plutoNetwork)}\nscanner_center_frequency_hz: ${config.centerFrequencyHz}\nscanner_retune_settle_ms: $SCANNER_RETUNE_SETTLE_MS\nscanner_discarded_frames: $discardedFrames\nscanner_detected_frames: $detectedFrameCount\nscanner_scored_frames: $scoredFrameCount\n$iqMetricsDiagnostic\n$bestDiagnostic",
                imageQuality = if (analogDetected) previewQuality else 0.0,
            )
            val metrics = gainMetricsFromDiagnostics(
                iqMetricsDiagnostic = iqMetricsDiagnostic,
                videoDiagnostic = bestDiagnostic,
                videoConfidence = bestConfidence,
            )
            gainController.update(metrics)
            result
        } finally {
            if (sessionHandle != 0L) {
                closeAnalogVideoPlaybackSession(sessionHandle)
            }
            restorePlaybackNetworkBinding(previousBoundNetwork, plutoNetwork)
        }
    }

    private fun probeChannelForSignalViaWebSocket(
        channel: KnownChannel,
        profile: ScannerProbeProfile,
        config: PlutoIqConfig,
    ): SignalProbeResult {
        val uri = "ws://${config.plutoWebSocketEndpoint()}${config.plutoWebSocketPath}"
        val primeDiagnostic = primePlutoUsbForWebSocket(config)
        val networkSelection = findPlutoNetworkWithDiagnostics(config.maiaHost.ifBlank { MAIA_DEFAULT_HOST })
        val plutoNetwork = networkSelection.network
        if (plutoNetwork == null || plutoNetwork.networkHandle == 0L) {
            return scannerSourceFailureResult(
                uri = uri,
                config = config,
                reason = "Android network to Pluto WebSocket host was not found",
                diagnostic = "$primeDiagnostic\n${networkSelection.diagnostics}",
            )
        }
        val tcpTest = testTcpConnection(
            network = plutoNetwork,
            host = config.maiaHost.ifBlank { MAIA_DEFAULT_HOST },
            port = config.maiaPort,
            label = "Pluto WebSocket TCP connection test",
            connectTimeoutMs = PLUTO_WS_CONNECT_TIMEOUT_MS,
        )
        if (!tcpTest.connectionEstablished) {
            return scannerSourceFailureResult(
                uri = uri,
                config = config,
                reason = "Pluto WebSocket TCP ${config.maiaHost}:${config.maiaPort} was not reachable",
                diagnostic = "$primeDiagnostic\n${networkSelection.diagnostics}\n${tcpTest.diagnostics}",
            )
        }

        val transport = PlutoWebSocketTransport.create(
            network = plutoNetwork,
            host = config.maiaHost,
            port = config.maiaPort,
            path = config.plutoWebSocketPath,
            connectTimeoutMs = PLUTO_WS_CONNECT_TIMEOUT_MS,
            readTimeoutMs = PLUTO_WS_READ_TIMEOUT_MS,
        )
        var sessionHandle = 0L
        return try {
            sessionHandle = createPlutoWebSocketAnalogVideoPlaybackSession(
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
                return scannerSourceFailureResult(
                    uri = uri,
                    config = config,
                    reason = "native Pluto WebSocket playback probe session could not be created",
                    diagnostic = "$primeDiagnostic\nprobe_session_failed: $nativeError\n${networkSelection.diagnostics}",
                )
            }

            Thread.sleep(SCANNER_RETUNE_SETTLE_MS)
            val discardedFrames = scannerProbeDiscardFrameCount(profile)
            repeat(discardedFrames) { discardIndex ->
                val packet = decodeNextAnalogVideoPlaybackFrame(sessionHandle)
                if (packet.isEmpty()) {
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    return scannerSourceFailureResult(
                        uri = uri,
                        config = config,
                        reason = "native Pluto WebSocket probe returned no frame while discarding frame $discardIndex",
                        diagnostic = "$primeDiagnostic\nnative_error: $nativeError\n${networkSelection.diagnostics}",
                    )
                }
            }

            var bestFrame: DecodedFrame? = null
            var bestDiagnostic = ""
            var bestConfidence = 0.0
            var detectedFrameCount = 0
            var sourceFailureResult: SignalProbeResult? = null

            fun scoreNextFrame(frameIndex: Int): Boolean {
                val packet = decodeNextAnalogVideoPlaybackFrame(sessionHandle)
                if (packet.isEmpty()) {
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    sourceFailureResult = scannerSourceFailureResult(
                        uri = uri,
                        config = config,
                        reason = "native Pluto WebSocket probe returned no frame at scored frame $frameIndex",
                        diagnostic = "$primeDiagnostic\nnative_error: $nativeError\n${networkSelection.diagnostics}",
                    )
                    return false
                }
                val frame = grayscaleFramePacketToBitmap(
                    packet = packet,
                    includeDiagnostic = true,
                )
                if (frame == null) {
                    val nativeError = consumeLastNativeError().ifBlank { "unavailable" }
                    sourceFailureResult = scannerSourceFailureResult(
                        uri = uri,
                        config = config,
                        reason = "native Pluto WebSocket probe returned a malformed frame packet at scored frame $frameIndex",
                        diagnostic = "$primeDiagnostic\nnative_error: $nativeError\n${networkSelection.diagnostics}",
                    )
                    return false
                }
                val diagnostic = frame.diagnostic
                val confidence = analogConfidence(diagnostic)
                if (isAnalogVideoDetected(diagnostic)) {
                    detectedFrameCount += 1
                }
                if (confidence >= bestConfidence) {
                    bestFrame = frame
                    bestDiagnostic = diagnostic
                    bestConfidence = confidence
                }
                return true
            }

            repeat(scannerFastProbeFrameCount(profile)) { index ->
                if (!scoreNextFrame(index)) {
                    return sourceFailureResult ?: scannerSourceFailureResult(
                        uri = uri,
                        config = config,
                        reason = "native Pluto WebSocket playback probe failed",
                        diagnostic = "$primeDiagnostic\n${networkSelection.diagnostics}",
                    )
                }
            }

            val needsConfirmation = detectedFrameCount > 0 ||
                bestConfidence >= SCANNER_CONFIRMATION_CONFIDENCE
            if (needsConfirmation) {
                repeat(scannerConfirmProbeFrameCount(profile)) { index ->
                    if (!scoreNextFrame(scannerFastProbeFrameCount(profile) + index)) {
                        return sourceFailureResult ?: scannerSourceFailureResult(
                            uri = uri,
                            config = config,
                            reason = "native Pluto WebSocket confirmation probe failed",
                            diagnostic = "$primeDiagnostic\n${networkSelection.diagnostics}",
                        )
                    }
                }
            }

            val scoredFrameCount = scannerFastProbeFrameCount(profile) +
                if (needsConfirmation) scannerConfirmProbeFrameCount(profile) else 0
            val analogDetected = detectedFrameCount >= scannerRequiredDetectedFrameCount(profile) &&
                bestConfidence >= SCANNER_MIN_ANALOG_CONFIDENCE
            val previewQuality = bestFrame?.bitmap?.let { imageQualityScore(it) } ?: 0.0
            val iqMetricsDiagnostic = "pluto_ws_iq_metrics: unavailable"
            val result = SignalProbeResult(
                signalPresent = analogDetected,
                signalType = if (analogDetected) SignalType.ANALOG else SignalType.UNKNOWN,
                rssiDbfs = if (analogDetected) estimateRssiDbfs(bestConfidence) else null,
                confidence = bestConfidence,
                previewFrame = if (analogDetected) bestFrame?.bitmap else null,
                diagnostic = "scanner_stage: cs8_websocket_video_decode\nscanner_probe_profile: ${profile.name.lowercase(Locale.US)}\nscanner_uri: $uri\nscanner_sample_format: ${IqSampleFormat.CS8.metadataValue}\nscanner_iq_source: ${config.iqSource.preferenceValue}\nscanner_android_network: ${networkLabel(plutoNetwork)}\nscanner_center_frequency_hz: ${config.centerFrequencyHz}\nscanner_channel: ${channel.channelName}\nscanner_retune_settle_ms: $SCANNER_RETUNE_SETTLE_MS\nscanner_discarded_frames: $discardedFrames\nscanner_detected_frames: $detectedFrameCount\nscanner_scored_frames: $scoredFrameCount\n$primeDiagnostic\n$iqMetricsDiagnostic\n$bestDiagnostic",
                imageQuality = if (analogDetected) previewQuality else 0.0,
            )
            gainController.update(
                gainMetricsFromDiagnostics(
                    iqMetricsDiagnostic = iqMetricsDiagnostic,
                    videoDiagnostic = bestDiagnostic,
                    videoConfidence = bestConfidence,
                ),
            )
            result
        } finally {
            if (sessionHandle != 0L) {
                closeAnalogVideoPlaybackSession(sessionHandle)
            }
            transport.close()
        }
    }

    private fun configForChannel(channel: KnownChannel): PlutoIqConfig {
        return plutoIqConfig.copy(centerFrequencyHz = channel.centerFrequencyHz)
    }

    private fun captureConfigForCurrentSelection(): PlutoIqConfig {
        val selectedChannel = selectedPlaybackChannel
        val baseConfig = if (selectedChannel != null) {
            configForChannel(selectedChannel)
        } else {
            currentPlutoSessionRecovery()?.config ?: plutoIqConfig
        }
        return baseConfig
            .forcedSampleFormat(IqSampleFormat.CS8)
            .copy(
                gainDb = gainController.currentPlutoGainDb,
                captureDurationSec = baseConfig.captureDurationSec.coerceAtLeast(RECORD_IQ_MIN_CAPTURE_DURATION_SEC),
            )
    }

    private fun scannerConfigForChannel(channel: KnownChannel): PlutoIqConfig {
        return configForChannel(channel).copy(
            sampleFormat = IqSampleFormat.CS8,
        )
    }

    private fun scannerProbeDiscardFrameCount(profile: ScannerProbeProfile): Int {
        return when (profile) {
            ScannerProbeProfile.QUICK -> SCANNER_QUICK_PROBE_DISCARD_FRAME_COUNT
            ScannerProbeProfile.DEEP_CONFIRM -> SCANNER_DEEP_PROBE_DISCARD_FRAME_COUNT
        }
    }

    private fun scannerFastProbeFrameCount(profile: ScannerProbeProfile): Int {
        return when (profile) {
            ScannerProbeProfile.QUICK -> SCANNER_QUICK_PROBE_FRAME_COUNT
            ScannerProbeProfile.DEEP_CONFIRM -> SCANNER_DEEP_FAST_PROBE_FRAME_COUNT
        }
    }

    private fun scannerConfirmProbeFrameCount(profile: ScannerProbeProfile): Int {
        return when (profile) {
            ScannerProbeProfile.QUICK -> SCANNER_QUICK_CONFIRM_PROBE_FRAME_COUNT
            ScannerProbeProfile.DEEP_CONFIRM -> SCANNER_DEEP_CONFIRM_PROBE_FRAME_COUNT
        }
    }

    private fun scannerRequiredDetectedFrameCount(profile: ScannerProbeProfile): Int {
        return when (profile) {
            ScannerProbeProfile.QUICK -> SCANNER_QUICK_REQUIRED_DETECTED_FRAME_COUNT
            ScannerProbeProfile.DEEP_CONFIRM -> SCANNER_DEEP_REQUIRED_DETECTED_FRAME_COUNT
        }
    }

    private fun scannerIqMetricsWindowMs(profile: ScannerProbeProfile): Double {
        return when (profile) {
            ScannerProbeProfile.QUICK -> SCANNER_QUICK_IQ_METRICS_WINDOW_MS
            ScannerProbeProfile.DEEP_CONFIRM -> SCANNER_DEEP_IQ_METRICS_WINDOW_MS
        }
    }

    private fun plutoNativeDiagnosticFailed(diagnostic: String): Boolean {
        return Regex("""(?m)^\s*status\s*:\s*failed\s*$""").containsMatchIn(diagnostic)
    }

    private fun scannerSourceFailureResult(
        uri: String,
        config: PlutoIqConfig,
        reason: String,
        diagnostic: String,
    ): SignalProbeResult {
        return SignalProbeResult(
            signalPresent = false,
            signalType = SignalType.UNKNOWN,
            rssiDbfs = null,
            confidence = 0.0,
            previewFrame = null,
            diagnostic = buildString {
                append("scanner_stage: ")
                append(
                    when (config.iqSource) {
                        PlutoIqSource.IIO -> "cs8_libiio_video_decode"
                        PlutoIqSource.WEBSOCKET -> "cs8_websocket_video_decode"
                    },
                )
                append("\nscanner_source_failed: true")
                append("\nscanner_source_failure_reason: $reason")
                append("\nscanner_uri: $uri")
                append("\nscanner_sample_format: ${config.sampleFormat.metadataValue}")
                append("\nscanner_iq_source: ${config.iqSource.preferenceValue}")
                if (config.iqSource == PlutoIqSource.IIO) {
                    append("\nscanner_iio_transport: ip_iio")
                }
                append("\nscanner_center_frequency_hz: ${config.centerFrequencyHz}")
                if (diagnostic.isNotBlank()) {
                    append("\n")
                    append(diagnostic)
                }
            },
            sourceFailed = true,
        )
    }

    private fun gainMetricsFromDiagnostics(
        iqMetricsDiagnostic: String,
        videoDiagnostic: String,
        videoConfidence: Double,
    ): GainMetrics {
        val peakDbfs = diagnosticNumber(iqMetricsDiagnostic, "peak_dbfs")
            ?: diagnosticNumber(videoDiagnostic, "peak_level_dbfs")
            ?: Double.NaN
        val rmsDbfs = diagnosticNumber(iqMetricsDiagnostic, "rms_dbfs") ?: Double.NaN
        val noiseFloorDbfs = diagnosticNumber(iqMetricsDiagnostic, "noise_floor_dbfs") ?: Double.NaN
        val snrDb = diagnosticNumber(iqMetricsDiagnostic, "snr_db") ?: Double.NaN
        return GainMetrics(
            peakDbfs = peakDbfs,
            rmsDbfs = rmsDbfs,
            noiseFloorDbfs = noiseFloorDbfs,
            snrDb = snrDb,
            videoConfidence = videoConfidence.coerceIn(0.0, 1.0),
            syncConfidence = syncConfidence(videoDiagnostic),
        )
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

    private fun syncConfidence(diagnostic: String): Double {
        val syncLocked = diagnostic.contains("sync_locked: yes", ignoreCase = true) ||
            diagnostic.contains("sync_locked=yes", ignoreCase = true)
        val syncScore = diagnosticNumber(diagnostic, "sync_score") ?: 0.0
        val lineStability = diagnosticNumber(diagnostic, "line_stability")
            ?: diagnosticNumber(diagnostic, "line_stability_score")
            ?: 0.0
        val frameSyncEdges = diagnosticNumber(diagnostic, "frame_sync_edges")
            ?: diagnosticNumber(diagnostic, "detected_frame_sync_edges")
            ?: 0.0
        val lockBoost = if (syncLocked) 0.45 else 0.0
        return (lockBoost + syncScore * 0.35 + lineStability * 0.25 + (frameSyncEdges / 4.0) * 0.15)
            .coerceIn(0.0, 1.0)
    }

    private fun estimateRssiDbfs(confidence: Double): Double {
        return -95.0 + confidence.coerceIn(0.0, 1.0) * 45.0
    }

    private fun imageQualityScore(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 16 || height < 16) {
            return 0.0
        }

        val blockSize = (minOf(width, height) / 18).coerceIn(6, 14)
        val blockMeans = mutableListOf<Double>()
        var globalSum = 0.0
        var globalSumSquares = 0.0
        var globalCount = 0
        var localVarianceSum = 0.0
        var localBlockCount = 0

        var y = 0
        while (y + blockSize <= height) {
            var x = 0
            while (x + blockSize <= width) {
                var sum = 0.0
                var sumSquares = 0.0
                var count = 0
                for (yy in y until y + blockSize) {
                    for (xx in x until x + blockSize) {
                        val value = grayAt(bitmap, xx, yy)
                        sum += value
                        sumSquares += value * value
                        count += 1
                    }
                }

                if (count > 0) {
                    val mean = sum / count.toDouble()
                    val variance = ((sumSquares / count.toDouble()) - (mean * mean)).coerceAtLeast(0.0)
                    blockMeans.add(mean)
                    localVarianceSum += variance
                    localBlockCount += 1
                    globalSum += sum
                    globalSumSquares += sumSquares
                    globalCount += count
                }
                x += blockSize
            }
            y += blockSize
        }

        if (globalCount <= 0 || blockMeans.size < 4) {
            return 0.0
        }

        val mean = globalSum / globalCount.toDouble()
        val globalVariance = ((globalSumSquares / globalCount.toDouble()) - (mean * mean)).coerceAtLeast(0.0)
        if (globalVariance <= 1.0) {
            return 0.0
        }

        val blockMeanAverage = blockMeans.sum() / blockMeans.size.toDouble()
        var blockVarianceSum = 0.0
        for (blockMean in blockMeans) {
            val delta = blockMean - blockMeanAverage
            blockVarianceSum += delta * delta
        }
        val blockVariance = blockVarianceSum / blockMeans.size.toDouble()
        val localVariance = localVarianceSum / localBlockCount.coerceAtLeast(1).toDouble()
        val structureRatio = (blockVariance / (globalVariance + 1.0)).coerceIn(0.0, 1.0)
        val localNoiseRatio = (localVariance / (globalVariance + 1.0)).coerceIn(0.0, 1.0)
        val exposureScore = (1.0 - (kotlin.math.abs(mean - 127.5) / 127.5)).coerceIn(0.0, 1.0)
        val contrastScore = (sqrt(globalVariance) / 82.0).coerceIn(0.0, 1.0)
        val noisePenalty = (localNoiseRatio * 0.85).coerceIn(0.0, 0.85)
        return (structureRatio * 0.55 + contrastScore * 0.25 + exposureScore * 0.20) *
            (1.0 - noisePenalty)
    }

    private fun grayAt(bitmap: Bitmap, x: Int, y: Int): Double {
        return bitmap.getPixel(x, y).and(0xFF).toDouble()
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

    private fun scannerProgressText(
        title: String,
        channel: KnownChannel?,
        config: PlutoIqConfig,
    ): String {
        return buildString {
            append(title)
            append("\n\nsource: ${config.iqSource.label} live scan")
            append("\nsample_format: ${config.sampleFormat.metadataValue}")
            append("\nsample_rate_hz: ${config.sampleRateHz}")
            append("\nrf_bandwidth_hz: ${config.rfBandwidthHz}")
            append("\ngain_db: ${String.format(Locale.US, "%.1f", config.gainDb)}")
            append("\nrecording_to_file: no")
            if (channel != null) {
                append("\n\nchannel: ${channel.bandName} / ${channel.channelName}")
                append("\ncenter_frequency_hz: ${channel.centerFrequencyHz}")
                append("\ncenter_frequency: ${formatFrequency(channel.centerFrequencyHz)}")
            }
            append("\n\nWaiting for live probe results...")
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
                    record.measuredFrequencyHz?.let { measured ->
                        append(" | Measured: ")
                        append(formatFrequency(measured))
                    }
                    append(" | ")
                    append(record.signalType.name.lowercase(Locale.US))
                    record.frequencyOffsetHz?.let { offset ->
                        append("\nOffset: ")
                        append(formatFrequency(kotlin.math.abs(offset)))
                        append(if (offset >= 0) " high" else " low")
                    }
                    append("\nRSSI: ")
                    append(record.rssiDbfs?.let { String.format(Locale.US, "%.1f dBFS", it) } ?: "--")
                    record.snrDb?.let {
                        append(" | SNR: ")
                        append(String.format(Locale.US, "%.1f dB", it))
                    }
                    record.occupiedBandwidthHz?.let {
                        append(" | BW: ")
                        append(formatFrequency(it))
                    }
                    record.noiseFloorDb?.let {
                        append("\nNoise: ")
                        append(String.format(Locale.US, "%.1f dB", it))
                    }
                    append(" | Dir: ")
                    append(record.direction.name.lowercase(Locale.US))
                    append(" | C: ")
                    append(String.format(Locale.US, "%.2f", record.confidence))
                    append(" | N: ")
                    append(record.detectionCount)
                    append(" | Q: ")
                    append(String.format(Locale.US, "%.2f", record.imageQuality))
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
        stopMaiaWaterfallScanner()
        selectedPlaybackChannel = record.channel
        currentScannerChannel = null
        scannerRunning = false
        scanController.lockPlaying()
        updateCurrentFrequencyLabel(record.measuredFrequencyHz ?: record.channel.centerFrequencyHz)
        updateScannerUi()
        binding.videoFrameContainer.visibility = View.VISIBLE
        record.previewFrame?.let { setVideoFrameBitmap(it) }
        startSelectedChannelPlayback(
            if (record.measuredFrequencyHz != null) {
                record.channel.copy(centerFrequencyHz = record.measuredFrequencyHz)
            } else {
                record.channel
            },
        )
    }

    private fun startSelectedChannelPlayback(channel: KnownChannel) {
        stopSpectrum()
        stopPlayback()
        selectedPlaybackChannel = channel
        gainController.tuneChannel(channel, "selected playback tune")
        scanController.lockPlaying()
        updateScannerUi()
        startConfiguredPlutoCs8Playback(
            VideoStandard.AUTO,
            configForChannel(channel)
                .forcedSampleFormat(IqSampleFormat.CS8)
                .copy(gainDb = gainController.currentPlutoGainDb),
        )
    }

    private fun startConfiguredPlutoCs8Playback(
        standard: VideoStandard,
        config: PlutoIqConfig,
    ) {
        val playbackConfig = config.forcedSampleFormat(IqSampleFormat.CS8)
        when (playbackConfig.iqSource) {
            PlutoIqSource.IIO -> {
                activeMode = ActiveMode.PLUTO_IP_PLAYBACK
                preparePlutoIpIioPlayback(standard, playbackConfig)
            }
            PlutoIqSource.WEBSOCKET -> {
                activeMode = ActiveMode.PLUTO_WS_PLAYBACK
                preparePlutoWebSocketPlayback(standard, playbackConfig)
            }
        }
    }

    private fun retunePlaybackToAdjacentChannel(direction: Int) {
        val current = selectedPlaybackChannel ?: return
        val target = adjacentAnalogChannel(current, direction) ?: return
        selectedPlaybackChannel = target
        currentScannerChannel = null
        scannerRunning = true
        scanController.lockPlaying()
        updateCurrentFrequencyLabel(target.centerFrequencyHz)
        updateScannerUi()
        startSelectedChannelPlayback(target)
    }

    private fun updateGainControls() {
        val snapshot = gainController.snapshot()
        binding.gainControls.visibility = View.VISIBLE
        binding.gainModeButton.text = if (snapshot.manualGainControl) {
            "Manual"
        } else {
            "AGC"
        }
        binding.gainDownButton.isEnabled = true
        binding.gainModeButton.isEnabled = true
        binding.gainUpButton.isEnabled = true
    }

    private fun toggleManualGainControl() {
        val snapshot = gainController.setManualGainControl(!gainController.isManualGainControl)
        applyGainSnapshotToUiAndActiveSession(snapshot, restartActivePlayback = snapshot.manualGainControl)
    }

    private fun adjustManualPlutoGain(deltaDb: Double) {
        val snapshot = gainController.adjustPlutoGain(deltaDb)
        applyGainSnapshotToUiAndActiveSession(snapshot, restartActivePlayback = true)
    }

    private fun applyGainSnapshotToUiAndActiveSession(
        snapshot: GainControllerSnapshot,
        restartActivePlayback: Boolean,
    ) {
        plutoIqConfig = plutoIqConfig.copy(gainDb = snapshot.plutoGainDb)
        pendingPlutoIqConfig = pendingPlutoIqConfig.copy(gainDb = snapshot.plutoGainDb)
        updateScannerUi()
        if (!restartActivePlayback) {
            binding.sampleText.text = "Gain control switched to AGC.\n\nCurrent Pluto gain: " +
                String.format(Locale.US, "%.1f dB", snapshot.plutoGainDb)
            return
        }
        restartActivePlutoSessionWithGain(snapshot.plutoGainDb)
    }

    private fun restartActivePlutoSessionWithGain(gainDb: Double) {
        val activePlutoSession = currentPlutoSessionRecovery()
        val previousMode = activeMode
        val restartConfig = (activePlutoSession?.config ?: plutoIqConfig).copy(gainDb = gainDb)
        val restartStandard = activePlutoSession?.standard ?: VideoStandard.AUTO
        val gainText = String.format(Locale.US, "%.1f dB", gainDb)
        when {
            playbackRunning && previousMode == ActiveMode.PLUTO_USB_PLAYBACK -> {
                stopPlayback()
                activeMode = ActiveMode.PLUTO_USB_PLAYBACK
                binding.sampleText.text = "Manual Pluto gain changed to $gainText.\n\nRestarting Pluto IIO playback."
                preparePlutoUsbPlayback(restartStandard, restartConfig)
            }
            playbackRunning && previousMode == ActiveMode.PLUTO_IP_PLAYBACK -> {
                stopPlayback()
                activeMode = ActiveMode.PLUTO_IP_PLAYBACK
                binding.sampleText.text = "Manual Pluto gain changed to $gainText.\n\nRestarting Pluto IP IIO playback."
                preparePlutoIpIioPlayback(restartStandard, restartConfig)
            }
            playbackRunning && previousMode == ActiveMode.PLUTO_WS_PLAYBACK -> {
                stopPlayback()
                activeMode = ActiveMode.PLUTO_WS_PLAYBACK
                binding.sampleText.text = "Manual Pluto gain changed to $gainText.\n\nRestarting Pluto WebSocket playback."
                preparePlutoWebSocketPlayback(restartStandard, restartConfig)
            }
            scannerRunning -> {
                binding.sampleText.text = "Manual Pluto gain changed to $gainText.\n\nNew gain applies to the next scan/probe step."
            }
            else -> {
                binding.sampleText.text = "Manual Pluto gain changed to $gainText.\n\nNew gain applies to the next Pluto session."
            }
        }
    }

    private fun adjacentAnalogChannel(channel: KnownChannel, direction: Int): KnownChannel? {
        val channels = ChannelPlan.knownChannels
            .filter { it.expectedSignalType == SignalType.ANALOG }
            .filter { it.rfFrontendProfileId == channel.rfFrontendProfileId }
            .distinctBy { it.centerFrequencyHz }
            .sortedBy { it.centerFrequencyHz }
        if (channels.isEmpty()) {
            return null
        }

        val index = channels.indexOfFirst { it.centerFrequencyHz == channel.centerFrequencyHz }
        if (index < 0) {
            return null
        }

        val targetIndex = index + direction.coerceIn(-1, 1)
        if (targetIndex !in channels.indices) {
            return null
        }
        return channels[targetIndex]
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
                    preparePlutoIpIioPlayback(
                        VideoStandard.AUTO,
                        configForChannel(selected)
                            .forcedSampleFormat(IqSampleFormat.CS8)
                            .copy(gainDb = gainController.currentPlutoGainDb),
                    )
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
        stopMaiaWaterfallScanner()
        stopPlayback()
        stopSpectrum()
        activeMode = ActiveMode.NONE
        scannerRunning = false
        updateSleepBlocker()
        if (::binding.isInitialized) {
            binding.scannerWebView.removeJavascriptInterface("AndroidScanner")
            binding.scannerWebView.stopLoading()
            binding.scannerWebView.webViewClient = WebViewClient()
            binding.scannerWebView.destroy()
        }
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
            ActiveMode.PLUTO_SCANNER -> when (snapshot.config.iqSource) {
                PlutoIqSource.IIO -> plutoIpIioConnectionStatus(snapshot.config)
                PlutoIqSource.WEBSOCKET -> webSocketPlutoConnectionStatus(snapshot.config)
            }
            ActiveMode.PLUTO_IP_PLAYBACK -> plutoIpIioConnectionStatus(snapshot.config)
            ActiveMode.PLUTO_USB_PLAYBACK,
            ActiveMode.PLUTO_USB_SPECTRUM -> directUsbPlutoConnectionStatus()
            ActiveMode.PLUTO_WS_PLAYBACK,
            ActiveMode.PLUTO_WS_SPECTRUM -> webSocketPlutoConnectionStatus(snapshot.config)
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

    private fun webSocketPlutoConnectionStatus(config: PlutoIqConfig): PlutoConnectionStatus {
        val host = config.maiaHost.ifBlank { MAIA_DEFAULT_HOST }
        val selection = findPlutoNetworkWithDiagnostics(host)
        val network = selection.network
        if (network == null || network.networkHandle == 0L) {
            return PlutoConnectionStatus(
                connected = false,
                diagnostic = "pluto_ws: Android network to $host missing\n${selection.diagnostics}",
            )
        }
        val tcpTest = testTcpConnection(
            network = network,
            host = host,
            port = config.maiaPort,
            label = "Pluto WebSocket TCP connection test",
            connectTimeoutMs = PLUTO_WS_CONNECT_TIMEOUT_MS,
        )
        if (!tcpTest.connectionEstablished) {
            return PlutoConnectionStatus(
                connected = false,
                diagnostic = "pluto_ws: TCP $host:${config.maiaPort} unreachable on ${networkLabel(network)}\n" +
                    selection.diagnostics + "\n" + tcpTest.diagnostics,
            )
        }
        return PlutoConnectionStatus(
            connected = true,
            diagnostic = "pluto_ws: TCP reachable on ${networkLabel(network)}",
        )
    }

    private fun plutoIpIioConnectionStatus(config: PlutoIqConfig): PlutoConnectionStatus {
        val host = config.maiaHost.ifBlank { MAIA_DEFAULT_HOST }
        val selection = findPlutoNetworkWithDiagnostics(host)
        val network = selection.network
        if (network == null || network.networkHandle == 0L) {
            return PlutoConnectionStatus(
                connected = false,
                diagnostic = "pluto_ip_iio: Android network to $host missing\n${selection.diagnostics}",
            )
        }
        val tcpTest = testPlutoIiodTcpConnection(network, host)
        if (!tcpTest.connectionEstablished) {
            return PlutoConnectionStatus(
                connected = false,
                diagnostic = "pluto_ip_iio: iiod TCP $host:$PLUTO_IP_IIO_PORT unreachable on ${networkLabel(network)}\n" +
                    selection.diagnostics + "\n" + tcpTest.diagnostics,
            )
        }
        return PlutoConnectionStatus(
            connected = true,
            diagnostic = "pluto_ip_iio: iiod TCP reachable on ${networkLabel(network)}",
        )
    }

    private fun isPlutoSessionActive(snapshot: PlutoSessionRecovery): Boolean {
        return when (snapshot.mode) {
            ActiveMode.PLUTO_SCANNER -> scannerRunning
            ActiveMode.PLUTO_USB_PLAYBACK,
            ActiveMode.PLUTO_IP_PLAYBACK,
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
                ActiveMode.PLUTO_SCANNER -> stopScannerMode()
                ActiveMode.PLUTO_USB_PLAYBACK,
                ActiveMode.PLUTO_IP_PLAYBACK,
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
            ActiveMode.PLUTO_SCANNER -> startScannerModeAfterPreflight(snapshot.config)
            ActiveMode.PLUTO_USB_PLAYBACK -> preparePlutoUsbPlayback(snapshot.standard, snapshot.config)
            ActiveMode.PLUTO_IP_PLAYBACK -> preparePlutoIpIioPlayback(snapshot.standard, snapshot.config)
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

    private fun handleScannerPlutoRuntimeFailure(channel: KnownChannel, result: SignalProbeResult) {
        val snapshot = currentPlutoSessionRecovery()
            ?: PlutoSessionRecovery(ActiveMode.PLUTO_SCANNER, VideoStandard.AUTO, plutoIqConfig)
        scanSessionId += 1L
        scannerRunning = false
        selectedPlaybackChannel = null
        currentScannerChannel = channel
        activeMode = ActiveMode.PLUTO_SCANNER
        scanController.error()
        updateSleepBlocker()
        renderSignalTable()
        updateScannerUi()
        synchronized(this) {
            plutoSessionRecovery = snapshot.copy(mode = ActiveMode.PLUTO_SCANNER)
            plutoMissingSinceMs = SystemClock.elapsedRealtime()
        }
        binding.videoFrameContainer.visibility = View.GONE
        binding.edgeTimelineView.setTimeline(null)
        binding.sampleText.text = buildString {
            append("ALARM: Pluto scanner link failed. Scanning stopped.\n\n")
            append("channel: ${channel.bandName} / ${channel.channelName} / ${formatFrequency(channel.centerFrequencyHz)}\n")
            append("The app will retry when Pluto IP IIO is reachable. ")
            append("If it remains absent for ${PLUTO_CONNECTION_ABSENT_POWER_CYCLE_MS / 1000}s, USB OTG power-cycle recovery will run.\n\n")
            append(result.diagnostic)
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

    private fun defaultIqPathForConfig(config: PlutoIqConfig = plutoIqConfig): String {
        val fileName = when (config.sampleFormat) {
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
            source = if (uri.startsWith("ip:")) "pluto_iio_ip_live" else "pluto_iio_usb_live",
            device = "PlutoSDR $uri",
            requestedVideoStandard = requestedStandard,
        )
    }

    private fun plutoIioIpUri(config: PlutoIqConfig): String {
        return "ip:${config.maiaHost.ifBlank { MAIA_DEFAULT_HOST }}"
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
            binding.edgeTimelineView.setTimeline(null)
            binding.sampleText.text = metadata.toDiagnosticText() +
                "\n\nAnalog FPV video frame decode failed. sample_rate_hz metadata is required and the IQ file must contain enough samples."
            return
        }

        setVideoFrameBitmap(frame.bitmap, frame.edgeTimeline)
        binding.sampleText.text = metadata.toDiagnosticText() + "\n\n" + frame.diagnostic
    }

    private fun setVideoFrameBitmap(bitmap: Bitmap, edgeTimeline: EdgeTimelineData? = null) {
        binding.videoFrameContainer.visibility = View.VISIBLE
        binding.videoFrameImage.setImageBitmap(bitmap)
        binding.edgeTimelineView.setTimeline(edgeTimeline)
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
        val generation = beginPlaybackSession()
        updateSleepBlocker()
        playbackFrameIndex = 0L
        schedulePlaybackFrame(path, metadata, standard, generation)
    }

    private fun beginPlaybackSession(): Long {
        playbackGeneration += 1L
        playbackRunning = true
        return playbackGeneration
    }

    private fun playbackSessionActive(generation: Long): Boolean {
        return playbackRunning && playbackGeneration == generation
    }

    private fun stopPlayback() {
        val previousMode = activeMode
        playbackGeneration += 1L
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
        val previousBoundNetwork = playbackPreviousBoundNetwork
        val hadPlaybackNetworkBinding = playbackBoundNetwork != null
        playbackPreviousBoundNetwork = null
        playbackBoundNetwork = null
        if (sessionToClose != 0L || usbConnectionToClose != null) {
            decodeExecutor.execute {
                if (sessionToClose != 0L) {
                    closeAnalogVideoPlaybackSession(sessionToClose)
                }
                usbConnectionToClose?.close()
            }
        }
        if (hadPlaybackNetworkBinding) {
            getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(previousBoundNetwork)
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
        generation: Long,
        createFileSessionIfNeeded: Boolean = true,
    ) {
        if (!playbackSessionActive(generation)) {
            return
        }

        val frameIndex = playbackFrameIndex
        decodeExecutor.execute {
            if (!playbackSessionActive(generation)) {
                return@execute
            }
            var sessionHandle = playbackSessionHandle
            if (sessionHandle == 0L) {
                if (!createFileSessionIfNeeded) {
                    mainHandler.post {
                        if (!playbackSessionActive(generation)) {
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
                        if (!playbackSessionActive(generation)) {
                            return@post
                        }
                        stopPlayback()
                        binding.sampleText.text = metadata.toDiagnosticText() +
                            "\n\nPlayback stopped: native playback session could not be created."
                    }
                    return@execute
                }

                if (!playbackSessionActive(generation)) {
                    closeAnalogVideoPlaybackSession(sessionHandle)
                    return@execute
                }
                playbackSessionHandle = sessionHandle
            }

            val frame = grayscaleFramePacketToBitmap(
                packet = decodeNextAnalogVideoPlaybackFrame(sessionHandle),
                includeDiagnostic = true,
            )
            mainHandler.post {
                if (!playbackSessionActive(generation)) {
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

                setVideoFrameBitmap(frame.bitmap, frame.edgeTimeline)
                if ((frameIndex % PLAYBACK_DIAGNOSTIC_EVERY_FRAMES) == 0L) {
                    updateSampleText(
                        metadata.toDiagnosticText() +
                            "\n\nplayback_frame_index: $frameIndex\n" + frame.diagnostic,
                        preserveStatsScroll = true,
                    )
                }
                playbackFrameIndex = frameIndex + 1L
                mainHandler.postDelayed(
                    { schedulePlaybackFrame(path, metadata, standard, generation, createFileSessionIfNeeded) },
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
                    updateSampleText(frame.diagnostic, preserveStatsScroll = true)
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

    private fun preparePlutoIpIioCapture(
        path: String,
        config: PlutoIqConfig,
    ) {
        if (requestPlutoUsbPermissionForNetworkIfNeeded(
                action = PlutoUsbAction.IP_IIO_CAPTURE,
                standard = VideoStandard.AUTO,
                config = config,
                description = "Pluto IP IIO IQ capture",
                capturePath = path,
            )
        ) {
            return
        }
        startPlutoIpIioCapture(path, config)
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

    private fun preparePlutoIpIioPlayback(
        standard: VideoStandard,
        config: PlutoIqConfig,
    ) {
        if (requestPlutoUsbPermissionForNetworkIfNeeded(
                action = PlutoUsbAction.IP_IIO_PLAYBACK,
                standard = standard,
                config = config,
                description = "Pluto IP IIO playback",
            )
        ) {
            return
        }
        startPlutoIpIioPlayback(standard, config)
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

    private fun startPlutoIpIioCapture(
        path: String,
        config: PlutoIqConfig,
    ) {
        val uri = plutoIioIpUri(config)
        activeMode = ActiveMode.RECORD_IQ
        updateSleepBlocker()
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        binding.sampleText.text = "Capturing Pluto IP IIO ${config.sampleFormat.metadataValue} IQ to:\n$path\n\nuri: $uri"
        decodeExecutor.execute {
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            val host = config.maiaHost.ifBlank { MAIA_DEFAULT_HOST }
            val selection = waitForPlutoIioNetworkWithDiagnostics(host)
            val network = selection.network
            if (network == null || network.networkHandle == 0L) {
                mainHandler.post {
                    activeMode = ActiveMode.NONE
                    updateSleepBlocker()
                    binding.sampleText.text = "Pluto IQ capture\nstatus: failed\nuri: $uri\nerror: Android network to $host was not found\n\n" +
                        selection.diagnostics
                }
                return@execute
            }

            val previousBoundNetwork = connectivityManager.boundNetworkForProcess
            if (!connectivityManager.bindProcessToNetwork(network)) {
                mainHandler.post {
                    activeMode = ActiveMode.NONE
                    updateSleepBlocker()
                    binding.sampleText.text = "Pluto IQ capture\nstatus: failed\nuri: $uri\nerror: bindProcessToNetwork failed for ${networkLabel(network)}\n\n" +
                        selection.diagnostics
                }
                return@execute
            }

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
                restorePlaybackNetworkBinding(previousBoundNetwork, network)
            }
            val captured = diagnostic.contains("status: captured")
            if (captured) {
                writeCaptureMetadata(path, uri, config)
            }
            mainHandler.post {
                activeMode = ActiveMode.NONE
                updateSleepBlocker()
                binding.sampleText.text = if (captured) {
                    diagnostic + "\n\nmetadata path: ${metadataFileFor(path).absolutePath}\n\n" +
                        selection.diagnostics
                } else {
                    diagnostic + "\n\n" + selection.diagnostics
                }
            }
        }
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

        val generation = beginPlaybackSession()
        updateSleepBlocker()
        playbackFrameIndex = 0L
        binding.sampleText.text = "Opening Pluto IIO ${config.sampleFormat.metadataValue} live playback:\n${usbDeviceLabel(device)}"
        decodeExecutor.execute {
            if (!playbackSessionActive(generation)) {
                return@execute
            }
            val openResult = openPlutoUsbPlaybackSessionWithRetry(
                usbManager = usbManager,
                device = device,
                standard = standard,
                config = config,
            )
            mainHandler.post {
                if (!playbackSessionActive(generation)) {
                    if (openResult.sessionHandle != 0L) {
                        decodeExecutor.execute {
                            closeAnalogVideoPlaybackSession(openResult.sessionHandle)
                            openResult.connection?.close()
                        }
                    } else {
                        openResult.connection?.close()
                    }
                    return@post
                }

                if (openResult.sessionHandle == 0L || openResult.connection == null) {
                    playbackRunning = false
                    updateSleepBlocker()
                    openResult.connection?.close()
                    val metadata = livePlutoMetadata(openResult.uri.ifBlank { "usb:fd:<unavailable>" }, standard, config)
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nPlayback stopped: native Pluto live playback session could not be created.\n\n" +
                        "native_error: ${openResult.nativeError.ifBlank { "unavailable" }}\n\n" +
                        openResult.diagnostic + "\n\n" +
                        plutoUsbDiagnosticText()
                    return@post
                }

                val metadata = livePlutoMetadata(openResult.uri, standard, config)
                playbackSessionHandle = openResult.sessionHandle
                liveUsbConnection = openResult.connection
                binding.sampleText.text = metadata.toDiagnosticText() +
                    "\n\nPluto IIO ${config.sampleFormat.metadataValue} live playback started.\n" +
                    openResult.diagnostic
                schedulePlaybackFrame(
                    path = "",
                    metadata = metadata,
                    standard = standard,
                    generation = generation,
                    createFileSessionIfNeeded = false,
                )
            }
        }
    }

    private fun startPlutoIpIioPlayback(
        standard: VideoStandard,
        config: PlutoIqConfig,
    ) {
        val uri = plutoIioIpUri(config)
        activeMode = ActiveMode.PLUTO_IP_PLAYBACK
        rememberPlutoSession(ActiveMode.PLUTO_IP_PLAYBACK, standard, config)
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        val generation = beginPlaybackSession()
        updateSleepBlocker()
        playbackFrameIndex = 0L
        binding.sampleText.text = "Opening Pluto IP IIO ${config.sampleFormat.metadataValue} live playback:\n$uri"
        decodeExecutor.execute {
            if (!playbackSessionActive(generation)) {
                return@execute
            }
            val openResult = openPlutoIpIioPlaybackSessionWithRetry(
                uri = uri,
                standard = standard,
                config = config,
            )
            mainHandler.post {
                if (!playbackSessionActive(generation)) {
                    if (openResult.sessionHandle != 0L) {
                        decodeExecutor.execute {
                            closeAnalogVideoPlaybackSession(openResult.sessionHandle)
                            restorePlaybackNetworkBinding(
                                openResult.previousBoundNetwork,
                                openResult.boundNetwork,
                            )
                        }
                    } else {
                        restorePlaybackNetworkBinding(
                            openResult.previousBoundNetwork,
                            openResult.boundNetwork,
                        )
                    }
                    return@post
                }

                if (openResult.sessionHandle == 0L) {
                    playbackRunning = false
                    updateSleepBlocker()
                    restorePlaybackNetworkBinding(
                        openResult.previousBoundNetwork,
                        openResult.boundNetwork,
                    )
                    val metadata = livePlutoMetadata(openResult.uri.ifBlank { uri }, standard, config)
                    binding.sampleText.text = metadata.toDiagnosticText() +
                        "\n\nPlayback stopped: native Pluto IP IIO playback session could not be created.\n\n" +
                        "native_error: ${openResult.nativeError.ifBlank { "unavailable" }}\n\n" +
                        openResult.diagnostic
                    return@post
                }

                val metadata = livePlutoMetadata(openResult.uri, standard, config)
                playbackSessionHandle = openResult.sessionHandle
                liveUsbConnection = null
                playbackPreviousBoundNetwork = openResult.previousBoundNetwork
                playbackBoundNetwork = openResult.boundNetwork
                binding.sampleText.text = metadata.toDiagnosticText() +
                    "\n\nPluto IP IIO ${config.sampleFormat.metadataValue} live playback started.\n" +
                    openResult.diagnostic
                schedulePlaybackFrame(
                    path = "",
                    metadata = metadata,
                    standard = standard,
                    generation = generation,
                    createFileSessionIfNeeded = false,
                )
            }
        }
    }

    private fun openPlutoUsbPlaybackSessionWithRetry(
        usbManager: UsbManager,
        device: UsbDevice,
        standard: VideoStandard,
        config: PlutoIqConfig,
    ): PlutoUsbSessionOpenResult {
        val diagnostics = StringBuilder()
        var lastError = ""
        var lastUri = ""

        for (attempt in 1..PLUTO_USB_OPEN_RETRY_COUNT) {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                lastError = "Failed to open Pluto USB device"
                diagnostics.append("usb_open_attempt_$attempt: openDevice returned null\n")
            } else {
                val fd = connection.fileDescriptor
                if (fd < 0) {
                    lastError = "Android returned an invalid Pluto USB file descriptor: $fd"
                    diagnostics.append("usb_open_attempt_$attempt: invalid_fd=$fd\n")
                    connection.close()
                } else {
                    val uri = "usb:fd:$fd"
                    lastUri = uri
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
                    if (sessionHandle != 0L) {
                        diagnostics.append("usb_open_attempt_$attempt: ok uri=$uri")
                        return PlutoUsbSessionOpenResult(
                            sessionHandle = sessionHandle,
                            connection = connection,
                            uri = uri,
                            nativeError = "",
                            diagnostic = diagnostics.toString().trim(),
                        )
                    }

                    lastError = consumeLastNativeError().ifBlank { "native session creation failed" }
                    diagnostics.append("usb_open_attempt_$attempt: failed native_error=$lastError\n")
                    connection.close()
                }
            }

            if (attempt < PLUTO_USB_OPEN_RETRY_COUNT && shouldRetryPlutoUsbOpen(lastError)) {
                Thread.sleep(PLUTO_USB_OPEN_RETRY_DELAY_MS * attempt.toLong())
            } else {
                break
            }
        }

        return PlutoUsbSessionOpenResult(
            sessionHandle = 0L,
            connection = null,
            uri = lastUri,
            nativeError = lastError,
            diagnostic = diagnostics.toString().trim(),
        )
    }

    private fun openPlutoIpIioPlaybackSessionWithRetry(
        uri: String,
        standard: VideoStandard,
        config: PlutoIqConfig,
    ): PlutoIpIioSessionOpenResult {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val host = config.maiaHost.ifBlank { MAIA_DEFAULT_HOST }
        val selection = waitForPlutoIioNetworkWithDiagnostics(host)
        val network = selection.network
        if (network == null || network.networkHandle == 0L) {
            return PlutoIpIioSessionOpenResult(
                sessionHandle = 0L,
                previousBoundNetwork = null,
                boundNetwork = null,
                uri = uri,
                nativeError = "Android network to $host was not found",
                diagnostic = selection.diagnostics,
            )
        }

        val previousBoundNetwork = connectivityManager.boundNetworkForProcess
        if (!connectivityManager.bindProcessToNetwork(network)) {
            return PlutoIpIioSessionOpenResult(
                sessionHandle = 0L,
                previousBoundNetwork = previousBoundNetwork,
                boundNetwork = null,
                uri = uri,
                nativeError = "bindProcessToNetwork failed for ${networkLabel(network)}",
                diagnostic = selection.diagnostics,
            )
        }

        val diagnostics = StringBuilder()
        diagnostics.append(selection.diagnostics.trim())
        diagnostics.append("\nprocess_bound_network: ${networkLabel(network)}")
        diagnostics.append("\nprevious_bound_network: ${networkLabel(previousBoundNetwork)}\n")
        var lastError = ""

        for (attempt in 1..PLUTO_USB_OPEN_RETRY_COUNT) {
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
            if (sessionHandle != 0L) {
                diagnostics.append("ip_iio_open_attempt_$attempt: ok uri=$uri")
                return PlutoIpIioSessionOpenResult(
                    sessionHandle = sessionHandle,
                    previousBoundNetwork = previousBoundNetwork,
                    boundNetwork = network,
                    uri = uri,
                    nativeError = "",
                    diagnostic = diagnostics.toString().trim(),
                )
            }

            lastError = consumeLastNativeError().ifBlank { "native session creation failed" }
            diagnostics.append("ip_iio_open_attempt_$attempt: failed native_error=$lastError\n")
            if (attempt < PLUTO_USB_OPEN_RETRY_COUNT && shouldRetryPlutoUsbOpen(lastError)) {
                Thread.sleep(PLUTO_USB_OPEN_RETRY_DELAY_MS * attempt.toLong())
            } else {
                break
            }
        }

        restorePlaybackNetworkBinding(previousBoundNetwork, network)
        return PlutoIpIioSessionOpenResult(
            sessionHandle = 0L,
            previousBoundNetwork = previousBoundNetwork,
            boundNetwork = null,
            uri = uri,
            nativeError = lastError,
            diagnostic = diagnostics.toString().trim(),
        )
    }

    private fun waitForPlutoIioNetworkWithDiagnostics(host: String): EthernetNetworkSelection {
        val diagnostics = StringBuilder()
        val deadlineMs = SystemClock.elapsedRealtime() + PLUTO_IP_NETWORK_WAIT_MS
        var attempt = 0

        while (true) {
            attempt++
            val selection = findPlutoNetworkWithDiagnostics(host)
            diagnostics.append("pluto_ip_iio_network_probe_attempt_$attempt\n")
            diagnostics.append(selection.diagnostics.trim())
            diagnostics.append("\n")

            val network = selection.network
            if (network == null || network.networkHandle == 0L) {
                diagnostics.append("pluto_ip_iio_network_probe_result: no Android network\n")
            } else {
                val tcpTest = testPlutoIiodTcpConnection(network, host)
                diagnostics.append(tcpTest.diagnostics)
                diagnostics.append("\n")
                if (tcpTest.connectionEstablished) {
                    diagnostics.append("pluto_ip_iio_network_ready: true\n")
                    return EthernetNetworkSelection(network, diagnostics.toString().trim())
                }
            }

            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                break
            }
            Thread.sleep(minOf(PLUTO_IP_NETWORK_POLL_MS, remainingMs))
        }

        diagnostics.append("pluto_ip_iio_network_ready: false\n")
        diagnostics.append("pluto_ip_iio_network_wait_ms: $PLUTO_IP_NETWORK_WAIT_MS\n")
        diagnostics.append("pluto_ip_iio_note: native libiio open was skipped because iiod was not reachable through Android Ethernet.\n")
        return EthernetNetworkSelection(null, diagnostics.toString().trim())
    }

    private fun restorePlaybackNetworkBinding(previousBoundNetwork: Network?, boundNetwork: Network?) {
        if (boundNetwork == null) {
            return
        }
        getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(previousBoundNetwork)
    }

    private fun shouldRetryPlutoUsbOpen(message: String): Boolean {
        val lower = message.lowercase(Locale.US)
        return lower.contains("reset pipes") ||
            lower.contains("timed out") ||
            lower.contains("timeout") ||
            lower.contains("connection timed out") ||
            lower.contains("network is unreachable") ||
            lower.contains("no route to host") ||
            lower.contains("host is unreachable") ||
            lower.contains("resource busy") ||
            lower.contains("busy") ||
            lower.contains("open pluto usb")
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

    private fun findPlutoNetworkWithDiagnostics(host: String): EthernetNetworkSelection {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val diagnostics = StringBuilder()
        val activeNetwork = connectivityManager.activeNetwork
        val networks = connectivityManager.allNetworks
        val targetPrefix = host.substringBeforeLast('.', missingDelimiterValue = host) + "."
        var sameSubnetNetwork: Network? = null
        var sameSubnetEthernetNetwork: Network? = null
        var ethernetNetwork: Network? = null

        diagnostics.append("Pluto IP IIO network diagnostics\n")
        diagnostics.append("target_host: $host\n")
        diagnostics.append("target_ipv4_prefix: $targetPrefix\n")
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
            val isEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val isSameSubnet = hasIpv4Prefix(linkProperties, targetPrefix)
            if (sameSubnetNetwork == null && isSameSubnet) {
                sameSubnetNetwork = network
            }
            if (sameSubnetEthernetNetwork == null && isSameSubnet && isEthernet) {
                sameSubnetEthernetNetwork = network
            }
            if (ethernetNetwork == null && isEthernet) {
                ethernetNetwork = network
            }
        }

        val selectedNetwork = sameSubnetEthernetNetwork ?: ethernetNetwork ?: sameSubnetNetwork
        diagnostics.append("\npluto_same_subnet_network: ${networkLabel(sameSubnetNetwork)}\n")
        diagnostics.append("pluto_same_subnet_ethernet_network: ${networkLabel(sameSubnetEthernetNetwork)}\n")
        diagnostics.append("ethernet_network: ${networkLabel(ethernetNetwork)}\n")
        diagnostics.append("selected_pluto_network: ${networkLabel(selectedNetwork)}\n")
        selectedNetwork?.let { network ->
            diagnostics.append("selected_network_handle: ${network.networkHandle}\n")
            connectivityManager.getLinkProperties(network)?.interfaceName?.let { interfaceName ->
                diagnostics.append("selected_interface_name: $interfaceName\n")
            }
        }

        logMultiline(diagnostics.toString())
        return EthernetNetworkSelection(selectedNetwork, diagnostics.toString())
    }

    private fun testPlutoIiodTcpConnection(
        network: Network,
        host: String,
    ): MaiaHttpConnectionTestResult {
        return testTcpConnection(
            network = network,
            host = host,
            port = PLUTO_IP_IIO_PORT,
            label = "Pluto iiod TCP connection test",
            connectTimeoutMs = PLUTO_IP_CONNECT_TIMEOUT_MS,
        )
    }

    private fun testTcpConnection(
        network: Network,
        host: String,
        port: Int,
        label: String,
        connectTimeoutMs: Int,
    ): MaiaHttpConnectionTestResult {
        val diagnostics = StringBuilder()
        diagnostics.append("$label\n")
        diagnostics.append("target: $host:$port\n")
        diagnostics.append("network: ${networkLabel(network)}\n")
        diagnostics.append("network_handle: ${network.networkHandle}\n")
        diagnostics.append("connect_timeout_ms: $connectTimeoutMs\n")

        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(
                    InetSocketAddress(host, port),
                    connectTimeoutMs,
                )
                diagnostics.append("socket_local_address: ${socket.localAddress?.hostAddress ?: "unavailable"}:${socket.localPort}\n")
                diagnostics.append("socket_remote_address: ${socket.inetAddress?.hostAddress ?: "unavailable"}:${socket.port}\n")
            }
            diagnostics.append("connection_established: true\n")
            MaiaHttpConnectionTestResult(connectionEstablished = true, diagnostics = diagnostics.toString())
        } catch (error: SocketTimeoutException) {
            diagnostics.append("connection_established: false\n")
            diagnostics.append("timeout: true\n")
            diagnostics.append("exception_message: ${error.message ?: error.javaClass.name}\n")
            MaiaHttpConnectionTestResult(connectionEstablished = false, diagnostics = diagnostics.toString())
        } catch (error: Exception) {
            diagnostics.append("connection_established: false\n")
            diagnostics.append("timeout: false\n")
            diagnostics.append("exception_message: ${error.message ?: error.javaClass.name}\n")
            MaiaHttpConnectionTestResult(connectionEstablished = false, diagnostics = diagnostics.toString())
        }
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

    private fun hasIpv4Prefix(linkProperties: LinkProperties?, prefix: String): Boolean {
        return linkProperties
            ?.linkAddresses
            ?.any { linkAddress ->
                val address = linkAddress.address
                address is Inet4Address && address.hostAddress?.startsWith(prefix) == true
            } == true
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
        val generation = beginPlaybackSession()
        updateSleepBlocker()
        playbackFrameIndex = 0L
        binding.sampleText.text = "Opening Maia CS8 live playback:\nhttp://${config.maiaEndpoint()}"
        decodeExecutor.execute {
            if (!playbackSessionActive(generation)) {
                return@execute
            }
            val ethernetSelection = findEthernetNetworkWithDiagnostics()
            val ethernetNetwork = ethernetSelection.network
            if (ethernetNetwork == null || ethernetNetwork.networkHandle == 0L) {
                mainHandler.post {
                    if (!playbackSessionActive(generation)) {
                        return@post
                    }
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
                if (!playbackSessionActive(generation)) {
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
                    generation = generation,
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
        capturePath: String? = null,
    ): Boolean {
        val usbManager = getSystemService(UsbManager::class.java)
        val plutoDevice = findPlutoUsbDevice(usbManager) ?: return false
        if (usbManager.hasPermission(plutoDevice)) {
            return false
        }

        pendingPlutoCapturePath = capturePath
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
            "This permission helps Android expose Pluto's USB network path on some devices."
        usbManager.requestPermission(plutoDevice, permissionIntent)
        return true
    }

    private fun startPlutoWebSocketPlayback(standard: VideoStandard, config: PlutoIqConfig) {
        val metadata = livePlutoWebSocketMetadata(standard, config)
        activeMode = ActiveMode.PLUTO_WS_PLAYBACK
        rememberPlutoSession(ActiveMode.PLUTO_WS_PLAYBACK, standard, config)
        updateCurrentFrequencyLabel(config.centerFrequencyHz)
        val generation = beginPlaybackSession()
        updateSleepBlocker()
        playbackFrameIndex = 0L
        binding.sampleText.text = "Opening Pluto WebSocket CS8 live playback:\nws://${config.plutoWebSocketEndpoint()}${config.plutoWebSocketPath}"
        decodeExecutor.execute {
            if (!playbackSessionActive(generation)) {
                return@execute
            }
            val primeDiagnostic = primePlutoUsbForWebSocket(config)
            val ethernetSelection = findEthernetNetworkWithDiagnostics()
            val ethernetNetwork = ethernetSelection.network
            if (ethernetNetwork == null || ethernetNetwork.networkHandle == 0L) {
                mainHandler.post {
                    if (!playbackSessionActive(generation)) {
                        return@post
                    }
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
                if (!playbackSessionActive(generation)) {
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
                    generation = generation,
                    createFileSessionIfNeeded = false,
                )
            }
        }
    }

    private fun primePlutoUsbForWebSocket(config: PlutoIqConfig): String {
        val throttleDelayMs = reservePlutoWebSocketPrimeDelay()
        if (throttleDelayMs > 0L) {
            Thread.sleep(throttleDelayMs)
        }
        val throttleDiagnostic = if (throttleDelayMs > 0L) {
            "pluto_usb_prime_throttle_ms: $throttleDelayMs\n"
        } else {
            ""
        }
        val usbManager = getSystemService(UsbManager::class.java)
        val plutoDevice = findPlutoUsbDevice(usbManager)
            ?: return throttleDiagnostic + "pluto_usb_prime: skipped, Pluto USB device not found"
        if (!usbManager.hasPermission(plutoDevice)) {
            return throttleDiagnostic + "pluto_usb_prime: skipped, Android USB permission is not granted"
        }
        val iioInterface = findPlutoIioInterface(plutoDevice)
            ?: return throttleDiagnostic + "pluto_usb_prime: skipped, Pluto USB IIO interface was not found"
        val connection = usbManager.openDevice(plutoDevice)
            ?: return throttleDiagnostic + "pluto_usb_prime: failed, could not open Pluto USB device"
        return try {
            val fd = connection.fileDescriptor
            if (fd < 0) {
                throttleDiagnostic + "pluto_usb_prime: failed, invalid Android USB file descriptor $fd"
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
                    throttleDiagnostic + "pluto_usb_prime: failed, native CS16 libiio session could not be created; native_error: $nativeError"
                } else {
                    closeAnalogVideoPlaybackSession(sessionHandle)
                    throttleDiagnostic + "pluto_usb_prime: ok, configured ${formatFrequency(primeConfig.centerFrequencyHz)} through ${iioInterface.usbInterface.name ?: "IIO"}"
                }
            }
        } finally {
            connection.close()
        }
    }

    @Synchronized
    private fun reservePlutoWebSocketPrimeDelay(): Long {
        val nowMs = SystemClock.elapsedRealtime()
        val earliestMs = lastPlutoWebSocketPrimeMs + PLUTO_WS_PRIME_MIN_INTERVAL_MS
        val delayMs = (earliestMs - nowMs).coerceAtLeast(0L)
        lastPlutoWebSocketPrimeMs = nowMs + delayMs
        return delayMs
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
            .put("source", if (uri.startsWith("ip:")) "pluto_iio_ip" else "pluto_iio_usb")
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
        when (config.iqSource) {
            PlutoIqSource.IIO -> dialogBinding.iqSourceIioRadio.isChecked = true
            PlutoIqSource.WEBSOCKET -> dialogBinding.iqSourceWebsocketRadio.isChecked = true
        }
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
            iqSource = when {
                dialogBinding.iqSourceIioRadio.isChecked -> PlutoIqSource.IIO
                else -> PlutoIqSource.WEBSOCKET
            },
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
            iqSource = PlutoIqSource.WEBSOCKET,
            maiaHost = MAIA_DEFAULT_HOST,
            maiaPort = PLUTO_WS_DEFAULT_PORT,
            plutoWebSocketPath = PLUTO_WS_DEFAULT_PATH,
            plutoWebSocketReceiveBufferMs = PLUTO_WS_RECEIVE_BUFFER_DEFAULT_MS,
            sampleFormat = IqSampleFormat.CS8,
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

    private fun defaultGainControllerConfig(): GainControllerConfig {
        return GainControllerConfig(
            rfFrontendMode = RfFrontendMode.DIRECT_PLUTO,
            enableExternalFrontend = false,
            enableLnaBypass = false,
            defaultLnaState = LnaState.ON,
            scanPlutoGainDb = SCAN_PLUTO_GAIN_DB,
            scanAttenuationDb = AttenuationDb.DB_0,
        )
    }

    private fun loadPlutoIqConfigDefaults(): PlutoIqConfig {
        val defaults = defaultPlutoIqConfig()
        val preferences = getSharedPreferences(PLUTO_PREFS_NAME, Context.MODE_PRIVATE)
        return PlutoIqConfig(
            iqSource = PlutoIqSource.fromPreferenceValue(
                preferences.getString(PREF_IQ_SOURCE, defaults.iqSource.preferenceValue),
            ),
            maiaHost = preferences.getString(PREF_MAIA_HOST, defaults.maiaHost)?.trim()?.takeIf { it.isNotBlank() }
                ?: defaults.maiaHost,
            maiaPort = preferences.getInt(PREF_PLUTO_WS_PORT, defaults.maiaPort)
                .takeIf { it in 1..65535 }
                ?.let { port ->
                    if (port == PLUTO_WS_LEGACY_DEFAULT_PORT) defaults.maiaPort else port
                }
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

    private fun loadMaiaScanConfigDefaults(): MaiaScanConfig {
        val defaults = MaiaScanConfig()
        val preferences = getSharedPreferences(PLUTO_PREFS_NAME, Context.MODE_PRIVATE)
        return MaiaScanConfig(
            sampleRateHz = preferences.getLong("maia_scan_sample_rate_hz", defaults.sampleRateHz),
            rfBandwidthHz = preferences.getLong("maia_scan_rf_bandwidth_hz", defaults.rfBandwidthHz),
            frequencyStepHz = preferences.getLong("maia_scan_frequency_step_hz", defaults.frequencyStepHz),
            usableSpanHz = preferences.getLong("maia_scan_usable_span_hz", defaults.usableSpanHz),
            loSettlingMs = preferences.getLong("maia_scan_lo_settling_ms", defaults.loSettlingMs),
            discardedFramesAfterRetune = preferences.getInt(
                "maia_scan_discarded_frames_after_retune",
                defaults.discardedFramesAfterRetune,
            ),
            fastMeasurementMs = preferences.getLong("maia_scan_fast_measurement_ms", defaults.fastMeasurementMs),
            candidateMeasurementMs = preferences.getLong(
                "maia_scan_candidate_measurement_ms",
                defaults.candidateMeasurementMs,
            ),
            candidateRevisitCount = preferences.getInt(
                "maia_scan_candidate_revisit_count",
                defaults.candidateRevisitCount,
            ),
            requiredPositiveRevisits = preferences.getInt(
                "maia_scan_required_positive_revisits",
                defaults.requiredPositiveRevisits,
            ),
            retuneTimeoutMs = preferences.getLong("maia_scan_retune_timeout_ms", defaults.retuneTimeoutMs),
            defaultRfPathSettlingMs = preferences.getLong(
                "maia_scan_default_rf_path_settling_ms",
                defaults.defaultRfPathSettlingMs,
            ),
            minSnrDb = Float.fromBits(preferences.getInt("maia_scan_min_snr_db_bits", defaults.minSnrDb.toBits())),
            minOccupiedBandwidthHz = preferences.getLong(
                "maia_scan_min_occupied_bandwidth_hz",
                defaults.minOccupiedBandwidthHz,
            ),
            maxOccupiedBandwidthHz = preferences.getLong(
                "maia_scan_max_occupied_bandwidth_hz",
                defaults.maxOccupiedBandwidthHz,
            ),
            minAdjacentBins = preferences.getInt("maia_scan_min_adjacent_bins", defaults.minAdjacentBins),
            dcExclusionBins = preferences.getInt("maia_scan_dc_exclusion_bins", defaults.dcExclusionBins),
            mergeFrequencyToleranceHz = preferences.getLong(
                "maia_scan_merge_frequency_tolerance_hz",
                defaults.mergeFrequencyToleranceHz,
            ),
            fpvMatchToleranceHz = preferences.getLong(
                "maia_scan_fpv_match_tolerance_hz",
                defaults.fpvMatchToleranceHz,
            ),
        )
    }

    private fun loadMaiaScanRanges(): List<ScanRange> {
        val preferences = getSharedPreferences(PLUTO_PREFS_NAME, Context.MODE_PRIVATE)
        return DefaultMaiaScanRanges.ranges.map { range ->
            range.copy(enabled = preferences.getBoolean("maia_scan_range_${range.id}_enabled", range.enabled))
        }
    }

    private fun savePlutoIqConfigDefaults(config: PlutoIqConfig) {
        getSharedPreferences(PLUTO_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_IQ_SOURCE, config.iqSource.preferenceValue)
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
        return DecodedFrame(
            bitmap = bitmap,
            diagnostic = diagnostic,
            edgeTimeline = edgeTimelineFromDiagnostic(diagnostic),
        )
    }

    private fun edgeTimelineFromDiagnostic(diagnostic: String): EdgeTimelineData? {
        if (diagnostic.isBlank()) {
            return null
        }
        val sampleCount = diagnosticNumber(diagnostic, "timeline_samples")
            ?.toInt()
            ?.takeIf { it > 0 }
            ?: return null
        return EdgeTimelineData(
            sampleCount = sampleCount,
            strictEdges = diagnosticIntList(diagnostic, "timeline_strict_edges", sampleCount),
            skippedEdges = diagnosticIntList(diagnostic, "timeline_skipped_edges", sampleCount),
        )
    }

    private fun diagnosticIntList(
        diagnostic: String,
        key: String,
        maxValue: Int,
    ): List<Int> {
        val regex = Regex("""\b${Regex.escape(key)}\s*[:=]\s*([0-9]+(?:,[0-9]+)*)?""")
        val raw = regex.find(diagnostic)?.groupValues?.getOrNull(1).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return raw.split(',')
            .mapNotNull { value -> value.toIntOrNull() }
            .filter { value -> value in 0..maxValue }
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
        val edgeTimeline: EdgeTimelineData?,
    )

    private enum class ScannerProbeProfile {
        QUICK,
        DEEP_CONFIRM,
    }

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

    private data class PlutoUsbSessionOpenResult(
        val sessionHandle: Long,
        val connection: UsbDeviceConnection?,
        val uri: String,
        val nativeError: String,
        val diagnostic: String,
    )

    private data class PlutoIpIioSessionOpenResult(
        val sessionHandle: Long,
        val previousBoundNetwork: Network?,
        val boundNetwork: Network?,
        val uri: String,
        val nativeError: String,
        val diagnostic: String,
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

    private data class ScannerProbeSelection(
        val channel: KnownChannel,
        val result: SignalProbeResult,
    )

    private data class PlutoIqConfig(
        val iqSource: PlutoIqSource,
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
            return maiaEndpoint()
        }

        fun toDiagnosticText(): String {
            return buildString {
                append("iq_source: ${iqSource.preferenceValue}")
                append("\npluto_iio_uri: ${plutoIioUri()}")
                append("\npluto_websocket_endpoint: ws://${plutoWebSocketEndpoint()}$plutoWebSocketPath")
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

        private fun plutoIioUri(): String {
            return "ip:${maiaHost.ifBlank { MAIA_DEFAULT_HOST }}"
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

    private enum class PlutoIqSource(val preferenceValue: String, val label: String) {
        IIO("iio", "Pluto IP IIO"),
        WEBSOCKET("websocket", "Pluto WebSocket");

        companion object {
            fun fromPreferenceValue(value: String?): PlutoIqSource {
                return when (value?.trim()?.lowercase(Locale.US)) {
                    "iio" -> IIO
                    "websocket", "ws" -> WEBSOCKET
                    else -> WEBSOCKET
                }
            }
        }
    }

    private enum class PlutoUsbAction {
        CAPTURE_TO_FILE,
        IP_IIO_CAPTURE,
        LIVE_PLAYBACK,
        IP_IIO_PLAYBACK,
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
        PLUTO_IP_PLAYBACK,
        PLUTO_SCANNER,
        PLUTO_USB_SPECTRUM,
        PLUTO_WS_PLAYBACK,
        PLUTO_WS_SPECTRUM,
        MAIA_PLAYBACK,
        MAIA_SPECTRUM;

        fun isPlaybackMode(): Boolean {
            return this == FILE_PLAYBACK ||
                this == PLUTO_USB_PLAYBACK ||
                this == PLUTO_IP_PLAYBACK ||
                this == PLUTO_WS_PLAYBACK ||
                this == MAIA_PLAYBACK
        }

        fun isSpectrumMode(): Boolean {
            return this == PLUTO_USB_SPECTRUM ||
                this == PLUTO_WS_SPECTRUM ||
                this == MAIA_SPECTRUM
        }

        fun isPlutoPlaybackMode(): Boolean {
            return this == PLUTO_USB_PLAYBACK || this == PLUTO_IP_PLAYBACK || this == PLUTO_WS_PLAYBACK
        }

        fun isPlutoSpectrumMode(): Boolean {
            return this == PLUTO_USB_SPECTRUM || this == PLUTO_WS_SPECTRUM
        }

        fun label(): String {
            return when (this) {
                PLUTO_USB_PLAYBACK -> "Pluto USB playback"
                PLUTO_IP_PLAYBACK -> "Pluto IP IIO playback"
                PLUTO_SCANNER -> "Pluto scanner"
                PLUTO_USB_SPECTRUM -> "Pluto USB FFT / waterfall"
                PLUTO_WS_PLAYBACK -> "Pluto WebSocket playback"
                PLUTO_WS_SPECTRUM -> "Pluto WebSocket FFT / waterfall"
                else -> name.lowercase(Locale.US)
            }
        }
    }

    /**
     * Opens a little-endian CS16 I/Q file and returns first-block diagnostics.
     * Use metadata-driven playback/FFT paths for CS8 captures.
     */
    external fun diagnoseCs16File(path: String): String

    /**
     * Reads a little-endian CS16 I/Q file in blocks and returns aggregate diagnostics.
     * Use metadata-driven playback/FFT paths for CS8 captures.
     */
    external fun diagnoseCs16FileBlocks(path: String): String

    /**
     * Reads an I/Q block using metadata format, applies a Hann-windowed FFT,
     * and returns spectrum diagnostics.
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
     * Captures raw IQ from PlutoSDR through libiio into a replayable file.
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

    external fun probePlutoIqMetrics(
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
        windowMs: Double,
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
        private const val SCANNER_RETUNE_SETTLE_MS = 150L
        private const val SCANNER_QUICK_PROBE_DISCARD_FRAME_COUNT = 4
        private const val SCANNER_QUICK_PROBE_FRAME_COUNT = 2
        private const val SCANNER_QUICK_CONFIRM_PROBE_FRAME_COUNT = 1
        private const val SCANNER_QUICK_REQUIRED_DETECTED_FRAME_COUNT = 1
        private const val SCANNER_DEEP_PROBE_DISCARD_FRAME_COUNT = 12
        private const val SCANNER_DEEP_FAST_PROBE_FRAME_COUNT = 3
        private const val SCANNER_DEEP_CONFIRM_PROBE_FRAME_COUNT = 5
        private const val SCANNER_DEEP_REQUIRED_DETECTED_FRAME_COUNT = 2
        private const val SCANNER_QUICK_IQ_METRICS_WINDOW_MS = 25.0
        private const val SCANNER_DEEP_IQ_METRICS_WINDOW_MS = 15.0
        private const val SCANNER_GAIN_ACQUISITION_CONFIRMATION_PASSES = 5
        private const val SCANNER_CONFIRMATION_CONFIDENCE = 0.35
        private const val SCANNER_MIN_ANALOG_CONFIDENCE = 0.50
        private const val SCAN_PLUTO_GAIN_DB = 38.0
        private const val SCANNER_NEARBY_CHANNEL_RADIUS_HZ = 12_000_000L
        private const val SCANNER_NEARBY_CHANNEL_Q_SWITCH_MARGIN = 0.03
        private const val SCANNER_NEARBY_CHANNEL_Q_TIE_MARGIN = 0.01
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
        private const val PLUTO_USB_OPEN_RETRY_COUNT = 3
        private const val PLUTO_USB_OPEN_RETRY_DELAY_MS = 700L
        private const val PLUTO_IP_IIO_PORT = 30431
        private const val PLUTO_IP_NETWORK_WAIT_MS = 5_000L
        private const val PLUTO_IP_NETWORK_POLL_MS = 250L
        private const val PLUTO_IP_CONNECT_TIMEOUT_MS = 1500
        private const val MAIA_RECORDER_PATH = "/api/recorder"
        private const val MAIA_CONNECT_TIMEOUT_MS = 3000
        private const val MAIA_READ_TIMEOUT_MS = 3000
        private const val MAIA_TEST_RESPONSE_LIMIT_BYTES = 64 * 1024
        private const val PLUTO_WS_CONNECT_TIMEOUT_MS = 3000
        private const val PLUTO_WS_READ_TIMEOUT_MS = 3000
        private const val PLUTO_WS_PRIME_MIN_INTERVAL_MS = 2_000L
        private const val PLUTO_WS_RECEIVE_BUFFER_MIN_MS = 50
        private const val PLUTO_WS_RECEIVE_BUFFER_MAX_MS = 150
        private const val PLUTO_WS_RECEIVE_BUFFER_DEFAULT_MS = 120
        private const val SCANNER_WEB_URL = "file:///android_asset/ui/sdr-scanner-panel.html"
        private const val SCANNER_WEB_MAX_EVENTS = 60
        private const val SCANNER_WEB_MAX_SPECTRUM_BINS = 256
        private const val SCANNER_WEB_SPECTRUM_THROTTLE_NS = 200_000_000L
        private const val MENU_PLAY_PLUTO_IIO_CS8 = 1
        private const val MENU_STOP = 2
        private const val MENU_SETUP_IQ = 3
        private const val MENU_RECORD_IQ = 4
        private const val MENU_PLUTO_WS_FFT = 5
        private const val MENU_PLAY_AUTO_FILE = 6
        private const val MENU_DECODE_AUTO_FILE = 7
        private const val MENU_TUNE_FREQUENCY = 8
        private const val MENU_PLAY_PLUTO_USB_CS16 = 9
        private const val MENU_ABOUT = 10
        private const val ACTION_USB_PERMISSION = "com.example.sdrvideoscanner.USB_PERMISSION"
        private const val PLUTO_USB_VENDOR_ID = 0x0456
        private const val PLUTO_USB_PRODUCT_ID = 0xb673
        private const val PLUTO_SAMPLE_RATE_HZ = 25_000_000L
        private const val PLUTO_CENTER_FREQUENCY_HZ = 5_885_000_000L
        private const val PLUTO_RF_BANDWIDTH_HZ = 20_000_000L
        private const val PLUTO_GAIN_DB = 46.0
        private const val PLUTO_CAPTURE_DURATION_SEC = 3.0
        private const val RECORD_IQ_MIN_CAPTURE_DURATION_SEC = 3.0
        private const val PLUTO_LO_OFFSET_HZ = 0L
        private const val PLUTO_HARDWARE_IQ_CORRECTION = true
        private const val PLUTO_HARDWARE_BBDC_CORRECTION = true
        private const val PLUTO_HARDWARE_RFDC_CORRECTION = true
        private const val MAIA_DEFAULT_HOST = "192.168.2.1"
        private const val MAIA_DEFAULT_PORT = 80
        private const val PLUTO_WS_DEFAULT_PORT = MAIA_DEFAULT_PORT
        private const val PLUTO_WS_LEGACY_DEFAULT_PORT = 7682
        private const val PLUTO_WS_DEFAULT_PATH = "/iq"
        private const val PLUTO_PREFS_NAME = "pluto_iq_setup"
        private const val PREF_IQ_SOURCE = "iq_source"
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
