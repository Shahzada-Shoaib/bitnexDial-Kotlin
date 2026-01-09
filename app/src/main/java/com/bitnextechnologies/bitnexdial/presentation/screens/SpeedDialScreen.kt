package com.bitnextechnologies.bitnexdial.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexGreen
import com.bitnextechnologies.bitnexdial.presentation.viewmodel.SpeedDialViewModel
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import com.bitnextechnologies.bitnexdial.util.SpeedDialEntry
import com.bitnextechnologies.bitnexdial.util.SpeedDialManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedDialScreen(
    viewModel: SpeedDialViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onPickContact: (String) -> Unit = {} // Callback to pick a contact for a digit
) {
    val speedDialEntries by viewModel.speedDialEntries.collectAsState()

    // Dialog state for manual number entry
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedDigit by remember { mutableStateOf<String?>(null) }
    var phoneNumberInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }

    // Dialog for adding/editing speed dial
    if (showAddDialog && selectedDigit != null) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                selectedDigit = null
                phoneNumberInput = ""
                nameInput = ""
            },
            title = { Text("Speed Dial ${selectedDigit}") },
            text = {
                Column {
                    Text(
                        text = "Long-press key ${selectedDigit} to call this number",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedTextField(
                        value = phoneNumberInput,
                        onValueChange = { phoneNumberInput = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (phoneNumberInput.isNotBlank()) {
                            viewModel.setSpeedDial(
                                digit = selectedDigit!!,
                                phoneNumber = phoneNumberInput.trim(),
                                contactName = nameInput.takeIf { it.isNotBlank() }?.trim()
                            )
                        }
                        showAddDialog = false
                        selectedDigit = null
                        phoneNumberInput = ""
                        nameInput = ""
                    },
                    enabled = phoneNumberInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        selectedDigit = null
                        phoneNumberInput = ""
                        nameInput = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speed Dial") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
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
            // Info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Long-press a dialpad key (2-9) to quickly call the assigned number.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Speed dial slots
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(SpeedDialManager.SPEED_DIAL_DIGITS) { digit ->
                    val entry = speedDialEntries[digit]
                    SpeedDialSlot(
                        digit = digit,
                        entry = entry,
                        onClick = {
                            selectedDigit = digit
                            phoneNumberInput = entry?.phoneNumber ?: ""
                            nameInput = entry?.contactName ?: ""
                            showAddDialog = true
                        },
                        onDelete = {
                            viewModel.removeSpeedDial(digit)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SpeedDialSlot(
    digit: String,
    entry: SpeedDialEntry?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Digit circle
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry != null) BitNexGreen
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = digit,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (entry != null) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Entry info
            Column(modifier = Modifier.weight(1f)) {
                if (entry != null) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (entry.contactName != null) {
                        Text(
                            text = PhoneNumberUtils.formatForDisplay(entry.phoneNumber),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "Not set",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Tap to add",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            // Action button
            if (entry != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove speed dial",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add speed dial",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
