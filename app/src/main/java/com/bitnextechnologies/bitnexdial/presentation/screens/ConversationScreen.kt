package com.bitnextechnologies.bitnexdial.presentation.screens

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bitnextechnologies.bitnexdial.domain.model.Message
import com.bitnextechnologies.bitnexdial.domain.model.MessageDirection
import com.bitnextechnologies.bitnexdial.domain.model.MessageStatus
import com.bitnextechnologies.bitnexdial.presentation.viewmodel.ConversationViewModel
import com.bitnextechnologies.bitnexdial.util.DateTimeUtils
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    conversationId: String,
    contactName: String? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
    onCallClick: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val conversation by viewModel.conversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()

    val listState = rememberLazyListState()

    // Delete confirmation dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Fullscreen image viewer state
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }

    // Observe error messages
    LaunchedEffect(Unit) {
        viewModel.errorMessage.collectLatest { error ->
            snackbarHostState.showSnackbar(error)
        }
    }

    // Observe delete success and navigate back
    LaunchedEffect(Unit) {
        viewModel.deleteSuccess.collectLatest { success ->
            if (success) {
                onNavigateBack()
            }
        }
    }

    // File picker launcher - matches web's file input
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.setSelectedFile(uri)
    }

    // Image picker launcher for camera/gallery
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.setSelectedFile(uri)
    }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId, contactName)
    }

    // Reverse messages for reverseLayout - newest (index 0) will appear at bottom
    val displayMessages = remember(messages) { messages.asReversed() }

    // Auto-load more messages when scrolling to older messages (high indices in reversed list)
    LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisibleIndex >= displayMessages.size - 5 && hasMoreMessages && !isLoadingMore && displayMessages.isNotEmpty()) {
            viewModel.loadMoreMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Determine display name - use contact name if available, otherwise formatted phone
            val displayName = conversation?.contactName?.takeIf { it.isNotBlank() }
                ?: PhoneNumberUtils.formatForDisplay(conversation?.phoneNumber ?: "")
            val hasContactName = !conversation?.contactName.isNullOrBlank()

            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            val initials = if (hasContactName) {
                                val name = conversation?.contactName ?: ""
                                val parts = name.trim().split(" ").filter { it.isNotBlank() }
                                when {
                                    parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
                                    parts.size == 1 -> parts.first().take(2).uppercase()
                                    else -> "?"
                                }
                            } else {
                                PhoneNumberUtils.getInitialsFromNumber(conversation?.phoneNumber ?: "")
                            }
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Name/Number - single line, clean display
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { conversation?.phoneNumber?.let { onCallClick(it) } }) {
                        Icon(Icons.Default.Call, contentDescription = "Call")
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = !isDeleting
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete conversation")
                    }
                }
            )
        }
    ) { paddingValues ->

        // Delete confirmation dialog
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                title = { Text("Delete Conversation") },
                text = {
                    Text(
                        "Are you sure you want to delete this conversation with ${conversation?.contactName ?: PhoneNumberUtils.formatForDisplay(conversation?.phoneNumber ?: "")}? This action cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteConversation()
                        },
                        enabled = !isDeleting
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Fullscreen image viewer
        fullscreenImageUrl?.let { imageUrl ->
            FullscreenImageViewer(
                imageUrl = imageUrl,
                onDismiss = { fullscreenImageUrl = null }
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
        // Messages list with reverseLayout - starts from bottom like WhatsApp
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            reverseLayout = true, // Start from bottom - no scroll needed!
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Messages (reversed so newest is at index 0, which appears at bottom)
            items(
                items = displayMessages,
                key = { it.id }
            ) { message ->
                MessageBubble(
                    message = message,
                    onDelete = { viewModel.deleteMessage(message.id) },
                    onImageClick = { imageUrl -> fullscreenImageUrl = imageUrl }
                )
            }

            // Loading indicator (appears at top when scrolling up to older messages)
            if (isLoadingMore) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        // Selected file preview - matches web's file preview UI
        if (selectedFile != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Preview icon or thumbnail
                    if (selectedFile?.isImage == true) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(selectedFile?.uri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Selected image",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "File",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // File name
                    Text(
                        text = selectedFile?.fileName ?: "File",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )

                    // Remove button - matches web's IoClose
                    IconButton(onClick = { viewModel.clearSelectedFile() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove file",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Message input - enhanced with attachment buttons like web version
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Attachment button - matches web's FiPaperclip
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = !isSending && !isUploading
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // Camera/Image button - matches web's FiCamera
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !isSending && !isUploading
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Attach image",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // Text input
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { viewModel.setMessageText(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    enabled = !isSending && !isUploading
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Send button - matches web's IoSend
                val canSend = (messageText.isNotBlank() || selectedFile != null) &&
                              messageText.length <= 1600 &&
                              !isSending &&
                              !isUploading

                FloatingActionButton(
                    onClick = {
                        if (canSend) {
                            viewModel.sendMessage()
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    containerColor = if (canSend)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    shape = CircleShape
                ) {
                    if (isSending || isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
        } // End Column
    } // End Scaffold
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    onDelete: () -> Unit = {},
    onImageClick: (String) -> Unit = {}
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isSavingImage by remember { mutableStateOf(false) }

    // Permission state for storage (needed for API < 29)
    var pendingImageUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingImageUrls.isNotEmpty()) {
            scope.launch {
                saveImagesToGallery(context, pendingImageUrls) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
            pendingImageUrls = emptyList()
        } else if (!isGranted) {
            Toast.makeText(context, "Storage permission required to save images", Toast.LENGTH_SHORT).show()
        }
    }

    // Collect all image URLs - from mediaUrls AND from HTML in message body
    val allImageUrls = remember(message.mediaUrls, message.body) {
        val urls = mutableListOf<String>()
        // Add mediaUrls first
        urls.addAll(message.mediaUrls.filter { isImageUrl(it) })
        // Then extract from HTML in body
        urls.addAll(extractImageUrlsFromHtml(message.body))
        urls.distinct()
    }

    // Clean text content - remove HTML tags
    val cleanTextContent = remember(message.body) {
        stripHtmlTags(message.body)
    }

    val hasText = cleanTextContent.isNotBlank()
    val hasImages = allImageUrls.isNotEmpty()

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Message") },
            text = { Text("Are you sure you want to delete this message?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { /* Normal click does nothing */ },
                    onLongClick = {
                        // Haptic feedback on long press
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showContextMenu = true
                    }
                ),
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
        ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOutgoing) 16.dp else 4.dp,
                bottomEnd = if (isOutgoing) 4.dp else 16.dp
            ),
            color = if (isOutgoing)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Render images (from mediaUrls or extracted from HTML)
                if (allImageUrls.isNotEmpty()) {
                    allImageUrls.forEach { imageUrl ->
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Image attachment",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(imageUrl) },
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Message text (cleaned of HTML, if not empty)
                if (cleanTextContent.isNotBlank()) {
                    Text(
                        text = cleanTextContent,
                        color = if (isOutgoing)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Timestamp and status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = DateTimeUtils.formatTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOutgoing)
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        when (message.status) {
                            MessageStatus.SENDING -> {
                                // Show progress indicator while sending/uploading
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                    strokeWidth = 1.5.dp
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = when (message.status) {
                                        MessageStatus.PENDING -> Icons.Default.Schedule
                                        MessageStatus.SENT -> Icons.Default.Check
                                        MessageStatus.DELIVERED -> Icons.Default.DoneAll
                                        MessageStatus.FAILED -> Icons.Default.Error
                                        else -> Icons.Default.Check
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (message.status == MessageStatus.FAILED)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
        } // End Column

        // Context menu dropdown
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = DpOffset(
                x = if (isOutgoing) (-16).dp else 16.dp,
                y = 0.dp
            )
        ) {
            // Copy Text option - always show if there's text content
            if (hasText) {
                DropdownMenuItem(
                    text = { Text("Copy Text") },
                    onClick = {
                        copyTextToClipboard(context, cleanTextContent)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                )
            }

            // Share Text option
            if (hasText) {
                DropdownMenuItem(
                    text = { Text("Share Text") },
                    onClick = {
                        shareText(context, cleanTextContent)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Share, contentDescription = null)
                    }
                )
            }

            // Save Image option - with proper permission handling
            if (hasImages) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (allImageUrls.size > 1) "Save Images" else "Save Image")
                            if (isSavingImage) {
                                Spacer(modifier = Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    },
                    onClick = {
                        showContextMenu = false
                        // Check and request permission for API < 29
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!hasPermission) {
                                pendingImageUrls = allImageUrls
                                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                return@DropdownMenuItem
                            }
                        }
                        // Save images
                        isSavingImage = true
                        scope.launch {
                            saveImagesToGallery(context, allImageUrls) { success, msg ->
                                isSavingImage = false
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Download, contentDescription = null)
                    },
                    enabled = !isSavingImage
                )
            }

            // Share Image option
            if (hasImages && allImageUrls.size == 1) {
                DropdownMenuItem(
                    text = { Text("Share Image") },
                    onClick = {
                        showContextMenu = false
                        shareImageUrl(context, allImageUrls.first())
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Share, contentDescription = null)
                    }
                )
            }

            // Divider before delete
            HorizontalDivider()

            // Delete option
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showContextMenu = false
                    showDeleteConfirm = true
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    } // End Box
}

private const val TAG = "ConversationScreen"

/**
 * Copy text to clipboard with toast feedback
 */
private fun copyTextToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Message", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to copy text to clipboard", e)
        Toast.makeText(context, "Failed to copy text", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Share text via Android share sheet
 */
private fun shareText(context: Context, text: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share message"))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to share text", e)
        Toast.makeText(context, "Failed to share", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Share image URL via Android share sheet
 */
private fun shareImageUrl(context: Context, imageUrl: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, imageUrl)
        }
        context.startActivity(Intent.createChooser(intent, "Share image"))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to share image URL", e)
        Toast.makeText(context, "Failed to share", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Save multiple images to gallery with proper API handling
 * Uses MediaStore for Android 10+ (scoped storage)
 * Uses DownloadManager for older versions
 */
private suspend fun saveImagesToGallery(
    context: Context,
    imageUrls: List<String>,
    onComplete: (Boolean, String) -> Unit
) {
    var successCount = 0
    var failCount = 0

    withContext(Dispatchers.IO) {
        for (imageUrl in imageUrls) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ - Use MediaStore API (scoped storage)
                    saveImageWithMediaStore(context, imageUrl)
                    successCount++
                } else {
                    // Android 9 and below - Use DownloadManager
                    saveImageWithDownloadManager(context, imageUrl)
                    successCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image: $imageUrl", e)
                failCount++
            }
        }
    }

    // Report results on main thread
    withContext(Dispatchers.Main) {
        val message = when {
            failCount == 0 && successCount == 1 -> "Image saved to gallery"
            failCount == 0 && successCount > 1 -> "$successCount images saved to gallery"
            successCount == 0 -> "Failed to save image${if (imageUrls.size > 1) "s" else ""}"
            else -> "$successCount saved, $failCount failed"
        }
        onComplete(failCount == 0, message)
    }
}

/**
 * Save image using MediaStore API (Android 10+)
 * Properly integrates with gallery and respects scoped storage
 */
private suspend fun saveImageWithMediaStore(context: Context, imageUrl: String) {
    withContext(Dispatchers.IO) {
        val fileName = "BitnexDial_${System.currentTimeMillis()}.jpg"

        // Download image to byte array
        val url = URL(imageUrl)
        val connection = url.openConnection()
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        val inputStream: InputStream = connection.getInputStream()
        val imageBytes = inputStream.readBytes()
        inputStream.close()

        // Create MediaStore entry
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BitnexDial")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Failed to create MediaStore entry")

        // Write image data
        resolver.openOutputStream(imageUri)?.use { outputStream ->
            outputStream.write(imageBytes)
        } ?: throw Exception("Failed to open output stream")

        // Mark as complete (no longer pending)
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(imageUri, contentValues, null, null)

        Log.d(TAG, "Image saved via MediaStore: $fileName")
    }
}

/**
 * Save image using DownloadManager (Android 9 and below)
 * Requires WRITE_EXTERNAL_STORAGE permission
 */
private fun saveImageWithDownloadManager(context: Context, imageUrl: String) {
    val fileName = "BitnexDial_${System.currentTimeMillis()}.jpg"

    val request = DownloadManager.Request(Uri.parse(imageUrl))
        .setTitle("Saving image...")
        .setDescription("Downloading from BitnexDial")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "BitnexDial/$fileName")
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    downloadManager.enqueue(request)

    Log.d(TAG, "Image download started via DownloadManager: $fileName")
}

/**
 * Check if URL is an image URL based on extension or content type hints
 */
private fun isImageUrl(url: String): Boolean {
    val lowerUrl = url.lowercase()
    return lowerUrl.endsWith(".jpg") ||
            lowerUrl.endsWith(".jpeg") ||
            lowerUrl.endsWith(".png") ||
            lowerUrl.endsWith(".gif") ||
            lowerUrl.endsWith(".webp") ||
            lowerUrl.endsWith(".bmp") ||
            lowerUrl.contains("image") ||
            lowerUrl.contains("/media/") ||
            lowerUrl.contains("cloudinary") ||
            lowerUrl.contains("s3.") ||
            lowerUrl.contains("blob.") ||
            lowerUrl.contains("twilio") ||
            lowerUrl.contains("amazonaws")
}

/**
 * Extract image URLs from HTML content (like <img src="...">) and direct URLs in text
 */
private fun extractImageUrlsFromHtml(html: String): List<String> {
    val urls = mutableListOf<String>()

    // First, look for <img> tags
    val imgPattern = Regex("""<img[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
    imgPattern.findAll(html).forEach { match ->
        val url = match.groupValues.getOrNull(1)
        if (!url.isNullOrBlank()) {
            urls.add(url)
        }
    }

    // Also look for direct URLs that might be images
    val urlPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    urlPattern.findAll(html).forEach { match ->
        val url = match.value
        if (isImageUrl(url) && !urls.contains(url)) {
            urls.add(url)
        }
    }

    return urls
}

/**
 * Remove HTML tags and image URLs from text
 */
private fun stripHtmlTags(html: String): String {
    var result = html
        .replace(Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE), "") // Remove img tags
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n") // Convert <br> to newline
        .replace(Regex("""<[^>]+>"""), "") // Remove other HTML tags
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")

    // Remove image URLs from text (they'll be rendered as images)
    val urlPattern = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    urlPattern.findAll(result).forEach { match ->
        val url = match.value
        if (isImageUrl(url)) {
            result = result.replace(url, "")
        }
    }

    return result.trim()
}

/**
 * Fullscreen image viewer dialog with save and share options
 */
@Composable
private fun FullscreenImageViewer(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    // Permission launcher for storage (API < 29)
    var pendingSave by remember { mutableStateOf(false) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingSave) {
            isSaving = true
            scope.launch {
                saveImagesToGallery(context, listOf(imageUrl)) { success, message ->
                    isSaving = false
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        } else if (!isGranted) {
            Toast.makeText(context, "Storage permission required to save image", Toast.LENGTH_SHORT).show()
        }
        pendingSave = false
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            // Top action bar
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Share button
                IconButton(
                    onClick = { shareImageUrl(context, imageUrl) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Image",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Save button with loading indicator
                IconButton(
                    onClick = {
                        // Check permission for API < 29
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!hasPermission) {
                                pendingSave = true
                                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                return@IconButton
                            }
                        }
                        // Save image
                        isSaving = true
                        scope.launch {
                            saveImagesToGallery(context, listOf(imageUrl)) { success, message ->
                                isSaving = false
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Save Image",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Close button
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Fullscreen image
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Fullscreen image",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(onClick = {}), // Prevent dismiss when clicking on image
                contentScale = ContentScale.Fit
            )
        }
    }
}
