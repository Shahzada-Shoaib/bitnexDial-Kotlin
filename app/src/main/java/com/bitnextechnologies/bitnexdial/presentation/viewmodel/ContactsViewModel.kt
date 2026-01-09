package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketEvent
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.domain.model.Contact
import com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ContactsViewModel"

/**
 * Data class for Add Contact dialog state
 */
data class AddContactState(
    val isVisible: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val isLoading: Boolean = false,
    val nameError: String? = null,
    val phoneError: String? = null
)

/**
 * Data class for Edit Contact dialog state
 */
data class EditContactState(
    val isVisible: Boolean = false,
    val contact: Contact? = null,
    val name: String = "",
    val isLoading: Boolean = false,
    val nameError: String? = null
)

/**
 * Data class for Delete Contact dialog state
 */
data class DeleteContactState(
    val isVisible: Boolean = false,
    val contact: Contact? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: IContactRepository,
    private val socketManager: SocketManager
) : ViewModel() {

    // Track if initial data load is complete - prevents showing loading on subsequent visits
    private var hasInitiallyLoaded = false

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(true) // Start true only for first load
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Separate refresh state for pull-to-refresh indicator
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Add Contact dialog state
    private val _addContactState = MutableStateFlow(AddContactState())
    val addContactState: StateFlow<AddContactState> = _addContactState.asStateFlow()

    // Edit Contact dialog state
    private val _editContactState = MutableStateFlow(EditContactState())
    val editContactState: StateFlow<EditContactState> = _editContactState.asStateFlow()

    // Delete Contact dialog state
    private val _deleteContactState = MutableStateFlow(DeleteContactState())
    val deleteContactState: StateFlow<DeleteContactState> = _deleteContactState.asStateFlow()

    // Selected tab: 0 = All, 1 = Favorites
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    // Favorite contacts - always loaded for quick access
    val favoriteContacts: StateFlow<List<Contact>> = contactRepository.getFavoriteContacts()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val contacts: StateFlow<List<Contact>> = combine(_searchQuery, _selectedTab) { query, tab ->
        Pair(query, tab)
    }.flatMapLatest { (query, tab) ->
        when {
            query.isNotEmpty() -> flow { emit(contactRepository.searchContacts(query)) }
            tab == 1 -> contactRepository.getFavoriteContacts()
            else -> contactRepository.getAllContacts()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        loadInitialData()
        observeSocketEvents()

        // Mark initial load complete when data arrives
        viewModelScope.launch {
            contacts.collect { contactList ->
                if (contactList.isNotEmpty() && !hasInitiallyLoaded) {
                    hasInitiallyLoaded = true
                    _isLoading.value = false
                    Log.d(TAG, "Initial contacts loaded: ${contactList.size}")
                } else if (hasInitiallyLoaded) {
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Load initial data - only shows loading indicator on first app launch
     */
    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                contactRepository.syncContacts()
                hasInitiallyLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial contacts", e)
                _error.value = e.message ?: "Failed to load contacts"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Listen to Socket.IO events for real-time contact updates.
     * This is the professional event-driven approach - no polling needed.
     */
    private fun observeSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.ContactUpdated -> {
                        Log.d(TAG, "Contact ${event.action}: ${event.name} (${event.contactId})")
                        handleContactUpdate(event)
                    }
                    else -> {
                        // Ignore other events
                    }
                }
            }
        }
    }

    /**
     * Handle contact update event - refresh contacts to reflect changes
     * For created/updated: sync to get the new/updated contact
     * For deleted: sync to remove the contact from local database
     */
    private fun handleContactUpdate(event: SocketEvent.ContactUpdated) {
        viewModelScope.launch {
            try {
                when (event.action) {
                    "created", "updated" -> {
                        // Sync contacts to get the new/updated contact
                        contactRepository.syncContacts()
                        Log.d(TAG, "Contacts synced after ${event.action}: ${event.name}")
                    }
                    "deleted" -> {
                        // Delete the contact locally and sync
                        contactRepository.deleteContact(event.contactId)
                        Log.d(TAG, "Contact deleted: ${event.name}")
                    }
                    else -> {
                        // Unknown action, do a full sync
                        contactRepository.syncContacts()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling contact update", e)
            }
        }
    }

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                contactRepository.syncContacts()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing contacts", e)
                _error.value = e.message ?: "Failed to sync contacts"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            try {
                contactRepository.deleteContact(contactId)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting contact", e)
                _error.value = e.message ?: "Failed to delete contact"
            }
        }
    }

    fun toggleFavorite(contactId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            try {
                contactRepository.setFavorite(contactId, isFavorite)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling favorite", e)
                _error.value = e.message ?: "Failed to update favorite"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    // ==================== Add Contact Dialog Methods ====================

    /**
     * Show the add contact dialog
     */
    fun showAddContactDialog(prefillPhone: String = "") {
        _addContactState.value = AddContactState(
            isVisible = true,
            phone = prefillPhone
        )
    }

    /**
     * Hide the add contact dialog
     */
    fun hideAddContactDialog() {
        _addContactState.value = AddContactState()
    }

    /**
     * Update contact name in dialog
     */
    fun updateAddContactName(name: String) {
        _addContactState.value = _addContactState.value.copy(name = name, nameError = null)
    }

    /**
     * Update contact phone in dialog
     */
    fun updateAddContactPhone(phone: String) {
        _addContactState.value = _addContactState.value.copy(phone = phone, phoneError = null)
    }

    /**
     * Normalize phone number for comparison (like web version)
     */
    private fun normalizePhoneForComparison(phone: String): String {
        return phone.replace(Regex("[^\\d]"), "").removePrefix("1").takeLast(10)
    }

    /**
     * Check if contact name already exists
     */
    private suspend fun isContactNameExists(name: String): Boolean {
        if (name.isBlank()) return false
        val allContacts = contactRepository.searchContacts("")
        return allContacts.any {
            it.displayName.equals(name.trim(), ignoreCase = true)
        }
    }

    /**
     * Check if phone number already exists and return existing contact name
     */
    private suspend fun getExistingContactByPhone(phone: String): String? {
        if (phone.isBlank()) return null
        val normalizedPhone = normalizePhoneForComparison(phone)
        val allContacts = contactRepository.searchContacts("")
        val existing = allContacts.find { contact ->
            contact.phoneNumbers.any {
                normalizePhoneForComparison(it) == normalizedPhone
            }
        }
        return existing?.displayName
    }

    /**
     * Add new contact with validation (matches web version's logic)
     */
    fun addContact() {
        val state = _addContactState.value
        val name = state.name.trim()
        val phone = state.phone.trim()

        // Basic validation
        if (name.isEmpty()) {
            _addContactState.value = _addContactState.value.copy(nameError = "Please enter a contact name")
            return
        }
        if (phone.isEmpty()) {
            _addContactState.value = _addContactState.value.copy(phoneError = "Please enter a phone number")
            return
        }

        viewModelScope.launch {
            _addContactState.value = _addContactState.value.copy(isLoading = true, nameError = null, phoneError = null)

            try {
                // Check for duplicate name (like web version)
                if (isContactNameExists(name)) {
                    _addContactState.value = _addContactState.value.copy(
                        isLoading = false,
                        nameError = "A contact named \"$name\" already exists."
                    )
                    return@launch
                }

                // Check for duplicate phone (like web version)
                val existingContactName = getExistingContactByPhone(phone)
                if (existingContactName != null) {
                    _addContactState.value = _addContactState.value.copy(
                        isLoading = false,
                        phoneError = "This phone number is already saved as \"$existingContactName\"."
                    )
                    return@launch
                }

                // Create the contact
                val contact = Contact(
                    id = normalizePhoneForComparison(phone), // Use normalized phone as ID
                    name = name,
                    phoneNumbers = listOf(phone),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                contactRepository.createContact(contact)
                Log.d(TAG, "Contact created successfully: $name")

                // Close dialog and sync
                _addContactState.value = AddContactState()
                syncContacts()

            } catch (e: Exception) {
                Log.e(TAG, "Error adding contact", e)
                _addContactState.value = _addContactState.value.copy(
                    isLoading = false,
                    phoneError = "Failed to add contact. Please try again."
                )
            }
        }
    }

    /**
     * Sync contacts after adding
     */
    private fun syncContacts() {
        viewModelScope.launch {
            try {
                contactRepository.syncContacts()
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing contacts after add", e)
            }
        }
    }

    // ==================== Edit Contact Dialog Methods ====================

    /**
     * Show the edit contact dialog
     */
    fun showEditContactDialog(contact: Contact) {
        _editContactState.value = EditContactState(
            isVisible = true,
            contact = contact,
            name = contact.displayName
        )
    }

    /**
     * Hide the edit contact dialog
     */
    fun hideEditContactDialog() {
        _editContactState.value = EditContactState()
    }

    /**
     * Update contact name in edit dialog
     */
    fun updateEditContactName(name: String) {
        _editContactState.value = _editContactState.value.copy(name = name, nameError = null)
    }

    /**
     * Edit contact (update name)
     */
    fun editContact() {
        val state = _editContactState.value
        val contact = state.contact ?: return
        val newName = state.name.trim()

        if (newName.isEmpty()) {
            _editContactState.value = _editContactState.value.copy(nameError = "Please enter a contact name")
            return
        }

        viewModelScope.launch {
            _editContactState.value = _editContactState.value.copy(isLoading = true, nameError = null)

            try {
                // Check for duplicate name (excluding current contact)
                val allContacts = contactRepository.searchContacts("")
                val duplicateName = allContacts.find { c ->
                    c.id != contact.id && c.displayName.equals(newName, ignoreCase = true)
                }

                if (duplicateName != null) {
                    _editContactState.value = _editContactState.value.copy(
                        isLoading = false,
                        nameError = "A contact named \"$newName\" already exists."
                    )
                    return@launch
                }

                // Update the contact
                val updatedContact = contact.copy(name = newName)
                contactRepository.updateContact(updatedContact)
                Log.d(TAG, "Contact updated successfully: $newName")

                // Close dialog and sync
                _editContactState.value = EditContactState()
                syncContacts()

            } catch (e: Exception) {
                Log.e(TAG, "Error updating contact", e)
                _editContactState.value = _editContactState.value.copy(
                    isLoading = false,
                    nameError = "Failed to update contact. Please try again."
                )
            }
        }
    }

    // ==================== Delete Contact Dialog Methods ====================

    /**
     * Show the delete contact dialog
     */
    fun showDeleteContactDialog(contact: Contact) {
        _deleteContactState.value = DeleteContactState(
            isVisible = true,
            contact = contact
        )
    }

    /**
     * Hide the delete contact dialog
     */
    fun hideDeleteContactDialog() {
        _deleteContactState.value = DeleteContactState()
    }

    /**
     * Delete contact
     */
    fun confirmDeleteContact() {
        val state = _deleteContactState.value
        val contact = state.contact ?: return

        viewModelScope.launch {
            _deleteContactState.value = _deleteContactState.value.copy(isLoading = true)

            try {
                contactRepository.deleteContact(contact.id)
                Log.d(TAG, "Contact deleted successfully: ${contact.displayName}")

                // Close dialog
                _deleteContactState.value = DeleteContactState()

            } catch (e: Exception) {
                Log.e(TAG, "Error deleting contact", e)
                _deleteContactState.value = DeleteContactState()
                _error.value = "Failed to delete contact. Please try again."
            }
        }
    }
}
