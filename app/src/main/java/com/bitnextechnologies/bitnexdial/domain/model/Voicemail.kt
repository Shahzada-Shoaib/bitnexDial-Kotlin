package com.bitnextechnologies.bitnexdial.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.*

/**
 * Domain model representing a voicemail
 */
@Parcelize
data class Voicemail(
    val id: String,
    val callerNumber: String,
    val callerName: String? = null,
    val contactId: String? = null,
    val duration: Int, // in seconds
    val isRead: Boolean = false,
    val transcription: String? = null,
    val audioUrl: String? = null,
    val receivedAt: Long = System.currentTimeMillis()
) : Parcelable {

    /**
     * Get display name
     */
    fun getDisplayName(): String {
        return callerName?.takeIf { it.isNotBlank() } ?: formatPhoneNumber(callerNumber)
    }

    /**
     * Get formatted duration string
     */
    fun getFormattedDuration(): String {
        val minutes = duration / 60
        val seconds = duration % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Get formatted date string
     */
    fun getFormattedDate(): String {
        val date = Date(receivedAt)
        val now = Calendar.getInstance()
        val vmDate = Calendar.getInstance().apply { timeInMillis = receivedAt }

        return when {
            isSameDay(now, vmDate) -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
            isYesterday(now, vmDate) -> "Yesterday"
            isSameWeek(now, vmDate) -> SimpleDateFormat("EEEE", Locale.getDefault()).format(date)
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
        }
    }

    /**
     * Get initials for avatar
     */
    fun getInitials(): String {
        val name = callerName ?: callerNumber
        val parts = name.trim().split(" ")
        return when {
            parts.size >= 2 -> "${parts.first().firstOrNull() ?: ""}${parts.last().firstOrNull() ?: ""}"
            name.isNotBlank() -> name.take(2)
            else -> "?"
        }.uppercase()
    }

    private fun formatPhoneNumber(phone: String): String {
        val cleaned = phone.replace(Regex("[^\\d]"), "")
        return when {
            cleaned.length == 10 -> "(${cleaned.substring(0, 3)}) ${cleaned.substring(3, 6)}-${cleaned.substring(6)}"
            cleaned.length == 11 && cleaned.startsWith("1") -> "+1 (${cleaned.substring(1, 4)}) ${cleaned.substring(4, 7)}-${cleaned.substring(7)}"
            else -> phone
        }
    }

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
}
