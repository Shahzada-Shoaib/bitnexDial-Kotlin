package com.bitnextechnologies.bitnexdial.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.bitnextechnologies.bitnexdial.service.SipForegroundService
import com.bitnextechnologies.bitnexdial.util.ServiceKeepAliveManager
import com.bitnextechnologies.bitnexdial.worker.SipStartupWorker

/**
 * Receiver that starts the SIP service on device boot.
 * Ensures VoIP connectivity is restored after restart.
 *
 * Uses a two-pronged approach like WhatsApp:
 * 1. Direct service start for immediate connection
 * 2. WorkManager as reliable backup (survives app kills, has network constraints)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== BootReceiver onReceive ===")
        Log.d(TAG, "Action: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Device just booted but is still locked
                Log.d(TAG, "Locked boot completed - scheduling worker for when unlocked")
                // Schedule worker - it will wait for network
                SipStartupWorker.schedule(context.applicationContext)
            }
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Boot completed - starting SIP service + scheduling worker + keepalive")
                startSipServiceIfUnlocked(context.applicationContext)
                // Also schedule worker as backup
                SipStartupWorker.schedule(context.applicationContext)
                // Schedule periodic check
                SipStartupWorker.schedulePeriodicCheck(context.applicationContext)
                // Schedule AlarmManager-based keepalive
                ServiceKeepAliveManager.scheduleKeepalive(context.applicationContext)
            }
            Intent.ACTION_USER_UNLOCKED -> {
                Log.d(TAG, "User unlocked - starting SIP service + scheduling worker")
                startSipServiceIfUnlocked(context.applicationContext)
                SipStartupWorker.schedule(context.applicationContext)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "App updated - restarting SIP service")
                startSipServiceIfUnlocked(context.applicationContext)
                SipStartupWorker.schedule(context.applicationContext)
            }
        }
    }

    private fun startSipServiceIfUnlocked(context: Context) {
        // Check if user storage is unlocked (required for EncryptedSharedPreferences)
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val isUserUnlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            userManager?.isUserUnlocked ?: true
        } else {
            true
        }

        if (!isUserUnlocked) {
            Log.d(TAG, "User storage not yet unlocked, cannot start service")
            return
        }

        // Start service immediately - it handles network connectivity internally
        startSipService(context)
    }

    private fun startSipService(context: Context) {
        try {
            Log.d(TAG, "=== Starting SIP foreground service from BootReceiver ===")
            Log.d(TAG, "Context: ${context.javaClass.simpleName}")

            val serviceIntent = Intent(context, SipForegroundService::class.java).apply {
                action = SipForegroundService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Using startForegroundService (Android O+)")
                context.startForegroundService(serviceIntent)
            } else {
                Log.d(TAG, "Using startService (pre-Android O)")
                context.startService(serviceIntent)
            }

            Log.d(TAG, "SIP service start requested successfully from boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SIP service on boot", e)
        }
    }
}
