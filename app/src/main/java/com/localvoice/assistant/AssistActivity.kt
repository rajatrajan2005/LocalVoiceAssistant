package com.localvoice.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.localvoice.assistant.service.AssistantService

class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceIntent = Intent(this, AssistantService::class.java)
            .setAction(AssistantService.ACTION_START_HANDS_FREE)
        ContextCompat.startForegroundService(this, serviceIntent)
        val uiIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(uiIntent)
        finish()
    }
}
