package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.presentation.screens.BlockedNumber
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BlockedNumbersViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs: SharedPreferences = context.getSharedPreferences("blocked_numbers", Context.MODE_PRIVATE)

    private val _blockedNumbers = MutableStateFlow<List<BlockedNumber>>(emptyList())
    val blockedNumbers: StateFlow<List<BlockedNumber>> = _blockedNumbers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadBlockedNumbers()
    }

    private fun loadBlockedNumbers() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val json = prefs.getString("blocked_list", "[]") ?: "[]"
                val jsonArray = JSONArray(json)
                val numbers = mutableListOf<BlockedNumber>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    numbers.add(
                        BlockedNumber(
                            id = obj.getString("id"),
                            phoneNumber = obj.getString("phoneNumber"),
                            blockedAt = obj.getLong("blockedAt"),
                            name = obj.optString("name", null)
                        )
                    )
                }

                _blockedNumbers.value = numbers.sortedByDescending { it.blockedAt }
            } catch (e: Exception) {
                _blockedNumbers.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun saveBlockedNumbers() {
        val jsonArray = JSONArray()
        _blockedNumbers.value.forEach { blocked ->
            val obj = JSONObject().apply {
                put("id", blocked.id)
                put("phoneNumber", blocked.phoneNumber)
                put("blockedAt", blocked.blockedAt)
                blocked.name?.let { put("name", it) }
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("blocked_list", jsonArray.toString()).apply()
    }

    fun blockNumber(phoneNumber: String, name: String? = null) {
        viewModelScope.launch {
            // Check if already blocked
            if (_blockedNumbers.value.any { it.phoneNumber == phoneNumber }) {
                return@launch
            }

            val newBlocked = BlockedNumber(
                id = UUID.randomUUID().toString(),
                phoneNumber = phoneNumber,
                blockedAt = System.currentTimeMillis(),
                name = name
            )

            _blockedNumbers.value = listOf(newBlocked) + _blockedNumbers.value
            saveBlockedNumbers()
        }
    }

    fun unblockNumber(id: String) {
        viewModelScope.launch {
            _blockedNumbers.value = _blockedNumbers.value.filter { it.id != id }
            saveBlockedNumbers()
        }
    }

    fun isNumberBlocked(phoneNumber: String): Boolean {
        return _blockedNumbers.value.any { it.phoneNumber == phoneNumber }
    }
}
