package com.spasfonk.obsidianrecorder.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

enum class EngineState { IDLE, RECORDING, PAUSED_SILENCE, PAUSED_MANUAL, STOPPED }

data class LevelSnapshot(
    val peakDb: Float,
    val rmsDb: Float,
    val timestampMs: Long
)

private class IntRingQueue(capacity: Int) {
    private val buffer = IntArray(capacity)
    private val cap = capacity

    @Volatile private var head = 0
    @Volatile private var tail = 0

    fun offer(value: Int): Boolean {
        val nextTail = (tail + 1) % cap
        if (nextTail == head) return false
        buffer[tail] = value
        tail = nextTail
        return true
    }

    fun poll(): Int {
        if (head == tail) return -1
        val v = buffer[head]
        head = (head + 1) % cap
        return v
    }

    fun isEmpty(): Boolean = head == tail
}

class AudioRecordEngine(
    private val outputFile: File,
    private val sampleRate: Int = 44100,
    initialThresholdDb: Float = -45f,
    private val silenceHoldMs: Long = 1000L,
    private val preRollMs: Long = 400L
) {
    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _level = MutableStateFlow(LevelSnapshot(-90f, -90f, 0L))
    val level: StateFlow<LevelSnapshot> = _level.asStateFlow()

    private val _waveformHistory = MutableStateFlow<List<Float>>(emptyList())
    val waveformHistory: StateFlow<List<Float>> = _waveformHistory.asStateFlow()

    @Volatile
    var thresholdDb: Float = initialThresholdDb

    private val hysteresisDb = 3f
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val encoderLock = Any()
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    private var muxerStarted = false
    @Volatile private var eosDrained = false
    private var totalPresentationTimeUs = 0L

    private lateinit var callbackThread: HandlerThread
    private lateinit var callbackHandler: Handler

    private val availableInputIndices = IntRingQueue(64)

    @Volatile
    private var running = false

    @Volatile
    private var manualPause = false

    private val chunkSamples = 1024
    private val chunkBytes = chunkSamples * 2

    private val bufferSizeBytes: Int by lazy {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        maxOf(minBuf, 4096) * 2
    }

    private val pcmStagingBytes = ByteArray(chunkBytes)
    private val pcmStagingBuffer: ByteBuffer = ByteBuffer.wrap(pcmStagingBytes)

    private val feedPoolSize = 16
    private val feedPool: Array<ByteArray> = Array(feedPoolSize) { ByteArray(chunkBytes) }
    private val feedLengths = IntArray(feedPoolSize)
    private var feedWriteIdx = 0
    private var feedReadIdx = 0
    private var feedCount = 0

    private val preRollPoolSize = ((preRollMs / 1000f) * (sampleRate / chunkSamples.toFloat()) * 2f).toInt().coerceAtLeast(4)
    private val preRollPool: Array<ShortArray> = Array(preRollPoolSize) { ShortArray(chunkSamples) }
    private val preRollLengths = IntArray(preRollPoolSize)
    private var preRollWriteIndex = 0
    private var preRollCount = 0
    private var preRollDurationMs = 0L

    private var lastWaveformEmitMs = 0L
    private val waveformEmitIntervalMs = 33L

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        setupEncoderAsync()
        audioRecord = AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes
        )
        running = true
        manualPause = false
        _state.value = EngineState.RECORDING
        audioRecord?.startRecording()

        captureThread = thread(start = true, name = "AudioCaptureThread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            captureLoop()
            drainRemainingAndSignalEos()
        }
    }

    fun setManualPause(paused: Boolean) {
        manualPause = paused
        _state.value = if (paused) EngineState.PAUSED_MANUAL else EngineState.RECORDING
    }

    fun stop() {
        running = false
        captureThread?.join(3000)
        captureThread = null
        try { audioRecord?.stop() } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null

        val deadline = System.currentTimeMillis() + 2000
        while (!eosDrained && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        finalizeEncoder()
        _state.value = EngineState.STOPPED
    }

    private fun setupEncoderAsync() {
        callbackThread = HandlerThread("MediaCodecCallbackThread").apply { start() }
        callbackHandler = Handler(callbackThread.looper)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerTrackIndex = -1
        muxerStarted = false
        eosDrained = false
        totalPresentationTimeUs = 0L

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    if (!availableInputIndices.offer(index)) {
                        try {
                            mc.queueInputBuffer(index, 0, 0, 0L, 0)
                        } catch (_: Exception) { }
                    }
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    handleOutputBuffer(mc, index, info)
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    eosDrained = true
                }

                override fun onOutputFormatChanged(mc: MediaCodec, newFormat: MediaFormat) {
                    synchronized(encoderLock) {
                        if (!muxerStarted) {
                            muxerTrackIndex = muxer?.addTrack(newFormat) ?: -1
                            muxer?.start()
                            muxerStarted = true
                        }
                    }
                }
            }, callbackHandler)
            start()
        }
    }

    private fun handleOutputBuffer(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        synchronized(encoderLock) {
            val mx = muxer
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                info.size = 0
            }
            if (info.size > 0 && muxerStarted && mx != null) {
                val encodedData = mc.getOutputBuffer(index)
                if (encodedData != null) {
                    encodedData.position(info.offset)
                    encodedData.limit(info.offset + info.size)
                    mx.writeSampleData(muxerTrackIndex, encodedData, info)
                }
            }
            try { mc.releaseOutputBuffer(index, false) } catch (_: Exception) { }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                eosDrained = true
            }
        }
    }

    private fun captureLoop() {
        val chunkBuffer = ShortArray(chunkSamples)
        val samplesPerMs = sampleRate / 1000f
        var silentSinceMs: Long = -1L

        val historySize = 120
        val historyRing = FloatArray(historySize)
        var historyIndex = 0
        var historyFilled = 0

        while (running) {
            val ar = audioRecord ?: break
            val read = ar.read(chunkBuffer, 0, chunkSamples)
            if (read <= 0) continue

            var peak = 0
            var sumSquares = 0.0
            for (i in 0 until read) {
                val sample = chunkBuffer[i].toInt()
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
                sumSquares += (sample.toDouble() * sample.toDouble())
            }
            val rms = sqrt(sumSquares / read)
            val peakDb = amplitudeToDb(peak.toDouble())
            val rmsDb = amplitudeToDb(rms)
            val nowMs = System.currentTimeMillis()

            _level.value = LevelSnapshot(peakDb.toFloat(), rmsDb.toFloat(), nowMs)

            historyRing[historyIndex] = rmsDb.toFloat()
            historyIndex = (historyIndex + 1) % historySize
            if (historyFilled < historySize) historyFilled++

            if (nowMs - lastWaveformEmitMs >= waveformEmitIntervalMs) {
                lastWaveformEmitMs = nowMs
                _waveformHistory.value = buildOrderedSnapshot(historyRing, historyIndex, historyFilled)
            }

            val effectiveThreshold = if (silentSinceMs >= 0) thresholdDb + hysteresisDb else thresholdDb
            val isLoud = rmsDb > effectiveThreshold

            pushToPreRollPool(chunkBuffer, read, samplesPerMs)

            if (manualPause) continue

            if (isLoud) {
                silentSinceMs = -1L
                if (_state.value == EngineState.PAUSED_SILENCE) {
                    _state.value = EngineState.RECORDING
                    flushPreRollPool()
                } else {
                    submitChunk(chunkBuffer, read)
                }
            } else {
                if (silentSinceMs < 0) silentSinceMs = nowMs
                val silentDuration = nowMs - silentSinceMs
                if (silentDuration < silenceHoldMs) {
                    submitChunk(chunkBuffer, read)
                } else if (_state.value != EngineState.PAUSED_SILENCE) {
                    _state.value = EngineState.PAUSED_SILENCE
                }
            }
        }
    }

    private fun buildOrderedSnapshot(ring: FloatArray, writeIndex: Int, filled: Int): List<Float> {
        val size = ring.size
        val result = ArrayList<Float>(filled)
        val start = if (filled < size) 0 else writeIndex
        for (i in 0 until filled) {
            result.add(ring[(start + i) % size])
        }
        return result
    }

    private fun pushToPreRollPool(source: ShortArray, length: Int, samplesPerMs: Float) {
        val slot = preRollPool[preRollWriteIndex]
        System.arraycopy(source, 0, slot, 0, length)
        preRollLengths[preRollWriteIndex] = length
        preRollWriteIndex = (preRollWriteIndex + 1) % preRollPool.size
        if (preRollCount < preRollPool.size) preRollCount++
        preRollDurationMs += (length / samplesPerMs).toLong()

        while (preRollDurationMs > preRollMs && preRollCount > 1) {
            val oldestIndex = (preRollWriteIndex - preRollCount + preRollPool.size) % preRollPool.size
            val oldestLength = preRollLengths[oldestIndex]
            preRollDurationMs -= (oldestLength / samplesPerMs).toLong()
            preRollCount--
        }
    }

    private fun flushPreRollPool() {
        val startIndex = (preRollWriteIndex - preRollCount + preRollPool.size) % preRollPool.size
        for (i in 0 until preRollCount) {
            val idx = (startIndex + i) % preRollPool.size
            submitChunk(preRollPool[idx], preRollLengths[idx])
        }
        preRollCount = 0
        preRollDurationMs = 0L
    }

    private fun amplitudeToDb(amplitude: Double): Double {
        if (amplitude < 1.0) return -90.0
        val ref = Short.MAX_VALUE.toDouble()
        return (20 * log10(amplitude / ref)).coerceIn(-90.0, 0.0)
    }

    private fun submitChunk(shorts: ShortArray, length: Int) {
        drainFeedBacklog()

        if (feedCount == 0) {
            val index = availableInputIndices.poll()
            if (index >= 0) {
                queueShortsDirect(index, shorts, length, endOfStream = false)
                return
            }
        }
        enqueueToFeedPool(shorts, length)
        drainFeedBacklog()
    }

    private fun enqueueToFeedPool(shorts: ShortArray, length: Int) {
        if (feedCount >= feedPoolSize) {
            feedReadIdx = (feedReadIdx + 1) % feedPoolSize
            feedCount--
        }
        val slot = feedPool[feedWriteIdx]
        val byteLen = length * 2
        val bb = ByteBuffer.wrap(slot)
        bb.clear()
        for (i in 0 until length) bb.putShort(shorts[i])
        feedLengths[feedWriteIdx] = byteLen
        feedWriteIdx = (feedWriteIdx + 1) % feedPoolSize
        feedCount++
    }

    private fun drainFeedBacklog() {
        while (feedCount > 0) {
            val index = availableInputIndices.poll()
            if (index < 0) return
            val bytes = feedPool[feedReadIdx]
            val len = feedLengths[feedReadIdx]
            queueBytesDirect(index, bytes, len, endOfStream = false)
            feedReadIdx = (feedReadIdx + 1) % feedPoolSize
            feedCount--
        }
    }

    private fun queueShortsDirect(index: Int, shorts: ShortArray, length: Int, endOfStream: Boolean) {
        pcmStagingBuffer.clear()
        for (i in 0 until length) pcmStagingBuffer.putShort(shorts[i])
        queueBytesDirect(index, pcmStagingBytes, length * 2, endOfStream)
    }

    private fun queueBytesDirect(index: Int, bytes: ByteArray, byteLength: Int, endOfStream: Boolean) {
        val mc = codec ?: return
        try {
            val inputBuffer = mc.getInputBuffer(index) ?: return
            inputBuffer.clear()
            val toWrite = minOf(inputBuffer.remaining(), byteLength)
            if (toWrite > 0) inputBuffer.put(bytes, 0, toWrite)
            val flag = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            val presentationTimeUs = totalPresentationTimeUs
            totalPresentationTimeUs += (toWrite / 2) * 1_000_000L / sampleRate
            mc.queueInputBuffer(index, 0, toWrite, presentationTimeUs, flag)
        } catch (_: Exception) { }
    }

    private fun drainRemainingAndSignalEos() {
        val deadline = System.currentTimeMillis() + 2000
        while (feedCount > 0 && System.currentTimeMillis() < deadline) {
            drainFeedBacklog()
            if (feedCount > 0) Thread.sleep(2)
        }
        var index = -1
        val eosDeadline = System.currentTimeMillis() + 2000
        while (index < 0 && System.currentTimeMillis() < eosDeadline) {
            index = availableInputIndices.poll()
            if (index < 0) Thread.sleep(2)
        }
        if (index >= 0) {
            queueBytesDirect(index, pcmStagingBytes, 0, endOfStream = true)
        } else {
            eosDrained = true
        }
    }

    private fun finalizeEncoder() {
        synchronized(encoderLock) {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            codec = null
            muxer = null
            muxerStarted = false
        }
        try { callbackThread.quitSafely() } catch (_: Exception) { }
    }
}
