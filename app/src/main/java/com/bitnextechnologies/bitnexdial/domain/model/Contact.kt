package com.bitnextechnologies.bitnexdial.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Domain model representing a contact
 */
@Parcelize
data class Contact(
    val id: String,
    val name: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val jobTitle: String? = null,
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val avatarUrl: String? = null,
    val notes: String? = null,
    val isFavorite: Boolean = false,
    val isBlocked: Boolean = false,
    val profileColor: String? = null,
    val type: ContactType = ContactType.PERSONAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable {

    /**
     * Get display name (name or first phone number if name is empty)
     */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: buildString {
                firstName?.let { append(it) }
                if (isNotEmpty() && lastName != null) append(" ")
                lastName?.let { append(it) }
            }.takeIf { it.isNotBlank() }
            ?: phoneNumbers.firstOrNull()
            ?: ""

    /**
     * Get the primary phone number
     */
    val primaryPhone: String
        get() = phoneNumbers.firstOrNull() ?: ""

    /**
     * Get initials for avatar
     */
    fun getInitials(): String {
        val displayText = displayName
        val parts = displayText.trim().split(" ")
        return when {
            parts.size >= 2 -> "${parts.first().firstOrNull() ?: ""}${parts.last().firstOrNull() ?: ""}"
            displayText.isNotBlank() -> displayText.take(2)
            else -> "?"
        }.uppercase()
    }

    /**
     * Get formatted phone number
     */
    fun getFormattedPhone(): String {
        val phone = primaryPhone
        val cleaned = phone.replace(Regex("[^\\d]"), "")
        return when {
            cleaned.length == 10 -> "(${cleaned.substring(0, 3)}) ${cleaned.substring(3, 6)}-${cleaned.substring(6)}"
            cleaned.length == 11 && cleaned.startsWith("1") -> "+1 (${cleaned.substring(1, 4)}) ${cleaned.substring(4, 7)}-${cleaned.substring(7)}"
            else -> phone
        }
    }

    companion object {
        fun empty() = Contact(
            id = "",
            name = ""
        )
    }
}

enum class ContactType {
    PERSONAL,
    BUSINESS,
    FAMILY,
    FRIEND,
    OTHER
}
