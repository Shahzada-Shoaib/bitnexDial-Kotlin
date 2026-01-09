package com.bitnextechnologies.bitnexdial.util

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages speed dial entries (digits 2-9 mapped to phone numbers).
 * Speed dial 1 is typically reserved for voicemail.
 */
@Singleton
class SpeedDialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "speed_dial_prefs"
        private const val KEY_PREFIX = "speed_dial_"

        // Valid speed dial digits (2-9, 1 is reserved for voicemail)
        val SPEED_DIAL_DIGITS = listOf("2", "3", "4", "5", "6", "7", "8", "9")
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Observable speed dial entries for UI updates
    private val _speedDialEntries = MutableStateFlow<Map<String, SpeedDialEntry>>(emptyMap())
    val speedDialEntries: StateFlow<Map<String, SpeedDialEntry>> = _speedDialEntries.asStateFlow()

    init {
        loadEntries()
    }

    /**
     * Load all speed dial entries from SharedPreferences
     */
    private fun loadEntries() {
        val entries = mutableMapOf<String, SpeedDialEntry>()
        SPEED_DIAL_DIGITS.forEach { digit ->
            val number = prefs.getString("${KEY_PREFIX}${digit}_number", null)
            val name = prefs.getString("${KEY_PREFIX}${digit}_name", null)
            if (number != null) {
                entries[digit] = SpeedDialEntry(digit, number, name)
            }
        }
        _speedDialEntries.value = entries
    }

    /**
     * Get speed dial entry for a specific digit
     */
    fun getSpeedDial(digit: String): SpeedDialEntry? {
        return _speedDialEntries.value[digit]
    }

    /**
     * Check if a digit has a speed dial assigned
     */
    fun hasSpeedDial(digit: String): Boolean {
        return _speedDialEntries.value.containsKey(digit)
    }

    /**
     * Set speed dial for a digit
     */
    fun setSpeedDial(digit: String, phoneNumber: String, contactName: String? = null) {
        if (digit !in SPEED_DIAL_DIGITS) return

        prefs.edit().apply {
            putString("${KEY_PREFIX}${digit}_number", phoneNumber)
            if (contactName != null) {
                putString("${KEY_PREFIX}${digit}_name", contactName)
            } else {
                remove("${KEY_PREFIX}${digit}_name")
            }
            apply()
        }
        loadEntries() // Refresh
    }

    /**
     * Remove speed dial for a digit
     */
    fun removeSpeedDial(digit: String) {
        if (digit !in SPEED_DIAL_DIGITS) return

        prefs.edit().apply {
            remove("${KEY_PREFIX}${digit}_number")
            remove("${KEY_PREFIX}${digit}_name")
            apply()
        }
        loadEntries() // Refresh
    }

    /**
     * Clear all speed dial entries
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        _speedDialEntries.value = emptyMap()
    }

    /**
     * Get all configured speed dial entries
     */
    fun getAllEntries(): List<SpeedDialEntry> {
        return _speedDialEntries.value.values.toList()
    }
}

/**
 * Speed dial entry data class
 */
data class SpeedDialEntry(
    val digit: String,
    val phoneNumber: String,
    val contactName: String? = null
) {
    val displayName: String
        get() = contactName ?: phoneNumber
}
