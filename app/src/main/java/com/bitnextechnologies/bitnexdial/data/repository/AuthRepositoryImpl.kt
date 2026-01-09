package com.bitnextechnologies.bitnexdial.data.repository

import android.content.Context
import android.provider.Settings
import com.bitnextechnologies.bitnexdial.BuildConfig
import com.bitnextechnologies.bitnexdial.data.local.dao.BlockedNumberDao
import com.bitnextechnologies.bitnexdial.data.local.dao.CallDao
import com.bitnextechnologies.bitnexdial.data.local.dao.ContactDao
import com.bitnextechnologies.bitnexdial.data.local.dao.MessageDao
import com.bitnextechnologies.bitnexdial.data.local.dao.PhoneNumberDao
import com.bitnextechnologies.bitnexdial.data.local.dao.UserDao
import com.bitnextechnologies.bitnexdial.data.local.dao.VoicemailDao
import com.bitnextechnologies.bitnexdial.data.local.entity.PhoneNumberEntity
import com.bitnextechnologies.bitnexdial.data.local.entity.UserEntity
import com.bitnextechnologies.bitnexdial.data.remote.api.BitnexApiService
import com.bitnextechnologies.bitnexdial.data.remote.dto.DeviceRegistrationRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.LoginRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.PushTokenRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.RefreshTokenRequest
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import com.bitnextechnologies.bitnexdial.domain.model.PhoneNumber
import com.bitnextechnologies.bitnexdial.service.SipForegroundService
import com.bitnextechnologies.bitnexdial.domain.model.User
import com.bitnextechnologies.bitnexdial.domain.repository.IAuthRepository
import com.bitnextechnologies.bitnexdial.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: BitnexApiService,
    private val userDao: UserDao,
    private val phoneNumberDao: PhoneNumberDao,
    private val contactDao: ContactDao,
    private val callDao: CallDao,
    private val messageDao: MessageDao,
    private val voicemailDao: VoicemailDao,
    private val blockedNumberDao: BlockedNumberDao,
    private val secureCredentialManager: SecureCredentialManager
) : IAuthRepository {

    override suspend fun login(email: String, password: String): User {
        val deviceId = getDeviceId()
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        val response = apiService.login(
            LoginRequest(
                email = email,
                password = password,
                deviceId = deviceId,
                deviceName = deviceName
            )
        )

        if (response.isSuccessful && response.body() != null) {
            val loginResponse = response.body()
                ?: throw Exception("Login response body is null")

            if (!loginResponse.success || loginResponse.user == null) {
                throw Exception(loginResponse.message ?: "Login failed")
            }

            val userDto = loginResponse.user

            // Use sessionToken or sessionId as auth token
            val authToken = loginResponse.sessionToken ?: loginResponse.sessionId ?: loginResponse.token

            // IMPORTANT: Save SIP credentials FIRST before inserting user
            // This prevents a race condition where syncMessages() runs before phone number is saved
            // The Room Flow triggers immediately on user insert, but syncMessages needs the phone number
            saveSipCredentials(userDto)

            // SECURITY: Save auth tokens to encrypted storage (not Room database)
            if (authToken != null) {
                secureCredentialManager.saveAuthTokens(
                    authToken = authToken,
                    refreshToken = loginResponse.refreshToken,
                    expiresAt = loginResponse.expiresAt
                )
            }

            // Save user to Room WITHOUT sensitive tokens (tokens stored in SecureCredentialManager)
            val userEntity = UserEntity(
                id = userDto.id,
                email = userDto.email,
                name = userDto.fullName ?: userDto.name ?: "${userDto.firstName ?: ""} ${userDto.lastName ?: ""}".trim(),
                firstName = userDto.firstName,
                lastName = userDto.lastName,
                avatarUrl = userDto.avatarUrl,
                // SECURITY: Don't store sensitive tokens in Room database - use SecureCredentialManager
                authToken = null,
                refreshToken = null,
                tokenExpiresAt = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            userDao.insertUser(userEntity)

            // Save phone numbers if provided, or create from senderPhone
            if (!userDto.phoneNumbers.isNullOrEmpty()) {
                userDto.phoneNumbers.forEach { phoneDto ->
                    phoneNumberDao.insertPhoneNumber(
                        PhoneNumberEntity(
                            id = phoneDto.id,
                            userId = userDto.id,
                            number = phoneDto.number,
                            formatted = phoneDto.formatted,
                            type = phoneDto.type,
                            callerIdName = phoneDto.callerIdName,
                            smsEnabled = phoneDto.smsEnabled ?: true,
                            voiceEnabled = phoneDto.voiceEnabled ?: true,
                            isActive = phoneDto.isActive ?: true,
                            isPrimary = phoneDto.type == "primary"
                        )
                    )
                }
            } else if (userDto.senderPhone != null) {
                // Create phone number from senderPhone
                phoneNumberDao.insertPhoneNumber(
                    PhoneNumberEntity(
                        id = "sip_${userDto.id}",
                        userId = userDto.id,
                        number = userDto.senderPhone,
                        formatted = userDto.senderPhone,
                        type = "primary",
                        callerIdName = userDto.fullName ?: userDto.name,
                        smsEnabled = true,
                        voiceEnabled = true,
                        isActive = true,
                        isPrimary = true
                    )
                )
            }

            // Register FCM token
            registerFcmToken()

            // Trigger SIP registration now that credentials are saved
            SipForegroundService.registerNow(context)

            return User(
                id = userDto.id,
                email = userDto.email,
                name = userDto.name,
                firstName = userDto.firstName,
                lastName = userDto.lastName,
                avatarUrl = userDto.avatarUrl,
                phoneNumbers = userDto.phoneNumbers?.map {
                    PhoneNumber(
                        id = it.id,
                        number = it.number,
                        formatted = it.formatted,
                        type = it.type,
                        callerIdName = it.callerIdName,
                        smsEnabled = it.smsEnabled ?: true,
                        voiceEnabled = it.voiceEnabled ?: true,
                        isActive = it.isActive ?: true
                    )
                } ?: emptyList()
            )
        } else {
            throw Exception("Login failed: ${response.message()}")
        }
    }

    override suspend fun logout() {
        try {
            // Send SIP UNREGISTER to cleanly disconnect from server
            SipForegroundService.unregisterNow(context)

            // Unregister FCM token
            unregisterFcmToken()
            apiService.logout()
        } catch (e: Exception) {
            // Continue with local logout even if server fails
            android.util.Log.w("AuthRepository", "Server logout failed, continuing with local cleanup", e)
        }

        // Clear ALL local account data to prevent data leakage between accounts
        android.util.Log.d("AuthRepository", "Clearing all local data for logout")

        // Clear user and phone numbers
        userDao.deleteUser()
        phoneNumberDao.deleteAll()

        // Clear all cached contacts (API contacts, not device contacts)
        contactDao.deleteAllContacts()

        // Clear call history
        callDao.deleteAllCalls()

        // Clear all messages and conversations
        messageDao.deleteAllMessages()
        messageDao.deleteAllConversations()

        // Clear voicemails
        voicemailDao.deleteAllVoicemails()

        // Clear blocked numbers
        blockedNumberDao.deleteAll()

        // Clear secure credentials
        secureCredentialManager.clearAll()

        // Clear legacy prefs
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        android.util.Log.d("AuthRepository", "All local data cleared successfully")
    }

    override suspend fun refreshToken(): Boolean {
        // SECURITY: Read refresh token from encrypted storage
        val refreshToken = secureCredentialManager.getRefreshToken() ?: return false

        return try {
            val response = apiService.refreshToken(RefreshTokenRequest(refreshToken))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body() ?: return false
                if (body.success && body.token != null) {
                    // SECURITY: Save new tokens to encrypted storage
                    secureCredentialManager.saveAuthTokens(
                        authToken = body.token,
                        refreshToken = body.refreshToken,
                        expiresAt = body.expiresAt
                    )
                    true
                } else false
            } else false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getCurrentUser(): User? {
        return userDao.getUser()?.let { entity ->
            User(
                id = entity.id,
                email = entity.email,
                name = entity.name,
                firstName = entity.firstName,
                lastName = entity.lastName,
                avatarUrl = entity.avatarUrl,
                phoneNumbers = emptyList()
            )
        }
    }

    override fun getCurrentUserFlow(): Flow<User?> {
        return userDao.getUserFlow().map { entity ->
            entity?.let {
                User(
                    id = it.id,
                    email = it.email,
                    name = it.name,
                    firstName = it.firstName,
                    lastName = it.lastName,
                    avatarUrl = it.avatarUrl,
                    phoneNumbers = emptyList() // Phone numbers loaded separately via getPhoneNumbersFlow
                )
            }
        }
    }

    /**
     * Get phone numbers as a flow - call separately to avoid slowing down user loading
     */
    fun getPhoneNumbersFlow(): Flow<List<PhoneNumber>> {
        return phoneNumberDao.getAllPhoneNumbers().map { entities ->
            entities.map { phoneEntity ->
                PhoneNumber(
                    id = phoneEntity.id,
                    number = phoneEntity.number,
                    formatted = phoneEntity.formatted,
                    type = phoneEntity.type,
                    callerIdName = phoneEntity.callerIdName,
                    smsEnabled = phoneEntity.smsEnabled,
                    voiceEnabled = phoneEntity.voiceEnabled,
                    isActive = phoneEntity.isActive,
                    label = phoneEntity.label,
                    sipPassword = phoneEntity.sipPassword
                )
            }
        }
    }

    override suspend fun isLoggedIn(): Boolean {
        // SECURITY: Check encrypted storage for auth token (not Room database)
        val hasUser = userDao.getUser() != null
        val hasToken = secureCredentialManager.getAuthToken() != null
        return hasUser && hasToken
    }

    override suspend fun getAuthToken(): String? {
        // SECURITY: Read auth token from encrypted storage (not Room database)
        return secureCredentialManager.getAuthToken()
    }

    override suspend fun getPhoneNumbers(): List<PhoneNumber> {
        val user = userDao.getUser() ?: return emptyList()
        // This should ideally use a Flow, but for simplicity returning list
        return try {
            val response = apiService.getPhoneNumbers()
            if (response.isSuccessful && response.body() != null) {
                (response.body() ?: return emptyList()).map {
                    PhoneNumber(
                        id = it.id,
                        number = it.number,
                        formatted = it.formatted,
                        type = it.type,
                        callerIdName = it.callerIdName,
                        smsEnabled = it.smsEnabled ?: true,
                        voiceEnabled = it.voiceEnabled ?: true,
                        isActive = it.isActive ?: true
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun registerFcmToken() {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        var fcmToken = prefs.getString(Constants.KEY_FCM_TOKEN, null)

        // If no token in prefs, request it from Firebase
        if (fcmToken == null) {
            try {
                fcmToken = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
                if (fcmToken != null) {
                    // Save token to prefs
                    prefs.edit().putString(Constants.KEY_FCM_TOKEN, fcmToken).apply()
                    android.util.Log.d("AuthRepository", "FCM token retrieved and saved: ${fcmToken.take(20)}...")
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Failed to get FCM token", e)
                return
            }
        }

        if (fcmToken == null) return

        try {
            val senderPhone = secureCredentialManager.getSenderPhone()
            // Clean the phone number to 10 digits (remove +1 and non-digits) - must match server's format
            val cleanPhone = senderPhone?.replace(Regex("[^\\d]"), "")?.removePrefix("1")?.takeLast(10)
            android.util.Log.d("AuthRepository", "FCM token registering with clean phone: $cleanPhone (raw: $senderPhone)")
            val response = apiService.registerPushToken(
                PushTokenRequest(
                    token = fcmToken,
                    platform = "android",
                    deviceId = getDeviceId(),
                    userId = cleanPhone,
                    phoneNumber = cleanPhone
                )
            )
            android.util.Log.d("AuthRepository", "FCM token registered with server: ${response.isSuccessful}, phone: $cleanPhone")
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Failed to register FCM token with server", e)
        }
    }

    private suspend fun unregisterFcmToken() {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val fcmToken = prefs.getString(Constants.KEY_FCM_TOKEN, null) ?: return

        try {
            apiService.unregisterPushToken(
                PushTokenRequest(
                    token = fcmToken,
                    platform = "android",
                    deviceId = getDeviceId()
                )
            )
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun saveSipCredentials(userDto: com.bitnextechnologies.bitnexdial.data.remote.dto.UserResponse) {
        // Save SIP credentials securely using EncryptedSharedPreferences
        if (userDto.sipUsername != null && userDto.sipPassword != null) {
            secureCredentialManager.saveSipCredentials(
                username = userDto.sipUsername,
                password = userDto.sipPassword,
                domain = userDto.domain ?: userDto.secureWebSocketServer ?: "",
                server = userDto.secureWebSocketServer ?: userDto.domain ?: "",
                port = userDto.webSocketPort ?: 8089,
                path = userDto.webSocketPath ?: "/ws"
            )
        }

        // Save user info
        secureCredentialManager.saveUserInfo(
            userId = userDto.id,
            email = userDto.email,
            senderPhone = userDto.senderPhone
        )

        // Note: Legacy plain-text SharedPreferences storage removed for security
        // All credentials now stored via SecureCredentialManager (EncryptedSharedPreferences)
    }

    /**
     * Get SIP credentials from secure storage.
     * Returns a map for backward compatibility with existing callers.
     */
    fun getSipCredentialsFromPrefs(): Map<String, Any?>? {
        val sipConfig = secureCredentialManager.getSipCredentials() ?: return null

        return mapOf(
            "username" to sipConfig.username,
            "password" to sipConfig.password,
            "domain" to sipConfig.domain,
            "server" to sipConfig.server,
            "port" to (sipConfig.port?.toIntOrNull() ?: 8089),
            "path" to sipConfig.path
        )
    }
}
