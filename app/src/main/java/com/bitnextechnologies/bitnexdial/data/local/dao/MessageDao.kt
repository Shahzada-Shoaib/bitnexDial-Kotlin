package com.bitnextechnologies.bitnexdial.data.local.dao

import androidx.room.*
import com.bitnextechnologies.bitnexdial.data.local.entity.ConversationEntity
import com.bitnextechnologies.bitnexdial.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Messages
 */
@Dao
interface MessageDao {

    // ==================== Messages ====================

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId IN (:conversationIds) ORDER BY createdAt ASC")
    fun getMessagesForConversationIds(conversationIds: List<String>): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentMessagesForConversation(conversationId: String, limit: Int = 50): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPage(conversationId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestMessageForConversation(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit")
    fun getAllMessages(limit: Int = 200): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages
        WHERE body LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchMessages(query: String, limit: Int = 50): List<MessageEntity>

    /**
     * Insert a message, IGNORING if contentSignature already exists.
     * This is the key to professional deduplication - duplicates are IMPOSSIBLE.
     *
     * When OnConflictStrategy.IGNORE is used with a UNIQUE index on contentSignature,
     * attempting to insert a duplicate will silently do nothing.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long  // Returns -1 if ignored

    /**
     * Insert multiple messages, IGNORING any with duplicate contentSignature.
     * Returns list of row IDs (-1 for ignored/duplicate messages).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(messages: List<MessageEntity>): List<Long>

    /**
     * Insert or update a message (for status updates on existing messages).
     * Use this when you need to update an existing message's status.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    /**
     * Find message by content signature for status updates
     */
    @Query("SELECT * FROM messages WHERE contentSignature = :signature LIMIT 1")
    suspend fun getMessageBySignature(signature: String): MessageEntity?

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("UPDATE messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markAsRead(messageId: String)

    @Query("UPDATE messages SET isRead = 1 WHERE conversationId = :conversationId")
    suspend fun markConversationAsRead(conversationId: String)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    /**
     * Find a potential duplicate message by matching content signature
     * Used for deduplication when syncing server messages with locally-sent messages
     * Matches messages with same from/to/body within a 60-second window
     */
    @Query("""
        SELECT * FROM messages
        WHERE fromNumber = :fromNumber
        AND toNumber = :toNumber
        AND body = :body
        AND ABS(createdAt - :createdAt) < 60000
        LIMIT 1
    """)
    suspend fun findDuplicateMessage(
        fromNumber: String,
        toNumber: String,
        body: String,
        createdAt: Long
    ): MessageEntity?

    /**
     * Check if a message with this exact ID already exists
     */
    @Query("SELECT COUNT(*) FROM messages WHERE id = :messageId")
    suspend fun messageExists(messageId: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0 AND direction = 'INCOMING'")
    fun getUnreadMessageCount(): Flow<Int>

    // ==================== Conversations ====================

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY updatedAt DESC")
    fun getActiveConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getArchivedConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationById(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationByIdFlow(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getConversationByPhoneNumber(phoneNumber: String): ConversationEntity?

    @Query("""
        SELECT * FROM conversations
        WHERE phoneNumber LIKE '%' || :query || '%'
        OR contactName LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchConversations(query: String, limit: Int = 20): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversationById(conversationId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnreadCount(conversationId: String)

    @Query("UPDATE conversations SET isArchived = :isArchived WHERE id = :conversationId")
    suspend fun setArchived(conversationId: String, isArchived: Boolean)

    @Query("UPDATE conversations SET isMuted = :isMuted WHERE id = :conversationId")
    suspend fun setMuted(conversationId: String, isMuted: Boolean)

    @Query("SELECT SUM(unreadCount) FROM conversations")
    fun getTotalUnreadCount(): Flow<Int?>

    @Query("SELECT SUM(unreadCount) FROM conversations")
    suspend fun getTotalUnreadCountDirect(): Int?
}
