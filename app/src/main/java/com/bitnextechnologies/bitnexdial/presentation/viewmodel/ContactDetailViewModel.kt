package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.domain.model.Contact
import com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ContactDetailViewModel"

/**
 * Edit contact state for the dialog
 */
data class EditContactDetailState(
    val isVisible: Boolean = false,
    val name: String = "",
    val isLoading: Boolean = false,
    val nameError: String? = null
)

/**
 * Delete contact state for confirmation dialog
 */
data class DeleteContactDetailState(
    val isVisible: Boolean = false,
    val isLoading: Boolean = false
)

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val contactRepository: IContactRepository
) : ViewModel() {

    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _editState = MutableStateFlow(EditContactDetailState())
    val editState: StateFlow<EditContactDetailState> = _editState.asStateFlow()

    private val _deleteState = MutableStateFlow(DeleteContactDetailState())
    val deleteState: StateFlow<DeleteContactDetailState> = _deleteState.asStateFlow()

    private val _contactDeleted = MutableStateFlow(false)
    val contactDeleted: StateFlow<Boolean> = _contactDeleted.asStateFlow()

    fun loadContact(contactId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // First try loading by contactId
                var contact = contactRepository.getContactById(contactId)

                // If not found and contactId looks like a phone number, try lookup by phone number
                if (contact == null && contactId.any { it.isDigit() }) {
                    contact = contactRepository.getContactByPhoneNumber(contactId)
                }

                // If still not found but we have a phone number, create a temporary contact for display
                if (contact == null && contactId.any { it.isDigit() }) {
                    val phoneNumber = contactId.filter { it.isDigit() || it == '+' }
                    contact = Contact(
                        id = phoneNumber,
                        name = null,
                        phoneNumbers = listOf(phoneNumber)
                    )
                    Log.d(TAG, "Created temporary contact for phone: $phoneNumber")
                }

                _contact.value = contact
            } catch (e: Exception) {
                Log.e(TAG, "Error loading contact", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val currentContact = _contact.value ?: return@launch
            try {
                contactRepository.setFavorite(currentContact.id, !currentContact.isFavorite)
                _contact.value = currentContact.copy(isFavorite = !currentContact.isFavorite)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling favorite", e)
            }
        }
    }

    // ==================== Edit Contact ====================

    fun showEditDialog() {
        val currentContact = _contact.value ?: return
        _editState.value = EditContactDetailState(
            isVisible = true,
            name = currentContact.displayName
        )
    }

    fun hideEditDialog() {
        _editState.value = EditContactDetailState()
    }

    fun updateEditName(name: String) {
        _editState.value = _editState.value.copy(name = name, nameError = null)
    }

    fun confirmEdit() {
        val currentContact = _contact.value ?: return
        val newName = _editState.value.name.trim()

        if (newName.isEmpty()) {
            _editState.value = _editState.value.copy(nameError = "Please enter a contact name")
            return
        }

        viewModelScope.launch {
            _editState.value = _editState.value.copy(isLoading = true, nameError = null)

            try {
                // Check for duplicate name
                val allContacts = contactRepository.searchContacts("")
                val duplicate = allContacts.find { c ->
                    c.id != currentContact.id && c.displayName.equals(newName, ignoreCase = true)
                }

                if (duplicate != null) {
                    _editState.value = _editState.value.copy(
                        isLoading = false,
                        nameError = "A contact named \"$newName\" already exists."
                    )
                    return@launch
                }

                // Update contact
                val updatedContact = currentContact.copy(name = newName)
                contactRepository.updateContact(updatedContact)
                _contact.value = updatedContact

                Log.d(TAG, "Contact updated successfully: $newName")
                _editState.value = EditContactDetailState()

            } catch (e: Exception) {
                Log.e(TAG, "Error updating contact", e)
                _editState.value = _editState.value.copy(
                    isLoading = false,
                    nameError = "Failed to update contact. Please try again."
                )
            }
        }
    }

    // ==================== Delete Contact ====================

    fun showDeleteDialog() {
        _deleteState.value = DeleteContactDetailState(isVisible = true)
    }

    fun hideDeleteDialog() {
        _deleteState.value = DeleteContactDetailState()
    }

    fun confirmDelete() {
        val currentContact = _contact.value ?: return

        viewModelScope.launch {
            _deleteState.value = _deleteState.value.copy(isLoading = true)

            try {
                contactRepository.deleteContact(currentContact.id)
                Log.d(TAG, "Contact deleted successfully: ${currentContact.displayName}")
                _deleteState.value = DeleteContactDetailState()
                _contactDeleted.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting contact", e)
                _deleteState.value = DeleteContactDetailState()
            }
        }
    }
}
