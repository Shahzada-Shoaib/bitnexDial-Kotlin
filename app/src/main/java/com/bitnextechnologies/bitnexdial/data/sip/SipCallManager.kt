package com.bitnextechnologies.bitnexdial.data.sip

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.CallDirection
import com.bitnextechnologies.bitnexdial.domain.model.CallStatus
import com.bitnextechnologies.bitnexdial.data.local.dao.VoicemailDao
import com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IMessageRepository
import com.bitnextechnologies.bitnexdial.util.BadgeManager
import com.bitnextechnologies.bitnexdial.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SIP Call states
 */
enum class SipCallState {
    IDLE,
    CONNECTING,
    RINGING,
    EARLY_MEDIA,
    CONNECTED,
    ON_HOLD,
    DISCONNECTING,
    DISCONNECTED,
    FAILED,
    BUSY,
    REJECTED
}

/**
 * Manages SIP calls and their lifecycle
 */
@Singleton
class SipCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sipEngine: SipEngine,
    private val socketManager: SocketManager,
    private val callRepository: Lazy<ICallRepository>,
    private val messageRepository: Lazy<IMessageRepository>,
    private val voicemailDao: VoicemailDao,
    private val badgeManager: BadgeManager
) {
    companion object {
        private const val TAG = "SipCallManager"
        private const val MAX_LINES = 4
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active calls map (callId -> Call)
    private val _activeCalls = MutableStateFlow<Map<String, Call>>(emptyMap())
    val activeCalls: StateFlow<Map<String, Call>> = _activeCalls.asStateFlow()

    // Track call start times for duration calculation
    private val callStartTimes = mutableMapOf<String, Long>()

    // Track if a call was ever connected (answered)
    private val answeredCalls = mutableSetOf<String>()

    // Current active call
    private val _currentCall = MutableStateFlow<Call?>(null)
    val currentCall: StateFlow<Call?> = _currentCall.asStateFlow()

    // Current call state (for observing from UI)
    private val _currentCallState = MutableStateFlow(SipCallState.IDLE)
    val currentCallState: StateFlow<SipCallState> = _currentCallState.asStateFlow()

    // Waiting call (incoming call while on another call)
    private val _waitingCall = MutableStateFlow<Call?>(null)
    val waitingCall: StateFlow<Call?> = _waitingCall.asStateFlow()

    // Has waiting call indicator
    val hasWaitingCall: StateFlow<Boolean> = _waitingCall.map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // Held call (call on hold while talking to another)
    private val _heldCall = MutableStateFlow<Call?>(null)
    val heldCall: StateFlow<Call?> = _heldCall.asStateFlow()

    // Has held call indicator
    val hasHeldCall: StateFlow<Boolean> = _heldCall.map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // Call state listeners
    private val callStateListeners = mutableMapOf<String, (SipCallState) -> Unit>()

    // Internal call state tracking
    private val callStates = mutableMapOf<String, SipCallState>()

    init {
        // Listen to SIP engine events
        setupSipEngineListeners()
    }

    private fun setupSipEngineListeners() {
        sipEngine.setCallStateCallback { callId, state ->
            handleCallStateChange(callId, state)
        }

        sipEngine.setIncomingCallCallback { callId, callerNumber, callerName ->
            handleIncomingCall(callId, callerNumber, callerName)
        }
    }

    /**
     * Make an outgoing call
     */
    fun makeCall(phoneNumber: String, callId: String): Boolean {
        Log.d(TAG, "Making call to $phoneNumber with id $callId")

        if (_activeCalls.value.size >= MAX_LINES) {
            Log.w(TAG, "Maximum number of calls reached")
            return false
        }

        val call = Call.createOutgoing(
            number = phoneNumber,
            lineNumber = findAvailableLine()
        ).copy(id = callId)

        // Add to active calls
        _activeCalls.value = _activeCalls.value + (callId to call)
        _currentCall.value = call
        callStates[callId] = SipCallState.CONNECTING

        // Track call start time
        callStartTimes[callId] = System.currentTimeMillis()

        // Emit call started event for real-time UI updates
        socketManager.emitLocalCallStarted(
            callId = callId,
            number = phoneNumber,
            direction = "outbound",
            name = null
        )

        // Initiate SIP call
        scope.launch {
            try {
                sipEngine.makeCall(phoneNumber, callId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to make call", e)
                handleCallStateChange(callId, SipCallState.FAILED)
            }
        }

        return true
    }

    /**
     * Answer an incoming call
     */
    fun answerCall(callId: String): Boolean {
        Log.d(TAG, "Answering call $callId")

        val call = _activeCalls.value[callId] ?: return false

        scope.launch {
            try {
                sipEngine.answerCall(callId)
                updateCallStatus(callId, CallStatus.CONNECTED)
                callStates[callId] = SipCallState.CONNECTED
                // Mark this call as answered (not missed)
                answeredCalls.add(callId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to answer call", e)
            }
        }

        return true
    }

    /**
     * Reject an incoming call
     */
    fun rejectCall(callId: String): Boolean {
        Log.d(TAG, "Rejecting call $callId")

        scope.launch {
            try {
                sipEngine.rejectCall(callId)
                handleCallStateChange(callId, SipCallState.REJECTED)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject call", e)
            }
        }

        return true
    }

    /**
     * End an active call
     */
    fun endCall(callId: String): Boolean {
        Log.d(TAG, "Ending call $callId")

        scope.launch {
            try {
                sipEngine.endCall(callId)
                handleCallStateChange(callId, SipCallState.DISCONNECTED)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end call", e)
            }
        }

        return true
    }

    /**
     * Hold a call
     */
    fun holdCall(callId: String): Boolean {
        Log.d(TAG, "Holding call $callId")

        scope.launch {
            try {
                sipEngine.holdCall(callId)
                updateCallStatus(callId, CallStatus.ON_HOLD)
                callStates[callId] = SipCallState.ON_HOLD
                notifyCallStateListener(callId, SipCallState.ON_HOLD)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hold call", e)
            }
        }

        return true
    }

    /**
     * Resume a held call
     */
    fun resumeCall(callId: String): Boolean {
        Log.d(TAG, "Resuming call $callId")

        scope.launch {
            try {
                sipEngine.resumeCall(callId)
                updateCallStatus(callId, CallStatus.CONNECTED)
                callStates[callId] = SipCallState.CONNECTED
                notifyCallStateListener(callId, SipCallState.CONNECTED)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume call", e)
            }
        }

        return true
    }

    /**
     * Mute/unmute call
     */
    fun setMute(callId: String, mute: Boolean): Boolean {
        Log.d(TAG, "Setting mute $mute for call $callId")

        scope.launch {
            try {
                sipEngine.setMute(callId, mute)
                _activeCalls.value[callId]?.let { call ->
                    val updatedCall = call.copy(isMuted = mute)
                    _activeCalls.value = _activeCalls.value + (callId to updatedCall)
                    if (_currentCall.value?.id == callId) {
                        _currentCall.value = updatedCall
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set mute", e)
            }
        }

        return true
    }

    /**
     * Toggle mute on a call
     */
    fun toggleMute(callId: String): Boolean {
        val call = _activeCalls.value[callId] ?: return false
        return setMute(callId, !call.isMuted)
    }

    /**
     * Send DTMF tone
     */
    fun sendDtmf(callId: String, digit: String): Boolean {
        Log.d(TAG, "Sending DTMF $digit for call $callId")

        scope.launch {
            try {
                sipEngine.sendDtmf(callId, digit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send DTMF", e)
            }
        }

        return true
    }

    /**
     * Transfer call (blind)
     */
    fun transferCall(callId: String, destination: String): Boolean {
        Log.d(TAG, "Transferring call $callId to $destination")

        scope.launch {
            try {
                sipEngine.transferCall(callId, destination)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to transfer call", e)
            }
        }

        return true
    }

    /**
     * Start attended transfer - puts current call on hold and calls target
     */
    fun startAttendedTransfer(callId: String, targetNumber: String): Boolean {
        Log.d(TAG, "Starting attended transfer from $callId to $targetNumber")

        scope.launch {
            try {
                sipEngine.startAttendedTransfer(targetNumber)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start attended transfer", e)
            }
        }

        return true
    }

    /**
     * Complete attended transfer - connects the two parties
     */
    fun completeAttendedTransfer(callId: String): Boolean {
        Log.d(TAG, "Completing attended transfer for $callId")

        scope.launch {
            try {
                sipEngine.completeAttendedTransfer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete attended transfer", e)
            }
        }

        return true
    }

    /**
     * Cancel attended transfer - ends consultation and resumes original call
     */
    fun cancelAttendedTransfer(callId: String): Boolean {
        Log.d(TAG, "Cancelling attended transfer for $callId")

        scope.launch {
            try {
                sipEngine.cancelAttendedTransfer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel attended transfer", e)
            }
        }

        return true
    }

    /**
     * Start add call for conference - puts current call on hold and calls new party
     */
    fun startAddCall(callId: String, targetNumber: String): Boolean {
        Log.d(TAG, "Starting add call from $callId to $targetNumber")

        scope.launch {
            try {
                sipEngine.startAddCall(targetNumber)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start add call", e)
            }
        }

        return true
    }

    /**
     * Merge calls into conference
     */
    fun mergeConference(callId: String): Boolean {
        Log.d(TAG, "Merging conference for $callId")

        scope.launch {
            try {
                sipEngine.mergeConference()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to merge conference", e)
            }
        }

        return true
    }

    /**
     * End conference - ends the added call
     */
    fun endConference(callId: String): Boolean {
        Log.d(TAG, "Ending conference for $callId")

        scope.launch {
            try {
                sipEngine.endConference()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end conference", e)
            }
        }

        return true
    }

    /**
     * Register call state listener
     */
    fun registerCallStateListener(callId: String, listener: (SipCallState) -> Unit) {
        callStateListeners[callId] = listener
        // Immediately notify with current state if available
        callStates[callId]?.let { listener(it) }
    }

    /**
     * Unregister call state listener
     */
    fun unregisterCallStateListener(callId: String) {
        callStateListeners.remove(callId)
    }

    /**
     * Handle incoming call from SIP
     */
    private fun handleIncomingCall(callId: String, callerNumber: String, callerName: String?) {
        Log.d(TAG, "Incoming call from $callerNumber")

        val call = Call.createIncoming(
            number = callerNumber,
            name = callerName,
            callId = callId,
            lineNumber = findAvailableLine()
        )

        // If there's already an active call, set this as a waiting call (call waiting)
        if (hasActiveCall()) {
            Log.d(TAG, "Call waiting: Incoming call while on another call")
            _waitingCall.value = call
        }

        _activeCalls.value = _activeCalls.value + (callId to call)
        callStates[callId] = SipCallState.RINGING

        // CRITICAL: Update the state flow so IncomingCallActivity observes RINGING, not stale DISCONNECTED
        _currentCallState.value = SipCallState.RINGING

        // Track call start time for duration calculation
        callStartTimes[callId] = System.currentTimeMillis()

        // Emit call started event for real-time UI updates
        socketManager.emitLocalCallStarted(
            callId = callId,
            number = callerNumber,
            direction = "inbound",
            name = callerName
        )

        // The ConnectionService will handle showing the incoming call UI
    }

    /**
     * Handle call state change
     */
    private fun handleCallStateChange(callId: String, state: SipCallState) {
        Log.d(TAG, "Call state changed: $callId -> $state")

        callStates[callId] = state
        _currentCallState.value = state  // Update the flow for UI observers
        notifyCallStateListener(callId, state)

        when (state) {
            SipCallState.CONNECTING -> {
                updateCallStatus(callId, CallStatus.CONNECTING)
            }
            SipCallState.RINGING -> {
                updateCallStatus(callId, CallStatus.RINGING)
            }
            SipCallState.CONNECTED, SipCallState.EARLY_MEDIA -> {
                updateCallStatus(callId, CallStatus.CONNECTED)
                // Mark call as answered when it connects
                answeredCalls.add(callId)
            }
            SipCallState.ON_HOLD -> {
                updateCallStatus(callId, CallStatus.ON_HOLD)
            }
            SipCallState.DISCONNECTED -> {
                updateCallStatus(callId, CallStatus.DISCONNECTED)
                emitCallEndedEvent(callId, "answered")
                removeCall(callId)
                // CRITICAL: Broadcast call ended to dismiss IncomingCallActivity
                // This handles the case where app was closed and remote party ends call
                broadcastCallEnded(callId)
            }
            SipCallState.FAILED -> {
                updateCallStatus(callId, CallStatus.FAILED)
                emitCallEndedEvent(callId, "failed")
                removeCall(callId)
                // CRITICAL: Broadcast call ended to dismiss IncomingCallActivity
                broadcastCallEnded(callId)
            }
            SipCallState.REJECTED -> {
                updateCallStatus(callId, CallStatus.FAILED)
                emitCallEndedEvent(callId, "no-answer")
                removeCall(callId)
                // CRITICAL: Broadcast call ended to dismiss IncomingCallActivity
                broadcastCallEnded(callId)
            }
            SipCallState.BUSY -> {
                updateCallStatus(callId, CallStatus.FAILED)
                emitCallEndedEvent(callId, "busy")
                removeCall(callId)
                // CRITICAL: Broadcast call ended to dismiss IncomingCallActivity
                broadcastCallEnded(callId)
            }
            else -> {
                // IDLE state - no action needed
            }
        }
    }

    /**
     * Emit call ended event for real-time UI updates
     * This enables the call history to update immediately when calls end
     */
    private fun emitCallEndedEvent(callId: String, status: String) {
        val call = _activeCalls.value[callId] ?: return
        val startTime = callStartTimes[callId] ?: System.currentTimeMillis()
        val endTime = System.currentTimeMillis()
        val durationSeconds = ((endTime - startTime) / 1000).toInt()

        // Determine the actual status
        // For incoming calls that were never answered, mark as "no-answer" (missed)
        val wasAnswered = answeredCalls.contains(callId)
        val actualStatus = when {
            call.direction == CallDirection.INCOMING && !wasAnswered -> "no-answer"
            status == "answered" && durationSeconds > 0 -> "answered"
            else -> status
        }

        val direction = if (call.direction == CallDirection.OUTGOING) "outbound" else "inbound"

        Log.d(TAG, "Emitting call ended event: callId=$callId, duration=$durationSeconds, status=$actualStatus, direction=$direction")

        socketManager.emitLocalCallEnded(
            callId = callId,
            number = call.phoneNumber,
            direction = direction,
            duration = durationSeconds,
            status = actualStatus
        )

        // CRITICAL: If this was a missed call, save to database and update badge immediately
        if (actualStatus == "no-answer" && call.direction == CallDirection.INCOMING) {
            Log.d(TAG, "Detected missed call from ${call.phoneNumber}, saving to database")
            scope.launch {
                try {
                    // Save missed call to database
                    callRepository.get().saveMissedCall(
                        phoneNumber = call.phoneNumber,
                        contactName = call.contactName,
                        timestamp = startTime
                    )
                    Log.d(TAG, "Missed call saved to database")

                    // Update badge immediately
                    val missedCount = callRepository.get().getUnreadMissedCallCountDirect()
                    val unreadMessages = messageRepository.get().getTotalUnreadCountDirect()
                    val unreadVoicemails = voicemailDao.getUnreadVoicemailCountDirect()
                    Log.d(TAG, "Updating badge after missed call: missedCalls=$missedCount, messages=$unreadMessages, voicemails=$unreadVoicemails")

                    badgeManager.updateBadgeCount(missedCount, unreadMessages, unreadVoicemails)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving missed call or updating badge", e)
                }
            }
        }

        // Clean up tracking maps
        callStartTimes.remove(callId)
        answeredCalls.remove(callId)
    }

    /**
     * Update call status
     */
    private fun updateCallStatus(callId: String, status: CallStatus) {
        _activeCalls.value[callId]?.let { call ->
            val updatedCall = call.copy(status = status)
            _activeCalls.value = _activeCalls.value + (callId to updatedCall)
            if (_currentCall.value?.id == callId) {
                _currentCall.value = updatedCall
            }
        }
    }

    /**
     * Remove call from active calls
     */
    private fun removeCall(callId: String) {
        _activeCalls.value = _activeCalls.value - callId
        callStates.remove(callId)
        callStateListeners.remove(callId)

        // Clear waiting call if it was this call
        if (_waitingCall.value?.id == callId) {
            _waitingCall.value = null
        }

        // Clear held call if it was this call
        if (_heldCall.value?.id == callId) {
            _heldCall.value = null
            Log.d(TAG, "Held call ended and cleared")
        }

        // If current call ended and there's a held call, resume the held call
        if (_currentCall.value?.id == callId) {
            val held = _heldCall.value
            if (held != null) {
                Log.d(TAG, "Current call ended, resuming held call: ${held.id}")
                scope.launch {
                    try {
                        sipEngine.resumeCall(held.id)
                        _currentCall.value = held.copy(status = CallStatus.CONNECTED)
                        _heldCall.value = null
                        _activeCalls.value = _activeCalls.value + (held.id to held.copy(status = CallStatus.CONNECTED))
                        callStates[held.id] = SipCallState.CONNECTED
                        notifyCallStateListener(held.id, SipCallState.CONNECTED)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resume held call after current ended", e)
                        _currentCall.value = _activeCalls.value.values.firstOrNull()
                    }
                }
            } else {
                _currentCall.value = _activeCalls.value.values.firstOrNull()
            }
        }

        // Reset call state to IDLE when no more active calls
        // This ensures the next incoming call UI won't see stale DISCONNECTED state
        if (_activeCalls.value.isEmpty()) {
            _currentCallState.value = SipCallState.IDLE
            _waitingCall.value = null
            _heldCall.value = null
            Log.d(TAG, "All calls ended, reset state to IDLE")
        }
    }

    /**
     * Clear the waiting call (call answered or declined)
     */
    fun clearWaitingCall() {
        _waitingCall.value = null
    }

    /**
     * Answer the waiting call (puts current call on hold)
     */
    fun answerWaitingCall() {
        val waiting = _waitingCall.value ?: return
        val current = _currentCall.value

        Log.d(TAG, "Answering waiting call: ${waiting.id}, current call: ${current?.id}")

        scope.launch {
            try {
                // Put current call on hold first
                current?.let { call ->
                    Log.d(TAG, "Putting current call ${call.id} on hold")
                    sipEngine.holdCall(call.id)
                    // Track the held call for switch/end functionality
                    _heldCall.value = call.copy(status = CallStatus.ON_HOLD)
                    // Update the call in active calls map
                    _activeCalls.value = _activeCalls.value + (call.id to call.copy(status = CallStatus.ON_HOLD))
                    callStates[call.id] = SipCallState.ON_HOLD
                }

                // Answer the waiting call
                sipEngine.answerCall(waiting.id)
                answeredCalls.add(waiting.id)

                // Switch current call to the waiting call
                _currentCall.value = waiting.copy(status = CallStatus.CONNECTED)
                _waitingCall.value = null

                // Update the waiting call status in active calls
                _activeCalls.value = _activeCalls.value + (waiting.id to waiting.copy(status = CallStatus.CONNECTED))
                callStates[waiting.id] = SipCallState.CONNECTED
                notifyCallStateListener(waiting.id, SipCallState.CONNECTED)

                Log.d(TAG, "Switched to waiting call, previous call on hold")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to answer waiting call", e)
            }
        }
    }

    /**
     * Switch between current call and held call
     */
    fun switchCalls() {
        val current = _currentCall.value ?: return
        val held = _heldCall.value ?: return

        Log.d(TAG, "Switching calls: current=${current.id}, held=${held.id}")

        scope.launch {
            try {
                // Put current call on hold
                sipEngine.holdCall(current.id)

                // Resume the held call
                sipEngine.resumeCall(held.id)

                // Swap the calls
                _currentCall.value = held.copy(status = CallStatus.CONNECTED)
                _heldCall.value = current.copy(status = CallStatus.ON_HOLD)

                // Update active calls map
                _activeCalls.value = _activeCalls.value +
                    (held.id to held.copy(status = CallStatus.CONNECTED)) +
                    (current.id to current.copy(status = CallStatus.ON_HOLD))

                // Update call states
                callStates[current.id] = SipCallState.ON_HOLD
                callStates[held.id] = SipCallState.CONNECTED

                notifyCallStateListener(current.id, SipCallState.ON_HOLD)
                notifyCallStateListener(held.id, SipCallState.CONNECTED)

                Log.d(TAG, "Calls switched successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch calls", e)
            }
        }
    }

    /**
     * End the held call and continue with current call
     */
    fun endHeldCall() {
        val held = _heldCall.value ?: return

        Log.d(TAG, "Ending held call: ${held.id}")

        scope.launch {
            try {
                sipEngine.endCall(held.id)
                _heldCall.value = null
                Log.d(TAG, "Held call ended")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end held call", e)
            }
        }
    }

    /**
     * End current call and resume held call
     */
    fun endCurrentAndResumeHeld() {
        val current = _currentCall.value ?: return
        val held = _heldCall.value

        Log.d(TAG, "Ending current call ${current.id} and resuming held call ${held?.id}")

        scope.launch {
            try {
                // End current call
                sipEngine.endCall(current.id)

                // Resume held call if exists
                held?.let { heldCall ->
                    sipEngine.resumeCall(heldCall.id)
                    _currentCall.value = heldCall.copy(status = CallStatus.CONNECTED)
                    _heldCall.value = null

                    // Update call state
                    _activeCalls.value = _activeCalls.value + (heldCall.id to heldCall.copy(status = CallStatus.CONNECTED))
                    callStates[heldCall.id] = SipCallState.CONNECTED
                    notifyCallStateListener(heldCall.id, SipCallState.CONNECTED)

                    Log.d(TAG, "Resumed held call after ending current")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end current and resume held", e)
            }
        }
    }

    /**
     * Decline the waiting call (send to voicemail/reject)
     */
    fun declineWaitingCall() {
        val waiting = _waitingCall.value ?: return

        Log.d(TAG, "Declining waiting call: ${waiting.id}")

        scope.launch {
            try {
                // End the waiting call
                sipEngine.endCall(waiting.id)

                // Log as missed call
                callRepository.get().saveMissedCall(
                    phoneNumber = waiting.phoneNumber,
                    contactName = waiting.contactName,
                    timestamp = System.currentTimeMillis()
                )
                Log.d(TAG, "Waiting call logged as missed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log declined waiting call", e)
            }
        }

        _waitingCall.value = null
    }

    /**
     * Broadcast call ended event to dismiss IncomingCallActivity.
     * This is critical for the case where app was closed and remote party ends call.
     * The broadcast ensures IncomingCallActivity is dismissed even if:
     * - The FCM push didn't trigger a call_cancelled message
     * - The SIP session started/ended before IncomingCallActivity could observe it
     */
    private fun broadcastCallEnded(callId: String) {
        try {
            Log.d(TAG, "Broadcasting call ended for callId=$callId")

            // Dismiss incoming call notification
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1001) // NOTIFICATION_ID_INCOMING_CALL from PushCallService

            // Send broadcast to dismiss IncomingCallActivity
            val intent = Intent(Constants.ACTION_CALL_CANCELLED).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
                setPackage(context.packageName) // Explicit package for Android 13+ compatibility
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Call ended broadcast sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting call ended", e)
        }
    }

    /**
     * Notify call state listener
     */
    private fun notifyCallStateListener(callId: String, state: SipCallState) {
        callStateListeners[callId]?.invoke(state)
    }

    /**
     * Find available line number
     */
    private fun findAvailableLine(): Int {
        val usedLines = _activeCalls.value.values.map { it.lineNumber }.toSet()
        return (1..MAX_LINES).first { it !in usedLines }
    }

    /**
     * Get active call count
     */
    fun getActiveCallCount(): Int = _activeCalls.value.size

    /**
     * Check if there's an active call
     */
    fun hasActiveCall(): Boolean = _activeCalls.value.isNotEmpty()

    /**
     * Get call by ID
     */
    fun getCall(callId: String): Call? = _activeCalls.value[callId]
}
