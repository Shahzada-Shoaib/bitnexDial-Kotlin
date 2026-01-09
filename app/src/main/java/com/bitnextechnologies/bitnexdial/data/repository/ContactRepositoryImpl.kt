package com.bitnextechnologies.bitnexdial.data.repository

import android.content.Context
import android.util.Log
import com.bitnextechnologies.bitnexdial.data.local.dao.ContactDao
import com.bitnextechnologies.bitnexdial.data.local.entity.ContactEntity
import com.bitnextechnologies.bitnexdial.data.remote.api.BitnexApiService
import com.bitnextechnologies.bitnexdial.data.remote.dto.DeleteContactRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.SaveContactRequest
import com.bitnextechnologies.bitnexdial.domain.model.Contact
import com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContactRepositoryImpl"

@Singleton
class ContactRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val apiService: BitnexApiService,
    private val secureCredentialManager: SecureCredentialManager
) : IContactRepository {

    /**
     * Get the user's phone number (owner) from SecureCredentialManager
     */
    private fun getOwnerPhone(): String {
        val phone = secureCredentialManager.getSenderPhone() ?: ""
        // Remove non-digit characters but keep the full number including country code
        return phone.replace(Regex("[^\\d+]"), "")
    }

    override fun getAllContacts(): Flow<List<Contact>> {
        return contactDao.getAllContacts().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getFavoriteContacts(): Flow<List<Contact>> {
        return contactDao.getFavoriteContacts().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getContactById(contactId: String): Contact? {
        return contactDao.getContactById(contactId)?.toDomain()
    }

    override suspend fun getContactByPhoneNumber(phoneNumber: String): Contact? {
        return contactDao.findContactByPhoneNumber(phoneNumber)?.toDomain()
    }

    override suspend fun searchContacts(query: String): List<Contact> {
        return contactDao.searchContacts(query).map { it.toDomain() }
    }

    override suspend fun createContact(contact: Contact): Contact {
        val owner = getOwnerPhone()
        if (owner.isEmpty()) {
            Log.w(TAG, "createContact: No owner phone available")
            contactDao.insertContact(ContactEntity.fromDomain(contact))
            return contact
        }

        // Create on server first using /api/save-contact
        try {
            val primaryNumber = contact.phoneNumbers.firstOrNull() ?: ""
            val response = apiService.saveContact(
                SaveContactRequest(
                    myPhoneNumber = owner,
                    contactNumber = primaryNumber,
                    name = contact.displayName ?: "${contact.firstName ?: ""} ${contact.lastName ?: ""}".trim(),
                    type = "personal"
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "createContact: Saved contact to server")
                contactDao.insertContact(ContactEntity.fromDomain(contact, System.currentTimeMillis()))
                return contact
            }
        } catch (e: Exception) {
            Log.e(TAG, "createContact: Failed to save contact to server", e)
        }

        // Save locally as fallback
        contactDao.insertContact(ContactEntity.fromDomain(contact))
        return contact
    }

    override suspend fun updateContact(contact: Contact): Contact {
        val owner = getOwnerPhone()
        if (owner.isEmpty()) {
            Log.w(TAG, "updateContact: No owner phone available")
            contactDao.updateContact(ContactEntity.fromDomain(contact))
            return contact
        }

        // Update on server using /api/save-contact (same endpoint for create/update)
        try {
            val primaryNumber = contact.phoneNumbers.firstOrNull() ?: ""
            val response = apiService.saveContact(
                SaveContactRequest(
                    myPhoneNumber = owner,
                    contactNumber = primaryNumber,
                    name = contact.name ?: contact.displayName,
                    type = "personal"
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "updateContact: Updated contact on server")
            } else {
                Log.w(TAG, "updateContact: Server update failed - ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateContact: Failed to update contact on server", e)
        }

        // Always update locally
        contactDao.updateContact(ContactEntity.fromDomain(contact))
        return contact
    }

    override suspend fun deleteContact(contactId: String) {
        val owner = getOwnerPhone()
        val contact = contactDao.getContactById(contactId)

        if (owner.isNotEmpty() && contact != null) {
            try {
                val contactNumber = contact.phoneNumbers.firstOrNull()?.number ?: ""
                if (contactNumber.isNotEmpty()) {
                    apiService.deleteContact(
                        DeleteContactRequest(
                            myPhoneNumber = owner,
                            contactNumber = contactNumber
                        )
                    )
                    Log.d(TAG, "deleteContact: Deleted contact from server")
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteContact: Failed to delete from server", e)
            }
        }
        contactDao.deleteContactById(contactId)
    }

    override suspend fun setFavorite(contactId: String, isFavorite: Boolean) {
        contactDao.setFavorite(contactId, isFavorite)
    }

    override suspend fun syncContacts() {
        val owner = getOwnerPhone()
        if (owner.isEmpty()) {
            Log.w(TAG, "syncContacts: No owner phone available, skipping sync")
            return
        }

        Log.d(TAG, "syncContacts: Fetching contacts for owner=$owner")

        try {
            // Save user preferences (favorites, blocked) before sync to restore after
            val existingPreferences = contactDao.getAllContactPreferences()
            val preferencesMap = existingPreferences.associateBy { it.id }
            Log.d(TAG, "syncContacts: Saved ${preferencesMap.size} contact preferences (favorites/blocked)")

            val response = apiService.getContacts(owner = owner)
            if (response.isSuccessful && response.body() != null) {
                // The API returns an array directly: [{ contact: "...", name: "..." }, ...]
                val contactItems = response.body() ?: return

                Log.d(TAG, "syncContacts: Got ${contactItems.size} contacts from API")

                val contacts = contactItems.mapNotNull { dto ->
                    // API returns: { contact: "1234567890", name: "John Doe" }
                    val phoneNumber = dto.contact ?: return@mapNotNull null
                    val name = dto.name ?: phoneNumber

                    // Normalize phone number for consistent ID (prevents +1234 vs 1234 duplicates)
                    val normalizedPhone = com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils.normalizeForComparison(phoneNumber)

                    // Preserve user preferences (favorite, blocked) from existing contact
                    val existingPrefs = preferencesMap[normalizedPhone]

                    ContactEntity(
                        id = normalizedPhone, // Use normalized phone number as ID
                        name = name,
                        firstName = null,
                        lastName = null,
                        company = null,
                        jobTitle = null,
                        phoneNumbers = listOf(
                            com.bitnextechnologies.bitnexdial.data.local.entity.ContactPhoneNumberEntity(
                                phoneNumber, "mobile", true
                            )
                        ),
                        emails = emptyList(),
                        avatarUrl = null,
                        notes = null,
                        isFavorite = existingPrefs?.isFavorite ?: false, // Preserve favorite status
                        isBlocked = existingPrefs?.isBlocked ?: false,   // Preserve blocked status
                        tags = emptyList(),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        syncedAt = System.currentTimeMillis()
                    )
                }

                if (contacts.isNotEmpty()) {
                    contactDao.insertContacts(contacts)
                    Log.d(TAG, "syncContacts: Saved ${contacts.size} contacts to local database (preserved favorites/blocked)")
                }
            } else {
                Log.e(TAG, "syncContacts: API error - ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncContacts: Error syncing contacts", e)
            throw e
        }
    }
}
