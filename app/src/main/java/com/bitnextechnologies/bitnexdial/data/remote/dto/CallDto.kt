package com.bitnextechnologies.bitnexdial.data.remote.dto

import com.google.gson.annotations.SerializedName

// ==================== Call History API Response DTOs ====================

/**
 * Response from GET /api/call-history?extension=X&limit=X&offset=X
 * Also used for GET /api/call-history/missed
 * Note: API may return data in either "data" or "calls" field
 */
data class CallHistoryApiResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("data")
    val data: List<CallRecordApiResponse>?,
    @SerializedName("calls")
    val calls: List<CallRecordApiResponse>?,
    @SerializedName("pagination")
    val pagination: PaginationResponse?
) {
    // Helper property to get calls from either field
    val allCalls: List<CallRecordApiResponse>?
        get() = data ?: calls
}

data class PaginationResponse(
    @SerializedName("limit")
    val limit: Int,
    @SerializedName("offset")
    val offset: Int,
    @SerializedName("count")
    val count: Int,
    @SerializedName("hasMore")
    val hasMore: Boolean
)

/**
 * Call record from the API - matches actual server response
 */
data class CallRecordApiResponse(
    @SerializedName("id")
    val id: Int,
    @SerializedName("caller")
    val caller: String?,
    @SerializedName("callee")
    val callee: String?,
    @SerializedName("direction")
    val direction: String?, // "inbound", "outbound"
    @SerializedName("start_time")
    val startTime: String?,
    @SerializedName("answer_time")
    val answerTime: String?,
    @SerializedName("end_time")
    val endTime: String?,
    @SerializedName("duration")
    val duration: Int?,
    @SerializedName("ring_time")
    val ringTime: Int?,
    @SerializedName("terminated_by")
    val terminatedBy: String?,
    @SerializedName("reason_code")
    val reasonCode: String?,
    @SerializedName("reason_text")
    val reasonText: String?,
    @SerializedName("session_id")
    val sessionId: String?,
    @SerializedName("with_video")
    val withVideo: Int?, // 0 or 1
    @SerializedName("read")
    val read: Int?, // 0 or 1
    @SerializedName("contact_name")
    val contactName: String?
)

/**
 * Response from GET /api/call-history/all-ids
 */
data class CallHistoryIdsResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("ids")
    val ids: List<Int>?
)

// ==================== Legacy DTOs (kept for compatibility) ====================

data class CallHistoryResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("calls")
    val calls: List<CallRecordResponse>,
    @SerializedName("total")
    val total: Int?,
    @SerializedName("page")
    val page: Int?,
    @SerializedName("limit")
    val limit: Int?,
    @SerializedName("has_more")
    val hasMore: Boolean?
)

data class CallRecordResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("phone_number")
    val phoneNumber: String,
    @SerializedName("contact_name")
    val contactName: String?,
    @SerializedName("direction")
    val direction: String, // "incoming", "outgoing"
    @SerializedName("type")
    val type: String, // "answered", "missed", "rejected", "voicemail"
    @SerializedName("duration")
    val duration: Int?, // seconds
    @SerializedName("started_at")
    val startedAt: String,
    @SerializedName("ended_at")
    val endedAt: String?,
    @SerializedName("is_read")
    val isRead: Boolean?,
    @SerializedName("recording_url")
    val recordingUrl: String?,
    @SerializedName("recording_id")
    val recordingId: String?,
    @SerializedName("line_number")
    val lineNumber: Int?,
    @SerializedName("from_number")
    val fromNumber: String?,
    @SerializedName("to_number")
    val toNumber: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class CreateCallRecordRequest(
    @SerializedName("phone_number")
    val phoneNumber: String,
    @SerializedName("contact_name")
    val contactName: String? = null,
    @SerializedName("direction")
    val direction: String, // "incoming", "outgoing"
    @SerializedName("type")
    val type: String, // "answered", "missed", "rejected", "voicemail"
    @SerializedName("duration")
    val duration: Int? = null,
    @SerializedName("started_at")
    val startedAt: String,
    @SerializedName("ended_at")
    val endedAt: String? = null,
    @SerializedName("line_number")
    val lineNumber: Int? = null,
    @SerializedName("from_number")
    val fromNumber: String? = null,
    @SerializedName("to_number")
    val toNumber: String? = null
)

/**
 * Request for POST /api/save-call
 */
data class SaveCallRequest(
    @SerializedName("caller")
    val caller: String,
    @SerializedName("callee")
    val callee: String,
    @SerializedName("direction")
    val direction: String, // "inbound", "outbound"
    @SerializedName("start_time")
    val startTime: String,
    @SerializedName("answer_time")
    val answerTime: String? = null,
    @SerializedName("end_time")
    val endTime: String? = null,
    @SerializedName("duration")
    val duration: Int = 0,
    @SerializedName("ring_time")
    val ringTime: Int = 0,
    @SerializedName("terminated_by")
    val terminatedBy: String? = null,
    @SerializedName("reason_code")
    val reasonCode: String? = null,
    @SerializedName("reason_text")
    val reasonText: String? = null,
    @SerializedName("session_id")
    val sessionId: String,
    @SerializedName("with_video")
    val withVideo: Boolean = false
)

/**
 * Response from POST /api/save-call
 */
