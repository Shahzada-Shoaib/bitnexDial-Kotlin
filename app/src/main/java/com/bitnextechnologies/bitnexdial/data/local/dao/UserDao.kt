package com.bitnextechnologies.bitnexdial.data.local.dao

import androidx.room.*
import com.bitnextechnologies.bitnexdial.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getUser(): UserEntity?

    @Query("SELECT * FROM users LIMIT 1")
    fun getUserFlow(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE isLoggedIn = 1 LIMIT 1")
    fun getCurrentUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE isLoggedIn = 1 LIMIT 1")
    suspend fun getCurrentUserSync(): UserEntity?

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)

    @Update
    suspend fun update(user: UserEntity)

    @Query("UPDATE users SET isLoggedIn = 0")
    suspend fun logoutAllUsers()

    @Query("UPDATE users SET sessionId = :sessionId WHERE id = :userId")
    suspend fun updateSessionId(userId: String, sessionId: String?)

    @Query("UPDATE users SET fcmToken = :token WHERE id = :userId")
    suspend fun updateFcmToken(userId: String, token: String?)

    @Query("UPDATE users SET authToken = :token, refreshToken = :refreshToken, tokenExpiresAt = :expiresAt WHERE id = (SELECT id FROM users LIMIT 1)")
    suspend fun updateTokens(token: String?, refreshToken: String?, expiresAt: Long?)

    @Delete
    suspend fun delete(user: UserEntity)

    @Query("DELETE FROM users")
    suspend fun deleteUser()

    @Query("DELETE FROM users")
    suspend fun deleteAll()

    @Query("SELECT authToken FROM users LIMIT 1")
    suspend fun getAuthToken(): String?
}
