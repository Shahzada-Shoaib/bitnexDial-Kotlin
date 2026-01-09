package com.bitnextechnologies.bitnexdial.data.remote.socket

import android.util.Log
import com.bitnextechnologies.bitnexdial.BuildConfig
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Socket.IO connection states
 */
enum class SocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * Socket event types
 */
sealed class SocketEvent {
    data class NewMessage(
        val messageId: String,
        val conversationId: String,
        val fromNumber: String,
        val toNumber: String,
        val body: String,
        val timestamp: Long,
        val mediaUrl: String? = null
    ) : SocketEvent()

    // New SMS event - matches web version's new_sms event
    data class NewSms(
        val from: String,
        val to: String,
        val body: String,
        val time: String,
        val mediaUrl: String? = null,
        val groupId: String? = null
    ) : SocketEvent()

    // Call history update event
    data class CallHistoryUpdate(
        val callId: String,
        val caller: String,
        val callee: String,
        val direction: String,
        val status: String,
        val duration: Int,
        val startTime: String
    ) : SocketEvent()

    // Call started event - for optimistic UI update
    data class CallStarted(
        val callId: String,
        val number: String,
        val direction: String, // "outbound" or "inbound"
        val name: String?,
        val startTime: Long
    ) : SocketEvent()

    // Call ended with metrics - for updating call with duration
    data class CallEndedWithMetrics(
        val callId: String,
        val number: String,
        val direction: String,
        val duration: Int,
        val status: String, // "answered", "no-answer", "failed", "busy"
        val endTime: Long
    ) : SocketEvent()

    // New voicemail event
    data class NewVoicemail(
        val voicemailId: String,
        val callerNumber: String,
        val callerName: String?,
        val duration: Int,
        val transcription: String?,
        val audioUrl: String?,
        val receivedAt: Long
    ) : SocketEvent()

    // Contact update event
    data class ContactUpdated(
        val contactId: String,
        val name: String,
        val phoneNumber: String,
        val action: String // "created", "updated", "deleted"
    ) : SocketEvent()

    data class MessageDelivered(
        val messageId: String,
        val conversationId: String,
        val deliveredAt: Long
    ) : SocketEvent()

    data class MessageRead(
        val messageId: String,
        val conversationId: String,
        val readAt: Long
    ) : SocketEvent()

    data class TypingStarted(
        val conversationId: String,
        val phoneNumber: String
    ) : SocketEvent()

    data class TypingStopped(
        val conversationId: String,
        val phoneNumber: String
    ) : SocketEvent()

    data class IncomingCall(
        val callId: String,
        val callerNumber: String,
        val callerName: String?
    ) : SocketEvent()

    data class CallEnded(
        val callId: String
    ) : SocketEvent()

    data class PresenceUpdate(
        val phoneNumber: String,
        val isOnline: Boolean
    ) : SocketEvent()

    data class Error(
        val message: String,
        val code: String?
    ) : SocketEvent()

    // SMS sent response - server confirmation after send-sms emit
    data class SmsSent(
        val success: Boolean,
        val messageUuid: String,
        val error: String?,
        val sid: String?,
        val to: String?
    ) : SocketEvent()

    // SMS status update - delivery status changes
    data class SmsStatusUpdate(
        val messageUuid: String,
        val status: String,
        val timestamp: String
    ) : SocketEvent()
}

/**
 * Socket.IO manager for real-time messaging and events
 * Mirrors the web codebase Socket.io implementation
 */
@Singleton
class SocketManager @Inject constructor() {

