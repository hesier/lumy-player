package com.hesier.lumy.util

import android.content.Context
import android.util.Log
import com.hesier.lumy.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 有些电视盒子/投影仪（如极米）关闭了系统日志，logcat 读不到任何东西。
 * debug 包同时写一份到应用私有目录，用 `adb shell run-as com.hesier.lumy.debug cat files/debug.log` 查看。
 */
object DebugLog {
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        file = File(context.filesDir, "debug.log").apply { if (length() > 512 * 1024) delete() }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        val f = file ?: return
        runCatching { f.appendText("${time.format(Date())} $tag: $message\n") }
    }
}
