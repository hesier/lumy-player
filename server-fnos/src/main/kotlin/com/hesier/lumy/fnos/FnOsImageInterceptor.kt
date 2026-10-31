package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.ServerKind
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** A network interceptor rechecks credentials on every redirect, including during server switches. */
class FnOsImageInterceptor(private val currentServer: () -> MediaServer?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = currentServer()
        val base = current?.session?.baseUrl?.toHttpUrl()
        val ownImage = base != null && request.url.scheme == base.scheme &&
            request.url.host == base.host && request.url.port == base.port &&
            request.url.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/Items/") &&
            request.url.encodedPath.contains("/Images/")
        // OkHttp strips Authorization across origins, but does not strip these nonstandard headers.
        val signed = request.newBuilder().removeHeader("X-Emby-Authorization").removeHeader("X-Emby-Token")
            .apply {
                if (current?.session?.kind == ServerKind.FNOS && ownImage) {
                    current.playbackHeaders().forEach { (key, value) -> header(key, value) }
                }
            }.build()
        return chain.proceed(signed)
    }
}
