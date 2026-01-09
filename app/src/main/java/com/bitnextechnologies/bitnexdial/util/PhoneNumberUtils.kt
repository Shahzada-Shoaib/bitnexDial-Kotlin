package com.bitnextechnologies.bitnexdial.util

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import java.util.Locale

/**
 * Utility class for phone number operations
 */
object PhoneNumberUtils {

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /**
     * Normalize phone number to E.164 format
     */
    fun normalizeNumber(phoneNumber: String, defaultRegion: String = "US"): String {
        if (phoneNumber.isBlank()) return phoneNumber

        return try {
            val parsed = phoneUtil.parse(phoneNumber, defaultRegion)
            phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: Exception) {
            // Fallback: just clean and add country code
            val cleaned = phoneNumber.replace(Regex("[^\\d+]"), "")
            when {
                cleaned.startsWith("+") -> cleaned
                cleaned.length == 10 -> "+1$cleaned"
                cleaned.length == 11 && cleaned.startsWith("1") -> "+$cleaned"
                else -> cleaned
            }
        }
    }

    /**
     * Format phone number for display
     */
    fun formatForDisplay(phoneNumber: String, defaultRegion: String = "US"): String {
        if (phoneNumber.isBlank()) return phoneNumber

        return try {
            val parsed = phoneUtil.parse(phoneNumber, defaultRegion)
            phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
        } catch (e: Exception) {
            // Fallback formatting
            val cleaned = phoneNumber.replace(Regex("[^\\d]"), "")
            when (cleaned.length) {
                10 -> "(${cleaned.substring(0, 3)}) ${cleaned.substring(3, 6)}-${cleaned.substring(6)}"
                11 -> if (cleaned.startsWith("1")) {
                    "+1 (${cleaned.substring(1, 4)}) ${cleaned.substring(4, 7)}-${cleaned.substring(7)}"
                } else phoneNumber
                else -> phoneNumber
            }
        }
    }

    /**
     * Check if phone number is valid
     */
    fun isValidNumber(phoneNumber: String, defaultRegion: String = "US"): Boolean {
        if (phoneNumber.isBlank()) return false

        return try {
            val parsed = phoneUtil.parse(phoneNumber, defaultRegion)
            phoneUtil.isValidNumber(parsed)
        } catch (e: Exception) {
            // Fallback: check if it's a reasonable length
            val cleaned = phoneNumber.replace(Regex("[^\\d]"), "")
            cleaned.length in 7..15
        }
    }

    /**
     * Compare two phone numbers for equality (ignoring formatting)
     * Handles country code differences by comparing last 10 digits
     */
    fun areNumbersEqual(number1: String, number2: String): Boolean {
        val normalized1 = normalizeForComparison(number1)
        val normalized2 = normalizeForComparison(number2)

        // Both numbers must have at least 7 digits to be valid for comparison
        // This prevents empty or very short strings from matching everything
        if (normalized1.length < 7 || normalized2.length < 7) {
            return false
        }

        // Exact match after normalization
        if (normalized1 == normalized2) {
            return true
        }

        // Handle country code differences - only match if the shorter number
        // has at least 10 digits and matches the end of the longer number
        val minLength = minOf(normalized1.length, normalized2.length)
        if (minLength >= 10) {
            return normalized1.takeLast(10) == normalized2.takeLast(10)
        }

        return false
    }

    /**
     * Normalize for comparison (strip to last 10 digits)
     */
    fun normalizeForComparison(phoneNumber: String): String {
        val cleaned = phoneNumber.replace(Regex("[^\\d]"), "")
        return when {
            cleaned.length >= 10 -> cleaned.takeLast(10)
            else -> cleaned
        }
    }

