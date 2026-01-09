package com.bitnextechnologies.bitnexdial.data.remote.dto

import com.google.gson.annotations.SerializedName

// ==================== SMS API Response DTOs ====================

/**
 * Request for POST /sms-history
 */
data class SmsHistoryRequest(
    @SerializedName("number")
    val number: String,
    @SerializedName("contact")
    val contact: String,
    @SerializedName("limit")
    val limit: Int = 50,
    @SerializedName("offset")
    val offset: Int = 0
)

/**
 * Response from POST /sms-history
 */
data class SmsHistoryResponse(
    @SerializedName("success")
    val success: Boolean?,
    @SerializedName("data")
    val data: List<SmsMessageApiResponse>?,
    @SerializedName("messages")
    val messages: List<SmsMessageApiResponse>?,
    @SerializedName("pagination")
    val pagination: PaginationResponse?
)

/**
 * Response from GET /sms-latest-summary?owner=X
 */
data class SmsLatestSummaryResponse(
    @SerializedName("success")
    val success: Boolean?,
    @SerializedName("data")
    val data: List<SmsConversationSummary>?,
    @SerializedName("conversations")
    val conversations: List<SmsConversationSummary>?
)

/**
 * SMS message from the API
 * NOTE: API uses 'sender' and 'receiver' field names, not 'from' and 'to'
 */
data class SmsMessageApiResponse(
    @SerializedName("id")
    val id: Int?,
    @SerializedName("sender")
    val sender: String?,
    @SerializedName("receiver")
    val receiver: String?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("body")
    val body: String?,
    @SerializedName("direction")
    val direction: String?, // "inbound", "outbound"
    @SerializedName("status")
    val status: String?,
    @SerializedName("read")
    val read: Int?, // 0 or 1
    @SerializedName("tx_rx_datetime")
    val txRxDatetime: String?, // Primary timestamp from API
    @SerializedName("timestamp")
    val timestamp: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("media_url")
    val mediaUrl: String?
)

/**
 * SMS conversation summary from latest-summary API
 */
data class SmsConversationSummary(
    @SerializedName("contact")
    val contact: String?,
    @SerializedName("contact_name")
    val contactName: String?,
    @SerializedName("last_message")
    val lastMessage: String?,
    @SerializedName("last_timestamp")
    val lastTimestamp: String?,
    @SerializedName("unread_count")
    val unreadCount: Int?,
    @SerializedName("is_favorite")
    val isFavorite: Boolean?
)

/**
 * Request for POST /send-sms
 */
data class SendSmsRequest(
    @SerializedName("from")
    val from: String,
    @SerializedName("to")
    val to: String,
    @SerializedName("message")
    val message: String,
    @SerializedName("mediaUrl")
    val mediaUrl: String? = null,
    @SerializedName("messageUuid")
    val messageUuid: String? = null
)

/**
 * Response from POST /send-sms
 */
data class SendSmsResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?,
    @SerializedName("messageId")
    val messageId: String?
)

/**
 * Request for POST /api/mark-sms-read
 */
data class MarkSmsReadRequest(
    @SerializedName("userNumber")
    val userNumber: String,
    @SerializedName("contactId")
    val contactId: String
)

/**
 * Response from POST /api/mark-sms-read
 */
data class MarkSmsReadResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?,
    @SerializedName("markedAsRead")
    val markedAsRead: Int?
)

/**
 * Request for POST /api/unread-sms-counts
 */
data class UnreadSmsCountsRequest(
    @SerializedName("userNumber")
    val userNumber: String
)

/**
 * Response from POST /api/unread-sms-counts
 * Matches web version's response format
 */
data class UnreadSmsCountsResponse(
    @SerializedName("success")
    val success: Boolean?,
    @SerializedName("unreadCounts")
    val unreadCounts: List<UnreadCountItem>?,
    @SerializedName("total")
    val total: Int?
)

/**
 * Individual unread count item from the API
 */
data class UnreadCountItem(
    @SerializedName("contactId")
    val contactId: String?,
    @SerializedName("count")
    val count: Int?,
    @SerializedName("lastMessageTime")
    val lastMessageTime: String?,
    @SerializedName("lastMessage")
    val lastMessage: String?,
    @SerializedName("type")
    val type: String?
)

/**
 * Request for POST /api/delete-conversation
 */
data class DeleteConversationRequest(
    @SerializedName("myPhoneNumber")
    val myPhoneNumber: String,
    @SerializedName("targetNumber")
    val targetNumber: String
)

/**
 * Response from POST /api/delete-conversation
 */
data class DeleteConversationResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?
)

/**
 * Request for POST /api/delete-messages
 */
data class DeleteMessagesRequest(
    @SerializedName("messageIds")
    val messageIds: List<Int>,
    @SerializedName("deleteType")
    val deleteType: String = "forMe"
)

/**
 * Response from POST /api/delete-messages
 */
data class DeleteMessagesResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?
)

/**
 * Request for POST /api/sms-contacts/all
 */
data class SmsContactsRequest(
    @SerializedName("number")
    val number: String,
    @SerializedName("limit")
    val limit: Int = 50,
    @SerializedName("offset")
    val offset: Int = 0
)

/**
 * Response from POST /api/sms-contacts/all
 */
data class SmsContactsResponse(
    @SerializedName("success")
    val success: Boolean?,
    @SerializedName("data")
    val data: List<SmsContactItem>?,
    @SerializedName("conversations")
    val conversations: List<SmsContactItem>?,
    @SerializedName("total")
    val total: Int?,
    @SerializedName("hasMore")
    val hasMore: Boolean?,
    @SerializedName("isPaginated")
    val isPaginated: Boolean?
)

