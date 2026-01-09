package com.bitnextechnologies.bitnexdial.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "phone_numbers")
data class PhoneNumberEntity(
    @PrimaryKey
    val id: String,
    val userId: String? = null,
    val number: String,
    val formatted: String? = null,
    val type: String? = null,
    val label: String? = null,
    val callerIdName: String? = null,
    val smsEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val isActive: Boolean = true,
    val isPrimary: Boolean = false,
    val isDefault: Boolean = false,
    val sipUsername: String? = null,
    val sipPassword: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