    /**
     * Get country code from phone number
     */
    fun getCountryCode(phoneNumber: String): String? {
        return try {
            val parsed = phoneUtil.parse(phoneNumber, "US")
            parsed.countryCode.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if number is a US number
     */
    fun isUSNumber(phoneNumber: String): Boolean {
        return try {
            val parsed = phoneUtil.parse(phoneNumber, "US")
            parsed.countryCode == 1
        } catch (e: Exception) {
            val cleaned = phoneNumber.replace(Regex("[^\\d]"), "")
            cleaned.length == 10 || (cleaned.length == 11 && cleaned.startsWith("1"))
        }
    }

    /**
     * Extract area code from phone number
     */
    fun getAreaCode(phoneNumber: String): String? {
        val cleaned = phoneNumber.replace(Regex("[^\\d]"), "")
        return when {
            cleaned.length >= 10 -> cleaned.takeLast(10).take(3)
            else -> null
        }
    }

    /**
     * Get initials from phone number (last 2 digits) for avatar fallback
     */
    fun getInitialsFromNumber(phoneNumber: String): String {
        val cleaned = phoneNumber.replace(Regex("[^\\d]"), "")
        return if (cleaned.length >= 2) cleaned.takeLast(2) else "?"
    }

    /**
     * Check if string looks like a phone number
     */
    fun looksLikePhoneNumber(input: String): Boolean {
        val cleaned = input.replace(Regex("[^\\d+]"), "")
        return cleaned.length >= 7 && cleaned.matches(Regex("^\\+?\\d+$"))
    }

    /**
     * Strip to just digits
     */
    fun stripToDigits(phoneNumber: String): String {
        return phoneNumber.replace(Regex("[^\\d]"), "")
    }

    /**
     * Parse SIP-style caller ID and extract clean phone number.
     * Handles formats like:
     * - "2102012856" <2102012856>
     * - "John Doe" <2102012856>
     * - <2102012856>
     * - 2102012856
     * - +12102012856
     */
    fun parseSipCallerId(callerId: String?): String {
        if (callerId.isNullOrBlank()) return ""

        // Try to extract number from angle brackets: <number>
        val bracketMatch = Regex("<([^>]+)>").find(callerId)
        if (bracketMatch != null) {
            val extracted = bracketMatch.groupValues[1]
            // Clean and return just digits
            return extracted.replace(Regex("[^\\d+]"), "")
        }

        // Try to extract from quotes with angle brackets: "name" <number>
        val quoteBracketMatch = Regex("\"[^\"]*\"\\s*<([^>]+)>").find(callerId)
        if (quoteBracketMatch != null) {
            val extracted = quoteBracketMatch.groupValues[1]
            return extracted.replace(Regex("[^\\d+]"), "")
        }

        // No angle brackets - just clean the string
        return callerId.replace(Regex("[^\\d+]"), "")
    }

    /**
     * Extract display name from SIP-style caller ID.
     * Returns null if no meaningful name is found (just number).
     * Handles formats like:
     * - "John Doe" <2102012856> -> "John Doe"
     * - John Doe <2102012856> -> "John Doe"
     * - "2102012856" <2102012856> -> null (name is just the number)
     */
    fun parseSipCallerName(callerId: String?): String? {
        if (callerId.isNullOrBlank()) return null

        // Try to extract name from quotes: "name" <number>
        val quoteMatch = Regex("\"([^\"]+)\"\\s*<").find(callerId)
        if (quoteMatch != null) {
            val name = quoteMatch.groupValues[1].trim()
            // If name is just digits, it's not a real name
            if (name.replace(Regex("[^\\d]"), "").length == name.length) {
                return null
            }
            return name.takeIf { it.isNotBlank() }
        }

        // Try to extract name before angle brackets: name <number>
        val beforeBracket = callerId.substringBefore("<").trim()
        if (beforeBracket.isNotBlank() && beforeBracket != callerId) {
            // Remove quotes if present
            val cleaned = beforeBracket.trim('"', ' ')
            // If it's just digits, it's not a real name
            if (cleaned.replace(Regex("[^\\d]"), "").length == cleaned.length) {
                return null
            }
            return cleaned.takeIf { it.isNotBlank() }
        }

        return null
    }
}
