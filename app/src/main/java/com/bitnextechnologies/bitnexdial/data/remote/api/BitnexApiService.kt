package com.bitnextechnologies.bitnexdial.data.remote.api

import com.bitnextechnologies.bitnexdial.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API service interface for BitNex backend
 * Endpoints based on the actual server API
 */
interface BitnexApiService {

    // ==================== Authentication ====================

    @POST("admin/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<LoginResponse>

    @POST("admin/logout")
    suspend fun logout(): Response<Unit>

    @GET("api/validate-session")
    suspend fun validateSession(): Response<UserResponse>

    @POST("api/auth/refresh")
    suspend fun refreshToken(
        @Body request: RefreshTokenRequest
    ): Response<LoginResponse>

    @POST("api/auth/forgot-password")
    suspend fun forgotPassword(
        @Body request: ForgotPasswordRequest
    ): Response<ForgotPasswordResponse>

    // ==================== Phone Numbers ====================

    @GET("api/numbers/list")
    suspend fun getPhoneNumbers(): Response<List<PhoneNumberResponse>>

    @POST("api/numbers/switch")
    suspend fun switchNumber(
        @Body request: SwitchNumberRequest
    ): Response<SwitchNumberResponse>

    @GET("api/user/sip-credentials")
    suspend fun getSipCredentials(
        @Query("phone_number") phoneNumber: String
    ): Response<SipCredentialsResponse>

    // ==================== Contacts ====================

    @GET("api/get-contacts")
    suspend fun getContacts(
        @Query("owner") owner: String
    ): Response<List<ContactApiItem>>

    @POST("api/save-contact")
    suspend fun saveContact(
        @Body contact: SaveContactRequest
    ): Response<SaveContactResponse>

    @POST("api/delete-contact")
    suspend fun deleteContact(
        @Body request: DeleteContactRequest
    ): Response<DeleteContactResponse>

    @POST("api/sms-contacts/all")
    suspend fun getAllSmsContacts(
        @Body request: SmsContactsRequest
    ): Response<SmsContactsResponse>

    @POST("api/sms-contacts/favorites")
    suspend fun getFavoriteContacts(
        @Body request: SmsContactsRequest
    ): Response<SmsContactsResponse>

    // ==================== Call History ====================

    @GET("api/call-history")
    suspend fun getCallHistory(
        @Query("extension") extension: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<CallHistoryApiResponse>

    @GET("api/call-history/outgoing")
    suspend fun getOutgoingCalls(
        @Query("extension") extension: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<CallHistoryApiResponse>

    @GET("api/call-history/incoming")
    suspend fun getIncomingCalls(
        @Query("extension") extension: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<CallHistoryApiResponse>

    @GET("api/call-history/missed")
    suspend fun getMissedCalls(
        @Query("extension") extension: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<CallHistoryApiResponse>

    @GET("api/call-history/all-ids")
    suspend fun getCallHistoryIds(
        @Query("extension") extension: String
    ): Response<CallHistoryIdsResponse>

    @POST("api/save-call")
    suspend fun saveCall(
        @Body record: SaveCallRequest
    ): Response<SaveCallResponse>

    @POST("api/mark-calls-read")
    suspend fun markCallsAsRead(
        @Body request: MarkCallsReadRequest
    ): Response<MarkCallsReadResponse>

    @DELETE("api/call-history/{callId}")
    suspend fun deleteCall(
        @Path("callId") callId: String,
        @Query("myPhoneNumber") myPhoneNumber: String
    ): Response<DeleteCallsResponse>

    @POST("api/call-history/bulk-delete")
    suspend fun deleteCalls(
        @Body request: DeleteCallsRequest
    ): Response<DeleteCallsResponse>

    // ==================== SMS/Messages ====================

    @POST("sms-history")
    suspend fun getSmsHistory(
        @Body request: SmsHistoryRequest
    ): Response<SmsHistoryResponse>

    @GET("api/sms-latest-summary")
    suspend fun getSmsLatestSummary(
        @Query("owner") owner: String
    ): Response<SmsLatestSummaryResponse>

    @POST("api/mark-sms-read")
    suspend fun markSmsAsRead(
        @Body request: MarkSmsReadRequest
    ): Response<MarkSmsReadResponse>

    @POST("api/unread-sms-counts")
    suspend fun getUnreadSmsCounts(
        @Body request: UnreadSmsCountsRequest
    ): Response<UnreadSmsCountsResponse>

    @POST("api/send-sms")
    suspend fun sendSms(
        @Body request: SendSmsRequest
    ): Response<SendSmsResponse>

    @POST("api/delete-conversation")
    suspend fun deleteConversation(
        @Body request: DeleteConversationRequest
    ): Response<DeleteConversationResponse>

    @POST("api/delete-messages")
    suspend fun deleteMessages(
        @Body request: DeleteMessagesRequest
    ): Response<DeleteMessagesResponse>

    @POST("api/toggle-favorite-chat")
    suspend fun toggleFavoriteChat(
        @Body request: ToggleFavoriteChatRequest
    ): Response<ToggleFavoriteChatResponse>

    @GET("api/favorite-chats")
    suspend fun getFavoriteChats(
        @Query("owner") owner: String
    ): Response<FavoriteChatsResponse>

    // ==================== Voicemail ====================

    @GET("api/voicemails")
    suspend fun getVoicemails(
        @Query("mailbox") mailbox: String
    ): Response<List<VoicemailApiItem>>

    @POST("api/mark-voicemail-read")
    suspend fun markVoicemailAsRead(
        @Body request: MarkVoicemailReadRequest
    ): Response<MarkVoicemailReadResponse>

    // ==================== Blocked Numbers ====================

    @GET("api/blocked-numbers")
    suspend fun getBlockedNumbers(
        @Query("owner") owner: String
    ): Response<BlockedNumbersResponse>

    @POST("api/block-number")
    suspend fun blockNumber(
        @Body request: BlockNumberRequest
    ): Response<BlockedNumberResponse>

    @POST("api/unblock-number")
    suspend fun unblockNumber(
        @Body request: UnblockNumberRequest
    ): Response<UnblockNumberResponse>

    // ==================== Recordings ====================

    @GET("api/recordings")
    suspend fun getRecordings(
        @Query("extension") extension: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<RecordingsResponse>

    // ==================== Push Notifications ====================

    @POST("api/push/register")
    suspend fun registerPushToken(
        @Body request: PushTokenRequest
    ): Response<Unit>

    @POST("api/push/unregister")
    suspend fun unregisterPushToken(
        @Body request: PushTokenRequest
    ): Response<Unit>

    // ==================== File Upload (MMS) ====================

    /**
     * Upload file for MMS/media messaging
     * Matches web's POST /upload endpoint on port 3000
     * Uses @Url to allow passing the full URL with port 3000
     */
    @Multipart
    @POST
    suspend fun uploadFile(
        @retrofit2.http.Url url: String,
        @Part file: MultipartBody.Part,
        @Part("sender") sender: RequestBody,
        @Part("receiver") receiver: RequestBody,
        @Part("body") body: RequestBody?
    ): Response<UploadResponse>
}
