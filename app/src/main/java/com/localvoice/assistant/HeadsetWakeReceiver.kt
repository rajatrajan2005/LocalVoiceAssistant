package com.localvoice.assistant

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.localvoice.assistant.service.AssistantService

class HeadsetWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shouldStart = when (intent.action) {
            Intent.ACTION_HEADSET_PLUG -> intent.getIntExtra("state", 0) == 1
            BluetoothDevice.ACTION_ACL_CONNECTED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> true

            else -> false
        }
        if (!shouldStart) return

        val serviceIntent = Intent(context, AssistantService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
