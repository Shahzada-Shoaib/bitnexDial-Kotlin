package com.bitnextechnologies.bitnexdial.data.sip

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebView-based SIP bridge that loads the phone.js from the web dialer
 * and provides native callbacks for call events
 */
@Singleton
class WebViewSipBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WebViewSipBridge"
    }

    private var webView: WebView? = null
    private var isInitialized = false
    private var isWebViewReady = false
    private var pendingRegistration: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Call state
    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _activeCallId = MutableStateFlow<String?>(null)
    val activeCallId: StateFlow<String?> = _activeCallId.asStateFlow()

    private val _callState = MutableStateFlow<String>("idle")
    val callState: StateFlow<String> = _callState.asStateFlow()

    // Callbacks
    var onCallStateChanged: ((callId: String, state: String) -> Unit)? = null
    var onIncomingCall: ((callId: String, callerNumber: String, callerName: String?) -> Unit)? = null
    var onRegistrationStateChanged: ((isRegistered: Boolean) -> Unit)? = null
    var onWebViewReady: (() -> Unit)? = null
    var onTransferStateChanged: ((state: String) -> Unit)? = null
    var onTransferFailed: ((error: String) -> Unit)? = null
    var onConferenceStateChanged: ((state: String) -> Unit)? = null
    var onConferenceFailed: ((error: String) -> Unit)? = null
    var onWebSocketConnected: (() -> Unit)? = null
    var onWebSocketDisconnected: (() -> Unit)? = null
    var onReconnectFailed: ((error: String) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun initialize(webView: WebView) {
        this.webView = webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Enable WebRTC
            setGeolocationEnabled(true)
            databaseEnabled = true
        }

        // Add JavaScript interface for callbacks
        webView.addJavascriptInterface(SipJsBridge(), "AndroidSipBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "SIP.js page loaded: $url")
                // No injection needed - our HTML already has everything
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                // Grant microphone permission for WebRTC
                request?.grant(request.resources)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(TAG, "WebView Console: ${consoleMessage?.message()}")
                return true
            }
        }

        // Load the phone system HTML
        loadPhoneSystem()
        isInitialized = true
    }

    // Cache the JsSIP script content to avoid reading on main thread
    private var cachedJsSipScript: String? = null

    /**
     * Pre-load JsSIP script from assets on a background thread
     * Call this before initialize() to avoid main thread blocking
     */
    fun preloadScript() {
        if (cachedJsSipScript != null) return
        try {
            cachedJsSipScript = context.assets.open("jssip.min.js").bufferedReader().use { it.readText() }
            Log.d(TAG, "JsSIP script preloaded (${cachedJsSipScript?.length ?: 0} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload jssip.min.js from assets", e)
        }
    }

    private fun loadPhoneSystem() {
        // Load JsSIP implementation - professional and widely-used SIP library
        Log.d(TAG, "Loading JsSIP phone system")

        // Use cached script or read from assets
        val jsSipScript = cachedJsSipScript ?: try {
            context.assets.open("jssip.min.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load jssip.min.js from assets", e)
            ""
        }

        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <script>$jsSipScript</script>
</head>
<body>
<audio id="remoteAudio" autoplay></audio>
<audio id="localAudio" muted></audio>
<script>
(function() {
    'use strict';

    let ua = null;
    let currentSession = null;
    let registered = false;
    let localStream = null;
    let reconnectAttempts = 0;
    let maxReconnectAttempts = 10;
    let reconnectTimer = null;
    let isReconnecting = false;

    // Store credentials for reconnection
    let storedCredentials = null;

    // Pending reject flag - if user declines before SIP INVITE arrives
    let pendingReject = false;

    // ========== SHARED UTILITY FUNCTIONS ==========

    /**
     * Format phone number to E.164 format for SIP URIs.
     * Handles US numbers (10 or 11 digits) and international numbers.
     * @param {string} number - Raw phone number input
     * @returns {string} Formatted number with + prefix, or null if invalid
     * @throws {Error} If number is empty or contains no digits
     */
    function formatPhoneNumber(number) {
        // Input validation
        if (!number || typeof number !== 'string') {
            throw new Error('Invalid phone number: empty or not a string');
        }

        var cleanNumber = number.replace(/[^\d]/g, '');

        // Validate we have actual digits
        if (cleanNumber.length === 0) {
            throw new Error('Invalid phone number: no digits found');
        }

        // Validate minimum length (shortest valid numbers are ~3 digits for special codes)
        if (cleanNumber.length < 3) {
            throw new Error('Invalid phone number: too short');
        }

        if (cleanNumber.length === 10) {
            // US local number - add +1
            return '+1' + cleanNumber;
        } else if (cleanNumber.length === 11 && cleanNumber.charAt(0) === '1') {
            // US number with country code - add +
            return '+' + cleanNumber;
        } else {
            // International or other - ensure + prefix
            return '+' + cleanNumber;
        }
    }

    // Initialize JsSIP User Agent
    window.initSip = function(username, password, domain, wssServer, wssPort) {
        // Store credentials for reconnection
        storedCredentials = { username, password, domain, wssServer, wssPort };
        console.log('Initializing JsSIP with:', username, domain, wssServer);

        try {
            const socket = new JsSIP.WebSocketInterface('wss://' + wssServer + ':' + wssPort + '/ws');

            const configuration = {
                sockets: [socket],
                uri: 'sip:' + username + '@' + domain,
                password: password,
                register: true,
                session_timers: false,
                use_preloaded_route: false,
                // FreePBX/Twilio compatibility hacks
                hack_ip_in_contact: true,
                hack_via_ws: true,
                connection_recovery_min_interval: 2,
                connection_recovery_max_interval: 30,
                no_answer_timeout: 60,
                register_expires: 300
            };

            ua = new JsSIP.UA(configuration);

            ua.on('connected', function() {
                console.log('WebSocket connected');
                reconnectAttempts = 0;
                isReconnecting = false;
                if (reconnectTimer) {
                    clearTimeout(reconnectTimer);
                    reconnectTimer = null;
                }
                AndroidSipBridge.onWebSocketConnected();
            });

            ua.on('disconnected', function(e) {
                console.log('WebSocket disconnected', e ? e.error : '');
                // Don't auto-reconnect here - let the native side handle it
                // This prevents duplicate reconnection attempts
                AndroidSipBridge.onWebSocketDisconnected();
            });

            ua.on('registered', function() {
                console.log('SIP registered');
                registered = true;
                AndroidSipBridge.onRegistered();
            });

            ua.on('unregistered', function() {
                console.log('SIP unregistered');
                registered = false;
                AndroidSipBridge.onUnregistered();
            });

            ua.on('registrationFailed', function(e) {
                console.error('Registration failed:', e.cause);
                registered = false;
                AndroidSipBridge.onRegistrationFailed(e.cause || 'Registration failed');
            });

            ua.on('newRTCSession', function(data) {
                const session = data.session;

                if (session.direction === 'incoming') {
                    console.log('=== SIP INVITE received ===');
                    console.log('Incoming call from:', session.remote_identity.uri.user);
                    console.log('pendingReject flag:', pendingReject);
                    console.log('Session status:', session.status);

                    // Check if user already declined via FCM before SIP INVITE arrived
                    if (pendingReject) {
                        console.log('✅ Pending reject flag set - immediately rejecting incoming call');
                        pendingReject = false;
                        try {
                            session.terminate({
                                status_code: 486,
                                reason_phrase: 'Busy Here'
                            });
                            console.log('✅ SIP 486 sent for pending reject');
                        } catch (e) {
                            console.error('❌ Failed to reject pending call:', e);
                        }
                        AndroidSipBridge.onCallStateChanged('1', 'rejected');
                        return;
                    }

                    currentSession = session;
                    setupSessionEvents(session);
                    AndroidSipBridge.onIncomingCall(
                        '1',
                        session.remote_identity.uri.user || 'Unknown',
                        session.remote_identity.display_name || ''
                    );
                }
            });

            ua.start();
            console.log('JsSIP UA started');

        } catch (error) {
            console.error('JsSIP initialization error:', error);
            AndroidSipBridge.onRegistrationFailed(error.message || String(error));
        }
    };

    var callInProgress = false;

    function setupSessionEvents(session) {
        // Send immediate feedback when session starts
        session.on('sending', function(e) {
            console.log('SIP INVITE being sent');
            // Already in connecting state from sipMakeCall
        });

        session.on('progress', function(e) {
            callInProgress = true;
            var statusCode = e.response ? e.response.status_code : 0;
            console.log('Call progress - status:', statusCode);

            // 100 = Trying (server received request)
            if (statusCode === 100) {
                console.log('Server processing call');
            }
            // 180 = Ringing, 183 = Session Progress (early media)
            else if (statusCode === 180) {
                console.log('Remote party is ringing');
                AndroidSipBridge.onCallStateChanged('1', 'ringing');
            } else if (statusCode === 183) {
                console.log('Early media / Session Progress - setting up audio');
                // Setup early media immediately for faster audio
                if (session.connection) {
                    setupRemoteMedia(session);
                }
                // Send 'early' state to stop local ringback (real audio is coming from server)
                AndroidSipBridge.onCallStateChanged('1', 'early');
            }
        });

        session.on('accepted', function() {
            console.log('Call accepted');
            callInProgress = true;
            AndroidSipBridge.onCallStateChanged('1', 'connected');
        });

        session.on('confirmed', function() {
            console.log('Call confirmed');
            setupRemoteMedia(session);
        });

        session.on('ended', function(e) {
            var originator = e.originator || 'unknown';
            console.log('Call ended by:', originator);
            AndroidSipBridge.onCallStateChanged('1', 'disconnected');
            cleanupMedia();
            currentSession = null;
            callInProgress = false;
        });

        session.on('failed', function(e) {
            var failReason = e.cause || 'Unknown';
            var statusCode = e.message && e.message.status_code ? e.message.status_code : '';
            var originator = e.originator || '';
            console.error('Call failed - Cause:', failReason, 'Code:', statusCode, 'Originator:', originator);
            AndroidSipBridge.onCallStateChanged('1', 'failed');
            AndroidSipBridge.onCallFailed('', failReason + (statusCode ? ' (' + statusCode + ')' : ''));
            cleanupMedia();
            currentSession = null;
            callInProgress = false;
        });

        session.on('peerconnection', function(data) {
            console.log('PeerConnection created');
            var pc = data.peerconnection;

            // Handle remote track for audio
            pc.ontrack = function(event) {
                console.log('Remote track received');
                const remoteAudio = document.getElementById('remoteAudio');
                remoteAudio.srcObject = event.streams[0];
                remoteAudio.play().catch(e => console.log('Audio play error:', e));
            };

            // Monitor ICE connection state for faster status updates
            pc.oniceconnectionstatechange = function() {
                console.log('ICE connection state:', pc.iceConnectionState);
                if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
                    console.log('ICE connected - call is fully established');
                }
            };

            // Log ICE gathering state
            pc.onicegatheringstatechange = function() {
                console.log('ICE gathering state:', pc.iceGatheringState);
            };
        });
    }

    // Make outgoing call
    window.sipMakeCall = function(number) {
        console.log('Making call to:', number);
        if (!ua || !registered) {
            console.error('Not registered');
            AndroidSipBridge.onCallFailed(number, 'Not registered');
            return;
        }

        try {
            // Format number using shared utility
            var formattedNumber = formatPhoneNumber(number);
            console.log('Formatted number:', formattedNumber);

            // Direct media routing through FreePBX
            // Disable ICE candidates to use server relay mode
            const options = {
                mediaConstraints: { audio: true, video: false },
                pcConfig: {
                    iceServers: [],
                    bundlePolicy: 'max-bundle',
                    rtcpMuxPolicy: 'require'
                },
                rtcOfferConstraints: {
                    offerToReceiveAudio: true,
                    offerToReceiveVideo: false
                },
                sessionTimersExpires: 0
            };

            var targetUri = 'sip:' + formattedNumber + '@' + ua.configuration.uri.host;
            console.log('Calling:', targetUri);

            callInProgress = false;
            const session = ua.call(targetUri, options);
            currentSession = session;
            setupSessionEvents(session);

            AndroidSipBridge.onCallStarted('1', formattedNumber);
            // Only send connecting if we haven't already received a progress event
            if (!callInProgress) {
                AndroidSipBridge.onCallStateChanged('1', 'connecting');
            }

        } catch (error) {
            console.error('Make call error:', error);
            AndroidSipBridge.onCallFailed(number, error.message || String(error));
        }
    };

    // End call (for active/answered calls)
    window.sipEndCall = function() {
        console.log('Ending call');
        if (currentSession) {
            currentSession.terminate();
            currentSession = null;
        }
        // Clear any pending reject flag
        pendingReject = false;
    };

    // Reject incoming call (sends proper SIP 486 Busy Here response)
    window.sipRejectCall = function() {
        console.log('=== sipRejectCall called ===');
        console.log('currentSession exists:', !!currentSession);
        console.log('ua exists:', !!ua);
        console.log('registered:', registered);

        if (currentSession) {
            console.log('Session exists - checking state:', currentSession.status);
            try {
                // For JsSIP, use terminate with status code for incoming calls
                currentSession.terminate({
                    status_code: 486,
                    reason_phrase: 'Busy Here'
                });
                console.log('✅ SIP 486 Busy Here sent successfully');
            } catch (e) {
                console.error('❌ Failed to terminate session:', e);
            }
            currentSession = null;
        } else {
            // SIP INVITE hasn't arrived yet - set pending reject flag
            console.log('⚠️ No session yet - setting pending reject flag');
            pendingReject = true;

            // Also set a timeout to clear the flag if no call arrives
            setTimeout(function() {
                if (pendingReject) {
                    console.log('Clearing stale pending reject flag');
                    pendingReject = false;
                }
            }, 30000); // 30 second timeout
        }

        AndroidSipBridge.onCallStateChanged('1', 'rejected');
    };

    // Answer incoming call
    window.sipAnswerCall = function() {
        console.log('Answering call');
        if (currentSession) {
            // Send connecting state immediately for faster UI response
            AndroidSipBridge.onCallStateChanged('1', 'connecting');

            // Minimal ICE config - FreePBX handles media routing
            const options = {
                mediaConstraints: { audio: true, video: false },
                pcConfig: {
                    iceServers: [],
                    bundlePolicy: 'max-bundle',
                    rtcpMuxPolicy: 'require'
                }
            };
            currentSession.answer(options);
        }
    };

    // Setup remote audio
    function setupRemoteMedia(session) {
        try {
            const pc = session.connection;
            if (pc) {
                const remoteAudio = document.getElementById('remoteAudio');
                const receivers = pc.getReceivers();
                if (receivers.length > 0) {
                    const remoteStream = new MediaStream();
                    receivers.forEach(function(receiver) {
                        if (receiver.track) {
                            remoteStream.addTrack(receiver.track);
                        }
                    });
                    remoteAudio.srcObject = remoteStream;
                    remoteAudio.play().catch(e => console.log('Audio play error:', e));
                }
            }
        } catch (e) {
            console.error('Setup remote media error:', e);
        }
    }

    function cleanupMedia() {
        if (localStream) {
            localStream.getTracks().forEach(track => track.stop());
            localStream = null;
        }
        const remoteAudio = document.getElementById('remoteAudio');
        remoteAudio.srcObject = null;
    }

    // Mute/unmute
    window.sipMute = function(mute) {
        if (currentSession && currentSession.connection) {
            const senders = currentSession.connection.getSenders();
            senders.forEach(function(sender) {
                if (sender.track && sender.track.kind === 'audio') {
                    sender.track.enabled = !mute;
                }
            });
            console.log('Mute set to:', mute);
        }
    };

    // Send DTMF with options for better compatibility
    window.sipSendDtmf = function(digit) {
        console.log('sipSendDtmf called with digit:', digit, 'currentSession:', currentSession ? 'exists' : 'null');
        if (currentSession) {
            try {
                // Send DTMF with duration options for better compatibility
                currentSession.sendDTMF(digit, {
                    duration: 160,        // Duration in ms (standard is 160ms)
                    interToneGap: 100     // Gap between tones if sending multiple
                });
                console.log('DTMF sent successfully:', digit);
                AndroidSipBridge.onLog('DTMF sent: ' + digit);
            } catch (e) {
                console.error('DTMF send failed:', e);
                AndroidSipBridge.onLog('DTMF failed: ' + e.message);
            }
        } else {
            console.error('Cannot send DTMF - no active session');
            AndroidSipBridge.onLog('DTMF failed - no active session');
        }
    };

    // Hold/unhold
    window.sipHold = function(hold) {
        if (currentSession) {
            if (hold) {
                currentSession.hold();
            } else {
                currentSession.unhold();
            }
            console.log('Hold set to:', hold);
        }
    };

    // ========== ATTENDED TRANSFER ==========
    var transferSession = null;
    var originalSessionOnHold = null;

    // Start attended transfer - puts current call on hold and calls the target
    window.sipStartAttendedTransfer = function(targetNumber) {
        console.log('Starting attended transfer to:', targetNumber);

        if (!currentSession || !ua || !registered) {
            console.error('No active call or not registered');
            AndroidSipBridge.onTransferFailed('No active call');
            return;
        }

        try {
            // Put current call on hold
            currentSession.hold();
            originalSessionOnHold = currentSession;

            // Format the target number using shared utility
            var formattedNumber = formatPhoneNumber(targetNumber);

            // Make consultation call to transfer target
            const options = {
                mediaConstraints: { audio: true, video: false },
                pcConfig: {
                    iceServers: [],
                    bundlePolicy: 'max-bundle',
                    rtcpMuxPolicy: 'require'
                }
            };

            var targetUri = 'sip:' + formattedNumber + '@' + ua.configuration.uri.host;
            console.log('Calling transfer target:', targetUri);

            transferSession = ua.call(targetUri, options);

            transferSession.on('progress', function(e) {
                console.log('Transfer call progress');
                AndroidSipBridge.onTransferStateChanged('ringing');
            });

            transferSession.on('accepted', function() {
                console.log('Transfer target answered - ready to complete transfer');
                AndroidSipBridge.onTransferStateChanged('connected');
            });

            transferSession.on('failed', function(e) {
                console.error('Transfer call failed:', e.cause);
                // Resume original call
                if (originalSessionOnHold) {
                    originalSessionOnHold.unhold();
                }
                transferSession = null;
                AndroidSipBridge.onTransferFailed(e.cause || 'Transfer call failed');
            });

            transferSession.on('ended', function() {
                console.log('Transfer call ended');
                transferSession = null;
            });

            AndroidSipBridge.onTransferStateChanged('calling');

        } catch (error) {
            console.error('Start transfer error:', error);
            if (originalSessionOnHold) {
                originalSessionOnHold.unhold();
            }
            AndroidSipBridge.onTransferFailed(error.message || 'Transfer failed');
        }
    };

    // Complete attended transfer - connect the two parties
    window.sipCompleteTransfer = function() {
        console.log('Completing attended transfer');

        if (!originalSessionOnHold || !transferSession) {
            console.error('Missing sessions for transfer completion');
            AndroidSipBridge.onTransferFailed('Missing sessions');
            return;
        }

        try {
            // Use SIP REFER to transfer the original call to the transfer target
            // The transferSession's remote identity becomes the target
            var targetUri = transferSession.remote_identity.uri;

            // Send REFER with Replaces header for attended transfer
            originalSessionOnHold.refer(targetUri, {
                replaces: transferSession
            });

            console.log('Transfer REFER sent');
            AndroidSipBridge.onTransferStateChanged('completed');

            // End our sessions - the parties will be connected directly
            transferSession.terminate();
            transferSession = null;
            originalSessionOnHold = null;
            currentSession = null;

        } catch (error) {
            console.error('Complete transfer error:', error);
            AndroidSipBridge.onTransferFailed(error.message || 'Transfer completion failed');
        }
    };

    // Cancel attended transfer - end consultation and resume original call
    window.sipCancelTransfer = function() {
        console.log('Cancelling attended transfer');

        if (transferSession) {
            transferSession.terminate();
            transferSession = null;
        }

        if (originalSessionOnHold) {
            originalSessionOnHold.unhold();
            currentSession = originalSessionOnHold;
            originalSessionOnHold = null;
        }

        AndroidSipBridge.onTransferStateChanged('cancelled');
    };

    // ========== CONFERENCE / ADD CALL ==========
    var conferenceSession = null;
    var conferenceActive = false;
    var conferenceAudioMixer = null;
    var conferenceAudioContext = null;

    // Start add call for conference - puts current call on hold and calls new party
    window.sipStartAddCall = function(targetNumber) {
        console.log('Starting add call to:', targetNumber);

        if (!currentSession || !ua || !registered) {
            console.error('No active call or not registered');
            AndroidSipBridge.onConferenceFailed('No active call');
            return;
        }

        try {
            // Put current call on hold
            currentSession.hold();

            // Format the target number using shared utility
            var formattedNumber = formatPhoneNumber(targetNumber);

            // Make call to new party
            const options = {
                mediaConstraints: { audio: true, video: false },
                pcConfig: {
                    iceServers: [],
                    bundlePolicy: 'max-bundle',
                    rtcpMuxPolicy: 'require'
                }
            };

            var targetUri = 'sip:' + formattedNumber + '@' + ua.configuration.uri.host;
            console.log('Calling conference target:', targetUri);

            conferenceSession = ua.call(targetUri, options);

            conferenceSession.on('progress', function(e) {
                console.log('Conference call progress');
                AndroidSipBridge.onConferenceStateChanged('ringing');
            });

            conferenceSession.on('accepted', function() {
                console.log('Conference call answered - ready to merge');
                AndroidSipBridge.onConferenceStateChanged('connected');
            });

            conferenceSession.on('failed', function(e) {
                console.error('Conference call failed:', e.cause);
                // Resume original call
                if (currentSession) {
                    currentSession.unhold();
                }
                conferenceSession = null;
                AndroidSipBridge.onConferenceFailed(e.cause || 'Conference call failed');
            });

            conferenceSession.on('ended', function() {
                console.log('Conference party left');
                if (conferenceActive) {
                    destroyConferenceAudioBridge();
                    conferenceActive = false;
                    AndroidSipBridge.onConferenceStateChanged('ended');
                }
                conferenceSession = null;
            });

            AndroidSipBridge.onConferenceStateChanged('calling');

        } catch (error) {
            console.error('Start add call error:', error);
            if (currentSession) {
                currentSession.unhold();
            }
            AndroidSipBridge.onConferenceFailed(error.message || 'Add call failed');
        }
    };

    // Create professional audio bridge for conference
    function createConferenceAudioBridge() {
        try {
            console.log('Creating conference audio bridge');

            // Create audio context
            var AudioContextClass = window.AudioContext || window.webkitAudioContext;
            if (!conferenceAudioContext) {
                conferenceAudioContext = new AudioContextClass();
            }

            if (conferenceAudioContext.state === 'suspended') {
                conferenceAudioContext.resume();
            }

            var mixer = {
                audioContext: conferenceAudioContext,
                sources: {},
                gains: {},
                destinations: {}
            };

            // Get peer connections from both sessions
            var pc1 = currentSession.connection;
            var pc2 = conferenceSession.connection;

            if (!pc1 || !pc2) {
                console.error('Missing peer connections for audio bridge');
                return null;
            }

            // Create destinations for each party
            var dest1 = conferenceAudioContext.createMediaStreamDestination();
            var dest2 = conferenceAudioContext.createMediaStreamDestination();
            mixer.destinations.party1 = dest1;
            mixer.destinations.party2 = dest2;

            // Get remote audio from party 1 (original call)
            var receivers1 = pc1.getReceivers();
            receivers1.forEach(function(receiver) {
                if (receiver.track && receiver.track.kind === 'audio') {
                    var remoteStream1 = new MediaStream([receiver.track]);
                    var source1 = conferenceAudioContext.createMediaStreamSource(remoteStream1);
                    var gain1 = conferenceAudioContext.createGain();
                    gain1.gain.value = 0.9;
                    source1.connect(gain1);
                    gain1.connect(dest2); // Party 1's audio goes to Party 2
                    mixer.sources.party1 = source1;
                    mixer.gains.party1to2 = gain1;
                    console.log('Connected party 1 audio to party 2');
                }
            });

            // Get remote audio from party 2 (conference call)
            var receivers2 = pc2.getReceivers();
            receivers2.forEach(function(receiver) {
                if (receiver.track && receiver.track.kind === 'audio') {
                    var remoteStream2 = new MediaStream([receiver.track]);
                    var source2 = conferenceAudioContext.createMediaStreamSource(remoteStream2);
                    var gain2 = conferenceAudioContext.createGain();
                    gain2.gain.value = 0.9;
                    source2.connect(gain2);
                    gain2.connect(dest1); // Party 2's audio goes to Party 1
                    mixer.sources.party2 = source2;
                    mixer.gains.party2to1 = gain2;
                    console.log('Connected party 2 audio to party 1');
                }
            });

            // Get local microphone and connect to both destinations
            if (localStream) {
                var localSource = conferenceAudioContext.createMediaStreamSource(localStream);
                var localGain1 = conferenceAudioContext.createGain();
                var localGain2 = conferenceAudioContext.createGain();
                localGain1.gain.value = 1.0;
                localGain2.gain.value = 1.0;
                localSource.connect(localGain1);
                localSource.connect(localGain2);
                localGain1.connect(dest1);
                localGain2.connect(dest2);
                mixer.sources.local = localSource;
                mixer.gains.localTo1 = localGain1;
                mixer.gains.localTo2 = localGain2;
                console.log('Connected local mic to both parties');
            }

            // Replace audio sender tracks with mixed audio
            // Party 1 gets: local mic + party 2's audio
            var senders1 = pc1.getSenders();
            senders1.forEach(function(sender) {
                if (sender.track && sender.track.kind === 'audio') {
                    var mixedTrack1 = dest1.stream.getAudioTracks()[0];
                    if (mixedTrack1) {
                        sender.replaceTrack(mixedTrack1).then(function() {
                            console.log('Replaced party 1 audio with mixed stream');
                        }).catch(function(e) {
                            console.error('Failed to replace party 1 track:', e);
                        });
                    }
                }
            });

            // Party 2 gets: local mic + party 1's audio
            var senders2 = pc2.getSenders();
            senders2.forEach(function(sender) {
                if (sender.track && sender.track.kind === 'audio') {
                    var mixedTrack2 = dest2.stream.getAudioTracks()[0];
                    if (mixedTrack2) {
                        sender.replaceTrack(mixedTrack2).then(function() {
                            console.log('Replaced party 2 audio with mixed stream');
                        }).catch(function(e) {
                            console.error('Failed to replace party 2 track:', e);
                        });
                    }
                }
            });

            conferenceAudioMixer = mixer;
            console.log('Conference audio bridge created successfully');
            return mixer;

        } catch (error) {
            console.error('Failed to create conference audio bridge:', error);
            return null;
        }
    }

    // Destroy conference audio bridge
    function destroyConferenceAudioBridge() {
        try {
            console.log('Destroying conference audio bridge');

            if (conferenceAudioMixer) {
                // Disconnect all sources
                if (conferenceAudioMixer.sources) {
                    Object.values(conferenceAudioMixer.sources).forEach(function(source) {
                        try { source.disconnect(); } catch(e) {}
                    });
                }

                // Disconnect all gains
                if (conferenceAudioMixer.gains) {
                    Object.values(conferenceAudioMixer.gains).forEach(function(gain) {
                        try { gain.disconnect(); } catch(e) {}
                    });
                }

                conferenceAudioMixer = null;
            }

            // Don't close audio context - it might be reused
            console.log('Conference audio bridge destroyed');

        } catch (error) {
            console.error('Error destroying audio bridge:', error);
        }
    }

    // Merge calls into conference with professional audio mixing
    window.sipMergeConference = function() {
        console.log('Merging calls into conference with audio mixing');

        if (!currentSession || !conferenceSession) {
            console.error('Missing sessions for conference');
            AndroidSipBridge.onConferenceFailed('Missing sessions');
            return;
        }

        try {
            // Unhold the original call
            currentSession.unhold();
            conferenceActive = true;

            // Create professional audio bridge that mixes both calls
            // Each party will hear: local user + the other party
            setTimeout(function() {
                createConferenceAudioBridge();
            }, 500); // Small delay to let unhold settle

            console.log('Conference active with professional audio mixing');
            AndroidSipBridge.onConferenceStateChanged('active');

        } catch (error) {
            console.error('Merge conference error:', error);
            AndroidSipBridge.onConferenceFailed(error.message || 'Conference merge failed');
        }
    };

    // End conference - end the added call and resume original
    window.sipEndConference = function() {
        console.log('Ending conference');

        // Destroy audio bridge first
        destroyConferenceAudioBridge();

        if (conferenceSession) {
            conferenceSession.terminate();
            conferenceSession = null;
        }

        conferenceActive = false;

        // Original call continues
        if (currentSession) {
            AndroidSipBridge.onConferenceStateChanged('ended');
        }
    };

    // ========== BLIND TRANSFER ==========
    // Blind transfer - directly transfer call without consultation
    // Uses proper event-driven handling for REFER success/failure
    window.sipBlindTransfer = function(targetNumber) {
        console.log('Executing blind transfer to:', targetNumber);

        if (!currentSession) {
            console.error('No active call for blind transfer');
            AndroidSipBridge.onTransferFailed('No active call');
            return;
        }

        if (!ua || !registered) {
            console.error('Not registered for blind transfer');
            AndroidSipBridge.onTransferFailed('Not registered');
            return;
        }

        try {
            // Use shared number formatting function
            var formattedNumber = formatPhoneNumber(targetNumber);

            // Build target URI for REFER
            var targetUri = 'sip:' + formattedNumber + '@' + ua.configuration.uri.host;
            console.log('Blind transfer target URI:', targetUri);

            // Store reference to session for event handling
            var transferringSession = currentSession;

            // Report initiated state immediately
            AndroidSipBridge.onTransferStateChanged('initiated');

            // Send SIP REFER for blind transfer (no Replaces header)
            // The eventHandlers option tracks the REFER request outcome:
            // - requestSucceeded: Server accepted REFER (202 Accepted)
            // - requestFailed: Server rejected REFER (4xx/5xx/6xx)
            transferringSession.refer(targetUri, {
                eventHandlers: {
                    requestSucceeded: function(e) {
                        console.log('Blind transfer REFER accepted (202)');
                        AndroidSipBridge.onTransferStateChanged('completed');
                        // Session will end via BYE from server after transfer completes
                    },
                    requestFailed: function(e) {
                        var cause = e.cause || 'Unknown error';
                        console.error('Blind transfer REFER failed:', cause);
                        AndroidSipBridge.onTransferFailed('Transfer rejected: ' + cause);
                        // Session remains active - transfer failed
                    }
                }
            });

            console.log('Blind transfer REFER sent');

        } catch (error) {
            console.error('Blind transfer error:', error);
            AndroidSipBridge.onTransferFailed(error.message || 'Blind transfer failed');
        }
    };

    // ========== SIP UNREGISTER ==========
    // Send SIP UNREGISTER request to properly disconnect from server
    window.sipUnregister = function() {
        console.log('Sending SIP UNREGISTER');
        if (ua) {
            try {
                // End any active calls first
                if (currentSession) {
                    currentSession.terminate();
                    currentSession = null;
                }
                if (conferenceSession) {
                    conferenceSession.terminate();
                    conferenceSession = null;
                }
                if (transferSession) {
                    transferSession.terminate();
                    transferSession = null;
                }

                // Unregister from SIP server
                ua.unregister({ all: true });

                // Stop the UA
                setTimeout(function() {
                    if (ua) {
                        ua.stop();
                        ua = null;
                    }
                    registered = false;
                    console.log('SIP UA stopped');
                }, 1000);

            } catch (error) {
                console.error('Unregister error:', error);
            }
        }
    };

    // ========== SIP RECONNECT ==========
    // Reconnect SIP after network change - properly reinitializes the UA
    window.sipReconnect = function() {
        console.log('SIP Reconnect requested');

        if (!storedCredentials) {
            console.error('No stored credentials for reconnection');
            AndroidSipBridge.onReconnectFailed('No credentials');
            return;
        }

        if (isReconnecting) {
            console.log('Already reconnecting, ignoring request');
            return;
        }

        isReconnecting = true;
        reconnectAttempts++;

        console.log('Reconnection attempt ' + reconnectAttempts + '/' + maxReconnectAttempts);

        // Clean up existing UA if any
        if (ua) {
            try {
                // End any active calls
                if (currentSession) {
                    currentSession.terminate();
                    currentSession = null;
                }

                // Stop the UA without unregister (network is probably down anyway)
                ua.stop();
                ua = null;
            } catch (error) {
                console.error('Error cleaning up UA:', error);
            }
        }

        registered = false;

        // Wait a moment then reinitialize
        setTimeout(function() {
            try {
                console.log('Reinitializing SIP with stored credentials');
                window.initSip(
                    storedCredentials.username,
                    storedCredentials.password,
                    storedCredentials.domain,
                    storedCredentials.wssServer,
                    storedCredentials.wssPort
                );
            } catch (error) {
                console.error('Reconnection error:', error);
                isReconnecting = false;
                AndroidSipBridge.onReconnectFailed(error.message || 'Reconnection failed');
            }
        }, 1000);
    };

    // Check if SIP is connected
    window.sipIsConnected = function() {
        return ua !== null && registered;
    };

    // Get connection state
    window.sipGetState = function() {
        return JSON.stringify({
            hasUA: ua !== null,
            registered: registered,
            isReconnecting: isReconnecting,
            reconnectAttempts: reconnectAttempts
        });
    };

    console.log('JsSIP phone system ready');
    AndroidSipBridge.onWebViewReady();
})();
</script>
</body>
</html>
        """.trimIndent()

        // Load HTML with local base URL since JsSIP is bundled
        webView?.loadDataWithBaseURL(
            "file:///android_asset/",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    fun register(extension: String, password: String, domain: String, wssServer: String, wssPort: String) {
        Log.d(TAG, "register called: extension=$extension, domain=$domain, wssServer=$wssServer:$wssPort")

        // Call our clean initSip function
        val executeRegistration: () -> Unit = {
            val script = "window.initSip('$extension', '$password', '$domain', '$wssServer', '$wssPort');"
            mainHandler.post {
                webView?.evaluateJavascript(script, null)
            }
        }

        // Check if WebView is ready - if not, queue the registration
        if (isWebViewReady) {
            Log.d(TAG, "WebView ready, executing registration immediately")
            executeRegistration()
        } else {
            Log.d(TAG, "WebView not ready yet, queueing registration")
            pendingRegistration = executeRegistration
        }
    }

    fun makeCall(phoneNumber: String) {
        Log.d(TAG, "Making call to: $phoneNumber")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipMakeCall('$phoneNumber');", null)
        }
    }

    fun endCall(lineNumber: Int = 1) {
        Log.d(TAG, "Ending call")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipEndCall();", null)
        }
    }

    /**
     * Reject an incoming call with proper SIP 486 response.
     * If the SIP session hasn't arrived yet (FCM arrived first),
     * sets a pending reject flag to reject when the INVITE arrives.
     */
    fun rejectCall(lineNumber: Int = 1) {
        Log.d(TAG, "=== rejectCall called ===")
        Log.d(TAG, "WebView initialized: $isInitialized, WebView ready: $isWebViewReady")
        Log.d(TAG, "WebView object exists: ${webView != null}")

        if (webView == null) {
            Log.e(TAG, "WebView is null! Cannot reject call")
            return
        }

        mainHandler.post {
            Log.d(TAG, "Executing sipRejectCall() on WebView")
            webView?.evaluateJavascript("window.sipRejectCall();") { result ->
                Log.d(TAG, "sipRejectCall result: $result")
            }
        }
    }

    fun answerCall(lineNumber: Int = 1) {
        Log.d(TAG, "Answering call")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipAnswerCall();", null)
        }
    }

    fun muteCall(lineNumber: Int = 1, mute: Boolean) {
        Log.d(TAG, "Setting mute=$mute")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipMute($mute);", null)
        }
    }

    fun holdCall(lineNumber: Int = 1, hold: Boolean) {
        Log.d(TAG, "Setting hold=$hold")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipHold($hold);", null)
        }
    }

    fun sendDtmf(lineNumber: Int = 1, digit: String) {
        Log.d(TAG, "Sending DTMF $digit")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipSendDtmf('$digit');", null)
        }
    }

    // ========== BLIND TRANSFER ==========

    /**
     * Execute blind transfer - immediately transfers the call without consultation.
     * The current call is transferred to the target and ends for this user.
     */
    fun blindTransfer(targetNumber: String) {
        Log.d(TAG, "Executing blind transfer to: $targetNumber")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipBlindTransfer('$targetNumber');", null)
        }
    }

    // ========== ATTENDED TRANSFER ==========

    fun startAttendedTransfer(targetNumber: String) {
        Log.d(TAG, "Starting attended transfer to: $targetNumber")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipStartAttendedTransfer('$targetNumber');", null)
        }
    }

    fun completeTransfer() {
        Log.d(TAG, "Completing attended transfer")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipCompleteTransfer();", null)
        }
    }

    fun cancelTransfer() {
        Log.d(TAG, "Cancelling attended transfer")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipCancelTransfer();", null)
        }
    }

    // ========== CONFERENCE / ADD CALL ==========

    fun startAddCall(targetNumber: String) {
        Log.d(TAG, "Starting add call for conference to: $targetNumber")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipStartAddCall('$targetNumber');", null)
        }
    }

    fun mergeConference() {
        Log.d(TAG, "Merging calls into conference")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipMergeConference();", null)
        }
    }

    fun endConference() {
        Log.d(TAG, "Ending conference")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipEndConference();", null)
        }
    }

    fun getRegistrationStatus(callback: (Boolean) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript("(typeof registered !== 'undefined' && registered)") { result ->
                callback(result == "true")
            }
        }
    }

    /**
     * Send SIP UNREGISTER request to properly disconnect from server.
     * This should be called before logout to cleanly terminate the SIP session.
     */
    fun unregister() {
        Log.d(TAG, "Sending SIP UNREGISTER")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipUnregister();", null)
        }
        _isRegistered.value = false
    }

    /**
     * Reconnect SIP after network change.
     * This properly reinitializes the JsSIP User Agent with stored credentials.
     * Call this after network transitions (WiFi <-> Cellular) for seamless reconnection.
     */
    fun reconnect() {
        Log.d(TAG, "Requesting SIP reconnect")
        mainHandler.post {
            webView?.evaluateJavascript("window.sipReconnect();", null)
        }
    }

    /**
     * Check if SIP is currently connected
     */
    fun isConnected(): Boolean = _isRegistered.value

    /**
     * Get connection state as JSON (for debugging)
     */
    fun getConnectionState(callback: (String) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript("window.sipGetState()") { result ->
                callback(result)
            }
        }
    }

    fun destroy() {
        // Unregister first for clean shutdown
        unregister()

        // Wait a bit for unregister to complete, then destroy
        mainHandler.postDelayed({
            webView?.destroy()
            webView = null
            isInitialized = false
            isWebViewReady = false
            pendingRegistration = null
        }, 500)
    }

    /**
     * JavaScript interface for callbacks from WebView to native
     */
    inner class SipJsBridge {
        @JavascriptInterface
        fun onRegistered() {
            Log.d(TAG, "SIP Registered")
            _isRegistered.value = true
            onRegistrationStateChanged?.invoke(true)
        }

        @JavascriptInterface
        fun onUnregistered() {
            Log.d(TAG, "SIP Unregistered")
            _isRegistered.value = false
            onRegistrationStateChanged?.invoke(false)
        }

        @JavascriptInterface
        fun onRegistrationFailed(error: String) {
            Log.e(TAG, "SIP Registration failed: $error")
            _isRegistered.value = false
            onRegistrationStateChanged?.invoke(false)
        }

        @JavascriptInterface
        fun onCallStarting(phoneNumber: String) {
            Log.d(TAG, "Call starting to: $phoneNumber")
            _callState.value = "connecting"
        }

        @JavascriptInterface
        fun onCallStarted(lineNumber: String, phoneNumber: String) {
            Log.d(TAG, "Call started on line $lineNumber to $phoneNumber")
            _activeCallId.value = lineNumber
            _callState.value = "connecting"
            onCallStateChanged?.invoke(lineNumber, "connecting")
        }

        @JavascriptInterface
        fun onCallFailed(phoneNumber: String, error: String) {
            Log.e(TAG, "Call to $phoneNumber failed: $error")
            _callState.value = "failed"
            _activeCallId.value?.let { onCallStateChanged?.invoke(it, "failed") }
        }

        @JavascriptInterface
        fun onCallStateChanged(callId: String, state: String) {
            Log.d(TAG, "Call $callId state: $state")
            _callState.value = state
            onCallStateChanged?.invoke(callId, state)
        }

        @JavascriptInterface
        fun onIncomingCall(callId: String, callerNumber: String, callerName: String) {
            Log.d(TAG, "Incoming call from $callerNumber ($callerName)")
            _activeCallId.value = callId
            _callState.value = "incoming"
            onIncomingCall?.invoke(callId, callerNumber, callerName.ifEmpty { null })
        }

        @JavascriptInterface
        fun onLog(message: String) {
            Log.d(TAG, "JS: $message")
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "JS Log: $message")
        }

        // ========== TRANSFER CALLBACKS ==========

        @JavascriptInterface
        fun onTransferStateChanged(state: String) {
            Log.d(TAG, "Transfer state: $state")
            onTransferStateChanged?.invoke(state)
        }

        @JavascriptInterface
        fun onTransferFailed(error: String) {
            Log.e(TAG, "Transfer failed: $error")
            onTransferFailed?.invoke(error)
        }

        // ========== CONFERENCE CALLBACKS ==========

        @JavascriptInterface
        fun onConferenceStateChanged(state: String) {
            Log.d(TAG, "Conference state: $state")
            onConferenceStateChanged?.invoke(state)
        }

        @JavascriptInterface
        fun onConferenceFailed(error: String) {
            Log.e(TAG, "Conference failed: $error")
            onConferenceFailed?.invoke(error)
        }

        @JavascriptInterface
        fun onWebViewReady() {
            Log.d(TAG, "WebView phone system ready")
            isWebViewReady = true

            // Execute any pending registration that was queued
            pendingRegistration?.let { registration ->
                Log.d(TAG, "Executing queued registration")
                registration()
                pendingRegistration = null
            }

            onWebViewReady?.invoke()
        }

        // ========== WEBSOCKET/RECONNECTION CALLBACKS ==========

        @JavascriptInterface
        fun onWebSocketConnected() {
            Log.d(TAG, "WebSocket connected")
            onWebSocketConnected?.invoke()
        }

        @JavascriptInterface
        fun onWebSocketDisconnected() {
            Log.d(TAG, "WebSocket disconnected")
            onWebSocketDisconnected?.invoke()
        }

        @JavascriptInterface
        fun onReconnectFailed(error: String) {
            Log.e(TAG, "SIP reconnect failed: $error")
            onReconnectFailed?.invoke(error)
        }
    }
}
