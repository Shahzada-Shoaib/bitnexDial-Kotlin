package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.data.local.dao.PhoneNumberDao
import com.bitnextechnologies.bitnexdial.data.preferences.AppLockPreferences
import com.bitnextechnologies.bitnexdial.data.preferences.NotificationPreferences
import com.bitnextechnologies.bitnexdial.data.preferences.ThemePreferences
import com.bitnextechnologies.bitnexdial.domain.model.PhoneNumber
import com.bitnextechnologies.bitnexdial.domain.model.User
import com.bitnextechnologies.bitnexdial.domain.repository.IAuthRepository
import com.bitnextechnologies.bitnexdial.util.BadgeManager
import com.bitnextechnologies.bitnexdial.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.leolin.shortcutbadger.ShortcutBadger
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: IAuthRepository,
    private val phoneNumberDao: PhoneNumberDao,
    private val themePreferences: ThemePreferences,
    private val appLockPreferences: AppLockPreferences,
    private val notificationPreferences: NotificationPreferences,
    private val badgeManager: BadgeManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val user: StateFlow<User?> = authRepository.getCurrentUserFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Load phone numbers from local database (saved during login)
    val phoneNumbers: StateFlow<List<PhoneNumber>> = phoneNumberDao.getAllPhoneNumbers()
        .map { entities ->
            entities.map { entity ->
                PhoneNumber(
                    id = entity.id,
                    number = entity.number,
                    formatted = entity.formatted,
                    type = entity.type,
                    callerIdName = entity.callerIdName,
                    smsEnabled = entity.smsEnabled,
                    voiceEnabled = entity.voiceEnabled,
                    isActive = entity.isActive,
                    label = entity.label,
                    sipPassword = entity.sipPassword
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun loadPhoneNumbers() {
        // No longer needed - phone numbers are loaded automatically via Flow
        // Keeping method for backward compatibility
    }

    val darkModeEnabled: StateFlow<Boolean> = themePreferences.darkModeEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val useSystemTheme: StateFlow<Boolean> = themePreferences.useSystemTheme
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    // App Lock / Biometric preferences
    val appLockEnabled: StateFlow<Boolean> = appLockPreferences.appLockEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val lockTimeout: StateFlow<AppLockPreferences.LockTimeout> = appLockPreferences.lockTimeout
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppLockPreferences.LockTimeout.IMMEDIATELY
        )

    // Notification / vibration preferences
    val vibrationEnabled: StateFlow<Boolean> = notificationPreferences.vibrationEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    fun setDarkModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themePreferences.setDarkModeEnabled(enabled)
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appLockPreferences.setAppLockEnabled(enabled)
        }
    }

    fun setLockTimeout(timeout: AppLockPreferences.LockTimeout) {
        viewModelScope.launch {
            appLockPreferences.setLockTimeout(timeout)
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationPreferences.setVibrationEnabled(enabled)
        }
    }

    /**
     * Check if biometric authentication is available on this device
     */
    fun isBiometricAvailable(): Boolean {
        return com.bitnextechnologies.bitnexdial.util.BiometricHelper.isBiometricAvailable(context)
    }

    fun logout() {
        viewModelScope.launch {
            try {
                authRepository.logout()
            } catch (e: Exception) {
                Log.e(TAG, "Error during logout", e)
            }
        }
    }

    /**
     * Test badge functionality by recreating the notification channel and posting a test badge.
     * This helps debug badge issues on devices like Infinix with XOS Launcher.
     */
    fun testBadge(): String {
        val sb = StringBuilder()

        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val manufacturer = Build.MANUFACTURER

            sb.appendLine("Device: $manufacturer ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.SDK_INT}")

            // Detect default launcher
            val launcherPackage = getDefaultLauncherPackage()
            sb.appendLine("Launcher: $launcherPackage")

            // Check if it's XOS/Transsion launcher
            val isXosLauncher = launcherPackage?.contains("transsion", ignoreCase = true) == true ||
                    launcherPackage?.contains("XOS", ignoreCase = true) == true ||
                    launcherPackage?.contains("hilauncher", ignoreCase = true) == true
            sb.appendLine("XOS Launcher: $isXosLauncher")

            // Check ShortcutBadger support
            val shortcutBadgerSupported = ShortcutBadger.isBadgeCounterSupported(context)
            sb.appendLine("ShortcutBadger: $shortcutBadgerSupported")

            // Check notification permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotificationPermission = context.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                sb.appendLine("Notification permission: $hasNotificationPermission")
            }

            // Check if app notifications are enabled
            val areNotificationsEnabled = notificationManager.areNotificationsEnabled()
            sb.appendLine("Notifications enabled: $areNotificationsEnabled")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Delete and recreate the missed_calls channel to ensure badge is enabled
                val existingChannel = notificationManager.getNotificationChannel(Constants.CHANNEL_ID_MISSED_CALLS)
                if (existingChannel != null) {
                    sb.appendLine("Channel badge enabled: ${existingChannel.canShowBadge()}")
                    sb.appendLine("Channel importance: ${existingChannel.importance}")
                    sb.appendLine("Deleting and recreating channel...")
                    notificationManager.deleteNotificationChannel(Constants.CHANNEL_ID_MISSED_CALLS)
                }

                // Recreate with badge enabled
                val newChannel = NotificationChannel(
                    Constants.CHANNEL_ID_MISSED_CALLS,
                    "Missed Calls",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Missed call notifications"
                    setShowBadge(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(newChannel)

                // Verify
                val verifyChannel = notificationManager.getNotificationChannel(Constants.CHANNEL_ID_MISSED_CALLS)
                sb.appendLine("New channel badge: ${verifyChannel?.canShowBadge()}")
            }

            // Test ShortcutBadger directly
            try {
                val success = ShortcutBadger.applyCount(context, 5)
                sb.appendLine("ShortcutBadger.applyCount(5): $success")
            } catch (e: Exception) {
                sb.appendLine("ShortcutBadger error: ${e.message}")
            }

            // Trigger test badge
            Log.d("SettingsViewModel", "Testing badge with count 5")
            badgeManager.testBadge(5)
            sb.appendLine("\nBadge test sent with count: 5")
            sb.appendLine("\nCheck your app icon!")

            sb.appendLine("\n--- TROUBLESHOOTING ---")
            if (isXosLauncher) {
                sb.appendLine("XOS Launcher detected!")
                sb.appendLine("Known issue: XOS Launcher has badge bugs.")
                sb.appendLine("\nTry these steps:")
                sb.appendLine("1. Long-press BitnexDial icon")
                sb.appendLine("2. Tap 'App info' > 'Notifications'")
                sb.appendLine("3. Enable 'App icon badges'")
                sb.appendLine("4. Clear XOS Launcher cache:")
                sb.appendLine("   Settings > Apps > XOS Launcher > Clear Cache")
                sb.appendLine("5. Try using Nova Launcher instead")
            } else {
                sb.appendLine("1. Settings > Apps > BitnexDial")
                sb.appendLine("2. Tap 'Notifications'")
                sb.appendLine("3. Enable 'App icon badges'")
            }

        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
            Log.e("SettingsViewModel", "Badge test error", e)
        }

        return sb.toString()
    }

    /**
     * Get the package name of the default launcher
     */
    private fun getDefaultLauncherPackage(): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Error getting launcher package", e)
            null
        }
    }

    /**
     * Open the app's notification settings
     */
    fun openNotificationSettings(): Intent {
        return Intent().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Open the launcher app's settings to enable badges
     */
    fun openLauncherSettings(): Intent? {
        val launcherPackage = getDefaultLauncherPackage() ?: return null
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$launcherPackage")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
