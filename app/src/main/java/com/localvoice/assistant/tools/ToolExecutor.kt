package com.localvoice.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

enum class ConfirmationDecision { CONFIRM, CANCEL, UNRELATED }

data class PendingConfirmation(
    val action: ToolAction,
    val promptText: String,
    val resolvedName: String,
    val resolvedNumber: String,
)

sealed interface ExecutionResult {
    data class Spoken(val message: String) : ExecutionResult
    data class NeedsConfirmation(val pending: PendingConfirmation) : ExecutionResult
    data class NeedsPermission(val permission: String, val action: ToolAction) : ExecutionResult
}

class ToolExecutor(private val context: Context) {
    companion object {
        private val CONFIRM_YES = Regex("""\b(yes|yeah|yep|confirm|go ahead|do it|ok|okay|sure|proceed)\b""")
        private val CONFIRM_NO = Regex("""\b(no|nope|cancel|stop|abort|never mind|don't|dont)\b""")

        fun parseVoiceConfirmation(transcript: String): ConfirmationDecision {
            val lower = transcript.lowercase()
            if (CONFIRM_NO.containsMatchIn(lower)) return ConfirmationDecision.CANCEL
            if (CONFIRM_YES.containsMatchIn(lower)) return ConfirmationDecision.CONFIRM
            return ConfirmationDecision.UNRELATED
        }
    }

    fun execute(action: ToolAction): ExecutionResult = when (action) {
        is ToolAction.SetAlarm -> setAlarm(action)
        is ToolAction.SetTimer -> setTimer(action)
        is ToolAction.OpenApp -> openApp(action)
        is ToolAction.CallContact -> prepareCallOrSms(action, action.target)
        is ToolAction.SendSms -> prepareCallOrSms(action, action.target)
        is ToolAction.Flashlight -> setFlashlight(action.on)
        is ToolAction.Volume -> adjustVolume(action)
        is ToolAction.MediaControl -> dispatchMediaKey(action.action)
        is ToolAction.ReadBattery -> readBattery()
        is ToolAction.OpenSettingsPanel -> openSettingsPanel(action.panel)
    }

    fun executeConfirmed(pending: PendingConfirmation): ExecutionResult {
        val numberUri = Uri.encode(pending.resolvedNumber)
        return when (val action = pending.action) {
            is ToolAction.CallContact -> {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numberUri"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent(intent, "Opening dialer for ${pending.resolvedName}.")
            }
            is ToolAction.SendSms -> {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$numberUri"))
                    .putExtra("sms_body", action.message)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent(intent, "Opening message composer for ${pending.resolvedName}.")
            }
            else -> execute(action)
        }
    }

    private fun prepareCallOrSms(action: ToolAction, rawTarget: String): ExecutionResult {
        if (rawTarget.isBlank()) {
            return ExecutionResult.Spoken("Please specify a contact name or phone number.")
        }
        if (action is ToolAction.SendSms && action.message.isBlank()) {
            return ExecutionResult.Spoken("Please specify the message text.")
        }

        val (name, number) = if (looksLikePhoneNumber(rawTarget)) {
            rawTarget to rawTarget
        } else {
            // Only request READ_CONTACTS when resolving a spoken contact name.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return ExecutionResult.NeedsPermission(Manifest.permission.READ_CONTACTS, action)
            }
            lookupContactPhone(rawTarget)
                ?: return ExecutionResult.Spoken("Could not find a contact matching $rawTarget.")
        }

