package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

internal class DanmakuDeviceBenchmarkConfig(arguments: Bundle) {
    val item = required(arguments, "item")
    val version = number(arguments, "version", 0).toInt()
    val url = required(arguments, "url")
    val anime = required(arguments, "anime")
    val episode = arguments.getString("danmaku_benchmark_episode") ?: "1"
    val videoStartMs = number(arguments, "start_ms", 60_000)
    val commentStartMs = number(arguments, "comment_start_ms", videoStartMs)
    val durationMs = number(arguments, "seconds", 300) * 1_000
    val warmupMs = number(arguments, "warmup_seconds", 20) * 1_000
    val autoReduce = arguments.getString("danmaku_benchmark_auto_reduce")?.toBooleanStrictOrNull() ?: false
    val audioMime = arguments.getString("danmaku_benchmark_audio_mime")
    val profiles = (arguments.getString("danmaku_benchmark_profiles") ?: "off,low,high,low,off")
        .split(',').map(String::trim).also { values -> require(values.isNotEmpty() && values.all { it in setOf("off", "low", "high") }) }

    init {
        require(version >= 0 && videoStartMs >= 0 && commentStartMs >= 0)
        require(durationMs in 5_000L..1_800_000L && warmupMs in 0L..120_000L)
    }

    private fun required(arguments: Bundle, key: String): String =
        arguments.getString("danmaku_benchmark_$key")?.takeIf { it.isNotBlank() }
            ?: error("Missing benchmark parameter: $key")

    private fun number(arguments: Bundle, key: String, default: Long): Long =
        arguments.getString("danmaku_benchmark_$key")?.toLongOrNull() ?: default
}

/** Persist anonymous technical metrics and stream each row to the instrumentation caller. */
internal class DanmakuDeviceBenchmarkReport(private val instrumentation: Instrumentation) {
    private val file = File(instrumentation.targetContext.filesDir, "danmaku-device-benchmark.jsonl")
    private var cpuStartMs = Process.getElapsedCpuTime()
    private var wallStartMs = SystemClock.elapsedRealtime()
    private var gcBaseline = Debug.getRuntimeStats()

    init { file.writeText("") }

    fun startProcessSample() {
        cpuStartMs = Process.getElapsedCpuTime()
        wallStartMs = SystemClock.elapsedRealtime()
        gcBaseline = Debug.getRuntimeStats()
    }

    fun write(value: JSONObject) {
        value.put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).put("monotonicNs", System.nanoTime())
        val line = value.toString()
        file.appendText(line + "\n")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
    }

    fun processSnapshot(): JSONObject {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val wallMs = SystemClock.elapsedRealtime() - wallStartMs
        val cpuMs = Process.getElapsedCpuTime() - cpuStartMs
        val gc = Debug.getRuntimeStats()
        fun gcDelta(key: String) = (gc[key]?.toLongOrNull() ?: 0) - (gcBaseline[key]?.toLongOrNull() ?: 0)
        return JSONObject().put("pssKb", memory.totalPss).put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("javaPssKb", memory.getMemoryStat("summary.java-heap")).put("nativePssKb", memory.getMemoryStat("summary.native-heap"))
            .put("cpuMs", cpuMs).put("wallMs", wallMs).put("singleCoreCpuPercent", if (wallMs == 0L) 0.0 else cpuMs * 100.0 / wallMs)
            .put("gcCount", gcDelta("art.gc.gc-count")).put("gcTimeMs", gcDelta("art.gc.gc-time"))
            .put("blockingGcCount", gcDelta("art.gc.blocking-gc-count")).put("blockingGcTimeMs", gcDelta("art.gc.blocking-gc-time"))
    }
}
