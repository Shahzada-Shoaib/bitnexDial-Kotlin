package com.bitnextechnologies.bitnexdial.data.sip

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.bitnextechnologies.bitnexdial.domain.model.SipConfig
import com.bitnextechnologies.bitnexdial.domain.repository.SipRegistrationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SIP Engine that delegates to WebViewSipBridge for real VoIP calls
 * Uses SIP.js loaded in a WebView for actual SIP signaling
 */
@Singleton
class SipEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webViewSipBridge: WebViewSipBridge
) {
    companion object {
        private const val TAG = "SipEngine"
    }

    // Registration state
    private val _registrationState = MutableStateFlow(SipRegistrationState.UNREGISTERED)
    val registrationState: StateFlow<SipRegistrationState> = _registrationState.asStateFlow()

    // Callbacks
    private var callStateCallback: ((String, SipCallState) -> Unit)? = null
    private var incomingCallCallback: ((String, String, String?) -> Unit)? = null

    // WebRTC components (kept for future native integration)
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    // SIP Configuration
    private var sipConfig: SipConfig? = null

    // Audio state
    private var isMuted = false
    private var isSpeakerOn = false

    // Track if WebView bridge is ready
    private var webViewInitialized = false
    private var webRtcInitialized = false

    // Map line numbers to call IDs (thread-safe)
    private val lineToCallId = ConcurrentHashMap<Int, String>()
    private val callIdToLine = ConcurrentHashMap<String, Int>()

    init {
        // IMPORTANT: Don't do heavy initialization here - it blocks the main thread
        // WebRTC will be initialized lazily when needed
        setupWebViewBridgeCallbacks()
    }

    /**
     * Initialize WebView for SIP.js - must be called from Activity with WebView
     */
    fun initializeWebView(webView: WebView) {
        Log.d(TAG, "Initializing WebView for SIP bridge")
        webViewSipBridge.initialize(webView)
        webViewInitialized = true
    }

    private fun setupWebViewBridgeCallbacks() {
        webViewSipBridge.onRegistrationStateChanged = { isRegistered ->
            Log.d(TAG, "WebView SIP registration state: $isRegistered")
            _registrationState.value = if (isRegistered) {
                SipRegistrationState.REGISTERED
            } else {
                SipRegistrationState.UNREGISTERED
            }
        }

        webViewSipBridge.onCallStateChanged = { lineNumber, state ->
            Log.d(TAG, "WebView call state: line=$lineNumber, state=$state")
            val callId = lineToCallId[lineNumber.toIntOrNull() ?: 1] ?: lineNumber
            val sipState = when (state.lowercase()) {
                "connecting", "trying" -> SipCallState.CONNECTING
                "ringing", "progress" -> SipCallState.RINGING
                "early" -> SipCallState.EARLY_MEDIA
                "connected", "confirmed", "accepted" -> SipCallState.CONNECTED
                "hold", "on_hold" -> SipCallState.ON_HOLD
                "disconnected", "terminated", "bye" -> SipCallState.DISCONNECTED
                "failed", "error", "cancelled", "canceled" -> SipCallState.FAILED
                "busy" -> SipCallState.BUSY
                "rejected", "declined" -> SipCallState.REJECTED
                else -> SipCallState.IDLE
            }
            callStateCallback?.invoke(callId, sipState)
        }

        webViewSipBridge.onIncomingCall = { callId, callerNumber, callerName ->
            Log.d(TAG, "WebView incoming call: $callerNumber ($callerName)")
            incomingCallCallback?.invoke(callId, callerNumber, callerName)
        }

        webViewSipBridge.onWebViewReady = {
            Log.d(TAG, "WebView is ready, attempting to register SIP")
            webViewInitialized = true
            // Try to register with stored config
            retryRegistration()
        }
    }

    /**
     * Initialize WebRTC lazily - only when needed for calls
     * This is a heavy operation and should not be called on main thread during startup
     */
    @Synchronized
    private fun ensureWebRtcInitialized() {
        if (webRtcInitialized) return

        try {
            Log.d(TAG, "Initializing WebRTC (lazy)")
            // Initialize WebRTC
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false) // Disable tracing for performance
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            // Create PeerConnectionFactory - skip video for audio-only VoIP
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()

            webRtcInitialized = true
            Log.d(TAG, "WebRTC initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC", e)
        }
    }

    /**
     * Initialize the SIP engine
     */
    suspend fun initialize() {
        Log.d(TAG, "Initializing SIP engine")
        _registrationState.value = SipRegistrationState.UNREGISTERED
    }

    /**
     * Register with SIP server using WebView SIP.js bridge
     */
    suspend fun register(config: SipConfig) {
        Log.d(TAG, "Registering with SIP server: ${config.domain}")
        sipConfig = config
        _registrationState.value = SipRegistrationState.REGISTERING

        try {
            if (webViewInitialized) {
                // Use WebView SIP.js bridge for real registration
                Log.d(TAG, "Using WebView SIP bridge for registration")
                webViewSipBridge.register(
                    extension = config.username,
                    password = config.password,
                    domain = config.domain,
                    wssServer = config.server,
                    wssPort = config.port
                )
                // Registration state will be updated via callback
            } else {
                Log.w(TAG, "WebView not initialized, registration pending")
                // Keep in REGISTERING state until WebView is ready
            }
        } catch (e: Exception) {
            Log.e(TAG, "SIP registration failed", e)
            _registrationState.value = SipRegistrationState.FAILED
            throw e
        }
    }

    /**
     * Re-register with stored config when WebView becomes available
     */
    fun retryRegistration() {
        if (webViewInitialized && sipConfig != null) {
            Log.d(TAG, "Retrying SIP registration with WebView bridge")
            webViewSipBridge.register(
                extension = sipConfig!!.username,
                password = sipConfig!!.password,
                domain = sipConfig!!.domain,
                wssServer = sipConfig!!.server,
                wssPort = sipConfig!!.port
            )
        }
    }

    /**
     * Unregister from SIP server
     */
    suspend fun unregister() {
        Log.d(TAG, "Unregistering from SIP server")
        _registrationState.value = SipRegistrationState.UNREGISTERING

        try {
            // Send SIP UNREGISTER via WebView bridge
            webViewSipBridge.unregister()
            _registrationState.value = SipRegistrationState.UNREGISTERED
            Log.d(TAG, "SIP UNREGISTER sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "SIP unregistration failed", e)
            _registrationState.value = SipRegistrationState.UNREGISTERED
        }
    }

    /**
     * Make outgoing call using WebView SIP.js bridge
     */
    suspend fun makeCall(phoneNumber: String, callId: String) {
        Log.d(TAG, "Making call to $phoneNumber with callId: $callId")

        try {
            if (webViewInitialized) {
                // Use WebView SIP.js bridge for real call
                Log.d(TAG, "Using WebView SIP bridge for call")

                // Track line number for this call (default to line 1)
                val lineNumber = findAvailableLine()
                lineToCallId[lineNumber] = callId
                callIdToLine[callId] = lineNumber

                callStateCallback?.invoke(callId, SipCallState.CONNECTING)
                webViewSipBridge.makeCall(phoneNumber)
                // Call state will be updated via callback from JavaScript
            } else {
                Log.w(TAG, "WebView not initialized, cannot make real call")
                // Show connecting state anyway
                callStateCallback?.invoke(callId, SipCallState.CONNECTING)

                // Then show failed after a brief delay
                kotlinx.coroutines.delay(1000)
                callStateCallback?.invoke(callId, SipCallState.FAILED)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make call", e)
            callStateCallback?.invoke(callId, SipCallState.FAILED)
            throw e
        }
    }

    private fun findAvailableLine(): Int {
        for (i in 1..4) {
            if (!lineToCallId.containsKey(i)) {
                return i
            }
        }
        return 1 // Default to line 1 if all are taken
    }

    /**
     * Answer incoming call using WebView SIP.js bridge
     */
    suspend fun answerCall(callId: String) {
        Log.d(TAG, "Answering call $callId")

        try {
            if (webViewInitialized) {
                val lineNumber = callIdToLine[callId] ?: 1
                webViewSipBridge.answerCall(lineNumber)
            }
            callStateCallback?.invoke(callId, SipCallState.CONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call", e)
            throw e
        }
    }

    /**
     * Reject incoming call with proper SIP 486 response.
     * Uses the dedicated rejectCall method which handles the case where
     * FCM arrives before SIP INVITE (sets pending reject flag).
     */
    suspend fun rejectCall(callId: String) {
        Log.d(TAG, "Rejecting call $callId")

        try {
            if (webViewInitialized) {
                val lineNumber = callIdToLine[callId]
                if (lineNumber != null) {
                    webViewSipBridge.rejectCall(lineNumber)
                } else {
                    // FCM push callId doesn't match SIP callId - reject on line 1
                    // The rejectCall method handles pending reject if session not ready yet
                    Log.d(TAG, "CallId not mapped, rejecting incoming call on line 1")
                    webViewSipBridge.rejectCall(1)
                }
            }
            cleanupCall(callId)
            callStateCallback?.invoke(callId, SipCallState.REJECTED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject call", e)
            throw e
        }
    }

    /**
     * End active call using WebView SIP.js bridge
     */
    suspend fun endCall(callId: String) {
        Log.d(TAG, "Ending call $callId")

        try {
            if (webViewInitialized) {
                val lineNumber = callIdToLine[callId]
                if (lineNumber != null) {
                    Log.d(TAG, "Ending call on line $lineNumber via WebView bridge")
                    webViewSipBridge.endCall(lineNumber)
                } else {
                    // FCM push callId doesn't match SIP callId - end active call on line 1
                    Log.d(TAG, "CallId not mapped, ending active call on line 1")
                    webViewSipBridge.endCall(1)
                }
            }
            cleanupCall(callId)
            callStateCallback?.invoke(callId, SipCallState.DISCONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call", e)
            throw e
        }
    }

    /**
     * Hold call using WebView SIP.js bridge
     */
    suspend fun holdCall(callId: String) {
        Log.d(TAG, "Holding call $callId")

        try {
            if (webViewInitialized) {
                val lineNumber = callIdToLine[callId] ?: 1
                webViewSipBridge.holdCall(lineNumber, true)
            }
            callStateCallback?.invoke(callId, SipCallState.ON_HOLD)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hold call", e)
            throw e
        }
    }

    /**
     * Resume held call using WebView SIP.js bridge
     */
    suspend fun resumeCall(callId: String) {
        Log.d(TAG, "Resuming call $callId")

        try {
            if (webViewInitialized) {
                val lineNumber = callIdToLine[callId] ?: 1
                webViewSipBridge.holdCall(lineNumber, false)
            }
            callStateCallback?.invoke(callId, SipCallState.CONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume call", e)
            throw e
        }
    }

    /**
     * Mute/unmute call using WebView SIP.js bridge
     */
    suspend fun setMute(callId: String, mute: Boolean) {
        Log.d(TAG, "Setting mute: $mute for call $callId")
        isMuted = mute
        if (webViewInitialized) {
            val lineNumber = callIdToLine[callId] ?: 1
            webViewSipBridge.muteCall(lineNumber, mute)
        }
    }

    /**
     * Send DTMF tone using WebView SIP.js bridge
     */
    suspend fun sendDtmf(callId: String, digit: String) {
        Log.d(TAG, "Sending DTMF: $digit for call $callId")

        try {
            if (webViewInitialized) {
                val lineNumber = callIdToLine[callId] ?: 1
                webViewSipBridge.sendDtmf(lineNumber, digit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send DTMF", e)
        }
    }

    /**
     * Transfer call (blind transfer).
     * Sends SIP REFER to transfer the call to the destination.
     * The call cleanup happens via normal session termination after server confirms transfer.
     *
     * Transfer states reported via onTransferStateChanged callback:
     * - "initiated" - REFER request being sent
     * - "completed" - REFER accepted (202 Accepted), call will end via BYE from server
     *
     * Failure reported via onTransferFailed callback with error message.
     * If transfer fails, the original call remains active.
     */
    suspend fun transferCall(callId: String, destination: String) {
        Log.d(TAG, "Transferring call $callId to $destination (blind transfer)")

        try {
            if (webViewInitialized) {
                // Initiate blind transfer - session cleanup happens via normal
                // call termination after server processes the REFER
                webViewSipBridge.blindTransfer(destination)
                // Note: Don't cleanup here - the session will end via normal
                // call termination (BYE) after the server completes the transfer.
                // If transfer fails, the call remains active.
            } else {
                Log.w(TAG, "WebView not initialized, cannot transfer call")
                throw IllegalStateException("SIP not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transfer call", e)
            throw e
        }
    }

    /**
     * Start attended transfer - puts current call on hold and calls target
     */
    fun startAttendedTransfer(targetNumber: String) {
        Log.d(TAG, "Starting attended transfer to $targetNumber")
        webViewSipBridge.startAttendedTransfer(targetNumber)
    }

    /**
     * Complete attended transfer - connects the two parties
     */
    fun completeAttendedTransfer() {
        Log.d(TAG, "Completing attended transfer")
        webViewSipBridge.completeTransfer()
    }

    /**
     * Cancel attended transfer - ends consultation and resumes original call
     */
    fun cancelAttendedTransfer() {
        Log.d(TAG, "Cancelling attended transfer")
        webViewSipBridge.cancelTransfer()
    }

    /**
     * Start add call for conference - puts current call on hold and calls new party
     */
    fun startAddCall(targetNumber: String) {
        Log.d(TAG, "Starting add call to $targetNumber")
        webViewSipBridge.startAddCall(targetNumber)
    }

    /**
     * Merge calls into conference
     */
    fun mergeConference() {
        Log.d(TAG, "Merging conference")
        webViewSipBridge.mergeConference()
    }

    /**
     * End conference - ends the added call
     */
    fun endConference() {
        Log.d(TAG, "Ending conference")
        webViewSipBridge.endConference()
    }

    /**
     * Set speaker mode.
     * Note: Actual audio routing is handled by InCallViewModel.setSpeakerphoneEnabled()
     * which uses AudioManager with proper API level handling.
     */
    fun setSpeaker(enabled: Boolean) {
        isSpeakerOn = enabled
    }

    /**
     * Set call state callback
     */
    fun setCallStateCallback(callback: (String, SipCallState) -> Unit) {
        callStateCallback = callback
    }

    /**
     * Set incoming call callback
     */
    fun setIncomingCallCallback(callback: (String, String, String?) -> Unit) {
        incomingCallCallback = callback
    }

    /**
     * Check if registered
     */
    fun isRegistered(): Boolean = _registrationState.value == SipRegistrationState.REGISTERED

    /**
     * Shutdown the SIP engine
     */
    suspend fun shutdown() {
        Log.d(TAG, "Shutting down SIP engine")

        try {
            // End all active calls
            peerConnections.keys.toList().forEach { callId ->
                endCall(callId)
            }

            // Unregister
            if (isRegistered()) {
                unregister()
            }

            // Cleanup WebRTC
            localAudioTrack?.dispose()
            audioSource?.dispose()
            peerConnectionFactory?.dispose()

            _registrationState.value = SipRegistrationState.UNREGISTERED
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }

    // Helper methods

    private fun createLocalAudioTrack() {
        if (localAudioTrack != null) return

        // Ensure WebRTC is initialized
        ensureWebRtcInitialized()

        try {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }

            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)
            localAudioTrack?.setEnabled(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create audio track", e)
        }
    }

    private fun createPeerConnection(callId: String): PeerConnection {
        // Ensure WebRTC is initialized before creating peer connection
        ensureWebRtcInitialized()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "ICE candidate: ${candidate?.sdp}")
                // NOTE: Explicit ICE candidate exchange to remote peer is NOT needed.
                // The WebViewSipBridge/JsSIP architecture uses server relay mode with empty
                // iceServers, allowing FreePBX to handle all media routing. This approach:
                // 1. Simplifies NAT traversal (no STUN/TURN configuration needed)
                // 2. Ensures media quality through server infrastructure
                // 3. Provides centralized call recording/monitoring capabilities
                // ICE candidates are still gathered locally but the server handles routing.
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        callStateCallback?.invoke(callId, SipCallState.CONNECTED)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        callStateCallback?.invoke(callId, SipCallState.DISCONNECTED)
                    }
                    else -> {}
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "Remote stream added")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        return peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create peer connection")
    }

    private fun cleanupCall(callId: String) {
        peerConnections[callId]?.close()
        peerConnections.remove(callId)

        // Clean up line mappings
        val lineNumber = callIdToLine[callId]
        if (lineNumber != null) {
            lineToCallId.remove(lineNumber)
            callIdToLine.remove(callId)
        }
    }
}