    companion object {
        private const val TAG = "SocketManager"

        // Socket events
        const val EVENT_CONNECT = Socket.EVENT_CONNECT
        const val EVENT_DISCONNECT = Socket.EVENT_DISCONNECT
        const val EVENT_CONNECT_ERROR = Socket.EVENT_CONNECT_ERROR

        // Custom events
        const val EVENT_NEW_MESSAGE = "new_message"
        const val EVENT_NEW_SMS = "new_sms"  // Matches web version
        const val EVENT_NEW_SMS_ALT = "new-sms"  // Alternative format from web
        const val EVENT_CALL_HISTORY_UPDATE = "call_history_update"
        const val EVENT_CALL_STARTED = "call_started"
        const val EVENT_CALL_END_METRICS = "call_end_metrics"
        const val EVENT_NEW_VOICEMAIL = "new_voicemail"
        const val EVENT_VOICEMAIL_UPDATE = "voicemail_update"
        const val EVENT_CONTACT_CREATED = "contact_created"
        const val EVENT_CONTACT_UPDATED = "contact_updated"
        const val EVENT_CONTACT_DELETED = "contact_deleted"
        const val EVENT_MESSAGE_DELIVERED = "message_delivered"
        const val EVENT_MESSAGE_READ = "message_read"
        const val EVENT_TYPING_START = "typing_start"
        const val EVENT_TYPING_STOP = "typing_stop"
        const val EVENT_INCOMING_CALL = "incoming_call"
        const val EVENT_CALL_ENDED = "call_ended"
        const val EVENT_PRESENCE_UPDATE = "presence_update"
        const val EVENT_ERROR = "error"

        // Emit events
        const val EMIT_SEND_MESSAGE = "send_message"
        const val EMIT_SEND_SMS = "send-sms"  // Matches web version socket.emit('send-sms')
        const val EMIT_MARK_READ = "mark_read"
        const val EMIT_TYPING = "typing"
        const val EMIT_JOIN_ROOM = "join_room"
        const val EMIT_LEAVE_ROOM = "leave_room"
        const val EMIT_AUTHENTICATE = "authenticate"
        const val EMIT_REGISTER = "register"  // Register with phone number like web version

        // SMS Status events
        const val EVENT_SMS_STATUS_UPDATE = "sms-status-update"
        const val EVENT_SMS_SENT = "sms-sent"
    }

    private var socket: Socket? = null
    private var authToken: String? = null
    private var pendingPhoneNumber: String? = null  // Phone number to register after connection

