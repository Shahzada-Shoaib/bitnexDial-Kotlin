package com.bitnextechnologies.bitnexdial.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.bitnextechnologies.bitnexdial.receiver.ServiceRestartReceiver
import com.bitnextechnologies.bitnexdial.service.SipForegroundService

/**
 * Professional keepalive manager for SIP service.
 * Uses AlarmManager to ensure the service stays running even on aggressive
 * battery optimization devices like Infinix, Xiaomi, Oppo, etc.
 *
 * This is similar to how WhatsApp maintains persistent connections.
 */
object ServiceKeepAliveManager {
    private const val TAG = "ServiceKeepAliveManager"
    private const val KEEPALIVE_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    private const val REQUEST_CODE_KEEPALIVE = 12345
    private const val REQUEST_CODE_RESTART = 12346

    /**
     * Schedule periodic keepalive alarms to ensure service stays running.
     * Uses inexact repeating alarms for better battery efficiency.
     */
    fun scheduleKeepalive(context: Context) {
        Log.d(TAG, "Scheduling keepalive alarm")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
            action = ServiceRestartReceiver.ACTION_KEEPALIVE
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_KEEPALIVE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel any existing alarm first
        alarmManager.cancel(pendingIntent)

        // Schedule repeating alarm
        val triggerTime = SystemClock.elapsedRealtime() + KEEPALIVE_INTERVAL_MS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Use setExactAndAllowWhileIdle for better reliability on Doze mode
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact keepalive alarm for ${KEEPALIVE_INTERVAL_MS / 1000}s")
            } catch (e: SecurityException) {
                // Fallback if exact alarm permission not granted
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled inexact keepalive alarm (exact not allowed)")
            }
        } else {
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                KEEPALIVE_INTERVAL_MS,
                pendingIntent
            )
            Log.d(TAG, "Scheduled repeating keepalive alarm")
        }
    }

    /**
     * Cancel keepalive alarms
     */
    fun cancelKeepalive(context: Context) {
        Log.d(TAG, "Canceling keepalive alarm")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
            action = ServiceRestartReceiver.ACTION_KEEPALIVE
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_KEEPALIVE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
    }

    /**
     * Schedule a one-time restart alarm for when service is killed.
     * This is called from onTaskRemoved() to ensure service restarts.
     */
    fun scheduleServiceRestart(context: Context, delayMs: Long = 1000L) {
        Log.d(TAG, "Scheduling service restart in ${delayMs}ms")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
            action = ServiceRestartReceiver.ACTION_RESTART_SERVICE
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_RESTART,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = SystemClock.elapsedRealtime() + delayMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }

        Log.d(TAG, "Service restart alarm scheduled")
    }

    /**
     * Immediately restart the SIP service
     */
    fun restartServiceNow(context: Context) {
        Log.d(TAG, "Restarting SIP service immediately")

        try {
            val serviceIntent = Intent(context, SipForegroundService::class.java).apply {
                action = SipForegroundService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // Re-schedule keepalive
            scheduleKeepalive(context)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service", e)
        }
    }
}
