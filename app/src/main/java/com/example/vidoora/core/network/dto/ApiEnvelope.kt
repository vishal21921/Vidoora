package com.example.vidoora.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiErrorDto? = null
)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String
)
