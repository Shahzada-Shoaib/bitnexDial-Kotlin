package com.bitnextechnologies.bitnexdial.data.local.converter

import androidx.room.TypeConverter
import com.bitnextechnologies.bitnexdial.data.local.entity.ContactEmailEntity
import com.bitnextechnologies.bitnexdial.data.local.entity.ContactPhoneNumberEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room type converters for complex types
 */
class Converters {
    private val gson = Gson()

    // String List converters
    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return gson.toJson(value ?: emptyList<String>())
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    // Phone Numbers List converters
    @TypeConverter
    fun fromPhoneNumberList(value: List<ContactPhoneNumberEntity>?): String {
        return gson.toJson(value ?: emptyList<ContactPhoneNumberEntity>())
    }

    @TypeConverter
    fun toPhoneNumberList(value: String): List<ContactPhoneNumberEntity> {
        val type = object : TypeToken<List<ContactPhoneNumberEntity>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    // Email List converters
    @TypeConverter
    fun fromEmailList(value: List<ContactEmailEntity>?): String {
        return gson.toJson(value ?: emptyList<ContactEmailEntity>())
    }

    @TypeConverter
    fun toEmailList(value: String): List<ContactEmailEntity> {
        val type = object : TypeToken<List<ContactEmailEntity>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

}
