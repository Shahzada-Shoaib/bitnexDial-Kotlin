package com.bitnextechnologies.bitnexdial.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bitnextechnologies.bitnexdial.R
import dagger.hilt.android.qualifiers.ApplicationContext
import me.leolin.shortcutbadger.ShortcutBadger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app icon badges (launcher badges) for WhatsApp-style notification experience.
 *
 * Uses ShortcutBadger library which supports 20+ launchers including:
 * - Samsung, Huawei, Xiaomi, Infinix/Transsion (XOS), OPPO, Vivo, Sony, etc.
 * - Also falls back to notification-based badges for Android 8.0+
 */
@Singleton
class BadgeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BadgeManager"
        private const val BADGE_CHANNEL_ID = "badge_channel"
        private const val BADGE_NOTIFICATION_ID = 999
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val manufacturer = Build.MANUFACTURER.lowercase()

    init {
        createBadgeChannel()
        // Check if ShortcutBadger is supported
        val isSupported = ShortcutBadger.isBadgeCounterSupported(context)
        Log.d(TAG, "ShortcutBadger supported: $isSupported, manufacturer: $manufacturer")
    }

    private fun createBadgeChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BADGE_CHANNEL_ID,
                "Missed Notifications",
                NotificationManager.IMPORTANCE_DEFAULT  // Use DEFAULT for better badge support
            ).apply {
                description = "Shows missed calls and messages count"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Update the app icon badge count.
     * This is the total count of all unread items (missed calls + unread messages + voicemails).
     */
    fun updateBadgeCount(missedCalls: Int, unreadMessages: Int, unreadVoicemails: Int = 0) {
        val totalCount = missedCalls + unreadMessages + unreadVoicemails
        Log.d(TAG, "Updating badge count: missedCalls=$missedCalls, messages=$unreadMessages, voicemails=$unreadVoicemails, total=$totalCount")

        if (totalCount > 0) {
            // Primary: Use ShortcutBadger (most reliable across launchers)
            applyShortcutBadge(totalCount)
            // Secondary: Also post notification for Android 8.0+ launchers that use notification badges
            showBadgeNotification(totalCount, missedCalls, unreadMessages, unreadVoicemails)
            // Tertiary: Manufacturer-specific broadcasts as fallback
            updateManufacturerBadge(totalCount)
        } else {
            clearBadge()
        }
    }

    /**
     * Apply badge using ShortcutBadger library
     */
    private fun applyShortcutBadge(count: Int) {
        try {
            val success = ShortcutBadger.applyCount(context, count)
            Log.d(TAG, "ShortcutBadger.applyCount($count) = $success")
        } catch (e: Exception) {
            Log.e(TAG, "ShortcutBadger error", e)
        }
    }

    /**
     * Test badge with a specific count (for debugging).
     * Call this from settings or debug menu to verify badge works on the device.
     */
    fun testBadge(count: Int = 5) {
        Log.d(TAG, "Testing badge with count: $count")
        Log.d(TAG, "Device manufacturer: $manufacturer")
        Log.d(TAG, "Is Transsion device: ${isTranssionDevice()}")
        Log.d(TAG, "ShortcutBadger supported: ${ShortcutBadger.isBadgeCounterSupported(context)}")

        // Use ShortcutBadger first (most reliable)
        applyShortcutBadge(count)
        // Also show notification for notification-based badge launchers
        showBadgeNotification(count, count, 0)
        // And try manufacturer-specific broadcasts
        updateManufacturerBadge(count)
    }

    /**
     * Show a notification that triggers the badge on the app icon.
     * Uses the missed_calls channel for better launcher compatibility.
     */
    private fun showBadgeNotification(totalCount: Int, missedCalls: Int, unreadMessages: Int, unreadVoicemails: Int = 0) {
        try {
            Log.d(TAG, "showBadgeNotification called: total=$totalCount, missed=$missedCalls, messages=$unreadMessages, voicemails=$unreadVoicemails")
            Log.d(TAG, "Device manufacturer: $manufacturer, isTranssion: ${isTranssionDevice()}")

            // Create intent to open app at recents tab
            val intent = Intent(context, Class.forName("com.bitnextechnologies.bitnexdial.presentation.MainActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("tab", "recents")
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                BADGE_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Build notification text
            val contentText = buildString {
                val parts = mutableListOf<String>()
                if (missedCalls > 0) parts.add("$missedCalls missed call${if (missedCalls > 1) "s" else ""}")
                if (unreadMessages > 0) parts.add("$unreadMessages unread message${if (unreadMessages > 1) "s" else ""}")
                if (unreadVoicemails > 0) parts.add("$unreadVoicemails voicemail${if (unreadVoicemails > 1) "s" else ""}")
                append(parts.joinToString(", "))
            }

            // Log channel status BEFORE posting notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = notificationManager.getNotificationChannel("missed_calls")
                if (channel == null) {
                    Log.e(TAG, "ERROR: missed_calls channel is NULL! Creating fallback channel...")
                    // Create the channel if it doesn't exist
                    val fallbackChannel = NotificationChannel(
                        "missed_calls",
                        "Missed Calls",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Missed call notifications"
                        setShowBadge(true)
                    }
                    notificationManager.createNotificationChannel(fallbackChannel)
                    Log.d(TAG, "Created fallback missed_calls channel with badge enabled")
                } else {
                    Log.d(TAG, "Channel exists: importance=${channel.importance}, showBadge=${channel.canShowBadge()}, id=${channel.id}")
                    if (!channel.canShowBadge()) {
                        Log.w(TAG, "WARNING: missed_calls channel has badge DISABLED! User may need to enable it in settings.")
                    }
                }
            }

            // Use missed_calls channel which is a standard channel that launchers recognize
            // XOS Launcher and other Chinese launchers often require visible notifications
            val notification = NotificationCompat.Builder(context, "missed_calls")
                .setSmallIcon(R.drawable.ic_call_missed)  // Use missed call icon for better recognition
                .setContentTitle("$totalCount unread")
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setNumber(totalCount)  // This sets the badge count - CRITICAL for numeric badges
                .setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)  // Use LARGE to show number instead of dot
                .setOnlyAlertOnce(true)  // Don't make sound/vibrate on update
                .setAutoCancel(false)  // Keep notification visible until explicitly dismissed
                .setOngoing(false)  // Allow user to swipe away
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)  // Use MISSED_CALL category
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // Show on lock screen
                .setShortcutId(null)  // Don't associate with shortcut
                // Add extras that some launchers read for badge count
                .addExtras(android.os.Bundle().apply {
                    putInt("android.notification.badgeCount", totalCount)
                    putInt("badge_count", totalCount)
                })
                .build()

            notificationManager.notify(BADGE_NOTIFICATION_ID, notification)
            Log.d(TAG, "Badge notification posted: ID=$BADGE_NOTIFICATION_ID, count=$totalCount, channel=missed_calls")

            // Verify notification was posted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNotifications = notificationManager.activeNotifications
                val badgeNotification = activeNotifications.find { it.id == BADGE_NOTIFICATION_ID }
                if (badgeNotification != null) {
                    Log.d(TAG, "Verified: Badge notification is active in notification tray")
                } else {
                    Log.e(TAG, "ERROR: Badge notification was NOT found in active notifications!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing badge notification", e)
        }
    }

    /**
     * Clear all badges
     */
    fun clearBadge() {
        try {
            // Clear ShortcutBadger
            ShortcutBadger.removeCount(context)
            Log.d(TAG, "ShortcutBadger badge removed")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing ShortcutBadger badge", e)
        }
        notificationManager.cancel(BADGE_NOTIFICATION_ID)
        clearManufacturerBadge()
        Log.d(TAG, "Badge cleared")
    }

    /**
     * Update manufacturer-specific badges (Samsung, Huawei, Infinix, etc.)
     */
    private fun updateManufacturerBadge(count: Int) {
        try {
            val launcherClassName = getLauncherClassName() ?: return

            // Send numeric badge intent first (works on some launchers)
            sendNumericBadgeIntent(count)

            // Infinix / Transsion / Tecno / Itel (XOS Launcher)
            if (isTranssionDevice()) {
                updateTranssionBadge(count, launcherClassName)
            }

            // Samsung badges (TouchWiz/OneUI)
            updateSamsungBadge(count, launcherClassName)

            // Huawei / Honor badges (EMUI)
            updateHuaweiBadge(count, launcherClassName)

            // Xiaomi / MIUI badges
            updateXiaomiBadge(count, launcherClassName)

            // OPPO / ColorOS badges
            updateOppoBadge(count, launcherClassName)

            // Vivo / FuntouchOS badges
            updateVivoBadge(count, launcherClassName)

            // Sony badges
            updateSonyBadge(count, launcherClassName)

            // Generic Android badge (works on some launchers)
            updateGenericBadge(count, launcherClassName)

        } catch (e: Exception) {
            Log.w(TAG, "Error updating manufacturer badge", e)
        }
    }

    private fun isTranssionDevice(): Boolean {
        return manufacturer.contains("infinix") ||
               manufacturer.contains("tecno") ||
               manufacturer.contains("itel") ||
               manufacturer.contains("transsion")
    }

    /**
     * Try to set badge via XOS Launcher's content provider
     * This is the most reliable method for Transsion devices (Infinix, Tecno, Itel)
     */
    private fun tryXosContentProvider(count: Int) {
        try {
            // XOS Launcher uses XLauncherUnreadProvider for badge counts
            // Authority: com.transsion.XOSLauncher.unreadprovider
            val xosUri = Uri.parse("content://com.transsion.XOSLauncher.unreadprovider")

            val values = ContentValues().apply {
                put("package_name", context.packageName)
                put("class_name", "com.bitnextechnologies.bitnexdial.presentation.MainActivity")
                put("unread_count", count)
                put("badge_count", count)
                // Alternative column names some XOS versions use
                put("packageName", context.packageName)
                put("className", "com.bitnextechnologies.bitnexdial.presentation.MainActivity")
                put("count", count)
            }

            // Try insert first
            val insertResult = context.contentResolver.insert(xosUri, values)
            Log.d(TAG, "XOS content provider insert result: $insertResult")

            // Also try update in case record exists
            val updateResult = context.contentResolver.update(
                xosUri,
                values,
                "package_name = ?",
                arrayOf(context.packageName)
            )
            Log.d(TAG, "XOS content provider update result: $updateResult rows")

            // Try with /unread path
            val xosUnreadUri = Uri.parse("content://com.transsion.XOSLauncher.unreadprovider/unread")
            context.contentResolver.insert(xosUnreadUri, values)
            Log.d(TAG, "XOS content provider (unread path) insert attempted")

            // Try HiLauncher provider (alternative Transsion launcher)
            try {
                val hiUri = Uri.parse("content://com.transsion.hilauncher.unreadprovider")
                context.contentResolver.insert(hiUri, values)
                Log.d(TAG, "HiLauncher content provider insert attempted")
            } catch (e: Exception) {
                // HiLauncher not available, that's fine
            }

        } catch (e: Exception) {
            Log.d(TAG, "XOS content provider not available: ${e.message}")
        }
    }

    /**
     * Send numeric badge via launcher-specific notification service binding
     * This works on some launchers that support the Android O+ badge API
     */
    private fun sendNumericBadgeIntent(count: Int) {
        try {
            // Some launchers listen for this specific broadcast to update numeric badges
            val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", "com.bitnextechnologies.bitnexdial.presentation.MainActivity")
                // Additional keys that different launchers might use
                putExtra("count", count)
                putExtra("number", count)
                putExtra("package", context.packageName)
                putExtra("class", "com.bitnextechnologies.bitnexdial.presentation.MainActivity")
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent numeric badge intent with count=$count")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send numeric badge intent", e)
        }
    }

    private fun updateTranssionBadge(count: Int, launcherClassName: String) {
        try {
            val componentName = ComponentName(context.packageName, launcherClassName)

            // Try XOS Launcher content provider first (most reliable for XOS)
            tryXosContentProvider(count)

            // XOS Launcher badge support - primary method
            val intent1 = Intent("com.transsion.XOSLauncher.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
                // Also add component format
                putExtra("component", componentName.flattenToString())
            }
            context.sendBroadcast(intent1)
            Log.d(TAG, "Sent XOSLauncher.action.BADGE_COUNT_UPDATE broadcast")

            // Alternative Transsion intent with android.intent.action
            val intent2 = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
            }
            context.sendBroadcast(intent2)
            Log.d(TAG, "Sent android.intent.action.BADGE_COUNT_UPDATE broadcast")

            // Try XOS Launcher specific package targeted broadcast
            val intent3 = Intent("com.transsion.XOSLauncher.action.BADGE_COUNT_UPDATE").apply {
                `package` = "com.transsion.XOSLauncher"
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
            }
            context.sendBroadcast(intent3)
            Log.d(TAG, "Sent targeted XOSLauncher broadcast")

            // Alternative: com.android.launcher.action.UPDATE_BADGE (used by some Infinix versions)
            val intent4 = Intent("com.android.launcher.action.UPDATE_BADGE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
                putExtra("count", count)
                putExtra("packageName", context.packageName)
                putExtra("className", launcherClassName)
            }
            context.sendBroadcast(intent4)
            Log.d(TAG, "Sent com.android.launcher.action.UPDATE_BADGE broadcast")

            // Try launcher3 style badge update (XOS Launcher is based on Launcher3)
            val intent5 = Intent("com.android.launcher3.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
            }
            context.sendBroadcast(intent5)
            Log.d(TAG, "Sent launcher3 badge broadcast")

            // XOS Launcher 2.0+ uses a different action format
            val intent6 = Intent("com.transsion.launcher.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
                putExtra("packageName", context.packageName)
                putExtra("className", launcherClassName)
            }
            context.sendBroadcast(intent6)
            Log.d(TAG, "Sent transsion.launcher badge broadcast")

            // Some Infinix/Tecno models use the HiOS launcher
            val intent7 = Intent("com.transsion.hilauncher.badge.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
            }
            context.sendBroadcast(intent7)
            Log.d(TAG, "Sent hilauncher badge broadcast")

            // Standard Android O+ badge via notification channel is primary method
            // The broadcasts above are fallbacks for launchers that don't use notification badges

            Log.d(TAG, "Transsion badge broadcasts sent: count=$count, class=$launcherClassName")
        } catch (e: Exception) {
            Log.w(TAG, "Transsion badge not supported", e)
        }
    }

    private fun updateSamsungBadge(count: Int, launcherClassName: String) {
        try {
            val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // Samsung badge API not available
        }
    }

    private fun updateHuaweiBadge(count: Int, launcherClassName: String) {
        try {
            val bundle = android.os.Bundle().apply {
                putString("package", context.packageName)
                putString("class", launcherClassName)
                putInt("badgenumber", count)
            }
            context.contentResolver.call(
                android.net.Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
                "change_badge",
                null,
                bundle
            )
        } catch (e: Exception) {
            // Huawei badge API not available
        }
    }

    private fun updateXiaomiBadge(count: Int, launcherClassName: String) {
        try {
            // MIUI uses notification-based badges, so the notification should work
            // But we can also try the direct API
            val intent = Intent("android.intent.action.APPLICATION_MESSAGE_UPDATE").apply {
                putExtra("android.intent.extra.update_application_component_name",
                    "${context.packageName}/$launcherClassName")
                putExtra("android.intent.extra.update_application_message_text", count.toString())
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // Xiaomi badge API not available
        }
    }

    private fun updateOppoBadge(count: Int, launcherClassName: String) {
        try {
            val bundle = android.os.Bundle().apply {
                putInt("app_badge_count", count)
            }
            context.contentResolver.call(
                android.net.Uri.parse("content://com.oppo.launcher.provider/"),
                "setAppBadgeCount",
                context.packageName,
                bundle
            )
        } catch (e: Exception) {
            // OPPO badge API not available
        }
    }

    private fun updateVivoBadge(count: Int, launcherClassName: String) {
        try {
            val intent = Intent("launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM").apply {
                putExtra("packageName", context.packageName)
                putExtra("className", launcherClassName)
                putExtra("notificationNum", count)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // Vivo badge API not available
        }
    }

    private fun updateSonyBadge(count: Int, launcherClassName: String) {
        try {
            val intent = Intent("com.sonyericsson.home.action.UPDATE_BADGE").apply {
                putExtra("com.sonyericsson.home.intent.extra.badge.SHOW_MESSAGE", count > 0)
                putExtra("com.sonyericsson.home.intent.extra.badge.ACTIVITY_NAME", launcherClassName)
                putExtra("com.sonyericsson.home.intent.extra.badge.MESSAGE", count.toString())
                putExtra("com.sonyericsson.home.intent.extra.badge.PACKAGE_NAME", context.packageName)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // Sony badge API not available
        }
    }

    private fun updateGenericBadge(count: Int, launcherClassName: String) {
        try {
            val intent = Intent("me.leolin.shortcutbadger.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launcherClassName)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // Generic badge not supported
        }
    }

    /**
     * Clear manufacturer-specific badges
     */
    private fun clearManufacturerBadge() {
        updateManufacturerBadge(0)
    }

    /**
     * Get the launcher activity class name
     */
    private fun getLauncherClassName(): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = context.packageName
            }
            context.packageManager.queryIntentActivities(intent, 0)
                .firstOrNull()?.activityInfo?.name
        } catch (e: Exception) {
            Log.w(TAG, "Error getting launcher class name", e)
            null
        }
    }
}
