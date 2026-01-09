package com.bitnextechnologies.bitnexdial.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.CallDirection
import com.bitnextechnologies.bitnexdial.domain.model.CallStatus
import com.bitnextechnologies.bitnexdial.domain.model.CallType

/**
 * Room entity for Call records
 */
@Entity(tableName = "call_history")
data class CallEntity(
    @PrimaryKey
    val id: String,
    val phoneNumber: String,
    val contactName: String?,
    val contactId: String?,
    val direction: String, // "INCOMING", "OUTGOING"
    val status: String, // "RINGING", "CONNECTING", "CONNECTED", "ON_HOLD", "DISCONNECTED"
    val type: String, // "ANSWERED", "MISSED", "REJECTED", "VOICEMAIL", "BLOCKED"
    val duration: Long, // milliseconds
    val startTime: Long,
    val endTime: Long?,
    val lineNumber: Int,
    val fromNumber: String?,
    val toNumber: String?,
    val recordingUrl: String?,
    val recordingId: String?,
    val isRead: Boolean,
    val isMuted: Boolean,
    val isOnHold: Boolean,
    val notes: String? = null, // User notes for this call
    val createdAt: Long,
    val syncedAt: Long?
) {
    fun toDomain(): Call {
        // Safe enum conversion with fallbacks to prevent crashes
        val callDirection = try {
            CallDirection.valueOf(direction)
        } catch (e: IllegalArgumentException) {
            CallDirection.OUTGOING
        }

        val callStatus = try {
            CallStatus.valueOf(status)
        } catch (e: IllegalArgumentException) {
            CallStatus.DISCONNECTED
        }

        val callType = try {
            CallType.valueOf(type)
        } catch (e: IllegalArgumentException) {
            if (callDirection == CallDirection.INCOMING) CallType.INCOMING else CallType.OUTGOING
        }

        return Call(
            id = id,
            phoneNumber = phoneNumber,
            contactName = contactName,
            contactId = contactId,
            direction = callDirection,
            status = callStatus,
            type = callType,
            duration = duration,
            startTime = startTime,
            endTime = endTime,
            lineNumber = lineNumber,
            isMuted = isMuted,
            isOnHold = isOnHold,
            notes = notes
        )
    }

    companion object {
        fun fromDomain(call: Call, syncedAt: Long? = null): CallEntity {
            return CallEntity(
                id = call.id,
                phoneNumber = call.phoneNumber,
                contactName = call.contactName,
                contactId = call.contactId,
                direction = call.direction.name,
                status = call.status.name,
                type = call.type.name,
                duration = call.duration,
                startTime = call.startTime,
                endTime = call.endTime,
                lineNumber = call.lineNumber,
                fromNumber = if (call.direction == CallDirection.INCOMING) call.phoneNumber else null,
                toNumber = if (call.direction == CallDirection.OUTGOING) call.phoneNumber else null,
                recordingUrl = null,
                recordingId = null,
                isRead = call.type != CallType.MISSED,
                isMuted = call.isMuted,
                isOnHold = call.isOnHold,
                notes = call.notes,
                createdAt = System.currentTimeMillis(),
                syncedAt = syncedAt
            )
        }
    }
}
