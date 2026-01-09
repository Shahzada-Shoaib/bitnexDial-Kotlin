package com.bitnextechnologies.bitnexdial.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnextechnologies.bitnexdial.presentation.viewmodel.BlockedNumbersViewModel
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils

data class BlockedNumber(
    val id: String,
    val phoneNumber: String,
    val blockedAt: Long,
    val name: String? = null
)

/**
 * BlockedNumbersScreen with ViewModel injection
 */
@Composable
fun BlockedNumbersScreen(
    viewModel: BlockedNumbersViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val blockedNumbers by viewModel.blockedNumbers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    BlockedNumbersScreenContent(
        blockedNumbers = blockedNumbers,
        isLoading = isLoading,
        onNavigateBack = onNavigateBack,
        onBlockNumber = { phoneNumber -> viewModel.blockNumber(phoneNumber) },
        onUnblockNumber = { id -> viewModel.unblockNumber(id) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockedNumbersScreenContent(
    blockedNumbers: List<BlockedNumber>,
    isLoading: Boolean,
    onNavigateBack: () -> Unit,
    onBlockNumber: (String) -> Unit,
    onUnblockNumber: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var numberToUnblock by remember { mutableStateOf<BlockedNumber?>(null) }

    // Add blocked number dialog
    if (showAddDialog) {
        AddBlockedNumberDialog(
            onDismiss = { showAddDialog = false },
            onBlock = { phoneNumber ->
                onBlockNumber(phoneNumber)
                showAddDialog = false
            }
        )
    }

    // Unblock confirmation dialog
    numberToUnblock?.let { blocked ->
        AlertDialog(
            onDismissRequest = { numberToUnblock = null },
            title = { Text("Unblock Number") },
            text = {
                Text("Are you sure you want to unblock ${blocked.name ?: PhoneNumberUtils.formatForDisplay(blocked.phoneNumber)}?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnblockNumber(blocked.id)
                        numberToUnblock = null
                    }
                ) {
                    Text("Unblock")
                }
            },
            dismissButton = {
                TextButton(onClick = { numberToUnblock = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked Numbers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add blocked number")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Info text
            Text(
                text = "Calls and messages from blocked numbers will be silently rejected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(16.dp)
            )

            if (isLoading && blockedNumbers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (blockedNumbers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No blocked numbers",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { showAddDialog = true }) {
                            Text("Block a number")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(blockedNumbers) { blocked ->
                        BlockedNumberItem(
                            blockedNumber = blocked,
                            onUnblock = { numberToUnblock = blocked }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedNumberItem(
    blockedNumber: BlockedNumber,
    onUnblock: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = blockedNumber.name
                    ?: PhoneNumberUtils.formatForDisplay(blockedNumber.phoneNumber)
            )
        },
        supportingContent = {
            if (blockedNumber.name != null) {
                Text(PhoneNumberUtils.formatForDisplay(blockedNumber.phoneNumber))
            }
        },
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        trailingContent = {
            TextButton(onClick = onUnblock) {
                Text("Unblock")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlockedNumberDialog(
    onDismiss: () -> Unit,
    onBlock: (String) -> Unit
) {
    var phoneNumber by remember { mutableStateOf("") }
    val isValid = PhoneNumberUtils.isValidNumber(phoneNumber)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block a Number") },
        text = {
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Phone number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isValid) {
                        onBlock(PhoneNumberUtils.normalizeNumber(phoneNumber))
                        onDismiss()
                    }
                },
                enabled = isValid
            ) {
                Text("Block")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