        val prompt = when (action) {
            is ToolAction.CallContact -> "Call $name? Say yes to confirm or tap Confirm."
            is ToolAction.SendSms -> "Compose SMS to $name saying ${action.message}? Say yes to confirm or tap Confirm."
            else -> return execute(action)
        }
        return ExecutionResult.NeedsConfirmation(
            PendingConfirmation(
                action = action,
                promptText = prompt,
                resolvedName = name,
                resolvedNumber = number,
            )
        )
    }

    private fun setAlarm(action: ToolAction.SetAlarm): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action.label.isNotEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
        }
        val minStr = action.minute.toString().padStart(2, '0')
        return launchIntent(intent, "Setting alarm for ${action.hour}:$minStr.")
    }

    private fun setTimer(action: ToolAction.SetTimer): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action.label.isNotEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
        }
        return launchIntent(intent, "Setting timer for ${action.seconds} seconds.")
    }

    private fun openApp(action: ToolAction.OpenApp): ExecutionResult {
        if (action.app.isBlank()) {
            return ExecutionResult.Spoken("Please name an app to open.")
        }
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0)
        val query = action.app.lowercase()

        val match = apps.firstOrNull {
            it.loadLabel(pm).toString().equals(query, ignoreCase = true)
        } ?: apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().startsWith(query)
        } ?: apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(query) ||
                it.activityInfo.packageName.lowercase().contains(query)
        } ?: return ExecutionResult.Spoken("Could not find app ${action.app}.")

        val label = match.loadLabel(pm).toString()
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(launch, "Opening $label.")
    }

    private fun setFlashlight(on: Boolean): ExecutionResult {
        val cam = context.getSystemService(CameraManager::class.java)
            ?: return ExecutionResult.Spoken("Camera service unavailable.")
        val torchId = runCatching {
            cam.cameraIdList.firstOrNull { id ->
                val chars = cam.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val backFacing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && backFacing
            } ?: cam.cameraIdList.firstOrNull { id ->
                cam.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return ExecutionResult.Spoken("No flashlight hardware found.")

        return runCatching {
            cam.setTorchMode(torchId, on)
            ExecutionResult.Spoken(if (on) "Flashlight turned on." else "Flashlight turned off.")
        }.getOrElse {
            ExecutionResult.Spoken("Flashlight is unavailable right now.")
        }
    }

    private fun adjustVolume(action: ToolAction.Volume): ExecutionResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return ExecutionResult.Spoken("Audio service unavailable.")
        val flags = AudioManager.FLAG_SHOW_UI
        return when (action.action) {
            "up" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
                ExecutionResult.Spoken("Volume increased.")
            }
            "down" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
                ExecutionResult.Spoken("Volume lowered.")
            }
            "mute" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, flags)
                ExecutionResult.Spoken("Volume muted.")
            }
            "unmute" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, flags)
                ExecutionResult.Spoken("Volume unmuted.")
            }
            "set" -> {
                val pct = (action.level ?: 50).coerceIn(0, 100)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetIndex = ((pct / 100f) * max).roundToInt().coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, flags)
                ExecutionResult.Spoken("Volume set to $pct percent.")
            }
            else -> ExecutionResult.Spoken("Unknown volume action.")
        }
    }

    private fun dispatchMediaKey(action: String): ExecutionResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return ExecutionResult.Spoken("Audio service unavailable.")
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ExecutionResult.Spoken("Media $action.")
    }

    private fun readBattery(): ExecutionResult {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }
        if (pct < 0) return ExecutionResult.Spoken("Battery status unavailable.")

        val status = when (sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            else -> "not charging"
        }
        return ExecutionResult.Spoken("Battery is at $pct percent, $status.")
    }

    private fun openSettingsPanel(panel: String): ExecutionResult {
        val action = when (panel) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_VOLUME
            } else {
                Settings.ACTION_SOUND_SETTINGS
            }
            "internet" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            } else {
                Settings.ACTION_WIRELESS_SETTINGS
            }
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_WIFI
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(intent, "Opening $panel settings.")
    }

    private fun launchIntent(intent: Intent, successMessage: String): ExecutionResult {
        if (intent.resolveActivity(context.packageManager) == null) {
            return ExecutionResult.Spoken("No app available to handle this action.")
        }
        return runCatching {
            context.startActivity(intent)
            ExecutionResult.Spoken(successMessage)
        }.getOrElse {
            ExecutionResult.Spoken("Unable to launch action: ${it.message}")
        }
    }

    private fun looksLikePhoneNumber(value: String): Boolean {
        return value.any { it.isDigit() } && value.all { it.isDigit() || it in "+-() ." }
    }

    private fun lookupContactPhone(target: String): Pair<String, String>? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        var fallback: Pair<String, String>? = null
        context.contentResolver.query(uri, projection, selection, arrayOf("%$target%"), null)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)?.trim().orEmpty()
                val num = cursor.getString(numCol)?.trim().orEmpty()
                if (num.isNotEmpty()) {
                    if (name.equals(target, ignoreCase = true)) return name to num
                    if (fallback == null) fallback = name to num
                }
            }
        }
        return fallback
    }
}