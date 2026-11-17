@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.*
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

internal class FeaturePlaybackFixture(val instrumentation: Instrumentation) {
    private val app get() = LumyApp.instance
    private val context get() = instrumentation.targetContext
    private val field = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
    private val original = field.get(app)
    private val originalId = app.store.currentId
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val savedPreferences = preferences.all.toMap()
    private val entry = SavedServer("playback-feature-test", ServerSession(ServerKind.PLEX, "fixture", "Fixture", "http://127.0.0.1", ""))
    private val file = File(context.cacheDir, "playback-features.mkv")
    val requests = CopyOnWriteArrayList<PlaybackRequest>()
    val stopped = CopyOnWriteArrayList<String>()
    val reports = CopyOnWriteArrayList<Pair<PlaybackRequest, PlaybackState>>()
    var failLimited = false
    var failOriginal = false
    var hold = false
    private var pending: Continuation<PlaybackSource>? = null
    private var pendingRequest: PlaybackRequest? = null
    var latestProgress = 0L
    var holdDetail = false
    private var pendingDetail: Continuation<MediaDetail>? = null
    lateinit var activity: PlayerActivity
    val player get() = activity.readPlaybackField("player") as ExoPlayer
    val binding get() = activity.readPlaybackField("binding") as ActivityPlayerBinding
    val selection get() = activity.readPlaybackField("selection") as PlaybackSelection
    private val version by lazy {
        MediaVersion("v", 0, "mkv", 600, VideoStream("h264", 320, 180, 24f, 8, "High", HdrType.NONE, null, 500),
            emptyList(), emptyList(), listOf(MediaPart("p", "fixture", "mkv", file.length(), 20_000)))
    }

    fun open(waitReady: Boolean = true) {
        instrumentation.context.assets.open("dual-subtitles/fixture.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        val server = Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> entry.session
                "getEmbedsSubtitles", "getSupportsFavorites" -> false
                "detail" -> if (holdDetail) {
                    @Suppress("UNCHECKED_CAST")
                    pendingDetail = args!!.last() as Continuation<MediaDetail>
                    COROUTINE_SUSPENDED
                } else MediaDetail(item(args!![0] as String), versions = listOf(version))
                "playbackSource" -> source(args!!)
                "playbackHeaders" -> emptyMap<String, String>()
                "reportProgress" -> { reports += (args!![0] as PlaybackRequest) to (args[1] as PlaybackState); Unit }
                "stopPlayback" -> { stopped += (args!![0] as PlaybackRequest).sessionId; Unit }
                "saveStreamSelection" -> Unit
                "nextEpisode" -> item("second").takeIf { (args!![0] as MediaItem).id == "first" }
                "children", "containerItems" -> listOf(item("first"), item("second"))
                "danmakuResource" -> null
                "imageUrl" -> ""
                else -> error("Unexpected playback fixture call: ${method.name}")
            }
        } as MediaServer
        main {
            app.store.add(entry)
            app.store.currentId = entry.id
            field.set(app, server)
            app.settings.watchNextEnabled = false
            app.settings.autoPlayNext = true
            app.settings.matchFrameRate = false
            app.settings.defaultQuality = 0
            app.settings.setAllowTranscode(entry.id, true)
            app.settings.danmaku.enabled = false
        }
        instrumentation.setInTouchMode(false)
        activity = instrumentation.startActivitySync(PlayerActivity.intent(context, "first", 0, 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as PlayerActivity
        if (waitReady) await("fixture ready") { player.isPlaying }
    }

    private fun item(id: String) = MediaItem(id, MediaType.EPISODE, id, parentId = "season", grandparentId = "show",
        durationMs = if (latestProgress > 0) 200_000 else 20_000, viewOffsetMs = latestProgress)

    private fun source(args: Array<out Any?>): Any {
        val request = args[0] as PlaybackRequest
        requests += request
        if ((request.mode is PlaybackMode.Transcode && failLimited) || (request.mode is PlaybackMode.Direct && failOriginal)) {
            throw IOException("Fixture source failure")
        }
        if (hold) {
            @Suppress("UNCHECKED_CAST")
            pending = args.last() as Continuation<PlaybackSource>
            pendingRequest = request
            hold = false
            return COROUTINE_SUSPENDED
        }
        return mediaSource(request)
    }

    private fun mediaSource(request: PlaybackRequest) = PlaybackSource(listOf(file.toURI().toString()), false, emptyList(), 0, request.startMs)

    fun release() {
        pending?.resumeWith(Result.success(mediaSource(checkNotNull(pendingRequest))))
        pending = null
    }

    fun invoke(name: String, vararg args: Any) {
        val method = activity.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        method.invoke(activity, *args)
    }

    fun close() {
        if (::activity.isInitialized) {
            main { activity.finishAndRemoveTask(); release() }
            await("fixture destroyed") { activity.isDestroyed }
            main {
                pendingDetail?.resumeWith(Result.success(MediaDetail(item("first"), versions = listOf(version))))
                pendingDetail = null
            }
            runBlocking { withContext(Dispatchers.Main) { app.awaitPendingReports() } }
        }
        main {
            app.store.remove(entry.id)
            app.store.currentId = originalId
            field.set(app, original)
        }
        val editor = preferences.edit().clear()
        savedPreferences.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
        } }
        editor.commit()
    }

    fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
            SystemClock.sleep(40)
        }
    }

    fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
