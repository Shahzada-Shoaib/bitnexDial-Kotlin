package com.bitnextechnologies.bitnexdial.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized permission management utility.
 * Consolidates permission checks across the app for consistency and maintainability.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * All permissions required for full app functionality
     */
    val requiredPermissions: List<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.MANAGE_OWN_CALLS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    /**
     * Check if a specific permission is granted
     */
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if all required permissions are granted
     */
    fun hasAllRequiredPermissions(): Boolean {
        return requiredPermissions.all { hasPermission(it) }
    }

    /**
     * Get list of permissions that are not yet granted
     */
    fun getMissingPermissions(): List<String> {
        return requiredPermissions.filter { !hasPermission(it) }
    }

    /**
     * Check if microphone permission is granted (required for calls)
     */
    fun hasMicrophonePermission(): Boolean {
        return hasPermission(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Check if phone state permission is granted (required for telecom)
     */
    fun hasPhoneStatePermission(): Boolean {
        return hasPermission(Manifest.permission.READ_PHONE_STATE)
    }

    /**
     * Check if contacts permission is granted
     */
    fun hasContactsPermission(): Boolean {
        return hasPermission(Manifest.permission.READ_CONTACTS)
    }

    /**
     * Check if call phone permission is granted
     */
    fun hasCallPhonePermission(): Boolean {
        return hasPermission(Manifest.permission.CALL_PHONE)
    }

    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Not required on older versions
        }
    }

    /**
     * Check if manage own calls permission is granted
     */
    fun hasManageOwnCallsPermission(): Boolean {
        return hasPermission(Manifest.permission.MANAGE_OWN_CALLS)
    }

    /**
     * Get a human-readable permission status summary
     */
    fun getPermissionStatusSummary(): String {
        return buildString {
            appendLine("Permission Status:")
            appendLine("- Microphone: ${if (hasMicrophonePermission()) "✓" else "✗"}")
            appendLine("- Contacts: ${if (hasContactsPermission()) "✓" else "✗"}")
            appendLine("- Phone: ${if (hasCallPhonePermission()) "✓" else "✗"}")
            appendLine("- Phone State: ${if (hasPhoneStatePermission()) "✓" else "✗"}")
            appendLine("- Manage Calls: ${if (hasManageOwnCallsPermission()) "✓" else "✗"}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appendLine("- Notifications: ${if (hasNotificationPermission()) "✓" else "✗"}")
            }
        }
    }
}
