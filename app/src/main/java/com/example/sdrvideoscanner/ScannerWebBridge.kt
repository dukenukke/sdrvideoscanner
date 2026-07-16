package com.example.sdrvideoscanner

import android.webkit.JavascriptInterface

class ScannerWebBridge(
    private val handler: Handler,
) {
    interface Handler {
        fun startScan()
        fun pauseScan()
        fun resumeScan()
        fun stopScan()
        fun skipSignal()
        fun selectSignal(id: Long)
        fun lockSignal(id: Long)
        fun releaseSignal()
        fun showSpectrum()
        fun showVideo()
        fun applyScanConfig(config: ValidatedScanCommandConfig)
    }

    @JavascriptInterface
    fun startScan() {
        handler.startScan()
    }

    @JavascriptInterface
    fun pauseScan() {
        handler.pauseScan()
    }

    @JavascriptInterface
    fun resumeScan() {
        handler.resumeScan()
    }

    @JavascriptInterface
    fun stopScan() {
        handler.stopScan()
    }

    @JavascriptInterface
    fun skipSignal() {
        handler.skipSignal()
    }

    @JavascriptInterface
    fun selectSignal(id: String?) {
        ScannerWebCommandValidator.parseSignalId(id)?.let { handler.selectSignal(it) }
    }

    @JavascriptInterface
    fun lockSignal(id: String?) {
        ScannerWebCommandValidator.parseSignalId(id)?.let { handler.lockSignal(it) }
    }

    @JavascriptInterface
    fun releaseSignal() {
        handler.releaseSignal()
    }

    @JavascriptInterface
    fun showSpectrum() {
        handler.showSpectrum()
    }

    @JavascriptInterface
    fun showVideo() {
        handler.showVideo()
    }

    @JavascriptInterface
    fun applyScanConfig(json: String?) {
        val config = runCatching {
            ScannerWebCommandValidator.validateScanConfig(json.orEmpty())
        }.getOrNull() ?: return
        handler.applyScanConfig(config)
    }
}
