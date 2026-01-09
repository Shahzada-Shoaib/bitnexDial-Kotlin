package com.bitnextechnologies.bitnexdial.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bitnextechnologies.bitnexdial.service.SipForegroundService

/**
 * Receiver to restart SIP service when it gets killed.
 * This is part of the WhatsApp-like professional approach to ensure
 * the VoIP service stays running reliably.
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceRestartReceiver"
        const val ACTION_RESTART_SERVICE = "com.bitnextechnologies.bitnexdial.RESTART_SERVICE"
        const val ACTION_KEEPALIVE = "com.bitnextechnologies.bitnexdial.KEEPALIVE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== ServiceRestartReceiver onReceive ===")
        Log.d(TAG, "Action: ${intent.action}")

        when (intent.action) {
            ACTION_RESTART_SERVICE, ACTION_KEEPALIVE -> {
                Log.d(TAG, "Restarting SIP foreground service")
                startSipService(context)
            }
        }
    }

    private fun startSipService(context: Context) {
        try {
            val serviceIntent = Intent(context, SipForegroundService::class.java).apply {
                action = SipForegroundService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d(TAG, "SIP service restart requested successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart SIP service", e)
        }
    }
}
