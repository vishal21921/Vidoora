package com.example.vidoora.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequestDto(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String,
    val creatorInviteCode: String = ""
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String
)

@Serializable
data class RefreshTokenRequestDto(
    val refreshToken: String
)

@Serializable
data class TokenResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val user: UserDto
)
