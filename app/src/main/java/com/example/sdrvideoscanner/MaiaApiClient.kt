package com.example.sdrvideoscanner

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MaiaApiClient(
    private val host: String,
    private val port: Int,
    private val network: Network? = null,
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 3000,
) {
    suspend fun configureAd9361(sampleRateHz: Long, rfBandwidthHz: Long, centerFrequencyHz: Long? = null) {
        val body = JSONObject()
            .put("sampling_frequency", sampleRateHz)
            .put("rx_rf_bandwidth", rfBandwidthHz)
        if (centerFrequencyHz != null) {
            body.put("rx_lo_frequency", centerFrequencyHz)
        }
        patch("/api/ad9361", body)
    }

    suspend fun tune(centerFrequencyHz: Long) {
        patch("/api/ad9361", JSONObject().put("rx_lo_frequency", centerFrequencyHz))
    }

    suspend fun configureSpectrometer() {
        patch(
            "/api/spectrometer",
            JSONObject()
                .put("input", "AD9361")
                .put("mode", "Average"),
        )
    }

    suspend fun getAd9361FrequencyHz(): Long? {
        val json = getJson("/api/ad9361")
        return json.optLong("rx_lo_frequency").takeIf { it > 0L }
    }

    private suspend fun patch(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = openConnection(path, "PATCH")
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Content-Length", bytes.size.toString())
            connection.outputStream.use { it.write(bytes) }
            readJsonResponse(connection, path, body.toString())
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = openConnection(path, "GET")
        try {
            readJsonResponse(connection, path, requestBody = null)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val url = URL("http://${endpoint()}$path")
        Log.i(LOG_TAG, "Maia API $method $url")
        return ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            doInput = true
            setRequestProperty("Accept", "application/json,*/*")
            setRequestProperty("User-Agent", "SDRVideoScanner/1.0")
        }
    }

    private fun readJsonResponse(connection: HttpURLConnection, path: String, requestBody: String?): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("Maia API $path HTTP $code request=$requestBody response=$text")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun endpoint(): String = if (port == 80) host else "$host:$port"

    companion object {
        private const val LOG_TAG = "SDRVideoScanner.MaiaApi"
    }
}

class MaiaHttpRadioTuner(
    private val apiClient: MaiaApiClient,
) : IRadioTuner {
    private var currentFrequencyHz: Long? = null

    override suspend fun configure(sampleRateHz: Long, rfBandwidthHz: Long) {
        apiClient.configureAd9361(sampleRateHz, rfBandwidthHz, currentFrequencyHz)
        apiClient.configureSpectrometer()
    }

    override suspend fun tune(centerFrequencyHz: Long) {
        apiClient.tune(centerFrequencyHz)
        currentFrequencyHz = centerFrequencyHz
    }

    override suspend fun currentFrequencyHz(): Long? {
        return apiClient.getAd9361FrequencyHz() ?: currentFrequencyHz
    }
}
