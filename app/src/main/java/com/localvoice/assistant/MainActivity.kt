package com.localvoice.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localvoice.assistant.service.AssistantService
import com.localvoice.assistant.service.AssistantUiState
import com.localvoice.assistant.voice.ListeningState
import java.util.Locale
import kotlinx.coroutines.delay

private val ANSWER_WORD_REGEX = Regex("\\S+")

class MainActivity : ComponentActivity() {
    private var serviceState by mutableStateOf<AssistantService?>(null)
    private var pendingPermissionName: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceState = (binder as AssistantService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val service = serviceState
                    if (service == null) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Connecting to LocalVoice service...")
                        }
                    } else {
                        val state by service.uiState.collectAsStateWithLifecycle()

                        val safPicker = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            if (uri != null) {
                                runCatching {
                                    contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                                service.loadLlmFromUri(uri)
                            }
                        }

                        var launchedPermissionName by remember { mutableStateOf<String?>(null) }
                        val permissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { granted ->
                            val perm = pendingPermissionName ?: launchedPermissionName
                                ?: return@rememberLauncherForActivityResult
                            pendingPermissionName = null
                            launchedPermissionName = null
                            service.onPermissionResult(perm, granted)
                        }

                        LaunchedEffect(state.permissionRequest) {
                            val perm = state.permissionRequest ?: run {
                                launchedPermissionName = null
                                return@LaunchedEffect
                            }
                            if (perm == launchedPermissionName) return@LaunchedEffect
                            launchedPermissionName = perm
                            pendingPermissionName = perm
                            permissionLauncher.launch(perm)
                        }

                        LaunchedEffect(Unit) {
                            while (true) {
                                service.refreshRamMetric()
                                delay(2000)
                            }
                        }

                        AssistantScreen(
                            state = state,
                            onPickModel = { safPicker.launch(arrayOf("*/*")) },
                            onModelSelected = { service.selectModel(it) },
                            onReloadOrUnload = {
                                if (state.llmLoaded) service.unloadLlm() else service.reloadAllModels()
                            },
                            onThreadsSelected = { service.setThreads(it) },
                            onHandsFreeChanged = { service.setHandsFree(it) },
                            onBargeInChanged = { service.setBargeIn(it) },
                            onConfirm = { service.confirmPendingAction() },
                            onCancel = { service.cancelPendingAction() },
                            onMicClick = { service.onMicButtonPressed() },
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN && HeadsetButtonReceiver.isTriggerKey(keyCode)) {
            HeadsetButtonReceiver.startHandsFree(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AssistantService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceState != null) {
            unbindService(connection)
            serviceState = null
        }
    }
}

@Composable
private fun AssistantScreen(
    state: AssistantUiState,
    onPickModel: () -> Unit,
    onModelSelected: (String) -> Unit,
    onReloadOrUnload: () -> Unit,
    onThreadsSelected: (Int) -> Unit,
    onHandsFreeChanged: (Boolean) -> Unit,
    onBargeInChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onMicClick: () -> Unit,
) {
    var modelExpanded by remember { mutableStateOf(false) }
    val selectedModelName = state.availableModels.firstOrNull { it.path == state.selectedModelPath }?.name
        ?: state.llmName

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("LocalVoice Offline Assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    OutlinedButton(
                        onClick = { modelExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Model: $selectedModelName")
                    }
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.availableModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No local .gguf models") },
                                onClick = { modelExpanded = false },
                            )
                        } else {
                            state.availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.name) },
                                    onClick = {
                                        onModelSelected(model.path)
                                        modelExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    "Speech: ${if (state.speechReady) "Ready" else "Missing"} | TTS: ${state.ttsBackend.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickModel) { Text("Pick .gguf (SAF)") }
                    OutlinedButton(onClick = onReloadOrUnload) {
                        Text(if (state.llmLoaded) "Unload LLM" else "Reload")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricItem("Tok/s", String.format(Locale.US, "%.1f", state.tokensPerSec))
                MetricItem("STT", "${state.sttLatencyMs} ms")
                MetricItem("TTFA", "${state.ttfaMs} ms")
                MetricItem("RAM", "${state.ramMb} MB")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hands-free", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.handsFree, onCheckedChange = onHandsFreeChanged)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Barge-in", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.bargeInEnabled, onCheckedChange = onBargeInChanged)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Threads:", style = MaterialTheme.typography.bodyMedium)
            (1..4).forEach { count ->
                FilterChip(
                    selected = state.threads == count,
                    onClick = { onThreadsSelected(count) },
                    label = { Text("$count") },
                )
            }
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val badge = when {
                    state.isSpeaking -> "Speaking..."
                    state.isGenerating -> "Thinking..."
                    state.listeningState == ListeningState.SPEECH_ACTIVE -> "Hearing speech..."
                    state.listeningState == ListeningState.LISTENING -> "Listening (VAD)..."
                    else -> "Idle"
                }
                Text(badge, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                AudioWaveform(
                    levels = state.waveform,
                    active = state.listeningState != ListeningState.IDLE,
                )

                if (state.partialTranscript.isNotEmpty()) {
                    Text("Live STT: ${state.partialTranscript}", style = MaterialTheme.typography.bodySmall)
                }
                if (state.lastUserText.isNotEmpty()) {
                    Text("You: ${state.lastUserText}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                if (state.assistantText.isNotEmpty()) {
                    val spokenIndex = state.spokenWordIndex
                    val visibleAnswer = remember(state.assistantText, spokenIndex, state.isSpeaking) {
                        when {
                            !state.isSpeaking -> state.assistantText
                            spokenIndex < 0 -> ""
                            else -> {
                                val matches = ANSWER_WORD_REGEX.findAll(state.assistantText).toList()
                                if (matches.isEmpty()) {
                                    ""
                                } else {
                                    val end = matches[spokenIndex.coerceAtMost(matches.lastIndex)].range.last + 1
                                    state.assistantText.substring(0, end)
                                }
                            }
                        }
                    }
                    Text("Assistant: $visibleAnswer", style = MaterialTheme.typography.bodyLarge)
                }

                val pending = state.pendingConfirmation
                if (pending != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirm) { Text("Confirm") }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
            }
        }

        val buttonLabel = when {
            state.isSpeaking || state.isGenerating -> "Stop / Interrupt"
            state.handsFree -> "Stop Hands-Free"
            state.listeningState != ListeningState.IDLE -> "Finish Speaking"
            else -> "Tap to Talk"
        }
        val isStopAction = state.isSpeaking || state.isGenerating || state.listeningState != ListeningState.IDLE
        Button(
            onClick = onMicClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = if (isStopAction) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(buttonLabel, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AudioWaveform(levels: List<Float>, active: Boolean) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        val count = levels.size.coerceAtLeast(1)
        val step = size.width / count
        levels.forEachIndexed { index, rawLevel ->
            val level = rawLevel.coerceIn(0f, 1f)
            val barHeight = (0.06f + level * 0.94f) * size.height
            val x = index * step + step / 2f
            drawLine(
                color = color,
                start = Offset(x, (size.height - barHeight) / 2f),
                end = Offset(x, (size.height + barHeight) / 2f),
                strokeWidth = (step * 0.55f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}