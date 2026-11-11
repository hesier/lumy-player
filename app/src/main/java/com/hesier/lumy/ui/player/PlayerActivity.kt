@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.hesier.lumy.util.DebugLog
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.hesier.lumy.ui.ThemedActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.BuildConfig
import com.hesier.lumy.tv.WatchNextManager
import com.hesier.lumy.tv.WatchNextResume
import com.hesier.lumy.ui.player.PlaybackStartupTiming.Stage
import com.hesier.lumy.ui.player.PlayerControls.Focus as ControlsFocus
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.readableMessage
import com.hesier.lumy.ui.widget.showMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

class PlayerActivity : ThemedActivity() {

    override val alwaysDark get() = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var server: MediaServer
    private lateinit var danmakuProbe: DanmakuProbe
    private lateinit var danmaku: PlayerDanmaku
    private lateinit var player: ExoPlayer
    private lateinit var codecs: LumyCodecSelector
    private lateinit var mediaItems: PlayerMediaItems
    private lateinit var device: DeviceProfile
    private lateinit var selection: PlaybackSelection
    private lateinit var tracks: PlayerTrackController
    private lateinit var menus: PlayerMenus
    private lateinit var controls: PlayerControls
    private lateinit var remote: PlayerRemoteInput
    private lateinit var seek: PlayerSeekController
    private lateinit var info: PlayerInfo

    private lateinit var detail: MediaDetail
    private val item: MediaItem get() = detail.item
    private var holdSpeedActive = false
    private val holdSpeedGesture by lazy {
        HoldSpeedGesture(
            scope = lifecycleScope,
            onTap = { togglePlay() },
            onHold = { beginHoldSpeed() },
            onRelease = { endHoldSpeed() },
        )
    }

    private val startupTiming = if (BuildConfig.DEBUG) PlaybackStartupTiming() else null

    private var request: PlaybackRequest? = null
    private var startJob: Job? = null
    private val playbackNotice by lazy { PlaybackNotice(binding.playbackNotice, lifecycleScope) }
    /** 网络错误已自动重试的次数，恢复播放后清零 */
    private var networkRetries = 0
    private var tracksApplied = false
    /**
     * 对外一律用“整部片”的绝对时间。多段文件时 = 前面各段时长之和 + 段内位置；
     * 转码 HLS 的时间轴可能从起播点开始计时，[timeBase] 记录起播段内的这段偏移。
     */
    private var timeBase = 0L
    private var startPart = 0
    private var hlsAligned = true
    /** Retry failed Dolby Vision decoding through ordinary HEVC (P5 may have incorrect colors). */
    private var doviHevcFallback = false
    private var firstFrameRendered = false
    private var doviWatchJob: Job? = null

    private val settings get() = LumyApp.instance.settings
    private lateinit var episodes: PlayerEpisodeController
    private lateinit var overlays: PlayerOverlayController
    private var progressJob: Job? = null

