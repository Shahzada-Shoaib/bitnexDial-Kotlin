package com.bitnextechnologies.bitnexdial.data.local.dao

import androidx.room.*
import com.bitnextechnologies.bitnexdial.data.local.entity.VoicemailEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Voicemails
 */
@Dao
interface VoicemailDao {

    @Query("SELECT * FROM voicemails ORDER BY receivedAt DESC")
    fun getAllVoicemails(): Flow<List<VoicemailEntity>>

    @Query("SELECT * FROM voicemails WHERE isRead = 0 ORDER BY receivedAt DESC")
    fun getUnreadVoicemails(): Flow<List<VoicemailEntity>>

    @Query("SELECT * FROM voicemails WHERE id = :voicemailId")
    suspend fun getVoicemailById(voicemailId: String): VoicemailEntity?

    @Query("SELECT * FROM voicemails WHERE id = :voicemailId")
    fun getVoicemailByIdFlow(voicemailId: String): Flow<VoicemailEntity?>

    @Query("""
        SELECT * FROM voicemails
        WHERE callerNumber LIKE '%' || :query || '%'
        OR callerName LIKE '%' || :query || '%'
        ORDER BY receivedAt DESC
        LIMIT :limit
    """)
    suspend fun searchVoicemails(query: String, limit: Int = 50): List<VoicemailEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoicemail(voicemail: VoicemailEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoicemails(voicemails: List<VoicemailEntity>)

    @Update
    suspend fun updateVoicemail(voicemail: VoicemailEntity)

    @Delete
    suspend fun deleteVoicemail(voicemail: VoicemailEntity)

    @Query("DELETE FROM voicemails WHERE id = :voicemailId")
    suspend fun deleteVoicemailById(voicemailId: String)

    @Query("DELETE FROM voicemails")
    suspend fun deleteAllVoicemails()

    @Query("UPDATE voicemails SET isRead = 1 WHERE id = :voicemailId")
    suspend fun markAsRead(voicemailId: String)

    @Query("UPDATE voicemails SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("UPDATE voicemails SET localAudioPath = :localPath WHERE id = :voicemailId")
    suspend fun setLocalAudioPath(voicemailId: String, localPath: String)

    @Query("SELECT COUNT(*) FROM voicemails WHERE isRead = 0")
    fun getUnreadVoicemailCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM voicemails WHERE isRead = 0")
    suspend fun getUnreadVoicemailCountDirect(): Int

    @Query("SELECT COUNT(*) FROM voicemails")
    suspend fun getVoicemailCount(): Int
}
