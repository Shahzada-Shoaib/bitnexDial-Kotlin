package com.bitnextechnologies.bitnexdial.data.local.dao

import androidx.room.*
import com.bitnextechnologies.bitnexdial.data.local.entity.PhoneNumberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhoneNumberDao {

    @Query("SELECT * FROM phone_numbers WHERE isActive = 1 ORDER BY isDefault DESC, number ASC")
    fun getAllPhoneNumbers(): Flow<List<PhoneNumberEntity>>

    @Query("SELECT * FROM phone_numbers WHERE isDefault = 1 AND isActive = 1 LIMIT 1")
    fun getDefaultPhoneNumber(): Flow<PhoneNumberEntity?>

    @Query("SELECT * FROM phone_numbers WHERE isDefault = 1 AND isActive = 1 LIMIT 1")
    suspend fun getDefaultPhoneNumberSync(): PhoneNumberEntity?

    @Query("SELECT * FROM phone_numbers WHERE isPrimary = 1 AND isActive = 1 LIMIT 1")
    suspend fun getPrimaryPhoneNumber(): PhoneNumberEntity?

    @Query("SELECT * FROM phone_numbers WHERE id = :id")
    suspend fun getPhoneNumberById(id: String): PhoneNumberEntity?

    @Query("SELECT * FROM phone_numbers WHERE number = :number LIMIT 1")
    suspend fun getPhoneNumberByNumber(number: String): PhoneNumberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoneNumber(phoneNumber: PhoneNumberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(phoneNumber: PhoneNumberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(phoneNumbers: List<PhoneNumberEntity>)

    @Update
    suspend fun update(phoneNumber: PhoneNumberEntity)

    @Query("UPDATE phone_numbers SET isDefault = 0")
    suspend fun clearDefaultPhoneNumber()

    @Query("UPDATE phone_numbers SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultPhoneNumber(id: String)

    @Delete
    suspend fun delete(phoneNumber: PhoneNumberEntity)

    @Query("DELETE FROM phone_numbers")
    suspend fun deleteAll()
}
