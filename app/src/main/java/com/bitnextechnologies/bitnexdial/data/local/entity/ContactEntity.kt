package com.bitnextechnologies.bitnexdial.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.bitnextechnologies.bitnexdial.data.local.converter.Converters
import com.bitnextechnologies.bitnexdial.domain.model.Contact

/**
 * Room entity for Contact
 */
@Entity(tableName = "contacts")
@TypeConverters(Converters::class)
data class ContactEntity(
    @PrimaryKey
    val id: String,
    val name: String?,
    val firstName: String?,
    val lastName: String?,
    val company: String?,
    val jobTitle: String?,
    val phoneNumbers: List<ContactPhoneNumberEntity>,
    val emails: List<ContactEmailEntity>,
    val avatarUrl: String?,
    val notes: String?,
    val isFavorite: Boolean,
    val isBlocked: Boolean,
    val tags: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val syncedAt: Long?
) {
    fun toDomain(): Contact {
        return Contact(
            id = id,
            name = name,
            firstName = firstName,
            lastName = lastName,
            company = company,
            jobTitle = jobTitle,
            phoneNumbers = phoneNumbers.map { it.number },
            emails = emails.map { it.email },
            avatarUrl = avatarUrl,
            notes = notes,
            isFavorite = isFavorite,
            isBlocked = isBlocked
        )
    }

    companion object {
        fun fromDomain(contact: Contact, syncedAt: Long? = null): ContactEntity {
            // Normalize ID if it looks like a phone number to prevent duplicates
            val normalizedId = if (contact.id.any { it.isDigit() }) {
                com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils.normalizeForComparison(contact.id)
            } else {
                contact.id
            }

            return ContactEntity(
                id = normalizedId,
                name = contact.name,
                firstName = contact.firstName,
                lastName = contact.lastName,
                company = contact.company,
                jobTitle = contact.jobTitle,
                phoneNumbers = contact.phoneNumbers.mapIndexed { index, number ->
                    ContactPhoneNumberEntity(number, "mobile", index == 0)
                },
                emails = contact.emails.mapIndexed { index, email ->
                    ContactEmailEntity(email, "personal", index == 0)
                },
                avatarUrl = contact.avatarUrl,
                notes = contact.notes,
                isFavorite = contact.isFavorite,
                isBlocked = contact.isBlocked,
                tags = emptyList(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                syncedAt = syncedAt
            )
        }
    }
}

data class ContactPhoneNumberEntity(
    val number: String,
    val type: String?,
    val isPrimary: Boolean
)

data class ContactEmailEntity(
    val email: String,
    val type: String?,
    val isPrimary: Boolean
)
