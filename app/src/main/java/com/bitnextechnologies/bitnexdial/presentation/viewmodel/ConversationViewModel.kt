package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketEvent
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import com.bitnextechnologies.bitnexdial.domain.model.Conversation
import com.bitnextechnologies.bitnexdial.domain.model.Message
import com.bitnextechnologies.bitnexdial.domain.repository.IAuthRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IMessageRepository
import com.bitnextechnologies.bitnexdial.data.repository.ContactRepository
import com.bitnextechnologies.bitnexdial.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "ConversationViewModel"

@HiltViewModel
class ConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepository: IMessageRepository,
    private val authRepository: IAuthRepository,
    private val socketManager: SocketManager,
    private val secureCredentialManager: SecureCredentialManager,
    private val contactRepository: ContactRepository
) : ViewModel() {

    private var conversationId: String = ""

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // Pagination state
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(true)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private var currentOffset = 0
    private val pageSize = 50

    // File attachment state - matches web's selectedFile, isUploading, uploadProgress
    private val _selectedFile = MutableStateFlow<SelectedFile?>(null)
    val selectedFile: StateFlow<SelectedFile?> = _selectedFile.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0)
    val uploadProgress: StateFlow<Int> = _uploadProgress.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    /**
     * Data class for selected file info
     */
    data class SelectedFile(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val isImage: Boolean
    )

    fun loadConversation(id: String, contactName: String? = null) {
        Log.e(TAG, "🔥 loadConversation called with id=$id, contactName=$contactName")

        // First, immediately set up a minimal conversation so user can start typing
        val normalizedId = id.replace(Regex("[^\\d]"), "").let { digits ->
            if (digits.length == 11 && digits.startsWith("1")) digits.substring(1)
            else if (digits.length >= 10) digits.takeLast(10)
            else digits
        }
        conversationId = normalizedId

        // Use ContactRepository (single source of truth) to get contact name
        val resolvedContactName = contactName ?: contactRepository.getContactName(id)
        Log.e(TAG, "🔥 Resolved contact name: $resolvedContactName (original: $contactName)")

        viewModelScope.launch {
            try {
                // Use getOrCreateConversation to handle both existing and new chats
                // This will find an existing conversation or create a new one
                val conversation = messageRepository.getOrCreateConversation(id, resolvedContactName)

                // If conversation has no name but we resolved one, update it
                val finalConversation = if (conversation.contactName.isNullOrBlank() && !resolvedContactName.isNullOrBlank()) {
                    conversation.copy(contactName = resolvedContactName)
                } else {
                    conversation
                }

                _conversation.value = finalConversation
                Log.e(TAG, "🔥 Conversation loaded: ${finalConversation.id}, phoneNumber=${finalConversation.phoneNumber}, name=${finalConversation.contactName}")

                // Use the actual conversation ID (might be different from normalizedId)
                conversationId = conversation.id

                // Reset pagination state
                currentOffset = 0
                _hasMoreMessages.value = true

                // NOW load messages using the ACTUAL conversation ID from database
                // This is critical - the stored conversation ID may differ from our calculated normalizedId
                Log.e(TAG, "🔥 Starting message collection for conversationId=${conversation.id}")
                loadMessagesForConversation(conversation.id)

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Don't catch CancellationException - rethrow to allow proper cancellation
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading conversation", e)
                // Even on error, create a minimal conversation for new chat
                _conversation.value = com.bitnextechnologies.bitnexdial.domain.model.Conversation(
                    id = normalizedId,
                    phoneNumber = id,
                    contactName = contactName,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                // Still try to load messages with normalized ID for new chats
                loadMessagesForConversation(normalizedId)
            }
        }

        // Mark conversation as read (only if it exists)
        viewModelScope.launch {
            try {
                messageRepository.markConversationAsRead(id)
            } catch (e: Exception) {
                Log.e(TAG, "Error marking conversation as read", e)
            }
        }

        // Join socket room for this conversation
        socketManager.joinRoom(id)

        // Observe socket events for this conversation
        observeSocketEvents()
    }

    /**
     * Load messages for a specific conversation ID
     * Separated to ensure we use the correct conversation ID from database
     */
    private fun loadMessagesForConversation(convId: String) {
        viewModelScope.launch {
            try {
                Log.e(TAG, "🔥 Collecting messages for convId=$convId")
                messageRepository.getMessagesForConversation(convId)
                    .collect { messageList ->
                        Log.e(TAG, "🔥 Received ${messageList.size} messages for convId=$convId")
                        _messages.value = messageList
                        currentOffset = messageList.size
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading messages for convId=$convId", e)
            }
        }
    }

    /**
     * Load more messages for pagination (triggered by scroll to top)
     */
    fun loadMoreMessages() {
        if (_isLoadingMore.value || !_hasMoreMessages.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val hasMore = messageRepository.loadMoreMessages(
                    conversationId = conversationId,
                    offset = currentOffset,
                    limit = pageSize
                )
                _hasMoreMessages.value = hasMore
                if (hasMore) {
                    currentOffset += pageSize
                }
                Log.d(TAG, "loadMoreMessages: offset=$currentOffset, hasMore=$hasMore")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more messages", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Listen to Socket.IO events for real-time message updates.
     * This is the professional event-driven approach matching the web version.
     */
    private fun observeSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.NewMessage -> {
                        if (event.conversationId == conversationId ||
                            event.fromNumber == _conversation.value?.phoneNumber) {
                            Log.d(TAG, "New message in this conversation: ${event.messageId}")
                            // Sync to get the new message
                            syncConversation()
                        }
                    }
                    is SocketEvent.NewSms -> {
                        // Check if this SMS is for our current conversation
                        val conversationPhone = _conversation.value?.phoneNumber?.replace(Regex("[^\\d]"), "")?.removePrefix("1")
                        val fromNumber = event.from.replace(Regex("[^\\d]"), "").removePrefix("1")
                        val toNumber = event.to.replace(Regex("[^\\d]"), "").removePrefix("1")

                        if (conversationPhone == fromNumber || conversationPhone == toNumber) {
                            Log.d(TAG, "New SMS in this conversation from: ${event.from}")
                            // Sync to get the new message
                            syncConversation()
                        }
                    }
                    is SocketEvent.MessageDelivered, is SocketEvent.MessageRead -> {
                        // Refresh to update message status
                        syncConversation()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun syncConversation() {
        viewModelScope.launch {
            try {
                messageRepository.syncMessagesForConversation(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing messages", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Leave socket room when leaving conversation
        if (conversationId.isNotEmpty()) {
            socketManager.leaveRoom(conversationId)
        }
    }

    fun setMessageText(text: String) {
        _messageText.value = text
    }

    /**
     * Set selected file for attachment - matches web's setSelectedFile
     */
    fun setSelectedFile(uri: Uri?) {
        if (uri == null) {
            _selectedFile.value = null
            return
        }

        try {
            // Get file info from URI
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = getFileNameFromUri(uri)
            val isImage = mimeType.startsWith("image/")

            _selectedFile.value = SelectedFile(
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                isImage = isImage
            )
            Log.d(TAG, "File selected: $fileName (type: $mimeType)")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing selected file", e)
            _selectedFile.value = null
        }
    }

    /**
     * Clear selected file - matches web's setSelectedFile(null)
     */
    fun clearSelectedFile() {
        _selectedFile.value = null
    }

    /**
     * Get file name from content URI
     */
    private fun getFileNameFromUri(uri: Uri): String {
        var name = "file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    /**
     * Delete a message
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.deleteMessage(messageId)
                Log.d(TAG, "Deleted message: $messageId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting message", e)
                _errorMessage.emit("Failed to delete message")
            }
        }
    }

    /**
     * Send message - handles both text and file attachments
     * Matches web's handleSendMessage and handleSendFile logic
     */
    fun sendMessage() {
        Log.e(TAG, "🔥 sendMessage() called")
        val text = _messageText.value.trim()
        val file = _selectedFile.value

        // Must have either text or file (like web version)
        if (text.isEmpty() && file == null) {
            Log.e(TAG, "🔥 sendMessage: No text or file, returning")
            return
        }

        val phoneNumber = _conversation.value?.phoneNumber
        if (phoneNumber == null) {
            Log.e(TAG, "🔥 sendMessage: No phone number in conversation, returning")
            return
        }
        Log.e(TAG, "🔥 sendMessage: phoneNumber=$phoneNumber, conversationId=$conversationId")

        viewModelScope.launch {
            _isSending.value = true
            if (file != null) {
                _isUploading.value = true
            }

            try {
                // Get user's phone number from SecureCredentialManager
                val fromNumber = secureCredentialManager.getSenderPhone()

                if (fromNumber.isNullOrEmpty()) {
                    Log.e(TAG, "No sender phone number configured")
                    _errorMessage.emit("No sender phone number configured")
                    return@launch
                }

                // Prevent texting own number
                if (com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils.areNumbersEqual(fromNumber, phoneNumber)) {
                    Log.w(TAG, "Cannot send message to own number")
                    _errorMessage.emit("You cannot send a message to your own number")
                    _isSending.value = false
                    _isUploading.value = false
                    return@launch
                }

                Log.d(TAG, "Sending message from $fromNumber to $phoneNumber")

                val result = if (file != null) {
                    // Send with media - matches web's handleSendFile
                    Log.d(TAG, "Sending MMS with file: ${file.fileName}")
                    messageRepository.sendMessageWithMedia(
                        conversationId = conversationId,
                        toNumber = phoneNumber,
                        fromNumber = fromNumber,
                        body = text,
                        fileUri = file.uri.toString(),
                        mimeType = file.mimeType,
                        fileName = file.fileName
                    )
                } else {
                    // Send text only - matches web's handleSendMessage
                    messageRepository.sendMessage(
                        conversationId = conversationId,
                        toNumber = phoneNumber,
                        fromNumber = fromNumber,
                        body = text
                    )
                }

                if (result.status == com.bitnextechnologies.bitnexdial.domain.model.MessageStatus.FAILED) {
                    _errorMessage.emit("Failed to send message")
                } else {
                    // Clear inputs on success (like web version)
                    _messageText.value = ""
                    _selectedFile.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                _errorMessage.emit("Failed to send message: ${e.message}")
            } finally {
                _isSending.value = false
                _isUploading.value = false
                _uploadProgress.value = 0
            }
        }
    }

    // Delete conversation state
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _deleteSuccess = MutableSharedFlow<Boolean>()
    val deleteSuccess: SharedFlow<Boolean> = _deleteSuccess.asSharedFlow()

    /**
     * Delete the current conversation.
     * Calls API to delete on server and removes from local DB.
     */
    fun deleteConversation() {
        val phoneNumber = _conversation.value?.phoneNumber ?: return

        viewModelScope.launch {
            _isDeleting.value = true
            try {
                val fromNumber = secureCredentialManager.getSenderPhone()
                if (fromNumber.isNullOrEmpty()) {
                    _errorMessage.emit("No sender phone number configured")
                    return@launch
                }

                val success = messageRepository.deleteConversation(
                    myPhoneNumber = fromNumber,
                    targetNumber = phoneNumber
                )

                if (success) {
                    Log.d(TAG, "Successfully deleted conversation with $phoneNumber")
                    _deleteSuccess.emit(true)
                } else {
                    Log.e(TAG, "Failed to delete conversation with $phoneNumber")
                    _errorMessage.emit("Failed to delete conversation")
                    _deleteSuccess.emit(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting conversation", e)
                _errorMessage.emit("Failed to delete conversation: ${e.message}")
                _deleteSuccess.emit(false)
            } finally {
                _isDeleting.value = false
            }
        }
    }
}
