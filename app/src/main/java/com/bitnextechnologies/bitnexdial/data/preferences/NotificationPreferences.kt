package com.bitnextechnologies.bitnexdial.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationDataStore: DataStore<Preferences> by preferencesDataStore(name = "notification_preferences")

/**
 * Preferences for notification and call alert settings.
 */
@Singleton
class NotificationPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        private const val PREFS_NAME = "notification_prefs_sync"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    }

    // SharedPreferences for synchronous access (used in FCM service where runBlocking is risky)
    private val syncPrefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Whether vibration is enabled for incoming calls
     */
    val vibrationEnabled: Flow<Boolean> = context.notificationDataStore.data.map { preferences ->
        preferences[VIBRATION_ENABLED] ?: true // Default to enabled
    }

    /**
     * Get vibration enabled synchronously.
     * Safe to call from any thread without blocking coroutines.
     * Used by FCM service where runBlocking could cause ANR.
     */
    fun getVibrationEnabledSync(): Boolean {
        return syncPrefs.getBoolean(KEY_VIBRATION_ENABLED, true)
    }

    /**
     * Enable or disable vibration
     */
    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { preferences ->
            preferences[VIBRATION_ENABLED] = enabled
        }
        // Also update sync prefs for FCM service access
        syncPrefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
    }
}
