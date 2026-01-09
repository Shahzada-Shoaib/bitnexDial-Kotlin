package com.bitnextechnologies.bitnexdial.data.remote.dto

import com.google.gson.annotations.SerializedName

// ==================== Authentication DTOs ====================

data class LoginRequest(
    @SerializedName("email")
    val email: String,
    @SerializedName("password")
    val password: String,
    @SerializedName("device_id")
    val deviceId: String? = null,
    @SerializedName("device_name")
    val deviceName: String? = null,
    @SerializedName("platform")
    val platform: String = "android"
)

data class LoginResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("sessionId")
    val sessionId: String?,
    @SerializedName("sessionToken")
    val sessionToken: String?,
    @SerializedName("token")
    val token: String?,
    @SerializedName("loginTime")
    val loginTime: String?,
    @SerializedName("mustChangePassword")
    val mustChangePassword: Boolean?,
    @SerializedName("refresh_token")
    val refreshToken: String?,
    @SerializedName("expires_at")
    val expiresAt: Long?,
    @SerializedName("user")
    val user: UserResponse?,
    @SerializedName("message")
    val message: String?
)

data class RefreshTokenRequest(
    @SerializedName("refresh_token")
    val refreshToken: String
)

data class ForgotPasswordRequest(
    @SerializedName("email")
    val email: String
)

data class ForgotPasswordResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?
)

data class DeviceRegistrationRequest(
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("platform")
    val platform: String = "android",
    @SerializedName("fcm_token")
    val fcmToken: String?,
    @SerializedName("app_version")
    val appVersion: String?
)

data class PushTokenRequest(
    @SerializedName("token")
    val token: String,
    @SerializedName("platform")
    val platform: String = "android",
    @SerializedName("deviceId")
    val deviceId: String? = null,
    @SerializedName("userId")
    val userId: String? = null,
    @SerializedName("phoneNumber")
    val phoneNumber: String? = null
)

data class RefreshTokenResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("token")
    val token: String?,
    @SerializedName("refresh_token")
    val refreshToken: String?,
    @SerializedName("expires_at")
    val expiresAt: Long?,
    @SerializedName("message")
    val message: String?
)

// ==================== User DTOs ====================

data class UserResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("email")
    val email: String,
    @SerializedName("name")
    val name: String?,
    @SerializedName("fullName")
    val fullName: String?,
    @SerializedName("first_name")
    val firstName: String?,
    @SerializedName("last_name")
    val lastName: String?,
    @SerializedName("avatar_url")
    val avatarUrl: String?,
    @SerializedName("phone_numbers")
    val phoneNumbers: List<PhoneNumberResponse>?,
    @SerializedName("role")
    val role: String?,
    @SerializedName("companyName")
    val companyName: String?,
    // SIP credentials
    @SerializedName("sipUsername")
    val sipUsername: String?,
    @SerializedName("sipPassword")
    val sipPassword: String?,
    @SerializedName("domain")
    val domain: String?,
    @SerializedName("webSocketPath")
    val webSocketPath: String?,
    @SerializedName("webSocketPort")
    val webSocketPort: Int?,
    @SerializedName("secureWebSocketServer")
    val secureWebSocketServer: String?,
    @SerializedName("senderPhone")
    val senderPhone: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class PhoneNumberResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("number")
    val number: String,
    @SerializedName("formatted")
    val formatted: String?,
    @SerializedName("type")
    val type: String?, // "primary", "secondary"
    @SerializedName("caller_id_name")
    val callerIdName: String?,
    @SerializedName("sms_enabled")
    val smsEnabled: Boolean?,
    @SerializedName("voice_enabled")
    val voiceEnabled: Boolean?,
    @SerializedName("is_active")
    val isActive: Boolean?
)

data class SipCredentialsResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("username")
    val username: String?,
    @SerializedName("password")
    val password: String?,
    @SerializedName("domain")
    val domain: String?,
    @SerializedName("server")
    val server: String?,
    @SerializedName("port")
    val port: Int?,
    @SerializedName("transport")
    val transport: String?, // "ws", "wss", "udp", "tcp"
    @SerializedName("stun_server")
    val stunServer: String?,
    @SerializedName("turn_server")
    val turnServer: String?,
    @SerializedName("turn_username")
    val turnUsername: String?,
    @SerializedName("turn_password")
    val turnPassword: String?,
    @SerializedName("message")
    val message: String?
)

data class UserSettingsResponse(
    @SerializedName("call_recording_enabled")
    val callRecordingEnabled: Boolean?,
    @SerializedName("voicemail_enabled")
    val voicemailEnabled: Boolean?,
    @SerializedName("do_not_disturb")
    val doNotDisturb: Boolean?,
    @SerializedName("call_forwarding_enabled")
    val callForwardingEnabled: Boolean?,
    @SerializedName("call_forwarding_number")
    val callForwardingNumber: String?,
    @SerializedName("ringtone")
    val ringtone: String?,
    @SerializedName("vibrate")
    val vibrate: Boolean?,
    @SerializedName("notification_sound")
    val notificationSound: String?
)

data class UserSettingsRequest(
    @SerializedName("call_recording_enabled")
    val callRecordingEnabled: Boolean? = null,
    @SerializedName("voicemail_enabled")
    val voicemailEnabled: Boolean? = null,
    @SerializedName("do_not_disturb")
    val doNotDisturb: Boolean? = null,
    @SerializedName("call_forwarding_enabled")
    val callForwardingEnabled: Boolean? = null,
    @SerializedName("call_forwarding_number")
    val callForwardingNumber: String? = null,
    @SerializedName("ringtone")
    val ringtone: String? = null,
    @SerializedName("vibrate")
    val vibrate: Boolean? = null,
    @SerializedName("notification_sound")
    val notificationSound: String? = null
)
