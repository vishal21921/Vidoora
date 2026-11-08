package com.example.vidoora.core.network

import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SharedVideoLink(val videoId: String,val token: String?=null) {
    override fun toString()="SharedVideoLink(videoId=$videoId, token=<redacted>)"
    companion object {
        fun parseIncoming(text: String,extraToken: String?): SharedVideoLink? {
            val base=parse(text) ?: return null
            if(base.token!=null || extraToken==null)return base
            return parse("vidoora://watch/${base.videoId}#$extraToken")
        }
        fun parseShared(text: String): SharedVideoLink? = parse(text)?.takeIf { it.token!=null }
        fun parse(text: String): SharedVideoLink? = try {
            if(text.length>4600) null else {
                val uri=URI(text.trim())
                val web=uri.scheme=="https" && uri.host==com.example.vidoora.BuildConfig.SHARE_HOST && uri.host!="links.invalid"
                val custom=uri.scheme=="vidoora" && uri.host=="watch"
                val id=if(web && uri.path?.startsWith("/watch/")==true) uri.path.removePrefix("/watch/") else uri.path?.removePrefix("/") ?: ""
                val token=uri.rawFragment
                if((!custom && !web) || uri.port!=-1 || uri.userInfo!=null || uri.rawQuery!=null ||
                    UUID.fromString(id).toString()!=id.lowercase() ||
                    (token!=null && (token.length>4096 || !Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matches(token)))) null
                else SharedVideoLink(id.lowercase(),token)
            }
        } catch (_: Exception) { null }
    }
}

/** Memory only; never put capabilities in route arguments, preferences, logs or media URLs. */
@Singleton
class VideoShareAccess @Inject constructor() {
    private val tokens=linkedMapOf<String,String>()
    @Synchronized fun remember(link: SharedVideoLink) {
        if(link.token==null) tokens.remove(link.videoId) else {
            if(tokens.size>=50) tokens.remove(tokens.keys.first())
            tokens[link.videoId]=link.token
        }
    }
    @Synchronized fun token(videoId: String): String?=tokens[videoId]
    @Synchronized fun forget(videoId: String) { tokens.remove(videoId) }
    @Synchronized fun clear() { tokens.clear() }
}


