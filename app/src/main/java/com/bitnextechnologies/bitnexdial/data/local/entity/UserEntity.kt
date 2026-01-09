package com.bitnextechnologies.bitnexdial.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val email: String,
    val name: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
    val authToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiresAt: Long? = null,
    val sessionId: String? = null,
    val sipUsername: String? = null,
    val sipPassword: String? = null,
    val sipDomain: String? = null,
    val wssServer: String? = null,
    val wssPort: String? = null,
    val fcmToken: String? = null,
    val isLoggedIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
