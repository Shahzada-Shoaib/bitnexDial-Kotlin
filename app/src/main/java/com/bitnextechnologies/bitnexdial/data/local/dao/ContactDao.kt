package com.bitnextechnologies.bitnexdial.data.local.dao

import androidx.room.*
import com.bitnextechnologies.bitnexdial.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Contacts
 */
@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isBlocked = 1 ORDER BY name ASC")
    fun getBlockedContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :contactId")
    suspend fun getContactById(contactId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE id = :contactId")
    fun getContactByIdFlow(contactId: String): Flow<ContactEntity?>

    @Query("""
        SELECT * FROM contacts
        WHERE name LIKE '%' || :query || '%'
        OR firstName LIKE '%' || :query || '%'
        OR lastName LIKE '%' || :query || '%'
        OR company LIKE '%' || :query || '%'
        OR phoneNumbers LIKE '%' || :query || '%'
        ORDER BY name ASC
        LIMIT :limit
    """)
    suspend fun searchContacts(query: String, limit: Int = 50): List<ContactEntity>

    @Query("""
        SELECT * FROM contacts
        WHERE phoneNumbers LIKE '%' || :phoneNumber || '%'
        LIMIT 1
    """)
    suspend fun findContactByPhoneNumber(phoneNumber: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<ContactEntity>)

    @Update
    suspend fun updateContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :contactId")
    suspend fun deleteContactById(contactId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAllContacts()

    @Query("UPDATE contacts SET isFavorite = :isFavorite WHERE id = :contactId")
    suspend fun setFavorite(contactId: String, isFavorite: Boolean)

    @Query("UPDATE contacts SET isBlocked = :isBlocked WHERE id = :contactId")
    suspend fun setBlocked(contactId: String, isBlocked: Boolean)

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getContactCount(): Int

    @Query("SELECT COUNT(*) FROM contacts WHERE isFavorite = 1")
    suspend fun getFavoriteCount(): Int

    @Query("SELECT id, isFavorite, isBlocked FROM contacts")
    suspend fun getAllContactPreferences(): List<ContactPreferences>
}

/**
 * Lightweight data class for preserving user preferences during sync
 */
data class ContactPreferences(
    val id: String,
    val isFavorite: Boolean,
    val isBlocked: Boolean
)
