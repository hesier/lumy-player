package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.BuildConfig
import com.hesier.lumy.net.danmaku.DanmakuCredentials
import com.hesier.lumy.net.danmaku.DanmakuEndpoint
import com.hesier.lumy.net.danmaku.DanmakuException
import com.hesier.lumy.net.danmaku.DanmakuFailure
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Online requests never inherit the media server's authentication interceptors. */
class DanmakuServices(context: Context, private val settings: DanmakuSourceSettings = Settings(context).danmakuSource) {
    val endpoint: DanmakuEndpoint get() = when (settings.onlineSource) {
        DanmakuOnlineSource.OFFICIAL -> official()
        DanmakuOnlineSource.CUSTOM -> custom()
    }

    fun official(): DanmakuEndpoint {
        val id = BuildConfig.DANDANPLAY_APP_ID
        val secret = BuildConfig.DANDANPLAY_APP_SECRET
        if (id.isBlank() || secret.isBlank()) throw DanmakuException(DanmakuFailure.UNAVAILABLE)
        return DanmakuEndpoint("https://api.dandanplay.net", true, DanmakuCredentials(id, secret))
    }

    fun custom(): DanmakuEndpoint {
        if (settings.customUrl.isBlank()) throw DanmakuException(DanmakuFailure.UNAVAILABLE)
        return DanmakuEndpoint(settings.customUrl)
    }

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build()
    }
}
