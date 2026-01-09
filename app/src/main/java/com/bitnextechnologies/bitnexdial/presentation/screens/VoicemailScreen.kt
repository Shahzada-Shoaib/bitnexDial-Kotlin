package com.bitnextechnologies.bitnexdial.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnextechnologies.bitnexdial.domain.model.Voicemail
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexGreen
import com.bitnextechnologies.bitnexdial.presentation.viewmodel.VoicemailViewModel
import com.bitnextechnologies.bitnexdial.util.DateTimeUtils
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicemailScreen(
    viewModel: VoicemailViewModel = hiltViewModel(),
    onCallBack: (String) -> Unit
) {
    val filteredVoicemails by viewModel.filteredVoicemails.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val playingVoicemailId by viewModel.playingVoicemailId.collectAsState()
    val isAudioLoading by viewModel.isAudioLoading.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val visuallyReadVoicemails by viewModel.visuallyReadVoicemails.collectAsState()

    // Mark visually read voicemails as actually read when:
    // 1. User switches to Read filter (they're viewing read tab, so mark the visually read ones)
    // 2. Screen is disposed (user navigates away)
    LaunchedEffect(selectedFilter) {
        if (selectedFilter == VoicemailViewModel.FilterType.READ && visuallyReadVoicemails.isNotEmpty()) {
            // User switched to read tab, mark visually read as actually read
            viewModel.markVisuallyReadAsRead()
        }
    }

    // Mark visually read voicemails as actually read when screen is disposed
    DisposableEffect(Unit) {
        onDispose {
            viewModel.markVisuallyReadAsRead()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Voicemail",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    if (isLoading && filteredVoicemails.isNotEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Filter tabs
                FilterTabs(
                    selectedFilter = selectedFilter,
                    onFilterSelected = { viewModel.setFilter(it) }
                )

                // Show loading only on initial load when data is empty
                if (isLoading && filteredVoicemails.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredVoicemails.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Voicemail,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "No Voicemails",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Your voicemails will appear here",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            OutlinedButton(
                                onClick = { viewModel.refresh() },
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Refresh")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(filteredVoicemails, key = { it.id }) { voicemail ->
                            ModernVoicemailItem(
                                voicemail = voicemail,
                                isPlaying = playingVoicemailId == voicemail.id,
                                isLoading = playingVoicemailId == voicemail.id && isAudioLoading,
                                progress = if (playingVoicemailId == voicemail.id) playbackProgress else 0f,
                                currentPosition = if (playingVoicemailId == voicemail.id) currentPositionMs else 0L,
                                duration = if (playingVoicemailId == voicemail.id && durationMs > 0) durationMs else (voicemail.duration * 1000L),
                                speed = if (playingVoicemailId == voicemail.id) playbackSpeed else 1.0f,
                                visuallyReadVoicemails = visuallyReadVoicemails,
                                onPlayPause = { viewModel.togglePlayback(voicemail.id) },
                                onSeek = { viewModel.seekTo(it) },
                                onSpeedChange = { viewModel.cyclePlaybackSpeed() },
                                onCallBack = { onCallBack(voicemail.callerNumber) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterTabs(
    selectedFilter: VoicemailViewModel.FilterType,
    onFilterSelected: (VoicemailViewModel.FilterType) -> Unit
) {
    TabRow(
        selectedTabIndex = selectedFilter.ordinal,
        modifier = Modifier.fillMaxWidth()
    ) {
        VoicemailViewModel.FilterType.values().forEach { filter ->
            Tab(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                text = {
                    Text(
                        text = when (filter) {
                            VoicemailViewModel.FilterType.ALL -> "All"
                            VoicemailViewModel.FilterType.READ -> "Read"
                            VoicemailViewModel.FilterType.UNREAD -> "Unread"
                        },
                        fontSize = 13.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun ModernVoicemailItem(
    voicemail: Voicemail,
    isPlaying: Boolean,
    isLoading: Boolean,
    progress: Float,
    currentPosition: Long,
    duration: Long,
    speed: Float,
    visuallyReadVoicemails: Set<String>,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedChange: () -> Unit,
    onCallBack: () -> Unit
) {
    val displayName = voicemail.callerName ?: PhoneNumberUtils.formatForDisplay(voicemail.callerNumber)
    val initials = getInitials(voicemail.callerName, voicemail.callerNumber)

    // Visual read state: actually read OR currently playing OR in visually read set (stays read until screen leaves)
    val isVisuallyRead = voicemail.isRead || isPlaying || (voicemail.id in visuallyReadVoicemails)

    // Better color scheme for read/unread - unread is prominent, read is subtle grey
    val cardColor by animateColorAsState(
        targetValue = if (!isVisuallyRead)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) // More prominent for unread
        else
            MaterialTheme.colorScheme.surface,
        animationSpec = tween(300),
        label = "cardColor"
    )

    // Text colors - unread is bold and dark, read is grey
    val nameColor = if (!isVisuallyRead) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }

    // Left border accent color for unread items - simple and prominent
    val borderColor = if (!isVisuallyRead) {
        BitNexGreen  // Prominent green color
    } else {
        Color.Transparent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (!isVisuallyRead) 3.dp else 1.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Left border accent for unread items
            if (!isVisuallyRead) {
                Box(
                    modifier = Modifier
                        .width(2.dp)  // Slim border
                        .fillMaxHeight()
                        .background(borderColor)
                        .align(Alignment.CenterStart)
                )
            }
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
            // Header row with avatar, name, time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Avatar - Fixed overflow with proper clipping, more prominent for unread
                Box(
                    modifier = Modifier
                        .size(if (!isVisuallyRead) 48.dp else 44.dp)
                        .clip(CircleShape)
                        .background(
                            if (!isVisuallyRead)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (!isVisuallyRead)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Name and time - Fixed overflow
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (!isVisuallyRead) FontWeight.Bold else FontWeight.Medium,
                        color = nameColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = DateTimeUtils.formatRelativeTime(voicemail.receivedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!isVisuallyRead)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            text = " · ",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Text(
                            text = formatDuration(voicemail.duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!isVisuallyRead)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        if (!isVisuallyRead) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Audio player - always visible inline (WhatsApp style)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(8.dp)
            ) {
                // Play/Pause button
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = BitNexGreen
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Progress bar and time
                Column(modifier = Modifier.weight(1f)) {
                    // Track if currently dragging for smoother animation
                    var isDragging by remember { mutableStateOf(false) }
                    var dragProgress by remember { mutableStateOf(0f) }

                    // Custom slim progress bar with tap and drag-to-seek
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .padding(vertical = 4.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    // Tap to seek
                                    val seekProgress = (offset.x / size.width).coerceIn(0f, 1f)
                                    onSeek(seekProgress)
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        isDragging = true
                                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                                        onSeek(dragProgress)
                                    },
                                    onDragEnd = {
                                        isDragging = false
                                    },
                                    onDragCancel = {
                                        isDragging = false
                                    }
                                )
                            }
                    ) {
                        val trackWidth = maxWidth

                        // Background track
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                                .align(Alignment.Center)
                        )

                        // Progress fill
                        val animatedProgress by animateFloatAsState(
                            targetValue = progress,
                            animationSpec = tween(100),
                            label = "progress"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(BitNexGreen)
                                .align(Alignment.CenterStart)
                        )

                        // Seek thumb (always visible when there's progress or playing)
                        // Enlarges while dragging for better feedback
                        if (isPlaying || progress > 0f || isDragging) {
                            val thumbSize by animateFloatAsState(
                                targetValue = if (isDragging) 16f else 12f,
                                animationSpec = tween(100),
                                label = "thumbSize"
                            )
                            val thumbOffset = (trackWidth * animatedProgress) - (thumbSize / 2).dp
                            Box(
                                modifier = Modifier
                                    .offset(x = thumbOffset.coerceAtLeast(0.dp))
                                    .size(thumbSize.dp)
                                    .clip(CircleShape)
                                    .background(BitNexGreen)
                                    .align(Alignment.CenterStart)
                            )
                        }
                    }

                    // Time display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDurationMs(currentPosition),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = formatDurationMs(duration),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Speed button - clickable to cycle through speeds
                if (isPlaying || progress > 0f) {
                    Surface(
                        onClick = onSpeedChange,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = when (speed) {
                                1.5f -> "1.5x"
                                2.0f -> "2x"
                                else -> "1x"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = BitNexGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Transcription if available
            voicemail.transcription?.takeIf { it.isNotBlank() }?.let { transcription ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatQuote,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = transcription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Call back button
                FilledTonalButton(
                    onClick = onCallBack,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Call Back", fontSize = 13.sp)
                }
            }
            }
        }
    }
}

private fun getInitials(name: String?, phoneNumber: String): String {
    if (!name.isNullOrBlank()) {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
            parts.size == 1 -> parts.first().take(2).uppercase()
            else -> PhoneNumberUtils.getInitialsFromNumber(phoneNumber)
        }
    }
    return PhoneNumberUtils.getInitialsFromNumber(phoneNumber)
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) {
        "${minutes}:${"%02d".format(remainingSeconds)}"
    } else {
        "0:${"%02d".format(remainingSeconds)}"
    }
}

private fun formatDurationMs(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
