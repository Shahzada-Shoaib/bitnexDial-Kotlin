package com.bitnextechnologies.bitnexdial.presentation.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.data.repository.ContactRepository
import com.bitnextechnologies.bitnexdial.data.sip.SipCallManager
import com.bitnextechnologies.bitnexdial.data.sip.SipCallState
import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.CallDirection
import com.bitnextechnologies.bitnexdial.domain.model.CallStatus
import com.bitnextechnologies.bitnexdial.domain.model.CallType
import com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository
import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import java.util.UUID
import com.bitnextechnologies.bitnexdial.util.CallRecordingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "InCallViewModel"

@HiltViewModel
class InCallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sipRepository: ISipRepository,
    private val sipCallManager: SipCallManager,
    private val contactRepository: IContactRepository,
    private val deviceContactRepository: ContactRepository,
    private val callRepository: ICallRepository,
    private val socketManager: SocketManager,
    private val callRecordingManager: CallRecordingManager
) : ViewModel() {

    private var callId: String = ""
    private var isOutgoingCall: Boolean = false
    private var hasRegisteredListener: Boolean = false
    private var callStartTime: Long = 0L
    private var hasEmittedCallStarted: Boolean = false

    private val _phoneNumber = MutableStateFlow("")
    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()

    private val _contactName = MutableStateFlow<String?>(null)
    val contactName: StateFlow<String?> = _contactName.asStateFlow()

    private val _callStatus = MutableStateFlow(CallStatus.CONNECTING)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeaker = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    private val _isOnHold = MutableStateFlow(false)
    val isOnHold: StateFlow<Boolean> = _isOnHold.asStateFlow()

    private val _showDialpad = MutableStateFlow(false)
    val showDialpad: StateFlow<Boolean> = _showDialpad.asStateFlow()

    // Transfer state
    private val _isTransferMode = MutableStateFlow(false)
    val isTransferMode: StateFlow<Boolean> = _isTransferMode.asStateFlow()

    private val _transferState = MutableStateFlow("")
    val transferState: StateFlow<String> = _transferState.asStateFlow()

    // Conference state
    private val _isConferenceMode = MutableStateFlow(false)
    val isConferenceMode: StateFlow<Boolean> = _isConferenceMode.asStateFlow()

    private val _conferenceState = MutableStateFlow("")
    val conferenceState: StateFlow<String> = _conferenceState.asStateFlow()

    // Recording state - exposed from CallRecordingManager
    val isRecording: StateFlow<Boolean> = callRecordingManager.isRecording
    val isRecordingPaused: StateFlow<Boolean> = callRecordingManager.isPaused

    // Call waiting state - exposed from SipCallManager
    val waitingCall = sipCallManager.waitingCall
    val hasWaitingCall = sipCallManager.hasWaitingCall

    // Held call state - exposed from SipCallManager (for call switching)
    val heldCall = sipCallManager.heldCall
    val hasHeldCall = sipCallManager.hasHeldCall

    private var durationJob: Job? = null
    private var ringbackJob: Job? = null
    @Volatile
    private var toneGenerator: ToneGenerator? = null
    @Volatile
    private var dtmfToneGenerator: ToneGenerator? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // DTMF tone mapping to ToneGenerator constants
    private val dtmfToneMap = mapOf(
        "0" to ToneGenerator.TONE_DTMF_0,
        "1" to ToneGenerator.TONE_DTMF_1,
        "2" to ToneGenerator.TONE_DTMF_2,
        "3" to ToneGenerator.TONE_DTMF_3,
        "4" to ToneGenerator.TONE_DTMF_4,
        "5" to ToneGenerator.TONE_DTMF_5,
        "6" to ToneGenerator.TONE_DTMF_6,
        "7" to ToneGenerator.TONE_DTMF_7,
        "8" to ToneGenerator.TONE_DTMF_8,
        "9" to ToneGenerator.TONE_DTMF_9,
        "*" to ToneGenerator.TONE_DTMF_S,
        "#" to ToneGenerator.TONE_DTMF_P
    )

    fun setCallInfo(id: String, number: String, isIncoming: Boolean, passedContactName: String? = null) {
        callId = id
        _phoneNumber.value = number
        isOutgoingCall = !isIncoming
        callStartTime = System.currentTimeMillis()

        // If contact name was passed (e.g., from call history), use it immediately
        if (!passedContactName.isNullOrBlank()) {
            _contactName.value = passedContactName
            Log.d(TAG, "Using passed contact name: $passedContactName")
        }

        // Initialize audio routing to earpiece (not speaker) for professional call experience
        initializeAudioForCall()

        // Start ringback for outgoing calls
        if (isOutgoingCall) {
            startRingback()
        }

        // Look up contact name from API contacts first, then device contacts
        // Only if we don't already have a contact name
        viewModelScope.launch {
            try {
                // Skip lookup if we already have a name
                if (!passedContactName.isNullOrBlank()) {
                    Log.d(TAG, "Skipping contact lookup, already have name: $passedContactName")
                    return@launch
                }

                val contact = contactRepository.getContactByPhoneNumber(number)
                // Try API contacts first, then fall back to device contacts
                val displayName = contact?.displayName
                    ?: deviceContactRepository.getContactName(number)
                _contactName.value = displayName

                // Emit CallStarted event for real-time call history update (optimistic)
                // This makes the call appear in the history immediately when it starts
                if (!hasEmittedCallStarted) {
                    hasEmittedCallStarted = true
                    val direction = if (isOutgoingCall) "outbound" else "inbound"
                    socketManager.emitLocalCallStarted(
                        callId = callId,
                        number = number,
                        direction = direction,
                        name = displayName
                    )
                    Log.d(TAG, "Emitted CallStarted event: $number ($direction)")
                }
            } catch (e: Exception) {
                // API lookup failed - try device contacts as fallback
                val deviceName = deviceContactRepository.getContactName(number)
                _contactName.value = deviceName

                // Still emit the event with whatever name we found
                if (!hasEmittedCallStarted) {
                    hasEmittedCallStarted = true
                    val direction = if (isOutgoingCall) "outbound" else "inbound"
                    socketManager.emitLocalCallStarted(
                        callId = callId,
                        number = number,
                        direction = direction,
                        name = null
                    )
                    Log.d(TAG, "Emitted CallStarted event: $number ($direction)")
                }
            }
        }

        // Register direct callback for call state changes (event-driven, not polling)
        registerCallStateListener()
    }

    /**
     * Register a direct callback listener for call state changes.
     * This is the professional approach - event-driven architecture where
     * the ViewModel is notified immediately when state changes, not through polling.
     */
    private fun registerCallStateListener() {
        if (hasRegisteredListener) return
        hasRegisteredListener = true

        sipCallManager.registerCallStateListener(callId) { sipState ->
            // Process state change on Main thread for proper Compose recomposition
            viewModelScope.launch(Dispatchers.Main.immediate) {
                // Map SipCallState to CallStatus and update UI immediately
                val newStatus = when (sipState) {
                    SipCallState.IDLE -> CallStatus.DISCONNECTED
                    SipCallState.CONNECTING -> CallStatus.CONNECTING
                    SipCallState.RINGING -> CallStatus.RINGING
                    SipCallState.EARLY_MEDIA -> CallStatus.RINGING  // Still ringing, but with real audio
                    SipCallState.CONNECTED -> CallStatus.CONNECTED
                    SipCallState.ON_HOLD -> CallStatus.ON_HOLD
                    SipCallState.DISCONNECTING -> CallStatus.DISCONNECTING
                    SipCallState.DISCONNECTED -> CallStatus.DISCONNECTED
                    SipCallState.FAILED -> CallStatus.FAILED
                    SipCallState.BUSY -> CallStatus.FAILED
                    SipCallState.REJECTED -> CallStatus.FAILED
                }

                _callStatus.value = newStatus

                // Handle state-specific actions
                when (sipState) {
                    SipCallState.EARLY_MEDIA -> {
                        // Stop local ringback - real audio is coming from server
                        stopRingback()
                        // Don't start timer yet - call not answered
                    }
                    SipCallState.CONNECTED -> {
                        stopRingback()
                        if (durationJob == null) {
                            startDurationTimer()
                        }
                    }
                    SipCallState.ON_HOLD -> {
                        _isOnHold.value = true
                    }
                    SipCallState.DISCONNECTED, SipCallState.FAILED, SipCallState.BUSY, SipCallState.REJECTED -> {
                        stopRingback()
                        stopDurationTimer()
                        // Emit CallEndedWithMetrics for real-time call history update
                        emitCallEnded(sipState)
                    }
                    else -> {
                        // CONNECTING, RINGING - ringback continues
                    }
                }
            }
        }
    }

    /**
     * Emit CallEndedWithMetrics event for real-time call history update.
     * This updates the call record with final duration and status.
     * Also saves the call directly to the repository for immediate persistence.
     */
    private fun emitCallEnded(sipState: SipCallState) {
        val durationSeconds = _callDuration.value.toInt() // Duration in seconds
        val direction = if (isOutgoingCall) "outbound" else "inbound"

        // Determine status based on SIP state
        val status = when (sipState) {
            SipCallState.DISCONNECTED -> if (durationSeconds > 0) "answered" else "no-answer"
            SipCallState.FAILED -> "failed"
            SipCallState.BUSY -> "busy"
            SipCallState.REJECTED -> if (isOutgoingCall) "no-answer" else "rejected"
            else -> "answered"
        }

        // Emit socket event for real-time sync across devices
        socketManager.emitLocalCallEnded(
            callId = callId,
            number = _phoneNumber.value,
            direction = direction,
            duration = durationSeconds,
            status = status
        )
        Log.d(TAG, "Emitted CallEndedWithMetrics: ${_phoneNumber.value}, duration=${durationSeconds}s, status=$status")

        // Save call directly to repository for immediate local persistence
        // This ensures call appears in history even if RecentsViewModel is not active
        // Use NonCancellable to ensure the save completes even if ViewModel scope is cancelled
        viewModelScope.launch {
            // Wrap in NonCancellable to prevent cancellation when activity finishes
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val callDirection = if (isOutgoingCall) CallDirection.OUTGOING else CallDirection.INCOMING

                    // Determine call type based on direction and status
                    // Outgoing calls are NEVER missed - only incoming calls can be missed
                    val callType = when {
                        isOutgoingCall -> {
                            if (durationSeconds > 0) CallType.ANSWERED else CallType.OUTGOING
                        }
                        status == "no-answer" || status == "missed" ||
                        status == "failed" || status == "busy" -> CallType.MISSED
                        else -> CallType.ANSWERED
                    }

                    val call = Call(
                        id = callId.ifEmpty { UUID.randomUUID().toString() },
                        callId = callId,
                        phoneNumber = _phoneNumber.value,
                        contactName = _contactName.value,
                        direction = callDirection,
                        status = CallStatus.DISCONNECTED,
                        type = callType,
                        startTime = callStartTime,
                        endTime = System.currentTimeMillis(),
                        duration = durationSeconds * 1000L, // Convert to milliseconds
                        isRead = callType != CallType.MISSED // Missed calls start as unread
                    )

                    callRepository.saveCall(call)
                    Log.d(TAG, "Saved call to repository: ${_phoneNumber.value}, type=$callType, duration=${durationSeconds}s")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save call to repository: ${e.message}", e)
                }
            }
        }
    }

    private fun startDurationTimer() {
        durationJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _callDuration.value += 1
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
    }

    private fun startRingback() {
        ringbackJob?.cancel()
        ringbackJob = null

        ringbackJob = viewModelScope.launch(Dispatchers.IO) {
            var localToneGenerator: ToneGenerator? = null
            try {
                // Ensure audio mode is set for voice call before creating ToneGenerator
                // This must be done on main thread for some devices
                withContext(Dispatchers.Main) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    setSpeakerphoneEnabled(false)
                }

                // Small delay to ensure audio routing is applied
                delay(100)

                // Use STREAM_VOICE_CALL for earpiece routing during calls
                localToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
                toneGenerator = localToneGenerator

                while (isActive) {
                    localToneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000)
                    delay(4000) // Standard ringback pattern: 1s tone, 3s silence
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ringback tone failed - non-critical", e)
            } finally {
                try {
                    localToneGenerator?.stopTone()
                    localToneGenerator?.release()
                } catch (e: Exception) {
                    // Cleanup error - non-critical
                }
                toneGenerator = null
            }
        }
    }

    private fun stopRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
    }

    fun toggleMute() {
        viewModelScope.launch {
            try {
                val newMuteState = !_isMuted.value
                sipRepository.muteCall(callId, newMuteState)
                _isMuted.value = newMuteState
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun toggleSpeaker() {
        _isSpeaker.value = !_isSpeaker.value
        setSpeakerphoneEnabled(_isSpeaker.value)
    }

    /**
     * Initialize audio routing for a call.
     * Sets audio mode to IN_COMMUNICATION and routes to earpiece by default.
     * This ensures sound comes from earpiece, not speaker, when call starts.
     */
    private fun initializeAudioForCall() {
        try {
            // Set audio mode for voice call
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Ensure speaker is off and audio goes to earpiece
            _isSpeaker.value = false
            setSpeakerphoneEnabled(false)

            Log.d(TAG, "Audio initialized for call: mode=IN_COMMUNICATION, speaker=OFF")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio for call", e)
        }
    }

    /**
     * Set speakerphone enabled/disabled using modern API for Android 12+
     * with backward compatibility for older versions.
     * Enhanced for better stereo speaker support.
     */
    private fun setSpeakerphoneEnabled(enabled: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): Use setCommunicationDevice
                val devices = audioManager.availableCommunicationDevices
                if (enabled) {
                    // Find speaker device - prefer the built-in speaker
                    val speakerDevice = devices.find {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    speakerDevice?.let { device ->
                        val success = audioManager.setCommunicationDevice(device)
                        Log.d(TAG, "Set speaker device: $success, device=${device.productName}")
                    }
                } else {
                    // Find earpiece or default device
                    val earpieceDevice = devices.find {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    if (earpieceDevice != null) {
                        audioManager.setCommunicationDevice(earpieceDevice)
                        Log.d(TAG, "Set earpiece device: ${earpieceDevice.productName}")
                    } else {
                        audioManager.clearCommunicationDevice()
                        Log.d(TAG, "Cleared communication device")
                    }
                }
            } else {
                // Older Android versions: Use deprecated but still functional API
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
                Log.d(TAG, "Set legacy speakerphone: $enabled")
            }

            // Ensure volume is at a reasonable level for speaker mode
            if (enabled) {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                // If volume is very low, bump it up for speaker mode
                if (currentVolume < maxVolume * 0.5) {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        (maxVolume * 0.7).toInt(),
                        0
                    )
                }
            }

            Log.d(TAG, "Speakerphone ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speakerphone", e)
        }
    }

    fun toggleHold() {
        viewModelScope.launch {
            try {
                if (_isOnHold.value) {
                    sipRepository.resumeCall(callId)
                } else {
                    sipRepository.holdCall(callId)
                }
                _isOnHold.value = !_isOnHold.value
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun toggleDialpad() {
        _showDialpad.value = !_showDialpad.value
    }

    /**
     * Answer the waiting call (puts current call on hold)
     */
    fun answerWaitingCall() {
        sipCallManager.answerWaitingCall()
    }

    /**
     * Decline the waiting call
     */
    fun declineWaitingCall() {
        sipCallManager.declineWaitingCall()
    }

    /**
     * Switch between current call and held call
     */
    fun switchCalls() {
        sipCallManager.switchCalls()
    }

    /**
     * End the held call and continue with current call
     */
    fun endHeldCall() {
        sipCallManager.endHeldCall()
    }

    /**
     * End current call and resume held call
     */
    fun endCurrentAndResumeHeld() {
        sipCallManager.endCurrentAndResumeHeld()
    }

    fun sendDtmf(digit: String) {
        // Play local DTMF tone for audio feedback
        playDtmfTone(digit)

        // Send DTMF through SIP
        viewModelScope.launch {
            try {
                sipRepository.sendDtmf(callId, digit)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Play local DTMF tone for audio feedback when user presses keypad buttons.
     * This provides immediate audio confirmation similar to the web dialer.
     */
    private fun playDtmfTone(digit: String) {
        try {
            val toneType = dtmfToneMap[digit] ?: return

            // Create ToneGenerator if needed
            if (dtmfToneGenerator == null) {
                dtmfToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            }

            // Play the DTMF tone for 150ms (matches web dialer duration)
            dtmfToneGenerator?.startTone(toneType, 150)
        } catch (e: Exception) {
            // Non-critical - DTMF still sent via SIP even if local tone fails
        }
    }

    fun endCall() {
        stopRingback()
        viewModelScope.launch {
            try {
                sipRepository.endCall(callId)
            } catch (e: Exception) {
                // Handle error
            }
        }
        stopDurationTimer()
    }

    // ========== ATTENDED TRANSFER ==========

    fun startTransfer(targetNumber: String) {
        _isTransferMode.value = true
        _transferState.value = "calling"
        viewModelScope.launch {
            try {
                sipRepository.startAttendedTransfer(callId, targetNumber)
            } catch (e: Exception) {
                _isTransferMode.value = false
                _transferState.value = ""
            }
        }
    }

    fun completeTransfer() {
        viewModelScope.launch {
            try {
                sipRepository.completeAttendedTransfer(callId)
                _transferState.value = "completed"
                // Call will end after transfer completes
            } catch (e: Exception) {
                _transferState.value = "failed"
            }
        }
    }

    fun cancelTransfer() {
        viewModelScope.launch {
            try {
                sipRepository.cancelAttendedTransfer(callId)
            } catch (e: Exception) {
                // Handle error
            }
        }
        _isTransferMode.value = false
        _transferState.value = ""
    }

    fun updateTransferState(state: String) {
        _transferState.value = state
        if (state == "cancelled" || state == "completed" || state == "failed") {
            _isTransferMode.value = false
        }
    }

    // ========== CONFERENCE / ADD CALL ==========

    fun startAddCall(targetNumber: String) {
        _isConferenceMode.value = true
        _conferenceState.value = "calling"
        viewModelScope.launch {
            try {
                sipRepository.startAddCall(callId, targetNumber)
            } catch (e: Exception) {
                _isConferenceMode.value = false
                _conferenceState.value = ""
            }
        }
    }

    fun mergeConference() {
        viewModelScope.launch {
            try {
                sipRepository.mergeConference(callId)
                _conferenceState.value = "active"
            } catch (e: Exception) {
                _conferenceState.value = "failed"
            }
        }
    }

    fun endConference() {
        viewModelScope.launch {
            try {
                sipRepository.endConference(callId)
            } catch (e: Exception) {
                // Handle error
            }
        }
        _isConferenceMode.value = false
        _conferenceState.value = ""
    }

    fun updateConferenceState(state: String) {
        _conferenceState.value = state
        if (state == "ended" || state == "failed") {
            _isConferenceMode.value = false
        }
    }

    // ========== CALL RECORDING ==========

    /**
     * Toggle call recording on/off.
     * Note: Call recording has legal restrictions in many jurisdictions.
     * Ensure compliance with local laws and always inform all parties.
     */
    fun toggleRecording() {
        if (callRecordingManager.isRecording.value) {
            callRecordingManager.stopRecording()
            Log.d(TAG, "Recording stopped")
        } else {
            callRecordingManager.startRecording(
                callId = callId,
                phoneNumber = _phoneNumber.value,
                contactName = _contactName.value
            )
            Log.d(TAG, "Recording started for: ${_phoneNumber.value}")
        }
    }

    /**
     * Start recording the current call.
     */
    fun startRecording() {
        if (!callRecordingManager.isRecording.value) {
            callRecordingManager.startRecording(
                callId = callId,
                phoneNumber = _phoneNumber.value,
                contactName = _contactName.value
            )
            Log.d(TAG, "Recording started for: ${_phoneNumber.value}")
        }
    }

    /**
     * Stop recording the current call.
     */
    fun stopRecording() {
        if (callRecordingManager.isRecording.value) {
            callRecordingManager.stopRecording()
            Log.d(TAG, "Recording stopped")
        }
    }

    /**
     * Pause the current recording (Android N+ only).
     */
    fun pauseRecording() {
        callRecordingManager.pauseRecording()
    }

    /**
     * Resume a paused recording (Android N+ only).
     */
    fun resumeRecording() {
        callRecordingManager.resumeRecording()
    }

    override fun onCleared() {
        super.onCleared()
        stopDurationTimer()
        stopRingback()
        // Stop recording if active
        if (callRecordingManager.isRecording.value) {
            callRecordingManager.stopRecording()
        }
        // Release DTMF tone generator
        try {
            dtmfToneGenerator?.release()
            dtmfToneGenerator = null
        } catch (e: Exception) {
            // Cleanup error - non-critical
        }
        // Unregister the call state listener
        if (hasRegisteredListener && callId.isNotEmpty()) {
            sipCallManager.unregisterCallStateListener(callId)
        }
        // Reset speaker to earpiece/default using modern API
        setSpeakerphoneEnabled(false)
    }
}
