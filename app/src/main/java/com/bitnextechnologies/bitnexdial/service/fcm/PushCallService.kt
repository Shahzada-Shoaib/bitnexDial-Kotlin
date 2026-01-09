package com.bitnextechnologies.bitnexdial.service.fcm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bitnextechnologies.bitnexdial.R
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IMessageRepository
import com.bitnextechnologies.bitnexdial.presentation.call.IncomingCallActivity
import com.bitnextechnologies.bitnexdial.service.SipForegroundService
import com.bitnextechnologies.bitnexdial.service.telecom.PhoneAccountManager
import com.bitnextechnologies.bitnexdial.data.local.dao.VoicemailDao
import com.bitnextechnologies.bitnexdial.data.preferences.NotificationPreferences
import com.bitnextechnologies.bitnexdial.util.BadgeManager
import com.bitnextechnologies.bitnexdial.util.Constants
import com.bitnextechnologies.bitnexdial.util.DndManager
import com.bitnextechnologies.bitnexdial.util.NotificationBehavior
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service for handling push notifications
 * Primarily used for incoming call notifications when app is in background
 */
@AndroidEntryPoint
class PushCallService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "PushCallService"

        // Push notification types
        const val TYPE_INCOMING_CALL = "incoming_call"
        const val TYPE_MISSED_CALL = "missed_call"
        const val TYPE_VOICEMAIL = "voicemail"
        const val TYPE_MESSAGE = "message"
        const val TYPE_CALL_CANCELLED = "call_cancelled"

        // Notification IDs
        const val NOTIFICATION_ID_INCOMING_CALL = 1001
        const val NOTIFICATION_ID_MISSED_CALL = 1002
        const val NOTIFICATION_ID_MESSAGE = 1003
        const val NOTIFICATION_ID_VOICEMAIL = 1004
    }

    @Inject
    lateinit var phoneAccountManager: PhoneAccountManager

    @Inject
    lateinit var secureCredentialManager: SecureCredentialManager

    @Inject
    lateinit var callRepository: ICallRepository

    @Inject
    lateinit var messageRepository: IMessageRepository

    @Inject
    lateinit var contactRepository: com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository

    @Inject
    lateinit var deviceContactRepository: com.bitnextechnologies.bitnexdial.data.repository.ContactRepository

    @Inject
    lateinit var badgeManager: BadgeManager

    @Inject
    lateinit var dndManager: DndManager

    @Inject
    lateinit var notificationPreferences: NotificationPreferences

    @Inject
    lateinit var voicemailDao: VoicemailDao

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
        // Send token to server for push notifications
        sendTokenToServer(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "FCM message received from: ${message.from}")
        Log.d(TAG, "FCM data: ${message.data}")

        val data = message.data
        val type = data["type"] ?: return

        when (type) {
            TYPE_INCOMING_CALL -> handleIncomingCall(data)
            TYPE_MISSED_CALL -> handleMissedCall(data)
            TYPE_VOICEMAIL -> handleVoicemail(data)
            TYPE_MESSAGE -> handleMessage(data)
            TYPE_CALL_CANCELLED -> handleCallCancelled(data)
            else -> Log.w(TAG, "Unknown push type: $type")
        }
    }

    /**
     * Handle incoming call push notification
     * This is called when an incoming SIP call arrives and app is not running
     */
    private fun handleIncomingCall(data: Map<String, String>) {
        val callerNumber = data["caller_number"] ?: data["from"] ?: "Unknown"
        var callerName = data["caller_name"] ?: data["display_name"]
        val sipUri = data["sip_uri"]

        // Use a fixed callId "1" to match what the SIP WebView uses
        // This ensures end call works properly
        val callId = "1"

        Log.d(TAG, "Incoming call from: $callerNumber, FCM name: $callerName")

        // Ensure SIP foreground service is running for handling the call
        // This wakes up the SIP connection so we can properly answer the call
        ensureSipServiceRunning()

        // Look up contact name if not provided in FCM data
        if (callerName.isNullOrBlank() || callerName == callerNumber) {
            // Try to resolve contact name from local contacts
            serviceScope.launch {
                try {
                    val resolvedName = lookupContactName(callerNumber)
                    val finalName = resolvedName ?: callerNumber
                    Log.d(TAG, "Resolved caller name: $finalName for $callerNumber")
                    // Show notification with resolved name (must run on main thread for notification)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        showIncomingCallScreen(callId, callerNumber, finalName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error looking up contact name", e)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        showIncomingCallScreen(callId, callerNumber, callerNumber)
                    }
                }
            }
        } else {
            // Name provided in FCM data
            showIncomingCallScreen(callId, callerNumber, callerName)
        }
    }

    /**
     * Look up contact name from API contacts first, then device contacts
     */
    private suspend fun lookupContactName(phoneNumber: String): String? {
        // Try API contacts first
        try {
            val apiContact = contactRepository.getContactByPhoneNumber(phoneNumber)
            if (apiContact?.displayName?.isNotBlank() == true) {
                return apiContact.displayName
            }
        } catch (e: Exception) {
            Log.w(TAG, "API contact lookup failed: ${e.message}")
        }

        // Fall back to device contacts
        try {
            val deviceName = deviceContactRepository.getContactName(phoneNumber)
            if (!deviceName.isNullOrBlank()) {
                return deviceName
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device contact lookup failed: ${e.message}")
        }

        return null
    }

    /**
     * Ensure SIP foreground service is running to handle the incoming call
     * The service manages the WebView-based SIP connection
     */
    private fun ensureSipServiceRunning() {
        if (!SipForegroundService.isServiceRunning()) {
            Log.d(TAG, "Starting SIP service to handle incoming call")
            SipForegroundService.start(this)
        } else {
            Log.d(TAG, "SIP service already running")
        }
    }

    /**
     * Add incoming call via TelecomManager for native integration
     */
    private fun addIncomingCallViaTelecom(
        callId: String,
        callerNumber: String,
        callerName: String
    ): Boolean {
        return try {
            // Use PhoneAccountManager's safe method which handles permissions
            phoneAccountManager.addIncomingCall(callId, callerNumber, callerName)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception adding incoming call via Telecom - permissions may not be granted", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add incoming call via Telecom", e)
            false
        }
    }

    /**
     * Show full-screen incoming call activity as fallback
     * Respects Do Not Disturb and ringer mode settings
     */
    private fun showIncomingCallScreen(
        callId: String,
        callerNumber: String,
        callerName: String
    ) {
        // Check DND and ringer settings to determine notification behavior
        val notificationBehavior = dndManager.getNotificationBehavior()

        // Check user's vibration preference from settings (synchronous to avoid ANR)
        val vibrationEnabled = notificationPreferences.getVibrationEnabledSync()
        Log.d(TAG, "Incoming call notification behavior: $notificationBehavior, vibrationEnabled=$vibrationEnabled (${dndManager.getStatusSummary()})")

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(Constants.EXTRA_CALLER_NAME, callerName)
            putExtra(Constants.EXTRA_IS_INCOMING, true)
        }

        // Create full-screen notification
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Answer intent - use BroadcastReceiver for reliable background handling
        val answerIntent = Intent(Constants.ACTION_ANSWER_CALL).apply {
            setPackage(packageName)
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(Constants.EXTRA_CALLER_NAME, callerName)
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline intent - use BroadcastReceiver for reliable background handling
        val declineIntent = Intent(Constants.ACTION_DECLINE_CALL).apply {
            setPackage(packageName)
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(Constants.EXTRA_CALLER_NAME, callerName)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification respecting DND/ringer settings
        val notificationBuilder = NotificationCompat.Builder(this, Constants.CHANNEL_INCOMING_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Incoming Call")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(R.drawable.ic_call_end, "Decline", declinePendingIntent)
            .addAction(R.drawable.ic_call, "Answer", answerPendingIntent)

        // Apply sound and vibration based on DND/ringer mode and user preference
        when (notificationBehavior) {
            NotificationBehavior.SOUND_AND_VIBRATE -> {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                notificationBuilder.setSound(ringtoneUri)
                // Only add vibration if user has it enabled in settings
                if (vibrationEnabled) {
                    notificationBuilder.setVibrate(longArrayOf(0, 500, 200, 500))
                }
            }
            NotificationBehavior.VIBRATE_ONLY -> {
                // Only vibrate if user has it enabled in settings
                if (vibrationEnabled) {
                    notificationBuilder
                        .setSilent(false)
                        .setVibrate(longArrayOf(0, 500, 200, 500))
                } else {
                    // User disabled vibration, but phone is in vibrate mode - stay silent
                    notificationBuilder.setSilent(true)
                }
            }
            NotificationBehavior.SILENT -> {
                notificationBuilder.setSilent(true)
            }
        }

        val notification = notificationBuilder.build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_INCOMING_CALL, notification)

        // Also start activity directly for lock screen (even in DND, show the UI)
        startActivity(intent)
    }

    /**
     * Handle missed call notification
     */
    private fun handleMissedCall(data: Map<String, String>) {
        val callerNumber = data["caller_number"] ?: data["from"] ?: "Unknown"
        val callerName = data["caller_name"] ?: data["display_name"] ?: callerNumber
        val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
        val callId = data["call_id"]

        Log.d(TAG, "FCM Missed call from: $callerNumber ($callerName)")

        // CRITICAL: Dismiss any active incoming call UI since the call is now missed
        // This handles the case where app was closed and remote party hung up
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_INCOMING_CALL)

        // Send broadcast to dismiss IncomingCallActivity
        val cancelIntent = Intent(Constants.ACTION_CALL_CANCELLED).apply {
            putExtra(Constants.EXTRA_CALL_ID, callId)
            setPackage(packageName)
        }
        sendBroadcast(cancelIntent)

        // CRITICAL: Save missed call to database and update badge immediately
        serviceScope.launch {
            try {
                // Save the missed call to database with unique ID
                callRepository.saveMissedCall(
                    phoneNumber = callerNumber,
                    contactName = if (callerName != callerNumber) callerName else null,
                    timestamp = timestamp
                )
                Log.d(TAG, "Missed call saved to database")

                // Get updated counts and update badge immediately
                val missedCount = callRepository.getUnreadMissedCallCountDirect()
                val unreadMessages = messageRepository.getTotalUnreadCountDirect()
                val unreadVoicemails = voicemailDao.getUnreadVoicemailCountDirect()
                Log.d(TAG, "Updating badge: missedCalls=$missedCount, messages=$unreadMessages, voicemails=$unreadVoicemails")

                badgeManager.updateBadgeCount(missedCount, unreadMessages, unreadVoicemails)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving missed call or updating badge", e)
            }
        }

        // Show notification
        val intent = Intent(this, Class.forName("com.bitnextechnologies.bitnexdial.presentation.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("tab", "recents")
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_MISSED_CALLS)
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle("Missed Call")
            .setContentText("$callerName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setNumber(1)  // For badge support
            .build()

        // Reuse notificationManager from above (already declared for dismissing incoming call)
        // Use bitwise AND with 0x7FFFFFFF to safely convert Long timestamp to positive Int without overflow
        notificationManager.notify(NOTIFICATION_ID_MISSED_CALL + (timestamp and 0x7FFFFFFF).toInt(), notification)
    }

    /**
     * Handle voicemail notification
     */
    private fun handleVoicemail(data: Map<String, String>) {
        val callerNumber = data["caller_number"] ?: "Unknown"
        val callerName = data["caller_name"] ?: callerNumber
        val duration = data["duration"]?.toIntOrNull() ?: 0
        val voicemailId = data["voicemail_id"]

        Log.d(TAG, "New voicemail from: $callerNumber")

        val intent = Intent(this, Class.forName("com.bitnextechnologies.bitnexdial.presentation.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("tab", "voicemail")
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val durationText = if (duration > 0) " (${duration}s)" else ""

        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_voicemail)
            .setContentTitle("New Voicemail")
            .setContentText("$callerName$durationText")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_VOICEMAIL, notification)
    }

    /**
     * Handle new message notification
     */
    private fun handleMessage(data: Map<String, String>) {
        val senderNumber = data["sender_number"] ?: data["from"] ?: "Unknown"
        var senderName = data["sender_name"]
        val messageBody = data["body"] ?: data["message"] ?: ""
        val conversationId = data["conversation_id"] ?: senderNumber
        val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

        Log.d(TAG, "FCM New message from: $senderNumber ($senderName)")

        // CRITICAL: Sync messages and update badge immediately
        serviceScope.launch {
            try {
                // Look up contact name if not provided
                if (senderName.isNullOrBlank() || senderName == senderNumber) {
                    senderName = lookupContactName(senderNumber) ?: senderNumber
                }

                // Sync messages from server to get the new message in the database
                messageRepository.syncMessages()
                Log.d(TAG, "Messages synced after FCM notification")

                // Get updated counts and update badge immediately
                val missedCalls = callRepository.getUnreadMissedCallCountDirect()
                val unreadMessages = messageRepository.getTotalUnreadCountDirect()
                val unreadVoicemails = voicemailDao.getUnreadVoicemailCountDirect()
                Log.d(TAG, "Updating badge: missedCalls=$missedCalls, messages=$unreadMessages, voicemails=$unreadVoicemails")

                badgeManager.updateBadgeCount(missedCalls, unreadMessages, unreadVoicemails)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing messages or updating badge", e)
            }
        }

        // Clean message body for notification preview (remove HTML tags, handle images)
        val cleanedBody = cleanMessageForNotification(messageBody)
        val finalSenderName = senderName ?: senderNumber

        // Create intent that opens the specific conversation
        val intent = Intent(this, Class.forName("com.bitnextechnologies.bitnexdial.presentation.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("tab", "messages")
            putExtra("conversation_id", conversationId)
            putExtra("contact_name", finalSenderName)
            // Add action to distinguish notification clicks
            action = "OPEN_CONVERSATION"
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),  // Unique request code per conversation
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(finalSenderName)
            .setContentText(cleanedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanedBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setNumber(1)  // For badge support
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Use bitwise AND with 0x7FFFFFFF to safely convert Long timestamp to positive Int without overflow
        notificationManager.notify(NOTIFICATION_ID_MESSAGE + (timestamp and 0x7FFFFFFF).toInt(), notification)
    }

    /**
     * Clean message body for notification preview.
     * Removes HTML tags and replaces image tags with a readable placeholder.
     */
    private fun cleanMessageForNotification(message: String): String {
        if (message.isBlank()) return ""

        // Check if message contains an image tag
        val hasImage = message.contains("<img", ignoreCase = true) ||
                message.contains("previewImage", ignoreCase = true)

        // Strip HTML tags
        var result = message
            .replace(Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE), "") // Remove img tags
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ") // Convert <br> to space
            .replace(Regex("""<[^>]+>"""), "") // Remove other HTML tags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()

        // Remove image URLs from text
        result = result.replace(
            Regex("""https?://[^\s]+\.(jpg|jpeg|png|gif|webp)[^\s]*""", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        // Return appropriate preview text
        return when {
            hasImage && result.isBlank() -> "[Image]"
            hasImage && result.isNotBlank() -> "[Image] $result"
            result.isBlank() -> "New message"
            else -> result
        }
    }

    /**
     * Handle call cancelled (caller hung up before answer)
     */
    private fun handleCallCancelled(data: Map<String, String>) {
        val callId = data["call_id"]

        Log.d(TAG, "Call cancelled: $callId")

        // Dismiss incoming call notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_INCOMING_CALL)

        // Send broadcast to dismiss incoming call screen
        // Use explicit package for Android 13+ compatibility with RECEIVER_NOT_EXPORTED
        val intent = Intent(Constants.ACTION_CALL_CANCELLED).apply {
            putExtra(Constants.EXTRA_CALL_ID, callId)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * Send FCM token to server for push notifications
     */
    private fun sendTokenToServer(token: String) {
        // Save token securely
        secureCredentialManager.saveFcmToken(token)

        // Also save to legacy prefs for backward compatibility
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.KEY_FCM_TOKEN, token)
            .apply()

        Log.d(TAG, "FCM token saved securely, will sync when authenticated")
    }
}
