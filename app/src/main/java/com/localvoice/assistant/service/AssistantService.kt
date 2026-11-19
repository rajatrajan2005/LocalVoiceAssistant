package com.localvoice.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.localvoice.assistant.MainActivity
import com.localvoice.assistant.llm.LlamaEngine
import com.localvoice.assistant.tools.ConfirmationDecision
import com.localvoice.assistant.tools.ExecutionResult
import com.localvoice.assistant.tools.JsonReplyStreamer
import com.localvoice.assistant.tools.ModelOutput
import com.localvoice.assistant.tools.PendingConfirmation
import com.localvoice.assistant.tools.ToolAction
import com.localvoice.assistant.tools.ToolExecutor
import com.localvoice.assistant.tools.ToolGrammar
import com.localvoice.assistant.tools.ToolParser
import com.localvoice.assistant.voice.AudioPipeline
import com.localvoice.assistant.voice.CaptureMode
import com.localvoice.assistant.voice.ListeningState
import com.localvoice.assistant.voice.SpeechProgress
import com.localvoice.assistant.voice.TtsBackend
import com.localvoice.assistant.voice.TtsPlayer
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LlmModelOption(
    val name: String,
    val path: String,
)

data class AssistantUiState(
    val llmLoaded: Boolean = false,
    val llmName: String = "None",
    val availableModels: List<LlmModelOption> = emptyList(),
    val selectedModelPath: String? = null,
    val speechReady: Boolean = false,
    val ttsBackend: TtsBackend = TtsBackend.UNAVAILABLE,
    val listeningState: ListeningState = ListeningState.IDLE,
    val handsFree: Boolean = false,
    val bargeInEnabled: Boolean = true,
    val threads: Int = LlamaEngine.DEFAULT_THREADS,
    val isGenerating: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialTranscript: String = "",
    val lastUserText: String = "",
    val assistantText: String = "",
    val spokenWordIndex: Int = -1,
    val statusMessage: String = "Initializing...",
    val pendingConfirmation: PendingConfirmation? = null,
    val permissionRequest: String? = null,
    val tokensPerSec: Double = 0.0,
    val sttLatencyMs: Long = 0L,
    val ttfaMs: Long = 0L,
    val ramMb: Int = 0,
)

class AssistantService : Service() {
    companion object {
        private const val CHANNEL_ID = "local_voice_assistant"
        private const val NOTIFICATION_ID = 101
        private const val PREF_LLM_PATH = "llm_path"
        const val ACTION_START_HANDS_FREE = "com.localvoice.assistant.START_HANDS_FREE"
        private val ANSWER_WORD_REGEX = Regex("\\S+")
    }

    inner class LocalBinder : Binder() {
        fun getService(): AssistantService = this@AssistantService
    }

    private val binder = LocalBinder()

    // Separate single-thread dispatchers prevent STT, LLM, TTS, and capture from starving one another.
    private val captureExecutor = Executors.newSingleThreadExecutor { Thread(it, "audio-capture") }
    private val sttExecutor = Executors.newSingleThreadExecutor { Thread(it, "stt-worker") }
    private val llmExecutor = Executors.newSingleThreadExecutor { Thread(it, "llm-worker") }
    private val ttsExecutor = Executors.newSingleThreadExecutor { Thread(it, "tts-worker") }

    private val captureDispatcher = captureExecutor.asCoroutineDispatcher()
    private val sttDispatcher = sttExecutor.asCoroutineDispatcher()
    private val llmDispatcher = llmExecutor.asCoroutineDispatcher()
    private val ttsDispatcher = ttsExecutor.asCoroutineDispatcher()

    private val serviceScope = CoroutineScope(SupervisorJob())

    private val llamaEngine = LlamaEngine()
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var audioPipeline: AudioPipeline

    private lateinit var prefs: SharedPreferences
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var activeLlmJob: Job? = null
    private var pendingPermissionAction: ToolAction? = null
    private var pendingMicAfterPermission = false
    private var lastLlmPath: String? = null
    private var lastLlmUri: Uri? = null
    private var lastLlmLabel: String = "None"
    @Volatile
    private var speechProgressGen = -1L
    @Volatile
    private var lastSpeechSentence = -1
    @Volatile
    private var lastSpeechWord = -1
    @Volatile
    private var turnStartMs = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("localvoice", MODE_PRIVATE)
        lastLlmPath = prefs.getString(PREF_LLM_PATH, null)
        createNotificationChannel()
        toolExecutor = ToolExecutor(this)

