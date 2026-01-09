package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketEvent
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.domain.model.Conversation
import com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IMessageRepository
import com.bitnextechnologies.bitnexdial.data.repository.ContactRepository
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MessagesViewModel"

@HiltViewModel
class MessagesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepository: IMessageRepository,
    private val apiContactRepository: IContactRepository,
    private val deviceContactRepository: ContactRepository,
    private val socketManager: SocketManager,
    private val secureCredentialManager: SecureCredentialManager
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

    // Selected tab: 0 = All, 1 = Favorites
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    // Favorite contact phone numbers
    private val _favoriteContacts = MutableStateFlow<Set<String>>(emptySet())
    val favoriteContacts: StateFlow<Set<String>> = _favoriteContacts.asStateFlow()

    // Track which contact is currently being toggled (for loading indicator)
    private val _togglingFavoriteFor = MutableStateFlow<String?>(null)
    val togglingFavoriteFor: StateFlow<String?> = _togglingFavoriteFor.asStateFlow()

    // Snackbar message for user feedback
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    // Contact name lookup map from API contacts (normalized phone number -> contact name)
    // This provides contact name lookup like the web version's getContactName()
    private val contactNameMap: StateFlow<Map<String, String>> = apiContactRepository.getAllContacts()
        .map { contacts ->
            val nameMap = mutableMapOf<String, String>()
            contacts.forEach { contact ->
                // Only use contacts that have an actual name (not just phone number fallback)
                val name = contact.name?.takeIf { it.isNotBlank() }
                    ?: "${contact.firstName ?: ""} ${contact.lastName ?: ""}".trim().takeIf { it.isNotBlank() }

                if (name != null && contact.phoneNumbers.isNotEmpty()) {
                    // Add all phone numbers for this contact
                    contact.phoneNumbers.forEach { phone ->
                        val normalized = normalizePhoneNumber(phone)
                        if (normalized.isNotEmpty()) {
                            nameMap[normalized] = name
                        }
                    }
                }
            }
            Log.d(TAG, "Built contact name map with ${nameMap.size} entries")
            nameMap
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    // All conversations from repository
    private val allConversations: StateFlow<List<Conversation>> = messageRepository.getConversations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Filtered conversations based on selected tab
    // Enriches conversations with contact names from API contacts, falls back to device contacts
    // Deduplicates by normalized phone number to prevent duplicates
    val conversations: StateFlow<List<Conversation>> = combine(
        allConversations,
        _selectedTab,
        _favoriteContacts,
        contactNameMap
    ) { conversations, tab, favorites, nameMap ->
        Log.d(TAG, "Processing ${conversations.size} conversations, tab=$tab, favorites=${favorites.size}")

        // Deduplicate by normalized phone number (keep the one with most recent message)
        val deduplicatedConversations = conversations
            .groupBy { normalizePhoneNumber(it.phoneNumber) }
            .map { (_, group) ->
                // Keep the conversation with the most recent lastMessageTime
                group.maxByOrNull { it.lastMessageTime ?: 0L } ?: group.first()
            }

        Log.d(TAG, "After dedup: ${deduplicatedConversations.size} conversations")

        // Enrich conversations with contact names
        // Priority: 1) API contacts (nameMap), 2) Device contacts (deviceContactRepository)
        val enrichedConversations = deduplicatedConversations.map { conv ->
            if (conv.contactName.isNullOrBlank()) {
                val normalizedPhone = normalizePhoneNumber(conv.phoneNumber)
                // First try API contacts
                val lookedUpName = nameMap[normalizedPhone]
                    // Fallback to device contacts
                    ?: deviceContactRepository.getContactName(conv.phoneNumber)
                if (lookedUpName != null) {
                    conv.copy(contactName = lookedUpName)
                } else {
                    conv
                }
            } else {
                conv
            }
        }.sortedByDescending { it.lastMessageTime ?: 0L } // Sort by most recent first

        when (tab) {
            1 -> {
                Log.d(TAG, "Favorites filter: checking ${enrichedConversations.size} conversations against ${favorites.size} favorites")
                Log.d(TAG, "Favorites set: $favorites")
                val filtered = enrichedConversations.filter { conv ->
                    val normalizedPhone = normalizePhoneNumber(conv.phoneNumber)
                    val isFav = favorites.contains(normalizedPhone)
                    Log.d(TAG, "Checking ${conv.phoneNumber} -> normalized: $normalizedPhone -> isFav: $isFav")
                    isFav
                }
                Log.d(TAG, "Filtered result: ${filtered.size} favorites")
                filtered
            }
            else -> enrichedConversations
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val totalUnreadCount: StateFlow<Int> = messageRepository.getTotalUnreadCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    init {
        loadInitialData()
        loadFavorites()
        syncContacts() // Sync API contacts for name lookup in messages list
        observeSocketEvents()

        // Mark initial load complete when data arrives
        viewModelScope.launch {
            conversations.collect { conversationList ->
                if (conversationList.isNotEmpty() && !hasInitiallyLoaded) {
                    hasInitiallyLoaded = true
                    _isLoading.value = false
                    Log.d(TAG, "Initial messages loaded: ${conversationList.size} conversations")
                } else if (hasInitiallyLoaded) {
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
                messageRepository.syncMessages()
                hasInitiallyLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial messages", e)
                _error.value = e.message ?: "Failed to load messages"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Sync contacts from server to local database for name lookup
     */
    private fun syncContacts() {
        viewModelScope.launch {
            try {
                apiContactRepository.syncContacts()
                Log.d(TAG, "Contacts synced successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing contacts", e)
            }
        }
    }

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
    }

    /**
     * Check if a conversation is a favorite
     */
    fun isFavorite(phoneNumber: String): Boolean {
        return _favoriteContacts.value.contains(normalizePhoneNumber(phoneNumber))
    }

    /**
     * Toggle favorite status for a conversation.
     * Uses optimistic update for instant UI feedback with revert on failure.
     */
    fun toggleFavorite(phoneNumber: String) {
        val owner = getUserPhoneNumber()
        if (owner.isEmpty()) {
            Log.w(TAG, "toggleFavorite: Owner phone is empty")
            _snackbarMessage.value = "Unable to update favorite"
            return
        }

        val normalizedContact = normalizePhoneNumber(phoneNumber)

        // Prevent duplicate requests for same contact
        if (_togglingFavoriteFor.value == normalizedContact) {
            return
        }

        val wasAlreadyFavorite = _favoriteContacts.value.contains(normalizedContact)
        val newFavoriteState = !wasAlreadyFavorite

        // Set loading state
        _togglingFavoriteFor.value = normalizedContact

        // Optimistic update for instant UI feedback
        _favoriteContacts.update { favorites ->
            if (newFavoriteState) favorites + normalizedContact else favorites - normalizedContact
        }

        viewModelScope.launch {
            val result = messageRepository.toggleFavoriteChat(owner, phoneNumber)

            result.onSuccess { serverFavoriteState ->
                // Only update if server state differs from our optimistic update
                if (serverFavoriteState != newFavoriteState) {
                    _favoriteContacts.update { favorites ->
                        if (serverFavoriteState) favorites + normalizedContact else favorites - normalizedContact
                    }
                }
                Log.d(TAG, "toggleFavorite: Success, isFavorite=$serverFavoriteState")
            }.onFailure { error ->
                Log.e(TAG, "toggleFavorite: Failed", error)
                // Revert optimistic update
                _favoriteContacts.update { favorites ->
                    if (wasAlreadyFavorite) favorites + normalizedContact else favorites - normalizedContact
                }
                _snackbarMessage.value = "Failed to update favorite"
            }

            _togglingFavoriteFor.value = null
        }
    }

    /**
     * Load favorite contacts from repository.
     * Uses retry logic for cases where user phone is not available yet.
     */
    private fun loadFavorites() {
        viewModelScope.launch {
            var retries = 0
            val maxRetries = 5
            val retryDelayMs = 1000L

            while (retries < maxRetries) {
                val owner = getUserPhoneNumber()
                Log.d(TAG, "loadFavorites: attempt ${retries + 1}, owner=$owner")

                if (owner.isEmpty()) {
                    retries++
                    if (retries < maxRetries) {
                        Log.d(TAG, "loadFavorites: owner is empty, retrying in ${retryDelayMs}ms")
                        kotlinx.coroutines.delay(retryDelayMs)
                        continue
                    } else {
                        Log.w(TAG, "loadFavorites: owner still empty after $maxRetries retries")
                        return@launch
                    }
                }

                try {
                    val favorites = messageRepository.getFavoriteChats(owner).toSet()
                    _favoriteContacts.value = favorites
                    Log.d(TAG, "loadFavorites: Loaded ${favorites.size} favorites")
                    return@launch // Success, exit the loop
                } catch (e: Exception) {
                    Log.e(TAG, "loadFavorites: Error", e)
                    retries++
                    if (retries < maxRetries) {
                        kotlinx.coroutines.delay(retryDelayMs)
                    }
                }
            }
        }
    }

    /**
     * Public method to reload favorites (can be called after login)
     */
    fun reloadFavorites() {
        loadFavorites()
    }

    private fun getUserPhoneNumber(): String {
        return secureCredentialManager.getSenderPhone() ?: ""
    }

    /**
     * Normalize phone number for comparison - delegates to central utility (SSOT)
     */
    private fun normalizePhoneNumber(phone: String): String {
        return com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils.normalizeForComparison(phone)
    }

    /**
     * Listen to Socket.IO events for real-time SMS updates.
     * This is the professional event-driven approach matching the web version.
     * Uses targeted sync for efficiency instead of full refresh.
     */
    private fun observeSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.NewMessage -> {
                        Log.d(TAG, "New message received: ${event.messageId}")
                        // Sync just the affected conversation
                        handleNewMessage(event)
                    }
                    is SocketEvent.NewSms -> {
                        // New SMS event - matches web version's new_sms socket event
                        Log.d(TAG, "New SMS received from: ${event.from}, to: ${event.to}")
                        // Sync just the affected conversation
                        handleNewSms(event)
                    }
                    is SocketEvent.MessageDelivered -> {
                        Log.d(TAG, "Message delivered: ${event.messageId}")
                        // Sync the conversation to update status
                        syncConversationById(event.conversationId)
                    }
                    is SocketEvent.MessageRead -> {
                        Log.d(TAG, "Message read: ${event.messageId}")
                        // Sync the conversation to update read status
                        syncConversationById(event.conversationId)
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Handle new message event - sync the affected conversation
     */
    private fun handleNewMessage(event: SocketEvent.NewMessage) {
        viewModelScope.launch {
            try {
                if (event.conversationId.isNotEmpty()) {
                    messageRepository.syncMessagesForConversation(event.conversationId)
                    Log.d(TAG, "Synced conversation: ${event.conversationId}")
                } else {
                    // Fallback to full sync if no conversation ID
                    messageRepository.syncMessages()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing after new message", e)
            }
        }
    }

    /**
     * Handle new SMS event - find and sync the affected conversation
     * Matches web version's real-time SMS handling
     */
    private fun handleNewSms(event: SocketEvent.NewSms) {
        viewModelScope.launch {
            try {
                // Determine which phone number is the remote party
                val userPhone = getUserPhoneNumber()
                val normalizedUserPhone = normalizePhoneNumber(userPhone)
                val normalizedFrom = normalizePhoneNumber(event.from)

                // The remote party is whichever number is NOT our own
                val remoteNumber = if (normalizedFrom == normalizedUserPhone) {
                    event.to
                } else {
                    event.from
                }

                // Find conversation by phone number and sync it
                val conversations = allConversations.value
                val targetConversation = conversations.find { conv ->
                    normalizePhoneNumber(conv.phoneNumber) == normalizePhoneNumber(remoteNumber)
                }

                if (targetConversation != null) {
                    // Sync just this conversation
                    messageRepository.syncMessagesForConversation(targetConversation.id)
                    Log.d(TAG, "Synced conversation for: $remoteNumber")
                } else {
                    // New conversation - do a full sync to pick it up
                    messageRepository.syncMessages()
                    Log.d(TAG, "New conversation, full sync triggered")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling new SMS", e)
            }
        }
    }

    /**
     * Sync a specific conversation by ID
     */
    private fun syncConversationById(conversationId: String) {
        if (conversationId.isEmpty()) return

        viewModelScope.launch {
            try {
                messageRepository.syncMessagesForConversation(conversationId)
                Log.d(TAG, "Synced conversation: $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing conversation $conversationId", e)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                messageRepository.syncMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing messages", e)
                _error.value = e.message ?: "Failed to sync messages"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                messageRepository.deleteConversation(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting conversation", e)
                _error.value = e.message ?: "Failed to delete conversation"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
