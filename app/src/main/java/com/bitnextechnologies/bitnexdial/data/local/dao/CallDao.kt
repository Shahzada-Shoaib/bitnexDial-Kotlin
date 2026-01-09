package com.bitnextechnologies.bitnexdial.data.local.dao

import androidx.room.*
import com.bitnextechnologies.bitnexdial.data.local.entity.CallEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Call History
 */
@Dao
interface CallDao {

    @Query("SELECT * FROM call_history ORDER BY startTime DESC")
    fun getAllCalls(): Flow<List<CallEntity>>

    @Query("SELECT * FROM call_history ORDER BY startTime DESC LIMIT :limit")
    fun getRecentCalls(limit: Int = 100): Flow<List<CallEntity>>

    @Query("SELECT * FROM call_history ORDER BY startTime DESC LIMIT :limit OFFSET :offset")
    suspend fun getCallsPage(limit: Int, offset: Int): List<CallEntity>

    @Query("SELECT * FROM call_history WHERE type = 'MISSED' ORDER BY startTime DESC")
    fun getMissedCalls(): Flow<List<CallEntity>>

    @Query("SELECT * FROM call_history WHERE direction = 'INCOMING' ORDER BY startTime DESC")
    fun getIncomingCalls(): Flow<List<CallEntity>>

    @Query("SELECT * FROM call_history WHERE direction = 'OUTGOING' ORDER BY startTime DESC")
    fun getOutgoingCalls(): Flow<List<CallEntity>>

    @Query("SELECT * FROM call_history WHERE id = :callId")
    suspend fun getCallById(callId: String): CallEntity?

    @Query("SELECT * FROM call_history WHERE id = :callId")
    fun getCallByIdFlow(callId: String): Flow<CallEntity?>

    @Query("""
        SELECT * FROM call_history
        WHERE phoneNumber LIKE '%' || :query || '%'
        OR contactName LIKE '%' || :query || '%'
        ORDER BY startTime DESC
        LIMIT :limit
    """)
    suspend fun searchCalls(query: String, limit: Int = 50): List<CallEntity>

    @Query("SELECT * FROM call_history WHERE phoneNumber = :phoneNumber ORDER BY startTime DESC")
    fun getCallsByPhoneNumber(phoneNumber: String): Flow<List<CallEntity>>

    /**
     * Find duplicate call - same phone number within time window (prevents double entries)
     */
    @Query("""
        SELECT * FROM call_history
        WHERE phoneNumber LIKE '%' || :phoneNumber || '%'
        AND startTime >= :startTime - 60000
        AND startTime <= :startTime + 60000
        ORDER BY startTime DESC
        LIMIT 1
    """)
    suspend fun findDuplicateCall(phoneNumber: String, startTime: Long): CallEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCall(call: CallEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalls(calls: List<CallEntity>)

    @Update
    suspend fun updateCall(call: CallEntity)

    @Delete
    suspend fun deleteCall(call: CallEntity)

    @Query("DELETE FROM call_history WHERE id = :callId")
    suspend fun deleteCallById(callId: String)

    @Query("DELETE FROM call_history")
    suspend fun deleteAllCalls()

    @Query("UPDATE call_history SET isRead = 1 WHERE id = :callId")
    suspend fun markAsRead(callId: String)

    @Query("UPDATE call_history SET isRead = 1 WHERE type = 'MISSED'")
    suspend fun markAllMissedAsRead()

    @Query("SELECT COUNT(*) FROM call_history WHERE type = 'MISSED' AND isRead = 0")
    fun getUnreadMissedCallCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM call_history WHERE type = 'MISSED' AND isRead = 0")
    suspend fun getUnreadMissedCallCountDirect(): Int

    @Query("SELECT COUNT(*) FROM call_history")
    suspend fun getCallCount(): Int

    @Query("""
        SELECT * FROM call_history
        WHERE startTime >= :startTime AND startTime <= :endTime
        ORDER BY startTime DESC
    """)
    suspend fun getCallsInRange(startTime: Long, endTime: Long): List<CallEntity>

    // ========== ANALYTICS QUERIES ==========

    @Query("SELECT COUNT(*) FROM call_history WHERE direction = 'INCOMING'")
    suspend fun getIncomingCallCount(): Int

    @Query("SELECT COUNT(*) FROM call_history WHERE direction = 'OUTGOING'")
    suspend fun getOutgoingCallCount(): Int

    @Query("SELECT COUNT(*) FROM call_history WHERE type = 'MISSED'")
    suspend fun getMissedCallCount(): Int

    @Query("SELECT COUNT(*) FROM call_history WHERE type = 'ANSWERED'")
    suspend fun getAnsweredCallCount(): Int

    @Query("SELECT SUM(duration) FROM call_history WHERE type = 'ANSWERED'")
    suspend fun getTotalCallDuration(): Long?

    @Query("SELECT AVG(duration) FROM call_history WHERE type = 'ANSWERED' AND duration > 0")
    suspend fun getAverageCallDuration(): Double?

    @Query("SELECT MAX(duration) FROM call_history WHERE type = 'ANSWERED'")
    suspend fun getLongestCallDuration(): Long?

    @Query("SELECT COUNT(*) FROM call_history WHERE startTime >= :startTime")
    suspend fun getCallCountSince(startTime: Long): Int

    @Query("SELECT SUM(duration) FROM call_history WHERE type = 'ANSWERED' AND startTime >= :startTime")
    suspend fun getTotalDurationSince(startTime: Long): Long?

    // ========== NOTES ==========

    @Query("UPDATE call_history SET notes = :notes WHERE id = :callId")
    suspend fun updateNotes(callId: String, notes: String?)

    @Query("SELECT notes FROM call_history WHERE id = :callId")
    suspend fun getNotes(callId: String): String?

    @Query("SELECT * FROM call_history WHERE notes IS NOT NULL AND notes != '' ORDER BY startTime DESC")
    fun getCallsWithNotes(): Flow<List<CallEntity>>
}