    private val _connectionState = MutableStateFlow(SocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    /**
     * Connect to Socket.IO server
     */
    fun connect(token: String) {
        Log.d(TAG, "connect() called")

        if (socket?.connected() == true) {
            Log.d(TAG, "Already connected, skipping")
            return
        }

        authToken = token
        _connectionState.value = SocketConnectionState.CONNECTING

        try {
            val socketUrl = BuildConfig.SOCKET_URL
            Log.d(TAG, "Creating connection to $socketUrl")

            // Match web version's socket configuration exactly
            val options = IO.Options().apply {
                transports = arrayOf("websocket", "polling")  // Try WebSocket first, fallback to polling
                upgrade = true  // Allow upgrade from polling to websocket
                reconnection = true
                reconnectionAttempts = 10
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 20000
                forceNew = false
            }

            Log.d(TAG, "Options configured, creating socket instance")

            socket = IO.socket(socketUrl, options)

            Log.d(TAG, "Socket instance created, setting up listeners")

            socket?.apply {
                setupListeners()
                Log.d(TAG, "Listeners attached, calling connect()")
                connect()
            }

            Log.d(TAG, "connect() called, waiting for events")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during connect: ${e.message}", e)
            _connectionState.value = SocketConnectionState.ERROR
            _events.tryEmit(SocketEvent.Error("Connection failed: ${e.message}", "CONNECT_ERROR"))
        }
    }

    /**
     * Disconnect from Socket.IO server
     */
    fun disconnect() {
        try {
            socket?.disconnect()
            socket?.off()
            socket = null
            _connectionState.value = SocketConnectionState.DISCONNECTED
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = socket?.connected() == true

    /**
     * Setup socket event listeners
     */
    private fun Socket.setupListeners() {
        Log.d(TAG, "Setting up event listeners")

        // Connection events
        on(EVENT_CONNECT) {
            Log.i(TAG, "Connected! Socket.id=${this.id()}")
            _connectionState.value = SocketConnectionState.CONNECTED
            // Authenticate after connection
            authenticate()
            // Register pending phone number if any
            pendingPhoneNumber?.let { phoneNumber ->
                Log.d(TAG, "Auto-registering phone: $phoneNumber")
                doRegisterPhoneNumber(phoneNumber)
            }
        }

        on(EVENT_DISCONNECT) { args ->
            Log.w(TAG, "Disconnected: ${args.firstOrNull()}")
            _connectionState.value = SocketConnectionState.DISCONNECTED
        }

        on(EVENT_CONNECT_ERROR) { args ->
            val error = args.firstOrNull()
            Log.e(TAG, "Connection error: $error")
            if (error is Exception) {
                Log.e(TAG, "Error details: ${error.javaClass.simpleName}: ${error.message}")
            }
            _connectionState.value = SocketConnectionState.ERROR
            _events.tryEmit(SocketEvent.Error("Connection error: $error", "CONNECT_ERROR"))
        }

        // Server acknowledgment events
        on("connection_ready") { args ->
            Log.d(TAG, "connection_ready: ${args.firstOrNull()}")
        }

        on("registered") { args ->
            Log.i(TAG, "Registered to room: ${args.firstOrNull()}")
            _connectionState.value = SocketConnectionState.CONNECTED
        }

        // Note: reconnect events are handled internally by socket.io
        // Connection state is managed via connect/disconnect events

        // Message events
        on(EVENT_NEW_MESSAGE) { args ->
            handleNewMessage(args)
        }

        // SMS events - matches web version
        on(EVENT_NEW_SMS) { args ->
            Log.d(TAG, "NEW_SMS event received")
            handleNewSms(args)
        }

        on(EVENT_NEW_SMS_ALT) { args ->
            Log.d(TAG, "NEW-SMS (alt) event received")
            handleNewSms(args)
        }

        // Call history events
        on(EVENT_CALL_HISTORY_UPDATE) { args ->
            handleCallHistoryUpdate(args)
        }

        on(EVENT_CALL_STARTED) { args ->
            handleCallStarted(args)
        }

        on(EVENT_CALL_END_METRICS) { args ->
            handleCallEndMetrics(args)
        }

        // Voicemail events
        on(EVENT_NEW_VOICEMAIL) { args ->
            handleNewVoicemail(args)
        }

        on(EVENT_VOICEMAIL_UPDATE) { args ->
            handleNewVoicemail(args) // Same handler
        }

        // Contact events
        on(EVENT_CONTACT_CREATED) { args ->
            handleContactUpdate(args, "created")
        }

        on(EVENT_CONTACT_UPDATED) { args ->
            handleContactUpdate(args, "updated")
        }

        on(EVENT_CONTACT_DELETED) { args ->
            handleContactUpdate(args, "deleted")
        }

        on(EVENT_MESSAGE_DELIVERED) { args ->
            handleMessageDelivered(args)
        }

        on(EVENT_MESSAGE_READ) { args ->
            handleMessageRead(args)
        }

        // Typing events
        on(EVENT_TYPING_START) { args ->
            handleTypingStart(args)
        }

        on(EVENT_TYPING_STOP) { args ->
            handleTypingStop(args)
        }

        // Call events
        on(EVENT_INCOMING_CALL) { args ->
            handleIncomingCall(args)
        }

        on(EVENT_CALL_ENDED) { args ->
            handleCallEnded(args)
        }

        // Presence events
        on(EVENT_PRESENCE_UPDATE) { args ->
            handlePresenceUpdate(args)
        }

        // Error events
        on(EVENT_ERROR) { args ->
            handleError(args)
        }

        // SMS sent response - server confirms if message was sent or failed
        on(EVENT_SMS_SENT) { args ->
            Log.d(TAG, "sms-sent event received")
            handleSmsSent(args)
        }

        // SMS status update - delivery status changes
        on(EVENT_SMS_STATUS_UPDATE) { args ->
            Log.d(TAG, "sms-status-update event received")
            handleSmsStatusUpdate(args)
        }
    }

    /**
     * Authenticate with the server
     */
    private fun authenticate() {
        authToken?.let { token ->
            emit(EMIT_AUTHENTICATE, JSONObject().apply {
                put("token", token)
            })
        }
    }

    /**
     * Register with phone number - matches web version
     * This tells the server which phone number to send events to
     */
    fun registerPhoneNumber(phoneNumber: String) {
        Log.d(TAG, "registerPhoneNumber called with: $phoneNumber")
        if (phoneNumber.isBlank()) {
            Log.w(TAG, "Cannot register empty phone number")
            return
        }

        // Clean phone number - remove non-digits and leading 1
        val cleanNumber = phoneNumber.replace(Regex("[^\\d]"), "").removePrefix("1")
        Log.d(TAG, "Cleaned number: $cleanNumber, socket connected: ${socket?.connected()}")

        // Store as pending - will be registered when socket connects
        pendingPhoneNumber = cleanNumber

        // If already connected, register immediately
        if (socket?.connected() == true) {
            doRegisterPhoneNumber(cleanNumber)
        } else {
            Log.d(TAG, "Socket not connected yet, will register when connected")
        }
    }

    /**
     * Actually emit the register event to the server
     */
    private fun doRegisterPhoneNumber(cleanNumber: String) {
        if (cleanNumber.length == 10) {
            try {
                socket?.emit(EMIT_REGISTER, cleanNumber)
                Log.d(TAG, "Registered with phone number: $cleanNumber")
            } catch (e: Exception) {
                Log.e(TAG, "Error registering phone number", e)
            }
        } else {
            Log.w(TAG, "Invalid phone number format: $cleanNumber")
        }
    }

    /**
     * Send a message
     */
    fun sendMessage(
        conversationId: String,
        toNumber: String,
        fromNumber: String,
        body: String,
        onAck: ((JSONObject?) -> Unit)? = null
    ) {
        val data = JSONObject().apply {
            put("conversation_id", conversationId)
            put("to", toNumber)
            put("from", fromNumber)
            put("body", body)
        }

        if (onAck != null) {
            emit(EMIT_SEND_MESSAGE, data) { args ->
                val response = args.firstOrNull() as? JSONObject
                onAck(response)
            }
        } else {
            emit(EMIT_SEND_MESSAGE, data)
        }
    }

    /**
     * Send SMS via socket - matches web version's socket.emit('send-sms')
     * This is the professional real-time messaging approach used by the web dialer
     */
    fun sendSms(
        fromNumber: String,
        toNumber: String,
        message: String,
        messageUuid: String,
        mediaUrl: String? = null,
        onAck: ((JSONObject?) -> Unit)? = null
    ) {
        val data = JSONObject().apply {
            put("from", fromNumber)
            put("to", toNumber)
            put("message", message)
            put("messageUuid", messageUuid)
            if (mediaUrl != null) {
                put("mediaUrl", mediaUrl)
            }
        }

        Log.d(TAG, "sendSms: Emitting send-sms event from=$fromNumber to=$toNumber uuid=$messageUuid")

        if (onAck != null) {
            emit(EMIT_SEND_SMS, data) { args ->
                val response = args.firstOrNull() as? JSONObject
                onAck(response)
            }
        } else {
            emit(EMIT_SEND_SMS, data)
        }
    }

    /**
     * Mark message as read
     */
    fun markAsRead(conversationId: String, messageId: String) {
        emit(EMIT_MARK_READ, JSONObject().apply {
            put("conversation_id", conversationId)
            put("message_id", messageId)
        })
    }

    /**
     * Send typing indicator
     */
    fun sendTyping(conversationId: String, isTyping: Boolean) {
        val event = if (isTyping) EMIT_TYPING else EVENT_TYPING_STOP
        emit(event, JSONObject().apply {
            put("conversation_id", conversationId)
        })
    }

    /**
     * Join a conversation room
     */
    fun joinRoom(conversationId: String) {
        emit(EMIT_JOIN_ROOM, JSONObject().apply {
            put("room", conversationId)
        })
    }

    /**
     * Leave a conversation room
     */
    fun leaveRoom(conversationId: String) {
        emit(EMIT_LEAVE_ROOM, JSONObject().apply {
            put("room", conversationId)
        })
    }

    // ==================== Local Event Emission (for optimistic UI updates) ====================

    /**
     * Emit call started event locally (for optimistic UI update)
     * Call this when user initiates or receives a call
     */
    fun emitLocalCallStarted(
        callId: String,
        number: String,
        direction: String,
        name: String? = null
    ) {
        val event = SocketEvent.CallStarted(
            callId = callId,
            number = number,
            direction = direction,
            name = name,
            startTime = System.currentTimeMillis()
        )
        _events.tryEmit(event)
        Log.d(TAG, "Local call started emitted: $number ($direction)")
    }

    /**
     * Emit call ended with metrics locally (for updating call duration)
     * Call this when a call ends
     */
    fun emitLocalCallEnded(
        callId: String,
        number: String,
        direction: String,
        duration: Int,
        status: String
    ) {
        val event = SocketEvent.CallEndedWithMetrics(
            callId = callId,
            number = number,
            direction = direction,
            duration = duration,
            status = status,
            endTime = System.currentTimeMillis()
        )
        _events.tryEmit(event)
        Log.d(TAG, "Local call ended emitted: $number, duration=${duration}s, status=$status")

        // Also emit CallHistoryUpdate for backward compatibility
        // Use UTC timezone for ISO format with 'Z' suffix
        val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val historyEvent = SocketEvent.CallHistoryUpdate(
            callId = callId,
            caller = if (direction == "outbound") "" else number,
            callee = if (direction == "outbound") number else "",
            direction = direction,
            status = status,
            duration = duration,
            startTime = utcFormat.format(java.util.Date(System.currentTimeMillis() - (duration * 1000L)))
        )
        _events.tryEmit(historyEvent)
    }

    /**
     * Emit new SMS event locally (for optimistic UI update)
     */
    fun emitLocalNewSms(
        from: String,
        to: String,
        body: String,
        mediaUrl: String? = null
    ) {
        // Use UTC timezone for ISO format with 'Z' suffix
        val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val event = SocketEvent.NewSms(
            from = from,
            to = to,
            body = body,
            time = utcFormat.format(java.util.Date()),
            mediaUrl = mediaUrl,
            groupId = null
        )
        _events.tryEmit(event)
        Log.d(TAG, "Local SMS emitted: from=$from to=$to")
    }

    /**
     * Emit event to server
     * Note: Socket.IO emit takes Object... varargs for data
     * When ack is needed, it needs Object[] + Ack
     */
    private fun emit(event: String, data: JSONObject, ack: ((Array<Any>) -> Unit)? = null) {
        try {
            Log.d(TAG, "Emitting $event")
            if (ack != null) {
                // With acknowledgement - needs array + Ack callback
                socket?.emit(event, arrayOf(data), io.socket.client.Ack { args -> ack(args) })
            } else {
                // Without acknowledgement - pass data directly as vararg
                socket?.emit(event, data)
            }
            Log.d(TAG, "Emitted $event successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error emitting $event", e)
        }
    }

    // Event handlers

    private fun handleNewMessage(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.NewMessage(
                messageId = data.optString("id"),
                conversationId = data.optString("conversation_id"),
                fromNumber = data.optString("from"),
                toNumber = data.optString("to"),
                body = data.optString("body"),
                timestamp = data.optLong("timestamp", System.currentTimeMillis()),
                mediaUrl = data.optString("mediaUrl", null)
            )
            _events.tryEmit(event)
            Log.d(TAG, "New message received: ${event.messageId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling new message", e)
        }
    }

    /**
     * Handle new SMS event - matches web version's new_sms format
     */
    private fun handleNewSms(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return

            // Skip group messages (same logic as web version)
            if (data.has("groupId") || data.has("group_id") ||
                data.has("isGroupReply") || data.has("is_group_reply")) {
                val groupId = data.optString("groupId", data.optString("group_id", ""))
                if (groupId.isNotEmpty() && groupId != "0") {
                    Log.d(TAG, "Skipping group message")
                    return
                }
            }

            val event = SocketEvent.NewSms(
                from = data.optString("from"),
                to = data.optString("to"),
                body = data.optString("body", data.optString("message", "")),
                time = data.optString("time", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date())),
                mediaUrl = data.optString("mediaUrl", null),
                groupId = data.optString("groupId", data.optString("group_id", null))
            )
            _events.tryEmit(event)
            Log.d(TAG, "New SMS received from: ${event.from}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling new SMS", e)
        }
    }

