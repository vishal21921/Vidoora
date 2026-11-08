package com.example.vidoora.core.network

import io.ktor.http.Url

/** Only compiled-in HTTPS segment delivery may receive the current viewer identity. */
object MediaEdgePolicy {
    private val segment=Regex("/api/v1/videos/[0-9a-f-]{36}/playback/[0-9]{1,3}p/segment_[0-9]{6}\\.ts")
    private fun sameOrigin(a: Url,b: Url)=a.protocol==b.protocol && a.host==b.host && a.port==b.port
    fun trusted(url: Url,edge: String=com.example.vidoora.BuildConfig.MEDIA_EDGE_BASE_URL): Boolean {
        if(edge.isEmpty()) return false
        val origin=Url(edge)
        return origin.protocol.name=="https" && sameOrigin(url,origin) && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
            url.parameters.isEmpty() && url.fragment.isEmpty() && segment.matches(url.encodedPath)
    }
    fun target(url: Url,edge: String=com.example.vidoora.BuildConfig.MEDIA_EDGE_BASE_URL): Url {
        if(edge.isEmpty() || !segment.matches(url.encodedPath)) return url
        val result=Url(edge.trimEnd('/')+url.encodedPath)
        require(trusted(result,edge))
        return result
    }
    fun apiOrEdge(url: Url): Boolean= sameOrigin(url,Url(ApiConfig.BASE_URL)) || trusted(url)
}