        ttsPlayer = TtsPlayer(
            context = this,
            ttsDispatcher = ttsDispatcher,
            onSpeakingChanged = { speaking ->
                audioPipeline.setTtsPlaying(speaking)
                if (!speaking) {
                    speechProgressGen = -1L
                    lastSpeechSentence = -1
                    lastSpeechWord = -1
                }
                _uiState.update {
                    it.copy(
                        isSpeaking = speaking,
                        spokenWordIndex = if (speaking) it.spokenWordIndex else -1,
                    )
                }
            },
            onFirstAudio = {
                val started = turnStartMs
                if (started > 0L) {
                    val ttfa = SystemClock.elapsedRealtime() - started
                    _uiState.update { it.copy(ttfaMs = ttfa) }
                }
            },
            onSpeechProgress = ::onSpeechProgress,
        )

        audioPipeline = AudioPipeline(
            context = this,
            captureDispatcher = captureDispatcher,
            sttDispatcher = sttDispatcher,
            onStateChanged = { state ->
                _uiState.update {
                    it.copy(
                        listeningState = state,
                        partialTranscript = if (state == ListeningState.IDLE) "" else it.partialTranscript,
                    )
                }
                if (state == ListeningState.IDLE && !_uiState.value.handsFree) {
                    serviceScope.launch { audioPipeline.stop() }
                }
            },
            onPartialTranscript = { partial ->
                _uiState.update { it.copy(partialTranscript = partial) }
            },
            onFinalTranscript = { text, sttMs ->
                handleUserTranscript(text, sttMs)
            },
            onBargeIn = {
                handleBargeIn()
            },
        )

