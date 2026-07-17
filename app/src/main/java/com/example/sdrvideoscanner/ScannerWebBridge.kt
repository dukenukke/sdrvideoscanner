package com.example.sdrvideoscanner

import android.webkit.JavascriptInterface
import org.json.JSONObject

class ScannerWebBridge(
    private val handler: Handler,
) {
    data class NativeVideoRect(
        val leftPx: Int,
        val topPx: Int,
        val widthPx: Int,
        val heightPx: Int,
        val viewportWidthPx: Int,
        val viewportHeightPx: Int,
        val visible: Boolean,
    )

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
        fun updateNativeVideoRect(rect: NativeVideoRect)
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

    @JavascriptInterface
    fun updateVideoRect(json: String?) {
        val rect = runCatching {
            val obj = JSONObject(json.orEmpty())
            NativeVideoRect(
                leftPx = obj.optDouble("left", 0.0).toInt().coerceAtLeast(0),
                topPx = obj.optDouble("top", 0.0).toInt().coerceAtLeast(0),
                widthPx = obj.optDouble("width", 0.0).toInt().coerceIn(0, MAX_VIDEO_RECT_PX),
                heightPx = obj.optDouble("height", 0.0).toInt().coerceIn(0, MAX_VIDEO_RECT_PX),
                viewportWidthPx = obj.optDouble("viewportWidth", 0.0).toInt().coerceIn(0, MAX_VIDEO_RECT_PX),
                viewportHeightPx = obj.optDouble("viewportHeight", 0.0).toInt().coerceIn(0, MAX_VIDEO_RECT_PX),
                visible = obj.optBoolean("visible", false),
            )
        }.getOrNull() ?: return
        handler.updateNativeVideoRect(rect)
    }

    private companion object {
        const val MAX_VIDEO_RECT_PX = 8_192
    }
}
