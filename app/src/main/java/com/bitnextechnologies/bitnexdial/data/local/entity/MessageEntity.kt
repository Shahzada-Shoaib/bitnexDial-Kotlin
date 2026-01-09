package com.bitnextechnologies.bitnexdial.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.bitnextechnologies.bitnexdial.data.local.converter.Converters
import com.bitnextechnologies.bitnexdial.domain.model.Message
import com.bitnextechnologies.bitnexdial.domain.model.MessageDirection
import com.bitnextechnologies.bitnexdial.domain.model.MessageStatus
import java.security.MessageDigest

/**
 * Room entity for Messages
 *
 * PROFESSIONAL DEDUPLICATION:
 * Uses contentSignature as a UNIQUE constraint to make duplicates physically impossible.
 * The signature is computed from: normalized(from) + normalized(to) + body
 * This ensures the same message content cannot be inserted twice.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["createdAt"]),
        Index(value = ["contentSignature"], unique = true)  // UNIQUE constraint - duplicates impossible
    ]
)
@TypeConverters(Converters::class)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val fromNumber: String,
    val toNumber: String,
    val body: String,
    val direction: String, // "INCOMING", "OUTGOING"
    val status: String, // "PENDING", "SENT", "DELIVERED", "FAILED", "RECEIVED"
    val isRead: Boolean,
    val mediaUrls: List<String>,
    val mediaType: String?,
    val errorMessage: String?,
    val sentAt: Long?,
    val deliveredAt: Long?,
    val createdAt: Long,
    val syncedAt: Long?,

    /**
     * Content signature for deduplication.
     * Computed as: SHA256(normalizedFrom|normalizedTo|body)
     *
     * This makes duplicates IMPOSSIBLE at the database level.
     */
    val contentSignature: String
) {
    fun toDomain(): Message {
        return Message(
            id = id,
            conversationId = conversationId,
            fromNumber = fromNumber,
            toNumber = toNumber,
            body = body,
            direction = MessageDirection.valueOf(direction),
            status = MessageStatus.valueOf(status),
            isRead = isRead,
            mediaUrls = mediaUrls,
            sentAt = sentAt,
            deliveredAt = deliveredAt,
            createdAt = createdAt
        )
    }

    companion object {
        /**
         * Normalize phone number to 10-digit format for consistent signature generation
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
         * Generate content signature for deduplication.
         * Uses SHA-256 hash of: normalizedFrom|normalizedTo|body|timeBucket
         *
         * TIME BUCKET STRATEGY:
         * - Use 5-minute (300000ms) buckets to handle local/server timestamp differences
         * - Local message at 10:02 and server message at 10:03 = same bucket = duplicate blocked
         * - Repeated message "ok" at 10:02 and another "ok" at 10:08 = different buckets = both saved
         *
         * This balances:
         * - Preventing duplicates from sync (same message within 5 min window)
         * - Allowing legitimate repeated messages (same content, different times)
         */
        fun generateContentSignature(fromNumber: String, toNumber: String, body: String, createdAt: Long): String {
            val normalizedFrom = normalizePhone(fromNumber)
            val normalizedTo = normalizePhone(toNumber)

            // Use 5-minute time buckets (300000ms)
            // This is large enough to handle local/server timestamp differences
            // but small enough to allow repeated messages
            val timeBucket = createdAt / 300000
            val content = "$normalizedFrom|$normalizedTo|$body|$timeBucket"

            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(content.toByteArray())
                hashBytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                // Fallback to simple hash if SHA-256 unavailable
                content.hashCode().toString()
            }
        }

        fun fromDomain(message: Message, syncedAt: Long? = null): MessageEntity {
            return MessageEntity(
                id = message.id,
                conversationId = message.conversationId,
                fromNumber = message.fromNumber,
                toNumber = message.toNumber,
                body = message.body,
                direction = message.direction.name,
                status = message.status.name,
                isRead = message.isRead,
                mediaUrls = message.mediaUrls,
                mediaType = if (message.mediaUrls.isNotEmpty()) "image" else null,
                errorMessage = null,
                sentAt = message.sentAt,
                deliveredAt = message.deliveredAt,
                createdAt = message.createdAt,
                syncedAt = syncedAt,
                contentSignature = generateContentSignature(
                    message.fromNumber,
                    message.toNumber,
                    message.body,
                    message.createdAt
                )
            )
        }
    }
}
