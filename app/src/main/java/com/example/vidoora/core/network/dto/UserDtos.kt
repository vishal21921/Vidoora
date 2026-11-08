package com.example.vidoora.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val isSuspended: Boolean = false,
    val role: String = "USER",
    val createdAt: String,
    val publicId: String = ""
) { val canUpload: Boolean get() = !isSuspended && role in setOf("CREATOR","ADMIN") }
