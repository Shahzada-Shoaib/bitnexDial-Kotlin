package com.bitnextechnologies.bitnexdial.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.WebView
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.Gravity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitnextechnologies.bitnexdial.R
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import com.bitnextechnologies.bitnexdial.data.sip.SipCallManager
import com.bitnextechnologies.bitnexdial.data.sip.SipEngine
import com.bitnextechnologies.bitnexdial.data.sip.WebViewSipBridge
import com.bitnextechnologies.bitnexdial.domain.model.SipConfig
import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import com.bitnextechnologies.bitnexdial.domain.repository.SipRegistrationState
import com.bitnextechnologies.bitnexdial.service.telecom.PhoneAccountManager
import com.bitnextechnologies.bitnexdial.util.BadgeManager
import com.bitnextechnologies.bitnexdial.util.Constants
import com.bitnextechnologies.bitnexdial.util.NetworkChangeEvent
import com.bitnextechnologies.bitnexdial.util.NetworkStateManager
import com.bitnextechnologies.bitnexdial.util.ServiceKeepAliveManager
import com.bitnextechnologies.bitnexdial.domain.repository.IAuthRepository
import com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IMessageRepository
import com.bitnextechnologies.bitnexdial.data.local.dao.VoicemailDao
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketConnectionState
import com.bitnextechnologies.bitnexdial.util.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Foreground service for maintaining SIP connection
 * Keeps the app alive in background for receiving calls
 */
@AndroidEntryPoint
class SipForegroundService : Service() {

