package com.example.sdrvideoscanner

import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class MaiaWaterfallWebSocketClient(
    private val network: Network?,
    private val host: String,
    private val port: Int,
    private val path: String = "/waterfall",
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 3000,
    private val parser: WaterfallFrameParser = MaiaFloat32WaterfallFrameParser(),
    private val onReconnect: () -> Unit = {},
    private val onDroppedFrame: () -> Unit = {},
) : WaterfallSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameFlow = MutableSharedFlow<WaterfallFrame>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @Volatile private var job: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var currentCenterFrequencyHz: Long = 0L
    @Volatile private var currentSampleRateHz: Long = 0L
    @Volatile private var currentBandwidthHz: Long? = null
    private var sequenceNumber = 0L

    fun updateRadioMetadata(centerFrequencyHz: Long, sampleRateHz: Long, bandwidthHz: Long?) {
        currentCenterFrequencyHz = centerFrequencyHz
        currentSampleRateHz = sampleRateHz
        currentBandwidthHz = bandwidthHz
    }

    override suspend fun connect() {
        if (job?.isActive == true) {
            return
        }
        job = scope.launch {
            reconnectingReadLoop()
        }
    }

    override suspend fun disconnect() {
        job?.cancel()
        job = null
        closeSocketOnly()
    }

    override fun frames(): Flow<WaterfallFrame> = frameFlow.asSharedFlow()

    private suspend fun reconnectingReadLoop() {
        var backoffMs = INITIAL_RECONNECT_MS
        while (currentCoroutineContext().isActive) {
            try {
                openSocket()
                Log.i(LOG_TAG, "Connected Maia waterfall WebSocket ws://${endpoint()}$path")
                backoffMs = INITIAL_RECONNECT_MS
                readFrames()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Maia waterfall WebSocket disconnected: ${error.message ?: error.javaClass.name}", error)
                onReconnect()
                closeSocketOnly()
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RECONNECT_MS)
            }
        }
    }

    private fun openSocket() {
        validateEndpoint()
        val createdSocket = network?.socketFactory?.createSocket() ?: Socket()
        createdSocket.tcpNoDelay = true
        createdSocket.receiveBufferSize = RECEIVE_BUFFER_BYTES
        createdSocket.soTimeout = readTimeoutMs
        createdSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = createdSocket
        input = createdSocket.getInputStream()
        output = createdSocket.getOutputStream()
        val key = newWebSocketKey()
        writeHandshake(key)
        val response = readOpeningHandshakeHeaders()
        validateHandshake(response, key)
    }

    private fun readFrames() {
        while (job?.isActive == true) {
            when (val frame = readFrame()) {
                is WebSocketFrame.Binary -> {
                    val metadata = WaterfallFrameMetadata(
                        centerFrequencyHz = currentCenterFrequencyHz,
                        sampleRateHz = currentSampleRateHz,
                        bandwidthHz = currentBandwidthHz,
                        sequenceNumber = sequenceNumber++,
                        receiveTimestampNs = System.nanoTime(),
                    )
                    val parsed = parser.parse(frame.payload, metadata)
                    if (parsed == null || metadata.centerFrequencyHz <= 0L || metadata.sampleRateHz <= 0L) {
                        onDroppedFrame()
                        Log.w(LOG_TAG, "Dropped malformed Maia waterfall frame bytes=${frame.payload.size}")
                    } else if (!frameFlow.tryEmit(parsed)) {
                        onDroppedFrame()
                    }
                }
                is WebSocketFrame.Text -> Log.d(LOG_TAG, "Ignoring Maia waterfall text frame: ${frame.text.take(TEXT_LOG_LIMIT)}")
                is WebSocketFrame.Ping -> writeControlFrame(OPCODE_PONG, frame.payload)
                WebSocketFrame.Pong -> Unit
                WebSocketFrame.Close -> throw EOFException("Maia waterfall WebSocket closed")
            }
        }
    }

    private fun validateEndpoint() {
        require(host.isNotBlank()) { "Maia waterfall host is empty" }
        require(port in 1..65535) { "Maia waterfall port must be 1..65535" }
        require(path.startsWith("/") && !path.contains("://") && !path.contains(" ")) { "Maia waterfall path must be absolute" }
    }

    private fun writeHandshake(key: String) {
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: ${endpoint()}\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Origin: http://${endpoint()}\r\n")
            append("User-Agent: SDRVideoScanner/1.0\r\n")
            append("\r\n")
        }
        outputOrThrow().write(request.toByteArray(Charsets.US_ASCII))
        outputOrThrow().flush()
    }

    private fun readOpeningHandshakeHeaders(): String {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val stream = inputOrThrow()
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = stream.read()
            if (value < 0) {
                throw EOFException("WebSocket opening handshake ended early")
            }
            bytes.write(value)
            matched = if (value == (HEADER_END[matched].toInt() and 0xFF)) {
                matched + 1
            } else if (value == (HEADER_END[0].toInt() and 0xFF)) {
                1
            } else {
                0
            }
            if (matched == HEADER_END.size) {
                return bytes.toString(Charsets.ISO_8859_1.name())
            }
        }
        throw IllegalStateException("WebSocket opening handshake headers exceeded $MAX_HEADER_BYTES bytes")
    }

    private fun validateHandshake(response: String, key: String) {
        val lines = response.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        if (!statusLine.contains(" 101 ")) {
            throw IllegalStateException("Maia waterfall WebSocket upgrade failed: status_line=$statusLine headers=${response.trim()}")
        }
        val headers = lines.drop(1).mapNotNull { line ->
            val split = line.indexOf(':')
            if (split <= 0) null else line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
        }.toMap()
        val expected = websocketAccept(key)
        val actual = headers["sec-websocket-accept"].orEmpty()
        if (actual != expected) {
            throw IllegalStateException("Maia waterfall WebSocket accept mismatch")
        }
    }

    private fun readFrame(): WebSocketFrame {
        val first = readByteRequired()
        val second = readByteRequired()
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        val length = readPayloadLength(second and 0x7F)
        if (length > MAX_FRAME_PAYLOAD_BYTES) {
            throw IllegalStateException("Maia waterfall frame payload too large: $length")
        }
        val mask = if (masked) readExactly(MASK_BYTES) else null
        val payload = readExactly(length.toInt())
        if (mask != null) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % MASK_BYTES].toInt()).toByte()
            }
        }
        if (!fin && opcode != OPCODE_BINARY && opcode != OPCODE_TEXT) {
            throw IllegalStateException("Fragmented WebSocket control frame is invalid")
        }
        return when (opcode) {
            OPCODE_TEXT -> WebSocketFrame.Text(payload.toString(Charsets.UTF_8))
            OPCODE_BINARY, OPCODE_CONTINUATION -> WebSocketFrame.Binary(payload)
            OPCODE_CLOSE -> WebSocketFrame.Close
            OPCODE_PING -> WebSocketFrame.Ping(payload)
            OPCODE_PONG -> WebSocketFrame.Pong
            else -> throw IllegalStateException("Unsupported WebSocket opcode: $opcode")
        }
    }

    private fun readPayloadLength(initialLength: Int): Long {
        return when (initialLength) {
            126 -> {
                val bytes = readExactly(2)
                (((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)).toLong()
            }
            127 -> {
                val bytes = readExactly(8)
                var value = 0L
                for (byte in bytes) {
                    value = (value shl 8) or (byte.toLong() and 0xFFL)
                }
                value
            }
            else -> initialLength.toLong()
        }
    }

    private fun writeControlFrame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(MASK_BYTES)
        RANDOM.nextBytes(mask)
        val frame = ByteArray(2 + MASK_BYTES + payload.size)
        frame[0] = (0x80 or opcode).toByte()
        frame[1] = (0x80 or payload.size).toByte()
        System.arraycopy(mask, 0, frame, 2, MASK_BYTES)
        for (index in payload.indices) {
            frame[2 + MASK_BYTES + index] = (payload[index].toInt() xor mask[index % MASK_BYTES].toInt()).toByte()
        }
        outputOrThrow().write(frame)
        outputOrThrow().flush()
    }

    private fun readByteRequired(): Int {
        val value = inputOrThrow().read()
        if (value < 0) {
            throw EOFException("WebSocket stream closed")
        }
        return value and 0xFF
    }

    private fun readExactly(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        val stream = inputOrThrow()
        while (offset < count) {
            val read = stream.read(result, offset, count - offset)
            if (read < 0) {
                throw EOFException("WebSocket stream closed while reading $count bytes")
            }
            offset += read
        }
        return result
    }

    private fun closeSocketOnly() {
        runCatching { input?.close() }
        input = null
        runCatching { output?.close() }
        output = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun inputOrThrow(): InputStream = input ?: throw IllegalStateException("Maia waterfall input is not open")

    private fun outputOrThrow(): OutputStream = output ?: throw IllegalStateException("Maia waterfall output is not open")

    private fun endpoint(): String = if (port == 80) host else "$host:$port"

    private sealed class WebSocketFrame {
        data class Text(val text: String) : WebSocketFrame()
        data class Binary(val payload: ByteArray) : WebSocketFrame()
        data class Ping(val payload: ByteArray) : WebSocketFrame()
        object Pong : WebSocketFrame()
        object Close : WebSocketFrame()
    }

    companion object {
        private const val LOG_TAG = "SDRVideoScanner.MaiaWs"
        private const val INITIAL_RECONNECT_MS = 100L
        private const val MAX_RECONNECT_MS = 5_000L
        private const val RECEIVE_BUFFER_BYTES = 512 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_FRAME_PAYLOAD_BYTES = 32_000_000L
        private const val TEXT_LOG_LIMIT = 256
        private const val MASK_BYTES = 4
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val RANDOM = SecureRandom()

        private fun newWebSocketKey(): String {
            val nonce = ByteArray(16)
            RANDOM.nextBytes(nonce)
            return Base64.getEncoder().encodeToString(nonce)
        }

        private fun websocketAccept(key: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val digest = sha1.digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().encodeToString(digest)
        }
    }
}
