package com.localvoice.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CaptureMode { MANUAL, HANDS_FREE }
enum class ListeningState { IDLE, LISTENING, SPEECH_ACTIVE }

data class SpeechLoadResult(
    val ok: Boolean,
    val message: String,
)

class AudioPipeline(
    private val context: Context,
    private val captureDispatcher: CoroutineDispatcher,
    private val sttDispatcher: CoroutineDispatcher,
    private val onStateChanged: (ListeningState) -> Unit,
    private val onPartialTranscript: (String) -> Unit,
    private val onFinalTranscript: (text: String, sttLatencyMs: Long) -> Unit,
    private val onBargeIn: () -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 512 // Matches Silero VAD's 32 ms window at 16 kHz.
        private const val PRE_ROLL_FRAMES = 12 // ~384 ms ring buffer preserves word onset before VAD triggers.
        private const val BARGE_IN_FRAMES = 6 // ~192 ms sustained speech guards against TTS speaker echo bursts.
        private const val BARGE_IN_MIN_RMS = 0.015f
        private const val MAX_UTTERANCE_MS = 20_000L
        private const val MIN_HANDS_FREE_UTTERANCE_MS = 600L
        private const val FRAME_CHANNEL_CAPACITY = 128
        private const val POST_TTS_ECHO_COOLDOWN_MS = 140L
        private const val TAIL_SILENCE_SAMPLES = 5120
    }

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null

    private var captureJob: Job? = null
    private var sttJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null

    private val running = AtomicBoolean(false)
    private val ttsPlaying = AtomicBoolean(false)
    private val bargeInEnabled = AtomicBoolean(true)
    private val manualFinishRequested = AtomicBoolean(false)
    private val muteCooldownUntilMs = AtomicLong(0L)

    val modelsReady: Boolean
        get() = vad != null && recognizer != null

    suspend fun loadModels(vadModelPath: String, sttDirPath: String): SpeechLoadResult =
        withContext(sttDispatcher) {
            releaseModelsInternal()

            val vadFile = File(vadModelPath)
            if (!vadFile.isFile) {
                return@withContext SpeechLoadResult(false, "Missing Silero VAD: $vadModelPath")
            }

            val sttDir = File(sttDirPath)
            val preprocess = findModel(sttDir, "preprocess")
                ?: return@withContext SpeechLoadResult(false, "Missing preprocess*.onnx in $sttDirPath")
            val encoder = findModel(sttDir, "encode.")
                ?: return@withContext SpeechLoadResult(false, "Missing encode*.onnx in $sttDirPath")
            val cachedDecoder = findModel(sttDir, "cached_decode")
                ?: return@withContext SpeechLoadResult(false, "Missing cached_decode*.onnx in $sttDirPath")
            val uncachedDecoder = findModel(sttDir, "uncached_decode")
                ?: return@withContext SpeechLoadResult(false, "Missing uncached_decode*.onnx in $sttDirPath")
            val tokens = File(sttDir, "tokens.txt").takeIf { it.isFile }
                ?: return@withContext SpeechLoadResult(false, "Missing tokens.txt in $sttDirPath")

            runCatching {
                vad = Vad(
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = vadFile.absolutePath,
                            threshold = 0.4f,
                            minSilenceDuration = 0.32f,
                            minSpeechDuration = 0.12f,
                            windowSize = FRAME_SAMPLES,
                            maxSpeechDuration = 15.0f,
                        ),
                        sampleRate = SAMPLE_RATE,
                        numThreads = 1,
                        provider = "cpu",
                        debug = false,
                    )
                )

                recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            moonshine = OfflineMoonshineModelConfig(
                                preprocessor = preprocess.absolutePath,
                                encoder = encoder.absolutePath,
                                cachedDecoder = cachedDecoder.absolutePath,
                                uncachedDecoder = uncachedDecoder.absolutePath,
                            ),
                            tokens = tokens.absolutePath,
                            numThreads = 2,
                            debug = false,
                            provider = "cpu",
                            modelType = "",
                        ),
                        decodingMethod = "greedy_search",
                    )
                )
            }.onFailure { err ->
                releaseModelsInternal()
                return@withContext SpeechLoadResult(false, "Speech model load failed: ${err.message}")
            }

            SpeechLoadResult(true, "Silero VAD + Moonshine loaded")
        }

    fun setTtsPlaying(playing: Boolean) {
        val wasPlaying = ttsPlaying.getAndSet(playing)
        if (wasPlaying && !playing && !bargeInEnabled.get()) {
            muteCooldownUntilMs.set(SystemClock.elapsedRealtime() + POST_TTS_ECHO_COOLDOWN_MS)
        }
    }

    fun setBargeInEnabled(enabled: Boolean) {
        bargeInEnabled.set(enabled)
    }

    fun requestManualFinish() {
        manualFinishRequested.set(true)
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, mode: CaptureMode): Boolean {
        if (!modelsReady) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (running.getAndSet(true)) return true

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuf * 2, FRAME_SAMPLES * 2 * 16)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            return false
        }

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        audioRecord = record
        manualFinishRequested.set(false)

        val frameChannel = Channel<FloatArray>(capacity = FRAME_CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        sttJob = scope.launch(sttDispatcher) {
            processAudioLoop(frameChannel, mode)
        }
        captureJob = scope.launch(captureDispatcher) {
            readMicLoop(record, frameChannel)
        }
        onStateChanged(if (mode == CaptureMode.MANUAL) ListeningState.SPEECH_ACTIVE else ListeningState.LISTENING)
        return true
    }

    suspend fun stop() {
        if (!running.getAndSet(false)) return
        captureJob?.cancelAndJoin()
        sttJob?.cancelAndJoin()
        captureJob = null
        sttJob = null
        releaseRecorder()
        onStateChanged(ListeningState.IDLE)
    }

    suspend fun release() {
        stop()
        withContext(sttDispatcher) {
            releaseModelsInternal()
        }
    }

    private fun readMicLoop(record: AudioRecord, frameChannel: Channel<FloatArray>) {
        val pcm = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            while (running.get()) {
                val read = record.read(pcm, 0, FRAME_SAMPLES)
                if (read > 0) {
                    val floats = FloatArray(read) { pcm[it] / 32768.0f }
                    frameChannel.trySend(floats)
                }
            }
        } finally {
            runCatching { record.stop() }
            releaseRecorder()
            frameChannel.close()
        }
    }

    private suspend fun processAudioLoop(frameChannel: Channel<FloatArray>, mode: CaptureMode) {
        val localVad = vad ?: return
        val localStt = recognizer ?: return
        localVad.reset()

        val preRoll = ArrayDeque<FloatArray>(PRE_ROLL_FRAMES + 1)
        val utterance = ArrayList<FloatArray>(MAX_UTTERANCE_MS.toInt() / 32)
        var capturingSpeech = (mode == CaptureMode.MANUAL)
        var speechStartedMs = if (capturingSpeech) SystemClock.elapsedRealtime() else 0L
        var bargeInStreak = 0
        var wasMuted = false

        try {
            for (frame in frameChannel) {
                if (!running.get() || !currentCoroutineIsActive()) break

                val muted = ttsPlaying.get() && !bargeInEnabled.get()
                val inCooldown = SystemClock.elapsedRealtime() < muteCooldownUntilMs.get()
                if (muted || inCooldown) {
                    if (!wasMuted) {
                        wasMuted = true
                        preRoll.clear()
                        utterance.clear()
                        localVad.reset()
                        bargeInStreak = 0
                    }
                    continue
                }
                if (wasMuted) {
                    wasMuted = false
                }

                pushPreRoll(preRoll, frame)
                localVad.acceptWaveform(frame)

                if (ttsPlaying.get()) {
                    if (localVad.isSpeechDetected() && frameRms(frame) >= BARGE_IN_MIN_RMS) {
                        bargeInStreak++
                    } else {
                        bargeInStreak = 0
                    }
                    if (bargeInStreak >= BARGE_IN_FRAMES) {
                        bargeInStreak = 0
                        ttsPlaying.set(false)
                        onBargeIn()
                        drainVadSegments(localVad)
                        utterance.clear()
                        utterance.addAll(preRoll)
                        preRoll.clear()
                        capturingSpeech = true
                        speechStartedMs = SystemClock.elapsedRealtime()
                        onStateChanged(ListeningState.SPEECH_ACTIVE)
                    }
                    continue
                }

                if (!capturingSpeech && localVad.isSpeechDetected()) {
                    capturingSpeech = true
                    speechStartedMs = SystemClock.elapsedRealtime()
                    utterance.clear()
                    utterance.addAll(preRoll)
                    preRoll.clear()
                    onStateChanged(ListeningState.SPEECH_ACTIVE)
                }

                if (capturingSpeech) {
                    utterance.add(frame)

                    val speechMs = SystemClock.elapsedRealtime() - speechStartedMs
                    val timedOut = speechMs >= MAX_UTTERANCE_MS
                    val vadEndpoint = mode == CaptureMode.HANDS_FREE && !localVad.empty() &&
                        speechMs >= MIN_HANDS_FREE_UTTERANCE_MS
                    val manualDone = mode == CaptureMode.MANUAL && manualFinishRequested.getAndSet(false)

                    if (vadEndpoint || manualDone || timedOut) {
                        val tEndpoint = SystemClock.elapsedRealtime()
                        drainVadSegments(localVad)
                        val finalText = recognizeUtterance(localStt, utterance)
                        val sttLatencyMs = SystemClock.elapsedRealtime() - tEndpoint

                        utterance.clear()
                        capturingSpeech = false

                        if (finalText.isNotEmpty()) {
                            onFinalTranscript(finalText, sttLatencyMs)
                        }

                        if (mode == CaptureMode.MANUAL) {
                            running.set(false)
                            audioRecord?.let { runCatching { it.stop() } }
                            onStateChanged(ListeningState.IDLE)
                            break
                        }
                        onStateChanged(ListeningState.LISTENING)
                    }
                } else {
                    drainVadSegments(localVad)
                    if (mode == CaptureMode.MANUAL && manualFinishRequested.getAndSet(false)) {
                        running.set(false)
                        audioRecord?.let { runCatching { it.stop() } }
                        onStateChanged(ListeningState.IDLE)
                        break
                    }
                }
            }
        } finally {
            utterance.clear()
        }
    }

    private fun recognizeUtterance(rec: OfflineRecognizer, frames: List<FloatArray>): String {
        val sampleCount = frames.sumOf { it.size } + TAIL_SILENCE_SAMPLES
        val samples = FloatArray(sampleCount)
        var offset = 0
        for (frame in frames) {
            frame.copyInto(samples, offset)
            offset += frame.size
        }
        var peak = 0.0f
        for (s in samples) {
            val v = abs(s)
            if (v > peak) peak = v
        }
        if (peak in 0.02f..0.35f) {
            val gain = minOf(3.0f, 0.6f / peak)
            for (i in samples.indices) samples[i] *= gain
        }

        val stream: OfflineStream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean {
        delay(0)
        return kotlinx.coroutines.currentCoroutineContext().isActive
    }

    private fun drainVadSegments(vad: Vad) {
        while (!vad.empty()) {
            vad.pop()
        }
    }

    private fun pushPreRoll(queue: ArrayDeque<FloatArray>, frame: FloatArray) {
        if (queue.size >= PRE_ROLL_FRAMES) {
            queue.removeFirst()
        }
        queue.addLast(frame)
    }

    private fun frameRms(samples: FloatArray): Float {
        var sum = 0.0f
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size)
    }

    private fun findModel(dir: File, prefix: String): File? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".onnx")
        }.orEmpty().toList()
        return files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull()
    }

    @Synchronized
    private fun releaseRecorder() {
        aec?.release()
        aec = null
        audioRecord?.release()
        audioRecord = null
    }

    private fun releaseModelsInternal() {
        vad?.release()
        vad = null
        recognizer?.release()
        recognizer = null
    }
}