    companion object {
        private const val TAG = "SipForegroundService"
        private const val NOTIFICATION_ID = 100
        private const val WAKE_LOCK_TAG = "BitNexDial:SipWakeLock"

        // Actions
        const val ACTION_START = "com.bitnextechnologies.bitnexdial.action.START_SIP"
        const val ACTION_STOP = "com.bitnextechnologies.bitnexdial.action.STOP_SIP"
        const val ACTION_REGISTER = "com.bitnextechnologies.bitnexdial.action.REGISTER"
        const val ACTION_UNREGISTER = "com.bitnextechnologies.bitnexdial.action.UNREGISTER"
        const val ACTION_RECONNECT = "com.bitnextechnologies.bitnexdial.action.RECONNECT"
        const val ACTION_REGISTER_NOW = "com.bitnextechnologies.bitnexdial.action.REGISTER_NOW"

        /**
         * Trigger SIP registration after login
         */
        fun registerNow(context: Context) {
            val intent = Intent(context, SipForegroundService::class.java).apply {
                action = ACTION_REGISTER_NOW
            }
            context.startService(intent)
        }

        /**
         * Trigger SIP unregistration before logout.
         * Sends a proper SIP UNREGISTER request to cleanly disconnect from the server.
         */
        fun unregisterNow(context: Context) {
            val intent = Intent(context, SipForegroundService::class.java).apply {
                action = ACTION_UNREGISTER
            }
            context.startService(intent)
        }

        // Service state
        private var isRunning = false
        fun isServiceRunning(): Boolean = isRunning

        /**
         * Start the foreground service
         */
        fun start(context: Context) {
            val intent = Intent(context, SipForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop the foreground service
         */
        fun stop(context: Context) {
            val intent = Intent(context, SipForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject
    lateinit var sipEngine: SipEngine

    @Inject
    lateinit var sipCallManager: SipCallManager

    @Inject
    lateinit var phoneAccountManager: PhoneAccountManager

    @Inject
    lateinit var webViewSipBridge: WebViewSipBridge

    @Inject
    lateinit var secureCredentialManager: SecureCredentialManager

    @Inject
    lateinit var networkStateManager: NetworkStateManager

    @Inject
    lateinit var badgeManager: BadgeManager

    @Inject
    lateinit var callRepository: ICallRepository

    @Inject
    lateinit var messageRepository: IMessageRepository

    @Inject
    lateinit var voicemailDao: VoicemailDao

    @Inject
    lateinit var socketManager: SocketManager

    @Inject
    lateinit var authRepository: IAuthRepository

    @Inject
    lateinit var permissionManager: PermissionManager

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var currentSipConfig: SipConfig? = null
    private var registrationJob: Job? = null
    private var healthCheckJob: Job? = null

    // Service-managed WebView for persistent SIP connection
    private var sipWebView: WebView? = null
    private var isWebViewInitialized = false

    // Connection monitoring - NEVER give up on reconnection
    private var consecutiveFailures = 0
    private val healthCheckIntervalMs = 5 * 60 * 1000L // 5 minutes
    private var networkMonitoringJob: Job? = null
    private var badgeMonitoringJob: Job? = null
    private var isReconnecting = false
    private var lastReconnectAttempt = 0L
    private val minReconnectIntervalMs = 5000L // Minimum 5 seconds between reconnect attempts

    // Socket connection monitoring - persistent like SIP
    private var socketHealthCheckJob: Job? = null
    private var socketMonitoringJob: Job? = null
    private var consecutiveSocketFailures = 0
    private var isSocketReconnecting = false
    private var lastSocketReconnectAttempt = 0L

    inner class LocalBinder : Binder() {
        fun getService(): SipForegroundService = this@SipForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== SipForegroundService onCreate ===")

        try {
            isRunning = true

            // CRITICAL: Start foreground IMMEDIATELY to avoid ANR
            // Must be called within 5 seconds of startForegroundService()
            startForegroundWithNotification()

            // Schedule keepalive alarm - professional WhatsApp-like approach
            // This ensures service restarts even if killed by system
            try {
                ServiceKeepAliveManager.scheduleKeepalive(this)
                Log.d(TAG, "Keepalive alarm scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule keepalive, non-critical", e)
            }

            // Do heavy initialization in background to avoid ANR
            serviceScope.launch {
                initializeServiceAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate, stopping service gracefully", e)
            isRunning = false
            // Don't crash - just stop the service
            stopSelf()
        }
    }

    /**
     * Async initialization to avoid blocking main thread
     * All operations here are non-critical - the service should continue even if they fail
     *
     * IMPORTANT: This is designed to be very lightweight initially.
     * Heavy operations (WebView) are only done when we have credentials.
     */
    private suspend fun initializeServiceAsync() {
        try {
            Log.d(TAG, "=== initializeServiceAsync started ===")

            // Setup professional network monitoring using NetworkStateManager
            try {
                networkStateManager.startMonitoring()
                startNetworkChangeMonitoring()
                Log.d(TAG, "Network state monitoring started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup network monitoring", e)
            }

            // Check if we have credentials - only then do heavy initialization
            val hasCredentials = secureCredentialManager.hasSipCredentials()
            Log.d(TAG, "Has SIP credentials: $hasCredentials")

            if (hasCredentials) {
                // Wait for network to be available (important after boot)
                val networkAvailable = waitForNetwork()
                Log.d(TAG, "Network available: $networkAvailable")

                if (networkAvailable) {
                    // Do full initialization with WebView
                    initializeFullSipStack()
                } else {
                    Log.w(TAG, "Network not available after waiting, will retry when network connects")
                    monitorRegistrationState()
                }
            } else {
                Log.d(TAG, "No stored SIP credentials found, waiting for login")
                // Monitor for credentials and initialize when available
                monitorRegistrationState()
            }

            // Start periodic health check
            startHealthCheck()

            // Start badge monitoring for WhatsApp-style launcher badges
            // This ensures badges are updated even when app is in background
            startBadgeMonitoring()

            // Initialize socket for real-time SMS updates (persistent like SIP)
            // This ensures socket stays connected even when app is closed
            initializeSocket()

        } catch (e: Exception) {
            Log.e(TAG, "Error during service initialization", e)
        }
    }

    /**
     * Monitor badge counts and update launcher icon badge
     * This runs in the foreground service so badges persist when app is closed
     */
    private fun startBadgeMonitoring() {
        badgeMonitoringJob?.cancel()
        badgeMonitoringJob = serviceScope.launch {
            try {
                combine(
                    callRepository.getUnreadMissedCallCount(),
                    messageRepository.getTotalUnreadCount(),
                    voicemailDao.getUnreadVoicemailCount()
                ) { missedCalls, unreadMessages, unreadVoicemails ->
                    Triple(missedCalls, unreadMessages, unreadVoicemails)
                }.collectLatest { (missedCalls, unreadMessages, unreadVoicemails) ->
                    Log.d(TAG, "Badge update: missedCalls=$missedCalls, messages=$unreadMessages, voicemails=$unreadVoicemails")
                    badgeManager.updateBadgeCount(missedCalls, unreadMessages, unreadVoicemails)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error monitoring badge counts", e)
            }
        }
    }

    // ==================== Socket Connection Management ====================

    /**
     * Initialize and connect socket for real-time SMS/messaging updates.
     * This runs in the foreground service so socket stays connected even when app is closed.
     * Similar to how SIP connection is maintained.
     */
    private fun initializeSocket() {
        serviceScope.launch {
            try {
                Log.d(TAG, "🔌 Initializing persistent socket connection...")

                // Get auth token
                val authToken = authRepository.getAuthToken()
                if (authToken == null) {
                    Log.w(TAG, "🔌 No auth token available, socket will connect after login")
                    return@launch
                }

                // Connect socket
                Log.d(TAG, "🔌 Connecting socket with auth token...")
                socketManager.connect(authToken)

                // Get and register phone number
                val rawPhoneNumber = secureCredentialManager.getSenderPhone()
                    ?: getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(Constants.KEY_SENDER_PHONE, null)

                val cleanPhoneNumber = rawPhoneNumber
                    ?.replace(Regex("[^\\d]"), "")
                    ?.removePrefix("1")
                    ?.takeLast(10)

                if (!cleanPhoneNumber.isNullOrBlank() && cleanPhoneNumber.length == 10) {
                    Log.d(TAG, "🔌 Registering phone number with socket: $cleanPhoneNumber")
                    socketManager.registerPhoneNumber(cleanPhoneNumber)
                } else {
                    Log.w(TAG, "🔌 Invalid phone number format, cannot register with socket")
                }

                // Start socket health monitoring
                startSocketHealthCheck()
                startSocketStateMonitoring()

                Log.d(TAG, "🔌 Socket initialization complete")
            } catch (e: Exception) {
                Log.e(TAG, "🔌 Error initializing socket", e)
                consecutiveSocketFailures++
            }
        }
    }

    /**
     * Start monitoring socket connection state changes
     */
    private fun startSocketStateMonitoring() {
        socketMonitoringJob?.cancel()
        socketMonitoringJob = serviceScope.launch {
            socketManager.connectionState.collectLatest { state ->
                Log.d(TAG, "🔌 Socket state changed: $state")
                when (state) {
                    SocketConnectionState.CONNECTED -> {
                        consecutiveSocketFailures = 0
                        Log.d(TAG, "🔌 Socket connected successfully")
                    }
                    SocketConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "🔌 Socket disconnected, will attempt reconnection")
                        // Reconnection handled by health check
                    }
                    SocketConnectionState.ERROR -> {
                        Log.e(TAG, "🔌 Socket error, will attempt reconnection")
                        consecutiveSocketFailures++
                    }
                    else -> { /* CONNECTING, RECONNECTING - transient states */ }
                }
            }
        }
    }

    /**
     * Periodic health check to ensure socket stays connected
     * Uses same interval as SIP health check
     */
    private fun startSocketHealthCheck() {
        socketHealthCheckJob?.cancel()
        socketHealthCheckJob = serviceScope.launch {
            while (isActive) {
                delay(healthCheckIntervalMs)
                checkSocketHealth()
            }
        }
    }

    /**
     * Check socket health and reconnect if needed
     * NEVER gives up - uses exponential backoff like SIP
     */
    private fun checkSocketHealth() {
        val isConnected = socketManager.isConnected()
        val isNetworkAvailable = networkStateManager.isNetworkAvailable()

        Log.d(TAG, "🔌 Socket health check: connected=$isConnected, network=$isNetworkAvailable, failures=$consecutiveSocketFailures")

        if (!isConnected && isNetworkAvailable) {
            // Socket disconnected but network available - reconnect
            handleSocketReconnection(reason = "health_check")
        } else if (isConnected) {
            consecutiveSocketFailures = 0
        }
    }

    /**
     * Handle socket reconnection with debouncing and exponential backoff
     */
    private fun handleSocketReconnection(reason: String) {
        val now = System.currentTimeMillis()

        // Debounce rapid reconnection attempts
        if (now - lastSocketReconnectAttempt < minReconnectIntervalMs) {
            Log.d(TAG, "🔌 Debouncing socket reconnection (too soon)")
            return
        }

        if (isSocketReconnecting) {
            Log.d(TAG, "🔌 Socket already reconnecting, ignoring request")
            return
        }

        lastSocketReconnectAttempt = now
        isSocketReconnecting = true

        serviceScope.launch {
            try {
                Log.d(TAG, "🔌 Initiating socket reconnection due to: $reason")

                // Calculate backoff delay
                val backoffDelay = minOf(
                    5000L * (1 shl minOf(consecutiveSocketFailures, 6)),
                    5 * 60 * 1000L // Max 5 minutes
                )

                if (consecutiveSocketFailures > 0) {
                    Log.d(TAG, "🔌 Waiting ${backoffDelay/1000}s before reconnect (failure #$consecutiveSocketFailures)")
                    delay(backoffDelay)
                }

                // Get auth token
                val authToken = authRepository.getAuthToken()
                if (authToken == null) {
                    Log.w(TAG, "🔌 No auth token for socket reconnection")
                    isSocketReconnecting = false
                    return@launch
                }

                // Disconnect and reconnect
                socketManager.disconnect()
                delay(500)
                socketManager.connect(authToken)

                // Re-register phone number
                val rawPhoneNumber = secureCredentialManager.getSenderPhone()
                    ?: getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(Constants.KEY_SENDER_PHONE, null)

                val cleanPhoneNumber = rawPhoneNumber
                    ?.replace(Regex("[^\\d]"), "")
                    ?.removePrefix("1")
                    ?.takeLast(10)

                if (!cleanPhoneNumber.isNullOrBlank() && cleanPhoneNumber.length == 10) {
                    delay(1000) // Wait for connection to establish
                    socketManager.registerPhoneNumber(cleanPhoneNumber)
                }

                // Wait and verify connection
                delay(3000)
                if (socketManager.isConnected()) {
                    Log.d(TAG, "🔌 Socket reconnected successfully")
                    consecutiveSocketFailures = 0
                } else {
                    Log.w(TAG, "🔌 Socket reconnection timeout")
                    consecutiveSocketFailures++
                }

            } catch (e: Exception) {
                Log.e(TAG, "🔌 Error during socket reconnection", e)
                consecutiveSocketFailures++
            } finally {
                isSocketReconnecting = false
            }
        }
    }

    /**
     * Wait for network to become available (with timeout)
     * Useful after boot when network might take a few seconds to connect
     */
    private suspend fun waitForNetwork(): Boolean {
        val maxWaitTimeMs = 30000L // 30 seconds max wait
        val checkIntervalMs = 1000L // Check every second
        var elapsedMs = 0L

        while (elapsedMs < maxWaitTimeMs) {
            if (networkStateManager.isNetworkAvailable()) {
                Log.d(TAG, "Network available after ${elapsedMs}ms")
                return true
            }
            delay(checkIntervalMs)
            elapsedMs += checkIntervalMs

            if (elapsedMs % 5000L == 0L) {
                Log.d(TAG, "Waiting for network... ${elapsedMs / 1000}s elapsed")
            }
        }

        Log.w(TAG, "Network not available after ${maxWaitTimeMs / 1000}s")
        return false
    }

    /**
     * Full SIP stack initialization - only called when we have credentials
     * All heavy operations are done with proper coroutine dispatchers
     */
    private suspend fun initializeFullSipStack() {
        try {
            Log.d(TAG, "=== initializeFullSipStack started ===")

            // Try to register phone account (lightweight)
            try {
                val registered = phoneAccountManager.registerPhoneAccount()
                if (!registered) {
                    Log.w(TAG, "Phone account not registered - will retry when permissions granted")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering phone account - continuing without it", e)
            }

            // Preload JsSIP script on IO thread (non-blocking)
            withContext(Dispatchers.IO) {
                try {
                    webViewSipBridge.preloadScript()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to preload JsSIP script", e)
                }
            }

            // Initialize WebView - this suspends until complete without blocking main thread
            initializeServiceWebView()

            // Monitor registration state
            monitorRegistrationState()

            // Auto-register with stored credentials
            autoRegisterWithStoredCredentials()

        } catch (e: Exception) {
            Log.e(TAG, "Error during full SIP stack initialization", e)
        }
    }

    /**
     * Periodic health check to ensure SIP connection stays alive
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            while (isActive) {
                delay(healthCheckIntervalMs)
                checkConnectionHealth()
            }
        }
    }

    /**
     * Start monitoring network change events for SIP reconnection
     */
    private fun startNetworkChangeMonitoring() {
        networkMonitoringJob?.cancel()
        networkMonitoringJob = serviceScope.launch {
            networkStateManager.networkChangeEvents.collectLatest { event ->
                Log.d(TAG, "Network change event: $event")

                when (event) {
                    NetworkChangeEvent.RECONNECTED -> {
                        // Network came back after disconnection
                        Log.d(TAG, "Network reconnected - triggering SIP + Socket reconnection")
                        handleNetworkReconnection(reason = "network_reconnected")
                        handleSocketReconnection(reason = "network_reconnected")
                    }
                    NetworkChangeEvent.NETWORK_SWITCHED -> {
                        // Network type changed (WiFi <-> Cellular)
                        // This REQUIRES a full reconnection as the IP address changed
                        Log.d(TAG, "Network switched - triggering SIP + Socket reconnection")
                        handleNetworkReconnection(reason = "network_switched")
                        handleSocketReconnection(reason = "network_switched")
                    }
                    NetworkChangeEvent.DISCONNECTED -> {
                        // Network lost - just log, don't do anything
                        // The reconnection will happen when network comes back
                        Log.d(TAG, "Network disconnected - waiting for reconnection")
                    }
                }
            }
        }
    }

    /**
     * Handle network reconnection with debouncing
     */
    private fun handleNetworkReconnection(reason: String) {
        val now = System.currentTimeMillis()

        // Debounce rapid reconnection attempts
        if (now - lastReconnectAttempt < minReconnectIntervalMs) {
            Log.d(TAG, "Debouncing reconnection attempt (too soon after last attempt)")
            return
        }

        if (isReconnecting) {
            Log.d(TAG, "Already reconnecting, ignoring request")
            return
        }

        lastReconnectAttempt = now
        isReconnecting = true

        serviceScope.launch {
            try {
                // Check if we have credentials
                if (!secureCredentialManager.hasSipCredentials() && currentSipConfig == null) {
                    Log.d(TAG, "No SIP credentials available, skipping reconnection")
                    isReconnecting = false
                    return@launch
                }

                Log.d(TAG, "Initiating SIP reconnection due to: $reason")

                // Use the WebViewSipBridge reconnect which properly reinitializes JsSIP
                webViewSipBridge.reconnect()

                // Wait for reconnection to complete (with timeout)
                var attempts = 0
                val maxAttempts = 30 // 30 seconds timeout
                while (attempts < maxAttempts) {
                    delay(1000)
                    if (sipEngine.isRegistered()) {
                        Log.d(TAG, "SIP reconnected successfully after $attempts seconds")
                        consecutiveFailures = 0
                        break
                    }
                    attempts++
                }

                if (!sipEngine.isRegistered()) {
                    Log.w(TAG, "SIP reconnection timed out, will retry on next network event")
                    consecutiveFailures++
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during SIP reconnection", e)
                consecutiveFailures++
            } finally {
                isReconnecting = false
            }
        }
    }

    /**
     * Check SIP connection health and attempt recovery if needed
     * IMPORTANT: This NEVER gives up - it uses exponential backoff for retries
     */
    private fun checkConnectionHealth() {
        val currentState = sipEngine.registrationState.value
        val isNetworkAvailable = networkStateManager.isNetworkAvailable()
        Log.d(TAG, "Health check: state=$currentState, network=$isNetworkAvailable, failures=$consecutiveFailures")

        when (currentState) {
            SipRegistrationState.REGISTERED -> {
                consecutiveFailures = 0
                // Re-acquire wake lock to keep service alive
                reacquireWakeLock()
            }
            SipRegistrationState.FAILED, SipRegistrationState.UNREGISTERED -> {
                // Only attempt reconnection if network is available
                if (isNetworkAvailable && (currentSipConfig != null || secureCredentialManager.hasSipCredentials())) {
                    consecutiveFailures++

                    // Calculate backoff delay - starts at 5s, max 5 minutes
                    val backoffDelay = minOf(
                        5000L * (1 shl minOf(consecutiveFailures, 6)), // Exponential backoff
                        5 * 60 * 1000L // Max 5 minutes
                    )

                    Log.w(TAG, "SIP not registered, scheduling reconnection in ${backoffDelay/1000}s (failure #$consecutiveFailures)")

                    // Schedule reconnection with backoff
                    serviceScope.launch {
                        delay(backoffDelay)
                        if (!sipEngine.isRegistered() && networkStateManager.isNetworkAvailable()) {
                            handleNetworkReconnection(reason = "health_check_recovery")
                        }
                    }
                } else if (!isNetworkAvailable) {
                    Log.d(TAG, "Network not available, waiting for reconnection")
                }
            }
            else -> {
                // REGISTERING or other transient states - wait for completion
            }
        }
    }

    /**
     * Re-acquire wake lock to keep service running
     */
    private fun reacquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(10 * 60 * 1000L)
                Log.d(TAG, "Wake lock re-acquired")
            }
        }
    }

    /**
     * Initialize WebView in service context for persistent SIP connection
     * This allows SIP to work even when MainActivity is destroyed
     * Uses suspendCancellableCoroutine to properly wait without blocking
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun initializeServiceWebView() {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Initializing service-managed WebView for SIP")
                sipWebView = WebView(this@SipForegroundService).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                }

                // Initialize via SipEngine which will call WebViewSipBridge
                // Only call one of them to avoid double initialization
                sipEngine.initializeWebView(sipWebView!!)
                isWebViewInitialized = true

                Log.d(TAG, "Service WebView initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize service WebView", e)
            }
        }
    }

    /**
     * Auto-register with credentials stored securely
     * Called on service start to maintain persistent connection
     */
    private fun autoRegisterWithStoredCredentials() {
        Log.d(TAG, "=== autoRegisterWithStoredCredentials called ===")

        serviceScope.launch {
            // Wait for WebView to be ready
            Log.d(TAG, "Waiting 2s for WebView to be ready...")
            delay(2000)
            Log.d(TAG, "WebView wait complete, checking credentials...")

            // First try secure storage, then fallback to legacy SharedPreferences
            val secureConfig = secureCredentialManager.getSipCredentials()
            Log.d(TAG, "Secure credentials found: ${secureConfig != null}")

            val legacyConfig = if (secureConfig == null) getLegacySipCredentials() else null
            Log.d(TAG, "Legacy credentials found: ${legacyConfig != null}")

            val sipConfig = secureConfig ?: legacyConfig

            if (sipConfig != null) {
                Log.d(TAG, "Auto-registering with SIP credentials: ${sipConfig.username}@${sipConfig.domain}")
                register(sipConfig)
            } else {
                Log.w(TAG, "No stored SIP credentials found (secure or legacy), waiting for login")
            }
        }
    }

    /**
     * Fallback to legacy SharedPreferences for existing users
     * Will migrate to secure storage on next login
     */
    private fun getLegacySipCredentials(): SipConfig? {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val username = prefs.getString(Constants.KEY_SIP_USERNAME, null) ?: return null
        val password = prefs.getString(Constants.KEY_SIP_PASSWORD, null) ?: return null
        val domain = prefs.getString(Constants.KEY_SIP_DOMAIN, null) ?: return null
        val server = prefs.getString(Constants.KEY_SIP_SERVER, null) ?: return null
        val port = prefs.getInt(Constants.KEY_SIP_PORT, 8089).toString()

        Log.d(TAG, "Using legacy SIP credentials, will migrate on next login")
        return SipConfig(
            username = username,
            password = password,
            domain = domain,
            server = server,
            port = port
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                // Foreground already started in onCreate(), just acquire wake lock
                acquireWakeLock()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REGISTER -> {
                // Parse SipConfig from intent extras
                val username = intent.getStringExtra("sip_username") ?: return START_STICKY
                val password = intent.getStringExtra("sip_password") ?: return START_STICKY
                val domain = intent.getStringExtra("sip_domain") ?: return START_STICKY
                val server = intent.getStringExtra("sip_server") ?: return START_STICKY
                val port = intent.getStringExtra("sip_port") ?: "8089"
                val config = SipConfig(
                    username = username,
                    password = password,
                    domain = domain,
                    server = server,
                    port = port
                )
                register(config)
            }
            ACTION_UNREGISTER -> {
                unregister()
            }
            ACTION_RECONNECT -> {
                reconnect()
            }
            ACTION_REGISTER_NOW -> {
                // Called after login - credentials should now be available
                Log.d(TAG, "Register now action received - initializing SIP stack + Socket with new credentials")
                serviceScope.launch {
                    // If WebView was never initialized (service started before login),
                    // we need to do full initialization now
                    if (!isWebViewInitialized) {
                        Log.d(TAG, "WebView not initialized, performing full SIP stack initialization")
                        initializeFullSipStack()
                    } else {
                        // WebView already initialized, just register with new credentials
                        autoRegisterWithStoredCredentials()
                    }

                    // Also initialize socket for real-time SMS updates
                    Log.d(TAG, "🔌 Initializing socket after login...")
                    initializeSocket()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Called when user swipes away app from recents.
     * Schedule immediate restart to maintain VoIP connectivity.
     * This is part of the professional WhatsApp-like approach.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "=== onTaskRemoved - App swiped away ===")

        // Schedule service restart in 1 second
        ServiceKeepAliveManager.scheduleServiceRestart(this, 1000L)

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "=== Service destroyed ===")
        isRunning = false

        // Schedule restart if we have credentials (service shouldn't be destroyed)
        if (secureCredentialManager.hasSipCredentials()) {
            Log.d(TAG, "Service destroyed but has credentials - scheduling restart")
            ServiceKeepAliveManager.scheduleServiceRestart(this, 2000L)
        }

        // Cancel all coroutine jobs explicitly for clean shutdown
        // Note: serviceScope.cancel() will also cancel children, but explicit cancellation
        // ensures immediate cleanup and prevents any race conditions
        cancelAllJobs()

        // Disconnect socket
        try {
            socketManager.disconnect()
            Log.d(TAG, "Socket disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting socket", e)
        }

        // Stop network state monitoring
        try {
            networkStateManager.stopMonitoring()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping network monitoring", e)
        }

        // Shutdown SIP engine with timeout to prevent ANR
        // Uses NonCancellable to ensure cleanup completes even if scope is cancelled
        // Timeout of 3 seconds prevents ANR (Android shows ANR at 5 seconds)
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        sipEngine.shutdown()
                    }
                } ?: Log.w(TAG, "SIP shutdown timed out after 3 seconds")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during SIP shutdown", e)
        }

        // Cancel the service scope - this cancels any remaining coroutines
        serviceScope.cancel()
        releaseWakeLock()

        // Cleanup WebView on main thread
        mainHandler.post {
            try {
                sipWebView?.destroy()
                sipWebView = null
                isWebViewInitialized = false
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying WebView", e)
            }
        }

        super.onDestroy()
    }

    /**
     * Start foreground service with notification.
     *
     * IMPORTANT: This method handles all edge cases to prevent crashes:
     * - Android 14+ requires explicit foreground service type
     * - Microphone type requires RECORD_AUDIO permission to be granted
     * - Background start restrictions on Android 12+
     * - ForegroundServiceStartNotAllowedException handling
     */
    private fun startForegroundWithNotification() {
        try {
            val notification = createNotification(SipRegistrationState.UNREGISTERED)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires explicit foreground service type
                // Check if we have microphone permission before using that type
                val hasMicPermission = permissionManager.hasMicrophonePermission()

                // Start with phone_call type only - it's safer and doesn't require mic permission
                // Microphone type requires the permission to be granted AND the app to be in foreground
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL

                try {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                    Log.d(TAG, "Started foreground service with PHONE_CALL type (mic=$hasMicPermission)")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException starting foreground, trying without type", e)
                    tryStartForegroundFallback(notification)
                } catch (e: Exception) {
                    Log.e(TAG, "Exception starting foreground with type", e)
                    tryStartForegroundFallback(notification)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10-13
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                    Log.d(TAG, "Started foreground service with PHONE_CALL type (Android Q+)")
                } catch (e: Exception) {
                    Log.e(TAG, "Exception starting foreground on Android Q+", e)
                    tryStartForegroundFallback(notification)
                }
            } else {
                // Android 9 and below
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "Started foreground service (legacy)")
            }
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 12+ restricts starting FGS from background
            Log.e(TAG, "ForegroundServiceStartNotAllowedException - app is in background", e)
            // Don't crash - the service will be started when app comes to foreground
            isRunning = false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in startForegroundWithNotification", e)
            isRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in startForegroundWithNotification", e)
            isRunning = false
        }
    }

