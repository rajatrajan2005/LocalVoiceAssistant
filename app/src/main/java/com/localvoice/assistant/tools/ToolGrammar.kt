package com.localvoice.assistant.tools

object ToolGrammar {
    // Few-shot examples teach tool selection so decoding can run without the slow GBNF grammar.
    const val SYSTEM_PROMPT =
        """Offline Android assistant. Reply ONLY with JSON.
For questions use {"reply":"..."}. For device actions use {"tool":"...","args":{...}}.
Tools:
set_alarm(hour,minute,label)
set_timer(seconds,label)
open_app(app)
call_contact(target)
send_sms(target,message)
flashlight(state:on|off)
volume(action:up|down|mute|unmute|set,level)
media_play_pause(action:toggle|play|pause|next|previous)
read_battery()
open_settings_panel(panel:wifi|bluetooth|volume|internet)

Examples:
User: set an alarm for 7 AM
Assistant: {"tool":"set_alarm","args":{"hour":7,"minute":0}}
User: open spotify
Assistant: {"tool":"open_app","args":{"app":"spotify"}}
User: read the battery level
Assistant: {"tool":"read_battery","args":{}}
User: turn the flashlight on
Assistant: {"tool":"flashlight","args":{"state":"on"}}
User: what is the capital of France
Assistant: {"reply":"Paris"}"""

    // Constrains llama.cpp logits to either {"reply":...} or one whitelisted tool call with typed args.
    val RESPONSE_GBNF = """
root ::= ws ( reply-obj | tool-obj ) ws
reply-obj ::= "{" ws "\"reply\"" ws ":" ws string ws "}"

tool-obj ::= "{" ws "\"tool\"" ws ":" ws (
    "\"set_alarm\"" ws "," ws "\"args\"" ws ":" ws alarm-args
  | "\"set_timer\"" ws "," ws "\"args\"" ws ":" ws timer-args
  | "\"open_app\"" ws "," ws "\"args\"" ws ":" ws app-args
  | "\"call_contact\"" ws "," ws "\"args\"" ws ":" ws contact-args
  | "\"send_sms\"" ws "," ws "\"args\"" ws ":" ws sms-args
  | "\"flashlight\"" ws "," ws "\"args\"" ws ":" ws flash-args
  | "\"volume\"" ws "," ws "\"args\"" ws ":" ws volume-args
  | "\"media_play_pause\"" ws "," ws "\"args\"" ws ":" ws media-args
  | "\"read_battery\"" ws "," ws "\"args\"" ws ":" ws empty-args
  | "\"open_settings_panel\"" ws "," ws "\"args\"" ws ":" ws panel-args
) ws "}"

alarm-args ::= "{" ws "\"hour\"" ws ":" ws int-val ws "," ws "\"minute\"" ws ":" ws int-val ( ws "," ws "\"label\"" ws ":" ws string )? ws "}"
timer-args ::= "{" ws "\"seconds\"" ws ":" ws int-val ( ws "," ws "\"label\"" ws ":" ws string )? ws "}"
app-args ::= "{" ws "\"app\"" ws ":" ws string ws "}"
contact-args ::= "{" ws "\"target\"" ws ":" ws string ws "}"
sms-args ::= "{" ws "\"target\"" ws ":" ws string ws "," ws "\"message\"" ws ":" ws string ws "}"
flash-args ::= "{" ws "\"state\"" ws ":" ws ( "\"on\"" | "\"off\"" ) ws "}"
volume-args ::= "{" ws "\"action\"" ws ":" ws ( "\"up\"" | "\"down\"" | "\"mute\"" | "\"unmute\"" | "\"set\"" ) ( ws "," ws "\"level\"" ws ":" ws int-val )? ws "}"
media-args ::= "{" ws "\"action\"" ws ":" ws ( "\"toggle\"" | "\"play\"" | "\"pause\"" | "\"next\"" | "\"previous\"" ) ws "}"
empty-args ::= "{" ws "}"
panel-args ::= "{" ws "\"panel\"" ws ":" ws ( "\"wifi\"" | "\"bluetooth\"" | "\"volume\"" | "\"internet\"" ) ws "}"

int-val ::= "0" | [1-9] [0-9]*
string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F]{4} ) )* "\""
ws ::= [ ]?
""".trimIndent()
}