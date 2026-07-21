package com.example.sdrvideoscanner

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class MaiaApiClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: MaiaApiClient
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/ad9361") { exchange ->
            record(exchange)
            respond(exchange, """{"rx_lo_frequency":5800000000}""")
        }
        server.createContext("/api/spectrometer") { exchange ->
            val body = record(exchange)
            val response = if (effectiveMethod(exchange) == "PATCH") {
                val requested = JSONObject(body).getDouble("output_sampling_frequency")
                """{"input":"AD9361","mode":"Average","output_sampling_frequency":${requested - 0.125},"integrations":17}"""
            } else {
                """{"input":"AD9361","mode":"Average","output_sampling_frequency":9.875,"integrations":17}"""
            }
            respond(exchange, response)
        }
        server.start()
        client = MaiaApiClient(
            host = "127.0.0.1",
            port = server.address.port,
            connectTimeoutMs = 1000,
            readTimeoutMs = 1000,
        )
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun readSpectrometerConfigurationParsesCompleteResponse() = runBlocking {
        val config = client.readSpectrometerConfiguration()

        assertEquals("AD9361", config.input)
        assertEquals("Average", config.mode)
        assertEquals(9.875, config.outputSamplingFrequency ?: -1.0, 0.0001)
        assertEquals(17, config.rawJson.getInt("integrations"))
    }

    @Test
    fun configureSpectrometerSendsRequestedFpsAndUsesReturnedActualFps() = runBlocking {
        val config = client.configureSpectrometer(10.0)

        val patch = requests.single { it.path == "/api/spectrometer" && it.effectiveMethod == "PATCH" }
        val body = JSONObject(patch.body)
        assertEquals("AD9361", body.getString("input"))
        assertEquals("Average", body.getString("mode"))
        assertEquals(10.0, body.getDouble("output_sampling_frequency"), 0.0001)
        assertEquals(9.875, config.outputSamplingFrequency ?: -1.0, 0.0001)
    }

    @Test
    fun configureForScanConfiguresAd9361BeforeSpectrometer() = runBlocking {
        val tuner = MaiaHttpRadioTuner(client)

        val result = tuner.configureForScan(
            MaiaScanConfig(waterfallFrameRateFps = 15.0),
            initialCenterFrequencyHz = 5_805_000_000L,
        )

        val patchPaths = requests.filter { it.effectiveMethod == "PATCH" }.map { it.path }
        assertTrue(patchPaths.indexOf("/api/ad9361") < patchPaths.indexOf("/api/spectrometer"))
        assertEquals(15.0, result.requestedWaterfallFrameRateFps, 0.0001)
        assertEquals(14.875, result.actualWaterfallFrameRateFps ?: -1.0, 0.0001)
        assertEquals("accepted", result.spectrometerStatus)
    }

    private fun record(exchange: HttpExchange): String {
        val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
        requests += RecordedRequest(exchange.requestMethod, effectiveMethod(exchange), exchange.requestURI.path, body)
        return body
    }

    private fun effectiveMethod(exchange: HttpExchange): String {
        return exchange.requestHeaders.getFirst("X-HTTP-Method-Override") ?: exchange.requestMethod
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class RecordedRequest(
        val method: String,
        val effectiveMethod: String,
        val path: String,
        val body: String,
    )
}
