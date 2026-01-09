package com.bitnextechnologies.bitnexdial.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * Utility class for contact operations
 */
object ContactUtils {

    private const val TAG = "ContactUtils"

    /**
     * Look up contact name from device contacts by phone number.
     * Returns the contact name if found, null otherwise.
     */
    fun getContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null

        Log.e(TAG, "🔍 Looking up contact for: $phoneNumber")

        try {
            // Check if we have permission
            val hasPermission = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.e(TAG, "🔍 READ_CONTACTS permission: $hasPermission")

            if (!hasPermission) {
                Log.e(TAG, "🔍 No READ_CONTACTS permission, cannot lookup contact")
                return null
            }

            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )

            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                Log.e(TAG, "🔍 Query returned ${cursor.count} results for $phoneNumber")
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) {
                            Log.e(TAG, "🔍 Found contact name for $phoneNumber: $name")
                            return name
                        }
                    }
                }
            }

            // Try with normalized formats
            val normalized = PhoneNumberUtils.normalizeForComparison(phoneNumber)
            Log.e(TAG, "🔍 Trying normalized format: $normalized")
            if (normalized != phoneNumber) {
                return getContactNameDirect(context, normalized)
            }

        } catch (e: Exception) {
            Log.e(TAG, "🔍 Error looking up contact for $phoneNumber", e)
        }

        Log.e(TAG, "🔍 No contact found for $phoneNumber")
        return null
    }

    /**
     * Direct lookup without recursion
     */
    private fun getContactNameDirect(context: Context, phoneNumber: String): String? {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )

            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                Log.e(TAG, "🔍 Direct query returned ${cursor.count} results for $phoneNumber")
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        Log.e(TAG, "🔍 Direct lookup found: $name")
                        return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔍 Error in direct contact lookup", e)
        }
        return null
    }

    /**
     * Get contact name with multiple format attempts
     */
    fun getContactNameWithFallback(context: Context, phoneNumber: String): String? {
        Log.e(TAG, "🔍 getContactNameWithFallback called for: $phoneNumber")

        val digits = phoneNumber.replace(Regex("[^\\d]"), "")
        Log.e(TAG, "🔍 Digits extracted: $digits (length: ${digits.length})")

        // Get 10-digit number
        val tenDigits = when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            digits.length > 11 && digits.startsWith("1") -> digits.substring(1).take(10)
            else -> digits
        }
        Log.e(TAG, "🔍 10-digit number: $tenDigits")

        // Try various formats that contacts might be saved as
        val formatsToTry = listOf(
            phoneNumber,                          // Original
            digits,                               // Just digits
            tenDigits,                            // 10 digits
            "+1$tenDigits",                       // +1 prefix
            "1$tenDigits",                        // 1 prefix
            "(${tenDigits.take(3)}) ${tenDigits.substring(3, 6)}-${tenDigits.takeLast(4)}", // (xxx) xxx-xxxx
            "${tenDigits.take(3)}-${tenDigits.substring(3, 6)}-${tenDigits.takeLast(4)}"   // xxx-xxx-xxxx
        )

        for (format in formatsToTry) {
            if (format.length < 7) continue // Skip invalid formats
            Log.e(TAG, "🔍 Trying format: $format")
            val name = getContactNameDirect(context, format)
            if (name != null) {
                Log.e(TAG, "🔍 Found with format $format: $name")
                return name
            }
        }

        Log.e(TAG, "🔍 No contact found after all attempts for: $phoneNumber")
        return null
    }

    /**
     * Get initials from a contact name or phone number
     */
    fun getInitials(name: String?, phoneNumber: String): String {
        if (!name.isNullOrBlank()) {
            val parts = name.trim().split(" ").filter { it.isNotBlank() }
            return when {
                parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
                parts.size == 1 -> parts.first().take(2).uppercase()
                else -> PhoneNumberUtils.getInitialsFromNumber(phoneNumber)
            }
        }
        return PhoneNumberUtils.getInitialsFromNumber(phoneNumber)
    }
}
