package com.bitnextechnologies.bitnexdial.domain.repository

import com.bitnextechnologies.bitnexdial.domain.model.Conversation
import com.bitnextechnologies.bitnexdial.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for SMS/message operations
 */
interface IMessageRepository {

    /**
     * Get all conversations as Flow
     */
    fun getConversations(): Flow<List<Conversation>>

    /**
     * Get messages for a conversation as Flow
     */
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>

    /**
     * Get conversation by ID
     */
    suspend fun getConversationById(conversationId: String): Conversation?

    /**
     * Get conversation by phone number (tries multiple format variations)
     */
    suspend fun getConversationByPhoneNumber(phoneNumber: String): Conversation?

    /**
     * Get or create conversation for a phone number
     * Returns existing conversation or creates a new empty one for new chats
     */
    suspend fun getOrCreateConversation(phoneNumber: String, contactName: String? = null): Conversation

    /**
     * Get message by ID
     */
    suspend fun getMessageById(messageId: String): Message?

    /**
     * Send SMS message
     */
    suspend fun sendMessage(
        conversationId: String,
        toNumber: String,
        fromNumber: String,
        body: String,
        mediaUrl: String? = null
    ): Message

    /**
     * Send MMS message with file attachment
     * Uploads file first, then sends message with media URL
     */
    suspend fun sendMessageWithMedia(
        conversationId: String,
        toNumber: String,
        fromNumber: String,
        body: String,
        fileUri: String,
        mimeType: String,
        fileName: String
    ): Message

    /**
     * Delete message
     */
    suspend fun deleteMessage(messageId: String)

    /**
     * Delete conversation locally
     */
    suspend fun deleteConversation(conversationId: String)

    /**
     * Delete conversation on server and locally.
     * Matches web's /api/delete-conversation endpoint.
     * Returns true if successful.
     */
    suspend fun deleteConversation(myPhoneNumber: String, targetNumber: String): Boolean

    /**
     * Mark conversation as read
     */
    suspend fun markConversationAsRead(conversationId: String)

    /**
     * Get total unread count as Flow
     */
    fun getTotalUnreadCount(): Flow<Int>

    /**
     * Get total unread count directly (not as Flow).
     * Used for immediate badge updates from event handlers.
     */
    suspend fun getTotalUnreadCountDirect(): Int

    /**
     * Search messages
     */
    suspend fun searchMessages(query: String): List<Message>

    /**
     * Sync messages with server
     */
    suspend fun syncMessages()

    /**
     * Sync messages for a specific conversation
     */
    suspend fun syncMessagesForConversation(conversationId: String)

    /**
     * Load more messages for pagination
     * Returns true if more messages are available
     */
    suspend fun loadMoreMessages(
        conversationId: String,
        offset: Int,
        limit: Int = 50
    ): Boolean

    // ==================== Favorite Chat Operations ====================

    /**
     * Toggle favorite status for a chat.
     * Returns Result with the new favorite state (true = favorited, false = unfavorited)
     */
    suspend fun toggleFavoriteChat(ownerPhone: String, contactPhone: String): Result<Boolean>

    /**
     * Get list of favorite chat phone numbers (normalized).
     */
    suspend fun getFavoriteChats(ownerPhone: String): List<String>
}
