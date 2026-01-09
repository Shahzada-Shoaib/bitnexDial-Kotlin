package com.bitnextechnologies.bitnexdial.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnextechnologies.bitnexdial.domain.model.Contact
import com.bitnextechnologies.bitnexdial.presentation.viewmodel.ContactsViewModel
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    contactsViewModel: ContactsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onContactSelected: (String) -> Unit
) {
    val contacts by contactsViewModel.contacts.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Filter contacts based on search query
    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isEmpty()) {
            contacts.take(20) // Show top 20 contacts when no search
        } else {
            contacts.filter { contact ->
                contact.displayName.contains(searchQuery, ignoreCase = true) ||
                contact.phoneNumbers.any { it.contains(searchQuery) }
            }
        }
    }

    // Check if search query looks like a phone number
    val isPhoneNumber = PhoneNumberUtils.looksLikePhoneNumber(searchQuery)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top bar
        TopAppBar(
            title = { Text("New Message") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        // Search/phone number input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
            placeholder = { Text("Enter name or phone number") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (isPhoneNumber && PhoneNumberUtils.isValidNumber(searchQuery)) {
                        onContactSelected(PhoneNumberUtils.normalizeNumber(searchQuery))
                    }
                }
            )
        )

        // If search looks like a phone number, show option to message it directly
        if (isPhoneNumber && searchQuery.length >= 7) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                onClick = {
                    val normalized = PhoneNumberUtils.normalizeNumber(searchQuery)
                    onContactSelected(normalized)
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Message,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Send to ${PhoneNumberUtils.formatForDisplay(searchQuery)}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "New conversation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // Contact list
        if (filteredContacts.isEmpty() && !isPhoneNumber) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonSearch,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isEmpty()) "Search for a contact" else "No contacts found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredContacts) { contact ->
                    NewMessageContactItem(
                        contact = contact,
                        onClick = {
                            contact.phoneNumbers.firstOrNull()?.let { phoneNumber ->
                                onContactSelected(phoneNumber)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NewMessageContactItem(
    contact: Contact,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(contact.displayName) },
        supportingContent = {
            contact.phoneNumbers.firstOrNull()?.let { number ->
                Text(PhoneNumberUtils.formatForDisplay(number))
            }
        },
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = contact.getInitials(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}
