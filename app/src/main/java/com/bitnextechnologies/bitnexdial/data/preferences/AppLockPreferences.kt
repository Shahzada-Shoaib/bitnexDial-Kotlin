package com.bitnextechnologies.bitnexdial.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appLockDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_lock_preferences")

/**
 * Preferences for app lock / biometric authentication feature.
 * Provides WhatsApp-style screen lock functionality.
 */
@Singleton
class AppLockPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val LOCK_TIMEOUT = stringPreferencesKey("lock_timeout")
        private val LAST_BACKGROUND_TIME = longPreferencesKey("last_background_time")
        private val IS_LOCKED = booleanPreferencesKey("is_locked")
    }

    /**
     * Whether app lock is enabled
     */
    val appLockEnabled: Flow<Boolean> = context.appLockDataStore.data.map { preferences ->
        preferences[APP_LOCK_ENABLED] ?: false
    }

    /**
     * Lock timeout setting
     */
    val lockTimeout: Flow<LockTimeout> = context.appLockDataStore.data.map { preferences ->
        val value = preferences[LOCK_TIMEOUT] ?: LockTimeout.IMMEDIATELY.name
        try {
            LockTimeout.valueOf(value)
        } catch (e: Exception) {
            LockTimeout.IMMEDIATELY
        }
    }

    /**
     * Whether app is currently in locked state
     */
    val isLocked: Flow<Boolean> = context.appLockDataStore.data.map { preferences ->
        preferences[IS_LOCKED] ?: false
    }

    /**
     * Enable or disable app lock
     */
    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.appLockDataStore.edit { preferences ->
            preferences[APP_LOCK_ENABLED] = enabled
            if (!enabled) {
                preferences[IS_LOCKED] = false
            }
        }
    }

    /**
     * Set lock timeout
     */
    suspend fun setLockTimeout(timeout: LockTimeout) {
        context.appLockDataStore.edit { preferences ->
            preferences[LOCK_TIMEOUT] = timeout.name
        }
    }

    /**
     * Record when app went to background
     */
    suspend fun setLastBackgroundTime(time: Long) {
        context.appLockDataStore.edit { preferences ->
            preferences[LAST_BACKGROUND_TIME] = time
        }
    }

    /**
     * Get last background time
     */
    suspend fun getLastBackgroundTime(): Long {
        return context.appLockDataStore.data.first()[LAST_BACKGROUND_TIME] ?: 0L
    }

    /**
     * Set locked state
     */
    suspend fun setLocked(locked: Boolean) {
        context.appLockDataStore.edit { preferences ->
            preferences[IS_LOCKED] = locked
        }
    }

    /**
     * Check if app should be locked based on timeout
     */
    suspend fun shouldLock(): Boolean {
        val prefs = context.appLockDataStore.data.first()
        val enabled = prefs[APP_LOCK_ENABLED] ?: false
        if (!enabled) return false

        val timeout = try {
            LockTimeout.valueOf(prefs[LOCK_TIMEOUT] ?: LockTimeout.IMMEDIATELY.name)
        } catch (e: Exception) {
            LockTimeout.IMMEDIATELY
        }

        val lastBackgroundTime = prefs[LAST_BACKGROUND_TIME] ?: 0L
        if (lastBackgroundTime == 0L) return true

        val elapsed = System.currentTimeMillis() - lastBackgroundTime
        return elapsed >= timeout.milliseconds
    }

    /**
     * Lock timeout options (like WhatsApp)
     */
    enum class LockTimeout(val displayName: String, val milliseconds: Long) {
        IMMEDIATELY("Immediately", 0L),
        AFTER_1_MINUTE("After 1 minute", 60_000L),
        AFTER_30_MINUTES("After 30 minutes", 30 * 60_000L)
    }
}
