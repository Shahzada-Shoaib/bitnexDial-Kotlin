package com.bitnextechnologies.bitnexdial.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bitnextechnologies.bitnexdial.domain.model.Voicemail

/**
 * Room entity for Voicemail
 */
@Entity(tableName = "voicemails")
data class VoicemailEntity(
    @PrimaryKey
    val id: String,
    val callerNumber: String,
    val callerName: String?,
    val contactId: String?,
    val duration: Int, // seconds
    val isRead: Boolean,
    val transcription: String?,
    val audioUrl: String?,
    val localAudioPath: String?,
    val receivedAt: Long,
    val createdAt: Long,
    val syncedAt: Long?
) {
    fun toDomain(): Voicemail {
        return Voicemail(
            id = id,
            callerNumber = callerNumber,
            callerName = callerName,
            contactId = contactId,
            duration = duration,
            isRead = isRead,
            transcription = transcription,
            audioUrl = audioUrl,
            receivedAt = receivedAt
        )
    }

    companion object {
        fun fromDomain(voicemail: Voicemail, syncedAt: Long? = null): VoicemailEntity {
            return VoicemailEntity(
                id = voicemail.id,
                callerNumber = voicemail.callerNumber,
                callerName = voicemail.callerName,
                contactId = voicemail.contactId,
                duration = voicemail.duration,
                isRead = voicemail.isRead,
                transcription = voicemail.transcription,
                audioUrl = voicemail.audioUrl,
                localAudioPath = null,
                receivedAt = voicemail.receivedAt,
                createdAt = System.currentTimeMillis(),
                syncedAt = syncedAt
            )
        }
    }
}
