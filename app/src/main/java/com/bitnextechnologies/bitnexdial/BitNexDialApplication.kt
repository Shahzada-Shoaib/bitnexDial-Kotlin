package com.bitnextechnologies.bitnexdial

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.bitnextechnologies.bitnexdial.data.repository.ContactRepository
import com.bitnextechnologies.bitnexdial.util.Constants
import com.bitnextechnologies.bitnexdial.util.ServiceKeepAliveManager
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

private const val TAG = "BitNexDialApplication"

@HiltAndroidApp
class BitNexDialApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var contactRepository: ContactRepository

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Firebase FIRST - MUST be synchronous for FCM to work
        // FCM messages won't be delivered if Firebase isn't initialized when app starts
        try {
            FirebaseApp.initializeApp(this)
            Log.d(TAG, "Firebase initialized synchronously")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase", e)
        }

        // Create notification channels (required for foreground service and notifications)
        createNotificationChannels()

        // Load device contacts into cache (single source of truth for contact names)
        // This is done early so contact names are available when viewing messages
        applicationScope.launch {
            try {
                contactRepository.loadContacts()
                Log.d(TAG, "Device contacts loaded into cache")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading device contacts", e)
            }
        }

        // Schedule SIP startup worker - professional approach like WhatsApp
        // This ensures the SIP service starts even if boot receiver wasn't triggered
        try {
            com.bitnextechnologies.bitnexdial.worker.SipStartupWorker.schedule(this)
            com.bitnextechnologies.bitnexdial.worker.SipStartupWorker.schedulePeriodicCheck(this)
            Log.d(TAG, "SIP startup workers scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling SIP startup workers", e)
        }

        // Schedule AlarmManager-based keepalive - additional reliability layer
        try {
            ServiceKeepAliveManager.scheduleKeepalive(this)
            Log.d(TAG, "Keepalive alarm scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling keepalive alarm", e)
        }

        // Initialize Crashlytics in background (non-critical)
        applicationScope.launch(Dispatchers.IO) {
            try {
                initializeCrashlytics()
                Log.d(TAG, "Crashlytics initialized in background")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Crashlytics", e)
            }
        }
    }

    private fun initializeCrashlytics() {
        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Incoming Calls Channel (High Priority)
            val incomingCallChannel = NotificationChannel(
                Constants.CHANNEL_ID_INCOMING_CALLS,
                getString(R.string.notification_channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_calls_desc)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(incomingCallChannel)

            // Missed Calls Channel - CRITICAL: setShowBadge(true) for app icon badge support
            val missedCallChannel = NotificationChannel(
                Constants.CHANNEL_ID_MISSED_CALLS,
                getString(R.string.notification_channel_missed),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_missed_desc)
                enableVibration(true)
                setShowBadge(true) // Required for app icon badge on launchers
            }
            notificationManager.createNotificationChannel(missedCallChannel)

            // Messages Channel
            val messagesChannel = NotificationChannel(
                Constants.CHANNEL_ID_MESSAGES,
                getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_messages_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(messagesChannel)

            // VoIP Service Channel (Low Priority - Silent)
            val serviceChannel = NotificationChannel(
                Constants.CHANNEL_ID_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // Active Call Channel
            val activeCallChannel = NotificationChannel(
                Constants.CHANNEL_ID_ACTIVE_CALL,
                "Active Call",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows ongoing call status"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(activeCallChannel)
        }
    }

    companion object {
        @Volatile
        private var instance: BitNexDialApplication? = null

        fun getInstance(): BitNexDialApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }

        fun getAppContext(): Context {
            return getInstance().applicationContext
        }
    }
}
