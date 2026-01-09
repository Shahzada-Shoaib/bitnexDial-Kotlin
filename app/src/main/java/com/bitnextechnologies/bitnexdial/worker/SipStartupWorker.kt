package com.bitnextechnologies.bitnexdial.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.bitnextechnologies.bitnexdial.service.SipForegroundService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based SIP startup worker.
 * This is a more reliable approach than just using BroadcastReceiver for BOOT_COMPLETED.
 *
 * Similar to how WhatsApp and other professional apps handle startup:
 * - Uses WorkManager which survives app restarts and device reboots
 * - Has exponential backoff for retries
 * - Works even if the app was force-stopped
 */
@HiltWorker
class SipStartupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SipStartupWorker"
        private const val UNIQUE_WORK_NAME = "sip_startup_work"

        /**
         * Schedule the startup worker to run.
         * Uses ExpeditedWork for immediate execution on Android 12+
         */
        fun schedule(context: Context) {
            Log.d(TAG, "Scheduling SIP startup worker")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SipStartupWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            Log.d(TAG, "SIP startup worker scheduled")
        }

        /**
         * Schedule periodic check to ensure SIP stays connected
         */
        fun schedulePeriodicCheck(context: Context) {
            Log.d(TAG, "Scheduling periodic SIP check")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicWork = PeriodicWorkRequestBuilder<SipStartupWorker>(
                15, TimeUnit.MINUTES // Run every 15 minutes minimum
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "${UNIQUE_WORK_NAME}_periodic",
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicWork
                )

            Log.d(TAG, "Periodic SIP check scheduled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "=== SipStartupWorker doWork started ===")

        return try {
            // Start the SIP foreground service
            startSipService()
            Log.d(TAG, "SIP service start requested from worker")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SIP service from worker", e)
            // Retry with exponential backoff
            if (runAttemptCount < 5) {
                Log.d(TAG, "Will retry (attempt ${runAttemptCount + 1}/5)")
                Result.retry()
            } else {
                Log.e(TAG, "Max retries reached, giving up")
                Result.failure()
            }
        }
    }

    private fun startSipService() {
        Log.d(TAG, "Starting SIP foreground service from worker")

        val serviceIntent = Intent(context, SipForegroundService::class.java).apply {
            action = SipForegroundService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        Log.d(TAG, "SIP service start intent sent")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Required for expedited work on Android 12+
        val notification = androidx.core.app.NotificationCompat.Builder(
            context,
            com.bitnextechnologies.bitnexdial.util.Constants.CHANNEL_VOIP_SERVICE
        )
            .setSmallIcon(com.bitnextechnologies.bitnexdial.R.drawable.ic_call)
            .setContentTitle("Starting VoIP Service")
            .setContentText("Connecting...")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(999, notification)
    }
}
