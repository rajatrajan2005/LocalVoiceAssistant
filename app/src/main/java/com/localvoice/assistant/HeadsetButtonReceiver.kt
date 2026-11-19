package com.localvoice.assistant

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.localvoice.assistant.service.AssistantService

class HeadsetButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (handleMediaButton(context, intent)) abortBroadcast()
    }

    companion object {
        fun componentName(context: Context) =
            ComponentName(context, HeadsetButtonReceiver::class.java)

        fun isTriggerKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true

            else -> false
        }

        fun handleMediaButton(context: Context, intent: Intent): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
            val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (event?.action != KeyEvent.ACTION_DOWN || !isTriggerKey(event.keyCode)) return false
            startHandsFree(context)
            return true
        }

        fun startHandsFree(context: Context) {
            val intent = Intent(context, AssistantService::class.java)
                .setAction(AssistantService.ACTION_START_HANDS_FREE)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
