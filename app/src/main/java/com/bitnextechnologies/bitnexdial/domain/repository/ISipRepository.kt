package com.bitnextechnologies.bitnexdial.domain.repository

import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.SipConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for SIP operations
 */
interface ISipRepository {

    /**
     * Registration state as StateFlow
     */
    val registrationState: StateFlow<SipRegistrationState>

    /**
     * All active calls (for multi-line support)
     */
    val activeCalls: Flow<Map<String, Call>>

    /**
     * Current active call as Flow
     */
    val currentCall: Flow<Call?>

    /**
     * Initialize SIP engine
     */
    suspend fun initialize()

    /**
     * Register with SIP server
     */
    suspend fun register(config: SipConfig)

    /**
     * Unregister from SIP server
     */
    suspend fun unregister()

    /**
     * Make outgoing call
     */
    suspend fun makeCall(phoneNumber: String): String

    /**
     * Answer incoming call
     */
    suspend fun answerCall(callId: String)

    /**
     * Reject incoming call
     */
    suspend fun rejectCall(callId: String)

    /**
     * End active call
     */
    suspend fun endCall(callId: String)

    /**
     * Hold call
     */
    suspend fun holdCall(callId: String)

    /**
     * Resume held call
     */
    suspend fun resumeCall(callId: String)

    /**
     * Mute call
     */
    suspend fun muteCall(callId: String, mute: Boolean)

    /**
     * Send DTMF tone
     */
    suspend fun sendDtmf(callId: String, digit: String)

    /**
     * Transfer call (blind transfer)
     */
    suspend fun transferCall(callId: String, destination: String)

    /**
     * Start attended transfer - puts current call on hold and calls target
     */
    suspend fun startAttendedTransfer(callId: String, targetNumber: String)

    /**
     * Complete attended transfer - connects the two parties
     */
    suspend fun completeAttendedTransfer(callId: String)

    /**
     * Cancel attended transfer - ends consultation and resumes original call
     */
    suspend fun cancelAttendedTransfer(callId: String)

    /**
     * Start add call for conference - puts current call on hold and calls new party
     */
    suspend fun startAddCall(callId: String, targetNumber: String)

    /**
     * Merge calls into conference
     */
    suspend fun mergeConference(callId: String)

    /**
     * End conference - ends the added call
     */
    suspend fun endConference(callId: String)

    /**
     * Get SIP credentials from server
     */
    suspend fun getSipCredentials(phoneNumber: String): SipConfig?

    /**
     * Check if registered
     */
    fun isRegistered(): Boolean

    /**
     * Shutdown SIP engine
     */
    suspend fun shutdown()
}

/**
 * SIP Registration states
 */
enum class SipRegistrationState {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    UNREGISTERING,
    FAILED,
    MAX_CONTACTS_REACHED
}
