package com.bitnextechnologies.bitnexdial.data.remote.dto

import com.google.gson.annotations.SerializedName

// ==================== Contacts API Response DTOs ====================

/**
 * Response from GET /api/get-contacts?owner=X
 * Returns an array directly (no wrapper object)
 */
data class ContactsApiResponse(
    @SerializedName("success")
    val success: Boolean? = true,
    @SerializedName("data")
    val data: List<ContactApiItem>? = null
)

/**
 * Single contact item from the API
 * API returns: { contact: "1234567890", name: "John Doe" }
 */
data class ContactApiItem(
    @SerializedName("contact")
    val contact: String?,
    @SerializedName("name")
    val name: String?
)

/**
 * Request body for POST /api/save-contact
 */
data class SaveContactRequest(
    @SerializedName("myPhoneNumber")
    val myPhoneNumber: String,
    @SerializedName("contactNumber")
    val contactNumber: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("type")
    val type: String = "personal"
)

/**
 * Response from POST /api/save-contact
 */
data class SaveContactResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?
)

/**
 * Request body for POST /api/delete-contact
 */
data class DeleteContactRequest(
    @SerializedName("myPhoneNumber")
    val myPhoneNumber: String,
    @SerializedName("contactNumber")
    val contactNumber: String
)

/**
 * Response from POST /api/delete-contact
 */
data class DeleteContactResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?
)

// ==================== Legacy DTOs (kept for compatibility) ====================

data class ContactsResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("contacts")
    val contacts: List<ContactResponse>,
    @SerializedName("total")
    val total: Int?,
    @SerializedName("page")
    val page: Int?,
    @SerializedName("limit")
    val limit: Int?,
    @SerializedName("has_more")
    val hasMore: Boolean?
)

data class ContactResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String?,
    @SerializedName("first_name")
    val firstName: String?,
    @SerializedName("last_name")
    val lastName: String?,
    @SerializedName("company")
    val company: String?,
    @SerializedName("job_title")
    val jobTitle: String?,
    @SerializedName("phone_numbers")
    val phoneNumbers: List<ContactPhoneNumber>?,
    @SerializedName("emails")
    val emails: List<ContactEmail>?,
    @SerializedName("avatar_url")
    val avatarUrl: String?,
    @SerializedName("notes")
    val notes: String?,
    @SerializedName("is_favorite")
    val isFavorite: Boolean?,
    @SerializedName("is_blocked")
    val isBlocked: Boolean?,
    @SerializedName("tags")
    val tags: List<String>?,
    @SerializedName("custom_fields")
    val customFields: Map<String, String>?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class ContactPhoneNumber(
    @SerializedName("number")
    val number: String,
    @SerializedName("type")
    val type: String?, // "mobile", "home", "work", "other"
    @SerializedName("is_primary")
    val isPrimary: Boolean?
)

data class ContactEmail(
    @SerializedName("email")
    val email: String,
    @SerializedName("type")
    val type: String?, // "personal", "work", "other"
    @SerializedName("is_primary")
    val isPrimary: Boolean?
)

data class CreateContactRequest(
    @SerializedName("name")
    val name: String?,
    @SerializedName("first_name")
    val firstName: String?,
    @SerializedName("last_name")
    val lastName: String?,
    @SerializedName("company")
    val company: String? = null,
    @SerializedName("job_title")
    val jobTitle: String? = null,
    @SerializedName("phone_numbers")
    val phoneNumbers: List<ContactPhoneNumber>,
    @SerializedName("emails")
    val emails: List<ContactEmail>? = null,
    @SerializedName("notes")
    val notes: String? = null,
    @SerializedName("is_favorite")
    val isFavorite: Boolean? = false,
    @SerializedName("tags")
    val tags: List<String>? = null
)

data class UpdateContactRequest(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("first_name")
    val firstName: String? = null,
    @SerializedName("last_name")
    val lastName: String? = null,
    @SerializedName("company")
    val company: String? = null,
    @SerializedName("job_title")
    val jobTitle: String? = null,
    @SerializedName("phone_numbers")
    val phoneNumbers: List<ContactPhoneNumber>? = null,
    @SerializedName("emails")
    val emails: List<ContactEmail>? = null,
    @SerializedName("notes")
    val notes: String? = null,
    @SerializedName("is_favorite")
    val isFavorite: Boolean? = null,
    @SerializedName("tags")
    val tags: List<String>? = null
)
