package com.bitnextechnologies.bitnexdial.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitnextechnologies.bitnexdial.domain.model.SipConfig
import com.bitnextechnologies.bitnexdial.domain.model.SipTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure credential manager using EncryptedSharedPreferences.
 * Stores sensitive data like SIP credentials and auth tokens securely.
 *
 * SECURITY: If encryption initialization fails due to corrupted keys,
 * this manager will attempt recovery by clearing corrupted data and
 * recreating fresh encrypted storage. User will need to log in again.
 */
@Singleton
class SecureCredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecureCredentialManager"
        private const val PREFS_NAME = "bitnex_secure_prefs"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

        // Keys for secure storage
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"

        private const val KEY_SIP_USERNAME = "sip_username"
        private const val KEY_SIP_PASSWORD = "sip_password"
        private const val KEY_SIP_DOMAIN = "sip_domain"
        private const val KEY_SIP_SERVER = "sip_server"
        private const val KEY_SIP_PORT = "sip_port"
        private const val KEY_SIP_PATH = "sip_path"

        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_SENDER_PHONE = "sender_phone"
        private const val KEY_FCM_TOKEN = "fcm_token"
    }

    // Track whether encryption is available
    private var isEncryptionAvailable: Boolean = true
    private var encryptionError: Exception? = null

    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPreferences()
    }

    /**
     * Creates EncryptedSharedPreferences with recovery logic for corrupted keys.
     * If the KeyStore key is corrupted (e.g., after app reinstall), this will
     * clear the corrupted data and create fresh encrypted storage.
     */
    private fun createEncryptedPreferences(): SharedPreferences {
        return try {
            createEncryptedPrefsInternal()
        } catch (e: Exception) {
            // Check if this is a key corruption issue (AEADBadTagException, etc.)
            if (isKeyCorruptionError(e)) {
                Log.w(TAG, "Detected corrupted encryption key, attempting recovery...")
                attemptRecovery()
            } else {
                Log.e(TAG, "SECURITY WARNING: Failed to create EncryptedSharedPreferences", e)
                isEncryptionAvailable = false
                encryptionError = e
                throw SecurityException("Secure storage initialization failed. Cannot store credentials securely.", e)
            }
        }
    }

    private fun createEncryptedPrefsInternal(): SharedPreferences {
        // Build the master key - this can also fail if KeyStore is corrupted
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build MasterKey", e)
            throw e
        }

        // Create encrypted preferences
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also {
            isEncryptionAvailable = true
            Log.d(TAG, "EncryptedSharedPreferences initialized successfully")
        }
    }

    /**
     * Check if the exception indicates a corrupted encryption key.
     * Covers various exception types that can occur when KeyStore keys become invalid.
     */
    private fun isKeyCorruptionError(e: Exception): Boolean {
        // Check all exceptions in the chain
        val allExceptions = generateSequence(e as Throwable?) { it.cause }.toList()

        for (ex in allExceptions) {
            val message = ex.message ?: ""
            val className = ex.javaClass.simpleName

            // Check exception class names
            if (className.contains("AEADBadTagException") ||
                className.contains("KeyStoreException") ||
                className.contains("InvalidKeyException") ||
                className.contains("BadPaddingException") ||
                className.contains("IllegalBlockSizeException")) {
                Log.d(TAG, "Detected key corruption: $className")
                return true
            }

            // Check error messages
            if (message.contains("Signature/MAC verification failed", ignoreCase = true) ||
                message.contains("could not decrypt", ignoreCase = true) ||
                message.contains("Key user not authenticated", ignoreCase = true) ||
                message.contains("Key blob corrupted", ignoreCase = true) ||
                message.contains("IV already used", ignoreCase = true) ||
                message.contains("Keystore operation failed", ignoreCase = true)) {
                Log.d(TAG, "Detected key corruption from message: $message")
                return true
            }
        }

        return false
    }

    /**
     * Attempt to recover from corrupted encryption by:
     * 1. Deleting the corrupted preferences files
     * 2. Deleting the corrupted MasterKey from KeyStore
     * 3. Creating fresh encrypted preferences
     */
    private fun attemptRecovery(): SharedPreferences {
        Log.w(TAG, "Starting encryption key recovery process...")

        try {
            // Step 1: Delete all corrupted preferences files
            deleteCorruptedPreferencesFiles()

            // Step 2: Delete the corrupted MasterKey from KeyStore
            deleteCorruptedKeyStoreEntries()

            // Brief pause to ensure KeyStore cleanup is complete
            Thread.sleep(100)

            // Step 3: Create fresh encrypted preferences
            Log.d(TAG, "Creating fresh EncryptedSharedPreferences...")
            return createEncryptedPrefsInternal().also {
                Log.w(TAG, "Recovery successful - user will need to log in again")
            }
        } catch (recoveryException: Exception) {
            Log.e(TAG, "Recovery failed", recoveryException)
            isEncryptionAvailable = false
            encryptionError = recoveryException
            throw SecurityException("Secure storage recovery failed. Cannot store credentials securely.", recoveryException)
        }
    }

    /**
     * Delete all preferences files related to secure storage.
     */
    private fun deleteCorruptedPreferencesFiles() {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")

        // Main preferences file
        val prefsFile = File(sharedPrefsDir, "$PREFS_NAME.xml")
        if (prefsFile.exists()) {
            val deleted = prefsFile.delete()
            Log.d(TAG, "Deleted corrupted prefs file: $deleted (${prefsFile.absolutePath})")
        }

        // Backup file (some devices create .bak files)
        val backupFile = File(sharedPrefsDir, "$PREFS_NAME.xml.bak")
        if (backupFile.exists()) {
            val deleted = backupFile.delete()
            Log.d(TAG, "Deleted backup prefs file: $deleted")
        }

        // Also try to delete via SharedPreferences edit().clear() if possible
        try {
            val regularPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            regularPrefs.edit().clear().commit()
            Log.d(TAG, "Cleared regular SharedPreferences")
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear regular SharedPreferences", e)
        }
    }

    /**
     * Delete corrupted KeyStore entries.
     */
    private fun deleteCorruptedKeyStoreEntries() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            // Delete the main master key alias
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
                Log.d(TAG, "Deleted corrupted MasterKey from KeyStore: $MASTER_KEY_ALIAS")
            }

            // Also check for any variant aliases that might exist
            val aliases = keyStore.aliases().toList()
            for (alias in aliases) {
                if (alias.contains("master_key", ignoreCase = true) ||
                    alias.contains("bitnex", ignoreCase = true)) {
                    try {
                        keyStore.deleteEntry(alias)
                        Log.d(TAG, "Deleted related KeyStore entry: $alias")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete KeyStore entry: $alias", e)
                    }
                }
            }
        } catch (ksException: Exception) {
            Log.w(TAG, "Failed to access KeyStore for cleanup", ksException)
        }
    }

    /**
     * Check if secure storage is available.
     * Should be called before storing sensitive credentials.
     */
    fun isSecureStorageAvailable(): Boolean {
        return try {
            encryptedPrefs // Trigger lazy initialization
            isEncryptionAvailable
        } catch (e: SecurityException) {
            false
        }
    }

    // ==================== Auth Token Management ====================

    fun saveAuthTokens(authToken: String, refreshToken: String?, expiresAt: Long?) {
        encryptedPrefs.edit().apply {
            putString(KEY_AUTH_TOKEN, authToken)
            refreshToken?.let { putString(KEY_REFRESH_TOKEN, it) }
            expiresAt?.let { putLong(KEY_TOKEN_EXPIRES_AT, it) }
            apply()
        }
        Log.d(TAG, "Auth tokens saved securely")
    }

    fun getAuthToken(): String? = encryptedPrefs.getString(KEY_AUTH_TOKEN, null)

    fun getRefreshToken(): String? = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)

    fun getTokenExpiresAt(): Long = encryptedPrefs.getLong(KEY_TOKEN_EXPIRES_AT, 0)

    fun isTokenExpired(): Boolean {
        val expiresAt = getTokenExpiresAt()
        if (expiresAt == 0L) return false // No expiry set
        return System.currentTimeMillis() > expiresAt
    }

    fun clearAuthTokens() {
        encryptedPrefs.edit().apply {
            remove(KEY_AUTH_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRES_AT)
            apply()
        }
        Log.d(TAG, "Auth tokens cleared")
    }

    // ==================== SIP Credentials Management ====================

    fun saveSipCredentials(
        username: String,
        password: String,
        domain: String,
        server: String,
        port: Int = 8089,
        path: String = "/ws"
    ) {
        encryptedPrefs.edit().apply {
            putString(KEY_SIP_USERNAME, username)
            putString(KEY_SIP_PASSWORD, password)
            putString(KEY_SIP_DOMAIN, domain)
            putString(KEY_SIP_SERVER, server)
            putInt(KEY_SIP_PORT, port)
            putString(KEY_SIP_PATH, path)
            apply()
        }
        Log.d(TAG, "SIP credentials saved securely for user: $username")
    }

    fun getSipCredentials(): SipConfig? {
        val username = encryptedPrefs.getString(KEY_SIP_USERNAME, null) ?: return null
        val password = encryptedPrefs.getString(KEY_SIP_PASSWORD, null) ?: return null
        val domain = encryptedPrefs.getString(KEY_SIP_DOMAIN, null) ?: return null
        val server = encryptedPrefs.getString(KEY_SIP_SERVER, null) ?: return null
        val port = encryptedPrefs.getInt(KEY_SIP_PORT, 8089)
        val path = encryptedPrefs.getString(KEY_SIP_PATH, "/ws") ?: "/ws"

        return SipConfig(
            username = username,
            password = password,
            domain = domain,
            server = server,
            port = port.toString(),
            transport = SipTransport.WSS,
            path = path
        )
    }

    fun hasSipCredentials(): Boolean {
        return encryptedPrefs.getString(KEY_SIP_USERNAME, null) != null &&
                encryptedPrefs.getString(KEY_SIP_PASSWORD, null) != null
    }

    fun clearSipCredentials() {
        encryptedPrefs.edit().apply {
            remove(KEY_SIP_USERNAME)
            remove(KEY_SIP_PASSWORD)
            remove(KEY_SIP_DOMAIN)
            remove(KEY_SIP_SERVER)
            remove(KEY_SIP_PORT)
            remove(KEY_SIP_PATH)
            apply()
        }
        Log.d(TAG, "SIP credentials cleared")
    }

    // ==================== User Info Management ====================

    fun saveUserInfo(userId: String, email: String, senderPhone: String?) {
        encryptedPrefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            senderPhone?.let { putString(KEY_SENDER_PHONE, it) }
            apply()
        }
    }

    fun getUserId(): String? = encryptedPrefs.getString(KEY_USER_ID, null)

    fun getUserEmail(): String? = encryptedPrefs.getString(KEY_USER_EMAIL, null)

    fun getSenderPhone(): String? = encryptedPrefs.getString(KEY_SENDER_PHONE, null)

    fun clearUserInfo() {
        encryptedPrefs.edit().apply {
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            remove(KEY_SENDER_PHONE)
            apply()
        }
    }

    // ==================== FCM Token Management ====================

    fun saveFcmToken(token: String) {
        encryptedPrefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getFcmToken(): String? = encryptedPrefs.getString(KEY_FCM_TOKEN, null)

    fun clearFcmToken() {
        encryptedPrefs.edit().remove(KEY_FCM_TOKEN).apply()
    }

    // ==================== Clear All ====================

    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
        Log.d(TAG, "All secure credentials cleared")
    }

    /**
     * Check if user is logged in based on secure storage
     */
    fun isLoggedIn(): Boolean {
        return getAuthToken() != null && hasSipCredentials()
    }
}
