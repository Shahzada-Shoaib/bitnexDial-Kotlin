package com.bitnextechnologies.bitnexdial.data.repository

import android.util.Log
import com.bitnextechnologies.bitnexdial.domain.model.Message
import com.bitnextechnologies.bitnexdial.domain.model.MessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PendingMessageStore"

/**
 * PROFESSIONAL SINGLE SOURCE OF TRUTH IMPLEMENTATION
 *
 * This store keeps pending (sending) messages in MEMORY ONLY.
 * Messages are NOT persisted to database until server confirms.
 *
 * Flow:
 * 1. User sends message → Added to PendingMessageStore (memory)
 * 2. Socket emits to server → Message shows as "sending" in UI
 * 3. Server confirms (sms-sent event) → Message removed from pending, saved to DB with server ID
 * 4. App closes → Pending messages are lost (they weren't sent anyway)
 * 5. App opens → Only server-confirmed messages loaded from DB
 *
 * This prevents duplicates because:
 * - Database ONLY contains server-confirmed messages
 * - Pending messages exist ONLY in memory
 * - No race condition possible
 */
@Singleton
class PendingMessageStore @Inject constructor() {

    // In-memory store of pending messages, keyed by messageUuid
    private val _pendingMessages = MutableStateFlow<Map<String, Message>>(emptyMap())
    val pendingMessages: StateFlow<Map<String, Message>> = _pendingMessages.asStateFlow()

    /**
     * Add a message to pending store (called when user sends a message)
     */
    fun addPendingMessage(message: Message) {
        _pendingMessages.update { current ->
            current + (message.id to message)
        }
        Log.d(TAG, "➕ Added pending message: ${message.id}")
    }

    /**
     * Update status of a pending message (e.g., SENDING -> SENT or FAILED)
     */
    fun updatePendingMessageStatus(messageUuid: String, status: MessageStatus) {
        _pendingMessages.update { current ->
            val existingMessage = current[messageUuid]
            if (existingMessage != null) {
                current + (messageUuid to existingMessage.copy(status = status))
            } else {
                current
            }
        }
        Log.d(TAG, "Updated pending message $messageUuid status to $status")
    }

    /**
     * Remove a message from pending store (called when server confirms or message fails permanently)
     */
    fun removePendingMessage(messageUuid: String) {
        _pendingMessages.update { current ->
            current - messageUuid
        }
        Log.d(TAG, "Removed pending message: $messageUuid")
    }

    /**
     * Get pending messages for a specific conversation
     */
    fun getPendingMessagesForConversation(conversationId: String): List<Message> {
        val normalized = normalizeConversationId(conversationId)
        return _pendingMessages.value.values
            .filter { normalizeConversationId(it.conversationId) == normalized }
            .sortedBy { it.createdAt }
    }

    /**
     * Check if a message UUID is pending
     */
    fun isPending(messageUuid: String): Boolean {
        return _pendingMessages.value.containsKey(messageUuid)
    }

    /**
     * Get a pending message by UUID
     */
    fun getPendingMessage(messageUuid: String): Message? {
        return _pendingMessages.value[messageUuid]
    }

    /**
     * Clear all pending messages (useful for logout)
     */
    fun clearAll() {
        _pendingMessages.value = emptyMap()
        Log.d(TAG, "Cleared all pending messages")
    }

    /**
     * Normalize conversation ID for matching
     */
    private fun normalizeConversationId(phoneNumber: String): String {
        val digits = phoneNumber.replace(Regex("[^\\d]"), "")
        return when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            digits.length > 11 && digits.startsWith("1") -> digits.substring(1).take(10)
            else -> digits
        }
    }
}
