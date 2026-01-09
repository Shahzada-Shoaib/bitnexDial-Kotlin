package com.bitnextechnologies.bitnexdial.domain.repository

import com.bitnextechnologies.bitnexdial.domain.model.PhoneNumber
import com.bitnextechnologies.bitnexdial.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for authentication operations
 */
interface IAuthRepository {

    /**
     * Login with email and password
     */
    suspend fun login(email: String, password: String): User

    /**
     * Logout
     */
    suspend fun logout()

    /**
     * Refresh token
     */
    suspend fun refreshToken(): Boolean

    /**
     * Get current user
     */
    suspend fun getCurrentUser(): User?

    /**
     * Get current user as Flow
     */
    fun getCurrentUserFlow(): Flow<User?>

    /**
     * Check if user is logged in
     */
    suspend fun isLoggedIn(): Boolean

    /**
     * Get auth token
     */
    suspend fun getAuthToken(): String?

    /**
     * Get user's phone numbers
     */
    suspend fun getPhoneNumbers(): List<PhoneNumber>

    /**
     * Register FCM token with server
     */
    suspend fun registerFcmToken()
}
