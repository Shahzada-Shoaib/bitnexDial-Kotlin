package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketEvent
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.domain.model.SipConfig
import com.bitnextechnologies.bitnexdial.domain.model.SipTransport
import com.bitnextechnologies.bitnexdial.data.preferences.ThemePreferences
import com.bitnextechnologies.bitnexdial.data.repository.ContactRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IAuthRepository
import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import com.bitnextechnologies.bitnexdial.domain.repository.SipRegistrationState
import com.bitnextechnologies.bitnexdial.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MainViewModel"

/**
 * Event data for launching the call UI
 */
data class CallUiEvent(
    val callId: String,
    val phoneNumber: String,
    val isIncoming: Boolean,
    val contactName: String? = null
)

/**
 * Event data for navigating to a specific conversation
 */
data class ConversationNavigationEvent(
    val conversationId: String,
    val contactName: String?
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: IAuthRepository,
    private val sipRepository: ISipRepository,
    private val socketManager: SocketManager,
    private val callRepository: com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository,
    private val messageRepository: com.bitnextechnologies.bitnexdial.domain.repository.IMessageRepository,
    private val badgeManager: com.bitnextechnologies.bitnexdial.util.BadgeManager,
    private val contactRepository: ContactRepository,
    themePreferences: ThemePreferences
) : ViewModel() {

    // Theme state - use Lazily to avoid blocking startup
    val darkModeEnabled: StateFlow<Boolean> = themePreferences.darkModeEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = false
        )

    val useSystemTheme: StateFlow<Boolean> = themePreferences.useSystemTheme
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = false
        )

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _currentTab = MutableStateFlow("dialer")
    val currentTab: StateFlow<String> = _currentTab.asStateFlow()

    private val _dialerNumber = MutableStateFlow("")
    val dialerNumber: StateFlow<String> = _dialerNumber.asStateFlow()

    // Event to trigger launching InCallActivity
    private val _callUiEvent = MutableSharedFlow<CallUiEvent>()
    val callUiEvent: SharedFlow<CallUiEvent> = _callUiEvent.asSharedFlow()

    // Event to trigger navigation to a specific conversation
    private val _conversationNavigationEvent = MutableSharedFlow<ConversationNavigationEvent>()
    val conversationNavigationEvent: SharedFlow<ConversationNavigationEvent> = _conversationNavigationEvent.asSharedFlow()

    val registrationState: StateFlow<SipRegistrationState> = sipRepository.registrationState

    val socketConnectionState = socketManager.connectionState

    // Badge counts for navigation - WhatsApp-style badges
    val missedCallCount: StateFlow<Int> = callRepository.getUnreadMissedCallCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val unreadMessageCount: StateFlow<Int> = messageRepository.getTotalUnreadCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    init {
        checkLoginStatus()
        observeAuthState()
        observeBadgeCounts()
        observeSocketEvents()
        observeSocketConnectionState()
    }

    /**
     * Observe socket connection state changes
     */
    private fun observeSocketConnectionState() {
        viewModelScope.launch {
            socketManager.connectionState.collect { state ->
                Log.d(TAG, "Socket state changed to: $state")
            }
        }
    }

    /**
     * Observe badge counts and update launcher icon badge (WhatsApp-style)
     */
    private fun observeBadgeCounts() {
        viewModelScope.launch {
            // Combine both counts and update launcher badge
            combine(missedCallCount, unreadMessageCount) { missed, messages ->
                Pair(missed, messages)
            }.collect { (missed, messages) ->
                badgeManager.updateBadgeCount(missed, messages)
            }
        }
    }

    /**
     * Global socket event observer for real-time updates.
     * This runs regardless of which screen is active.
     * Professional event-driven approach matching the web version.
     */
    private fun observeSocketEvents() {
        viewModelScope.launch {
            Log.d(TAG, "Starting socket events observer")
            socketManager.events.collect { event ->
                Log.d(TAG, "Received socket event: ${event.javaClass.simpleName}")
                when (event) {
                    is SocketEvent.NewSms -> {
                        Log.d(TAG, "New SMS from ${event.from} to ${event.to}")
                        handleNewSmsEvent(event)
                    }
                    is SocketEvent.NewMessage -> {
                        Log.d(TAG, "New message ${event.messageId}")
                        handleNewMessageEvent(event)
                    }
                    is SocketEvent.CallHistoryUpdate -> {
                        Log.d(TAG, "Call history update")
                        try {
                            callRepository.syncCallHistory()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error syncing call history", e)
                        }
                    }
                    is SocketEvent.Error -> {
                        Log.e(TAG, "Socket error: ${event.message}")
                    }
                    else -> { /* Other events handled by specific ViewModels */ }
                }
            }
        }
    }

    /**
     * Handle new SMS event from socket - sync and update badge
     */
    private fun handleNewSmsEvent(event: SocketEvent.NewSms) {
        viewModelScope.launch {
            try {
                // Sync messages to get the new SMS
                messageRepository.syncMessages()
                Log.d(TAG, "Synced messages after new SMS")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing after new SMS", e)
            }
        }
    }

    /**
     * Handle new message event from socket - sync and update badge
     */
    private fun handleNewMessageEvent(event: SocketEvent.NewMessage) {
        viewModelScope.launch {
            try {
                if (event.conversationId.isNotEmpty()) {
                    messageRepository.syncMessagesForConversation(event.conversationId)
                } else {
                    messageRepository.syncMessages()
                }
                Log.d(TAG, "Synced messages after new message event")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing after new message", e)
            }
        }
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.getCurrentUserFlow().collect { user ->
                val wasLoggedIn = _isLoggedIn.value
                val nowLoggedIn = user != null
                _isLoggedIn.value = nowLoggedIn

                // If user just logged in, sync data
                // Socket connection is now handled by SipForegroundService for persistence
                if (!wasLoggedIn && nowLoggedIn) {
                    Log.d(TAG, "User logged in, syncing data (socket managed by foreground service)")
                    syncDataAfterLogin()
                }
            }
        }
    }

    /**
     * Sync call history and messages after user logs in.
     * This runs in MainViewModel's scope which persists throughout the app session.
     */
    private fun syncDataAfterLogin() {
        viewModelScope.launch {
            // Load device contacts FIRST - required for showing names in call logs
            try {
                Log.d(TAG, "Loading device contacts...")
                contactRepository.loadContacts()
                Log.d(TAG, "Device contacts loaded!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load device contacts", e)
            }

            // Sync call history
            try {
                Log.d(TAG, "Syncing call history from server...")
                callRepository.syncCallHistory()
                Log.d(TAG, "Call history sync completed!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync call history", e)
            }

            // Sync messages
            try {
                Log.d(TAG, "Syncing messages from server...")
                messageRepository.syncMessages()
                Log.d(TAG, "Messages sync completed!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync messages", e)
            }
        }
    }

    private fun checkLoginStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Quick check - just see if we have a user stored
                val isLoggedIn = authRepository.isLoggedIn()
                _isLoggedIn.value = isLoggedIn

                // Set loading to false immediately so UI can render
                _isLoading.value = false

                // Defer network operations to after UI is ready
                if (isLoggedIn) {
                    // Token refresh done in background
                    // Socket connection is handled by SipForegroundService for persistence
                    Log.d(TAG, "User logged in, deferring network operations")
                    kotlinx.coroutines.delay(500) // Small delay to let UI render
                    try {
                        authRepository.refreshToken()
                    } catch (e: Exception) {
                        Log.w(TAG, "Token refresh failed", e)
                    }
                    // Register FCM token for push notifications
                    try {
                        Log.d(TAG, "Registering FCM token on app startup")
                        authRepository.registerFcmToken()
                    } catch (e: Exception) {
                        Log.w(TAG, "FCM token registration failed", e)
                    }
                    // Note: Socket connection now managed by SipForegroundService

                    // IMPORTANT: Sync data on app startup when already logged in
                    // This ensures unread badges are up-to-date from server
                    Log.d(TAG, "Syncing data on app startup...")
                    syncDataAfterLogin()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking login status", e)
                _isLoggedIn.value = false
                _isLoading.value = false
            }
        }
    }

    private suspend fun initializeSip() {
        try {
            Log.d(TAG, "initializeSip: Starting SIP initialization")
            sipRepository.initialize()

            // Get SIP credentials from SharedPreferences (saved during login)
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val sipUsername = prefs.getString(Constants.KEY_SIP_USERNAME, null)
            val sipPassword = prefs.getString(Constants.KEY_SIP_PASSWORD, null)
            val sipServer = prefs.getString(Constants.KEY_SIP_SERVER, null)
            val sipPort = prefs.getInt(Constants.KEY_SIP_PORT, 8089)
            val sipPath = prefs.getString(Constants.KEY_SIP_PATH, "/ws")

            Log.d(TAG, "initializeSip: SIP credentials - username=$sipUsername, server=$sipServer, port=$sipPort")

            if (!sipUsername.isNullOrBlank() && !sipPassword.isNullOrBlank() && !sipServer.isNullOrBlank()) {
                val sipConfig = SipConfig(
                    username = sipUsername,
                    password = sipPassword,
                    domain = sipServer,
                    server = sipServer,
                    port = sipPort.toString(),
                    transport = SipTransport.WSS,
                    path = sipPath ?: "/ws"
                )
                Log.d(TAG, "initializeSip: Registering with SIP config")
                sipRepository.register(sipConfig)
                Log.d(TAG, "initializeSip: SIP registration initiated")
            } else {
                Log.w(TAG, "initializeSip: Missing SIP credentials, cannot register")
            }

            // Note: Socket connection now managed by SipForegroundService for persistence
        } catch (e: Exception) {
            Log.e(TAG, "initializeSip: SIP initialization failed", e)
            // SIP initialization failed - user can still use app
        }
    }

    fun navigateToTab(tab: String) {
        _currentTab.value = tab
    }

    fun setDialerNumber(number: String) {
        _dialerNumber.value = number
        _currentTab.value = "dialer"
    }

    /**
     * Navigate to a specific conversation (from notification click)
     */
    fun navigateToConversation(conversationId: String, contactName: String?) {
        viewModelScope.launch {
            Log.d(TAG, "Navigating to conversation: $conversationId, contactName: $contactName")
            _conversationNavigationEvent.emit(ConversationNavigationEvent(conversationId, contactName))
        }
    }

    fun makeCall(phoneNumber: String, contactName: String? = null) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "makeCall: Initiating call to $phoneNumber, contactName=$contactName")
                val callId = sipRepository.makeCall(phoneNumber)
                Log.d(TAG, "makeCall: Call initiated with id $callId, emitting UI event")

                // Emit event to launch InCallActivity
                _callUiEvent.emit(CallUiEvent(
                    callId = callId,
                    phoneNumber = phoneNumber,
                    isIncoming = false,
                    contactName = contactName
                ))
            } catch (e: Exception) {
                Log.e(TAG, "makeCall: Failed to initiate call", e)
                // Still emit call UI event so user sees the call attempt
                // The InCallActivity will show the error state
                val fallbackCallId = "call_${System.currentTimeMillis()}"
                _callUiEvent.emit(CallUiEvent(
                    callId = fallbackCallId,
                    phoneNumber = phoneNumber,
                    isIncoming = false,
                    contactName = contactName
                ))
            }
        }
    }

    fun onLogout() {
        viewModelScope.launch {
            try {
                socketManager.disconnect()
                sipRepository.unregister()
                authRepository.logout()
            } catch (e: Exception) {
                Log.w(TAG, "Error during logout, continuing with local cleanup", e)
            } finally {
                // Always mark as logged out locally
                _isLoggedIn.value = false
            }
        }
    }

    fun onLoginSuccess() {
        // Note: Sync is now handled automatically by observeAuthState() when user becomes logged in
        // This method is kept for backward compatibility with navigation callbacks
        viewModelScope.launch {
            _isLoggedIn.value = true
            initializeSip()
        }
    }
}
