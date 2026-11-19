package com.localvoice.assistant.tools

import org.json.JSONObject

sealed interface ToolAction {
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : ToolAction
    data class SetTimer(val seconds: Int, val label: String) : ToolAction
    data class OpenApp(val app: String) : ToolAction
    data class CallContact(val target: String) : ToolAction
    data class SendSms(val target: String, val message: String) : ToolAction
    data class Flashlight(val on: Boolean) : ToolAction
    data class Volume(val action: String, val level: Int?) : ToolAction
    data class MediaControl(val action: String) : ToolAction
    data object ReadBattery : ToolAction
    data class OpenSettingsPanel(val panel: String) : ToolAction
}

sealed interface ModelOutput {
    data class Reply(val text: String) : ModelOutput
    data class Call(val action: ToolAction) : ModelOutput
    data class Invalid(val reason: String) : ModelOutput
}

object ToolParser {
    fun parse(text: String): ModelOutput {
        val trimmed = stripFences(text)
        if (trimmed.isEmpty()) return ModelOutput.Invalid("Empty response.")

        val obj = runCatching { JSONObject(trimmed) }.getOrElse {
            return ModelOutput.Invalid("I didn't understand that response.")
        }

        if (obj.has("tool")) {
            val name = obj.optString("tool", "")
            val args = obj.optJSONObject("args") ?: JSONObject()
            val action = toAction(name, args)
                ?: return ModelOutput.Invalid("Unknown tool: $name")
            return ModelOutput.Call(action)
        }

        if (obj.has("reply")) {
            return ModelOutput.Reply(obj.optString("reply", ""))
        }

        return ModelOutput.Invalid("I didn't understand that response.")
    }

    private fun stripFences(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            if (firstNewline >= 0) s = s.substring(firstNewline + 1)
            val lastFence = s.lastIndexOf("```")
            if (lastFence >= 0) s = s.substring(0, lastFence)
        }
        return s.trim()
    }

    private fun toAction(name: String, args: JSONObject): ToolAction? = when (name) {
        "set_alarm" -> ToolAction.SetAlarm(
            hour = args.optInt("hour", 7).coerceIn(0, 23),
            minute = args.optInt("minute", 0).coerceIn(0, 59),
            label = args.optString("label", ""),
        )
        "set_timer" -> ToolAction.SetTimer(
            seconds = args.optInt("seconds", 60).coerceAtLeast(1),
            label = args.optString("label", ""),
        )
        "open_app" -> ToolAction.OpenApp(args.optString("app", ""))
        "call_contact" -> ToolAction.CallContact(args.optString("target", ""))
        "send_sms" -> ToolAction.SendSms(
            target = args.optString("target", ""),
            message = args.optString("message", ""),
        )
        "flashlight" -> {
            val on = if (args.has("state")) {
                args.optString("state", "on").equals("on", ignoreCase = true)
            } else {
                args.optBoolean("on", true)
            }
            ToolAction.Flashlight(on)
        }
        "volume" -> ToolAction.Volume(
            action = args.optString("action", "up"),
            level = if (args.has("level")) args.optInt("level", 50) else null,
        )
        "media_play_pause" -> ToolAction.MediaControl(args.optString("action", "toggle"))
        "read_battery" -> ToolAction.ReadBattery
        "open_settings_panel" -> ToolAction.OpenSettingsPanel(args.optString("panel", "wifi"))
        else -> null
    }
}

class JsonReplyStreamer {
    private val raw = StringBuilder()
    private var emittedChars = 0
    private var isToolCall = false

    fun feed(token: String): String {
        if (token.isEmpty()) return ""
        raw.append(token)
        if (!isToolCall) {
            val replyKey = raw.indexOf("\"reply\"")
            val toolKey = raw.indexOf("\"tool\"")
            if (toolKey >= 0 && (replyKey < 0 || toolKey < replyKey)) isToolCall = true
        }
        if (isToolCall) return ""

        val decoded = extractReplyValue(raw.toString()) ?: return ""
        if (decoded.length <= emittedChars) return ""

        val delta = decoded.substring(emittedChars)
        emittedChars = decoded.length
        return delta
    }

    private fun extractReplyValue(source: String): String? {
        val key = "\"reply\""
        val keyIndex = source.indexOf(key)
        if (keyIndex < 0) return null

        var i = keyIndex + key.length
        while (i < source.length && source[i].isWhitespace()) i++
        if (i >= source.length || source[i] != ':') return null
        i++
        while (i < source.length && source[i].isWhitespace()) i++
        if (i >= source.length || source[i] != '"') return null
        i++

        val out = StringBuilder()
        while (i < source.length) {
            val c = source[i]
            if (c == '"') break
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= source.length) break
            when (val escape = source[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'u' -> {
                    if (i + 5 >= source.length) break
                    val code = source.substring(i + 2, i + 6).toIntOrNull(16) ?: break
                    out.append(code.toChar())
                    i += 4
                }
                else -> out.append(escape)
            }
            i += 2
        }
        return out.toString()
    }
}