    /**
     * Fallback method to start foreground without specific type.
     * Used when the typed version fails.
     */
    private fun tryStartForegroundFallback(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // On Android 14+, we MUST specify a type, use DATA_SYNC as safest fallback
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                Log.d(TAG, "Started foreground with DATA_SYNC fallback type")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "Started foreground with no type (fallback)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Even fallback foreground start failed", e)
            isRunning = false
        }
    }

    /**
     * Create service notification
     */
    private fun createNotification(state: SipRegistrationState): Notification {
        val intent = Intent(this, Class.forName("com.bitnextechnologies.bitnexdial.presentation.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (state) {
            SipRegistrationState.REGISTERED -> "Connected"
            SipRegistrationState.REGISTERING -> "Connecting..."
            SipRegistrationState.UNREGISTERING -> "Disconnecting..."
            SipRegistrationState.FAILED -> "Connection failed"
            SipRegistrationState.UNREGISTERED -> "Not connected"
            SipRegistrationState.MAX_CONTACTS_REACHED -> "Max contacts reached"
        }

        val icon = when (state) {
            SipRegistrationState.REGISTERED -> R.drawable.ic_call
            SipRegistrationState.FAILED -> R.drawable.ic_call_end
            else -> R.drawable.ic_call
        }

        return NotificationCompat.Builder(this, Constants.CHANNEL_VOIP_SERVICE)
            .setContentTitle("BitNex Dial")
            .setContentText(statusText)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Update notification with current state
     */
    private fun updateNotification(state: SipRegistrationState) {
        val notification = createNotification(state)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Monitor SIP registration state
     */
    private fun monitorRegistrationState() {
        serviceScope.launch {
            sipEngine.registrationState.collectLatest { state ->
                Log.d(TAG, "Registration state: $state")
                updateNotification(state)
            }
        }
    }

    /**
     * Register with SIP server
     */
    fun register(config: SipConfig) {
        currentSipConfig = config

        registrationJob?.cancel()
        registrationJob = serviceScope.launch {
            try {
                sipEngine.register(config)
            } catch (e: Exception) {
                Log.e(TAG, "Registration failed", e)
                // Retry after delay
                delay(5000)
                if (isActive) {
                    register(config)
                }
            }
        }
    }

    /**
     * Unregister from SIP server
     */
    fun unregister() {
        registrationJob?.cancel()
        serviceScope.launch {
            try {
                sipEngine.unregister()
            } catch (e: Exception) {
                Log.e(TAG, "Unregistration failed", e)
            }
        }
    }

    /**
     * Reconnect to SIP server
     */
    private fun reconnect() {
        currentSipConfig?.let { config ->
            serviceScope.launch {
                try {
                    sipEngine.unregister()
                    delay(1000)
                    sipEngine.register(config)
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnection failed", e)
                }
            }
        }
    }

    /**
     * Acquire wake lock to keep CPU running
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max, will be re-acquired
            }
            Log.d(TAG, "Wake lock acquired")
        }
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Check if SIP is registered
     */
    fun isRegistered(): Boolean {
        return sipEngine.registrationState.value == SipRegistrationState.REGISTERED
    }

    /**
     * Get current registration state
     */
    fun getRegistrationState(): SipRegistrationState {
        return sipEngine.registrationState.value
    }

    /**
     * Cancel all coroutine jobs for clean shutdown.
     * This ensures no memory leaks from orphaned coroutines.
     */
    private fun cancelAllJobs() {
        // Cancel all monitoring and registration jobs
        registrationJob?.cancel()
        registrationJob = null

        healthCheckJob?.cancel()
        healthCheckJob = null

        networkMonitoringJob?.cancel()
        networkMonitoringJob = null

        badgeMonitoringJob?.cancel()
        badgeMonitoringJob = null

        socketHealthCheckJob?.cancel()
        socketHealthCheckJob = null

        socketMonitoringJob?.cancel()
        socketMonitoringJob = null

        Log.d(TAG, "All coroutine jobs cancelled")
    }
}
