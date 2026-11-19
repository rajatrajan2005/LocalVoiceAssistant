package com.localvoice.optimizationdemo

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Profile(
    val name: String,
    val weightsGb: Float,
    val speedMultiplier: Float,
    val kvMbPerK: Float,
    val prefillTokens: Int,
)

private val PROFILES = listOf(
    Profile("Qwen2.5 1.5B Q4_K_M", 1.04f, 1.00f, 32f, 260),
    Profile("Qwen2.5 1.5B Q4_K_S", 0.96f, 1.04f, 32f, 260),
    Profile("Qwen2.5 1.5B Q4_0", 0.85f, 1.11f, 32f, 260),
    Profile("Qwen2.5 1.5B Q8_0", 1.90f, 0.72f, 32f, 260),
    Profile("Qwen3 0.6B Q4_K_M", 0.48f, 2.20f, 18f, 220),
    Profile("LFM2.5 1.2B Q4_0", 0.70f, 1.80f, 24f, 240),
)

private val THREAD_FACTOR = mapOf(
    1 to 0.62f,
    2 to 0.85f,
    3 to 0.96f,
    4 to 1.00f,
    5 to 0.94f,
    6 to 0.88f,
    7 to 0.85f,
    8 to 0.82f,
)

private val ANSWER_REGEX = Regex("\\S+")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LabScreen()
                }
            }
        }
    }
}

@Composable
private fun LabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsStatus by remember { mutableStateOf("Starting offline TTS...") }
    var demoText by remember {
        mutableStateOf("The assistant reads this answer aloud and reveals the text at the same pace. You can change the speaking rate and watch the words appear in sync.")
    }
    var wordsPerMinute by remember { mutableFloatStateOf(140f) }
    var visibleWords by remember { mutableIntStateOf(0) }
    var speaking by remember { mutableStateOf(false) }
    var revealJob by remember { mutableStateOf<Job?>(null) }
    var profileIndex by remember { mutableIntStateOf(0) }
    var threads by remember { mutableIntStateOf(4) }
    var contextSize by remember { mutableIntStateOf(1024) }
    var quantizedKv by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = instance?.setLanguage(Locale.US)
                ttsStatus = if (result == TextToSpeech.LANG_AVAILABLE) {
                    "Offline TTS ready"
                } else {
                    "TTS ready, US voice missing"
                }
                instance?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        speaking = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        speaking = false
                    }
                })
                tts = instance
            } else {
                ttsStatus = "TTS unavailable"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.shutdown()
            revealJob?.cancel()
        }
    }

    val memory = remember {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        "${info.availMem / 1024 / 1024} MB free of ${info.totalMem / 1024 / 1024} MB"
    }
    val thermal = remember {
        val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Thermal status ${manager.currentThermalStatus}"
        } else {
            "Thermal API unavailable"
        }
    }
    val abi = remember { Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LocalVoice Optimization Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("A50 profile · ARMv8-A baseline · offline only", style = MaterialTheme.typography.bodySmall)
        Text("Device: $memory · $thermal · $abi", style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { tab = 0 }, enabled = tab != 0) { Text("Sync") }
            OutlinedButton(onClick = { tab = 1 }, enabled = tab != 1) { Text("Estimator") }
            OutlinedButton(onClick = { tab = 2 }, enabled = tab != 2) { Text("Roadmap") }
        }

        Divider()

        when (tab) {
            0 -> SyncTab(
                demoText = demoText,
                onTextChange = { demoText = it },
                wordsPerMinute = wordsPerMinute,
                onRateChange = { wordsPerMinute = it },
                visibleWords = visibleWords,
                speaking = speaking,
                ttsStatus = ttsStatus,
                onPlay = {
                    val words = ANSWER_REGEX.findAll(demoText).toList()
                    if (words.isEmpty()) return@SyncTab
                    revealJob?.cancel()
                    visibleWords = 0
                    speaking = true
                    tts?.setSpeechRate(wordsPerMinute / 150f)
                    tts?.speak(demoText, TextToSpeech.QUEUE_FLUSH, null, "sync-demo")
                    revealJob = scope.launch {
                        val perWord = (60000f / wordsPerMinute).toLong()
                        delay(120)
                        words.indices.forEach { index ->
                            visibleWords = index + 1
                            delay(perWord)
                        }
                        visibleWords = words.size
                        speaking = false
                    }
                },
                onStop = {
                    revealJob?.cancel()
                    tts?.stop()
                    speaking = false
                    visibleWords = 0
                },
            )

            1 -> EstimatorTab(
                profileIndex = profileIndex,
                onProfileChange = { profileIndex = it },
                threads = threads,
                onThreadsChange = { threads = it },
                contextSize = contextSize,
                onContextChange = { contextSize = it },
                quantizedKv = quantizedKv,
                onKvChange = { quantizedKv = it },
            )

            else -> RoadmapTab()
        }
    }
}

