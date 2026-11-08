package com.example.vidoora.feature.downloads
@kotlinx.serialization.Serializable data class DownloadQuotaDto(val limit: Int,val used: Int,val reserved: Int,val remaining: Int)
