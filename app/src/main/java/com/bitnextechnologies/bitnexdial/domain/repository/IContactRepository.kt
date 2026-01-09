package com.bitnextechnologies.bitnexdial.domain.repository

import com.bitnextechnologies.bitnexdial.domain.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for contact operations
 */
interface IContactRepository {

    /**
     * Get all contacts as Flow
     */
    fun getAllContacts(): Flow<List<Contact>>

    /**
     * Get favorite contacts as Flow
     */
    fun getFavoriteContacts(): Flow<List<Contact>>

    /**
     * Get contact by ID
     */
    suspend fun getContactById(contactId: String): Contact?

    /**
     * Get contact by phone number
     */
    suspend fun getContactByPhoneNumber(phoneNumber: String): Contact?

    /**
     * Search contacts
     */
    suspend fun searchContacts(query: String): List<Contact>

    /**
     * Create contact
     */
    suspend fun createContact(contact: Contact): Contact

    /**
     * Update contact
     */
    suspend fun updateContact(contact: Contact): Contact

    /**
     * Delete contact
     */
    suspend fun deleteContact(contactId: String)

    /**
     * Set favorite status
     */
    suspend fun setFavorite(contactId: String, isFavorite: Boolean)

    /**
     * Sync contacts with server
     */
    suspend fun syncContacts()
}
