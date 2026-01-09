package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.bitnextechnologies.bitnexdial.util.SpeedDialEntry
import com.bitnextechnologies.bitnexdial.util.SpeedDialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SpeedDialViewModel @Inject constructor(
    private val speedDialManager: SpeedDialManager
) : ViewModel() {

    /**
     * Observable speed dial entries map (digit -> entry)
     */
    val speedDialEntries: StateFlow<Map<String, SpeedDialEntry>> = speedDialManager.speedDialEntries

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

    /**
     * Get speed dial entry for a digit
     */
    fun getSpeedDial(digit: String): SpeedDialEntry? {
        return speedDialManager.getSpeedDial(digit)
    }

    /**
     * Clear all speed dial entries
     */
    fun clearAll() {
        speedDialManager.clearAll()
    }
}
