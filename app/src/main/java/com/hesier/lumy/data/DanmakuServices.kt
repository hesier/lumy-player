package com.hesier.lumy.data

import android.content.Context
import android.util.Base64
import com.hesier.lumy.BuildConfig
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
        return try {
            val config = String(Base64.decode(BuildConfig.DANMAKU_PROXY_CONFIG, Base64.DEFAULT), Charsets.UTF_8)
                .split('\n')
            if (config.size != 2) throw DanmakuException(DanmakuFailure.UNAVAILABLE)
            DanmakuEndpoint(config[0], official = true, proxyToken = config[1])
        } catch (error: IllegalArgumentException) {
            throw DanmakuException(DanmakuFailure.UNAVAILABLE)
        }
    }

    fun custom(): DanmakuEndpoint {
        if (settings.customUrl.isBlank()) throw DanmakuException(DanmakuFailure.UNAVAILABLE)
        return DanmakuEndpoint(settings.customUrl)
    }

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) addInterceptor(DanmakuDiagnostics) }
            .retryOnConnectionFailure(true).build()
    }
}
