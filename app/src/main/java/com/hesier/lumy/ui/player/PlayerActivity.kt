@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import com.hesier.lumy.util.DebugLog
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.hesier.lumy.ui.ThemedActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.Marker
import com.hesier.lumy.core.MarkerType
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.SubtitleRule
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.core.TrackLanguage
import com.hesier.lumy.core.TrackPicker
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.data.ManualTracks
import com.hesier.lumy.data.Settings
import com.hesier.lumy.data.VideoScale
import com.hesier.lumy.data.SkipMode
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.formatClock
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.ui.widget.StepperPanel
import com.hesier.lumy.ui.widget.CardAdapter
import com.hesier.lumy.ui.widget.StartAlignedLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.ui.widget.addActionButton
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.clipToRoundedBackground
import com.hesier.lumy.ui.widget.readableMessage
import com.hesier.lumy.ui.widget.showMessage
import coil.load
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.widget.AssSubtitleView
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
    private lateinit var player: ExoPlayer
    private lateinit var ass: AssHandler
    private lateinit var codecs: LumyCodecSelector
    private lateinit var device: DeviceProfile
    private lateinit var planner: PlaybackPlanner

    private lateinit var detail: MediaDetail
    private val item: MediaItem get() = detail.item
    private lateinit var version: MediaVersion
    private var audio: AudioStream? = null
    private var subtitle: SubtitleStream? = null
    private var quality: Quality = Quality.Original
    private var speed = 1f

    private var request: PlaybackRequest? = null
    private var plan: Plan? = null
    /** 直接播放失败后改用转码，同一版本内保持 */
    private var fellBack = false
    /** 回退转码是因为本机视频解码失败：设备声明支持也不能让服务器原样复制视频流 */
    private var videoDecodeFailed = false
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

    private var controlsJob: Job? = null
    private var hintJob: Job? = null
    private var scrubJob: Job? = null
    private var scrubTarget: Long? = null
    private var playPauseIcon: ImageView? = null

    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    /** 当前音轨直通时的码流编码（C.ENCODING_*），解码成 PCM 输出时为 null */
    private var passthroughEncoding: Int? = null
    private var droppedFrames = 0
    /** 杜比视界解码器解不出画面，这个版本改用 HEVC 解码器播放基础层 */
    private var doviBaseLayer = false
    private var firstFrameRendered = false
    private var doviWatchJob: Job? = null

    private val settings get() = LumyApp.instance.settings
    private val policy get() = PlanPolicy(settings.allowTranscode, settings.allowSubtitleBurn, settings.displaySupportsHdr)
    /** 剧集的下一集，加载完详情后异步获取 */
    private var nextEpisode: MediaItem? = null
    /** 已自动跳过的标记，避免往回拖时被反复跳过 */
    private val autoSkipped = HashSet<Marker>()
    private var upNextDismissed = false
    private var upNextJob: Job? = null
    private var progressJob: Job? = null

    private val offsets = SyncOffsets()
    private var videoScale = VideoScale.FIT
    private val subtitlePadding get() = Settings.SUBTITLE_POSITIONS[settings.subtitlePosition]
    private lateinit var refreshRate: RefreshRateMatcher
    private var frameRateMatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = LumyApp.instance
        server = app.currentServer() ?: return finish()
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        device = DeviceProfile(this)
        planner = PlaybackPlanner(device)
        offsets.audioDelayMs = settings.audioDelayMs
        refreshRate = RefreshRateMatcher(this)
        val bundle = PlayerFactory.create(this, app.plex.http, server.playbackHeaders(), offsets, passthrough = settings.passthrough)
        player = bundle.player
        ass = bundle.ass
        codecs = bundle.codecs
        quality = Quality.presets.getOrElse(settings.defaultQuality) { Quality.Original }
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        binding.playerView.player = player
        videoScale = settings.videoScale
        binding.playerView.resizeMode = videoScale.resizeMode()
        binding.playerView.subtitleView?.apply {
            val background = if (settings.subtitleBackground) SUBTITLE_BACKGROUND else Color.TRANSPARENT
            setStyle(CaptionStyleCompat(Color.WHITE, background, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f * settings.subtitleScale)
            setBottomPaddingFraction(subtitlePadding)
            // libass 的特效字幕画在这一层上，和视频画面同尺寸
            addView(AssSubtitleView(this@PlayerActivity, ass))
        }
        binding.controls.visibility = View.GONE
        binding.upNextFrame.clipToRoundedBackground()
        binding.upNextPlay.setOnClickListener { playNext() }
        binding.upNextCancel.setOnClickListener { dismissUpNext() }
        setupTouch()
        load()
    }

    /** 触屏：点画面显示 / 收起控制栏，在进度条上点按或拖动跳转 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        // 关掉了 PlayerView 自带的控制器，它不再消费触摸事件，点击监听收不到，只能自己识别点击
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (binding.state.root.visibility == View.VISIBLE || !::detail.isInitialized) return false
                if (controlsVisible) hideControls() else showControls(ControlsFocus.NONE)
                return true
            }
        })
        binding.playerView.setOnTouchListener { _, e -> taps.onTouchEvent(e) }
        // 控制栏空白处吃掉点击，免得点按钮时稍微偏一点就穿透到画面，把控制栏收起
        binding.controls.isClickable = true
        binding.seek.setOnTouchListener { _, e ->
            val duration = durationMs
            if (duration <= 0) return@setOnTouchListener true
            val bar = binding.seekBar
            val fraction = ((e.x - bar.left) / bar.width).coerceIn(0f, 1f)
            when (e.actionMasked) {
                // 拖动时只更新目标时间，松手再 seek，和遥控器拖动一样
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    scrubJob?.cancel()
                    scrubTarget = (duration * fraction).toLong()
                    updateProgress()
                    scheduleHideControls()
                }
                MotionEvent.ACTION_UP -> commitScrub()
                MotionEvent.ACTION_CANCEL -> {
                    scrubTarget = null
                    updateProgress()
                }
            }
            true
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 点控制栏里的按钮也算操作，重新计时自动收起
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && controlsVisible) scheduleHideControls()
        return super.dispatchTouchEvent(ev)
    }

    private fun load() {
        lifecycleScope.launch {
            val id = intent.getStringExtra(EXTRA_ID)!!
            detail = attempt { LumyApp.instance.withReconnect { it.detail(id) } }.getOrElse {
                return@launch showError(it.readableMessage())
            }
            LumyApp.instance.currentServer()?.let { server = it }
            val v = detail.versions.getOrNull(intent.getIntExtra(EXTRA_VERSION, 0)) ?: detail.versions.firstOrNull()
                ?: return@launch showError(getString(R.string.no_playable_file))
            selectVersion(v)
            setupControls()
            start(intent.getLongExtra(EXTRA_START, 0))
            if (progressJob == null) startProgressReporting()
            startMarkerWatch()
            loadSeasonEpisodes()
            // 播放列表按顺序连播；剧集接着播下一集
            val queue = intent.getStringArrayListExtra(EXTRA_QUEUE)
            nextEpisode = when {
                queue != null -> queue.getOrNull(queue.indexOf(item.id) + 1)
                    ?.let { next -> attempt { server.detail(next).item }.getOrNull() }
                item.type == MediaType.EPISODE -> attempt { server.nextEpisode(item) }.getOrNull()
                else -> null
            }
        }
    }

    private fun onEnded() {
        DebugLog.i(TAG, "ended at ${positionMs}/${durationMs} next=${nextEpisode?.id}")
        if (nextEpisode != null && settings.autoPlayNext && !upNextDismissed) {
            playNext()
        } else {
            report(PlaybackState.STOPPED)
            finish()
        }
    }

    /** 连续播放：在同一个播放器里换成下一集，省去重新创建解码器的时间 */
    private fun playNext() {
        val next = nextEpisode ?: return finish()
        DebugLog.i(TAG, "play next ${next.id} from ${positionMs}")
        switchTo(next, 0L)
    }

    /** 在同一个播放器里换成另一集（下一集 / 从剧集行选的） */
    private fun switchTo(next: MediaItem, startMs: Long) {
        report(PlaybackState.STOPPED)
        request?.let { stopSession(it) }
        request = null
        hideOverlays()
        intent.putExtra(EXTRA_ID, next.id).putExtra(EXTRA_VERSION, 0).putExtra(EXTRA_START, startMs)
        seasonEpisodes = emptyList()
        hideControls()
        nextEpisode = null
        autoSkipped.clear()
        upNextDismissed = false
        offsets.subtitleDelayMs = 0
        frameRateMatched = false
        droppedFrames = 0
        binding.buffering.visibility = View.VISIBLE
        player.stop()
        load()
    }

    /** 剧集所属的剧，用来记住这部剧选的语言 */
    private val showId: String? get() = item.grandparentId?.takeIf { item.type == MediaType.EPISODE }

    /** 手动选择按服务器分开记 */
    private val memoryServerId: String get() = LumyApp.instance.currentEntry?.id.orEmpty()

    /**
     * 选默认音轨和字幕，优先级：这个文件手动选过 > 这部剧记住的语言 > 设置里的语言偏好 > 服务器的选择。
     * 某一级没有符合的轨道时交给下一级。
     */
    private fun selectVersion(v: MediaVersion) {
        version = v
        fellBack = false
        videoDecodeFailed = false
        doviBaseLayer = false
        audio = v.audio.firstOrNull { it.selected } ?: v.audio.firstOrNull()
        subtitle = v.subtitles.firstOrNull { it.selected }
        val memory = LumyApp.instance.trackMemory
        val serverId = memoryServerId
        memory.file(serverId, v.part.id)?.let { m ->
            val a = m.audioId?.let { id -> v.audio.firstOrNull { it.id == id } }
            val s = m.subtitleId?.let { id -> v.subtitles.firstOrNull { it.id == id } }
            // 轨道 id 对不上（文件被替换、重新扫描过）时当作没选过
            if ((m.audioId == null || a != null) && (m.subtitleId == null || s != null)) {
                a?.let { audio = it }
                subtitle = s
                DebugLog.i(TAG, "tracks (manual) audio=${audio?.title} subtitle=${subtitle?.title}")
                return
            }
        }
        val show = showId
        listOfNotNull(show?.let { memory.showAudio(serverId, it) }, settings.audioLanguage)
            .firstNotNullOfOrNull { TrackPicker.audio(v.audio, it) }
            ?.let { audio = it }
        val heard = audio?.let { TrackLanguage.of(it.language, it.title) }
        listOfNotNull(show?.let { memory.showSubtitle(serverId, it) }, settings.subtitleRule)
            .firstNotNullOfOrNull { TrackPicker.subtitle(v.subtitles, it, heard) }
            ?.let { subtitle = it.stream }
        DebugLog.i(TAG, "tracks audio=${audio?.title} subtitle=${subtitle?.title}")
    }

    // ---- 起播 ----

    private fun start(positionMs: Long) {
        request?.let { stopSession(it) }
        val p = planner.plan(version, audio, subtitle, quality, policy, forceTranscode = fellBack, videoDecodeFailed = videoDecodeFailed)
        DebugLog.i(TAG, "plan ${item.title} id=${item.id} [${version.summary}] dovi=${version.video?.doviProfile} audio=${audio?.codec} sub=${subtitle?.codec} -> ${p.mode} reasons=${p.reasons} subUnavailable=${p.subtitleUnavailable}")
        val req = PlaybackRequest(item, version, p.mode, positionMs, UUID.randomUUID().toString(), audio?.id, subtitle?.id)
        request = req
        plan = p
        doviWatchJob?.cancel()
        val doviProfile = version.video?.doviProfile
        // 没有杜比视界解码器时 Media3 只会把 P8 等退回 HEVC，P7 会直接初始化失败再去转码，这里提前改播基础层
        codecs.baseLayerOnly = doviBaseLayer || doviProfile in settings.brokenDolbyVisionProfiles ||
            (doviProfile == 7 && !device.hasDolbyVisionDecoder())
        tracksApplied = false
        timeBase = 0
        // 新的播放源准备好之前，播放器里还是旧的，位置以起播目标为准
        pendingSeekMs = positionMs
        startPart = version.partAt(positionMs)
        hlsAligned = p.mode is PlaybackMode.Direct
        binding.state.root.visibility = View.GONE
        binding.buffering.visibility = View.VISIBLE
        if (p.subtitleUnavailable) Toast.makeText(this, R.string.subtitle_unavailable, Toast.LENGTH_LONG).show()

        lifecycleScope.launch {
            // 转码时服务端按记住的音轨/字幕输出，所以先保存选择
            if (p.mode is PlaybackMode.Transcode) attempt { server.saveStreamSelection(version, audio?.id, subtitle?.id) }
            if (request !== req) return@launch
            val source = server.playbackSource(req)
            // 多段文件放进播放列表，段与段之间无缝衔接；外挂字幕属于第一段
            val media = source.urls.mapIndexed { i, url ->
                androidx.media3.common.MediaItem.Builder()
                    .setUri(url)
                    .setMediaId("${item.id}#$i")
                    .apply { if (source.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                    .setSubtitleConfigurations(
                        if (i != 0) emptyList() else source.externalSubtitles.map {
                            SubtitleConfiguration.Builder(Uri.parse(it.url))
                                .setId(EXTERNAL_PREFIX + it.streamId)
                                .setMimeType(subtitleMime(it.codec))
                                .setLanguage(it.language)
                                .setLabel(it.label)
                                .build()
                        },
                    )
                    .build()
            }
            player.setMediaItems(media, source.startPart, if (source.isHls) 0 else source.startOffsetMs)
            player.setPlaybackSpeed(speed)
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun encodingName(encoding: Int) = when (encoding) {
        C.ENCODING_AC3 -> "AC3"
        C.ENCODING_E_AC3 -> "E-AC3"
        C.ENCODING_E_AC3_JOC -> "E-AC3 Atmos"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        else -> "encoding $encoding"
    }

    private fun subtitleMime(codec: String) = when (codec.lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /** 第 [index] 段的播放器时间 0 对应的绝对时间 */
    private fun partBase(index: Int): Long =
        version.partStartsMs.getOrElse(index) { 0L } + if (index == startPart) timeBase else 0L

    /**
     * 跳转到还没加载的那一段时，播放器在缓冲完成前报告的位置不可靠（实测会返回另一段的时长），
     * 连续快退时会算错目标。跳转期间直接用目标时间，就绪后再改回播放器的位置。
     */
    private var pendingSeekMs: Long? = null

    private val positionMs: Long
        get() = pendingSeekMs ?: (partBase(player.currentMediaItemIndex) + player.currentPosition)
    private val bufferedMs: Long get() = partBase(player.currentMediaItemIndex) + player.bufferedPosition
    private val durationMs: Long
        get() = item.durationMs
            ?: version.parts.sumOf { it.durationMs ?: 0 }.takeIf { it > 0 }
            ?: (player.duration.takeIf { it != C.TIME_UNSET }?.plus(partBase(player.currentMediaItemIndex)) ?: 0)

    private fun seekTo(target: Long) {
        val t = target.coerceIn(0, (durationMs - 1_000).coerceAtLeast(0))
        val part = version.partAt(t)
        val local = version.localOffset(t, part)
        DebugLog.i(TAG, "seek target=$t part=$part local=$local")
        // 转码流只有已经转好的片段可以直接跳；跨段、跳到缓冲之后、或早于转码起点时，
        // 让服务器从目标位置重新转码（官方客户端也是这么做的），否则会请求到不存在的片段（404）
        val transcoding = plan?.mode is PlaybackMode.Transcode
        // 视频已在重新编码时，渲染不了的字幕总是交给服务器烧录（见 PlaybackPlanner）
        val reencoding = (plan?.mode as? PlaybackMode.Transcode)?.copyVideo == false
        val outsideTranscoded = part != player.currentMediaItemIndex || t > bufferedMs + 5_000 ||
            (part == startPart && local < timeBase)
        if (transcoding && outsideTranscoded) {
            start(t)
            return
        }
        if (!transcoding && fallBackIfUnseekable(t)) return
        pendingSeekMs = t
        if (part == startPart) player.seekTo(part, local - timeBase) else player.seekTo(part, local)
    }

    /**
     * 没有索引的 MKV（缺 Cues）等文件播放器不能跳转，续播和快进都会回到开头。
     * 这时改用服务器转码（视频能直接复制时只换封装），由服务器从目标位置开始。
     */
    private fun fallBackIfUnseekable(target: Long): Boolean {
        if (player.isCurrentMediaItemSeekable || fellBack || !settings.allowTranscode) return false
        DebugLog.i(TAG, "unseekable direct play, transcode from $target")
        fellBack = true
        Toast.makeText(this, R.string.player_unseekable_fallback, Toast.LENGTH_LONG).show()
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
            if (state == Player.STATE_READY && plan?.mode is PlaybackMode.Direct) {
                val startMs = request?.startMs ?: 0
                if (startMs > 0 && positionMs < startMs - 5_000) fallBackIfUnseekable(startMs)
            }
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPauseIcon?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (player.playbackState == Player.STATE_READY) report(if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED)
            if (isPlaying) scheduleHideControls()
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
                applyTrackSelection()
            }
            matchFrameRate()
        }

        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            doviWatchJob?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = positionMs
            DebugLog.i(TAG, "error ${error.errorCodeName} at $current mode=${plan?.mode} retries=$networkRetries: ${error.cause?.message}")
            val network = error.isNetworkError()
            if (network && networkRetries < MAX_NETWORK_RETRIES) {
                // 断网、地址变化、转码会话失效：重连后从当前位置重新起播，不改用转码
                networkRetries++
                reconnectAndStart(current)
            } else if (network && error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                // 连不上服务器时转码也一样连不上，直接提示
                showError(getString(R.string.player_network_error), retryAt = current)
            } else if (plan?.mode is PlaybackMode.Direct && !fellBack && settings.allowTranscode) {
                // 硬解失败、格式识别错误等：自动改用服务器转码，从当前位置继续。
                // 视频解码失败要重新编码；封装、解析等问题只换封装，服务器几乎没有负担
                fellBack = true
                videoDecodeFailed = error.isVideoDecodeError()
                Toast.makeText(this@PlayerActivity, R.string.player_fallback, Toast.LENGTH_LONG).show()
                start(current)
            } else {
                val hint = if (!settings.allowTranscode && plan?.reasons?.isNotEmpty() == true) {
                    getString(R.string.direct_only_failed, plan!!.reasons.text(this@PlayerActivity))
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
            videoDecoder = decoderName
            firstFrameRendered = false
        }

        // 解码器初始化事件早于 player.videoFormat 更新，格式要从这里拿
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && !codecs.baseLayerOnly && !firstFrameRendered) watchDolbyVision(format.codecs)
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            DebugLog.i(TAG, "audio decoder $decoderName")
            audioDecoder = decoderName
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
            passthroughEncoding = config.encoding.takeUnless { Util.isEncodingLinearPcm(it) }
            DebugLog.i(TAG, "audio track encoding=${config.encoding} ${config.sampleRate}Hz channelConfig=${config.channelConfig} passthrough=${passthroughEncoding != null}")
            // 直通的音轨无法变速，播放器会按 1x 播放，这里同步一下避免界面显示的倍速不对
            if (passthroughEncoding != null && speed != 1f) {
                speed = 1f
                player.setPlaybackSpeed(speed)
            }
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@PlayerActivity.droppedFrames += droppedFrames
        }
    }

    /**
     * 杜比视界解码器声明支持、实际可能一直不出画面（极米解 P7 时一直转圈，最后闪退）。
     * 数据已经缓冲够了还没有第一帧，就认为解码器卡住，改用 HEVC 解码器播放基础层，并记住这台设备的这个 profile。
     * 只在网络慢、数据没到时多等一会儿；P5 没有可用的基础层，不处理。
     */
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
            val profile = codecsString?.let { DOVI_PROFILE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: version.video?.doviProfile
            DebugLog.i(TAG, "dolby vision decoder stuck (profile=$profile, waited ${waited}ms), retry with base layer")
            if (profile == 5) return@launch
            profile?.let { settings.brokenDolbyVisionProfiles = settings.brokenDolbyVisionProfiles + it }
            if (profile == null) {
                doviBaseLayer = true
                start(positionMs)
                return@launch
            }
            // 卡住的解码器释放不掉，在同一个播放器里换源会一直卡着。重开播放页换一个新的播放器
            // （release 有超时，不会被旧的卡住），新页面读到上面记下的 profile，直接用基础层
            // 只带 extras：原 intent 的 NEW_TASK 等标志会让系统把正在结束的任务调到前台，而不是新建页面
            startActivity(
                Intent(this@PlayerActivity, PlayerActivity::class.java)
                    .putExtras(intent)
                    .putExtra(EXTRA_START, positionMs)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * Plex 转码流的时间轴可能从 0 开始（相对起播点），也可能覆盖整部影片。
     * 用 HLS 时长和影片时长对比判断，后者需要手动 seek 到起播点。
     */
    private fun alignHlsTimeline() {
        hlsAligned = true
        val start = version.localOffset(request?.startMs ?: return, startPart)
        if (start <= 0) return
        val hlsDuration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val partDuration = version.parts[startPart].durationMs ?: item.durationMs ?: return
        if (abs(hlsDuration - (partDuration - start)) < 15_000) {
            timeBase = start
        } else {
            player.seekTo(start)
        }
    }

    // ---- 音轨 / 字幕 ----

    /** 把 Plex 的流对应到播放器的轨道：内封轨道按顺序对应，外挂字幕按 id 对应 */
    private fun applyTrackSelection() {
        val mode = plan?.mode ?: return
        val groups = player.currentTracks.groups
        val params = player.trackSelectionParameters.buildUpon()

        if (mode is PlaybackMode.Direct) {
            val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            matchGroup(audioGroups, audio?.index, version.audio.indexOf(audio))?.let {
                params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
            }
        }

        val s = subtitle
        val burned = (mode as? PlaybackMode.Transcode)?.burnSubtitleId
        if (s == null || burned == s.id || plan?.subtitleUnavailable == true) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val group = if (s.external) {
                textGroups.firstOrNull { it.getTrackFormat(0).id?.contains(EXTERNAL_PREFIX + s.id) == true }
            } else {
                val embedded = textGroups.filter { it.getTrackFormat(0).id?.contains(EXTERNAL_PREFIX) != true }
                matchGroup(embedded, s.index, version.subtitles.filter { !it.external }.indexOf(s))
            }
            if (group != null) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            } else if (mode is PlaybackMode.Direct && policy.allowTranscode && policy.allowSubtitleBurn) {
                // 播放器没认出这条字幕，用户允许时改由服务器烧录
                fellBack = true
                start(positionMs)
                return
            } else {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                Toast.makeText(this, R.string.subtitle_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        player.trackSelectionParameters = params.build()
    }

    /**
     * 把 Plex 的流对应到播放器的轨道组。播放器里的轨道顺序不一定是文件里的顺序（实测 MKV 会把 AC3 排在 DTS 前面），
     * 不能按位置对应。MKV/MP4 的轨道 id 是从 1 开始的轨道号，等于 Plex 的流序号 + 1；
     * 对不上（其他封装）时才退回按位置对应。
     */
    private fun matchGroup(groups: List<Tracks.Group>, streamIndex: Int?, position: Int): Tracks.Group? {
        if (streamIndex != null) {
            val trackNumber = (streamIndex + 1).toString()
            groups.firstOrNull { it.getTrackFormat(0).id == trackNumber }?.let { return it }
        }
        return groups.getOrNull(position)
    }

    private fun pickAudio() {
        val options = version.audio.map { Option(it.title, it.channelsLabel, it == audio) }
        OptionsPanel(this, getString(R.string.player_audio), options) { i ->
            audio = version.audio[i]
            saveSelection()
            rememberAudio()
            if (plan?.mode is PlaybackMode.Direct) applyTrackSelection() else start(positionMs)
        }.show()
    }

    private fun pickSubtitle() {
        val transcoding = plan?.mode is PlaybackMode.Transcode
        // 视频已在重新编码时，渲染不了的字幕总是交给服务器烧录（见 PlaybackPlanner）
        val reencoding = (plan?.mode as? PlaybackMode.Transcode)?.copyVideo == false
        val options = listOf(Option(getString(R.string.subtitles_off), selected = subtitle == null)) +
            version.subtitles.map {
                val note = when {
                    device.canRenderSubtitle(it, transcoding) -> null
                    policy.allowTranscode && (policy.allowSubtitleBurn || reencoding) -> getString(R.string.subtitle_burn_note)
                    else -> getString(R.string.subtitle_not_available_note)
                }
                Option(it.title, listOfNotNull(it.codec.uppercase(), if (it.forced) getString(R.string.subtitle_forced) else null, note).joinToString(" · "), it == subtitle)
            }
        OptionsPanel(this, getString(R.string.player_subtitles), options) { i ->
            subtitle = if (i == 0) null else version.subtitles[i - 1]
            saveSelection()
            rememberSubtitle()
            // 播放方式不变（例如直接播放切换内封字幕）就只切轨道，否则重新起播
            val newPlan = planner.plan(version, audio, subtitle, quality, policy, forceTranscode = fellBack, videoDecodeFailed = videoDecodeFailed)
            if (newPlan.mode == plan?.mode) applyTrackSelection() else start(positionMs)
        }.show()
    }

    /** 记住这个文件选的轨道，以及这部剧的音轨语言（认不出语言时只记文件） */
    private fun rememberAudio() {
        rememberFile()
        val show = showId ?: return
        val lang = audio?.let { TrackLanguage.of(it.language, it.title) } ?: return
        LumyApp.instance.trackMemory.rememberShowAudio(memoryServerId, show, lang)
    }

    /** 记住这个文件选的轨道，以及这部剧的字幕：关闭 / 强制字幕 / 某种语言 */
    private fun rememberSubtitle() {
        rememberFile()
        val show = showId ?: return
        val s = subtitle
        val rule = if (s == null) {
            SubtitleRule.Off
        } else {
            val lang = TrackLanguage.of(s.language, s.title) ?: return
            if (s.forced) SubtitleRule.ForcedOnly(lang) else SubtitleRule.Language(lang)
        }
        LumyApp.instance.trackMemory.rememberShowSubtitle(memoryServerId, show, rule)
    }

    private fun rememberFile() {
        LumyApp.instance.trackMemory.rememberFile(memoryServerId, version.part.id, ManualTracks(audio?.id, subtitle?.id))
    }

    private fun saveSelection() {
        val v = version
        val a = audio?.id
        val s = subtitle?.id
        LumyApp.instance.appScope.launch { attempt { server.saveStreamSelection(v, a, s) } }
    }

    private fun pickQuality() {
        val options = Quality.presets.map {
            when (it) {
                Quality.Original -> Option(getString(R.string.quality_original), getString(R.string.quality_original_detail), quality == it)
                is Quality.Limited -> Option(
                    getString(R.string.quality_transcode, "${it.height}p", "%.0f".format(it.bitrateKbps / 1000f)),
                    getString(R.string.quality_transcode_detail),
                    quality == it,
                )
            }
        }
        OptionsPanel(this, getString(R.string.player_quality), options) { i ->
            quality = Quality.presets[i]
            start(positionMs)
        }.show()
    }

    private fun pickVersion() {
        val options = detail.versions.map {
            Option(it.summary, it.bitrateKbps?.let { b -> "%.1f Mbps · %s".format(b / 1000f, it.container.orEmpty().uppercase()) }, it == version)
        }
        OptionsPanel(this, getString(R.string.version_title), options) { i ->
            val pos = positionMs
            selectVersion(detail.versions[i])
            start(pos)
        }.show()
    }

    private fun pickSpeed() {
        // 只有当前音轨真的在直通时才不能变速（开了直通，AAC 等功放不支持的格式仍在本机解码）
        if (passthroughEncoding != null) {
            Toast.makeText(this, R.string.speed_unavailable_passthrough, Toast.LENGTH_LONG).show()
            return
        }
        val options = SPEEDS.map { Option(if (it == 1f) getString(R.string.speed_normal) else "${it}x", selected = it == speed) }
        OptionsPanel(this, getString(R.string.player_speed), options) { i ->
            speed = SPEEDS[i]
            player.setPlaybackSpeed(speed)
        }.show()
    }

    // ---- 控制栏 ----

    private fun setupControls() {
        binding.title.text = if (item.type == MediaType.EPISODE) item.showTitle ?: item.title else item.title
        binding.subtitle.text = when (item.type) {
            MediaType.EPISODE -> listOfNotNull(
                item.seasonNumber?.let { s -> item.episodeNumber?.let { e -> getString(R.string.episode_code, s, e) } },
                item.title,
            ).joinToString("  ·  ")
            else -> item.year?.toString()
        }
        val row = binding.buttons
        row.removeAllViews()
        val playButton = row.addActionButton(R.drawable.ic_pause, "") { togglePlay() }
        playButton.findViewById<TextView>(R.id.label).visibility = View.GONE
        playPauseIcon = playButton.findViewById(R.id.icon)
        if (version.subtitles.isNotEmpty()) row.addActionButton(R.drawable.ic_subtitles, getString(R.string.player_subtitles)) { pickSubtitle() }
        if (version.audio.size > 1) row.addActionButton(R.drawable.ic_audio, getString(R.string.player_audio)) { pickAudio() }
        if (detail.chapters.size > 1) row.addActionButton(R.drawable.ic_list, getString(R.string.player_chapters)) { pickChapter() }
        if (settings.allowTranscode) row.addActionButton(R.drawable.ic_hd, getString(R.string.player_quality)) { pickQuality() }
        row.addActionButton(R.drawable.ic_speed, getString(R.string.player_speed)) { pickSpeed() }
        row.addActionButton(R.drawable.ic_more, getString(R.string.player_more)) { showMore() }
        binding.seek.setOnFocusChangeListener { _, hasFocus ->
            binding.seekBar.animate().scaleY(if (hasFocus) 1.6f else 1f).setDuration(120).start()
        }
    }

    private val controlsVisible get() = binding.controls.visibility == View.VISIBLE

    private enum class ControlsFocus { SEEK, BUTTONS, NONE }

    private fun showControls(focusSeek: Boolean = true) =
        showControls(if (focusSeek) ControlsFocus.SEEK else ControlsFocus.NONE)

    private fun showControls(target: ControlsFocus) {
        if (!controlsVisible) {
            // 控制栏和跳过按钮不同时出现；控制栏收起后由标记检查重新显示
            hideSkip()
            binding.controls.visibility = View.VISIBLE
            binding.controls.alpha = 0f
            binding.controls.animate().alpha(1f).setDuration(150).start()
            // 控制栏出现时把字幕抬到它上方，避免重叠
            binding.playerView.subtitleView?.setBottomPaddingFraction(maxOf(subtitlePadding, SUBTITLE_PADDING_WITH_CONTROLS))
            when (target) {
                ControlsFocus.SEEK -> binding.seek.requestFocus()
                // 默认落在播放/暂停按钮上
                ControlsFocus.BUTTONS -> binding.buttons.getChildAt(0)?.requestFocus()
                ControlsFocus.NONE -> Unit
            }
            controlsJob?.cancel()
            controlsJob = lifecycleScope.launch {
                while (isActive) {
                    updateProgress()
                    delay(500)
                }
            }
        }
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsJob?.cancel()
        binding.controls.visibility = View.GONE
        binding.episodes.visibility = View.GONE
        binding.playerView.subtitleView?.setBottomPaddingFraction(subtitlePadding)
        commitScrub()
    }

    private var hideJob: Job? = null

    private fun scheduleHideControls() {
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(5_000)
            if (player.isPlaying && scrubTarget == null) hideControls()
        }
    }

    private fun updateProgress() {
        val duration = durationMs.takeIf { it > 0 } ?: return
        val shown = scrubTarget ?: positionMs
        binding.seekBar.progress = (shown * 10_000 / duration).toInt()
        binding.seekBar.secondaryProgress = (bufferedMs * 10_000 / duration).toInt()
        binding.position.text = formatClock(positionMs)
        binding.remaining.text = "-" + formatClock(duration - shown)
        binding.scrub.visibility = if (scrubTarget != null) View.VISIBLE else View.INVISIBLE
        binding.scrub.text = scrubTarget?.let { formatClock(it) }
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        showControls(focusSeek = false)
    }

    /** 拖动进度条：按住方向键越久步长越大，松手 0.8 秒后才真正 seek，避免每一步都请求网络 */
    private fun scrub(forward: Boolean, repeat: Int) {
        val step = when {
            repeat > 20 -> 120_000L
            repeat > 8 -> 60_000L
            repeat > 2 -> 30_000L
            else -> 10_000L
        }
        val base = scrubTarget ?: positionMs
        scrubTarget = (base + if (forward) step else -step).coerceIn(0, durationMs)
        updateProgress()
        scrubJob?.cancel()
        scrubJob = lifecycleScope.launch {
            delay(800)
            commitScrub()
        }
        scheduleHideControls()
    }

    private fun commitScrub() {
        scrubJob?.cancel()
        val target = scrubTarget ?: return
        scrubTarget = null
        seekTo(target)
        updateProgress()
    }

    /** 提示还在显示时连续快进/快退，累计显示总跳转量（按两次右键显示 +60 秒） */
    private var skipTotalMs = 0L

    private fun skip(deltaMs: Long) {
        seekTo(positionMs + deltaMs)
        if (binding.hint.visibility != View.VISIBLE) skipTotalMs = 0
        skipTotalMs += deltaMs
        val total = skipTotalMs
        val amount = formatSkip(abs(total))
        showHint(if (total < 0) "‹ -$amount" else "+$amount ›")
    }

    private fun formatSkip(ms: Long): String = getString(R.string.skip_seconds, (ms / 1000).toInt())

    private fun showHint(text: String) {
        binding.hint.text = text
        binding.hint.visibility = View.VISIBLE
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(900)
            binding.hint.visibility = View.GONE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.state.root.visibility == View.VISIBLE || !::detail.isInitialized) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) togglePlay()
                return true
            }
            // 菜单键：直接打开“更多”（字幕延迟、音频延迟、版本、播放信息）
            KeyEvent.KEYCODE_MENU -> {
                if (!down) showMore()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) skip(30_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) skip(-10_000)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (controlsVisible) {
                    if (!down) hideControls()
                    return true
                }
                if (binding.upNext.visibility == View.VISIBLE) {
                    if (!down) dismissUpNext()
                    return true
                }
            }
        }
        if (!controlsVisible) {
            // 只拦截方向键和确认键；返回键等交给系统，否则无法退出播放
            if (event.keyCode !in HIDDEN_CONTROL_KEYS) return super.dispatchKeyEvent(event)
            // 跳过按钮 / 下一集卡片有焦点：确认键点它，左右在卡片按钮间移动，上下呼出控制栏
            val confirm = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
            val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if ((overlayFocused && confirm) || (binding.upNext.hasFocus() && horizontal)) {
                return super.dispatchKeyEvent(event)
            }
            if (down) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> togglePlay()
                    KeyEvent.KEYCODE_DPAD_LEFT -> skip(-10_000)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> skip(30_000)
                    // 下键直接到按钮行（更常用），上键到进度条
                    KeyEvent.KEYCODE_DPAD_DOWN -> showControls(ControlsFocus.BUTTONS)
                    else -> showControls(ControlsFocus.SEEK)
                }
            }
            return true
        }
        scheduleHideControls()
        if (handleEpisodeRowKey(event)) return true
        if (binding.seek.hasFocus()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) scrub(event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down) if (scrubTarget != null) commitScrub() else togglePlay()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- 播放信息（排查 4K 卡顿用） ----

    private var infoJob: Job? = null

    private fun toggleInfo() {
        if (binding.info.visibility == View.VISIBLE) {
            infoJob?.cancel()
            binding.info.visibility = View.GONE
            return
        }
        binding.info.visibility = View.VISIBLE
        infoJob = lifecycleScope.launch {
            while (isActive) {
                binding.info.text = infoText()
                delay(1_000)
            }
        }
    }

    private fun infoText(): String {
        val p = plan
        val modeLabel = when (val m = p?.mode) {
            PlaybackMode.Direct -> getString(R.string.mode_direct)
            is PlaybackMode.Transcode -> if (m.copyVideo) {
                getString(R.string.mode_direct_stream)
            } else {
                getString(R.string.mode_transcode)
            }
            null -> "-"
        }
        val v = player.videoFormat
        val a = player.audioFormat
        val yesNo = { b: Boolean -> getString(if (b) R.string.supported else R.string.unsupported) }
        val rows = buildList {
            add(R.string.info_mode to modeLabel)
            p?.reasons?.takeIf { it.isNotEmpty() }?.let { add(R.string.info_reasons to it.text(this@PlayerActivity)) }
            add(R.string.info_source to "${version.summary}  ${version.bitrateKbps?.let { "%.1f Mbps".format(it / 1000f) } ?: ""}")
            if (v != null) add(R.string.info_video to "${v.width}x${v.height} ${v.sampleMimeType} ${v.codecs ?: ""} ${if (v.frameRate > 0) "%.3f fps".format(v.frameRate) else ""}")
            add(R.string.info_video_decoder to (videoDecoder ?: "-"))
            if (a != null) add(R.string.info_audio to "${a.sampleMimeType} ${a.channelCount}ch ${a.sampleRate}Hz")
            add(R.string.info_audio_decoder to (passthroughEncoding?.let { getString(R.string.info_passthrough, encodingName(it)) } ?: audioDecoder ?: "-"))
            add(R.string.info_dropped_frames to droppedFrames.toString())
            add(R.string.info_buffer to getString(R.string.info_seconds, (player.bufferedPosition - player.currentPosition) / 1000f))
            add(R.string.info_speed to "${speed}x")
            add(R.string.info_hdr to getString(R.string.info_hdr_value, yesNo(device.displaySupportsHdr), yesNo(settings.displaySupportsHdr)))
        }.map { (label, value) -> getString(label) to value }
        // 等宽字体下中文占两列，按显示宽度补空格让值对齐
        val width = rows.maxOf { monoWidth(it.first) } + 2
        return rows.joinToString("\n") { (label, value) -> label + " ".repeat(width - monoWidth(label)) + value }
    }

    private fun monoWidth(s: String): Int = s.sumOf { if (it.code >= 0x2E80) 2 else 1 }

    // ---- 本季剧集行 ----

    private var seasonEpisodes: List<MediaItem> = emptyList()
    private var episodesAdapter: CardAdapter? = null

    private fun loadSeasonEpisodes() {
        val seasonId = item.parentId?.takeIf { item.type == MediaType.EPISODE } ?: return
        lifecycleScope.launch {
            val result = attempt { server.children(seasonId) }
            DebugLog.i(TAG, "season $seasonId episodes=${result.getOrNull()?.size} error=${result.exceptionOrNull()}")
            val list = result.getOrDefault(emptyList())
            if (item.parentId != seasonId) return@launch
            seasonEpisodes = list
            val adapter = episodesAdapter ?: CardAdapter(
                server, HubStyle.LANDSCAPE,
                onFocused = {},
                onClick = { ep -> if (ep.id != item.id) switchTo(ep, ep.viewOffsetMs?.takeIf { it > 30_000 } ?: 0) },
            ).also {
                episodesAdapter = it
                binding.episodes.layoutManager = StartAlignedLayoutManager(this@PlayerActivity, RecyclerView.HORIZONTAL)
                binding.episodes.adapter = it
                binding.episodes.preferredPosition = { episodesAdapter?.indexOf(item.id) ?: -1 }
            }
            adapter.submit(list)
            adapter.selectedId = item.id
        }
    }

    /** 按钮行按下键展开剧集行并选中当前集；剧集行按上键收起回到按钮行 */
    private fun handleEpisodeRowKey(event: KeyEvent): Boolean {
        val focused = currentFocus ?: return false
        val inButtons = focused.parent === binding.buttons
        val inEpisodes = focused.parent === binding.episodes
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            DebugLog.i(TAG, "key ${event.keyCode} focus=${focused.javaClass.simpleName} inButtons=$inButtons inEpisodes=$inEpisodes episodes=${seasonEpisodes.size}")
        }
        when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && inButtons -> {
                if (seasonEpisodes.isEmpty()) return true
                if (event.action == KeyEvent.ACTION_DOWN) {
                    lastButton = binding.buttons.indexOfChild(focused)
                    val index = seasonEpisodes.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                    binding.episodes.visibility = View.VISIBLE
                    (binding.episodes.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
                    binding.episodes.post { binding.episodes.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
                }
                return true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP && inEpisodes -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    binding.buttons.getChildAt(lastButton.coerceIn(0, binding.buttons.childCount - 1))?.requestFocus()
                    binding.episodes.visibility = View.GONE
                }
                return true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && inEpisodes -> return true
        }
        return false
    }

    private var lastButton = 0

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

    // ---- 章节 / 更多 ----

    private fun pickChapter() {
        val chapters = detail.chapters
        val pos = positionMs
        val current = chapters.indexOfLast { pos >= it.startMs }.coerceAtLeast(0)
        val options = chapters.mapIndexed { i, c ->
            Option("${i + 1}. ${c.title ?: getString(R.string.chapter_n, i + 1)}", formatClock(c.startMs), i == current)
        }
        OptionsPanel(this, getString(R.string.player_chapters), options) { seekTo(chapters[it].startMs) }.show()
    }

    private fun showMore() {
        val actions = buildList<Pair<Option, () -> Unit>> {
            if (detail.versions.size > 1) add(Option(getString(R.string.action_version), version.summary) to ::pickVersion)
            add(Option(getString(R.string.video_scale), videoScaleLabel(this@PlayerActivity, videoScale)) to ::pickVideoScale)
            add(Option(getString(R.string.subtitle_delay), formatDelay(this@PlayerActivity, offsets.subtitleDelayMs)) to ::adjustSubtitleDelay)
            add(Option(getString(R.string.audio_delay), formatDelay(this@PlayerActivity, offsets.audioDelayMs)) to ::adjustAudioDelay)
            val infoLabel = if (binding.info.visibility == View.VISIBLE) R.string.info_hide else R.string.info_show
            add(Option(getString(infoLabel)) to ::toggleInfo)
        }
        OptionsPanel(this, getString(R.string.player_more), actions.map { it.first }) { actions[it].second() }.show()
    }

    /** 只对这次播放生效（连播的下一集也沿用），默认值在设置里改 */
    private fun pickVideoScale() {
        val scales = VideoScale.entries
        val options = scales.map { Option(videoScaleLabel(this, it), selected = it == videoScale) }
        OptionsPanel(this, getString(R.string.video_scale), options) { i ->
            videoScale = scales[i]
            binding.playerView.resizeMode = videoScale.resizeMode()
        }.show()
    }

    private fun adjustSubtitleDelay() {
        StepperPanel(
            this, getString(R.string.subtitle_delay), getString(R.string.delay_hint), offsets.subtitleDelayMs,
            100, 1_000, -10_000L..10_000L, { formatDelay(this, it) },
        ) { offsets.subtitleDelayMs = it }.show()
    }

    /** 音频延迟通常跟输出设备有关，保存到设置里，所有视频生效 */
    private fun adjustAudioDelay() {
        StepperPanel(
            this, getString(R.string.audio_delay), getString(R.string.delay_hint), offsets.audioDelayMs,
            100, 1_000, -5_000L..5_000L, { formatDelay(this, it) },
        ) {
            offsets.audioDelayMs = it
            settings.audioDelayMs = it
        }.show()
    }

    // ---- 跳过片头片尾 / 下一集 ----

    private var markerJob: Job? = null
    private var skipTarget: Long? = null

    /** 每半秒检查当前位置是否落在片头/片尾标记里 */
    private fun startMarkerWatch() {
        markerJob?.cancel()
        markerJob = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                if (player.playbackState == Player.STATE_READY) checkMarkers()
            }
        }
    }

    private fun checkMarkers() {
        val pos = positionMs
        val duration = durationMs
        val active = detail.markers.firstOrNull { pos >= it.startMs && pos < it.endMs - 1_000 }
        val next = nextEpisode?.takeIf { settings.autoPlayNext && !upNextDismissed }

        // 片尾：有下一集就显示倒计时卡片；没有片尾标记时在最后 20 秒显示
        val inCredits = active?.type == MarkerType.CREDITS ||
            (detail.markers.none { it.type == MarkerType.CREDITS } && duration > 0 && duration - pos in 1..UP_NEXT_FALLBACK_MS)
        if (next != null && inCredits) {
            showUpNext(next)
            return
        }
        // 从片尾拖回正片时收起下一集卡片（不算用户取消）
        if (binding.upNext.visibility == View.VISIBLE) {
            upNextJob?.cancel()
            binding.upNext.visibility = View.GONE
        }

        val mode = when (active?.type) {
            MarkerType.INTRO -> settings.skipIntro
            MarkerType.CREDITS -> settings.skipCredits
            null -> SkipMode.OFF
        }
        when {
            active == null || mode == SkipMode.OFF -> hideSkip()
            mode == SkipMode.AUTO -> if (autoSkipped.add(active)) {
                hideSkip()
                skipTo(active.endMs)
            }
            else -> showSkip(active)
        }
    }

    private fun showSkip(marker: Marker) {
        skipTarget = marker.endMs
        if (binding.skip.visibility == View.VISIBLE || controlsVisible) return
        binding.skipLabel.setText(if (marker.type == MarkerType.INTRO) R.string.skip_intro else R.string.skip_credits)
        binding.skip.visibility = View.VISIBLE
        binding.skip.setOnClickListener { skipTarget?.let(::skipTo); hideSkip() }
        binding.skip.requestFocus()
    }

    private fun hideSkip() {
        if (binding.skip.visibility != View.VISIBLE) return
        binding.skip.visibility = View.GONE
        skipTarget = null
    }

    /** 片尾标记一直到片子结束时，直接当作播放结束处理 */
    private fun skipTo(target: Long) {
        if (durationMs > 0 && target >= durationMs - 2_000) {
            onEnded()
        } else {
            seekTo(target)
        }
    }

    private fun showUpNext(next: MediaItem) {
        if (binding.upNext.visibility == View.VISIBLE || controlsVisible) return
        DebugLog.i(TAG, "up next ${next.id} at ${positionMs}")
        hideSkip()
        binding.upNextLabel.setText(if (next.type == MediaType.EPISODE) R.string.up_next else R.string.up_next_item)
        binding.upNextTitle.text = next.title
        binding.upNextSub.text = next.seasonNumber?.let { s -> next.episodeNumber?.let { e -> getString(R.string.episode_code, s, e) } }
        val frame = binding.upNextFrame.layoutParams
        (next.thumb ?: next.backdrop ?: next.poster)?.let { binding.upNextThumb.load(server.imageUrl(it, frame.width, frame.height)) }
        binding.upNext.visibility = View.VISIBLE
        binding.upNextPlay.requestFocus()
        upNextJob?.cancel()
        upNextJob = lifecycleScope.launch {
            for (left in UP_NEXT_SECONDS downTo 1) {
                binding.upNextPlay.text = getString(R.string.play_now) + " · " + getString(R.string.up_next_countdown, left)
                delay(1_000)
                // 暂停时倒计时也暂停
                while (!player.isPlaying) delay(300)
            }
            playNext()
        }
    }

    private fun dismissUpNext() {
        upNextDismissed = true
        upNextJob?.cancel()
        binding.upNext.visibility = View.GONE
    }

    private fun hideOverlays() {
        upNextJob?.cancel()
        binding.upNext.visibility = View.GONE
        hideSkip()
    }

    private val overlayFocused get() = binding.skip.hasFocus() || binding.upNext.hasFocus()

    // ---- 进度上报 ----

    private fun startProgressReporting() {
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) {
                    val v = player.videoFormat
                    DebugLog.i(TAG, "stats pos=${positionMs / 1000}s video=${v?.width}x${v?.height} ${v?.sampleMimeType} audio=${player.audioFormat?.sampleMimeType} dropped=$droppedFrames buffer=${(player.bufferedPosition - player.currentPosition) / 1000}s")
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
        LumyApp.instance.report { server.reportProgress(req, state, pos, dur) }
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
        binding.buffering.visibility = View.GONE
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
        // TV 上离开播放页就结束播放：上报停止（服务端据此记录进度/标记已看）并释放转码
        if (::detail.isInitialized) report(PlaybackState.STOPPED)
        request?.let { stopSession(it) }
        request = null
        player.release()
        ass.release()
        refreshRate.restore()
        finish()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_START = "start"
        private const val EXTRA_QUEUE = "queue"
        private const val EXTERNAL_PREFIX = "ext:"
        private const val TAG = "LumyPlayer"
        private const val UP_NEXT_SECONDS = 10
        private const val MODE_SWITCH_PAUSE_MS = 2_500L
        private const val UP_NEXT_FALLBACK_MS = 20_000L
        private const val MAX_NETWORK_RETRIES = 3
        private const val DOVI_FIRST_FRAME_TIMEOUT_MS = 8_000L
        private const val DOVI_MAX_WAIT_MS = 30_000L
        /** dvhe.07.06 / dvh1.08.06 → 7 / 8 */
        private val DOVI_PROFILE = Regex("""^dv[ah][e1v]\.(\d+)""")
        private const val NETWORK_RETRY_DELAY_MS = 3_000L
        private val SUBTITLE_BACKGROUND = Color.argb(0x99, 0, 0, 0)
        private const val SUBTITLE_PADDING_WITH_CONTROLS = 0.36f
        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val HIDDEN_CONTROL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )

        fun intent(context: Context, itemId: String, versionIndex: Int, startMs: Long, queue: List<String>? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, itemId)
                .putExtra(EXTRA_VERSION, versionIndex)
                .putExtra(EXTRA_START, startMs)
                .apply { queue?.let { putStringArrayListExtra(EXTRA_QUEUE, ArrayList(it)) } }
    }
}
