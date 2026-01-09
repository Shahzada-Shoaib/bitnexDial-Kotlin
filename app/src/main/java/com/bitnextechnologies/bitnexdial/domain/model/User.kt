package com.bitnextechnologies.bitnexdial.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Domain model representing the logged in user
 */
@Parcelize
data class User(
    val id: String,
    val email: String,
    val name: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
    val phoneNumbers: List<PhoneNumber> = emptyList(),
    val activePhoneNumber: String? = null,
    val sipUsername: String? = null,
    val sipPassword: String? = null,
    val sipDomain: String? = null,
    val wssServer: String? = null,
    val createdAt: Long? = null
) : Parcelable {

    /**
     * Get the currently active phone number object
     */
    fun getActivePhoneNumberObj(): PhoneNumber? {
        return phoneNumbers.find { it.number == activePhoneNumber || it.isActive }
    }

    /**
     * Get display name (full name or email)
     */
    fun getDisplayName(): String {
        return name?.takeIf { it.isNotBlank() }
            ?: buildString {
                firstName?.let { append(it) }
                if (isNotEmpty() && lastName != null) append(" ")
                lastName?.let { append(it) }
            }.takeIf { it.isNotBlank() }
            ?: email
    }

    /**
     * Get initials for avatar
     */
    fun getInitials(): String {
        val displayName = getDisplayName()
        val parts = displayName.trim().split(" ")
        return when {
            parts.size >= 2 -> "${parts.first().firstOrNull() ?: ""}${parts.last().firstOrNull() ?: ""}"
            displayName.isNotBlank() -> displayName.take(2)
            email.isNotBlank() -> email.take(2)
            else -> "?"
        }.uppercase()
    }

    /**
     * Check if user has valid SIP credentials
     */
    fun hasSipCredentials(): Boolean {
        return !sipUsername.isNullOrBlank() && !sipPassword.isNullOrBlank()
    }
}

/**
 * Domain model representing a phone number associated with user account
 */
@Parcelize
data class PhoneNumber(
    val id: String,
    val number: String,
    val formatted: String? = null,
    val type: String? = null,
    val callerIdName: String? = null,
    val smsEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val isActive: Boolean = false,
    val label: String? = null,
    val extension: String? = null,
    val sipPassword: String? = null,
    val capabilities: PhoneCapabilities = PhoneCapabilities()
) : Parcelable {

    /**
     * Get formatted phone number
     */
    fun getFormattedNumber(): String {
        if (formatted != null) return formatted
        val cleaned = number.replace(Regex("[^\\d]"), "")
        return when {
            cleaned.length == 10 -> "(${cleaned.substring(0, 3)}) ${cleaned.substring(3, 6)}-${cleaned.substring(6)}"
            cleaned.length == 11 && cleaned.startsWith("1") -> "+1 (${cleaned.substring(1, 4)}) ${cleaned.substring(4, 7)}-${cleaned.substring(7)}"
            else -> number
        }
    }

    /**
     * Get display label
     */
    fun getDisplayLabel(): String {
        return label ?: getFormattedNumber()
    }
}

/**
 * Phone number capabilities
 */
@Parcelize
data class PhoneCapabilities(
    val canMakeVoiceCalls: Boolean = true,
    val canSendSms: Boolean = true,
    val canReceiveSms: Boolean = true,
    val canSendMms: Boolean = false,
    val canReceiveFax: Boolean = false
) : Parcelable

/**
 * SIP Configuration for registration
 */
data class SipConfig(
    val username: String,
    val password: String,
    val domain: String,
    val server: String,
    val port: String = "8089",
    val path: String = "/ws",
    val transport: SipTransport = SipTransport.WSS
) {
    /**
     * Get full WebSocket URI
     */
    fun getWebSocketUri(): String {
        val protocol = if (transport == SipTransport.WSS) "wss" else "ws"
        return "$protocol://$server:$port$path"
    }

    /**
     * Get SIP URI
     */
    fun getSipUri(): String {
        return "sip:$username@$domain"
    }
}

enum class SipTransport {
    WS,
    WSS,
    UDP,
    TCP,
    TLS
}
