package com.vidoora.backend.modules.playback

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Serializable data class LibraryPage(val items: List<LibraryVideo>,val nextCursor: String?)
@Serializable data class EditVideoRequest(val title: String,val description: String?=null)

internal data class LibraryCursor(val createdAt: Instant,val id: UUID,val scope: String?=null) {
    fun encode(): String=Base64.getUrlEncoder().withoutPadding().encodeToString(("$createdAt|$id"+(scope?.let { "|$it" } ?: "")).toByteArray(Charsets.UTF_8))
    companion object {
        fun parse(value: String): LibraryCursor {
            try {
                require(value.length in 1..256)
                val parts=String(Base64.getUrlDecoder().decode(value),Charsets.UTF_8).split('|')
                require(parts.size in 2..3)
                val scope=parts.getOrNull(2); require(scope==null || Regex("[0-9a-f]{64}").matches(scope))
                val instant=Instant.parse(parts[0])
                require(instant.epochSecond in -62135596800L..253402300799L)
                val uuid=UUID.fromString(parts[1]); require(uuid.toString()==parts[1])
                return LibraryCursor(instant,uuid,scope)
            } catch (_: Exception) { throw IllegalArgumentException("Invalid library cursor") }
        }
    }
}

internal object LibraryViews {
    fun scope(owner: UUID,search: String,sort: String,state: String,sharing: String,folder: String="ALL"): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((if(folder=="ALL") "$owner|$sort|$state|$sharing|$search" else "folder-v1|$owner|$sort|$state|$sharing|$folder|$search").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}

