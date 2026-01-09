package com.bitnextechnologies.bitnexdial.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.CallDirection
import com.bitnextechnologies.bitnexdial.domain.model.CallType
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexGreen
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexRed
import com.bitnextechnologies.bitnexdial.presentation.viewmodel.RecentsViewModel
import com.bitnextechnologies.bitnexdial.util.DateTimeUtils
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecentsScreen(
    viewModel: RecentsViewModel = hiltViewModel(),
    onCallClick: (String, String?) -> Unit,  // (phoneNumber, contactName)
    onContactClick: (String) -> Unit,
    onNavigateToAnalytics: () -> Unit = {}
) {
    val calls by viewModel.calls.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val unreadMissedCount by viewModel.unreadMissedCount.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMoreCalls by viewModel.hasMoreCalls.collectAsState()

    // Selection mode state
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedCallIds by viewModel.selectedCallIds.collectAsState()
    val selectedCount by viewModel.selectedCount.collectAsState()
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    var showClearDialog by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var selectedCallForNotes by remember { mutableStateOf<Call?>(null) }
    var notesInput by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val filters = listOf("all", "outgoing", "incoming", "missed")
    // Use shorter labels to prevent text wrapping on smaller screens
    val filterLabels = listOf("All", "Out", "In", "Missed")
    val selectedIndex = filters.indexOf(selectedFilter).coerceAtLeast(0)

    // Data flows automatically from Room database via StateFlow
    // No need to refresh on every screen visit - ViewModel handles initial load
    // and real-time updates come via Socket.IO events

    // Show error in snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Auto-load more calls when scrolling near the bottom
    // Using snapshotFlow to avoid unnecessary recompositions during scroll
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem to totalItems
        }
            .distinctUntilChanged()
            .collect { (lastVisibleItem, totalItems) ->
                if (lastVisibleItem >= totalItems - 5 && hasMoreCalls && !isLoadingMore && calls.isNotEmpty()) {
                    viewModel.loadMoreCalls()
                }
            }
    }

    // Clear history confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Call History") },
            text = { Text("Are you sure you want to clear all call history? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete selected calls confirmation dialog
    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete Selected Calls") },
            text = { Text("Are you sure you want to delete $selectedCount selected call(s)? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedCalls()
                        showDeleteSelectedDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Notes dialog
    if (showNotesDialog && selectedCallForNotes != null) {
        AlertDialog(
            onDismissRequest = {
                showNotesDialog = false
                selectedCallForNotes = null
                notesInput = ""
            },
            title = { Text("Call Notes") },
            text = {
                Column {
                    Text(
                        text = selectedCallForNotes?.contactName
                            ?: PhoneNumberUtils.formatForDisplay(selectedCallForNotes?.phoneNumber ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = notesInput,
                        onValueChange = { notesInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Notes") },
                        placeholder = { Text("Add notes about this call...") },
                        minLines = 3,
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedCallForNotes?.let { call ->
                            viewModel.updateNotes(call.id, notesInput.ifBlank { null })
                        }
                        showNotesDialog = false
                        selectedCallForNotes = null
                        notesInput = ""
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNotesDialog = false
                        selectedCallForNotes = null
                        notesInput = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // Top bar - shows different content in selection mode
        if (isSelectionMode) {
            // Selection mode header
            TopAppBar(
                title = {
                    Text("$selectedCount selected")
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.exitSelectionMode() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }
                },
                actions = {
                    // Select All button
                    TextButton(onClick = { viewModel.selectAllCalls() }) {
                        Text("Select All")
                    }
                    // Delete button
                    IconButton(
                        onClick = { showDeleteSelectedDialog = true },
                        enabled = selectedCount > 0
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete selected",
                            tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        } else {
            // Normal header
            TopAppBar(
                title = { Text("Recents") },
                actions = {
                    IconButton(onClick = onNavigateToAnalytics) {
                        Icon(Icons.Default.Analytics, contentDescription = "Call Analytics")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = calls.isNotEmpty()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear call history")
                    }
                }
            )
        }

        // Filter tabs - All, Out, In, Missed
        // Using ScrollableTabRow for better text display on smaller screens
        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            edgePadding = 0.dp,
            divider = { HorizontalDivider() }
        ) {
            filters.forEachIndexed { index, filter ->
                Tab(
                    selected = selectedFilter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            // Add icons for call types (using AutoMirrored for RTL support)
                            when (filter) {
                                "outgoing" -> Icon(
                                    imageVector = Icons.AutoMirrored.Filled.CallMade,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = BitNexGreen
                                )
                                "incoming" -> Icon(
                                    imageVector = Icons.AutoMirrored.Filled.CallReceived,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                "missed" -> Icon(
                                    imageVector = Icons.AutoMirrored.Filled.CallMissed,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = BitNexRed
                                )
                                else -> {}
                            }
                            if (filter != "all") Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = filterLabels[index],
                                maxLines = 1,
                                softWrap = false
                            )
                            // Show badge for missed calls
                            if (filter == "missed" && unreadMissedCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Badge(
                                    containerColor = BitNexRed
                                ) {
                                    Text(
                                        text = if (unreadMissedCount > 99) "99+" else unreadMissedCount.toString(),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            // Show loading only on initial load when data is empty
            if (isLoading && calls.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (calls.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = when (selectedFilter) {
                                "missed" -> Icons.AutoMirrored.Filled.CallMissed
                                "incoming" -> Icons.AutoMirrored.Filled.CallReceived
                                "outgoing" -> Icons.AutoMirrored.Filled.CallMade
                                else -> Icons.Default.History
                            },
                            contentDescription = "No calls",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when (selectedFilter) {
                                "missed" -> "No missed calls"
                                "incoming" -> "No incoming calls"
                                "outgoing" -> "No outgoing calls"
                                else -> "No recent calls"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text("Tap to refresh")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {
                    items(
                        items = calls,
                        key = { it.id }
                    ) { call ->
                        val isSelected = selectedCallIds.contains(call.id)

                        SelectableCallItem(
                            call = call,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    viewModel.toggleCallSelection(call.id)
                                } else {
                                    onCallClick(call.phoneNumber, call.contactName)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    viewModel.enterSelectionMode(call.id)
                                }
                            },
                            onInfoClick = {
                                // Use contactId if available, otherwise use phone number as identifier
                                val identifier = call.contactId ?: call.phoneNumber
                                if (identifier.isNotBlank()) {
                                    onContactClick(identifier)
                                }
                            },
                            onNotesClick = {
                                selectedCallForNotes = call
                                notesInput = call.notes ?: ""
                                showNotesDialog = true
                            },
                            onDelete = {
                                viewModel.deleteCall(call.id)
                            }
                        )
                    }

                    // Loading indicator at bottom for loading more calls
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
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
            }
        }
    }
    }
}

/**
 * Selectable call item that supports both selection mode and normal mode.
 * - In selection mode: shows checkbox, tap toggles selection
 * - In normal mode: shows swipe-to-delete, tap makes call, long press enters selection mode
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SelectableCallItem(
    call: Call,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onInfoClick: () -> Unit,
    onNotesClick: () -> Unit,
    onDelete: () -> Unit
) {
    if (isSelectionMode) {
        // Selection mode - show checkbox, no swipe
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
            // Reuse CallHistoryItem content but without the full padding
            CallHistoryItemContent(
                call = call,
                onInfoClick = onInfoClick,
                onNotesClick = onNotesClick,
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        // Normal mode - swipe to delete, long press to select
        SwipeableCallItem(
            call = call,
            onClick = onClick,
            onLongClick = onLongClick,
            onInfoClick = onInfoClick,
            onNotesClick = onNotesClick,
            onDelete = onDelete
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableCallItem(
    call: Call,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onInfoClick: () -> Unit,
    onNotesClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                showDeleteConfirm = true
                false // Don't dismiss yet, show confirmation
            } else {
                false
            }
        }
    )

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Call") },
            text = { Text("Are you sure you want to delete this call from history?") },
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

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                },
                label = "swipe_color"
            )
            val scale by animateFloatAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) 1f else 0.75f,
                label = "icon_scale"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.scale(scale)
                )
            }
        },
        content = {
            CallHistoryItem(
                call = call,
                onClick = onClick,
                onLongClick = onLongClick,
                onInfoClick = onInfoClick,
                onNotesClick = onNotesClick
            )
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallHistoryItem(
    call: Call,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onInfoClick: () -> Unit,
    onNotesClick: () -> Unit
) {
    val hasNotes = !call.notes.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Call direction icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val (icon, color) = when {
                call.type == CallType.MISSED -> Icons.AutoMirrored.Filled.CallMissed to BitNexRed
                call.direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived to BitNexGreen
                else -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.primary
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Contact info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = call.contactName ?: PhoneNumberUtils.formatForDisplay(call.phoneNumber),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (call.type == CallType.MISSED) FontWeight.Bold else FontWeight.Normal,
                color = if (call.type == CallType.MISSED) BitNexRed else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = DateTimeUtils.formatCallTime(call.startTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (call.duration > 0) {
                    Text(
                        text = " · ${formatDuration(call.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // Notes indicator
                if (hasNotes) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.StickyNote2,
                        contentDescription = "Has notes",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Notes button
        IconButton(onClick = onNotesClick) {
            Icon(
                imageVector = if (hasNotes) Icons.Default.EditNote else Icons.Default.NoteAdd,
                contentDescription = if (hasNotes) "Edit notes" else "Add notes",
                tint = if (hasNotes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        // Info button
        IconButton(onClick = onInfoClick) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Reusable content for call history item (used by both selection mode and normal mode)
 */
@Composable
fun CallHistoryItemContent(
    call: Call,
    onInfoClick: () -> Unit,
    onNotesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasNotes = !call.notes.isNullOrBlank()

    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Call direction icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val (icon, color) = when {
                call.type == CallType.MISSED -> Icons.AutoMirrored.Filled.CallMissed to BitNexRed
                call.direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived to BitNexGreen
                else -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.primary
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Contact info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = call.contactName ?: PhoneNumberUtils.formatForDisplay(call.phoneNumber),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (call.type == CallType.MISSED) FontWeight.Bold else FontWeight.Normal,
                color = if (call.type == CallType.MISSED) BitNexRed else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = DateTimeUtils.formatCallTime(call.startTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (call.duration > 0) {
                    Text(
                        text = " · ${formatDuration(call.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // Notes indicator
                if (hasNotes) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.StickyNote2,
                        contentDescription = "Has notes",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Notes button
        IconButton(onClick = onNotesClick) {
            Icon(
                imageVector = if (hasNotes) Icons.Default.EditNote else Icons.Default.NoteAdd,
                contentDescription = if (hasNotes) "Edit notes" else "Add notes",
                tint = if (hasNotes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        // Info button
        IconButton(onClick = onInfoClick) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) {
        "${minutes}m ${remainingSeconds}s"
    } else {
        "${remainingSeconds}s"
    }
}
