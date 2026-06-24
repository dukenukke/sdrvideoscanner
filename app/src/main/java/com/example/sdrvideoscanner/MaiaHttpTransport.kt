package com.example.sdrvideoscanner

import android.net.Network
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class MaiaHttpTransport private constructor(
    private val network: Network,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) {
    private var streamConnection: HttpURLConnection? = null
    private var streamInput: InputStream? = null
    private var lastError: String = ""
    private var nextBlockIndex: Long? = null
    private var recorderStarted = false
    private var bytesReceived = 0L
    private var samplesReceived = 0L
    private var statsWindowStartNs = System.nanoTime()

    @Synchronized
    fun configureRecorder(): String? {
        return try {
            val configure = patchRecorder(RECORDER_CONFIGURE_BODY, "configure")
                ?: return lastError
            if (!configure.isIq8Bit()) {
                return fail("Maia recorder mode remained ${configure.mode ?: "unknown"} after IQ8bit configuration; response=${configure.rawBody}")
            }

            val start = patchRecorder(RECORDER_START_BODY, "start")
                ?: return lastError
            if (!start.isRunning()) {
                return fail("Maia recorder did not enter Running after state_change Start; response=${start.rawBody}")
            }

            val verified = waitRecorderRunning()
                ?: return lastError
            if (!verified.isIq8Bit()) {
                return fail("Maia recorder mode is ${verified.mode ?: "unknown"} after start; expected IQ8bit; response=${verified.rawBody}")
            }
            if (!verified.isRunning()) {
                return fail("Maia recorder state is ${verified.state ?: "unknown"} after start; expected Running; response=${verified.rawBody}")
            }

            recorderStarted = true
            lastError = ""
            null
        } catch (error: SocketTimeoutException) {
            fail("Maia recorder startup timeout: ${error.message ?: error.javaClass.name}", error)
        } catch (error: Exception) {
            fail("Maia recorder startup failed: ${error.message ?: error.javaClass.name}", error)
        }
    }

    @Synchronized
    fun openStream(): String? {
        closeStream()
        return try {
            openStreamFromDiscoveredBlock(allowRolloverRetry = true)
        } catch (error: SocketTimeoutException) {
            closeStream()
            fail("Maia IQ stream GET timeout: ${error.message ?: error.javaClass.name}", error)
        } catch (error: Exception) {
            closeStream()
            fail("Maia IQ stream GET failed: ${error.message ?: error.javaClass.name}", error)
        }
    }

    @Synchronized
    fun read(destination: ByteArray, maxBytes: Int): Int {
        val input = streamInput ?: run {
            lastError = "Maia IQ stream is not open"
            return -1
        }
        var copied = 0
        return try {
            while (copied < maxBytes) {
                val count = input.read(destination, copied, maxBytes - copied)
                if (count < 0) {
                    return if (copied > 0) copied else 0
                }
                if (count == 0) {
                    break
                }
                copied += count
            }
            recordBytesReceived(copied)
            lastError = ""
            copied
        } catch (error: SocketTimeoutException) {
            if (copied > 0) {
                copied
            } else {
                failRead("Maia IQ stream read timeout: ${error.message ?: error.javaClass.name}", error)
            }
        } catch (error: Exception) {
            failRead("Maia IQ stream read failed: ${error.message ?: error.javaClass.name}", error)
        }
    }

    @Synchronized
    fun close() {
        closeStream()
        if (recorderStarted) {
            runCatching {
                patchRecorder(RECORDER_STOP_BODY, "stop")
            }.onFailure { error ->
                Log.w(LOG_TAG, "Maia recorder stop failed during close: ${error.message ?: error.javaClass.name}", error)
            }
            recorderStarted = false
        }
    }

    @Synchronized
    fun lastError(): String = lastError

    private fun openConnection(url: URL, method: String): HttpURLConnection {
        Log.i(LOG_TAG, "Opening Maia HTTP $method $url on network=$network handle=${network.networkHandle}")
        return (network.openConnection(url) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            doInput = true
            setRequestProperty("User-Agent", "SDRVideoScanner/1.0")
            setRequestProperty("Accept", "application/json,*/*")
            setRequestProperty("Connection", "close")
        }
    }

    private fun closeStream() {
        runCatching { streamInput?.close() }
        streamInput = null
        streamConnection?.disconnect()
        streamConnection = null
    }

    private fun recorderUrl(): URL = URL("http://${endpoint()}$RECORDER_PATH")

    private fun metadataUrl(path: String): URL = URL("http://${endpoint()}$path")

    private fun patchRecorder(bodyText: String, action: String): RecorderStatus? {
        val url = recorderUrl()
        var connection: HttpURLConnection? = null
        return try {
            Log.i(LOG_TAG, "Maia recorder PATCH action=$action request_body=$bodyText")
            connection = openConnection(url, "PATCH")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = bodyText.toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Length", body.size.toString())
            connection.outputStream.use { output ->
                output.write(body)
            }

            val code = connection.responseCode
            val responseBody = readBodyPrefix(connection, METADATA_BODY_LIMIT_BYTES)
            Log.i(LOG_TAG, "Maia recorder PATCH action=$action response code=$code headers=${headersText(connection)} body=$responseBody")
            if (code !in 200..299) {
                fail("Maia recorder PATCH $action HTTP error: $code, body=$responseBody")
                null
            } else {
                parseRecorderStatus(responseBody)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun getRecorderStatus(): RecorderStatus? {
        val url = recorderUrl()
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(url, "GET")
            connection.setRequestProperty("Accept", "application/json,*/*")
            val code = connection.responseCode
            val responseBody = readBodyPrefix(connection, METADATA_BODY_LIMIT_BYTES)
            Log.i(LOG_TAG, "Maia recorder GET response code=$code headers=${headersText(connection)} body=$responseBody")
            if (code !in 200..299) {
                fail("Maia recorder GET HTTP error: $code, body=$responseBody")
                null
            } else {
                parseRecorderStatus(responseBody)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun waitRecorderRunning(): RecorderStatus? {
        var lastStatus: RecorderStatus? = null
        repeat(RECORDER_STATE_RETRY_COUNT) {
            val status = getRecorderStatus()
            if (status != null) {
                lastStatus = status
                if (status.isRunning() && status.isIq8Bit()) {
                    Log.i(LOG_TAG, "Maia recorder verified Running/IQ8bit: ${status.diagnostic()}")
                    return status
                }
                Log.w(LOG_TAG, "Maia recorder not ready yet: ${status.diagnostic()}")
            }
            Thread.sleep(RECORDER_STATE_RETRY_DELAY_MS)
        }
        fail("Maia recorder did not verify Running/IQ8bit after start; last_response=${lastStatus?.rawBody ?: "none"}")
        return null
    }

    private fun stopRecorderForIqDownload(): String? {
        val status = getRecorderStatus()
            ?: return lastError
        if (!status.isRunning()) {
            Log.i(LOG_TAG, "Maia recorder is already stopped before iq-data: ${status.diagnostic()}")
            recorderStarted = false
            return null
        }

        val stop = patchRecorder(RECORDER_STOP_BODY, "stop-before-iq-data")
            ?: return lastError
        Log.i(LOG_TAG, "Maia recorder stop-before-iq-data response: ${stop.diagnostic()}")
        recorderStarted = false
        return waitRecorderStopped("stop-before-iq-data")?.let { it } ?: run {
            Thread.sleep(IQ_DOWNLOAD_AFTER_STOP_DELAY_MS)
            null
        }
    }

    private fun waitRecorderStopped(action: String): String? {
        var lastStatus: RecorderStatus? = null
        repeat(RECORDER_STATE_RETRY_COUNT) {
            val status = getRecorderStatus()
            if (status != null) {
                lastStatus = status
                if (!status.isRunning()) {
                    Log.i(LOG_TAG, "Maia recorder verified stopped for $action: ${status.diagnostic()}")
                    return null
                }
                Log.w(LOG_TAG, "Maia recorder still running for $action: ${status.diagnostic()}")
            }
            Thread.sleep(RECORDER_STATE_RETRY_DELAY_MS)
        }
        return fail("Maia recorder remained Running for $action; last_response=${lastStatus?.rawBody ?: "none"}")
    }

    private fun parseRecorderStatus(body: String): RecorderStatus {
        return runCatching {
            val json = JSONTokener(body).nextValue() as? JSONObject
            RecorderStatus(
                rawBody = body,
                state = json?.optString("state")?.takeIf { it.isNotBlank() },
                mode = json?.optString("mode")?.takeIf { it.isNotBlank() },
                prependTimestamp = json?.optBooleanOrNull("prepend_timestamp"),
                maximumDuration = json?.optDoubleOrNull("maximum_duration"),
            )
        }.getOrElse { error ->
            RecorderStatus(rawBody = body, parseError = error.message ?: error.javaClass.name)
        }
    }

    private fun waitForUsableRecordingMetadata(): RecordingMetadata? {
        var lastDiagnostic = "metadata was not queried"
        repeat(METADATA_RETRY_COUNT) { attempt ->
            METADATA_PATHS.forEach { path ->
                val result = queryRecordingMetadata(path)
                if (result != null) {
                    lastDiagnostic = result.diagnostic()
                    if (result.isUsable()) {
                        Log.i(LOG_TAG, "Usable Maia recording metadata discovered on attempt=${attempt + 1}: ${result.diagnostic()}")
                        return result
                    }
                    Log.w(LOG_TAG, "Maia recording metadata is not usable yet: ${result.diagnostic()}")
                }
            }
            Thread.sleep(METADATA_RETRY_DELAY_MS)
        }
        fail("Maia recording metadata did not report available IQ blocks after retries: $lastDiagnostic")
        return null
    }

    private fun queryRecordingMetadata(path: String): RecordingMetadata? {
        val url = metadataUrl(path)
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(url, "GET")
            connection.setRequestProperty("Accept", "application/json,*/*")
            val code = connection.responseCode
            val bodyText = readBodyPrefix(connection, METADATA_BODY_LIMIT_BYTES)
            Log.i(LOG_TAG, "Maia metadata GET url=$url code=$code headers=${headersText(connection)} body=$bodyText")
            if (code !in 200..299 || bodyText.isBlank()) {
                null
            } else {
                parseRecordingMetadata(path, bodyText)
            }
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Maia metadata GET failed url=$url message=${error.message ?: error.javaClass.name}", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRecordingMetadata(endpoint: String, body: String): RecordingMetadata {
        val parser = MetadataParser(endpoint = endpoint, rawBody = body)
        return runCatching {
            when (val value = JSONTokener(body).nextValue()) {
                is JSONObject -> parser.parseObject(value)
                is JSONArray -> parser.parseArray(value)
            }
            parser.toMetadata()
        }.getOrElse { error ->
            RecordingMetadata(
                endpoint = endpoint,
                rawBody = body,
                parseError = error.message ?: error.javaClass.name,
            )
        }
    }

    private fun openStreamFromDiscoveredBlock(allowRolloverRetry: Boolean): String? {
        var metadata = waitForUsableRecordingMetadata() ?: return lastError
        stopRecorderForIqDownload()?.let { return it }
        metadata = waitForUsableRecordingMetadata() ?: return lastError
        val requestedBlockIndex = selectBlockIndex(metadata)
        val blockSize = metadata.effectiveBlockSize()
            ?: return fail("Maia metadata did not report a valid block size: ${metadata.diagnostic()}")
        val url = iqDataUrl(requestedBlockIndex, blockSize)
        Log.i(
            LOG_TAG,
            "Maia IQ request metadata_endpoint=${metadata.endpoint}, mode=${metadata.mode}, " +
                "recording_started=${metadata.recordingStarted}, first_block=${metadata.firstBlockIndex}, " +
                "last_block=${metadata.lastBlockIndex}, available_blocks=${metadata.effectiveAvailableBlocks()}, " +
                "traceability_sample_length=${metadata.sampleLength}, block_size=$blockSize, " +
                "requested_block=$requestedBlockIndex, url=$url",
        )

        val connection = openConnection(url, "GET")
        connection.setRequestProperty("Accept", "application/octet-stream,*/*")
        val code = connection.responseCode
        Log.i(LOG_TAG, "Maia IQ stream GET response code=$code, headers=${headersText(connection)}")
        if (code in 200..299) {
            streamConnection = connection
            streamInput = connection.inputStream
            nextBlockIndex = requestedBlockIndex + 1L
            lastError = ""
            return null
        }

        val bodyText = readBodyPrefix(connection)
        connection.disconnect()
        Log.w(LOG_TAG, "Maia IQ stream HTTP error code=$code, body=$bodyText")
        if (allowRolloverRetry && looksLikeRecordingInProgress(bodyText)) {
            Log.w(LOG_TAG, "Maia rejected iq-data while recording is in progress; stopping recorder and retrying IQ download")
            stopRecorderForIqDownload()?.let { return it }
            return openStreamFromDiscoveredBlock(allowRolloverRetry = false)
        }
        if (allowRolloverRetry && looksLikeMissingBlock(bodyText)) {
            Log.w(LOG_TAG, "Requested Maia block was rejected; refreshing metadata and switching to oldest available block")
            nextBlockIndex = null
            Thread.sleep(METADATA_RETRY_DELAY_MS)
            return openStreamFromDiscoveredBlock(allowRolloverRetry = false)
        }
        return fail(
            "Maia IQ stream HTTP error: $code, requested_block=$requestedBlockIndex, " +
                "requested_block_size=$blockSize, metadata_json=${metadata.rawBody}, body=$bodyText",
        )
    }

    private fun iqDataUrl(blockIndex: Long, blockSize: Long): URL {
        val blockIndexes = URLEncoder.encode(blockIndex.toString(), Charsets.UTF_8.name())
        return URL("http://${endpoint()}$IQ_DATA_PATH?block_indexes_str=$blockIndexes&block_size=$blockSize")
    }

    private fun selectBlockIndex(metadata: RecordingMetadata): Long {
        val first = metadata.firstBlockIndex ?: 0L
        val last = metadata.effectiveLastBlockIndex() ?: first
        val candidate = nextBlockIndex
        return if (candidate != null && candidate in first..last) {
            candidate
        } else {
            if (candidate != null) {
                Log.w(LOG_TAG, "Maia requested block candidate=$candidate is outside available range=$first..$last; switching to oldest available block")
            }
            first
        }
    }

    private fun looksLikeMissingBlock(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("block") &&
            (lower.contains("missing") ||
                lower.contains("not found") ||
                lower.contains("does not exist") ||
                lower.contains("out of range") ||
                lower.contains("invalid"))
    }

    private fun looksLikeRecordingInProgress(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("recording_in_progress") ||
            (lower.contains("recording") && lower.contains("progress"))
    }

    private fun endpoint(): String {
        return if (port == 80) host else "$host:$port"
    }

    private fun fail(message: String, error: Throwable? = null): String {
        lastError = message
        if (error == null) {
            Log.w(LOG_TAG, message)
        } else {
            Log.w(LOG_TAG, message, error)
        }
        return message
    }

    private fun failRead(message: String, error: Throwable): Int {
        lastError = message
        Log.w(LOG_TAG, message, error)
        return -1
    }

    private fun recordBytesReceived(byteCount: Int) {
        if (byteCount <= 0) {
            return
        }
        bytesReceived += byteCount.toLong()
        samplesReceived += byteCount.toLong() / BYTES_PER_CS8_SAMPLE
        val now = System.nanoTime()
        val elapsedSec = (now - statsWindowStartNs).toDouble() / 1_000_000_000.0
        if (elapsedSec >= 1.0) {
            val bytesPerSec = bytesReceived / elapsedSec
            val samplesPerSec = samplesReceived / elapsedSec
            Log.i(
                LOG_TAG,
                "Maia HTTP stream bytes_received=$bytesReceived, samples_received=$samplesReceived, " +
                    "estimated_bytes_per_sec=${bytesPerSec.toLong()}, estimated_sample_rate=${samplesPerSec.toLong()}",
            )
            bytesReceived = 0L
            samplesReceived = 0L
            statsWindowStartNs = now
        }
    }

    private data class RecorderStatus(
        val rawBody: String,
        val state: String? = null,
        val mode: String? = null,
        val prependTimestamp: Boolean? = null,
        val maximumDuration: Double? = null,
        val parseError: String? = null,
    ) {
        fun isRunning(): Boolean = state.equals("Running", ignoreCase = true)

        fun isIq8Bit(): Boolean = mode.equals("IQ8bit", ignoreCase = true)

        fun diagnostic(): String {
            return "state=$state, mode=$mode, prepend_timestamp=$prependTimestamp, " +
                "maximum_duration=$maximumDuration, parse_error=$parseError, raw_json=$rawBody"
        }
    }

    private data class RecordingMetadata(
        val endpoint: String,
        val rawBody: String,
        val mode: String? = null,
        val recordingStarted: Boolean? = null,
        val sampleLength: Long? = null,
        val blockSize: Long? = null,
        val availableBlocks: Long? = null,
        val firstBlockIndex: Long? = null,
        val lastBlockIndex: Long? = null,
        val parseError: String? = null,
        val fieldsFound: Set<String> = emptySet(),
    ) {
        fun isUsable(): Boolean {
            val length = sampleLength ?: return false
            if (length <= 0L) {
                return false
            }
            val size = effectiveBlockSize() ?: return false
            val first = firstBlockIndex ?: return false
            val last = effectiveLastBlockIndex() ?: return false
            val count = effectiveAvailableBlocks()
            return parseError == null && size > 0L && first >= 0L && last >= first && count > 0L
        }

        fun effectiveBlockSize(): Long? = blockSize ?: sampleLength

        fun effectiveAvailableBlocks(): Long {
            val explicit = availableBlocks
            if (explicit != null) {
                return explicit
            }
            val first = firstBlockIndex
            val last = effectiveLastBlockIndex()
            if (first != null && last != null && last >= first) {
                return (last - first) + 1L
            }
            val size = effectiveBlockSize()
            val length = sampleLength
            if (size != null && size > 0L && length != null && length > 0L) {
                return ((length + size - 1L) / size).coerceAtLeast(1L)
            }
            return 0L
        }

        fun effectiveLastBlockIndex(): Long? {
            if (lastBlockIndex != null) {
                return lastBlockIndex
            }
            val first = firstBlockIndex
            val count = availableBlocks
            return if (first != null && count != null && count > 0L) {
                first + count - 1L
            } else {
                null
            }
        }

        fun diagnostic(): String {
            return "endpoint=$endpoint, mode=$mode, recording_started=$recordingStarted, " +
                "traceability_sample_length=$sampleLength, block_size=$blockSize, effective_block_size=${effectiveBlockSize()}, " +
                "available_blocks=$availableBlocks, effective_available_blocks=${effectiveAvailableBlocks()}, " +
                "first_block=$firstBlockIndex, last_block=$lastBlockIndex, effective_last_block=${effectiveLastBlockIndex()}, " +
                "parse_error=$parseError, missing_fields=${missingFields().joinToString()}, " +
                "fields_found=${fieldsFound.joinToString()}, raw_json=$rawBody"
        }

        private fun missingFields(): List<String> {
            val missing = mutableListOf<String>()
            if (sampleLength == null || sampleLength <= 0L) {
                missing += "global.traceability:sample_length"
            }
            if (effectiveBlockSize() == null || effectiveBlockSize()!! <= 0L) {
                missing += "block_size"
            }
            if (firstBlockIndex == null) {
                missing += "first_block"
            }
            if (lastBlockIndex == null) {
                missing += "last_block"
            }
            if (effectiveAvailableBlocks() <= 0L) {
                missing += "available_blocks"
            }
            return missing
        }
    }

    private class MetadataParser(
        private val endpoint: String,
        private val rawBody: String,
    ) {
        private var mode: String? = null
        private var recordingStarted: Boolean? = null
        private var sampleLength: Long? = null
        private var blockSize: Long? = null
        private var availableBlocks: Long? = null
        private var firstBlockIndex: Long? = null
        private var lastBlockIndex: Long? = null
        private val fieldsFound = linkedSetOf<String>()
        private val explicitBlockIndexes = mutableListOf<Long>()

        fun parseObject(obj: JSONObject) {
            obj.keys().forEach { key ->
                val value = obj.opt(key)
                parseField(key, value)
                when (value) {
                    is JSONObject -> parseObject(value)
                    is JSONArray -> parseArray(value)
                }
            }
        }

        fun parseArray(array: JSONArray) {
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is JSONObject -> parseObject(value)
                    is JSONArray -> parseArray(value)
                    is Number -> explicitBlockIndexes += value.toLong()
                }
            }
        }

        fun toMetadata(): RecordingMetadata {
            if (explicitBlockIndexes.isNotEmpty()) {
                if (firstBlockIndex == null) {
                    firstBlockIndex = explicitBlockIndexes.minOrNull()
                }
                if (lastBlockIndex == null) {
                    lastBlockIndex = explicitBlockIndexes.maxOrNull()
                }
                if (availableBlocks == null) {
                    availableBlocks = explicitBlockIndexes.distinct().size.toLong()
                }
                fieldsFound += "explicit_block_indexes"
            }
            if (availableBlocks == null && firstBlockIndex != null && lastBlockIndex != null) {
                availableBlocks = (lastBlockIndex!! - firstBlockIndex!!) + 1L
            }
            if (blockSize == null && sampleLength != null && sampleLength!! > 0L) {
                blockSize = sampleLength
                fieldsFound += "block_size_derived_from_traceability_sample_length"
            }
            if (availableBlocks == null && sampleLength != null && blockSize != null && blockSize!! > 0L) {
                availableBlocks = ((sampleLength!! + blockSize!! - 1L) / blockSize!!).coerceAtLeast(1L)
            }
            if (firstBlockIndex == null && lastBlockIndex != null && availableBlocks != null && availableBlocks!! > 0L) {
                firstBlockIndex = maxOf(0L, lastBlockIndex!! - availableBlocks!! + 1L)
            }
            if (firstBlockIndex == null && availableBlocks != null && availableBlocks!! > 0L) {
                firstBlockIndex = 0L
            }
            if (lastBlockIndex == null && firstBlockIndex != null && availableBlocks != null && availableBlocks!! > 0L) {
                lastBlockIndex = firstBlockIndex!! + availableBlocks!! - 1L
            }
            return RecordingMetadata(
                endpoint = endpoint,
                rawBody = rawBody,
                mode = mode,
                recordingStarted = recordingStarted,
                sampleLength = sampleLength,
                blockSize = blockSize,
                availableBlocks = availableBlocks,
                firstBlockIndex = firstBlockIndex,
                lastBlockIndex = lastBlockIndex,
                fieldsFound = fieldsFound,
            )
        }

        private fun parseField(key: String, value: Any?) {
            val normalized = key.lowercase()
                .replace("-", "_")
                .replace(":", "_")
            when (normalized) {
                "mode", "recorder_mode", "recording_mode", "recording_state", "recorder_state", "state" -> {
                    mode = value?.toString()
                    fieldsFound += key
                    boolValue(value)?.let { recordingStarted = it }
                }
                "recording", "recording_started", "started", "is_recording", "running", "active" -> {
                    boolValue(value)?.let {
                        recordingStarted = it
                        fieldsFound += key
                    }
                }
                "block_size", "blocksize", "block_bytes", "block_byte_size", "bytes_per_block", "buffer_block_size" -> {
                    positiveLong(value)?.let {
                        blockSize = it
                        fieldsFound += key
                    }
                }
                "traceability_sample_length", "sample_length", "sample_count", "samples", "recording_sample_length" -> {
                    positiveLong(value)?.let {
                        sampleLength = it
                        fieldsFound += key
                    }
                }
                "available_blocks", "num_blocks", "nblocks", "block_count", "blocks_count", "recording_blocks", "num_available_blocks" -> {
                    nonNegativeLong(value)?.let {
                        availableBlocks = it
                        fieldsFound += key
                    }
                }
                "first_block", "first_block_index", "firstblockindex", "oldest_block", "oldest_block_index", "oldestblockindex", "start_block", "min_block_index" -> {
                    nonNegativeLong(value)?.let {
                        firstBlockIndex = it
                        fieldsFound += key
                    }
                }
                "last_block", "last_block_index", "lastblockindex", "newest_block", "newest_block_index", "newestblockindex", "end_block", "max_block_index" -> {
                    nonNegativeLong(value)?.let {
                        lastBlockIndex = it
                        fieldsFound += key
                    }
                }
                "current_block", "current_block_index" -> {
                    nonNegativeLong(value)?.let {
                        if (lastBlockIndex == null) {
                            lastBlockIndex = it
                        }
                        fieldsFound += key
                    }
                }
                "block_index", "index" -> {
                    nonNegativeLong(value)?.let {
                        explicitBlockIndexes += it
                        fieldsFound += key
                    }
                }
            }
        }

        private fun positiveLong(value: Any?): Long? = nonNegativeLong(value)?.takeIf { it > 0L }

        private fun nonNegativeLong(value: Any?): Long? {
            return when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            }?.takeIf { it >= 0L }
        }

        private fun boolValue(value: Any?): Boolean? {
            return when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> when (value.trim().lowercase()) {
                    "true", "yes", "1", "started", "running", "active" -> true
                    "false", "no", "0", "stopped", "idle", "inactive" -> false
                    else -> null
                }
                else -> null
            }
        }
    }

    companion object {
        private const val LOG_TAG = "SDRVideoScanner.MaiaHttp"
        private const val RECORDER_PATH = "/api/recorder"
        private const val RECORDING_META_PATH = "/api/datasources/maiasdr/maiasdr/recording/meta"
        private const val IQ_DATA_PATH = "/api/datasources/maiasdr/maiasdr/recording/iq-data"
        private const val RECORDER_CONFIGURE_BODY = """{"mode":"IQ8bit","prepend_timestamp":false,"maximum_duration":0}"""
        private const val RECORDER_START_BODY = """{"state_change":"Start"}"""
        private const val RECORDER_STOP_BODY = """{"state_change":"Stop"}"""
        private const val BYTES_PER_CS8_SAMPLE = 2
        private const val BODY_PREFIX_LIMIT_BYTES = 4096
        private const val METADATA_BODY_LIMIT_BYTES = 64 * 1024
        private const val RECORDER_STATE_RETRY_COUNT = 10
        private const val RECORDER_STATE_RETRY_DELAY_MS = 200L
        private const val IQ_DOWNLOAD_AFTER_STOP_DELAY_MS = 500L
        private const val METADATA_RETRY_COUNT = 20
        private const val METADATA_RETRY_DELAY_MS = 250L

        private val METADATA_PATHS = listOf(RECORDING_META_PATH)

        @JvmStatic
        fun create(
            network: Network,
            host: String,
            port: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): MaiaHttpTransport {
            Log.i(LOG_TAG, "Created Maia HTTP transport network=$network handle=${network.networkHandle} endpoint=$host:$port")
            return MaiaHttpTransport(network, host, port, connectTimeoutMs, readTimeoutMs)
        }

        private fun headersText(connection: HttpURLConnection): String {
            return connection.headerFields.entries.joinToString(separator = "; ") { (name, values) ->
                "${name ?: "status"}=${values.joinToString(separator = ",")}"
            }
        }

        private fun JSONObject.optBooleanOrNull(name: String): Boolean? {
            return if (has(name) && !isNull(name)) {
                runCatching { getBoolean(name) }.getOrNull()
            } else {
                null
            }
        }

        private fun JSONObject.optDoubleOrNull(name: String): Double? {
            return if (has(name) && !isNull(name)) {
                runCatching { getDouble(name) }.getOrNull()
            } else {
                null
            }
        }

    private fun readBodyPrefix(
        connection: HttpURLConnection,
        limitBytes: Int = BODY_PREFIX_LIMIT_BYTES,
    ): String {
            val input = if (connection.responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                connection.errorStream
            } else {
                connection.inputStream
            } ?: return ""
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (output.size() < limitBytes) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, limitBytes - output.size()))
                    if (count <= 0) {
                        break
                    }
                    output.write(buffer, 0, count)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        }
    }
}
