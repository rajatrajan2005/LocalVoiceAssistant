package com.localvoice.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TtsBackend { PIPER_OFFLINE, ANDROID_OFFLINE, UNAVAILABLE }

private val SPEECH_WORD_REGEX = Regex("\\S+")

private fun String.wordCount(): Int = SPEECH_WORD_REGEX.findAll(this).count()

data class SpeechProgress(
    val generation: Long,
    val sentenceIndex: Int,
    val sentenceText: String,
    val wordsBefore: Int,
    val progress: Float,
    val playedFrames: Long,
    val totalFrames: Long,
)

private data class SentenceTimeline(
    val index: Int,
    val text: String,
    val wordsBefore: Int,
    val startFrame: Long,
    val endFrame: Long,
)

private data class ProgressState(
    val generation: Long,
    val startHead: Long,
    val sentences: List<SentenceTimeline>,
)

class TtsPlayer(
    private val context: Context,
    private val ttsDispatcher: CoroutineDispatcher,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onFirstAudio: () -> Unit,
    private val onSpeechProgress: (SpeechProgress) -> Unit,
) {
    private sealed interface QueueItem {
        val gen: Long

        data class Speak(override val gen: Long, val text: String) : QueueItem
        data class EndUtterance(override val gen: Long) : QueueItem
    }

    private val queue = Channel<QueueItem>(Channel.UNLIMITED)
    private var workerJob: Job? = null

    private var offlineTts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0

    private var androidTts: TextToSpeech? = null
    @Volatile
    private var androidTtsReady = false
    @Volatile
    private var activeAndroidDeferred: CompletableDeferred<Unit>? = null

    private val sentenceBuffer = StringBuilder()
    private val bufferLock = Any()
    private val trackControlLock = Any() // Protects AudioTrack play/pause/flush without holding across blocking writes.

    private val generationId = AtomicLong(0L)
    private val speaking = AtomicBoolean(false)
    private val firstAudioEmitted = AtomicBoolean(false)
    private var activeGen = 0L
    @Volatile
    private var progressState: ProgressState? = null

    val backend: TtsBackend
        get() = when {
            offlineTts != null -> TtsBackend.PIPER_OFFLINE
            androidTtsReady -> TtsBackend.ANDROID_OFFLINE
            else -> TtsBackend.UNAVAILABLE
        }

    suspend fun loadModel(ttsDirPath: String): TtsBackend = withContext(ttsDispatcher) {
        releaseInternal()
        val dir = File(ttsDirPath)
        val modelFile = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.firstOrNull()
        val tokensFile = File(dir, "tokens.txt").takeIf { it.isFile }
        val espeakDir = File(dir, "espeak-ng-data").takeIf { it.isDirectory }
        val lexiconFile = File(dir, "lexicon.txt").takeIf { it.isFile }

        if (modelFile != null && tokensFile != null) {
            val loaded = runCatching {
                OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = modelFile.absolutePath,
                                lexicon = lexiconFile?.absolutePath.orEmpty(),
                                tokens = tokensFile.absolutePath,
                                dataDir = espeakDir?.absolutePath.orEmpty(),
                            ),
                            numThreads = 4,
                            debug = false,
                            provider = "cpu",
                        ),
                        maxNumSentences = 1,
                    )
                )
            }.getOrNull()

            if (loaded != null) {
                offlineTts = loaded
                return@withContext TtsBackend.PIPER_OFFLINE
            }
        }

        if (initAndroidTtsFallback()) TtsBackend.ANDROID_OFFLINE else TtsBackend.UNAVAILABLE
    }

    fun startWorker(scope: CoroutineScope) {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch(ttsDispatcher) {
            var currentPlayGen = -1L
            var totalFramesWritten = 0L
            var utteranceStartHead = 0L
            var utteranceHeadCaptured = false
            var sentenceIndex = 0

            val progressTicker = launch(Dispatchers.Default) {
                while (isActive) {
                    delay(25)
                    val state = progressState ?: continue
                    val track = audioTrack ?: continue
                    if (state.generation != generationId.get()) continue

                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    val played = (head - state.startHead) and 0xFFFFFFFFL
                    val total = state.sentences.lastOrNull()?.endFrame ?: 0L
                    if (total <= 0L) continue

                    val sentence = state.sentences.firstOrNull { played < it.endFrame }
                        ?: state.sentences.last()
                    val span = (sentence.endFrame - sentence.startFrame).coerceAtLeast(1L)
                    val current = (played - sentence.startFrame).coerceIn(0L, span)
                    onSpeechProgress(
                        SpeechProgress(
                            generation = state.generation,
                            sentenceIndex = sentence.index,
                            sentenceText = sentence.text,
                            wordsBefore = sentence.wordsBefore,
                            progress = current.toFloat() / span.toFloat(),
                            playedFrames = played,
                            totalFrames = total,
                        )
                    )
                }
            }

            try {
                for (item in queue) {
                    if (item.gen != generationId.get()) continue

                    when (item) {
                        is QueueItem.Speak -> {
                            if (item.gen != currentPlayGen) {
                                currentPlayGen = item.gen
                                totalFramesWritten = 0L
                                sentenceIndex = 0
                                utteranceStartHead = 0L
                                utteranceHeadCaptured = false
                                progressState = ProgressState(item.gen, utteranceStartHead, emptyList())
                                setSpeaking(true)
                            }
                            val index = sentenceIndex++
                            val startFrame = totalFramesWritten
                            val timelines = progressState?.sentences.orEmpty()
                            val wordsBefore = timelines.sumOf { it.text.wordCount() }
                            progressState = ProgressState(
                                item.gen,
                                utteranceStartHead,
                                timelines + SentenceTimeline(
                                    index = index,
                                    text = item.text,
                                    wordsBefore = wordsBefore,
                                    startFrame = startFrame,
                                    endFrame = startFrame + 1L,
                                ),
                            )
                            val written = synthesizeAndPlay(
                                text = item.text,
                                gen = item.gen,
                                onFirstFrame = { head ->
                                    if (!utteranceHeadCaptured) {
                                        utteranceHeadCaptured = true
                                        utteranceStartHead = head
                                        progressState = progressState?.copy(startHead = head)
                                    }
                                },
                                onFramesWritten = { sentenceFrames ->
                                    progressState = progressState?.let { state ->
                                        state.copy(
                                            sentences = state.sentences.mapIndexed { i, timeline ->
                                                if (i == index) {
                                                    timeline.copy(endFrame = startFrame + maxOf(1L, sentenceFrames))
                                                } else {
                                                    timeline
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                            totalFramesWritten += written
                        }

                        is QueueItem.EndUtterance -> {
                            if (item.gen == currentPlayGen) {
                                awaitAudioDrain(item.gen, utteranceStartHead, totalFramesWritten)
                            }
                            if (item.gen == generationId.get()) {
                                setSpeaking(false)
                            }
                            progressState = null
                        }
                    }
                }
            } finally {
                progressTicker.cancel()
            }
        }
    }

    fun beginStream(scope: CoroutineScope) {
        startWorker(scope)
        val gen = generationId.incrementAndGet()
        synchronized(bufferLock) {
            activeGen = gen
            sentenceBuffer.setLength(0)
        }
        firstAudioEmitted.set(false)
    }

    fun appendText(delta: String) {
        if (delta.isEmpty()) return
        val ready = mutableListOf<String>()
        val gen: Long
        synchronized(bufferLock) {
            gen = activeGen
            sentenceBuffer.append(delta)
            extractReadySentences(ready)
        }
        ready.forEach { sentence ->
            queue.trySend(QueueItem.Speak(gen, sentence))
        }
    }

    fun finishStream() {
        val tail: String
        val gen: Long
        synchronized(bufferLock) {
            gen = activeGen
            tail = sentenceBuffer.toString().trim()
            sentenceBuffer.setLength(0)
        }
        if (tail.isNotEmpty()) {
            queue.trySend(QueueItem.Speak(gen, tail))
        }
        queue.trySend(QueueItem.EndUtterance(gen))
    }

    fun speakImmediate(scope: CoroutineScope, text: String) {
        beginStream(scope)
        appendText(text)
        finishStream()
    }

    fun stop() {
        generationId.incrementAndGet()
        progressState = null
        synchronized(bufferLock) {
            sentenceBuffer.setLength(0)
        }
        synchronized(trackControlLock) {
            runCatching {
                audioTrack?.pause()
                audioTrack?.flush()
            }
        }
        runCatching { androidTts?.stop() }
        activeAndroidDeferred?.complete(Unit)
        activeAndroidDeferred = null
        setSpeaking(false)
    }

    suspend fun release() {
        stop()
        queue.close()
        workerJob?.cancelAndJoin()
        workerJob = null
        withContext(ttsDispatcher) {
            releaseInternal()
        }
    }

    private fun extractReadySentences(out: MutableList<String>) {
        while (true) {
            val splitAt = findSentenceBoundary(sentenceBuffer)
            if (splitAt < 0) break
            val sentence = sentenceBuffer.substring(0, splitAt + 1).trim()
            sentenceBuffer.delete(0, splitAt + 1)
            if (sentence.isNotEmpty()) {
                out.add(sentence)
            }
        }
    }

    private fun findSentenceBoundary(sb: CharSequence): Int {
        val len = sb.length
        for (i in 0 until len) {
            val c = sb[i]
            if (c == '\n' && i >= 3) return i
            if ((c == '.' || c == '!' || c == '?') && i >= 3) {
                val followedBySpace = i + 1 < len && sb[i + 1].isWhitespace()
                val isAbbrev = c == '.' && i >= 2 && sb[i - 2] == '.'
                if (followedBySpace && !isAbbrev) return i
            }
        }
        return -1
    }

    private suspend fun synthesizeAndPlay(
        text: String,
        gen: Long,
        onFirstFrame: (Long) -> Unit,
        onFramesWritten: (Long) -> Unit,
    ): Long {
        val piper = offlineTts
        if (piper != null) {
            return playWithPiper(piper, text, gen, onFirstFrame, onFramesWritten)
        }
        if (androidTtsReady) {
            playWithAndroidTts(text, gen)
        }
        return 0L
    }

    private fun playWithPiper(
        piper: OfflineTts,
        text: String,
        gen: Long,
        onFirstFrame: (Long) -> Unit,
        onFramesWritten: (Long) -> Unit,
    ): Long {
        val rate = piper.sampleRate()
        val track = ensureAudioTrack(rate)
        var framesWritten = 0L
        var startHeadCaptured = false

        fun onSamples(samples: FloatArray): Int {
            if (gen != generationId.get()) return 0
            if (samples.isEmpty()) return 1

            var offset = 0
            while (offset < samples.size) {
                if (gen != generationId.get()) return 0
                synchronized(trackControlLock) {
                    if (gen != generationId.get()) return 0
                    if (!startHeadCaptured) {
                        onFirstFrame(track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                        startHeadCaptured = true
                    }
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                }
                if (firstAudioEmitted.compareAndSet(false, true)) {
                    onFirstAudio()
                }
                // Write in ~90 ms slices so stop() unblocks AudioTrack nearly immediately on barge-in.
                val slice = minOf(2048, samples.size - offset)
                val wrote = track.write(samples, offset, slice, AudioTrack.WRITE_BLOCKING)
                if (wrote <= 0) break
                offset += wrote
                framesWritten += wrote
                onFramesWritten(framesWritten)
            }
            return if (gen == generationId.get()) 1 else 0
        }

        piper.generateWithCallback(
            text = text,
            sid = 0,
            speed = 1.0f,
            callback = TtsCallback { samples -> onSamples(samples) },
        )
        return framesWritten
    }

    private suspend fun playWithAndroidTts(text: String, gen: Long) {
        val tts = androidTts ?: return
        if (gen != generationId.get()) return
        val done = CompletableDeferred<Unit>()
        activeAndroidDeferred = done
        val utteranceId = "utt_${gen}_${System.nanoTime()}"
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        done.await()
        if (activeAndroidDeferred === done) {
            activeAndroidDeferred = null
        }
    }

    private suspend fun awaitAudioDrain(gen: Long, startHead: Long, totalFramesWritten: Long) {
        val track = audioTrack ?: return
        if (totalFramesWritten <= 0L) return
        val deadline = System.currentTimeMillis() + 3000L
        while (gen == generationId.get() && System.currentTimeMillis() < deadline) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val played = (head - startHead) and 0xFFFFFFFFL
            if (played >= totalFramesWritten) break
            delay(25)
        }
    }

    private fun ensureAudioTrack(sampleRate: Int): AudioTrack {
        val existing = audioTrack
        if (existing != null && trackSampleRate == sampleRate && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }
        existing?.release()
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferBytes = maxOf(minBytes, sampleRate * 4 / 5) // ~200 ms float buffer.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        trackSampleRate = sampleRate
        return track
    }

    private suspend fun initAndroidTtsFallback(): Boolean {
        val ready = CompletableDeferred<Boolean>()
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
        val ok = ready.await()
        if (!ok) {
            instance.shutdown()
            return false
        }

        val offlineVoice = instance.voices?.firstOrNull {
            it.locale.language == Locale.US.language && !it.isNetworkConnectionRequired
        } ?: instance.voices?.firstOrNull { !it.isNetworkConnectionRequired }
        if (offlineVoice != null) {
            instance.voice = offlineVoice
        }

        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (firstAudioEmitted.compareAndSet(false, true)) {
                    onFirstAudio()
                }
            }

            override fun onDone(utteranceId: String?) {
                activeAndroidDeferred?.complete(Unit)
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) {
                activeAndroidDeferred?.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                activeAndroidDeferred?.complete(Unit)
            }
        })

        androidTts = instance
        androidTtsReady = true
        return true
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking.getAndSet(value) != value) {
            onSpeakingChanged(value)
        }
    }

    private fun releaseInternal() {
        synchronized(trackControlLock) {
            audioTrack?.release()
            audioTrack = null
            trackSampleRate = 0
        }
        offlineTts?.release()
        offlineTts = null
        androidTtsReady = false
        androidTts?.shutdown()
        androidTts = null
    }
}