    private val offsets = SyncOffsets()
    private lateinit var refreshRate: RefreshRateMatcher
    private var frameRateMatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = LumyApp.instance
        intent.getStringExtra(EXTRA_SERVER_ID)?.let { sid ->
            val target = app.store.servers.firstOrNull { it.id == sid } ?: return finish()
            if (target.id != app.currentEntry?.id) app.switchTo(target.id)
        }
        server = app.currentServer() ?: return finish()
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        device = DeviceProfile(this, settings.allowSoftwareVideoDecoding)
        selection = PlaybackSelection(settings, device) { server }
        offsets.audioDelayMs = settings.audioDelayMs
        refreshRate = RefreshRateMatcher(this)
        val startupTransfer = startupTiming?.let(::PlaybackStartupTimingTransfer)
        val bundle = PlayerFactory.create(
            this, app.plex.http, server.playbackHeaders(), offsets,
            passthrough = settings.passthrough, allowSoftwareVideo = settings.allowSoftwareVideoDecoding,
            seekBackMs = settings.seekBackMs, seekForwardMs = settings.seekForwardMs,
            transferListener = startupTransfer,
            callFactory = startupTransfer?.callFactory(app.plex.http),
        )
        player = bundle.player
        codecs = bundle.codecs
        mediaItems = PlayerMediaItems(bundle.pgs, selection)
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        startupTiming?.let { player.addAnalyticsListener(PlaybackStartupTimingListener(it)) }
        binding.playerView.player = player
        binding.controls.visibility = View.GONE
        tracks = PlayerTrackController(this, bundle, binding, selection = selection, server = { server },
            onRestart = { start(positionMs, it) })
        info = PlayerInfo(this, binding, player, selection = selection, server = { server })
        danmaku = PlayerDanmaku(this, binding.playerView, player, positionMs = { positionMs },
            onMasterChanged = ::refreshDanmakuControls, onNotice = playbackNotice::showMessage)
        danmakuProbe = DanmakuProbe(binding.playerView, player, onProfileChanged = {
            danmaku.setTestActive(danmakuProbe.profile != 0)
        }) { positionMs }
        episodes = PlayerEpisodeController(this, binding, positionMs = { positionMs },
            onSeek = ::seekTo, onSwitch = ::switchTo, onFocusMoved = ::playFocusSound)
        overlays = PlayerOverlayController(this, binding, player, settings = settings,
            positionMs = { positionMs }, durationMs = { durationMs }, nextEpisode = { episodes.nextEpisode },
            onSeek = ::seekTo, onEnded = ::onEnded, onPlayNext = ::playNext)
        seek = PlayerSeekController(this, binding, settings, positionMs = { positionMs }, durationMs = { durationMs },
            bufferedMs = { bufferedMs }, onSeek = ::seekTo, needsRestart = ::needsTranscodeRestart,
            onInteraction = { controls.scheduleHide() })
        controls = PlayerControls(this, binding, tracks, episodes = episodes, overlays = overlays, seek = seek,
            durationMs = { durationMs }, isReady = { ::detail.isInitialized }, onShow = { holdSpeedGesture.cancel() })
        menus = PlayerMenus(this, binding, player, selection = selection, server = { server }, tracks = tracks,
            info = info, offsets = offsets, detail = { detail }, positionMs = { positionMs }, onSeek = ::seekTo,
            onRestart = { start(positionMs, it) }, onVersion = { v ->
                val position = positionMs
                selectVersion(v)
                start(position)
            })
        remote = PlayerRemoteInput(binding, controls, seek, episodes = episodes, overlays = overlays, settings = settings,
            holdSpeedGesture = holdSpeedGesture, isReady = { ::detail.isInitialized }, onTogglePlay = ::togglePlay,
            onMore = { menus.showMore() })
        load()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 点控制栏里的按钮也算操作，重新计时自动收起
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && controls.visible) controls.scheduleHide()
        return super.dispatchTouchEvent(ev)
    }

    private fun load() {
        val startupAttempt = startupTiming?.begin(withDetail = true)
        lifecycleScope.launch {
            val id = intent.getStringExtra(EXTRA_ID)!!
            detail = attempt { LumyApp.instance.withReconnect { it.detail(id) } }.getOrElse {
                return@launch showError(it.readableMessage())
            }
            startupTiming?.mark(startupAttempt, Stage.DETAIL_END)
            LumyApp.instance.currentServer()?.let { server = it }
            val v = detail.versions.firstOrNull { it.id == intent.getStringExtra("watch_next_version_id") }
                ?: detail.versions.getOrNull(intent.getIntExtra(EXTRA_VERSION, 0)) ?: detail.versions.firstOrNull()
                ?: return@launch showError(getString(R.string.no_playable_file))
            selectVersion(v)
            setResult(RESULT_OK, Intent().putExtra(RESULT_ITEM_ID, item.id))
            doviHevcFallback = intent.getBooleanExtra(EXTRA_DOVI_HEVC, false)
            intent.removeExtra(EXTRA_DOVI_HEVC)
            setupControls()
            start(intent.getLongExtra(EXTRA_START, 0), startupAttempt = startupAttempt)
            if (progressJob == null) startProgressReporting()
            episodes.bind(server, detail, intent.getStringExtra(EXTRA_PLAYLIST))
            overlays.bind(server, detail)
        }
    }

    private fun onEnded() {
        DebugLog.i(TAG, "ended at ${positionMs}/${durationMs} next=${episodes.nextEpisode?.id}")
        if (episodes.nextEpisode != null && settings.autoPlayNext && !overlays.upNextDismissed) {
            playNext()
        } else {
            report(PlaybackState.STOPPED)
            finish()
        }
    }

    /** 连续播放：在同一个播放器里换成下一集，省去重新创建解码器的时间 */
    private fun playNext() {
        val next = episodes.nextEpisode ?: return finish()
        DebugLog.i(TAG, "play next ${next.id} from ${positionMs}")
        switchTo(next, 0L)
    }

    /** 在同一个播放器里换成另一集（下一集 / 从剧集行选的） */
    private fun switchTo(next: MediaItem, startMs: Long) {
        startupTiming?.end(PlaybackStartupTiming.End.REPLACED)
        seek.cancel()
        playbackNotice.clear()
        danmaku.clear()
        danmakuProbe.reset()
        startJob?.cancel()
        report(PlaybackState.STOPPED)
        request?.let { stopSession(it) }
        request = null
        overlays.reset()
        episodes.reset()
        intent.removeExtra("watch_next_version_id")
        intent.putExtra(EXTRA_ID, next.id).putExtra(EXTRA_VERSION, 0).putExtra(EXTRA_START, startMs)
        controls.hide()
        offsets.subtitleDelayMs = 0
        frameRateMatched = false
        info.droppedFrames = 0
        binding.buffering.visibility = View.VISIBLE
        player.stop()
        load()
    }

    /** 剧集所属的剧，用来记住这部剧选的语言 */
    private val showId: String? get() = item.grandparentId?.takeIf { item.type == MediaType.EPISODE }

    /**
     * 选默认音轨和字幕，优先级：这个文件手动选过 > 这部剧记住的语言 > 设置里的语言偏好 > 服务器的选择。
     * 某一级没有符合的轨道时交给下一级。
     */
    private fun selectVersion(v: MediaVersion) {
        seek.cancel()
        playbackNotice.clear()
        danmakuProbe.reset()
        tracks.selector.selectSecondary(null)
        tracks.dual.clear()
        selection.selectVersion(v, showId)
        intent.putExtra("watch_next_version_id", v.id)
        danmaku.bind(server, detail, v, LumyApp.instance.currentEntry?.id.orEmpty())
        doviHevcFallback = false
        codecs.softwareVideoMime = null
        DebugLog.i(TAG, "tracks audio=${selection.audio?.title} subtitle=${selection.subtitle?.title}")
    }

    // ---- 起播 ----

    private fun start(positionMs: Long, playWhenReady: Boolean = true,
        startupAttempt: PlaybackStartupTiming.Attempt? = null) {
        val timingAttempt = startupAttempt?.takeIf { startupTiming?.isActive(it) == true } ?: startupTiming?.begin()
        seek.cancel()
        holdSpeedGesture.cancel()
        startJob?.cancel()
        tracks.prepare()
        // 换字幕 / 音轨 / 画质等重新起播时，旧会话要先报停止，否则服务端的“正在播放”里会一直留着它
        if (request != null) report(PlaybackState.STOPPED)
        request?.let { stopSession(it) }
        startupTiming?.mark(timingAttempt, Stage.PLAN_BEGIN)
        val p = selection.createPlan()
        startupTiming?.mark(timingAttempt, Stage.PLAN_END)
        DebugLog.i(TAG, "plan ${item.title} id=${item.id} [${selection.version.summary}] dovi=${selection.version.video?.doviProfile} audio=${selection.audio?.codec} sub=${selection.subtitle?.codec} -> ${p.mode} reasons=${p.reasons} subUnavailable=${p.subtitleUnavailable}")
        val req = PlaybackRequest(item, selection.version, p.mode, positionMs, UUID.randomUUID().toString(), selection.audio?.id, selection.subtitle?.id)
        request = req
        selection.plan = p
        doviWatchJob?.cancel()
        firstFrameRendered = false
        val doviProfile = selection.version.video?.doviProfile
        // Native Dolby Vision stays preferred until it is unavailable or has actually failed.
        codecs.hevcOnly = doviHevcFallback || doviProfile in settings.brokenDolbyVisionProfiles ||
            ((doviProfile == 5 || doviProfile == 7) && !device.hasDolbyVisionDecoder())
        if (doviProfile == 5 && codecs.hevcOnly) {
            Toast.makeText(this, R.string.player_dovi_compatibility, Toast.LENGTH_LONG).show()
        }
        tracksApplied = false
        timeBase = 0
        // 新的播放源准备好之前，播放器里还是旧的，位置以起播目标为准
        pendingSeekMs = positionMs
        startPart = selection.version.partAt(positionMs)
        hlsAligned = p.mode is PlaybackMode.Direct
        binding.state.root.visibility = View.GONE
        binding.buffering.visibility = View.VISIBLE
        if (p.subtitleUnavailable) Toast.makeText(this, R.string.subtitle_unavailable, Toast.LENGTH_LONG).show()

        playbackNotice.show("${item.id}:${selection.version.id}:${selection.version.index}", p,
            copiesVideo = (p.mode as? PlaybackMode.Transcode)?.copyVideo == true && server.session.kind != ServerKind.FNOS)
        startupTiming?.mark(timingAttempt, Stage.SOURCE_BEGIN)
        startJob = lifecycleScope.launch {
            // 转码时服务端按记住的音轨/字幕输出，所以先保存选择
            if (p.mode is PlaybackMode.Transcode) attempt {
                server.saveStreamSelection(req.version, req.audioStreamId, req.subtitleStreamId)
            }.onSuccess { startupTiming?.mark(timingAttempt, Stage.STREAM_SELECTION_SAVED) }
            if (request !== req) return@launch
            val source = attempt { server.playbackSource(req) }.getOrElse {
                if (request === req) {
                    val message = if (p.mode is PlaybackMode.Transcode) getString(
                        R.string.player_server_playback_error, it.readableMessage(), p.reasons.text(this@PlayerActivity),
                    ) else getString(R.string.player_error, it.readableMessage())
                    showError(message, retryAt = positionMs)
                }
                return@launch
            }
            // A decision request may finish after another seek, track change or episode switch.
            if (request !== req) return@launch
            startupTiming?.mark(timingAttempt, Stage.SOURCE_END)
            val media = mediaItems.build(req, source, timingAttempt)
            // 带内封字幕的转码流已从起播点开始，而且不能跳转
            val embedded = (p.mode as? PlaybackMode.Transcode)?.embedSubtitleId != null
            // fnOS serves a full VOD timeline but only generates segments near the requested start.
            val nativeHls = source.isHls && server.session.kind == ServerKind.FNOS
            if (nativeHls) hlsAligned = true
            player.setMediaItems(media, source.startPart,
                if ((source.isHls && !nativeHls) || embedded) 0 else source.startOffsetMs)
            player.setPlaybackSpeed(selection.speed)
            startupTiming?.mark(timingAttempt, Stage.PREPARE)
            player.prepare()
            player.playWhenReady = playWhenReady
        }
    }

    /** 第 [index] 段的播放器时间 0 对应的绝对时间 */
    private fun partBase(index: Int): Long =
        selection.version.partStartsMs.getOrElse(index) { 0L } + if (index == startPart) timeBase else 0L

    /**
     * 跳转到还没加载的那一段时，播放器在缓冲完成前报告的位置不可靠（实测会返回另一段的时长），
     * 连续快退时会算错目标。跳转期间直接用目标时间，就绪后再改回播放器的位置。
     */
    private var pendingSeekMs: Long? = null

    private val positionMs: Long
        get() = pendingSeekMs ?: (partBase(player.currentMediaItemIndex) + player.currentPosition)
    private val bufferedMs: Long get() = partBase(player.currentMediaItemIndex) + player.bufferedPosition
    private val durationMs: Long
        get() = selection.version.parts.sumOf { it.durationMs ?: 0 }
            .takeIf { server.session.kind == ServerKind.FNOS && it > 0 }
            ?: item.durationMs
            ?: selection.version.parts.sumOf { it.durationMs ?: 0 }.takeIf { it > 0 }
            ?: (player.duration.takeIf { it != C.TIME_UNSET }?.plus(partBase(player.currentMediaItemIndex)) ?: 0)

    private fun seekTo(target: Long) {
        seek.cancel()
        val t = target.coerceIn(0, (durationMs - 1_000).coerceAtLeast(0))
        val part = selection.version.partAt(t)
        val local = selection.version.localOffset(t, part)
        DebugLog.i(TAG, "seek target=$t part=$part local=$local")
        // 转码流只有已经转好的片段可以直接跳；跨段、跳到缓冲之后、或早于转码起点时，
        // 让服务器从目标位置重新转码（官方客户端也是这么做的），否则会请求到不存在的片段（404）
        if (needsTranscodeRestart(t)) {
            start(t)
            return
        }
        if (selection.plan?.mode !is PlaybackMode.Transcode && fallBackIfUnseekable(t)) return
        pendingSeekMs = t
        if (part == startPart) player.seekTo(part, local - timeBase) else player.seekTo(part, local)
    }

    private fun needsTranscodeRestart(target: Long): Boolean {
        val mode = selection.plan?.mode as? PlaybackMode.Transcode ?: return false
        // The player still exposes the old buffer while a replacement source is pending.
        if (startJob?.isActive == true) return true
        val part = selection.version.partAt(target)
        return mode.embedSubtitleId != null || part != player.currentMediaItemIndex || target > bufferedMs + 5_000 ||
            (part == startPart && selection.version.localOffset(target, part) < timeBase)
    }

    /**
     * 没有索引的 MKV（缺 Cues）等文件播放器不能跳转，续播和快进都会回到开头。
     * 这时改用服务器转码（视频能直接复制时只换封装），由服务器从目标位置开始。
     */
    private fun fallBackIfUnseekable(target: Long): Boolean {
        if (player.isCurrentMediaItemSeekable || selection.fellBack || !selection.policy.allowTranscode) return false
        DebugLog.i(TAG, "unseekable direct play, transcode from $target")
        selection.fellBack = true
        selection.fallbackReason = PlaybackIssue.Unseekable
        start(target)
        return true
    }

    // ---- 播放器回调 ----

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            DebugLog.i(TAG, "state=$state pos=${player.currentPosition}")
            binding.buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY || state == Player.STATE_IDLE) pendingSeekMs = null
            if (state == Player.STATE_READY) networkRetries = 0
            if (state == Player.STATE_READY && !hlsAligned) alignHlsTimeline()
            // 起播位置没生效（文件不能跳转，播放器从头开始）
            if (state == Player.STATE_READY && selection.plan?.mode is PlaybackMode.Direct) {
                val startMs = request?.startMs ?: 0
                if (startMs > 0 && positionMs < startMs - 5_000) fallBackIfUnseekable(startMs)
            }
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            menus.onPlayingChanged(isPlaying)
            if (player.playbackState == Player.STATE_READY) report(if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED)
            if (isPlaying) controls.scheduleHide() else holdSpeedGesture.cancel()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // 换到下一段文件：轨道对象都是新的，要重新按音轨/字幕选择覆盖
            tracksApplied = false
        }

        override fun onTracksChanged(tracks: Tracks) {
            tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEachIndexed { i, g ->
                val f = g.getTrackFormat(0)
                DebugLog.i(TAG, "audio#$i id=${f.id} lang=${f.language} label=${f.label} ${f.sampleMimeType} ch=${f.channelCount} support=${g.getTrackSupport(0)} selected=${g.isSelected}")
            }
            tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { g ->
                val f = g.getTrackFormat(0)
                val decoders = f.sampleMimeType?.let(device::decoderSummary)
                DebugLog.i(TAG, "video ${f.sampleMimeType} codecs=${f.codecs} ${f.width}x${f.height} support=${g.getTrackSupport(0)} decoders=[$decoders]")
            }
            if (!tracksApplied && !tracks.isEmpty) {
                tracksApplied = true
                this@PlayerActivity.tracks.apply()
            }
            matchFrameRate()
        }

        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            doviWatchJob?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            // A wedged codec may report release errors after the compatibility page has opened.
            if (isFinishing || isDestroyed) return
            val current = positionMs
            DebugLog.i(TAG, "error ${error.errorCodeName} at $current mode=${selection.plan?.mode} retries=$networkRetries: ${error.cause?.message}")
            val failedFormat = (error as? ExoPlaybackException)?.rendererFormat ?: player.videoFormat
            if (selection.plan?.mode is PlaybackMode.Direct && !codecs.hevcOnly &&
                dolbyVisionProfile(failedFormat?.codecs) == 5 && error.isVideoDecodeError()
            ) {
                retryDolbyVisionWithHevc(5)
                return
            }
            if (selection.plan?.mode is PlaybackMode.Direct && settings.allowSoftwareVideoDecoding &&
                codecs.softwareVideoMime == null && failedFormat != null &&
                SoftwareVideoPolicy.supports(failedFormat) && error.isVideoDecodeError() &&
                info.videoDecoder?.startsWith("LumyFFmpeg") != true
            ) {
                codecs.softwareVideoMime = failedFormat.sampleMimeType
                DebugLog.i(TAG, "retry software video ${failedFormat.sampleMimeType} at $current")
                start(current)
                return
            }
            val network = error.isNetworkError()
            if (network && networkRetries < MAX_NETWORK_RETRIES) {
                // 断网、地址变化、转码会话失效：重连后从当前位置重新起播，不改用转码
                networkRetries++
                reconnectAndStart(current)
            } else if (network && error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                // 连不上服务器时转码也一样连不上，直接提示
                showError(getString(R.string.player_network_error), retryAt = current)
            } else if (selection.plan?.mode is PlaybackMode.Direct && !selection.fellBack && selection.policy.allowTranscode) {
                // 硬解失败、格式识别错误等：自动改用服务器转码，从当前位置继续。
                // 视频解码失败要重新编码；封装、解析等问题只换封装，服务器几乎没有负担
                selection.fellBack = true
                selection.videoDecodeFailed = error.isVideoDecodeError()
                selection.fallbackReason = if (selection.videoDecodeFailed) PlaybackIssue.VideoDecodeFailed else PlaybackIssue.DirectPlayFailed
                start(current)
            } else {
                val hint = if (!selection.policy.allowTranscode && selection.plan?.reasons?.isNotEmpty() == true) {
                    getString(R.string.direct_only_failed, selection.plan!!.reasons.text(this@PlayerActivity))
                } else {
                    error.errorCodeName + (error.cause?.message?.let { " · $it" } ?: "")
                }
                showError(hint, retryAt = current)
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            DebugLog.i(TAG, "video decoder $decoderName init ${initializationDurationMs}ms")
            info.videoDecoder = decoderName
            firstFrameRendered = false
        }

        // 解码器初始化事件早于 player.videoFormat 更新，格式要从这里拿
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && !codecs.hevcOnly && !firstFrameRendered) watchDolbyVision(format.codecs)
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            DebugLog.i(TAG, "audio decoder $decoderName")
            info.audioDecoder = decoderName
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
            info.passthroughEncoding = config.encoding.takeUnless { Util.isEncodingLinearPcm(it) }
            if (info.passthroughEncoding != null) holdSpeedGesture.cancel()
            DebugLog.i(TAG, "audio track encoding=${config.encoding} ${config.sampleRate}Hz channelConfig=${config.channelConfig} passthrough=${info.passthroughEncoding != null}")
            // 直通的音轨无法变速，播放器会按 1x 播放，这里同步一下避免界面显示的倍速不对
            if (info.passthroughEncoding != null && selection.speed != 1f) {
                selection.speed = 1f
                player.setPlaybackSpeed(selection.speed)
            }
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            info.droppedFrames += droppedFrames
        }
    }

    /** Retry a stalled native decoder after buffered media rules out a slow start. */
    private fun watchDolbyVision(codecsString: String?) {
        doviWatchJob?.cancel()
        doviWatchJob = lifecycleScope.launch {
            var waited = 0L
            while (waited < DOVI_MAX_WAIT_MS) {
                delay(1_000)
                waited += 1_000
                if (firstFrameRendered) return@launch
                if (waited >= DOVI_FIRST_FRAME_TIMEOUT_MS && player.bufferedPosition - player.currentPosition >= 5_000) break
            }
            if (firstFrameRendered) return@launch
            val profile = dolbyVisionProfile(codecsString)
            DebugLog.i(TAG, "dolby vision decoder stuck (profile=$profile, waited ${waited}ms)")
            retryDolbyVisionWithHevc(profile)
        }
    }

    private fun dolbyVisionProfile(codecsString: String?): Int? =
        codecsString?.let { DOVI_PROFILE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: selection.version.video?.doviProfile

    private fun retryDolbyVisionWithHevc(profile: Int?) {
        doviWatchJob?.cancel()
        DebugLog.i(TAG, "retry Dolby Vision profile=$profile with HEVC compatibility decoding")
        profile?.let { settings.brokenDolbyVisionProfiles = settings.brokenDolbyVisionProfiles + it }
        if (profile == null) {
            doviHevcFallback = true
            start(positionMs)
            return
        }
        // A stalled vendor codec can stay wedged when the source changes in the same player.
        // Recreate the page; its initial selection reads the locally remembered failed profile.
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtras(intent)
                .putExtra(EXTRA_VERSION, detail.versions.indexOf(selection.version).coerceAtLeast(0))
                .putExtra(EXTRA_START, positionMs)
                .putExtra(EXTRA_DOVI_HEVC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * Plex 转码流的时间轴可能从 0 开始（相对起播点），也可能覆盖整部影片。
     * 用 HLS 时长和影片时长对比判断，后者需要手动 seek 到起播点。
     */
    private fun alignHlsTimeline() {
        hlsAligned = true
        val start = selection.version.localOffset(request?.startMs ?: return, startPart)
        if (start <= 0) return
        if ((selection.plan?.mode as? PlaybackMode.Transcode)?.embedSubtitleId != null) {
            // MKV 转码流从起播点开始：Emby 实测保留原片时间戳，时间轴也可能从 0 开始，按就绪时的位置判断
            if (abs(player.currentPosition - start) > 15_000) timeBase = start
            return
        }
        val hlsDuration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val partDuration = selection.version.parts[startPart].durationMs ?: item.durationMs ?: return
        if (abs(hlsDuration - (partDuration - start)) < 15_000) {
            timeBase = start
        } else {
            player.seekTo(start)
        }
    }

    // ---- 音轨 / 字幕 ----

    /** 把 Plex 的流对应到播放器的轨道：内封轨道按顺序对应，外挂字幕按 id 对应 */
    private fun setupControls(restoreFocus: Boolean = false) {
        menus.refreshControls(::togglePlay, onDanmaku = {
            if (danmakuProbe.profile != 0) { danmakuProbe.reset(); danmaku.setTestActive(false) }
            danmaku.showMenu()
        }, restoreFocus = restoreFocus)
    }

    private fun refreshDanmakuControls() {
        if (::detail.isInitialized && selection.ready) setupControls(restoreFocus = true)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        controls.show(ControlsFocus.BUTTONS)
    }

    private fun beginHoldSpeed() {
        if (!player.isPlaying || controls.visible || overlays.hasFocus) return
        if (info.passthroughEncoding != null) {
            Toast.makeText(this, R.string.speed_unavailable_passthrough, Toast.LENGTH_LONG).show()
            return
        }
        holdSpeedActive = true
        player.setPlaybackSpeed(settings.holdSpeed)
        seek.showHoldSpeed(settings.holdSpeed)
    }

    private fun endHoldSpeed() {
        if (!holdSpeedActive) return
        holdSpeedActive = false
        player.setPlaybackSpeed(selection.speed)
        seek.clearHint()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::controls.isInitialized) controls.onWindowFocusChanged(hasFocus)
        if (!hasFocus) holdSpeedGesture.cancel()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (::remote.isInitialized && remote.handle(event)) || super.dispatchKeyEvent(event)

    // ---- 刷新率 ----

    private fun matchFrameRate() {
        if (frameRateMatched || !settings.matchFrameRate) return
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0 } ?: return
        frameRateMatched = true
        if (refreshRate.match(fps)) {
            // HDMI 切换模式时电视会黑屏一两秒，先暂停，免得错过画面
            player.pause()
            lifecycleScope.launch {
                delay(MODE_SWITCH_PAUSE_MS)
                player.play()
            }
        }
    }

    // ---- 进度上报 ----

    private fun startProgressReporting() {
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) {
                    val v = player.videoFormat
                    DebugLog.i(TAG, "stats pos=${positionMs / 1000}s video=${v?.width}x${v?.height} ${v?.sampleMimeType} audio=${player.audioFormat?.sampleMimeType} dropped=${info.droppedFrames} buffer=${(player.bufferedPosition - player.currentPosition) / 1000}s")
                }
                if (player.isPlaying) report(PlaybackState.PLAYING)
            }
        }
    }

    private fun report(state: PlaybackState) {
        val req = request ?: return
        if (!::detail.isInitialized) return
        val pos = positionMs
        val dur = durationMs
        val s = server
        val curItem = item
        val next = episodes.nextEpisode
        val watchNextSession = WatchNextManager.captureSession(s)
        val appContext = applicationContext
        val resume = WatchNextResume(req.version.id, intent.getStringExtra(EXTRA_PLAYLIST))
        LumyApp.instance.report {
            WatchNextManager.onPlaybackProgress(
                context = appContext,
                server = s,
                item = curItem,
                positionMs = pos,
                durationMs = dur,
                nextEpisode = next,
                state = state,
                session = watchNextSession,
                resume = resume,
            )
        }
        LumyApp.instance.report { s.reportProgress(req, state, pos, dur) }
    }

    private fun stopSession(req: PlaybackRequest) {
        LumyApp.instance.report { server.stopPlayback(req) }
    }

    /** 地址可能变了：先确认 / 重新选可用地址，再用新的服务器实例起播 */
    private fun reconnectAndStart(positionMs: Long) {
        binding.state.root.visibility = View.GONE
        binding.buffering.visibility = View.VISIBLE
        val req = request
        lifecycleScope.launch {
            // 网络刚断开时立即重试大多还是失败，稍等一下
            if (networkRetries > 1) delay(NETWORK_RETRY_DELAY_MS)
            val app = LumyApp.instance
            if (attempt { app.reconnect() }.getOrDefault(false)) app.currentServer()?.let { server = it }
            // 等待期间用户可能已经换了集或改了设置，以那次起播为准
            if (request === req) start(positionMs)
        }
    }

    private fun PlaybackException.isVideoDecodeError(): Boolean {
        if (errorCode !in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) return false
        // 认不出是哪个渲染器时按视频处理，宁可多转一次码也不要再失败一次
        val mime = (this as? ExoPlaybackException)?.rendererFormat?.sampleMimeType ?: return true
        return !MimeTypes.isAudio(mime) && !MimeTypes.isText(mime)
    }

    private fun PlaybackException.isNetworkError() = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> true
        else -> false
    }

    private fun showError(message: String, retryAt: Long? = null) {
        startupTiming?.end(PlaybackStartupTiming.End.ERROR)
        holdSpeedGesture.cancel()
        binding.buffering.visibility = View.GONE
        binding.controlsHeader.visibility = View.GONE
        binding.controls.visibility = View.GONE
        binding.state.root.visibility = View.VISIBLE
        if (retryAt != null) {
            binding.state.showMessage(getString(R.string.player_error, message), getString(R.string.retry)) {
                networkRetries = 0
                reconnectAndStart(retryAt)
            }
        } else {
            binding.state.showMessage(getString(R.string.player_error, message))
        }
    }

    override fun onStop() {
        super.onStop()
        if (!::player.isInitialized) return
        startupTiming?.end(PlaybackStartupTiming.End.CANCELLED)
        seek.cancel()
        holdSpeedGesture.cancel()
        startJob?.cancel()
        // TV 上离开播放页就结束播放：上报停止（服务端据此记录进度/标记已看）并释放转码
        if (::detail.isInitialized) report(PlaybackState.STOPPED)
        request?.let { stopSession(it) }
        request = null
        if (::danmaku.isInitialized) danmaku.release()
        if (::danmakuProbe.isInitialized) danmakuProbe.release()
        if (::episodes.isInitialized) episodes.reset()
        if (::overlays.isInitialized) overlays.reset()
        player.release()
        tracks.release()
        controls.release()
        info.release()
        refreshRate.restore()
        finish()
    }

    companion object {
        const val RESULT_ITEM_ID = "played_item_id"
        private const val EXTRA_ID = "id"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_START = "start"
        private const val EXTRA_PLAYLIST = "playlist"
        private const val EXTRA_SERVER_ID = "server_id"
        private const val EXTRA_DOVI_HEVC = "dovi_hevc_compatibility"
        private const val TAG = "LumyPlayer"
        private const val MODE_SWITCH_PAUSE_MS = 2_500L
        private const val MAX_NETWORK_RETRIES = 3
        private const val DOVI_FIRST_FRAME_TIMEOUT_MS = 8_000L
        private const val DOVI_MAX_WAIT_MS = 30_000L
        /** dvhe.07.06 / dvh1.08.06 → 7 / 8 */
        private val DOVI_PROFILE = Regex("""^dv[ah][e1v]\.(\d+)""")
        private const val NETWORK_RETRY_DELAY_MS = 3_000L
        /** [playlistId]：在这个播放列表里播放，剧集行显示播放列表，按它的顺序连播 */
        fun intent(context: Context, itemId: String, versionIndex: Int, startMs: Long, playlistId: String? = null, serverId: String? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, itemId)
                .putExtra(EXTRA_VERSION, versionIndex)
                .putExtra(EXTRA_START, startMs)
                .apply {
                    playlistId?.let { putExtra(EXTRA_PLAYLIST, it) }
                    serverId?.let { putExtra(EXTRA_SERVER_ID, it) }
                }
    }
}
