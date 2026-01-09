package com.bitnextechnologies.bitnexdial.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_numbers")
data class BlockedNumberEntity(
    @PrimaryKey
    val id: String,
    val phoneNumber: String,
    val contactName: String? = null,
    val reason: String? = null,
    val blockedAt: Long = System.currentTimeMillis()
)