        reloadAllModels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_HANDS_FREE) {
            promoteToMicrophoneForeground()
            startHandsFree()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Check exact CRITICAL/COMPLETE levels; checking `>= CRITICAL` would also catch UI_HIDDEN (20) on open_app.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            unloadLlm("Unloaded LLM under system memory pressure.")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        llamaEngine.unload()
        kotlinx.coroutines.runBlocking {
            audioPipeline.release()
            ttsPlayer.release()
        }
        captureExecutor.shutdownNow()
        sttExecutor.shutdownNow()
        llmExecutor.shutdownNow()
        ttsExecutor.shutdownNow()
    }

    fun refreshModelList() {
        val dir = File(resolveModelsRoot(), "llm")
        val models = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
            .map { LlmModelOption(it.name, it.absolutePath) }
        val selected = lastLlmPath?.takeIf { path -> models.any { it.path == path } }
            ?: findDefaultGguf(dir)?.absolutePath
        _uiState.update { it.copy(availableModels = models, selectedModelPath = selected) }
    }

    fun selectModel(path: String) {
        val name = File(path).name
        prefs.edit().putString(PREF_LLM_PATH, path).apply()
        lastLlmUri = null
        _uiState.update { it.copy(selectedModelPath = path, statusMessage = "Switching to $name...") }
        loadLlmFromPath(path, name)
    }

    fun reloadAllModels() {
        refreshModelList()
        val root = resolveModelsRoot()
        val vadPath = File(root, "vad/silero_vad.onnx").absolutePath
        val sttDir = File(root, "stt").absolutePath
        val ttsDir = File(root, "tts").absolutePath

        serviceScope.launch {
            val speechResult = audioPipeline.loadModels(vadPath, sttDir)
            val ttsBackend = ttsPlayer.loadModel(ttsDir)
            _uiState.update {
                it.copy(
                    speechReady = speechResult.ok,
                    ttsBackend = ttsBackend,
                    statusMessage = if (speechResult.ok) "Speech ready" else speechResult.message,
                )
            }
            refreshRamMetric()
        }

        val uri = lastLlmUri
        if (uri != null) {
            loadLlmFromUri(uri)
            return
        }

        val targetPath = lastLlmPath ?: findDefaultGguf(File(root, "llm"))?.absolutePath
        if (targetPath != null) {
            loadLlmFromPath(targetPath, File(targetPath).name)
        } else {
            _uiState.update {
                it.copy(
                    llmLoaded = false,
                    statusMessage = "No GGUF found in ${File(root, "llm").absolutePath}. Pick one via SAF.",
                )
            }
        }
    }

    fun loadLlmFromUri(uri: Uri) {
        serviceScope.launch(llmDispatcher) {
            val name = queryDisplayName(uri)
            _uiState.update { it.copy(statusMessage = "Loading $name...") }
            val threads = _uiState.value.threads

            // Try zero-copy mmap via /proc/self/fd first; copy to app storage only if provider is non-seekable.
            val loadedDirect = runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    llamaEngine.load("/proc/self/fd/${pfd.fd}", threads)
                } ?: false
            }.getOrDefault(false)

            if (loadedDirect) {
                lastLlmUri = uri
                lastLlmPath = null
                lastLlmLabel = name
                finishLlmLoad(true, name)
                return@launch
            }

            val dest = File(resolveModelsRoot(), "llm/$name").apply { parentFile?.mkdirs() }
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.isFile && dest.length() > 0L
            }.getOrDefault(false)

            if (!copied) {
                _uiState.update { it.copy(llmLoaded = false, statusMessage = "Unable to read SAF file $name") }
                return@launch
            }

            lastLlmUri = null
            lastLlmPath = dest.absolutePath
            lastLlmLabel = name
            val ok = llamaEngine.load(dest.absolutePath, threads)
            finishLlmLoad(ok, name)
        }
    }

    private fun loadLlmFromPath(path: String, label: String) {
        serviceScope.launch(llmDispatcher) {
            _uiState.update { it.copy(statusMessage = "Loading $label...") }
            prefs.edit().putString(PREF_LLM_PATH, path).apply()
            lastLlmUri = null
            lastLlmPath = path
            lastLlmLabel = label
            _uiState.update { it.copy(selectedModelPath = path) }
            val ok = llamaEngine.load(path, _uiState.value.threads)
            finishLlmLoad(ok, label)
        }
    }

    private fun finishLlmLoad(ok: Boolean, label: String) {
        if (ok) {
            llamaEngine.warmPrefix(ToolGrammar.SYSTEM_PROMPT)
        }
        _uiState.update {
            it.copy(
                llmLoaded = ok,
                llmName = if (ok) label else "None",
                statusMessage = if (ok) "Ready ($label)" else "Failed to load $label",
            )
        }
        refreshRamMetric()
    }

    fun unloadLlm(reason: String = "Model unloaded") {
        activeLlmJob?.cancel()
        ttsPlayer.stop()
        serviceScope.launch(llmDispatcher) {
            llamaEngine.unload()
            _uiState.update {
                it.copy(
                    llmLoaded = false,
                    isGenerating = false,
                    statusMessage = reason,
                )
            }
            refreshRamMetric()
        }
    }

    fun setThreads(threads: Int) {
        val clamped = threads.coerceIn(1, LlamaEngine.MAX_THREADS)
        llamaEngine.setThreads(clamped)
        _uiState.update { it.copy(threads = clamped) }
    }

    fun setBargeIn(enabled: Boolean) {
        audioPipeline.setBargeInEnabled(enabled)
        _uiState.update { it.copy(bargeInEnabled = enabled) }
    }

    private fun onSpeechProgress(progress: SpeechProgress) {
        if (progress.generation != speechProgressGen) {
            speechProgressGen = progress.generation
            lastSpeechSentence = -1
            lastSpeechWord = -1
            _uiState.update { it.copy(spokenWordIndex = -1) }
        }
        if (progress.sentenceIndex < lastSpeechSentence) return

        val words = ANSWER_WORD_REGEX.findAll(progress.sentenceText).toList()
        if (words.isEmpty()) return
        val localIndex = (progress.progress * words.size).toInt().coerceIn(0, words.lastIndex)
        val candidate = progress.wordsBefore + localIndex
        val monotonic = if (progress.sentenceIndex == lastSpeechSentence) {
            maxOf(candidate, lastSpeechWord)
        } else {
            candidate
        }
        val visibleWordCount = ANSWER_WORD_REGEX.findAll(_uiState.value.assistantText).count()
        val safeIndex = monotonic.coerceAtMost(maxOf(0, visibleWordCount - 1))
        lastSpeechSentence = progress.sentenceIndex
        lastSpeechWord = safeIndex
        _uiState.update { state ->
            if (state.spokenWordIndex == safeIndex) state else state.copy(spokenWordIndex = safeIndex)
        }
    }

    fun setHandsFree(enabled: Boolean) {
        _uiState.update { it.copy(handsFree = enabled) }
        serviceScope.launch {
            audioPipeline.stop()
            if (enabled) {
                startCaptureWithPermissionCheck(CaptureMode.HANDS_FREE)
            }
        }
    }

    fun startHandsFree() {
        serviceScope.launch {
            var waitedMs = 0
            while (!audioPipeline.modelsReady && waitedMs < 10_000) {
                delay(100)
                waitedMs += 100
            }
            setHandsFree(true)
        }
    }

    fun onMicButtonPressed() {
        val state = _uiState.value
        if (state.isGenerating || state.isSpeaking) {
            handleBargeIn()
            if (!state.handsFree) return
        }

        if (state.handsFree) {
            setHandsFree(false)
            return
        }

        if (state.listeningState != ListeningState.IDLE) {
            audioPipeline.requestManualFinish()
            return
        }

        serviceScope.launch {
            startCaptureWithPermissionCheck(CaptureMode.MANUAL)
        }
    }

    fun confirmPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null) }
        turnStartMs = SystemClock.elapsedRealtime()
        val result = toolExecutor.executeConfirmed(pending)
        if (result is ExecutionResult.Spoken) {
            _uiState.update { it.copy(assistantText = result.message) }
            ttsPlayer.speakImmediate(serviceScope, result.message)
        }
    }

    fun cancelPendingAction() {
        if (_uiState.value.pendingConfirmation == null) return
        _uiState.update { it.copy(pendingConfirmation = null, assistantText = "Cancelled.") }
        turnStartMs = SystemClock.elapsedRealtime()
        ttsPlayer.speakImmediate(serviceScope, "Cancelled.")
    }

    fun onPermissionResult(permission: String, granted: Boolean) {
        _uiState.update { it.copy(permissionRequest = null) }
        if (permission == Manifest.permission.RECORD_AUDIO) {
            if (granted && pendingMicAfterPermission) {
                pendingMicAfterPermission = false
                val mode = if (_uiState.value.handsFree) CaptureMode.HANDS_FREE else CaptureMode.MANUAL
                serviceScope.launch { startCaptureWithPermissionCheck(mode) }
            } else {
                pendingMicAfterPermission = false
                _uiState.update { it.copy(handsFree = false, statusMessage = "Microphone permission denied") }
            }
            return
        }

        if (permission == Manifest.permission.READ_CONTACTS) {
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (granted && action != null) {
                turnStartMs = SystemClock.elapsedRealtime()
                handleExecutionResult(toolExecutor.execute(action))
            } else {
                _uiState.update { it.copy(assistantText = "Contacts permission denied.") }
                ttsPlayer.speakImmediate(serviceScope, "Contacts permission denied.")
            }
        }
    }

    fun clearPermissionRequest() {
        _uiState.update { it.copy(permissionRequest = null) }
    }

    private fun startCaptureWithPermissionCheck(mode: CaptureMode) {
        if (!audioPipeline.modelsReady) {
            _uiState.update { it.copy(handsFree = false, statusMessage = "Speech models not loaded") }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingMicAfterPermission = true
            _uiState.update { it.copy(permissionRequest = Manifest.permission.RECORD_AUDIO) }
            return
        }
        promoteToMicrophoneForeground()
        if (!audioPipeline.start(serviceScope, mode)) {
            _uiState.update { it.copy(handsFree = false, statusMessage = "Failed to open AudioRecord") }
        }
    }

    private fun handleBargeIn() {
        ttsPlayer.stop()
        llamaEngine.cancel()
        activeLlmJob?.cancel()
        _uiState.update { it.copy(isGenerating = false, isSpeaking = false) }
    }

    private fun handleUserTranscript(text: String, sttLatencyMs: Long) {
        turnStartMs = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                lastUserText = text,
                partialTranscript = "",
                sttLatencyMs = sttLatencyMs,
            )
        }

        val pending = _uiState.value.pendingConfirmation
        if (pending != null) {
            when (ToolExecutor.parseVoiceConfirmation(text)) {
                ConfirmationDecision.CONFIRM -> {
                    confirmPendingAction()
                    return
                }
                ConfirmationDecision.CANCEL -> {
                    cancelPendingAction()
                    return
                }
                ConfirmationDecision.UNRELATED -> {
                    _uiState.update { it.copy(pendingConfirmation = null) }
                }
            }
        }

        if (!llamaEngine.isLoaded) {
            val msg = "No LLM model loaded."
            _uiState.update { it.copy(assistantText = msg) }
            ttsPlayer.speakImmediate(serviceScope, msg)
            return
        }

        activeLlmJob?.cancel()
        activeLlmJob = serviceScope.launch(llmDispatcher) {
            _uiState.update { it.copy(isGenerating = true, assistantText = "", spokenWordIndex = -1) }
            val streamer = JsonReplyStreamer()
            val streamedReply = StringBuilder()
            speechProgressGen = -1L
            lastSpeechSentence = -1
            lastSpeechWord = -1
            ttsPlayer.stop()

            val result = llamaEngine.generate(
                systemPrompt = ToolGrammar.SYSTEM_PROMPT,
                userPrompt = text,
                grammar = null,
                maxTokens = 160,
            ) { token ->
                val delta = streamer.feed(token)
                if (delta.isNotEmpty()) {
                    streamedReply.append(delta)
                    _uiState.update { it.copy(assistantText = streamedReply.toString()) }
                }
            }

            _uiState.update { it.copy(isGenerating = false, tokensPerSec = result.tokensPerSec) }
            refreshRamMetric()

            if (result.cancelled) {
                ttsPlayer.stop()
                return@launch
            }

            when (val parsed = ToolParser.parse(result.text, text)) {
                is ModelOutput.Reply -> {
                    val usedFallback = parsed.text.isEmpty()
                    val replyText = if (usedFallback) "Okay." else parsed.text
                    _uiState.update { it.copy(assistantText = replyText) }
                    ttsPlayer.stop()
                    ttsPlayer.speakImmediate(serviceScope, replyText)
                }
                is ModelOutput.Call -> {
                    ttsPlayer.stop()
                    handleExecutionResult(toolExecutor.execute(parsed.action))
                }
                is ModelOutput.Invalid -> {
                    ttsPlayer.stop()
                    _uiState.update { it.copy(assistantText = parsed.reason) }
                    ttsPlayer.speakImmediate(serviceScope, parsed.reason)
                }
            }
        }
    }

    private fun handleExecutionResult(result: ExecutionResult) {
        when (result) {
            is ExecutionResult.Spoken -> {
                _uiState.update { it.copy(assistantText = result.message) }
                ttsPlayer.speakImmediate(serviceScope, result.message)
            }
            is ExecutionResult.NeedsConfirmation -> {
                _uiState.update {
                    it.copy(
                        pendingConfirmation = result.pending,
                        assistantText = result.pending.promptText,
                    )
                }
                ttsPlayer.speakImmediate(serviceScope, result.pending.promptText)
            }
            is ExecutionResult.NeedsPermission -> {
                pendingPermissionAction = result.action
                val shortName = result.permission.substringAfterLast('.')
                _uiState.update {
                    it.copy(
                        permissionRequest = result.permission,
                        assistantText = "Requesting $shortName permission...",
                    )
                }
            }
        }
    }

    private fun promoteToMicrophoneForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalVoice Active")
            .setContentText("Offline voice assistant running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Offline Voice Assistant",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun resolveModelsRoot(): File {
        val ext = getExternalFilesDir(null)?.let { File(it, "models") }
        if (ext != null && ext.exists()) return ext
        val internal = File(filesDir, "models")
        if (internal.exists()) return internal
        return (ext ?: internal).apply { mkdirs() }
    }

    private fun findDefaultGguf(dir: File): File? {
        if (!dir.isDirectory) return null
        val ggufs = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        return ggufs.firstOrNull { it.name.contains("1.5b", ignoreCase = true) } ?: ggufs.firstOrNull()
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                return c.getString(idx) ?: "model.gguf"
            }
        }
        return "model.gguf"
    }

    fun refreshRamMetric() {
        // VmRSS counts actual resident memory including mmap'd GGUF weights and KV buffers.
        val vmRssKb = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.replace(Regex("[^0-9]"), "")
                    ?.toIntOrNull()
            }
        }.getOrNull()

        val ramMb = if (vmRssKb != null) {
            vmRssKb / 1024
        } else {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss / 1024
        }
        _uiState.update { it.copy(ramMb = ramMb) }
    }
}