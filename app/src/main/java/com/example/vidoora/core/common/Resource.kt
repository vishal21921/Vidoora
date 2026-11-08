package com.example.vidoora.core.common

sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String, val code: String? = null) : Resource<Nothing>()
    object Loading : Resource<Nothing>()
}
