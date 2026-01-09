package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketEvent
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.data.repository.ContactRepository
import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.CallDirection
import com.bitnextechnologies.bitnexdial.domain.model.CallType
import com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "RecentsViewModel"

@HiltViewModel
class RecentsViewModel @Inject constructor(
    private val callRepository: ICallRepository,
    private val socketManager: SocketManager,
    private val apiContactRepository: IContactRepository,
    private val deviceContactRepository: ContactRepository
) : ViewModel() {

    // Track if initial data load is complete - prevents showing loading on subsequent visits
    private var hasInitiallyLoaded = false

    private val _isLoading = MutableStateFlow(true) // Start true only for first load
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Separate refresh state for pull-to-refresh indicator
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Selection mode state for bulk deletion
    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedCallIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedCallIds: StateFlow<Set<String>> = _selectedCallIds.asStateFlow()

    val selectedCount: StateFlow<Int> = _selectedCallIds.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Pagination state
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreCalls = MutableStateFlow(true)
    val hasMoreCalls: StateFlow<Boolean> = _hasMoreCalls.asStateFlow()

    private var currentOffset = 0
    private val pageSize = 50

    // Selected filter: "all", "outgoing", "incoming", "missed"
    private val _selectedFilter = MutableStateFlow("all")
    val selectedFilter: StateFlow<String> = _selectedFilter.asStateFlow()

    // Track active call IDs for optimistic updates
    private val activeCallIds = mutableSetOf<String>()

    // Contact name lookup map from API contacts (normalized phone number -> contact name)
    // This provides contact name lookup like the web version's getContactName()
    private val contactNameMap: StateFlow<Map<String, String>> = apiContactRepository.getAllContacts()
        .map { contacts ->
            val nameMap = mutableMapOf<String, String>()
            contacts.forEach { contact ->
                // Only use contacts that have an actual name (not just phone number fallback)
                val name = contact.name
                if (!name.isNullOrBlank() && !name.all { it.isDigit() || it == '+' || it == '-' || it == '(' || it == ')' || it == ' ' }) {
                    // Map all phone numbers to this contact name
                    contact.phoneNumbers.forEach { phoneNumber ->
                        val normalized = PhoneNumberUtils.normalizeForComparison(phoneNumber)
                        if (normalized.isNotBlank()) {
                            nameMap[normalized] = name
                        }
                    }
                }
            }
            nameMap
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    // All calls from repository
    // Use Eagerly to ensure the Flow is always active and receives updates
    // even when the user is on other screens (like InCallActivity)
    private val allCallsRaw: StateFlow<List<Call>> = callRepository.getRecentCalls(100)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    // Calls with contact names resolved from both API contacts and device contacts
    private val allCalls: StateFlow<List<Call>> = combine(
        allCallsRaw,
        contactNameMap
    ) { calls, nameMap ->
        calls.map { call ->
            // If call already has a contact name, keep it
            if (!call.contactName.isNullOrBlank()) {
                call
            } else {
                // Look up contact name from API contacts first, then device contacts
                val normalizedPhone = PhoneNumberUtils.normalizeForComparison(call.phoneNumber)
                val lookedUpName = nameMap[normalizedPhone]
                    // Fallback to device contacts
                    ?: deviceContactRepository.getContactName(call.phoneNumber)
                if (lookedUpName != null) {
                    call.copy(contactName = lookedUpName)
                } else {
                    call
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    // Filtered calls based on selected filter
    // Matches web version logic:
    // - INCOMING tab shows ALL inbound calls (answered + missed)
    // - MISSED tab shows only missed calls
    // - OUTGOING tab shows outgoing calls
    val calls: StateFlow<List<Call>> = combine(
        allCalls,
        _selectedFilter
    ) { calls, filter ->
        when (filter) {
            "outgoing" -> calls.filter { it.direction == CallDirection.OUTGOING }
            "incoming" -> calls.filter { it.direction == CallDirection.INCOMING } // ALL inbound calls
            "missed" -> calls.filter { it.type == CallType.MISSED }
            else -> calls
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val unreadMissedCount: StateFlow<Int> = callRepository.getUnreadMissedCallCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0
        )

    init {
        loadInitialData()
        observeSocketEvents()

        // Mark initial load complete when data arrives
        viewModelScope.launch {
            allCalls.collect { calls ->
                if (calls.isNotEmpty() && !hasInitiallyLoaded) {
                    hasInitiallyLoaded = true
                    _isLoading.value = false
                    Log.d(TAG, "Initial data loaded: ${calls.size} calls")
                } else if (hasInitiallyLoaded) {
                    // Ensure loading is false after initial load
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Load initial data - only shows loading indicator on first app launch
     */
    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                callRepository.syncCallHistory()
                hasInitiallyLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial data", e)
                _error.value = e.message ?: "Failed to load call history"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setFilter(filter: String) {
        Log.d(TAG, "setFilter: $filter")
        _selectedFilter.value = filter
        // Reset pagination when filter changes
        currentOffset = 0
        _hasMoreCalls.value = true

        // Sync calls for this filter from API in background - no loading indicator
        // Cached data is shown immediately, new data flows in via Room
        viewModelScope.launch {
            _error.value = null
            try {
                callRepository.syncCallHistoryWithFilter(filter)
                currentOffset = 100 // Initial load is 100 items

                // When user views the missed calls tab, mark all as read
                // This clears the badge (WhatsApp-style behavior)
                if (filter == "missed") {
                    callRepository.markAllMissedAsRead()
                    Log.d(TAG, "Marked all missed calls as read")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing $filter calls", e)
                // Only show error if it's not a transient network issue
            }
        }
    }

    /**
     * Load more call history for pagination (triggered by scroll to bottom)
     */
    fun loadMoreCalls() {
        if (_isLoadingMore.value || !_hasMoreCalls.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val hasMore = callRepository.loadMoreCalls(
                    offset = currentOffset,
                    limit = pageSize,
                    filter = _selectedFilter.value
                )
                _hasMoreCalls.value = hasMore
                if (hasMore) {
                    currentOffset += pageSize
                }
                Log.d(TAG, "loadMoreCalls: offset=$currentOffset, hasMore=$hasMore")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more calls", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Listen to Socket.IO events for real-time UI updates.
     *
     * ARCHITECTURE NOTE: This ViewModel is OBSERVER-ONLY for call history.
     * - InCallViewModel is the SINGLE SOURCE OF TRUTH for saving calls to database
     * - This ViewModel only observes the database Flow for UI updates
     * - Socket events are used only for tracking active calls (UI indicators)
     * - This prevents duplicate saves and ensures clean data flow
     */
    private fun observeSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.CallStarted -> {
                        // Track active call for UI purposes only (e.g., showing "in call" indicator)
                        Log.d(TAG, "CallStarted event: ${event.number} (${event.direction}) - tracking only, no save")
                        activeCallIds.add(event.callId)
                    }
                    is SocketEvent.CallEndedWithMetrics -> {
                        // Remove from active tracking - InCallViewModel handles the actual save
                        Log.d(TAG, "CallEndedWithMetrics event: ${event.number}, duration=${event.duration}s - tracking only")
                        activeCallIds.remove(event.callId)
                    }
                    is SocketEvent.CallHistoryUpdate -> {
                        // Server confirmed call - just log, database will be updated by sync
                        Log.d(TAG, "CallHistoryUpdate event: ${event.callId} - observing only")
                    }
                    is SocketEvent.CallEnded -> {
                        // Remove from active tracking
                        activeCallIds.remove(event.callId)
                    }
                    is SocketEvent.IncomingCall -> {
                        // Track incoming call for UI purposes
                        Log.d(TAG, "IncomingCall event: ${event.callerNumber} - tracking only")
                        val callId = event.callId.ifEmpty { "call_${System.currentTimeMillis()}" }
                        activeCallIds.add(callId)
                    }
                    else -> {
                        // Ignore other events
                    }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                callRepository.syncCallHistory()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to sync call history"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun deleteCall(callId: String) {
        viewModelScope.launch {
            try {
                callRepository.deleteCall(callId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to delete call"
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                callRepository.clearCallHistory()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to clear history"
            }
        }
    }

    fun markAsRead(callId: String) {
        viewModelScope.launch {
            try {
                callRepository.markAsRead(callId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to mark as read"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Update notes for a call
     */
    fun updateNotes(callId: String, notes: String?) {
        viewModelScope.launch {
            try {
                callRepository.updateNotes(callId, notes)
                Log.d(TAG, "Notes updated for call $callId")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating notes", e)
                _error.value = "Failed to save notes"
            }
        }
    }

    // ==================== Selection Mode Functions ====================

    /**
     * Enter selection mode (triggered by long press on a call item)
     */
    fun enterSelectionMode(initialCallId: String? = null) {
        _isSelectionMode.value = true
        if (initialCallId != null) {
            _selectedCallIds.value = setOf(initialCallId)
        }
        Log.d(TAG, "Entered selection mode with initial: $initialCallId")
    }

    /**
     * Exit selection mode and clear all selections
     */
    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedCallIds.value = emptySet()
        Log.d(TAG, "Exited selection mode")
    }

    /**
     * Toggle selection of a call (add/remove from selected set)
     */
    fun toggleCallSelection(callId: String) {
        val current = _selectedCallIds.value.toMutableSet()
        if (current.contains(callId)) {
            current.remove(callId)
            // Auto-exit selection mode if no items selected
            if (current.isEmpty()) {
                exitSelectionMode()
                return
            }
        } else {
            current.add(callId)
        }
        _selectedCallIds.value = current
        Log.d(TAG, "Toggled selection for $callId, total selected: ${current.size}")
    }

    /**
     * Select all visible calls in current filter
     */
    fun selectAllCalls() {
        val allVisibleIds = calls.value.map { it.id }.toSet()
        _selectedCallIds.value = allVisibleIds
        Log.d(TAG, "Selected all ${allVisibleIds.size} calls")
    }

    /**
     * Check if a call is selected
     */
    fun isCallSelected(callId: String): Boolean {
        return _selectedCallIds.value.contains(callId)
    }

    /**
     * Delete all selected calls using bulk delete API
     */
    fun deleteSelectedCalls() {
        val idsToDelete = _selectedCallIds.value.toList()
        if (idsToDelete.isEmpty()) return

        viewModelScope.launch {
            try {
                Log.d(TAG, "Deleting ${idsToDelete.size} selected calls")
                callRepository.deleteCallsBulk(idsToDelete)
                exitSelectionMode()
                Log.d(TAG, "Successfully deleted ${idsToDelete.size} calls")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting selected calls", e)
                _error.value = "Failed to delete selected calls"
            }
        }
    }
}
