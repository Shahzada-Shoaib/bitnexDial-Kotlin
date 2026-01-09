package com.bitnextechnologies.bitnexdial.service.telecom

import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.bitnextechnologies.bitnexdial.data.sip.SipCallManager
import com.bitnextechnologies.bitnexdial.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Android Telecom ConnectionService for native call integration
 * This service allows the app to integrate with the Android phone system
 */
@AndroidEntryPoint
class CallConnectionService : ConnectionService() {

    @Inject
    lateinit var sipCallManager: SipCallManager

    companion object {
        private const val TAG = "CallConnectionService"

        // Track active connections
        private val activeConnections = mutableMapOf<String, CallConnection>()

        fun getConnection(callId: String): CallConnection? = activeConnections[callId]

        fun addConnection(callId: String, connection: CallConnection) {
            activeConnections[callId] = connection
            Log.d(TAG, "Added connection: $callId, total: ${activeConnections.size}")
        }

        fun removeConnection(callId: String) {
            activeConnections.remove(callId)
            Log.d(TAG, "Removed connection: $callId, total: ${activeConnections.size}")
        }

        fun getAllConnections(): Map<String, CallConnection> = activeConnections.toMap()

        fun clearAllConnections() {
            activeConnections.clear()
            Log.d(TAG, "Cleared all connections")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ConnectionService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ConnectionService destroyed")
    }

    /**
     * Handle outgoing calls initiated from system dialer or app
     */
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "Creating outgoing connection: ${request?.address}")

        val phoneNumber = request?.address?.schemeSpecificPart ?: ""
        val callId = generateCallId()

        val connection = CallConnection(
            context = applicationContext,
            callId = callId,
            phoneNumber = phoneNumber,
            isIncoming = false,
            sipCallManager = sipCallManager
        ).apply {
            setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(phoneNumber, TelecomManager.PRESENTATION_ALLOWED)
            setInitializing()

            // Set capabilities
            connectionCapabilities = Connection.CAPABILITY_HOLD or
                    Connection.CAPABILITY_SUPPORT_HOLD or
                    Connection.CAPABILITY_MUTE or
                    Connection.CAPABILITY_RESPOND_VIA_TEXT

            // Mark as VoIP call
            audioModeIsVoip = true

            // Set connection properties
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
        }

        addConnection(callId, connection)

        // Initiate SIP call
        sipCallManager.makeCall(phoneNumber, callId)

        return connection
    }

    /**
     * Handle incoming calls from FCM push or SIP INVITE
     */
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras
        val callId = extras?.getString(Constants.EXTRA_CALL_ID) ?: generateCallId()
        val callerNumber = extras?.getString(Constants.EXTRA_CALLER_NUMBER) ?: "Unknown"
        val callerName = extras?.getString(Constants.EXTRA_CALLER_NAME) ?: callerNumber

        Log.d(TAG, "Creating incoming connection: $callerNumber ($callerName)")

        val connection = CallConnection(
            context = applicationContext,
            callId = callId,
            phoneNumber = callerNumber,
            isIncoming = true,
            sipCallManager = sipCallManager
        ).apply {
            setAddress(
                Uri.parse("tel:$callerNumber"),
                TelecomManager.PRESENTATION_ALLOWED
            )
            setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
            setRinging()

            // Set capabilities
            connectionCapabilities = Connection.CAPABILITY_HOLD or
                    Connection.CAPABILITY_SUPPORT_HOLD or
                    Connection.CAPABILITY_MUTE or
                    Connection.CAPABILITY_RESPOND_VIA_TEXT

            // Mark as VoIP call
            audioModeIsVoip = true

            // Set connection properties
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
        }

        addConnection(callId, connection)

        return connection
    }

    /**
     * Handle failed outgoing connection
     */
    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "Failed to create outgoing connection: ${request?.address}")
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    /**
     * Handle failed incoming connection
     */
    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "Failed to create incoming connection")
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    /**
     * Generate unique call ID
     */
    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }
}
