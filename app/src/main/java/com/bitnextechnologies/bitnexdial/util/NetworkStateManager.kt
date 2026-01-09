package com.bitnextechnologies.bitnexdial.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Professional network state manager for VoIP applications.
 *
 * Key features:
 * - Event-driven network monitoring (no polling)
 * - Detects network type changes (WiFi <-> Cellular)
 * - Provides debounced network change events
 * - Handles network transitions gracefully
 * - Never triggers logout on network failures
 */
@Singleton
class NetworkStateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkStateManager"

        // Debounce delay to avoid rapid reconnection attempts during network handoff
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 2000L

        // Stability delay before considering network fully available
        private const val NETWORK_STABILITY_DELAY_MS = 1500L
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var debounceJob: Job? = null
    private var lastNetworkType: NetworkType = NetworkType.NONE
    private var isCallbackRegistered = false

    // Current network state
    private val _networkState = MutableStateFlow(NetworkConnectionState())
    val networkState: StateFlow<NetworkConnectionState> = _networkState.asStateFlow()

    // Network change events for SIP reconnection
    private val _networkChangeEvents = MutableSharedFlow<NetworkChangeEvent>(replay = 0, extraBufferCapacity = 1)
    val networkChangeEvents: SharedFlow<NetworkChangeEvent> = _networkChangeEvents.asSharedFlow()

    /**
     * Start monitoring network state.
     * Uses registerDefaultNetworkCallback for proper network change detection.
     */
    fun startMonitoring() {
        if (isCallbackRegistered) {
            Log.d(TAG, "Network monitoring already active")
            return
        }

        Log.d(TAG, "Starting network monitoring")

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val newType = getNetworkType(capabilities)
                Log.d(TAG, "Network available: $newType")

                handleNetworkChange(newType, true)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                handleNetworkChange(NetworkType.NONE, false)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val newType = getNetworkType(capabilities)
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                Log.d(TAG, "Network capabilities changed: type=$newType, internet=$hasInternet, validated=$isValidated")

                if (hasInternet && isValidated) {
                    handleNetworkChange(newType, true)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                // Link properties changed (IP address, DNS, etc.)
                // This can indicate network handoff
                Log.d(TAG, "Link properties changed - possible network handoff")
            }
        }

        try {
            // Use registerDefaultNetworkCallback for proper network change detection
            // This tracks the system's default network and fires on network switches
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                // Fallback for older devices
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback!!)
            }
            isCallbackRegistered = true

            // Emit initial state
            updateCurrentState()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Stop monitoring network state
     */
    fun stopMonitoring() {
        if (!isCallbackRegistered) return

        Log.d(TAG, "Stopping network monitoring")

        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }

        networkCallback = null
        isCallbackRegistered = false
        debounceJob?.cancel()
    }

    /**
     * Handle network change with debouncing to avoid rapid events during handoff
     */
    private fun handleNetworkChange(newType: NetworkType, isAvailable: Boolean) {
        val previousType = lastNetworkType
        val previousState = _networkState.value

        // Check if this is a significant change
        val isTypeChange = previousType != newType && previousType != NetworkType.NONE && newType != NetworkType.NONE
        val isReconnection = !previousState.isConnected && isAvailable
        val isDisconnection = previousState.isConnected && !isAvailable

        // Update state immediately for UI
        _networkState.value = NetworkConnectionState(
            isConnected = isAvailable,
            networkType = newType,
            isValidated = isAvailable
        )

        lastNetworkType = newType

        // Debounce network change events for SIP reconnection
        debounceJob?.cancel()
        debounceJob = scope.launch {
            if (isTypeChange) {
                // Network type changed (WiFi <-> Cellular) - wait for stability
                Log.d(TAG, "Network type changed: $previousType -> $newType, waiting for stability")
                delay(NETWORK_STABILITY_DELAY_MS)

                // Verify network is still available
                if (isNetworkAvailable()) {
                    Log.d(TAG, "Network stable after type change, emitting NETWORK_SWITCHED event")
                    _networkChangeEvents.emit(NetworkChangeEvent.NETWORK_SWITCHED)
                }
            } else if (isReconnection) {
                // Reconnected after being disconnected
                Log.d(TAG, "Network reconnected, waiting for stability")
                delay(NETWORK_STABILITY_DELAY_MS)

                if (isNetworkAvailable()) {
                    Log.d(TAG, "Network stable after reconnection, emitting RECONNECTED event")
                    _networkChangeEvents.emit(NetworkChangeEvent.RECONNECTED)
                }
            } else if (isDisconnection) {
                // Network lost
                Log.d(TAG, "Network disconnected")
                delay(NETWORK_CHANGE_DEBOUNCE_MS) // Wait to see if it comes back quickly

                if (!isNetworkAvailable()) {
                    Log.d(TAG, "Network still disconnected, emitting DISCONNECTED event")
                    _networkChangeEvents.emit(NetworkChangeEvent.DISCONNECTED)
                }
            }
        }
    }

    /**
     * Get current network type from capabilities
     */
    private fun getNetworkType(capabilities: NetworkCapabilities?): NetworkType {
        if (capabilities == null) return NetworkType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                // VPN - check underlying transport
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) NetworkType.WIFI
                else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) NetworkType.CELLULAR
                else NetworkType.VPN
            }
            else -> NetworkType.OTHER
        }
    }

    /**
     * Update current network state
     */
    private fun updateCurrentState() {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                          capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val networkType = getNetworkType(capabilities)

        _networkState.value = NetworkConnectionState(
            isConnected = isConnected,
            networkType = networkType,
            isValidated = isConnected
        )

        lastNetworkType = networkType
        Log.d(TAG, "Initial network state: connected=$isConnected, type=$networkType")
    }

    /**
     * Check if network is currently available
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Get current network type
     */
    fun getCurrentNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return getNetworkType(capabilities)
    }
}

/**
 * Network connection state
 */
data class NetworkConnectionState(
    val isConnected: Boolean = false,
    val networkType: NetworkType = NetworkType.NONE,
    val isValidated: Boolean = false
)

/**
 * Network type enum
 */
enum class NetworkType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER
}

/**
 * Network change events for SIP reconnection
 */
enum class NetworkChangeEvent {
    /** Network reconnected after being disconnected */
    RECONNECTED,

    /** Network type changed (e.g., WiFi to Cellular) - requires SIP re-registration */
    NETWORK_SWITCHED,

    /** Network disconnected */
    DISCONNECTED
}
