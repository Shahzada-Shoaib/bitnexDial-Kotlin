package com.bitnextechnologies.bitnexdial.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bitnextechnologies.bitnexdial.BuildConfig
import com.bitnextechnologies.bitnexdial.data.local.dao.MessageDao
import com.bitnextechnologies.bitnexdial.data.local.entity.ConversationEntity
import com.bitnextechnologies.bitnexdial.data.local.entity.MessageEntity
import com.bitnextechnologies.bitnexdial.data.remote.api.BitnexApiService
import com.bitnextechnologies.bitnexdial.data.remote.dto.DeleteConversationRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.DeleteMessagesRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.MarkSmsReadRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.SendSmsRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.SmsContactsRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.SmsHistoryRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.UnreadSmsCountsRequest
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketEvent
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.domain.model.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.bitnextechnologies.bitnexdial.di.ApplicationScope
import com.bitnextechnologies.bitnexdial.domain.model.Message
import com.bitnextechnologies.bitnexdial.domain.model.MessageDirection
import com.bitnextechnologies.bitnexdial.domain.model.MessageStatus
import com.bitnextechnologies.bitnexdial.domain.repository.IMessageRepository
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.bitnextechnologies.bitnexdial.util.DateTimeUtils
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MessageRepositoryImpl"

@Singleton
class MessageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val apiService: BitnexApiService,
    private val socketManager: SocketManager,
    private val secureCredentialManager: SecureCredentialManager,
    @ApplicationScope private val applicationScope: CoroutineScope
) : IMessageRepository {

    // Use injected application scope for background operations
    // This scope is managed by Hilt and properly cleaned up with the application
    private val repositoryScope: CoroutineScope get() = applicationScope

    init {
        // Start listening for socket events to update message status in real-time
        startSocketEventListener()
    }

    /**
     * Listen for socket events and update message status accordingly
     * This mirrors the web version's handling of sms-sent and sms-status-update events
     */
    private fun startSocketEventListener() {
        repositoryScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.SmsSent -> handleSmsSentEvent(event)
                    is SocketEvent.SmsStatusUpdate -> handleSmsStatusUpdateEvent(event)
                    else -> { /* Ignore other events */ }
                }
            }
        }
    }

    /**
     * Handle sms-sent event from server
     * Updates message status: SENDING -> SENT (success) or SENDING -> FAILED (error)
     */
    private suspend fun handleSmsSentEvent(event: SocketEvent.SmsSent) {
        val messageUuid = event.messageUuid
        if (messageUuid.isBlank()) {
            Log.w(TAG, "handleSmsSentEvent: Empty messageUuid, ignoring")
            return
        }

        Log.d(TAG, "handleSmsSentEvent: uuid=$messageUuid, success=${event.success}, error=${event.error}")

        val currentMessage = messageDao.getMessageById(messageUuid)
        if (currentMessage == null) {
            Log.w(TAG, "handleSmsSentEvent: Message $messageUuid not found in database")
            return
        }

        if (event.success) {
            messageDao.updateMessage(
                currentMessage.copy(
                    status = MessageStatus.SENT.name,
                    sentAt = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "handleSmsSentEvent: Message $messageUuid status changed ${currentMessage.status} -> SENT")
        } else {
            messageDao.updateMessage(
                currentMessage.copy(
                    status = MessageStatus.FAILED.name,
                    errorMessage = event.error
                )
            )
            Log.e(TAG, "handleSmsSentEvent: Message $messageUuid status changed ${currentMessage.status} -> FAILED: ${event.error}")
        }
    }

    /**
     * Handle sms-status-update event from server
     * Updates message status: SENT -> DELIVERED, etc.
     */
    private suspend fun handleSmsStatusUpdateEvent(event: SocketEvent.SmsStatusUpdate) {
        val messageUuid = event.messageUuid
        if (messageUuid.isBlank()) {
            Log.w(TAG, "handleSmsStatusUpdateEvent: Empty messageUuid, ignoring")
            return
        }

        Log.d(TAG, "📬 handleSmsStatusUpdateEvent: uuid=$messageUuid, status=${event.status}")

        // Map server status to our MessageStatus enum
        val newStatus = when (event.status.lowercase()) {
            "delivered" -> MessageStatus.DELIVERED
            "sent" -> MessageStatus.SENT
            "failed", "undelivered" -> MessageStatus.FAILED
            "queued", "sending" -> MessageStatus.SENDING
            else -> {
                Log.w(TAG, "📬 Unknown status '${event.status}' for message $messageUuid")
                return
            }
        }

        val currentMessage = messageDao.getMessageById(messageUuid)
        if (currentMessage == null) {
            Log.w(TAG, "📬 Message $messageUuid not found in database")
            return
        }

        val oldStatus = currentMessage.status

        // Update message with new status and timestamp
        val updatedMessage = when (newStatus) {
            MessageStatus.DELIVERED -> currentMessage.copy(
                status = newStatus.name,
                deliveredAt = System.currentTimeMillis()
            )
            MessageStatus.SENT -> currentMessage.copy(
                status = newStatus.name,
                sentAt = currentMessage.sentAt ?: System.currentTimeMillis()
            )
            else -> currentMessage.copy(status = newStatus.name)
        }

        messageDao.updateMessage(updatedMessage)
        Log.d(TAG, "📬 Message $messageUuid: $oldStatus -> ${newStatus.name}")
    }

    /**
     * Normalize conversationId to a consistent format.
     * Uses 10-digit US format to prevent duplicates from different phone formats.
     * e.g., "+12102012856", "12102012856", "2102012856" all become "2102012856"
     */
    private fun normalizeConversationId(phoneNumber: String): String {
        val digits = phoneNumber.replace(Regex("[^\\d]"), "")
        return when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            digits.length > 11 && digits.startsWith("1") -> digits.substring(1).take(10)
            else -> digits // For non-US numbers, keep as-is
        }
    }

    /**
     * Get the user's phone number from SecureCredentialManager
     * Returns raw format (may include + and country code)
     */
    private fun getUserPhoneNumber(): String {
        val phone = secureCredentialManager.getSenderPhone() ?: ""
        return phone.replace(Regex("[^\\d+]"), "")
    }

    /**
     * Get the user's phone number normalized to 10 digits (US format)
     * Matches web version's normalizePhoneNumber() in globalSMSService.ts
     */
    private fun getNormalized10DigitNumber(): String {
        return getNormalized10DigitNumber(getUserPhoneNumber())
    }

    /**
     * Normalize any phone number to 10 digits (US format)
     * Matches web version's normalizePhoneNumber() in globalSMSService.ts
     */
    private fun getNormalized10DigitNumber(phone: String): String {
        val digitsOnly = phone.replace(Regex("[^\\d]"), "")
        return when {
            digitsOnly.length == 11 && digitsOnly.startsWith("1") -> digitsOnly.substring(1)
            digitsOnly.length == 10 -> digitsOnly
            else -> digitsOnly
        }
    }

    override fun getConversations(): Flow<List<Conversation>> {
        return messageDao.getActiveConversations().map { entities ->
            entities.map { entity ->
                Conversation(
                    id = entity.id,
                    phoneNumber = entity.phoneNumber,
                    contactName = entity.contactName,
                    contactId = entity.contactId,
                    lastMessage = entity.lastMessageBody,
                    lastMessageTime = entity.lastMessageTime,
                    unreadCount = entity.unreadCount,
                    isArchived = entity.isArchived,
                    isMuted = entity.isMuted
                )
            }
        }
    }

    override fun getMessagesForConversation(conversationId: String): Flow<List<Message>> {
        // Try multiple phone number formats to handle mismatches between:
        // - Synced conversations (use raw format from API like "2100759306")
        // - New conversations (use normalized 10-digit format)
        val normalized = getNormalized10DigitNumber(conversationId)
        val with1Prefix = "1$normalized"
        val withPlus1Prefix = "+1$normalized"

        // Build list of possible IDs to search (deduped)
        val possibleIds = listOfNotNull(
            conversationId,
            normalized,
            with1Prefix,
            withPlus1Prefix
        ).distinct()

        Log.d(TAG, "getMessagesForConversation: searching with IDs=$possibleIds")

        return messageDao.getMessagesForConversationIds(possibleIds).map { entities ->
            // Deduplicate messages for display (handles any legacy duplicates)
            val deduplicatedMessages = deduplicateMessagesForDisplay(entities)
            Log.d(TAG, "getMessagesForConversation: found ${entities.size} messages, after dedup: ${deduplicatedMessages.size}")
            deduplicatedMessages.map { it.toDomain() }
        }
    }

    /**
     * Deduplicate messages for display (handles legacy duplicates from before UNIQUE constraint).
     *
     * PROFESSIONAL APPROACH:
     * - Database UNIQUE constraint on contentSignature prevents new duplicates
     * - This function handles any legacy duplicates that existed before the fix
     * - Uses contentSignature field if available, falls back to computed signature
     */
    private fun deduplicateMessagesForDisplay(messages: List<MessageEntity>): List<MessageEntity> {
        if (messages.size <= 1) return messages

        // Use contentSignature as the dedup key (database ensures uniqueness for new messages)
        // For legacy messages, compute signature from content
        val seen = mutableSetOf<String>()
        return messages.filter { msg ->
            val signature = msg.contentSignature.ifEmpty {
                // Fallback for legacy messages without contentSignature
                // Use 5-minute buckets (300000ms) to match MessageEntity.generateContentSignature
                val normalizedFrom = getNormalized10DigitNumber(msg.fromNumber)
                val normalizedTo = getNormalized10DigitNumber(msg.toNumber)
                val timeBucket = msg.createdAt / 300000
                "$normalizedFrom|$normalizedTo|${msg.body}|$timeBucket"
            }
            seen.add(signature)  // Returns true if this is a new element
        }.sortedBy { it.createdAt }
    }

    override suspend fun getConversationById(conversationId: String): Conversation? {
        return messageDao.getConversationById(conversationId)?.let { entity ->
            Conversation(
                id = entity.id,
                phoneNumber = entity.phoneNumber,
                contactName = entity.contactName,
                contactId = entity.contactId,
                lastMessage = entity.lastMessageBody,
                lastMessageTime = entity.lastMessageTime,
                unreadCount = entity.unreadCount,
                isArchived = entity.isArchived,
                isMuted = entity.isMuted
            )
        }
    }

    override suspend fun getConversationByPhoneNumber(phoneNumber: String): Conversation? {
        // Try multiple phone number formats
        val normalized = getNormalized10DigitNumber(phoneNumber)
        val with1Prefix = "1$normalized"
        val withPlus1Prefix = "+1$normalized"

        Log.d(TAG, "getConversationByPhoneNumber: trying formats [$phoneNumber, $normalized, $with1Prefix, $withPlus1Prefix]")

        // Try each format
        val entity = messageDao.getConversationByPhoneNumber(phoneNumber)
            ?: messageDao.getConversationByPhoneNumber(normalized)
            ?: messageDao.getConversationByPhoneNumber(with1Prefix)
            ?: messageDao.getConversationByPhoneNumber(withPlus1Prefix)
            ?: messageDao.getConversationById(phoneNumber)
            ?: messageDao.getConversationById(normalized)

        Log.d(TAG, "getConversationByPhoneNumber: found=${entity != null}, id=${entity?.id}")

        return entity?.let {
            Conversation(
                id = it.id,
                phoneNumber = it.phoneNumber,
                contactName = it.contactName,
                contactId = it.contactId,
                lastMessage = it.lastMessageBody,
                lastMessageTime = it.lastMessageTime,
                unreadCount = it.unreadCount,
                isArchived = it.isArchived,
                isMuted = it.isMuted
            )
        }
    }

    override suspend fun getOrCreateConversation(phoneNumber: String, contactName: String?): Conversation {
        Log.d(TAG, "getOrCreateConversation: phoneNumber=$phoneNumber, contactName=$contactName")

        // First try to find existing conversation
        val existing = getConversationByPhoneNumber(phoneNumber)
        if (existing != null) {
            Log.d(TAG, "getOrCreateConversation: Found existing id=${existing.id}")
            return existing
        }

        // Create a new conversation for new chat
        val normalized = getNormalized10DigitNumber(phoneNumber)
        val now = System.currentTimeMillis()

        Log.d(TAG, "getOrCreateConversation: Creating new conversation with id=$normalized")

        val newConversation = ConversationEntity(
            id = normalized,
            phoneNumber = phoneNumber,
            contactName = contactName,
            contactId = null,
            lastMessageId = null,
            lastMessageBody = null,
            lastMessageTime = now,
            unreadCount = 0,
            isArchived = false,
            isMuted = false,
            updatedAt = now,
            syncedAt = null
        )

        messageDao.insertConversation(newConversation)
        Log.d(TAG, "getOrCreateConversation: Created new conversation for $phoneNumber")

        return Conversation(
            id = normalized,
            phoneNumber = phoneNumber,
            contactName = contactName,
            contactId = null,
            lastMessage = null,
            lastMessageTime = now,
            unreadCount = 0,
            isArchived = false,
            isMuted = false
        )
    }

    override suspend fun getMessageById(messageId: String): Message? {
        return messageDao.getMessageById(messageId)?.toDomain()
    }

    override suspend fun sendMessage(
        conversationId: String,
        toNumber: String,
        fromNumber: String,
        body: String,
        mediaUrl: String?
    ): Message {
        Log.e(TAG, "🚀 sendMessage CALLED: to=$toNumber, body=${body.take(20)}")

        // Generate UUID like web version: msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}
        val messageUuid = "msg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(9)}"
        Log.e(TAG, "🚀 sendMessage: Generated UUID=$messageUuid")
        val now = System.currentTimeMillis()

        // Normalize conversationId to prevent duplicates when syncing
        val normalizedConvId = normalizeConversationId(conversationId)

        // Build message body with media if present (like web's handleSendFile)
        val finalBody = if (mediaUrl != null) {
            buildMediaMessageBody(mediaUrl, body)
        } else {
            body
        }

        val message = Message(
            id = messageUuid,
            conversationId = normalizedConvId,
            fromNumber = fromNumber,
            toNumber = toNumber,
            body = finalBody,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENDING,
            isRead = true,
            mediaUrls = if (mediaUrl != null) listOf(mediaUrl) else emptyList(),
            sentAt = null,
            deliveredAt = null,
            createdAt = now
        )

        // Save to database immediately for persistence
        // Use messageUuid for matching when server syncs back
        messageDao.insertMessage(MessageEntity.fromDomain(message))
        Log.e(TAG, "🚀 sendMessage: Saved to DB with UUID=$messageUuid")

        // Update conversation preview - use normalized ID for SSOT consistency
        val previewText = if (mediaUrl != null) {
            if (body.isNotBlank()) body else "📷 Image"
        } else {
            body
        }
        updateConversation(normalizedConvId, toNumber, previewText, now)

        // Send via Socket.IO like web version (socket.emit('send-sms'))
        // This matches TextInterface.tsx handleSendMessage which uses socket
        try {
            // Format phone numbers to match web version exactly:
            // Web: from = '+1' + me10 (with +1 prefix)
            // Web: to = selectedContact.id (raw, no + prefix)
            val formattedFrom = formatFromNumberForSending(fromNumber)
            val formattedTo = formatToNumberForSending(toNumber)

            Log.d(TAG, "sendMessage: Sending via socket from=$formattedFrom to=$formattedTo uuid=$messageUuid")

            // Check if socket is connected before sending
            val isSocketConnected = socketManager.isConnected()
            Log.d(TAG, "sendMessage: Socket connected=$isSocketConnected")

            if (!isSocketConnected) {
                throw Exception("Socket not connected, will fallback to API")
            }

            // Use socket for real-time messaging like web version
            socketManager.sendSms(
                fromNumber = formattedFrom,
                toNumber = formattedTo,
                message = body, // Send original message (not HTML combined), server handles that
                messageUuid = messageUuid,
                mediaUrl = mediaUrl
            )

            // Keep status as SENDING - socket event handler will move to DB when server confirms
            Log.d(TAG, "sendMessage: Message emitted via socket, waiting for sms-sent event")

            return message.copy(status = MessageStatus.SENDING)
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage: Socket send failed, falling back to API", e)

            // Fallback to HTTP API if socket fails
            try {
                val response = apiService.sendSms(
                    SendSmsRequest(
                        from = formatPhoneNumberForSending(fromNumber),
                        to = formatPhoneNumberForSending(toNumber),
                        message = body,
                        mediaUrl = mediaUrl,
                        messageUuid = messageUuid
                    )
                )

                if (response.isSuccessful && response.body()?.success == true) {
                    messageDao.updateMessageStatus(messageUuid, "SENT")
                    Log.d(TAG, "sendMessage: Message sent via API fallback successfully")
                    return message.copy(status = MessageStatus.SENT, sentAt = now)
                } else {
                    messageDao.updateMessageStatus(messageUuid, "FAILED")
                    Log.e(TAG, "sendMessage: API returned error")
                    return message.copy(status = MessageStatus.FAILED)
                }
            } catch (apiError: Exception) {
                Log.e(TAG, "sendMessage: API fallback also failed", apiError)
                messageDao.updateMessageStatus(messageUuid, "FAILED")
                return message.copy(status = MessageStatus.FAILED)
            }
        }
    }

    /**
     * Send MMS message with file attachment
     * Matches web's handleSendFile() - uploads file first, then sends via socket
     */
    override suspend fun sendMessageWithMedia(
        conversationId: String,
        toNumber: String,
        fromNumber: String,
        body: String,
        fileUri: String,
        mimeType: String,
        fileName: String
    ): Message {
        val messageUuid = "msg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(9)}"
        val now = System.currentTimeMillis()

        // Normalize conversationId to prevent duplicates when syncing
        val normalizedConvId = normalizeConversationId(conversationId)

        // Create message for immediate UI update (in-memory only)
        val pendingMessage = Message(
            id = messageUuid,
            conversationId = normalizedConvId,
            fromNumber = fromNumber,
            toNumber = toNumber,
            body = if (body.isNotBlank()) body else "📷 Image",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENDING,
            isRead = true,
            mediaUrls = emptyList(), // Will be updated after upload
            sentAt = null,
            deliveredAt = null,
            createdAt = now
        )

        // Save to database immediately for persistence
        messageDao.insertMessage(MessageEntity.fromDomain(pendingMessage))
        // Use normalized ID for SSOT consistency
        updateConversation(normalizedConvId, toNumber, if (body.isNotBlank()) body else "📷 Image", now)

        try {
            // Step 1: Upload file to server (like web's XHR upload to /upload on port 3000)
            // Uses BuildConfig.SOCKET_URL to match environment (dev/prod)
            val uploadUrl = BuildConfig.SOCKET_URL + "/upload"
            Log.d(TAG, "sendMessageWithMedia: Uploading file $fileName to $uploadUrl")

            val file = copyUriToTempFile(fileUri, fileName)
            val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestFile)

            val senderPart = fromNumber.toRequestBody("text/plain".toMediaTypeOrNull())
            val receiverPart = toNumber.toRequestBody("text/plain".toMediaTypeOrNull())
            val bodyPart = body.toRequestBody("text/plain".toMediaTypeOrNull())

            val uploadResponse = apiService.uploadFile(
                url = uploadUrl,
                file = filePart,
                sender = senderPart,
                receiver = receiverPart,
                body = bodyPart
            )

            // Clean up temp file
            file.delete()

            if (!uploadResponse.isSuccessful || uploadResponse.body()?.success != true) {
                Log.e(TAG, "sendMessageWithMedia: Upload failed")
                messageDao.updateMessageStatus(messageUuid, "FAILED")
                return pendingMessage.copy(status = MessageStatus.FAILED)
            }

            val uploadResult = uploadResponse.body()
                ?: throw Exception("Upload response body is null")
            val mediaPath = uploadResult.path ?: uploadResult.url
            val fullMediaUrl = "${BuildConfig.API_BASE_URL}$mediaPath"

            Log.d(TAG, "sendMessageWithMedia: Upload successful, mediaUrl=$fullMediaUrl")

            // Step 2: Build combined body for storage (like web's combinedBody)
            val combinedBody = buildMediaMessageBody(fullMediaUrl, body)

            // Update message with media URL
            val updatedMessage = pendingMessage.copy(
                body = combinedBody,
                mediaUrls = listOf(fullMediaUrl)
            )
            messageDao.upsertMessage(MessageEntity.fromDomain(updatedMessage))

            // Step 3: Send via socket with mediaUrl (matches web's socket emit after upload)
            // Format numbers to match web: from with +1, to without + prefix
            socketManager.sendSms(
                fromNumber = formatFromNumberForSending(fromNumber),
                toNumber = formatToNumberForSending(toNumber),
                message = body, // Original message text
                messageUuid = messageUuid,
                mediaUrl = fullMediaUrl
            )

            // Keep status as SENDING - socket event handler will move to DB when confirmed
            Log.d(TAG, "sendMessageWithMedia: MMS emitted via socket, waiting for sms-sent event")

            return updatedMessage.copy(status = MessageStatus.SENDING)

        } catch (e: Exception) {
            Log.e(TAG, "sendMessageWithMedia: Failed to send MMS", e)
            messageDao.updateMessageStatus(messageUuid, "FAILED")
            return pendingMessage.copy(status = MessageStatus.FAILED)
        }
    }

    /**
     * Build HTML body with media like web version
     * Matches web's: `<img class="previewImage" src="${fileUrl}" onclick="PreviewImage(this)">`
     */
    private fun buildMediaMessageBody(mediaUrl: String, textBody: String): String {
        val isImage = mediaUrl.lowercase().let {
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") ||
            it.endsWith(".gif") || it.endsWith(".webp") || it.contains("image")
        }

        val mediaHtml = if (isImage) {
            """<img class="previewImage" src="$mediaUrl" onclick="PreviewImage(this)">"""
        } else {
            val fileName = mediaUrl.substringAfterLast("/")
            """<a href="$mediaUrl" target="_blank">$fileName</a>"""
        }

        return if (textBody.isNotBlank()) {
            "$mediaHtml<br>$textBody"
        } else {
            mediaHtml
        }
    }

    /**
     * Format "from" phone number for sending (add +1 prefix like web version)
     * Web: from = '+1' + me10  (e.g., '+12164281997')
     */
    private fun formatFromNumberForSending(phone: String): String {
        val cleaned = phone.replace(Regex("[^\\d+]"), "")
        return when {
            cleaned.startsWith("+") -> cleaned
            cleaned.length == 10 -> "+1$cleaned"
            cleaned.length == 11 && cleaned.startsWith("1") -> "+$cleaned"
            else -> "+1$cleaned"
        }
    }

    /**
     * Format "to" phone number for sending
     * Twilio requires E.164 format: +1XXXXXXXXXX
     * Server passes this directly to Twilio at line 2337 of send-sms.js
     */
    private fun formatToNumberForSending(phone: String): String {
        val cleaned = phone.replace(Regex("[^\\d]"), "")
        return when {
            cleaned.length == 10 -> "+1$cleaned"  // 10-digit -> +1XXXXXXXXXX
            cleaned.length == 11 && cleaned.startsWith("1") -> "+$cleaned"  // 11-digit starting with 1 -> +1XXXXXXXXXX
            else -> "+$cleaned"  // Add + prefix
        }
    }

    /**
     * Legacy format function for API calls (with + prefix)
     */
    private fun formatPhoneNumberForSending(phone: String): String {
        val cleaned = phone.replace(Regex("[^\\d+]"), "")
        return when {
            cleaned.startsWith("+") -> cleaned
            cleaned.length == 10 -> "+1$cleaned"
            cleaned.length == 11 && cleaned.startsWith("1") -> "+$cleaned"
            else -> "+$cleaned"
        }
    }

    /**
     * Copy content URI to temp file for upload
     */
    private fun copyUriToTempFile(uriString: String, fileName: String): File {
        val uri = Uri.parse(uriString)
        val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}_$fileName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }

    override suspend fun deleteMessage(messageId: String) {
        // Get the message first to find its conversation
        val message = messageDao.getMessageById(messageId)
        val conversationId = message?.conversationId

        try {
            val msgId = messageId.toIntOrNull()
            if (msgId != null) {
                apiService.deleteMessages(DeleteMessagesRequest(messageIds = listOf(msgId)))
                Log.d(TAG, "deleteMessage: Deleted message from server")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessage: Failed to delete from server", e)
        }
        messageDao.deleteMessageById(messageId)

        // Update the conversation preview to show the new last message
        if (!conversationId.isNullOrEmpty()) {
            updateConversationPreviewAfterDelete(conversationId)
        }
    }

    /**
     * Update conversation preview after a message is deleted.
     *
     * ARCHITECTURE: All conversation IDs in the database use normalized 10-digit format.
     * Messages and conversations share the same ID format, enabling simple single lookups.
     */
    private suspend fun updateConversationPreviewAfterDelete(conversationId: String) {
        try {
            // Get the latest remaining message for this conversation
            val latestMessage = messageDao.getLatestMessageForConversation(conversationId)

            // Get conversation and update its preview
            val conversation = messageDao.getConversationById(conversationId) ?: return

            val updatedConversation = conversation.copy(
                lastMessageBody = latestMessage?.body ?: "",
                lastMessageTime = latestMessage?.createdAt ?: conversation.createdAt,
                updatedAt = System.currentTimeMillis()
            )
            messageDao.updateConversation(updatedConversation)

            Log.d(TAG, "updateConversationPreviewAfterDelete: Updated preview for $conversationId")
        } catch (e: Exception) {
            Log.e(TAG, "updateConversationPreviewAfterDelete: Failed", e)
        }
    }

    override suspend fun deleteConversation(conversationId: String) {
        val userNumber = getNormalized10DigitNumber()
        val normalizedTarget = normalizeConversationId(conversationId)

        Log.d(TAG, "deleteConversation: Deleting conversation with $normalizedTarget (original: $conversationId)")

        // Step 1: Call API to delete from server
        try {
            if (userNumber.isNotEmpty()) {
                val response = apiService.deleteConversation(
                    DeleteConversationRequest(
                        myPhoneNumber = userNumber,
                        targetNumber = normalizedTarget
                    )
                )

                if (response.isSuccessful && response.body()?.success == true) {
                    Log.d(TAG, "deleteConversation: Successfully deleted from server")
                } else {
                    Log.w(TAG, "deleteConversation: Server returned ${response.code()}: ${response.body()?.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteConversation: Failed to delete from server", e)
        }

        // Step 2: Delete locally with all possible phone number formats
        val formatsToDelete = listOf(
            conversationId,
            normalizedTarget,
            "1$normalizedTarget",
            "+1$normalizedTarget"
        ).distinct()

        formatsToDelete.forEach { format ->
            try {
                messageDao.deleteMessagesForConversation(format)
                messageDao.deleteConversationById(format)
                Log.d(TAG, "deleteConversation: Deleted local data for format: $format")
            } catch (e: Exception) {
                // Ignore - might not exist with this format
            }
        }

        Log.d(TAG, "deleteConversation: Completed deletion of conversation $conversationId")
    }

    /**
     * Delete conversation on server and locally.
     * Matches web's /api/delete-conversation endpoint.
     */
    override suspend fun deleteConversation(myPhoneNumber: String, targetNumber: String): Boolean {
        return try {
            // Normalize numbers for API call
            val normalizedTarget = normalizeConversationId(targetNumber)
            val normalizedMy = getNormalized10DigitNumber(myPhoneNumber)

            Log.d(TAG, "deleteConversation: Deleting conversation with $normalizedTarget")

            val response = apiService.deleteConversation(
                DeleteConversationRequest(
                    myPhoneNumber = normalizedMy,
                    targetNumber = normalizedTarget
                )
            )

            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "deleteConversation: Successfully deleted from server")

                // Delete locally - try multiple formats to ensure cleanup
                val formatsToDelete = listOf(
                    targetNumber,
                    normalizedTarget,
                    "1$normalizedTarget",
                    "+1$normalizedTarget"
                )

                formatsToDelete.forEach { format ->
                    try {
                        messageDao.deleteMessagesForConversation(format)
                        messageDao.deleteConversationById(format)
                    } catch (e: Exception) {
                        // Ignore - might not exist with this format
                    }
                }

                true
            } else {
                Log.e(TAG, "deleteConversation: Server returned error: ${response.body()?.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteConversation: Failed to delete conversation", e)
            false
        }
    }

    override suspend fun markConversationAsRead(conversationId: String) {
        messageDao.markConversationAsRead(conversationId)
        messageDao.clearUnreadCount(conversationId)

        // Get raw phone number as stored (might include +1)
        val rawUserNumber = getUserPhoneNumber()
        // Also get normalized 10-digit format for comparison
        val normalized10Digit = getNormalized10DigitNumber()

        Log.d(TAG, "markConversationAsRead: userNumber=$normalized10Digit, conversationId=$conversationId")

        // Use the same normalization as web version's globalSMSService
        // The web normalizes both userNumber and contactId to 10 digits
        val normalizedContactId = conversationId.replace(Regex("[^\\d]"), "").let { digits ->
            when {
                digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
                digits.length == 10 -> digits
                else -> digits
            }
        }

        try {
            if (normalized10Digit.isNotEmpty()) {
                Log.d(TAG, "markConversationAsRead: Sending to API userNumber=$normalized10Digit, contactId=$normalizedContactId")
                val response = apiService.markSmsAsRead(
                    MarkSmsReadRequest(
                        userNumber = normalized10Digit,
                        contactId = normalizedContactId
                    )
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    Log.d(TAG, "markConversationAsRead: Response success=${body?.success}, markedAsRead=${body?.markedAsRead}")
                    if (body?.success == true) {
                        if (body.markedAsRead == 0) {
                            // Try with raw format as fallback
                            Log.d(TAG, "markConversationAsRead: markedAsRead=0, trying with raw format")
                            val response2 = apiService.markSmsAsRead(
                                MarkSmsReadRequest(
                                    userNumber = rawUserNumber,
                                    contactId = conversationId
                                )
                            )
                            val body2 = response2.body()
                            Log.d(TAG, "markConversationAsRead: Retry response markedAsRead=${body2?.markedAsRead}")
                        } else {
                            Log.d(TAG, "markConversationAsRead: Success - markedAsRead=${body.markedAsRead}")
                        }
                    } else {
                        Log.w(TAG, "markConversationAsRead: API returned success=false, message=${body?.message}")
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "markConversationAsRead: API error ${response.code()} - $errorBody")
                }
            } else {
                Log.w(TAG, "markConversationAsRead: userNumber is empty, skipping server sync")
            }
        } catch (e: Exception) {
            Log.e(TAG, "markConversationAsRead: Failed to mark as read on server", e)
        }
    }

    override fun getTotalUnreadCount(): Flow<Int> {
        return messageDao.getTotalUnreadCount().map { it ?: 0 }
    }

    override suspend fun getTotalUnreadCountDirect(): Int {
        val count = messageDao.getTotalUnreadCountDirect() ?: 0
        Log.d(TAG, "Direct unread message count: $count")
        return count
    }

    override suspend fun searchMessages(query: String): List<Message> {
        return messageDao.searchMessages(query).map { it.toDomain() }
    }

    override suspend fun syncMessages() {
        val owner = getUserPhoneNumber()
        if (owner.isEmpty()) {
            Log.w(TAG, "syncMessages: No user phone number available, skipping sync")
            return
        }

        Log.d(TAG, "syncMessages: Starting sync for user")

        try {
            // First, fetch actual unread counts from server to get accurate badges
            val unreadCountsMap = mutableMapOf<String, Int>()
            try {
                val unreadResponse = apiService.getUnreadSmsCounts(UnreadSmsCountsRequest(userNumber = owner))
                if (unreadResponse.isSuccessful && unreadResponse.body()?.success == true) {
                    // Convert list of UnreadCountItem to map for quick lookup
                    unreadResponse.body()?.unreadCounts?.forEach { item ->
                        val contactId = item.contactId
                        val count = item.count ?: 0
                        if (!contactId.isNullOrEmpty() && count > 0) {
                            // Store both original and normalized versions for matching
                            unreadCountsMap[contactId] = count
                            val normalized = getNormalized10DigitNumber(contactId)
                            if (normalized != contactId) {
                                unreadCountsMap[normalized] = count
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch unread counts", e)
            }

            // Use /api/sms-contacts/all with request body containing user's phone number
            val request = SmsContactsRequest(
                number = owner,
                limit = 100,
                offset = 0
            )
            val contactsResponse = apiService.getAllSmsContacts(request)
            if (contactsResponse.isSuccessful && contactsResponse.body() != null) {
                val apiResponse = contactsResponse.body() ?: return
                val contactsList = apiResponse.conversations ?: apiResponse.data ?: emptyList()

                Log.d(TAG, "syncMessages: Got ${contactsList.size} SMS contacts from API")

                // First, quickly save all conversation metadata so UI shows list immediately
                val conversations = contactsList.mapNotNull { contactItem ->
                    val contact = contactItem.contact ?: contactItem.contactNumber ?: return@mapNotNull null

                    // Use data from API response if available
                    val lastMessage = contactItem.lastMessage ?: contactItem.body
                    val lastTimestamp = contactItem.lastTimestamp?.let { parseDateTime(it) }
                        ?: contactItem.txRxDatetime?.let { parseDateTime(it) }
                        ?: System.currentTimeMillis()
                    // Use actual unread count from server API, normalize contact number for lookup
                    // SSOT: Always use normalized 10-digit ID to match message conversationId format
                    val normalizedContact = getNormalized10DigitNumber(contact)
                    val unreadFromNormalized = unreadCountsMap[normalizedContact]
                    val unreadFromOriginal = unreadCountsMap[contact]
                    val unreadFromItem = contactItem.unreadCount
                    val unreadCount = unreadFromNormalized ?: unreadFromOriginal ?: unreadFromItem ?: 0

                    ConversationEntity(
                        id = normalizedContact,  // SSOT: Use normalized ID to match message conversationId
                        phoneNumber = contact,   // Keep original phone number for display
                        contactName = contactItem.name,
                        contactId = null,
                        lastMessageId = null,
                        lastMessageBody = lastMessage,
                        lastMessageTime = lastTimestamp,
                        unreadCount = unreadCount,
                        isArchived = false,
                        isMuted = false,
                        updatedAt = lastTimestamp,
                        syncedAt = System.currentTimeMillis()
                    )
                }

                // Batch insert all conversations at once for faster UI update
                if (conversations.isNotEmpty()) {
                    messageDao.insertConversations(conversations)
                    Log.d(TAG, "syncMessages: Saved ${conversations.size} conversations to DB")
                }

                // Now fetch all messages in PARALLEL (much faster than sequential!)
                val contactIds = conversations.map { it.id }
                Log.d(TAG, "syncMessages: Fetching messages for ${contactIds.size} conversations in parallel...")

                val startTime = System.currentTimeMillis()

                // Use coroutineScope to run all message fetches in parallel
                coroutineScope {
                    // Process in batches of 10 to avoid overwhelming the server
                    contactIds.chunked(10).forEach { batch ->
                        val deferredResults = batch.map { contactId ->
                            async(Dispatchers.IO) {
                                try {
                                    syncConversationMessagesAsync(contactId)
                                } catch (e: Exception) {
                                    Log.e(TAG, "syncMessages: Error syncing $contactId", e)
                                    null
                                }
                            }
                        }
                        // Wait for this batch to complete
                        deferredResults.awaitAll()
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "syncMessages: Synced ${contactIds.size} conversations in ${elapsed}ms (parallel)")
            } else {
                Log.e(TAG, "syncMessages: API error - ${contactsResponse.code()} ${contactsResponse.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncMessages: Error syncing messages", e)
            throw e
        }
    }

    /**
     * Async version of syncConversationMessages for parallel execution
     * Returns the number of messages synced
     */
    private suspend fun syncConversationMessagesAsync(conversationId: String): Int {
        val owner = getUserPhoneNumber()
        if (owner.isEmpty()) return 0

        // Normalize conversationId to prevent duplicates from different phone formats
        val normalizedConvId = normalizeConversationId(conversationId)

        try {
            val request = SmsHistoryRequest(
                number = owner,
                contact = conversationId,  // Use original for API request
                limit = 100,  // Increased from 50 for faster initial load
                offset = 0
            )
            val response = apiService.getSmsHistory(request)
            if (response.isSuccessful && response.body() != null) {
                val apiResponse = response.body() ?: return 0
                val messageList = apiResponse.data ?: apiResponse.messages ?: emptyList()

                val messages = messageList.mapNotNull { dto ->
                    val msgId = dto.id?.toString() ?: return@mapNotNull null
                    val fromNum = dto.sender ?: ""
                    val toNum = dto.receiver ?: ""
                    val bodyText = dto.message ?: dto.body ?: ""
                    val createdAtTime = dto.txRxDatetime?.let { parseDateTime(it) } ?: dto.createdAt?.let { parseDateTime(it) } ?: dto.timestamp?.let { parseDateTime(it) } ?: System.currentTimeMillis()

                    MessageEntity(
                        id = msgId,
                        conversationId = normalizedConvId,  // Use normalized for storage
                        fromNumber = fromNum,
                        toNumber = toNum,
                        body = bodyText,
                        direction = if (dto.direction == "inbound") "INCOMING" else "OUTGOING",
                        status = when (dto.status) {
                            "pending" -> "PENDING"
                            "sent" -> "SENT"
                            "delivered" -> "DELIVERED"
                            "failed" -> "FAILED"
                            else -> "RECEIVED"
                        },
                        isRead = (dto.read ?: 1) == 1,
                        mediaUrls = dto.mediaUrl?.let { listOf(it) } ?: emptyList(),
                        mediaType = null,
                        errorMessage = null,
                        sentAt = dto.timestamp?.let { parseDateTime(it) },
                        deliveredAt = null,
                        createdAt = createdAtTime,
                        syncedAt = System.currentTimeMillis(),
                        contentSignature = MessageEntity.generateContentSignature(fromNum, toNum, bodyText, createdAtTime)
                    )
                }

                if (messages.isNotEmpty()) {
                    Log.e(TAG, "📥 API returned ${messages.size} messages for $conversationId")

                    // Log first few messages for debugging
                    messages.take(3).forEach { msg ->
                        Log.e(TAG, "📥 Sample msg: id=${msg.id}, from=${msg.fromNumber}, to=${msg.toNumber}, body=${msg.body.take(30)}, createdAt=${msg.createdAt}")
                    }

                    // Database UNIQUE constraint on contentSignature handles deduplication
                    // INSERT OR IGNORE means duplicates are silently skipped
                    messageDao.insertMessages(messages)

                    // Check how many are actually in DB after insert
                    val dbCount = messageDao.getMessageCount(normalizedConvId)
                    Log.e(TAG, "📥 After insert: DB has $dbCount messages for $normalizedConvId (API sent ${messages.size})")
                }
                return messages.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncConversationMessagesAsync: Error syncing $conversationId", e)
        }
        return 0
    }

    override suspend fun syncMessagesForConversation(conversationId: String) {
        syncConversationMessages(conversationId)
    }

    /**
     * Load more messages for pagination
     * Fetches from API with offset and saves to local DB
     * Returns true if more messages are available
     */
    override suspend fun loadMoreMessages(
        conversationId: String,
        offset: Int,
        limit: Int
    ): Boolean {
        val owner = getUserPhoneNumber()
        if (owner.isEmpty()) return false

        // Normalize conversationId to prevent duplicates
        val normalizedConvId = normalizeConversationId(conversationId)

        try {
            Log.d(TAG, "loadMoreMessages: Loading offset=$offset, limit=$limit for $conversationId")

            val request = SmsHistoryRequest(
                number = owner,
                contact = conversationId,  // Use original for API request
                limit = limit,
                offset = offset
            )
            val response = apiService.getSmsHistory(request)

            if (response.isSuccessful && response.body() != null) {
                val apiResponse = response.body() ?: return false
                val messageList = apiResponse.data ?: apiResponse.messages ?: emptyList()

                Log.d(TAG, "loadMoreMessages: Got ${messageList.size} messages")

                if (messageList.isEmpty()) {
                    return false // No more messages
                }

                val messages = messageList.mapNotNull { dto ->
                    val msgId = dto.id?.toString() ?: return@mapNotNull null
                    val fromNum = dto.sender ?: ""
                    val toNum = dto.receiver ?: ""
                    val bodyText = dto.message ?: dto.body ?: ""
                    val createdAtTime = dto.txRxDatetime?.let { parseDateTime(it) } ?: dto.createdAt?.let { parseDateTime(it) } ?: dto.timestamp?.let { parseDateTime(it) } ?: System.currentTimeMillis()

                    MessageEntity(
                        id = msgId,
                        conversationId = normalizedConvId,  // Use normalized for storage
                        fromNumber = fromNum,
                        toNumber = toNum,
                        body = bodyText,
                        direction = if (dto.direction == "inbound") "INCOMING" else "OUTGOING",
                        status = when (dto.status) {
                            "pending" -> "PENDING"
                            "sent" -> "SENT"
                            "delivered" -> "DELIVERED"
                            "failed" -> "FAILED"
                            else -> "RECEIVED"
                        },
                        isRead = (dto.read ?: 1) == 1,
                        mediaUrls = dto.mediaUrl?.let { listOf(it) } ?: emptyList(),
                        mediaType = null,
                        errorMessage = null,
                        sentAt = dto.timestamp?.let { parseDateTime(it) },
                        deliveredAt = null,
                        createdAt = createdAtTime,
                        syncedAt = System.currentTimeMillis(),
                        contentSignature = MessageEntity.generateContentSignature(fromNum, toNum, bodyText, createdAtTime)
                    )
                }

                if (messages.isNotEmpty()) {
                    // Database UNIQUE constraint handles deduplication automatically
                    messageDao.insertMessages(messages)
                    Log.d(TAG, "loadMoreMessages: Saved ${messages.size} messages (duplicates auto-ignored)")
                }

                // Return true if we got a full page (more might be available)
                return messages.size >= limit
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadMoreMessages: Error loading more messages", e)
        }
        return false
    }

    /**
     * Data class to hold result of syncing conversation messages
     */
    private data class ConversationSyncResult(
        val lastMessage: String?,
        val lastTimestamp: Long,
        val unreadCount: Int
    )

    /**
     * Sync messages for a conversation and return the latest message info
     */
    private suspend fun syncConversationMessagesAndGetLatest(conversationId: String): ConversationSyncResult {
        val owner = getUserPhoneNumber()
        if (owner.isEmpty()) {
            return ConversationSyncResult(null, System.currentTimeMillis(), 0)
        }

        // Normalize conversationId to prevent duplicates
        val normalizedConvId = normalizeConversationId(conversationId)

        try {
            val request = SmsHistoryRequest(
                number = owner,
                contact = conversationId,  // Use original for API request
                limit = 50,
                offset = 0
            )
            val response = apiService.getSmsHistory(request)
            if (response.isSuccessful && response.body() != null) {
                val apiResponse = response.body()
                    ?: return ConversationSyncResult(null, System.currentTimeMillis(), 0)
                val messageList = apiResponse.data ?: apiResponse.messages ?: emptyList()

                Log.d(TAG, "syncConversationMessagesAndGetLatest: Got ${messageList.size} messages for $conversationId")

                var lastMessage: String? = null
                var lastTimestamp = System.currentTimeMillis()
                var unreadCount = 0

                val messages = messageList.mapNotNull { dto ->
                    val msgId = dto.id?.toString() ?: return@mapNotNull null
                    val fromNum = dto.sender ?: ""
                    val toNum = dto.receiver ?: ""
                    val bodyText = dto.message ?: dto.body ?: ""
                    val createdAtTime = dto.txRxDatetime?.let { parseDateTime(it) } ?: dto.createdAt?.let { parseDateTime(it) } ?: dto.timestamp?.let { parseDateTime(it) } ?: System.currentTimeMillis()

                    // Track unread incoming messages
                    if (dto.direction == "inbound" && (dto.read ?: 1) == 0) {
                        unreadCount++
                    }

                    MessageEntity(
                        id = msgId,
                        conversationId = normalizedConvId,  // Use normalized for storage
                        fromNumber = fromNum,
                        toNumber = toNum,
                        body = bodyText,
                        direction = if (dto.direction == "inbound") "INCOMING" else "OUTGOING",
                        status = when (dto.status) {
                            "pending" -> "PENDING"
                            "sent" -> "SENT"
                            "delivered" -> "DELIVERED"
                            "failed" -> "FAILED"
                            else -> "RECEIVED"
                        },
                        isRead = (dto.read ?: 1) == 1,
                        mediaUrls = dto.mediaUrl?.let { listOf(it) } ?: emptyList(),
                        mediaType = null,
                        errorMessage = null,
                        sentAt = dto.timestamp?.let { parseDateTime(it) },
                        deliveredAt = null,
                        createdAt = createdAtTime,
                        syncedAt = System.currentTimeMillis(),
                        contentSignature = MessageEntity.generateContentSignature(fromNum, toNum, bodyText, createdAtTime)
                    )
                }

                // Get the latest message (first in the sorted list)
                if (messages.isNotEmpty()) {
                    val latestMsg = messages.maxByOrNull { it.createdAt }
                    lastMessage = latestMsg?.body
                    lastTimestamp = latestMsg?.createdAt ?: System.currentTimeMillis()

                    // Database UNIQUE constraint handles deduplication automatically
                    messageDao.insertMessages(messages)
                    Log.d(TAG, "syncConversationMessagesAndGetLatest: Saved ${messages.size} messages for $conversationId")
                }

                return ConversationSyncResult(lastMessage, lastTimestamp, unreadCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncConversationMessagesAndGetLatest: Error syncing conversation $conversationId", e)
        }

        return ConversationSyncResult(null, System.currentTimeMillis(), 0)
    }

    private suspend fun syncConversationMessages(conversationId: String) {
        val owner = getUserPhoneNumber()
        if (owner.isEmpty()) return

        // Normalize conversationId to prevent duplicates
        val normalizedConvId = normalizeConversationId(conversationId)

        try {
            val request = SmsHistoryRequest(
                number = owner,
                contact = conversationId,  // Use original for API request
                limit = 50,
                offset = 0
            )
            val response = apiService.getSmsHistory(request)
            if (response.isSuccessful && response.body() != null) {
                val apiResponse = response.body() ?: return
                val messageList = apiResponse.data ?: apiResponse.messages ?: emptyList()

                Log.d(TAG, "syncConversationMessages: Got ${messageList.size} messages for $conversationId")

                val messages = messageList.mapNotNull { dto ->
                    val msgId = dto.id?.toString() ?: return@mapNotNull null
                    val fromNum = dto.sender ?: ""
                    val toNum = dto.receiver ?: ""
                    val bodyText = dto.message ?: dto.body ?: ""
                    val createdAtTime = dto.txRxDatetime?.let { parseDateTime(it) } ?: dto.createdAt?.let { parseDateTime(it) } ?: dto.timestamp?.let { parseDateTime(it) } ?: System.currentTimeMillis()

                    MessageEntity(
                        id = msgId,
                        conversationId = normalizedConvId,  // Use normalized for storage
                        fromNumber = fromNum,
                        toNumber = toNum,
                        body = bodyText,
                        direction = if (dto.direction == "inbound") "INCOMING" else "OUTGOING",
                        status = when (dto.status) {
                            "pending" -> "PENDING"
                            "sent" -> "SENT"
                            "delivered" -> "DELIVERED"
                            "failed" -> "FAILED"
                            else -> "RECEIVED"
                        },
                        isRead = (dto.read ?: 1) == 1,
                        mediaUrls = dto.mediaUrl?.let { listOf(it) } ?: emptyList(),
                        mediaType = null,
                        errorMessage = null,
                        sentAt = dto.timestamp?.let { parseDateTime(it) },
                        deliveredAt = null,
                        createdAt = createdAtTime,
                        syncedAt = System.currentTimeMillis(),
                        contentSignature = MessageEntity.generateContentSignature(fromNum, toNum, bodyText, createdAtTime)
                    )
                }

                if (messages.isNotEmpty()) {
                    // Database UNIQUE constraint handles deduplication automatically
                    messageDao.insertMessages(messages)
                    Log.d(TAG, "syncConversationMessages: Saved ${messages.size} messages for $conversationId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncConversationMessages: Error syncing conversation $conversationId", e)
            // Ignore individual conversation sync failures
        }
    }

    /**
     * Update or create a conversation with the latest message preview.
     *
     * ARCHITECTURE: Caller must pass normalized 10-digit conversationId.
     * This maintains consistency with message.conversationId format.
     */
    private suspend fun updateConversation(
        conversationId: String,
        phoneNumber: String,
        lastMessage: String,
        timestamp: Long
    ) {
        val existing = messageDao.getConversationById(conversationId)

        if (existing != null) {
            messageDao.updateConversation(
                existing.copy(
                    lastMessageBody = lastMessage,
                    lastMessageTime = timestamp,
                    updatedAt = timestamp
                )
            )
        } else {
            messageDao.insertConversation(
                ConversationEntity(
                    id = conversationId,
                    phoneNumber = phoneNumber,
                    contactName = null,
                    contactId = null,
                    lastMessageId = null,
                    lastMessageBody = lastMessage,
                    lastMessageTime = timestamp,
                    unreadCount = 0,
                    isArchived = false,
                    isMuted = false,
                    updatedAt = timestamp,
                    syncedAt = null
                )
            )
        }
    }

    private fun parseDateTime(dateString: String): Long {
        return DateTimeUtils.parseUtcTimestamp(dateString)
    }

    // ==================== Favorite Chat Operations ====================

    override suspend fun toggleFavoriteChat(ownerPhone: String, contactPhone: String): Result<Boolean> {
        return try {
            val request = com.bitnextechnologies.bitnexdial.data.remote.dto.ToggleFavoriteChatRequest(
                myPhoneNumber = ownerPhone,
                contactNumber = contactPhone
            )
            val response = apiService.toggleFavoriteChat(request)

            if (response.isSuccessful && response.body()?.success == true) {
                val isFavorite = response.body()?.isFavorite ?: false
                Log.d(TAG, "toggleFavoriteChat: Success, isFavorite=$isFavorite")
                Result.success(isFavorite)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "toggleFavoriteChat: API failed - code=${response.code()}, error=$errorBody")
                Result.failure(Exception("Failed to toggle favorite: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleFavoriteChat: Error", e)
            Result.failure(e)
        }
    }

    override suspend fun getFavoriteChats(ownerPhone: String): List<String> {
        return try {
            val response = apiService.getFavoriteChats(ownerPhone)
            if (response.isSuccessful && response.body()?.success == true) {
                val favorites = response.body()?.favorites?.map { favoriteItem ->
                    getNormalized10DigitNumber(favoriteItem.contact)
                } ?: emptyList()
                Log.d(TAG, "getFavoriteChats: Got ${favorites.size} favorites")
                favorites
            } else {
                Log.w(TAG, "getFavoriteChats: API failed - code=${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFavoriteChats: Error", e)
            emptyList()
        }
    }
}
