package com.bitnextechnologies.bitnexdial.util

import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class for handling date/time formatting with proper timezone support.
 *
 * Key principle: Server stores UTC timestamps, app displays in device's local timezone.
 * This matches the web version behavior where JavaScript Date handles timezone conversion.
 */
object DateTimeUtils {

    // Parser for UTC ISO timestamps from server (with 'Z' suffix)
    private val utcIsoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Parser for UTC ISO timestamps without milliseconds
    private val utcIsoFormatNoMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Parser for ISO timestamps without 'Z' suffix (assumed UTC)
    private val isoFormatNoZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Parser for ISO timestamps without 'Z' and without milliseconds
    private val isoFormatNoZNoMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Parser for date-only format
    private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ==================== Display Formats (Local Timezone) ====================

    /**
     * Format time as "h:mm a" (e.g., "10:30 AM") in user's local timezone
     */
    fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        return format.format(Date(epochMillis))
    }

    /**
     * Format date as "MMM d" (e.g., "Jan 21") in user's local timezone
     */
    fun formatShortDate(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val format = SimpleDateFormat("MMM d", Locale.getDefault())
        return format.format(Date(epochMillis))
    }

    /**
     * Format date as "MMM d, yyyy" (e.g., "Jan 21, 2025") in user's local timezone
     */
    fun formatFullDate(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val format = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        return format.format(Date(epochMillis))
    }

    /**
     * Format date and time as "MMM d, h:mm a" (e.g., "Jan 21, 10:30 AM") in user's local timezone
     */
    fun formatDateTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val format = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return format.format(Date(epochMillis))
    }

    /**
     * Format full date and time with timezone indicator
     * Example: "Jan 21, 2025, 10:30:00 AM EST"
     */
    fun formatFullDateTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val format = SimpleDateFormat("MMM d, yyyy, h:mm:ss a z", Locale.getDefault())
        return format.format(Date(epochMillis))
    }

    /**
     * Format for message/conversation display - smart relative time
     * - Today: "10:30 AM"
     * - Yesterday: "Yesterday"
     * - This week: "Mon", "Tue", etc.
     * - This year: "Jan 21"
     * - Older: "Jan 21, 2024"
     */
    fun formatRelativeTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""

        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = epochMillis }

        return when {
            isSameDay(now, date) -> formatTime(epochMillis)
            isYesterday(now, date) -> "Yesterday"
            isSameWeek(now, date) -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMillis))
            isSameYear(now, date) -> formatShortDate(epochMillis)
            else -> formatFullDate(epochMillis)
        }
    }

    /**
     * Format for call history display
     * - Today: "10:30 AM"
     * - Yesterday: "Yesterday"
     * - This week: Day name (e.g., "Monday")
     * - This year: "Jan 21"
     * - Older: "Jan 21, 2024"
     */
    fun formatCallTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""

        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = epochMillis }

        return when {
            isSameDay(now, date) -> formatTime(epochMillis)
            isYesterday(now, date) -> "Yesterday"
            isSameWeek(now, date) -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(epochMillis))
            isSameYear(now, date) -> formatShortDate(epochMillis)
            else -> formatFullDate(epochMillis)
        }
    }

    /**
     * Format for message date separators
     * - Today: "Today"
     * - Yesterday: "Yesterday"
     * - This week: Day name (e.g., "Monday")
     * - This year: "Jan 21"
     * - Older: "Jan 21, 2024"
     */
    fun formatMessageDate(epochMillis: Long): String {
        if (epochMillis <= 0) return ""

        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = epochMillis }

        return when {
            isSameDay(now, date) -> "Today"
            isYesterday(now, date) -> "Yesterday"
            isSameWeek(now, date) -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(epochMillis))
            isSameYear(now, date) -> formatShortDate(epochMillis)
            else -> formatFullDate(epochMillis)
        }
    }

    // ==================== Parsing Functions ====================

    /**
     * Parse UTC timestamp string to epoch milliseconds.
     * Handles multiple formats from the server.
     */
    fun parseUtcTimestamp(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return System.currentTimeMillis()

        return try {
            // Try different formats
            when {
                // ISO with 'Z' and milliseconds: 2025-01-21T10:30:00.000Z
                timestamp.endsWith("Z") && timestamp.contains(".") -> {
                    utcIsoFormat.parse(timestamp)?.time ?: System.currentTimeMillis()
                }
                // ISO with 'Z' but no milliseconds: 2025-01-21T10:30:00Z
                timestamp.endsWith("Z") -> {
                    utcIsoFormatNoMs.parse(timestamp)?.time ?: System.currentTimeMillis()
                }
                // ISO with milliseconds but no 'Z': 2025-01-21T10:30:00.000
                timestamp.contains("T") && timestamp.contains(".") -> {
                    isoFormatNoZ.parse(timestamp)?.time ?: System.currentTimeMillis()
                }
                // ISO without 'Z' and without milliseconds: 2025-01-21T10:30:00
                timestamp.contains("T") -> {
                    isoFormatNoZNoMs.parse(timestamp)?.time ?: System.currentTimeMillis()
                }
                // Date only: 2025-01-21
                timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                    dateOnlyFormat.parse(timestamp)?.time ?: System.currentTimeMillis()
                }
                // Unix timestamp in seconds
                timestamp.matches(Regex("\\d{10}")) -> {
                    timestamp.toLong() * 1000
                }
                // Unix timestamp in milliseconds
                timestamp.matches(Regex("\\d{13}")) -> {
                    timestamp.toLong()
                }
                else -> System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * Format epoch millis to UTC ISO string for sending to server
     */
    fun toUtcIsoString(epochMillis: Long): String {
        return utcIsoFormat.format(Date(epochMillis))
    }

    // ==================== Helper Functions ====================

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(now: Calendar, date: Calendar): Boolean {
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameDay(yesterday, date)
    }

    private fun isSameWeek(now: Calendar, date: Calendar): Boolean {
        return now.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
                now.get(Calendar.WEEK_OF_YEAR) == date.get(Calendar.WEEK_OF_YEAR)
    }

    private fun isSameYear(now: Calendar, date: Calendar): Boolean {
        return now.get(Calendar.YEAR) == date.get(Calendar.YEAR)
    }

    /**
     * Format duration in seconds to readable string
     * Example: 125 -> "2:05" or 3725 -> "1:02:05"
     */
    fun formatDuration(seconds: Int): String {
        if (seconds < 0) return "0:00"

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    /**
     * Format duration in milliseconds to readable string
     */
    fun formatDurationMs(milliseconds: Long): String {
        return formatDuration((milliseconds / 1000).toInt())
    }
}
