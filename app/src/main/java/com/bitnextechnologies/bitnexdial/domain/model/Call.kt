package com.bitnextechnologies.bitnexdial.domain.model

import android.os.Parcelable
import com.bitnextechnologies.bitnexdial.util.DateTimeUtils
import kotlinx.parcelize.Parcelize
import java.util.*

/**
 * Domain model representing a call (active or historical)
 */
@Parcelize
data class Call(
    val id: String,
    val callId: String? = null,
    val phoneNumber: String,
    val contactName: String? = null,
    val contactId: String? = null,
    val direction: CallDirection,
    val status: CallStatus = CallStatus.IDLE,
    val type: CallType = CallType.OUTGOING,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val duration: Long = 0L, // in milliseconds
    val isRead: Boolean = true,
    val lineNumber: Int = 1,
    val isOnHold: Boolean = false,
    val isMuted: Boolean = false,
    val sessionId: String? = null,
    val recordingUrl: String? = null,
    val notes: String? = null // User notes for this call
) : Parcelable {

    /**
     * Get display name (name or number if name is null/empty)
     */
    fun getDisplayName(): String {
        return contactName?.takeIf { it.isNotBlank() } ?: formatPhoneNumber(phoneNumber)
    }

    /**
     * Get formatted duration string (MM:SS or HH:MM:SS)
     */
    fun getFormattedDuration(): String = DateTimeUtils.formatDurationMs(duration)

    /**
     * Get formatted date string (in user's local timezone)
     */
    fun getFormattedDate(): String = DateTimeUtils.formatCallTime(startTime)

    /**
     * Get formatted time string (in user's local timezone)
     */
    fun getFormattedTime(): String = DateTimeUtils.formatTime(startTime)

    /**
     * Check if call is missed (incoming call with 0 duration)
     */
    fun isMissed(): Boolean {
        return type == CallType.MISSED ||
            (direction == CallDirection.INCOMING && duration == 0L && status == CallStatus.DISCONNECTED)
    }

    private fun formatPhoneNumber(phone: String): String {
        val cleaned = phone.replace(Regex("[^\\d]"), "")
        return when {
            cleaned.length == 10 -> "(${cleaned.substring(0, 3)}) ${cleaned.substring(3, 6)}-${cleaned.substring(6)}"
            cleaned.length == 11 && cleaned.startsWith("1") -> "+1 (${cleaned.substring(1, 4)}) ${cleaned.substring(4, 7)}-${cleaned.substring(7)}"
            else -> phone
        }
    }

    companion object {
        fun createOutgoing(number: String, name: String? = null, lineNumber: Int = 1): Call {
            return Call(
                id = UUID.randomUUID().toString(),
                phoneNumber = number,
                contactName = name,
                direction = CallDirection.OUTGOING,
                status = CallStatus.DIALING,
                type = CallType.OUTGOING,
                startTime = System.currentTimeMillis(),
                lineNumber = lineNumber
            )
        }

        fun createIncoming(number: String, name: String? = null, callId: String? = null, lineNumber: Int = 1): Call {
            return Call(
                id = UUID.randomUUID().toString(),
                callId = callId,
                phoneNumber = number,
                contactName = name,
                direction = CallDirection.INCOMING,
                status = CallStatus.RINGING,
                type = CallType.INCOMING,
                startTime = System.currentTimeMillis(),
                lineNumber = lineNumber
            )
        }
    }
}

enum class CallDirection {
    INCOMING,
    OUTGOING
}

enum class CallStatus {
    IDLE,
    DIALING,
    RINGING,
    CONNECTING,
    CONNECTED,
    ON_HOLD,
    DISCONNECTING,
    DISCONNECTED,
    FAILED
}

enum class CallType {
    ANSWERED,
    MISSED,
    REJECTED,
    VOICEMAIL,
    BLOCKED,
    INCOMING,
    OUTGOING
}
