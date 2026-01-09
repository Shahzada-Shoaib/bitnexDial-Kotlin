package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import com.bitnextechnologies.bitnexdial.domain.repository.SipRegistrationState
import com.bitnextechnologies.bitnexdial.util.Constants
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import com.bitnextechnologies.bitnexdial.util.SpeedDialEntry
import com.bitnextechnologies.bitnexdial.util.SpeedDialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

private const val PREF_LAST_DIALED_NUMBER = "last_dialed_number"

@HiltViewModel
class DialerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sipRepository: ISipRepository,
    private val speedDialManager: SpeedDialManager
) : ViewModel() {

    // SIP registration state for connection status indicator
    val sipRegistrationState: StateFlow<SipRegistrationState> = sipRepository.registrationState

    private val _phoneNumber = MutableStateFlow("")
    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()

    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()

    // Last dialed number for redial feature
    private val _lastDialedNumber = MutableStateFlow("")
    val lastDialedNumber: StateFlow<String> = _lastDialedNumber.asStateFlow()

    init {
        // Load last dialed number from SharedPreferences
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        _lastDialedNumber.value = prefs.getString(PREF_LAST_DIALED_NUMBER, "") ?: ""
    }

    /**
     * Save number as last dialed before making a call
     */
    fun saveAsLastDialed(number: String) {
        _lastDialedNumber.value = number
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LAST_DIALED_NUMBER, number).apply()
    }

    /**
     * Restore last dialed number to the dialpad (for double-tap redial)
     */
    fun restoreLastDialedNumber(): Boolean {
        val lastNumber = _lastDialedNumber.value
        return if (lastNumber.isNotEmpty()) {
            _phoneNumber.value = lastNumber
            true
        } else {
            false
        }
    }

    /**
     * Clear the dialpad (called after call ends)
     */
    fun onCallEnded() {
        _phoneNumber.value = ""
    }

    /**
     * Check if the dialed number is the user's own number
     */
    fun isOwnNumber(number: String): Boolean {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val ownNumber = prefs.getString(Constants.KEY_SENDER_PHONE, "") ?: ""
        val ownNormalized = ownNumber.replace(Regex("[^\\d]"), "").takeLast(10)
        val dialedNormalized = number.replace(Regex("[^\\d]"), "").takeLast(10)
        return ownNormalized.isNotEmpty() && ownNormalized == dialedNormalized
    }

    fun appendDigit(digit: String) {
        if (_phoneNumber.value.length < 20) {
            _phoneNumber.value += digit
        }
    }

    fun deleteLastDigit() {
        if (_phoneNumber.value.isNotEmpty()) {
            _phoneNumber.value = _phoneNumber.value.dropLast(1)
        }
    }

    fun clearNumber() {
        _phoneNumber.value = ""
    }

    fun setNumber(number: String) {
        _phoneNumber.value = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        _validationError.value = null
    }

    /**
     * Validate phone number before making a call.
     * Returns the normalized number if valid, null if invalid.
     */
    fun validateAndNormalize(number: String): String? {
        _validationError.value = null

        if (number.isBlank()) {
            _validationError.value = "Please enter a phone number"
            return null
        }

        val digitsOnly = number.replace(Regex("[^\\d]"), "")

        // Check minimum length
        if (digitsOnly.length < 3) {
            _validationError.value = "Phone number is too short"
            return null
        }

        // Allow special codes (like *67, #31#, etc.)
        if (number.startsWith("*") || number.startsWith("#")) {
            return number // Pass through special codes as-is
        }

        // Check if it's a valid phone number
        if (!PhoneNumberUtils.isValidNumber(number)) {
            // Still allow it if it looks like a reasonable number (7+ digits)
            if (digitsOnly.length < 7) {
                _validationError.value = "Invalid phone number"
                return null
            }
        }

        // Check if calling own number
        if (isOwnNumber(number)) {
            _validationError.value = "Cannot call your own number"
            return null
        }

        // Normalize the number for dialing
        return PhoneNumberUtils.normalizeNumber(number)
    }

    /**
     * Clear any validation error
     */
    fun clearError() {
        _validationError.value = null
    }

    // ========== SPEED DIAL ==========

    /**
     * Speed dial entries observable
     */
    val speedDialEntries: StateFlow<Map<String, SpeedDialEntry>> = speedDialManager.speedDialEntries

    /**
     * Get speed dial entry for a digit (2-9)
     */
    fun getSpeedDial(digit: String): SpeedDialEntry? {
        return speedDialManager.getSpeedDial(digit)
    }

    /**
     * Check if a digit has speed dial assigned
     */
    fun hasSpeedDial(digit: String): Boolean {
        return speedDialManager.hasSpeedDial(digit)
    }

    /**
     * Set speed dial for a digit
     */
    fun setSpeedDial(digit: String, phoneNumber: String, contactName: String? = null) {
        speedDialManager.setSpeedDial(digit, phoneNumber, contactName)
    }

    /**
     * Remove speed dial for a digit
     */
    fun removeSpeedDial(digit: String) {
        speedDialManager.removeSpeedDial(digit)
    }
}
