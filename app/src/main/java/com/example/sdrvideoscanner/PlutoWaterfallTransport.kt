package com.example.sdrvideoscanner

import android.net.Network
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Minimal WebSocket client for Pluto waterfall frames.
 *
 * The /waterfall endpoint is used only as a scanner pre-filter. Binary payloads
 * are treated as unsigned FFT/waterfall intensity bins.
 */
class PlutoWaterfallTransport private constructor(
    private val network: Network,
    private val host: String,
    private val port: Int,
    path: String,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) {
    private val path = path.trim()
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var lastError: String = ""

    @Synchronized
    fun openStream(): String? {
        close()
        return try {
            validateEndpoint()?.let { return it }
            val createdSocket = network.socketFactory.createSocket()
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
            validateHandshake(response, key)?.let { return fail(it) }
            lastError = ""
            Log.i(LOG_TAG, "Opened Pluto waterfall stream ws://${endpoint()}$path network=$network handle=${network.networkHandle}")
            null
        } catch (error: SocketTimeoutException) {
            close()
            fail("Pluto waterfall WebSocket timeout: ${error.message ?: error.javaClass.name}", error)
        } catch (error: Exception) {
            close()
            fail("Pluto waterfall WebSocket open failed: ${error.message ?: error.javaClass.name}", error)
        }
    }

    @Synchronized
    fun readBinaryFrame(): ByteArray? {
        if (input == null || output == null) {
            lastError = "Pluto waterfall WebSocket stream is not open"
            return null
        }

        return try {
            repeat(MAX_CONTROL_OR_TEXT_FRAMES) {
                when (val frame = readFrame()) {
                    is WebSocketFrame.Binary -> {
                        lastError = ""
                        return frame.payload
                    }
                    is WebSocketFrame.Text -> {
                        Log.i(LOG_TAG, "Ignoring Pluto waterfall text frame: ${frame.text.take(TEXT_LOG_LIMIT)}")
                    }
                    is WebSocketFrame.Ping -> writeControlFrame(OPCODE_PONG, frame.payload)
                    is WebSocketFrame.Pong -> Unit
                    WebSocketFrame.Close -> {
                        closeSocketOnly()
                        lastError = "Pluto waterfall WebSocket closed"
                        return null
                    }
                }
            }
            fail("Pluto waterfall WebSocket did not produce a binary frame")
            null
        } catch (error: SocketTimeoutException) {
            fail("Pluto waterfall WebSocket read timeout: ${error.message ?: error.javaClass.name}", error)
            null
        } catch (error: Exception) {
            fail("Pluto waterfall WebSocket read failed: ${error.message ?: error.javaClass.name}", error)
            null
        }
    }

    @Synchronized
    fun close() {
        runCatching {
            if (output != null) {
                writeControlFrame(OPCODE_CLOSE, ByteArray(0))
            }
        }
        closeSocketOnly()
    }

    @Synchronized
    fun lastError(): String = lastError

    private fun validateEndpoint(): String? {
        if (host.isBlank()) {
            return fail("Pluto waterfall host is empty")
        }
        if (port !in 1..65535) {
            return fail("Pluto waterfall port must be between 1 and 65535")
        }
        if (!path.startsWith('/')) {
            return fail("Pluto waterfall path must start with /")
        }
        if (path.contains('\r') || path.contains('\n') || path.contains(' ') || path.contains("://")) {
            return fail("Pluto waterfall path must be a plain absolute path, for example /waterfall")
        }
        return null
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
            append("Cache-Control: no-cache\r\n")
            append("Pragma: no-cache\r\n")
            append("User-Agent: SDRVideoScanner/1.0\r\n")
            append("\r\n")
        }
        outputOrThrow().write(request.toByteArray(Charsets.US_ASCII))
        outputOrThrow().flush()
    }

    private fun readOpeningHandshakeHeaders(): String {
        val bytes = ByteArrayOutputStream()
        val stream = inputOrThrow()
        var matched = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = stream.read()
            if (value < 0) {
                throw EOFException("WebSocket opening handshake ended before headers completed")
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

    private fun validateHandshake(response: String, key: String): String? {
        val lines = response.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        if (!statusLine.contains(" 101 ")) {
            return "Pluto waterfall WebSocket upgrade failed: url=ws://${endpoint()}$path status_line=$statusLine headers=${response.trim()}"
        }
        val headers = lines.drop(1)
            .mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null else line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
            }
            .toMap()
        if (!headers["upgrade"].orEmpty().equals("websocket", ignoreCase = true)) {
            return "Pluto waterfall upgrade response did not include Upgrade: websocket; headers=${response.trim()}"
        }
        val expectedAccept = websocketAccept(key)
        val accept = headers["sec-websocket-accept"].orEmpty()
        if (accept != expectedAccept) {
            return "Pluto waterfall accept mismatch: expected=$expectedAccept actual=$accept"
        }
        return null
    }

    private fun readFrame(): WebSocketFrame {
        val first = readByteRequired()
        val second = readByteRequired()
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        val length = readPayloadLength(second and 0x7F)
        if (length > MAX_FRAME_PAYLOAD_BYTES) {
            throw IllegalStateException("WebSocket frame payload is too large: $length bytes")
        }
        val mask = if (masked) readExactly(MASK_BYTES) else null
        if (!fin && opcode != OPCODE_BINARY && opcode != OPCODE_TEXT) {
            throw IllegalStateException("Fragmented WebSocket control frame is invalid")
        }
        val payload = readPayloadBytes(length.toInt(), mask)
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
                ((bytes[0].toInt() and 0xFF) shl 8 or (bytes[1].toInt() and 0xFF)).toLong()
            }
            127 -> {
                val bytes = readExactly(8)
                var value = 0L
                for (byte in bytes) {
                    value = (value shl 8) or (byte.toLong() and 0xFFL)
                }
                if (value < 0) {
                    throw IllegalStateException("WebSocket frame length overflow")
                }
                value
            }
            else -> initialLength.toLong()
        }
    }

    private fun readPayloadBytes(length: Int, mask: ByteArray?): ByteArray {
        val payload = readExactly(length)
        if (mask != null) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % MASK_BYTES].toInt()).toByte()
            }
        }
        return payload
    }

    private fun writeControlFrame(opcode: Int, payload: ByteArray) {
        if (payload.size > 125) {
            throw IllegalArgumentException("WebSocket control payload is too large")
        }
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

    private fun inputOrThrow(): InputStream = input ?: throw IllegalStateException("Pluto waterfall input is not open")

    private fun outputOrThrow(): OutputStream = output ?: throw IllegalStateException("Pluto waterfall output is not open")

    private fun endpoint(): String = if (port == 80) host else "$host:$port"

    private fun fail(message: String, error: Throwable? = null): String {
        lastError = message
        if (error == null) {
            Log.w(LOG_TAG, message)
        } else {
            Log.w(LOG_TAG, message, error)
        }
        return message
    }

    private sealed class WebSocketFrame {
        data class Text(val text: String) : WebSocketFrame()
        data class Binary(val payload: ByteArray) : WebSocketFrame()
        data class Ping(val payload: ByteArray) : WebSocketFrame()
        object Pong : WebSocketFrame()
        object Close : WebSocketFrame()
    }

    companion object {
        private const val LOG_TAG = "SDRVideoScanner.Waterfall"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_FRAME_PAYLOAD_BYTES = 4_000_000L
        private const val MAX_CONTROL_OR_TEXT_FRAMES = 16
        private const val RECEIVE_BUFFER_BYTES = 128 * 1024
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

        fun create(
            network: Network,
            host: String,
            port: Int,
            path: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): PlutoWaterfallTransport {
            return PlutoWaterfallTransport(network, host, port, path, connectTimeoutMs, readTimeoutMs)
        }

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
