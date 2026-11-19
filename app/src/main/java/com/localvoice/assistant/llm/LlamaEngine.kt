package com.localvoice.assistant.llm

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class GenerateResult(
    val text: String,
    val tokensGenerated: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val tokensPerSec: Double,
    val cancelled: Boolean,
)

class LlamaEngine {
    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 1024
        const val DEFAULT_THREADS = 4
        const val MAX_THREADS = 8

        init {
            System.loadLibrary("llama_jni")
        }
    }

    private fun interface TokenReceiver {
        fun onTokenBytes(bytes: ByteArray)
    }

    @Volatile
    private var handle: Long = 0L

    // Guards load/warm/generate/unload; cancel() and setThreads() only take handleLock briefly.
    private val opLock = Any()
    private val handleLock = Any()

    val isLoaded: Boolean
        get() = handle != 0L

    fun load(modelPath: String, threads: Int = DEFAULT_THREADS): Boolean = synchronized(opLock) {
        unloadInternal()
        val clamped = threads.coerceIn(1, MAX_THREADS)
        val ptr = nativeLoad(modelPath, DEFAULT_CONTEXT_LENGTH, clamped)
        if (ptr == 0L) return false
        synchronized(handleLock) {
            handle = ptr
        }
        true
    }

    fun warmPrefix(systemPrompt: String): Boolean = synchronized(opLock) {
        val ptr = handle
        if (ptr == 0L) return false
        nativeWarmPrefix(ptr, systemPrompt)
    }

    fun setThreads(threads: Int) = synchronized(handleLock) {
        val ptr = handle
        if (ptr != 0L) {
            nativeSetThreads(ptr, threads.coerceIn(1, MAX_THREADS))
        }
    }

    fun cancel() = synchronized(handleLock) {
        val ptr = handle
        if (ptr != 0L) {
            nativeCancel(ptr)
        }
    }

    fun generate(
        systemPrompt: String,
        userPrompt: String,
        grammar: String? = null,
        maxTokens: Int = 192,
        onToken: (String) -> Unit,
    ): GenerateResult = synchronized(opLock) {
        val ptr = handle
        if (ptr == 0L) {
            return GenerateResult("", 0, 0L, 0L, 0.0, cancelled = true)
        }

        val fullText = StringBuilder()
        val utf8 = Utf8ChunkDecoder()
        val receiver = TokenReceiver { bytes ->
            val chunk = utf8.decodeChunk(bytes)
            if (chunk.isNotEmpty()) {
                fullText.append(chunk)
                onToken(chunk)
            }
        }

        val stats = nativeGenerate(ptr, systemPrompt, userPrompt, grammar, maxTokens, receiver)
        val tail = utf8.flush()
        if (tail.isNotEmpty()) {
            fullText.append(tail)
            onToken(tail)
        }

        val tokens = stats.getOrElse(0) { 0L }.toInt()
        val prefillMs = stats.getOrElse(1) { 0L }
        val decodeMs = stats.getOrElse(2) { 0L }
        val cancelled = stats.getOrElse(3) { 0L } != 0L
        val tps = if (decodeMs > 0L) tokens * 1000.0 / decodeMs else 0.0

        GenerateResult(
            text = fullText.toString(),
            tokensGenerated = tokens,
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            tokensPerSec = tps,
            cancelled = cancelled,
        )
    }

    fun unload() {
        // Cancel any active generation before blocking on opLock during trimMemory/destroy.
        cancel()
        synchronized(opLock) {
            unloadInternal()
        }
    }

    private fun unloadInternal() {
        val ptr = synchronized(handleLock) {
            val current = handle
            handle = 0L
            current
        }
        if (ptr != 0L) {
            nativeUnload(ptr)
        }
    }

    private class Utf8ChunkDecoder {
        private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        private var pending = ByteArray(0)

        fun decodeChunk(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val combined = if (pending.isEmpty()) bytes else pending + bytes
            val inBuf = ByteBuffer.wrap(combined)
            val outBuf = CharBuffer.allocate(combined.size * 2 + 1)
            decoder.decode(inBuf, outBuf, false)
            pending = if (inBuf.hasRemaining()) {
                ByteArray(inBuf.remaining()).also { inBuf.get(it) }
            } else {
                ByteArray(0)
            }
            outBuf.flip()
            return outBuf.toString()
        }

        fun flush(): String {
            if (pending.isEmpty()) return ""
            val inBuf = ByteBuffer.wrap(pending)
            val outBuf = CharBuffer.allocate(pending.size + 2)
            decoder.decode(inBuf, outBuf, true)
            decoder.flush(outBuf)
            pending = ByteArray(0)
            decoder.reset()
            outBuf.flip()
            return outBuf.toString()
        }
    }

    private external fun nativeLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeUnload(handle: Long)
    private external fun nativeSetThreads(handle: Long, nThreads: Int)
    private external fun nativeCancel(handle: Long)
    private external fun nativeWarmPrefix(handle: Long, systemPrompt: String): Boolean
    private external fun nativeGenerate(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        grammar: String?,
        maxTokens: Int,
        receiver: TokenReceiver,
    ): LongArray
}