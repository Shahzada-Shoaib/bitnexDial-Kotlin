package com.bitnextechnologies.bitnexdial.util

import android.util.Log
import com.bitnextechnologies.bitnexdial.BuildConfig

/**
 * Centralized logging utility for professional and secure logging.
 *
 * Features:
 * - Masks sensitive data (phone numbers, emails) in production builds
 * - Removes emoji characters for clean production logs
 * - Consistent log format across the codebase
 * - Conditional logging based on build type
 *
 * Usage:
 * ```
 * LogUtils.d(TAG, "Processing message for user", mapOf("phone" to phoneNumber))
 * LogUtils.e(TAG, "API call failed", exception)
 * ```
 */
object LogUtils {

    private val isDebugBuild = BuildConfig.DEBUG

    // Regex patterns for sensitive data
    private val PHONE_PATTERN = Regex("""(\+?1?\d{10,15})""")
    private val EMAIL_PATTERN = Regex("""[\w.+-]+@[\w.-]+\.\w+""")
    private val EMOJI_PATTERN = Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF\u2300-\u23FF\u2B50\u2B55\u200D]+""")

    /**
     * Log debug message
     */
    fun d(tag: String, message: String, sensitiveData: Map<String, String?>? = null) {
        if (isDebugBuild) {
            Log.d(tag, formatMessage(message, sensitiveData))
        }
    }

    /**
     * Log info message
     */
    fun i(tag: String, message: String, sensitiveData: Map<String, String?>? = null) {
        Log.i(tag, formatMessage(message, sensitiveData))
    }

    /**
     * Log warning message
     */
    fun w(tag: String, message: String, sensitiveData: Map<String, String?>? = null) {
        Log.w(tag, formatMessage(message, sensitiveData))
    }

    /**
     * Log warning with exception
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w(tag, formatMessage(message, null), throwable)
    }

    /**
     * Log error message
     */
    fun e(tag: String, message: String, sensitiveData: Map<String, String?>? = null) {
        Log.e(tag, formatMessage(message, sensitiveData))
    }

    /**
     * Log error with exception
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, formatMessage(message, null), throwable)
    }

    /**
     * Log verbose message (debug builds only)
     */
    fun v(tag: String, message: String, sensitiveData: Map<String, String?>? = null) {
        if (isDebugBuild) {
            Log.v(tag, formatMessage(message, sensitiveData))
        }
    }

    /**
     * Format message with optional sensitive data masking
     */
    private fun formatMessage(message: String, sensitiveData: Map<String, String?>?): String {
        var formattedMessage = message

        // Remove emojis from production logs
        if (!isDebugBuild) {
            formattedMessage = removeEmojis(formattedMessage)
        }

        // Append sensitive data (masked in production)
        sensitiveData?.let { data ->
            val dataStr = data.entries.joinToString(", ") { (key, value) ->
                "$key=${maskSensitiveData(value)}"
            }
            if (dataStr.isNotEmpty()) {
                formattedMessage = "$formattedMessage [$dataStr]"
            }
        }

        return formattedMessage
    }

    /**
     * Mask sensitive data in production builds
     * Shows full data in debug builds for development
     */
    private fun maskSensitiveData(value: String?): String {
        if (value == null) return "null"
        if (isDebugBuild) return value

        // Mask phone numbers: +1234567890 -> +1***7890
        var masked = PHONE_PATTERN.replace(value) { match ->
            val phone = match.value
            if (phone.length >= 4) {
                "${phone.take(2)}***${phone.takeLast(4)}"
            } else {
                "***"
            }
        }

        // Mask emails: user@domain.com -> u***@domain.com
        masked = EMAIL_PATTERN.replace(masked) { match ->
            val email = match.value
            val atIndex = email.indexOf('@')
            if (atIndex > 1) {
                "${email[0]}***${email.substring(atIndex)}"
            } else {
                "***@***"
            }
        }

        return masked
    }

    /**
     * Remove emoji characters for cleaner production logs
     */
    private fun removeEmojis(message: String): String {
        return EMOJI_PATTERN.replace(message, "").trim()
    }

    /**
     * Mask a phone number for display in logs
     * Use this when building log messages manually
     */
    fun maskPhone(phone: String?): String {
        if (phone == null) return "null"
        if (isDebugBuild) return phone
        return if (phone.length >= 4) {
            "${phone.take(2)}***${phone.takeLast(4)}"
        } else {
            "***"
        }
    }
}