    /**
     * Handle call history update event
     */
    private fun handleCallHistoryUpdate(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.CallHistoryUpdate(
                callId = data.optString("id", data.optString("call_id", "")),
                caller = data.optString("caller"),
                callee = data.optString("callee"),
                direction = data.optString("direction"),
                status = data.optString("status"),
                duration = data.optInt("duration", 0),
                startTime = data.optString("start_time", "")
            )
            _events.tryEmit(event)
            Log.d(TAG, "Call history update: ${event.callId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling call history update", e)
        }
    }

    /**
     * Handle call started event - for optimistic UI update
     */
    private fun handleCallStarted(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.CallStarted(
                callId = data.optString("id", data.optString("call_id", "call_${System.currentTimeMillis()}")),
                number = data.optString("number", data.optString("caller", data.optString("callee", ""))),
                direction = data.optString("direction", "outbound"),
                name = data.optString("name", null),
                startTime = System.currentTimeMillis()
            )
            _events.tryEmit(event)
            Log.d(TAG, "Call started: ${event.number} (${event.direction})")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling call started", e)
        }
    }

    /**
     * Handle call ended with metrics - for updating call with duration
     */
    private fun handleCallEndMetrics(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.CallEndedWithMetrics(
                callId = data.optString("id", data.optString("call_id", data.optString("session_id", ""))),
                number = data.optString("number", data.optString("caller_number", data.optString("callee_number", ""))),
                direction = data.optString("direction", "outbound"),
                duration = data.optInt("duration", 0),
                status = data.optString("status", "answered"),
                endTime = System.currentTimeMillis()
            )
            _events.tryEmit(event)
            Log.d(TAG, "Call ended with metrics: ${event.number}, duration=${event.duration}s, status=${event.status}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling call end metrics", e)
        }
    }

    /**
     * Handle new voicemail event
     */
    private fun handleNewVoicemail(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.NewVoicemail(
                voicemailId = data.optString("id", data.optString("voicemail_id", "")),
                callerNumber = data.optString("caller", data.optString("caller_number", "")),
                callerName = data.optString("caller_name", null),
                duration = data.optInt("duration", 0),
                transcription = data.optString("transcription", null),
                audioUrl = data.optString("audio_url", data.optString("audioUrl", null)),
                receivedAt = data.optLong("received_at", System.currentTimeMillis())
            )
            _events.tryEmit(event)
            Log.d(TAG, "New voicemail from: ${event.callerNumber}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling new voicemail", e)
        }
    }

    /**
     * Handle contact update events (created, updated, deleted)
     */
    private fun handleContactUpdate(args: Array<Any>, action: String) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.ContactUpdated(
                contactId = data.optString("id", data.optString("contact_id", "")),
                name = data.optString("name", ""),
                phoneNumber = data.optString("phone", data.optString("phone_number", "")),
                action = action
            )
            _events.tryEmit(event)
            Log.d(TAG, "Contact $action: ${event.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling contact $action", e)
        }
    }

    private fun handleMessageDelivered(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.MessageDelivered(
                messageId = data.optString("message_id"),
                conversationId = data.optString("conversation_id"),
                deliveredAt = data.optLong("delivered_at", System.currentTimeMillis())
            )
            _events.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message delivered", e)
        }
    }

    private fun handleMessageRead(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.MessageRead(
                messageId = data.optString("message_id"),
                conversationId = data.optString("conversation_id"),
                readAt = data.optLong("read_at", System.currentTimeMillis())
            )
            _events.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message read", e)
        }
    }

    private fun handleTypingStart(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.TypingStarted(
                conversationId = data.optString("conversation_id"),
                phoneNumber = data.optString("phone_number")
            )
            _events.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling typing start", e)
        }
    }

    private fun handleTypingStop(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.TypingStopped(
                conversationId = data.optString("conversation_id"),
                phoneNumber = data.optString("phone_number")
            )
            _events.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling typing stop", e)
        }
    }

    private fun handleIncomingCall(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.IncomingCall(
                callId = data.optString("call_id"),
                callerNumber = data.optString("caller_number"),
                callerName = data.optString("caller_name", null)
            )
            _events.tryEmit(event)
            Log.d(TAG, "Incoming call: ${event.callerNumber}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming call", e)
        }
    }

    private fun handleCallEnded(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.CallEnded(
                callId = data.optString("call_id")
            )
            _events.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling call ended", e)
        }
    }

    private fun handlePresenceUpdate(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val event = SocketEvent.PresenceUpdate(
                phoneNumber = data.optString("phone_number"),
                isOnline = data.optBoolean("is_online", false)
            )
            _events.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling presence update", e)
        }
    }

    private fun handleError(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject
            val message = data?.optString("message") ?: args.firstOrNull()?.toString() ?: "Unknown error"
            val code = data?.optString("code")
            val event = SocketEvent.Error(message, code)
            _events.tryEmit(event)
            Log.e(TAG, "Socket error: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling error event", e)
        }
    }

    /**
     * Handle sms-sent event - server response after sending SMS
     * Server sends: { success: boolean, error?: string, messageUuid: string, sid?: string, to?: string }
     */
    private fun handleSmsSent(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val success = data.optBoolean("success", false)
            val messageUuid = data.optString("messageUuid", "")
            val error = data.optString("error", null)
            val sid = data.optString("sid", null)
            val to = data.optString("to", null)

            Log.d(TAG, "handleSmsSent: success=$success, messageUuid=$messageUuid, error=$error")

            val event = SocketEvent.SmsSent(
                success = success,
                messageUuid = messageUuid,
                error = error,
                sid = sid,
                to = to
            )
            _events.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sms-sent event", e)
        }
    }

    /**
     * Handle sms-status-update event - delivery status changes
     * Server sends: { messageUuid: string, status: string, timestamp: string }
     */
    private fun handleSmsStatusUpdate(args: Array<Any>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val messageUuid = data.optString("messageUuid", "")
            val status = data.optString("status", "")
            val timestamp = data.optString("timestamp", "")

            Log.d(TAG, "handleSmsStatusUpdate: messageUuid=$messageUuid, status=$status")

            val event = SocketEvent.SmsStatusUpdate(
                messageUuid = messageUuid,
                status = status,
                timestamp = timestamp
            )
            _events.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sms-status-update event", e)
        }
    }
}