@Composable
private fun SyncTab(
    demoText: String,
    onTextChange: (String) -> Unit,
    wordsPerMinute: Float,
    onRateChange: (Float) -> Unit,
    visibleWords: Int,
    speaking: Boolean,
    ttsStatus: String,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val words = remember(demoText) { ANSWER_REGEX.findAll(demoText).toList() }
    val shown = remember(words, visibleWords, speaking) {
        if (!speaking) {
            demoText
        } else if (visibleWords <= 0) {
            ""
        } else {
            val end = words[minOf(visibleWords, words.size) - 1].range.last + 1
            demoText.substring(0, end)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sync demo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(ttsStatus, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = demoText,
            onValueChange = onTextChange,
            label = { Text("Answer text") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
        )
        Text("Speaking rate: ${wordsPerMinute.toInt()} WPM")
        Slider(
            value = wordsPerMinute,
            onValueChange = onRateChange,
            valueRange = 90f..220f,
            steps = 12,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay) { Text("Play") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }
        Text("Assistant: $shown", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EstimatorTab(
    profileIndex: Int,
    onProfileChange: (Int) -> Unit,
    threads: Int,
    onThreadsChange: (Int) -> Unit,
    contextSize: Int,
    onContextChange: (Int) -> Unit,
    quantizedKv: Boolean,
    onKvChange: (Boolean) -> Unit,
) {
    val profile = PROFILES[profileIndex]
    val threadFactor = THREAD_FACTOR[threads] ?: 1f
    val contextFactor = when (contextSize) {
        512 -> 1.03f
        2048 -> 0.92f
        else -> 1.00f
    }
    val tokensPerSec = 13f * profile.speedMultiplier * threadFactor * contextFactor
    val kvMultiplier = if (quantizedKv) 0.5f else 1f
    val estimatedRam = profile.weightsGb * 1024f +
        (contextSize / 1024f) * profile.kvMbPerK * kvMultiplier + 420f
    val ttfa = (profile.prefillTokens / (13f * profile.speedMultiplier * threadFactor * 2.6f) * 1000f + 180f).toLong()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Config estimator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Estimate only. Use it to choose A/B runs, then measure on the device.", style = MaterialTheme.typography.bodySmall)

        PROFILES.forEachIndexed { index, item ->
            OutlinedButton(
                onClick = { onProfileChange(index) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (index == profileIndex) "[${item.name}]" else item.name)
            }
        }

        Text("Threads: $threads")
        Slider(
            value = threads.toFloat(),
            onValueChange = { onThreadsChange(it.toInt()) },
            valueRange = 1f..8f,
            steps = 6,
        )

        Text("Context: $contextSize tokens")
        Slider(
            value = contextSize.toFloat(),
            onValueChange = { onContextChange((it / 256).toInt() * 256) },
            valueRange = 512f..2048f,
            steps = 5,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onKvChange(!quantizedKv) }) {
                Text(if (quantizedKv) "KV: q8_0" else "KV: f16")
            }
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text("Toggle KV cache precision", style = MaterialTheme.typography.bodySmall)
        }

        Text("Estimated decode: ${tokensPerSec.toInt()} tok/s", style = MaterialTheme.typography.titleMedium)
        Text("Estimated resident memory: ${estimatedRam.toInt()} MB", style = MaterialTheme.typography.titleMedium)
        Text("Estimated TTFA: ${ttfa} ms", style = MaterialTheme.typography.titleMedium)
        Text(
            if (estimatedRam <= 2200f) "Within 2.2 GB budget" else "Over budget: reduce context or use a smaller model",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RoadmapTab() {
    val items = listOf(
        "P0 · Default 4 threads instead of 8 on 4x A73 + 4x A53",
        "P0 · VAD trailing silence 0.45s to 0.32s",
        "P0 · STT threads 4 to 2",
        "P0 · AudioTrack low-latency performance mode",
        "P1 · A/B Q4_0, Q4_K_S, Q4_K_M on the exact prompt set",
        "P1 · Extend prefix cache from system prompt to full conversation",
        "P1 · KV cache q8_0 only when 2048 context is required",
        "P1 · Warm up Piper and Moonshine before first user turn",
        "P2 · Try Qwen3 0.6B and LFM2.5 1.2B for latency-first mode",
        "P2 · Thermal-aware thread reduction using API 30 thermal status",
        "Avoid · dotprod/i8mm flags, Vulkan, and manual core pinning on A50",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Prioritized roadmap", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        items.forEach { item -> Text(item, style = MaterialTheme.typography.bodyMedium) }
    }
}
