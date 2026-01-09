package com.bitnextechnologies.bitnexdial.data.repository

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SINGLE SOURCE OF TRUTH for contact names.
 *
 * Professional approach matching web version:
 * - Loads all device contacts ONCE on startup
 * - Caches them in a normalized map (10-digit phone -> name)
 * - Provides fast O(1) lookup for any phone number format
 * - Used by ConversationScreen, MessagesScreen, and ContactsScreen
 */
@Singleton
class ContactRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ContactRepository"
    }

    // Cache: normalized 10-digit phone -> contact name
    private val contactCache = mutableMapOf<String, String>()
    private var isLoaded = false
    private val mutex = Mutex()

    /**
     * Load all contacts from device into cache.
     * Call this once on app startup.
     */
    suspend fun loadContacts() {
        mutex.withLock {
            if (isLoaded) return

            withContext(Dispatchers.IO) {
                try {
                    val hasPermission = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        Log.w(TAG, "No READ_CONTACTS permission")
                        return@withContext
                    }

                    val projection = arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )

                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        projection,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                        var count = 0
                        while (cursor.moveToNext()) {
                            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                            val number = if (numberIndex >= 0) cursor.getString(numberIndex) else null

                            if (!name.isNullOrBlank() && !number.isNullOrBlank()) {
                                // Normalize to 10 digits and store
                                val normalized = normalizePhone(number)
                                if (normalized.length >= 10) {
                                    contactCache[normalized] = name
                                    // Also store with other common formats for fast lookup
                                    contactCache["1$normalized"] = name
                                    contactCache["+1$normalized"] = name
                                    count++
                                }
                            }
                        }
                        Log.d(TAG, "Loaded $count contacts into cache")
                    }

                    isLoaded = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading contacts", e)
                }
            }
        }
    }

    /**
     * Get contact name for a phone number.
     * Returns the contact name if found, null otherwise.
     * This is O(1) lookup from cache.
     */
    fun getContactName(phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null

        val normalized = normalizePhone(phoneNumber)

        // Try normalized (10 digits)
        contactCache[normalized]?.let { return it }

        // Try with 1 prefix
        contactCache["1$normalized"]?.let { return it }

        // Try with +1 prefix
        contactCache["+1$normalized"]?.let { return it }

        // Try original
        contactCache[phoneNumber]?.let { return it }

        return null
    }

    /**
     * Check if we have a contact for this number
     */
    fun hasContact(phoneNumber: String?): Boolean {
        return getContactName(phoneNumber) != null
    }

    /**
     * Normalize phone number to 10 digits
     */
    private fun normalizePhone(phone: String): String {
        val digits = phone.replace(Regex("[^\\d]"), "")
        return when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            digits.length > 11 && digits.startsWith("1") -> digits.substring(1).take(10)
            else -> digits
        }
    }

    /**
     * Force reload contacts (e.g., after permission granted)
     */
    suspend fun reloadContacts() {
        mutex.withLock {
            contactCache.clear()
            isLoaded = false
        }
        loadContacts()
    }

    /**
     * Get display name: contact name if found, otherwise formatted phone number
     */
    fun getDisplayName(phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return "Unknown"

        return getContactName(phoneNumber) ?: formatPhoneForDisplay(phoneNumber)
    }

    /**
     * Format phone number for display (xxx) xxx-xxxx
     */
    private fun formatPhoneForDisplay(phoneNumber: String): String {
        val digits = phoneNumber.replace(Regex("[^\\d]"), "")
        val tenDigits = when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            else -> return phoneNumber
        }

        return "(${tenDigits.take(3)}) ${tenDigits.substring(3, 6)}-${tenDigits.takeLast(4)}"
    }

    /**
     * Get initials for avatar
     */
    fun getInitials(phoneNumber: String?): String {
        val name = getContactName(phoneNumber)
        if (!name.isNullOrBlank()) {
            val parts = name.trim().split(" ").filter { it.isNotBlank() }
            return when {
                parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
                parts.size == 1 -> parts.first().take(2).uppercase()
                else -> getDigitInitials(phoneNumber)
            }
        }
        return getDigitInitials(phoneNumber)
    }

    private fun getDigitInitials(phoneNumber: String?): String {
        val digits = phoneNumber?.replace(Regex("[^\\d]"), "") ?: ""
        return if (digits.length >= 2) digits.takeLast(2) else "?"
    }
}