data class SaveCallResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("callId")
    val callId: Int?,
    @SerializedName("duplicate")
    val duplicate: Boolean?,
    @SerializedName("isMissedCall")
    val isMissedCall: Boolean?,
    @SerializedName("savedAt")
    val savedAt: String?
)

/**
 * Request for POST /api/mark-calls-read
 */
data class MarkCallsReadRequest(
    @SerializedName("callIds")
    val callIds: List<Int>?,
    @SerializedName("extension")
    val extension: String?,
    @SerializedName("userNumber")
    val userNumber: String?
)

/**
 * Response from POST /api/mark-calls-read
 */
data class MarkCallsReadResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?,
    @SerializedName("updatedCount")
    val updatedCount: Int?
)

// ==================== Voicemail API DTOs ====================

/**
 * Response from GET /api/voicemails?mailbox=X
 */
data class VoicemailsApiResponse(
    @SerializedName("success")
    val success: Boolean?,
    @SerializedName("data")
    val data: List<VoicemailApiItem>?,
    @SerializedName("voicemails")
    val voicemails: List<VoicemailApiItem>?
)

data class VoicemailApiItem(
    @SerializedName("id")
    val id: String?,
    @SerializedName("msgnum")
    val msgnum: Int?,
    @SerializedName("caller_number")
    val callerNumber: String?,
    @SerializedName("callerid")
    val callerId: String?,
    @SerializedName("duration")
    val duration: Int?,
    @SerializedName("origtime")
    val origTime: Long?,
    @SerializedName("received_at")
    val receivedAt: String?,
    @SerializedName("msg_folder")
    val msgFolder: String?,
    @SerializedName("read")
    val read: Int?,
    @SerializedName("transcription")
    val transcription: String?,
    @SerializedName("audio_url")
    val audioUrl: String?
)

/**
 * Request for POST /api/mark-voicemail-read
 */
data class MarkVoicemailReadRequest(
    @SerializedName("mailbox")
    val mailbox: String,
    @SerializedName("messageId")
    val messageId: String
)

/**
 * Response from POST /api/mark-voicemail-read
 */
data class MarkVoicemailReadResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?
)

// ==================== Blocked Numbers DTOs ====================

/**
 * Request for POST /api/block-number
 */
data class BlockNumberRequest(
    @SerializedName("owner")
    val owner: String,
    @SerializedName("contact")
    val contact: String,
    @SerializedName("reason")
    val reason: String? = null
)

/**
 * Request for POST /api/unblock-number
 */
data class UnblockNumberRequest(
    @SerializedName("owner")
    val owner: String,
    @SerializedName("contact")
    val contact: String
)

/**
 * Response from POST /api/unblock-number
 */
data class UnblockNumberResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?
)

// ==================== Legacy Blocked Numbers DTOs ====================

data class BlockedNumbersResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("blocked")
    val blocked: List<BlockedNumberResponse>
)

data class BlockedNumberResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("phone_number")
    val phoneNumber: String,
    @SerializedName("contact_name")
    val contactName: String?,
    @SerializedName("reason")
    val reason: String?,
    @SerializedName("blocked_at")
    val blockedAt: String
)

data class IsBlockedResponse(
    @SerializedName("is_blocked")
    val isBlocked: Boolean,
    @SerializedName("blocked_id")
    val blockedId: String?
)

// ==================== Phone Number DTOs ====================

/**
 * Request for POST /api/numbers/switch
 */
data class SwitchNumberRequest(
    @SerializedName("phone_number")
    val phoneNumber: String
)

/**
 * Response from POST /api/numbers/switch
 */
data class SwitchNumberResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?,
    @SerializedName("phone_number")
    val phoneNumber: String?
)

// ==================== Recording DTOs ====================

data class RecordingsResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("recordings")
    val recordings: List<RecordingResponse>,
    @SerializedName("total")
    val total: Int?,
    @SerializedName("page")
    val page: Int?,
    @SerializedName("limit")
    val limit: Int?,
    @SerializedName("has_more")
    val hasMore: Boolean?
)

data class RecordingResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("call_id")
    val callId: String?,
    @SerializedName("phone_number")
    val phoneNumber: String,
    @SerializedName("contact_name")
    val contactName: String?,
    @SerializedName("direction")
    val direction: String?,
    @SerializedName("duration")
    val duration: Int,
    @SerializedName("file_size")
    val fileSize: Long?,
    @SerializedName("audio_url")
    val audioUrl: String?,
    @SerializedName("recorded_at")
    val recordedAt: String,
    @SerializedName("created_at")
    val createdAt: String?
)

data class RecordingAudioResponse(
    @SerializedName("url")
    val url: String,
    @SerializedName("expires_at")
    val expiresAt: String?
)

// ==================== Call Delete DTOs ====================

/**
 * Request for POST /api/call-history/bulk-delete
 */
data class DeleteCallsRequest(
    @SerializedName("callIds")
    val callIds: List<String>,
    @SerializedName("myPhoneNumber")
    val myPhoneNumber: String
)

/**
 * Response from DELETE /api/call-history/{callId} or POST /api/call-history/bulk-delete
 */
data class DeleteCallsResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String?,
    @SerializedName("deletedCount")
    val deletedCount: Int?
)
