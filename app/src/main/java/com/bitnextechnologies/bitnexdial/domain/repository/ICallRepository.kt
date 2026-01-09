package com.bitnextechnologies.bitnexdial.domain.repository

import com.bitnextechnologies.bitnexdial.domain.model.Call
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for call operations
 */
interface ICallRepository {

    /**
     * Get call history as Flow
     */
    fun getCallHistory(): Flow<List<Call>>

    /**
     * Get recent calls with limit
     */
    fun getRecentCalls(limit: Int = 100): Flow<List<Call>>

    /**
     * Get missed calls as Flow
     */
    fun getMissedCalls(): Flow<List<Call>>

    /**
     * Get call by ID
     */
    suspend fun getCallById(callId: String): Call?

    /**
     * Save call to history
     */
    suspend fun saveCall(call: Call)

    /**
     * Delete call from history
     */
    suspend fun deleteCall(callId: String)

    /**
     * Delete all call history
     */
    suspend fun clearCallHistory()

    /**
     * Mark call as read
     */
    suspend fun markAsRead(callId: String)

    /**
     * Mark all missed calls as read
     * Used when user opens the missed calls tab
     */
    suspend fun markAllMissedAsRead()

    /**
     * Get unread missed calls count
     */
    fun getUnreadMissedCallCount(): Flow<Int>

    /**
     * Sync call history with server
     */
    suspend fun syncCallHistory()

    /**
     * Sync call history with server using a specific filter
     * @param filter "all", "outgoing", "incoming", or "missed"
     */
    suspend fun syncCallHistoryWithFilter(filter: String)

    /**
     * Load more call history for pagination
     * Returns true if more calls are available
     */
    suspend fun loadMoreCalls(offset: Int, limit: Int = 50, filter: String = "all"): Boolean

    /**
     * Save a missed call directly to the database.
     * Used by FCM and call end handlers for immediate persistence.
     * Generates a unique ID to avoid overwrites.
     */
    suspend fun saveMissedCall(
        phoneNumber: String,
        contactName: String? = null,
        timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Get current unread missed call count directly (not as Flow).
     * Used for immediate badge updates from event handlers.
     */
    suspend fun getUnreadMissedCallCountDirect(): Int

    /**
     * Update notes for a call
     */
    suspend fun updateNotes(callId: String, notes: String?)

    /**
     * Get notes for a call
     */
    suspend fun getNotes(callId: String): String?

    /**
     * Delete multiple calls at once using bulk delete API
     * Used for selective deletion feature
     */
    suspend fun deleteCallsBulk(callIds: List<String>)
}
