package com.bitnextechnologies.bitnexdial.util

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Do Not Disturb (DND) state detection and call interruption policy.
 * Use this to check if the app should ring/vibrate for incoming calls.
 */
@Singleton
class DndManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DndManager"
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Check if Do Not Disturb is currently active
     */
    fun isDndActive(): Boolean {
        return try {
            val filter = notificationManager.currentInterruptionFilter
            val isActive = filter != NotificationManager.INTERRUPTION_FILTER_ALL
            Log.d(TAG, "DND active: $isActive (filter=$filter)")
            isActive
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DND state", e)
            false
        }
    }

    /**
     * Get the current DND mode
     */
    fun getDndMode(): DndMode {
        return try {
            when (notificationManager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> DndMode.OFF
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndMode.PRIORITY_ONLY
                NotificationManager.INTERRUPTION_FILTER_NONE -> DndMode.TOTAL_SILENCE
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndMode.ALARMS_ONLY
                else -> DndMode.OFF
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting DND mode", e)
            DndMode.OFF
        }
    }

    /**
     * Check if calls can interrupt based on current DND policy.
     * Note: Android handles this automatically for notifications with CATEGORY_CALL,
     * but this method can be used for custom logic.
     */
    fun canCallsInterrupt(): Boolean {
        // If DND is off, calls can always interrupt
        if (!isDndActive()) {
            return true
        }

        // In priority mode, check if calls are allowed
        // Note: We can't directly query the DND policy without special permission,
        // but CATEGORY_CALL notifications are typically allowed in priority mode
        val mode = getDndMode()
        return when (mode) {
            DndMode.OFF -> true
            DndMode.PRIORITY_ONLY -> true // Calls are usually priority
            DndMode.ALARMS_ONLY -> false
            DndMode.TOTAL_SILENCE -> false
        }
    }

    /**
     * Check if the phone is in silent/vibrate mode (ringer mode)
     */
    fun isPhoneSilent(): Boolean {
        return try {
            val ringerMode = audioManager.ringerMode
            ringerMode == AudioManager.RINGER_MODE_SILENT
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ringer mode", e)
            false
        }
    }

    /**
     * Check if the phone is in vibrate mode
     */
    fun isPhoneVibrate(): Boolean {
        return try {
            val ringerMode = audioManager.ringerMode
            ringerMode == AudioManager.RINGER_MODE_VIBRATE
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ringer mode", e)
            false
        }
    }

    /**
     * Get the recommended notification behavior based on DND and ringer state
     */
    fun getNotificationBehavior(): NotificationBehavior {
        val dndMode = getDndMode()
        val ringerMode = audioManager.ringerMode

        return when {
            dndMode == DndMode.TOTAL_SILENCE -> NotificationBehavior.SILENT
            dndMode == DndMode.ALARMS_ONLY -> NotificationBehavior.SILENT
            ringerMode == AudioManager.RINGER_MODE_SILENT -> NotificationBehavior.SILENT
            ringerMode == AudioManager.RINGER_MODE_VIBRATE -> NotificationBehavior.VIBRATE_ONLY
            else -> NotificationBehavior.SOUND_AND_VIBRATE
        }
    }

    /**
     * Get a human-readable summary of DND status
     */
    fun getStatusSummary(): String {
        val dndMode = getDndMode()
        val behavior = getNotificationBehavior()
        return "DND: ${dndMode.name}, Behavior: ${behavior.name}"
    }
}

/**
 * DND mode states
 */
enum class DndMode {
    OFF,
    PRIORITY_ONLY,
    ALARMS_ONLY,
    TOTAL_SILENCE
}

/**
 * Recommended notification behavior based on DND and ringer settings
 */
enum class NotificationBehavior {
    SOUND_AND_VIBRATE,
    VIBRATE_ONLY,
    SILENT
}
