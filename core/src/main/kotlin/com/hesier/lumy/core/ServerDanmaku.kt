package com.hesier.lumy.core

/** An authenticated, read-only sidecar resource supplied by a server plugin. */
data class ServerDanmaku(val url: String, val headers: Map<String, String>, val format: ServerDanmakuFormat) {
    override fun toString() = "ServerDanmaku(format=$format)"
}

enum class ServerDanmakuFormat { XML, EMBY_JSON }