data class SmsContactItem(
    @SerializedName("contact")
    val contact: String?,
    @SerializedName("contact_number")
    val contactNumber: String?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("is_favorite")
    val isFavorite: Int?,  // 0 or 1
    @SerializedName("lastMessage")
    val lastMessage: String?,
    @SerializedName("body")
    val body: String?,
    @SerializedName("lastTimestamp")
    val lastTimestamp: String?,
    @SerializedName("tx_rx_datetime")
    val txRxDatetime: String?,
    @SerializedName("unreadCount")
    val unreadCount: Int?,
    @SerializedName("read")
    val read: Int?
)

// ==================== Favorite Chat DTOs ====================

/**
 * Request for POST /api/toggle-favorite-chat
 * Field names match server API: { myPhoneNumber, contactNumber }
 */
data class ToggleFavoriteChatRequest(
    @SerializedName("myPhoneNumber")
    val myPhoneNumber: String,
    @SerializedName("contactNumber")
    val contactNumber: String
)

/**
 * Response from POST /api/toggle-favorite-chat
 */
data class ToggleFavoriteChatResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("isFavorite")
    val isFavorite: Boolean?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("error")
    val error: String?
)

/**
 * Response from GET /api/favorite-chats
 */
data class FavoriteChatsResponse(
    @SerializedName("success")
    val success: Boolean?,
    @SerializedName("favorites")
    val favorites: List<FavoriteChatItem>?,
    @SerializedName("error")
    val error: String?
)

data class FavoriteChatItem(
    @SerializedName("contact")
    val contact: String,
    @SerializedName("name")
    val name: String?,
    @SerializedName("created_at")
    val createdAt: String?
)

// ==================== Legacy DTOs (kept for compatibility) ====================

data class ConversationsResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("conversations")
    val conversations: List<ConversationResponse>,
    @SerializedName("total")
    val total: Int?,
    @SerializedName("page")
    val page: Int?,
    @SerializedName("limit")
    val limit: Int?,
    @SerializedName("has_more")
    val hasMore: Boolean?
)

data class ConversationResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("phone_number")
    val phoneNumber: String,
    @SerializedName("contact_name")
    val contactName: String?,
    @SerializedName("contact_id")
    val contactId: String?,
    @SerializedName("last_message")
    val lastMessage: MessageResponse?,
    @SerializedName("unread_count")
    val unreadCount: Int,
    @SerializedName("is_archived")
    val isArchived: Boolean?,
    @SerializedName("is_muted")
    val isMuted: Boolean?,
    @SerializedName("updated_at")
    val updatedAt: String
)

data class MessagesResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("messages")
    val messages: List<MessageResponse>,
    @SerializedName("total")
    val total: Int?,
    @SerializedName("page")
    val page: Int?,
    @SerializedName("limit")
    val limit: Int?,
    @SerializedName("has_more")
    val hasMore: Boolean?
)

data class MessageResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("conversation_id")
    val conversationId: String?,
    @SerializedName("from_number")
    val fromNumber: String,
    @SerializedName("to_number")
    val toNumber: String,
    @SerializedName("body")
    val body: String,
    @SerializedName("direction")
    val direction: String, // "incoming", "outgoing"
    @SerializedName("status")
    val status: String, // "pending", "sent", "delivered", "failed", "received"
    @SerializedName("is_read")
    val isRead: Boolean,
    @SerializedName("media_urls")
    val mediaUrls: List<String>?,
    @SerializedName("media_type")
    val mediaType: String?, // "image", "video", "audio"
    @SerializedName("error_message")
    val errorMessage: String?,
    @SerializedName("sent_at")
    val sentAt: String?,
    @SerializedName("delivered_at")
    val deliveredAt: String?,
    @SerializedName("created_at")
    val createdAt: String
)

data class SendMessageRequest(
    @SerializedName("to")
    val to: String,
    @SerializedName("from")
    val from: String?,
    @SerializedName("body")
    val body: String,
    @SerializedName("media_urls")
    val mediaUrls: List<String>? = null
)

// ==================== Voicemail DTOs ====================

data class VoicemailsResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("voicemails")
    val voicemails: List<VoicemailResponse>,
    @SerializedName("total")
    val total: Int?,
    @SerializedName("page")
    val page: Int?,
    @SerializedName("limit")
    val limit: Int?,
    @SerializedName("has_more")
    val hasMore: Boolean?
)

data class VoicemailResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("caller_number")
    val callerNumber: String,
    @SerializedName("caller_name")
    val callerName: String?,
    @SerializedName("contact_id")
    val contactId: String?,
    @SerializedName("duration")
    val duration: Int, // seconds
    @SerializedName("is_read")
    val isRead: Boolean,
    @SerializedName("transcription")
    val transcription: String?,
    @SerializedName("audio_url")
    val audioUrl: String?,
    @SerializedName("received_at")
    val receivedAt: String,
    @SerializedName("created_at")
    val createdAt: String?
)

data class VoicemailAudioResponse(
    @SerializedName("url")
    val url: String,
    @SerializedName("expires_at")
    val expiresAt: String?
)

// ==================== File Upload DTOs ====================

/**
 * Response from POST /upload
 * Matches web's upload endpoint response
 */
data class UploadResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("path")
    val path: String?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("messageUuid")
    val messageUuid: String?,
    @SerializedName("error")
    val error: String?
)

