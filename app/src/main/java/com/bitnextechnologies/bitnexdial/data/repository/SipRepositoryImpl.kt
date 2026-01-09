package com.bitnextechnologies.bitnexdial.data.repository

import android.content.Context
import com.bitnextechnologies.bitnexdial.data.remote.api.BitnexApiService
import com.bitnextechnologies.bitnexdial.data.sip.SipCallManager
import com.bitnextechnologies.bitnexdial.data.sip.SipEngine
import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.SipConfig
import com.bitnextechnologies.bitnexdial.domain.model.SipTransport
import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import com.bitnextechnologies.bitnexdial.domain.repository.SipRegistrationState
import com.bitnextechnologies.bitnexdial.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SipRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sipEngine: SipEngine,
    private val sipCallManager: SipCallManager,
    private val apiService: BitnexApiService
) : ISipRepository {

    override val registrationState: StateFlow<SipRegistrationState>
        get() = sipEngine.registrationState

    override val activeCalls: Flow<Map<String, Call>>
        get() = sipCallManager.activeCalls

    override val currentCall: Flow<Call?>
        get() = sipCallManager.currentCall

    override suspend fun initialize() {
        sipEngine.initialize()
    }

    override suspend fun register(config: SipConfig) {
        sipEngine.register(config)
    }

    override suspend fun unregister() {
        sipEngine.unregister()
    }

    override suspend fun makeCall(phoneNumber: String): String {
        val callId = "call_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val success = sipCallManager.makeCall(phoneNumber, callId)
        if (!success) {
            throw IllegalStateException("Failed to initiate call")
        }
        return callId
    }

    override suspend fun answerCall(callId: String) {
        sipCallManager.answerCall(callId)
    }

    override suspend fun rejectCall(callId: String) {
        sipCallManager.rejectCall(callId)
    }

    override suspend fun endCall(callId: String) {
        sipCallManager.endCall(callId)
    }

    override suspend fun holdCall(callId: String) {
        sipCallManager.holdCall(callId)
    }

    override suspend fun resumeCall(callId: String) {
        sipCallManager.resumeCall(callId)
    }

    override suspend fun muteCall(callId: String, mute: Boolean) {
        sipCallManager.setMute(callId, mute)
    }

    override suspend fun sendDtmf(callId: String, digit: String) {
        sipCallManager.sendDtmf(callId, digit)
    }

    override suspend fun transferCall(callId: String, destination: String) {
        sipCallManager.transferCall(callId, destination)
    }

    override suspend fun startAttendedTransfer(callId: String, targetNumber: String) {
        sipCallManager.startAttendedTransfer(callId, targetNumber)
    }

    override suspend fun completeAttendedTransfer(callId: String) {
        sipCallManager.completeAttendedTransfer(callId)
    }

    override suspend fun cancelAttendedTransfer(callId: String) {
        sipCallManager.cancelAttendedTransfer(callId)
    }

    override suspend fun startAddCall(callId: String, targetNumber: String) {
        sipCallManager.startAddCall(callId, targetNumber)
    }

    override suspend fun mergeConference(callId: String) {
        sipCallManager.mergeConference(callId)
    }

    override suspend fun endConference(callId: String) {
        sipCallManager.endConference(callId)
    }

    override suspend fun getSipCredentials(phoneNumber: String): SipConfig? {
        // First try to get credentials from SharedPreferences (saved during login)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val username = prefs.getString(Constants.KEY_SIP_USERNAME, null)
        val password = prefs.getString(Constants.KEY_SIP_PASSWORD, null)

        if (username != null && password != null) {
            val domain = prefs.getString(Constants.KEY_SIP_DOMAIN, "") ?: ""
            val server = prefs.getString(Constants.KEY_SIP_SERVER, "") ?: ""
            val port = prefs.getInt(Constants.KEY_SIP_PORT, 8089)
            val path = prefs.getString(Constants.KEY_SIP_PATH, "/ws") ?: "/ws"

            return SipConfig(
                username = username,
                password = password,
                domain = domain,
                server = server,
                port = port.toString(),
                transport = SipTransport.WSS,
                path = path
            )
        }

        // Fallback to API call if no cached credentials
        return try {
            val response = apiService.getSipCredentials(phoneNumber)
            if (response.isSuccessful && response.body() != null) {
                val creds = response.body() ?: return null
                if (creds.success && creds.username != null && creds.password != null) {
                    val transport = when (creds.transport?.lowercase()) {
                        "ws" -> SipTransport.WS
                        "wss" -> SipTransport.WSS
                        "udp" -> SipTransport.UDP
                        "tcp" -> SipTransport.TCP
                        "tls" -> SipTransport.TLS
                        else -> SipTransport.WSS
                    }
                    SipConfig(
                        username = creds.username,
                        password = creds.password,
                        domain = creds.domain ?: "",
                        server = creds.server ?: "",
                        port = (creds.port ?: 8089).toString(),
                        transport = transport
                    )
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override fun isRegistered(): Boolean {
        return sipEngine.isRegistered()
    }

    override suspend fun shutdown() {
        sipEngine.shutdown()
    }
}
