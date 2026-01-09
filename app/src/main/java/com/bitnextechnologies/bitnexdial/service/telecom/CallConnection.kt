package com.bitnextechnologies.bitnexdial.service.telecom

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import com.bitnextechnologies.bitnexdial.data.sip.SipCallManager
import com.bitnextechnologies.bitnexdial.data.sip.SipCallState
import com.bitnextechnologies.bitnexdial.presentation.call.InCallActivity
import com.bitnextechnologies.bitnexdial.util.Constants

/**
 * Represents an individual call connection
 * Handles call state changes and user actions
 */
class CallConnection(
    private val context: Context,
    val callId: String,
    val phoneNumber: String,
    val isIncoming: Boolean,
    private val sipCallManager: SipCallManager
) : Connection() {

    companion object {
        private const val TAG = "CallConnection"
    }

    private var callStartTime: Long = 0
    private var isMutedState = false
    private var isOnHoldState = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        Log.d(TAG, "CallConnection created: $callId, incoming: $isIncoming")

        // Register for SIP call state changes
        sipCallManager.registerCallStateListener(callId) { state ->
            handler.post { handleSipCallState(state) }
        }
    }

    /**
     * User answers incoming call
     */
    override fun onAnswer() {
        Log.d(TAG, "onAnswer: $callId")
        setActive()
        callStartTime = System.currentTimeMillis()
        sipCallManager.answerCall(callId)
        launchInCallScreen()
    }

    override fun onAnswer(videoState: Int) {
        onAnswer()
    }

    /**
     * User rejects incoming call
     */
    override fun onReject() {
        Log.d(TAG, "onReject: $callId")
        sipCallManager.rejectCall(callId)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        CallConnectionService.removeConnection(callId)
    }

    /**
     * User ends active call
     */
    override fun onDisconnect() {
        Log.d(TAG, "onDisconnect: $callId")
        sipCallManager.endCall(callId)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        CallConnectionService.removeConnection(callId)
    }

    /**
     * User aborts call (before connection)
     */
    override fun onAbort() {
        Log.d(TAG, "onAbort: $callId")
        sipCallManager.endCall(callId)
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
        CallConnectionService.removeConnection(callId)
    }

    /**
     * User puts call on hold
     */
    override fun onHold() {
        Log.d(TAG, "onHold: $callId")
        sipCallManager.holdCall(callId)
        setOnHold()
        isOnHoldState = true
    }

    /**
     * User resumes call from hold
     */
    override fun onUnhold() {
        Log.d(TAG, "onUnhold: $callId")
        sipCallManager.resumeCall(callId)
        setActive()
        isOnHoldState = false
    }

    /**
     * Handle mute state change
     */
    override fun onMuteStateChanged(isMuted: Boolean) {
        Log.d(TAG, "onMuteStateChanged: $isMuted")
        isMutedState = isMuted
        sipCallManager.setMute(callId, isMuted)
    }

    /**
     * User sends DTMF tone
     */
    override fun onPlayDtmfTone(c: Char) {
        Log.d(TAG, "onPlayDtmfTone: $c")
        sipCallManager.sendDtmf(callId, c.toString())
    }

    /**
     * Stop DTMF tone
     */
    override fun onStopDtmfTone() {
        Log.d(TAG, "onStopDtmfTone")
        // DTMF tones are typically short, no action needed
    }

    /**
     * Handle SIP call state changes from the SIP stack
     */
    private fun handleSipCallState(state: SipCallState) {
        Log.d(TAG, "SIP state changed: $state for call: $callId")

        when (state) {
            SipCallState.CONNECTING -> {
                setInitializing()
            }
            SipCallState.RINGING -> {
                if (!isIncoming) {
                    // Outbound call is ringing at remote end
                    setDialing()
                }
            }
            SipCallState.EARLY_MEDIA -> {
                // Early media (ringback tone)
                setDialing()
            }
            SipCallState.CONNECTED -> {
                setActive()
                callStartTime = System.currentTimeMillis()
                if (!isIncoming) {
                    launchInCallScreen()
                }
            }
            SipCallState.ON_HOLD -> {
                setOnHold()
                isOnHoldState = true
            }
            SipCallState.DISCONNECTED -> {
                setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
                cleanup()
            }
            SipCallState.FAILED -> {
                setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                cleanup()
            }
            SipCallState.BUSY -> {
                setDisconnected(DisconnectCause(DisconnectCause.BUSY))
                cleanup()
            }
            SipCallState.REJECTED -> {
                setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
                cleanup()
            }
            else -> {
                // Handle other states if needed
            }
        }
    }

    /**
     * Cleanup connection resources
     */
    private fun cleanup() {
        sipCallManager.unregisterCallStateListener(callId)
        destroy()
        CallConnectionService.removeConnection(callId)
    }

    /**
     * Launch the in-call screen activity
     */
    private fun launchInCallScreen() {
        val intent = Intent(context, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALLER_NUMBER, phoneNumber)
            putExtra(Constants.EXTRA_IS_INCOMING, isIncoming)
        }
        context.startActivity(intent)
    }

    /**
     * Get call duration in milliseconds
     */
    fun getCallDuration(): Long {
        return if (callStartTime > 0) {
            System.currentTimeMillis() - callStartTime
        } else 0
    }

    /**
     * Get call duration formatted as string
     */
    fun getFormattedDuration(): String {
        val duration = getCallDuration() / 1000 // Convert to seconds
        val hours = duration / 3600
        val minutes = (duration % 3600) / 60
        val seconds = duration % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    /**
     * Check if call is muted
     */
    fun isMuted(): Boolean = isMutedState

    /**
     * Check if call is on hold
     */
    fun isOnHold(): Boolean = isOnHoldState
}
