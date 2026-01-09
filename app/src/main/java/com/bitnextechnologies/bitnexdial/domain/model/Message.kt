package com.bitnextechnologies.bitnexdial.domain.model

import android.os.Parcelable
import com.bitnextechnologies.bitnexdial.util.DateTimeUtils
import kotlinx.parcelize.Parcelize

/**
 * Domain model representing an SMS message
 */
@Parcelize
data class Message(
    val id: String,
    val conversationId: String,
    val fromNumber: String,
    val toNumber: String,
    val body: String,
    val direction: MessageDirection = MessageDirection.OUTGOING,
    val status: MessageStatus = MessageStatus.SENT,
    val isRead: Boolean = false,
    val mediaUrls: List<String> = emptyList(),
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable {

    /**
     * Check if outgoing message
     */
    val isOutgoing: Boolean
        get() = direction == MessageDirection.OUTGOING

    /**
     * Get formatted time string (in user's local timezone)
     */
    fun getFormattedTime(): String = DateTimeUtils.formatTime(createdAt)

    /**
     * Get formatted date string (in user's local timezone)
     */
    fun getFormattedDate(): String = DateTimeUtils.formatMessageDate(createdAt)

    /**
     * Check if message has media attachment
     */
    fun hasMedia(): Boolean = mediaUrls.isNotEmpty()
}

enum class MessageDirection {
    INCOMING,
    OUTGOING
}

enum class MessageStatus {
    PENDING,
    SENDING,
    SENT,
    DELIVERED,
    RECEIVED,
    READ,
    FAILED
}

/**
 * Domain model representing a conversation (thread of messages)
 */
@Parcelize
data class Conversation(
    val id: String,
    val phoneNumber: String,
    val contactName: String? = null,
    val contactId: String? = null,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val participants: List<String> = emptyList()
) : Parcelable {

    /**
     * Get display name
     */
    fun getDisplayName(): String {
        return when {
            isGroup && !groupName.isNullOrBlank() -> groupName
            !contactName.isNullOrBlank() -> contactName
            else -> formatPhoneNumber(phoneNumber)
        }
    }

    /**
     * Get formatted last message time (in user's local timezone)
     */
    fun getFormattedTime(): String {
        val time = lastMessageTime ?: return ""
        return DateTimeUtils.formatRelativeTime(time)
    }

    /**
     * Get initials for avatar
     */
    fun getInitials(): String {
        val name = getDisplayName()
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
}
