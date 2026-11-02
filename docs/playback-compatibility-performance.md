# Format Compatibility and Playback Performance Reference

English | [简体中文](playback-compatibility-performance.zh-CN.md)

Updated: 2026-10-31. Measurements through: 2026-10-30. Code baseline: `781e81e`.

This reference groups capabilities, measurements and limitations by container, codec and playback path for future development and regression testing. Real media samples use anonymous identifiers. Media titles, library names, server addresses, file paths, item IDs and credentials are omitted.

For settings and transcoding policies, see [Playback and Decoding](playback.md). Most measurements here come from Plex original-file playback. They do not establish acceptance for Jellyfin / Emby, every device or every file using the same codec.

## 1. What “supported” means

| Status | Meaning |
| --- | --- |
| Implemented | A parsing, decoding or rendering path exists; usability still depends on the device and file |
| Sample passed | A particular device, format combination and short observation passed; this does not cover complete files or all similar files |
| Conditional | Requires a setting, hardware capability or compatibility fallback, potentially with picture-quality limitations |
| Unverified | Insufficient playback evidence to classify as supported or unsupported |

Direct Play means the client processes the original media without server transcoding. Local FFmpeg software decoding, PCM downmixing, RM demuxing, subtitle rendering and HEVC compatibility playback remain local processing. Video and audio can use different hardware or software paths.

Acceptance should separately record startup, sustained progress, the displayed picture, color and dynamic range, audio output, actual listening, subtitles and recovery after seeking. READY does not mean a first frame has appeared or prove that playback will not rebuffer.

## 2. Environment and coverage

- Minimum Android API 21; Media3 is pinned to 1.8.0, with ARM32 / ARM64 packaged.
- Main playback environment: an Android 8 / API 26 projector with approximately 1.6 GiB of visible system memory. The app runs as ARM32; the display does not support HDR / Dolby Vision.
- Some decoder and lifecycle checks also passed in ARM64 environments. This is not full ARM64 UI or format acceptance.
- Direct Play tests used original quality with server transcoding and subtitle burn-in disabled. Matching server-session samples showed no transcoding sessions. These are sampled observations, not a complete packet capture.
- The initial pass covered 51 format combinations, followed by fixes and additional samples of affected formats. The entire library was not played individually. Initial failures and timings are not current-version statistics.
- Audio checks mainly covered decoders, PCM / AudioTrack output and clock progress. Speaker listening, long-term A/V sync and Atmos / DTS:X output still require on-site acceptance.

On this test device, dark or tinted HDR / DV pictures were recorded separately as incorrect color reproduction rather than decode-startup failures. This does not establish correct picture quality or satisfy acceptance requiring accurate HDR / DV output.

## 3. Containers

| Container | Current path | Evidence and limits |
| --- | --- | --- |
| MKV / Matroska | Media3 + libass integration, additional index probing and PGS snapshot output | Multiple playback samples; chained SeekHead and mixed video/subtitle Cues issues fixed |
| MP4 / M4V / MOV | Media3 demuxing | MP4 playback samples exist; the other extensions have not each been accepted |
| RM / RMVB | Incremental local FFmpeg demuxing through the existing DataSource | RV40 + AAC and RV40 + Cook samples passed; no whole-episode download in advance |
| WebM, TS / MPEG-TS, FLV, AVI, OGG, 3GP | Listed as supported containers, using existing player paths | Not every container was covered; verify on the target device |
| MP3, AAC, FLAC, M4A | Listed as supported containers | This round did not comprehensively validate standalone audio files |

A file extension, a recognizable container and successful decoding are separate properties. Damaged files, unusual tracks, missing parameters and server errors can still cause failures.

MKV index handling prefers available video synchronization points and retains a Cluster-search fallback when no usable index exists. Unknown length, probe-budget limits, damaged indexes and multiple video tracks remain edge cases; arbitrary targets are not guaranteed to recover immediately.

## 4. Video codecs and local software decoding

| Codec / type | Current path | Evidence or limitations |
| --- | --- | --- |
| H.264 / AVC 8-bit | Platform decoder preferred | Multiple SD / HD samples passed; ordinary AVC controls still use hardware decoding |
| HEVC / H.265 Main, Main10 | Platform decoder | 1080p / 4K samples passed; resolution, frame rate, bitrate and profile remain device-dependent |
| H.264 High10 | Local FFmpeg when software video decoding is enabled | Hardware decoding failed on the projector; similar 720×496 SDR samples passed in software, which does not prove smooth playback for all 1080p High10 |
| RV40 | Platform `video/realvideo` preferred; optional local software fallback | `OMX.MS.RV.Decoder` passed playback on this device; other devices may lack it |
| MPEG-2 | Platform decoding implemented | A sample was included in the initial pass; no comprehensive codec stress test |
| VP8, VP9, AV1, MPEG-4, VC-1 | Capability mapping implemented; requires an available platform decoder | Insufficient playback coverage in this round; the current software video fallback does not cover these codecs |
| Other unmapped codecs | No general guarantee | Handled according to capabilities and the user's transcoding policy |

Settings → Compatibility → **Allow software video decoding** is off by default. When enabled, available hardware remains preferred. Software attempts are limited to eligible H.264 / RV40 video:

- Known, valid dimensions: longest edge ≤ 1920, shortest edge ≤ 1080, including 1920×1080. This is not strictly “below 1080p.”
- SDR only; HDR, unknown dimensions and larger video are excluded.
- Original compressed video is decoded locally without requesting server re-encoding.
- This is a safety ceiling, not a real-time performance promise. Low-resolution High10 success does not establish high-frame-rate or high-bitrate 1080p performance.

Cook software audio decoding is independent of this setting. Disabling software video decoding does not disable FFmpeg audio decoding.

## 5. HDR and Dolby Vision

| Type | Current behavior | Validation limits |
| --- | --- | --- |
| HDR10 / HDR10+ / HLG | Depends on the decoder, system output chain and display; no client tone mapping | A picture on a non-HDR projector does not prove correct HDR output; full HLG / HDR10+ output acceptance was not completed |
| DV P5 | Native DV preferred; tries ordinary HEVC compatibility playback when native decoding is unavailable or fails | 4K samples produced pictures and A/V progress; green / pink tinting can remain. This is not a correct HDR10 / SDR conversion |
| DV P7 | Native DV preferred, with HEVC HDR10 base-layer fallback on failure | Sample base-layer playback passed; native DV or full enhancement-layer reconstruction was not established |
| DV P8 | Available decoders can play a compatible base layer; HDR10 / HLG properties depend on the profile variant | The sample actually used ordinary HEVC; this does not prove native DV output |

When native DV produces no first frame, the player tries HEVC fallback after its waiting and buffering conditions are met, recording failed profiles locally. The initial detection can be substantially slower than later compatibility starts. Each device requires validation.

P5 has no HDR10-compatible base layer. HEVC compatibility only provides a picture. Correct colors on replacement hardware still require the player decoder path, box, display and output chain to support the relevant DV profile; a Dolby label on the TV alone is insufficient.

Disabling **TV supports HDR** while allowing server transcoding triggers conversion to SDR for HDR sources. Disabling server transcoding does not provide local tone mapping.

## 6. Audio formats and output

| Audio | Current path | Observed scope and limitations |
| --- | --- | --- |
| AAC LC / HE-AAC, AC3, EAC3 | Available platform decoder preferred, with FFmpeg where needed | Output-chain samples cover several channel counts, not every profile |
| DTS, DTS-HD MA, DTS Express | Platform or FFmpeg decoding to PCM | Output samples exist; Express source and decoder-reported channel counts have differed, so channel fidelity remains unverified |
| TrueHD | Platform capabilities and FFmpeg path | 2ch, 5.1 and 7.1 playback; initialization/reset failures producing zero PCM on specific streams fixed |
| Cook | Explicitly uses FFmpeg | Stereo RM samples passed; the enumerable vendor decoder could not be configured through the tested standard interface and is not a product dependency |
| FLAC, Opus, PCM | Platform or existing local audio path | Format samples exist; PCM output has been observed as 24-bit → 16-bit and multichannel → stereo |
| MP3, MP2, Vorbis, ALAC | Capability mappings exist; actual platform / FFmpeg support required | Insufficient per-format playback coverage in this round |

Audio passthrough is off by default. Decoded multichannel PCM is downmixed to stereo for built-in TV and projector speakers. This does not preserve the original channel layout or prove Atmos / DTS:X object-audio reproduction.

With passthrough enabled, available formats depend on system HDMI capabilities and the receiver; passthrough tracks use fixed 1× speed. Comprehensive receiver-based format, channel-position and listening tests were not completed.

## 7. Subtitle formats

| Subtitle | Current path | Evidence and limitations |
| --- | --- | --- |
| SRT / SubRip | Local text parsing, including an external legacy-encoding compatibility path | Regression samples for legacy-encoding corruption passed; arbitrary unknown encodings are not guaranteed to be detected correctly |
| ASS / SSA | libass | Embedded / external paths implemented; bilingual dialogue and effects samples passed, without exhaustive font or animation coverage |
| Embedded PGS in MKV | Loader emits compressed state snapshots; a separate worker decodes/renders according to the subtitle clock | Multiple objects, windows/cropping, palette deltas and state reuse; paused track switching, seeking and pixel comparisons passed |
| External SUP / PGS | Custom SUP parsing, timestamp lookup and seek-state warm-up | Dialogue and on-screen translation effects samples passed; does not use the new embedded PGS snapshot path |
| WebVTT | Local text path implemented | Insufficient sample coverage |
| Embedded VobSub / DVD, mov_text / tx3g | Listed in local subtitle capabilities | Initial embedded VobSub and mov_text screenshots passed; not all styles or files accepted |
| Embedded DVB | Listed in local subtitle capabilities | Insufficient sample coverage; external VobSub / DVB is not in the current external subtitle capability list |

Embedded PGS selection filtering is enabled only when conditions such as a single original file allow safe mapping. Otherwise, fallback behavior is retained. Disabling subtitles or switching tracks should avoid generating unused PGS Bitmaps.

Snapshot history is bounded to **20 seconds / 16 MiB / 2048 entries**. Time-based eviction retains the last valid state preceding the window. Hard byte/count budgets take priority, with a single valid oversized sample allowed to remain. This prevents unlimited accumulation, but very dense subtitles may retain less than 20 seconds.

Snapshots independently decode subtitle states already in SampleQueue. They cannot reconstruct palette / RLE objects never read before a raw-file network seek target. Cross-epoch seeks and fresh seeks with positive subtitle delay still need dedicated preroll validation.

## 8. Performance metric definitions

| Metric | Definition | What it does not establish |
| --- | --- | --- |
| plan → READY | Playback-plan log to first READY | Neither app cold-start time nor precise first-frame time; does not guarantee sustained playback |
| launch → first frame | Test launch to the target video's first-frame event | Includes Activity, metadata, network and decoding; cannot be attributed solely to the codec |
| seek → READY | Target submission to READY recovery | Not first-frame display or the interval between the first two output frames |
| First-two-frame interval | Interval between the first two valid output frames at the new target | Excludes pre-frame seek loading; subtracting it from seek → READY does not establish total latency |
| Rebuffering | BUFFERING → READY after playback has started | Different from a frozen picture with data still buffered |
| dropped | Cumulative logged dropped-frame count | Zero does not prove perfect frame-by-frame viewing quality |
| CPU / PSS | Thread CPU over a specified window / process memory at a specified point | Not a system-wide peak, long-term average or fixed cost for every source |

The measurements below are short observations of specific formats, not cross-device rankings or full-file percentiles. Hardware, networking, keyframe spacing, caches, fonts, subtitle density and thermal conditions affect results.

## 9. Anonymous performance samples

### 9.1 Dense animated PGS: sample P-01

Combination: 1080p H.264 + EAC3 7.1 + embedded bilingual effects PGS in MKV. Each run started at the same target and was observed for 75 seconds.

| Stage | plan → READY | Rebuffering after startup | Cumulative logged dropped frames |
| --- | --- | --- | --- |
| PNG-path baseline `5a6d783` | 17.111 s | Once, 7.373 s | Not used for this comparison |
| First snapshot-path test | 10.731 s | None | 0 |
| Repeated snapshot-path test | 8.000 s | None | 0 |
| Implementation corresponding to final version `781e81e` | 8.012 s | None | 0 |
| Subtitles-off control | 6.853 s | None | 0 |

The first two snapshot tests used a smaller history budget. The final implementation increased it to the bounded budget above for subtitle-delay adjustment. Use final-version memory measurements rather than combining intermediate implementations.

- Final PSS was approximately **129.6 MiB**, versus approximately **107.4 MiB** for the baseline. Lower CPU overhead does not imply lower memory usage. These are single samples at the end of comparable observation windows, not peaks.
- In the initial two tests, Loader CPU over wall-clock seconds 18–32 was 76.3% for the baseline and 13.1% / 5.5% for snapshots; the new subtitle worker used 8.8% / 5.9%, with one CPU core defined as 100%. Loading cadence differs, so these are neither fixed speedup ratios nor final-version CPU measurements.
- Interactive forward/backward seeks took **5.534 / 4.260 s** from seek to READY. Loading waits remain.
- Disabling subtitles and switching to another PGS track while paused preserved the pause and refreshed subtitles correctly; switching back after resuming also passed.

An independent ARM32 subtitle-pipeline probe on the same device, without networking or video decoding:

| Approximately 25 seconds of subtitles, 834 display sets / 675 Bitmaps | Elapsed time |
| --- | --- |
| Old parser + PNG encoding and decoding | 20.677 s |
| New Loader compressed-snapshot generation | 0.399 s |
| Decoding all new snapshots | 2.063 s |

All 834 display sets matched the old parser's pixels and geometry. Independent decoding after shuffling snapshot order passed. The standalone process has a different heap environment from the full player; these times do not replace playback startup measurements.

### 9.2 Compatibility video decoding

These playback observations correspond to `5a6d783` and use local demuxing and decoding. The tested samples no longer failed to start after the fixes.

| Anonymous sample | Combination / path | plan → READY | Forward seek → READY | Backward seek → READY |
| --- | --- | --- | --- | --- |
| V-01 | RV40 + AAC, vendor RV40 hardware decoder | 2.691 s | 0.410 s | 2.168 s |
| V-02 | RV40 + Cook, hardware video / software audio | 3.499 s | 1.139 s | 1.877 s |
| V-03 | RV40 + Cook, independent same-format sample | 4.070 s | 0.482 s | 2.328 s |
| V-04 | 720×496 High10, FFmpeg software video | 5.255 s | 1.050 s | 1.682 s |
| V-05 | Independent same-type High10 sample | 5.240 s | 1.072 s | 0.820 s |
| V-06 | Ordinary 1080p AVC hardware control | 1.831 s | 0.422 s | 3.141 s |

Each is a single observation. Short steady-state samples logged zero dropped frames; some seeks dropped approximately 3–5 frames. Offline High10 decode throughput is not a full-player frame rate. With software video disabled, this format still fails hardware decoding on the current device.

### 9.3 MKV indexing, TrueHD and DV compatibility

| Anonymous scenario | Observation | Interpretation |
| --- | --- | --- |
| K-01: 11 MKV samples, 33 stages | 11 startup/resume runs and 22 fixed-target seeks; first-two-frame interval 38–80 ms, first PTS offset 0–37 ms; seek → READY 1.758–5.972 s | No recurrence of the original screening condition: READY with buffered data but stalled frame output. Loading still occurs |
| A-01: a specific TrueHD 2ch stream | Before the fix, zero PCM and repeated buffering; afterward, independent same-format samples and 5.1 / 7.1 controls sustained output | Initialization/reset channel-layout issue fixed; listening remains unverified |
| A-01: farther target in the same stream | A single seek → READY observation of 12.457 s, followed by PCM/video progress | Remaining loading issue with no confirmed root cause; no longer classified as the zero-PCM hang |
| D-01: first native failure and fallback for 4K DV P5 | Launch → first frame 17.593 s | Includes native detection and reopening, not just decode time |
| D-01 / D-02: known HEVC fallback path | Launch → first frame 4.710 / 6.585 s | Tinting remains; both same-format samples produced pictures and position progress |

Most K-01 samples had subtitles disabled to isolate indexing, so these results do not establish dense-subtitle performance. Correct indexing decodes from a preceding synchronization point; loading at some positions may be slower than with the incorrect index. Compare loading and continuous frame output after recovery together.

## 10. Current limitations and regression priorities

1. DV P5 compatibility colors remain uncorrected. Native DV, correct HDR output and complete enhancement-layer handling require suitable hardware and separate testing.
2. Some MKV starts/seeks still load for several seconds; a specific TrueHD sample reached approximately 12.5 seconds in one observation. Do not attribute every wait to networking or one shared cause.
3. Measured dense animated PGS rebuffering improved. Long-running/full-file tests, cross-epoch seeking, nonzero delay and extremely dense subtitles still need stress testing.
4. Software video is conditionally available only for H.264 / RV40. 1080p is a dimension ceiling, not a performance guarantee.
5. Actual listening, long-term A/V sync, passthrough, Atmos / DTS:X, full-file subtitle sync and seamless multi-Part transitions have not been comprehensively accepted.
6. Unplayed codecs, damaged/unknown-format samples and failed index scans remain unverified. They are neither confirmed playback failures nor evidence of support.

## 11. Validation and maintenance

Before delivery of the current PGS optimization, Debug / AndroidTest / Release builds, 65 tests across four JVM modules and the main projector instrumentation passed. Device checks cover snapshot pixels, input budgets, track filtering, sample offsets/supplemental data, pause, seek, EOS, period boundaries, worker coalescing, stale-result rejection and Bitmap lifetime, with regression checks for SUP, indexes, TrueHD, DV, software video and RM paths.

Reusable validation entry points:

```bash
./gradlew :core:test :net:test :server-plex:test :server-jellyfin:test
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
adb -s <device> shell am instrument -w \
  com.hesier.lumy.debug.test/com.hesier.lumy.ui.player.PgsInstrumentation
```

Install the matching Debug and AndroidTest APKs on the target before instrumentation. Passing these checks does not replace playback, listening and remote-control acceptance on the actual display device.

Implementation references:

- [Capability detection](../app/src/main/java/com/hesier/lumy/ui/player/DeviceProfile.kt), [playback planning](../app/src/main/java/com/hesier/lumy/ui/player/PlaybackPlanner.kt), [software video policy](../app/src/main/java/com/hesier/lumy/ui/player/SoftwareVideoPolicy.kt).
- [Player construction](../app/src/main/java/com/hesier/lumy/ui/player/PlayerFactory.kt), [local compatibility decoder build instructions](../third_party/media3-ffmpeg/README.md).
- [MKV index handling](../app/src/main/java/com/hesier/lumy/ui/player/ClusterSeekingMatroska.kt), [SUP parsing](../app/src/main/java/com/hesier/lumy/ui/player/SupExtractor.kt).
- [PGS snapshot format](../app/src/main/java/com/hesier/lumy/ui/player/PgsSnapshotCodec.kt), [PGS rendering and history budgets](../app/src/main/java/com/hesier/lumy/ui/player/PgsSnapshotRenderer.kt).

Future updates should record the date, code baseline, anonymous sample ID, full format combination, device/ABI, settings, metric start/end events, repetition count, observation length and validation limits. Regression and new-format checks should include an independent same-type sample. Mark old results as historical baselines rather than mixing them into a current success rate. Public documentation must not link private logs, screenshots, inventories or credential files containing media titles or secrets.


## 12. Danmaku performance probe

Added 2026-11-02, debug only. During playback, press Menu and select More → Danmaku performance test: Off, Follows settings or Stress input. Off is the default; selection is not persisted and resets when changing media or file versions. All comments are synthetic and no media metadata is sent to external services.

Enable Settings → Danmaku first. Both profiles follow all saved preferences without overlap. Normal/stress generate candidates every 100/25 ms; both admit at most 1/2/4 comments per sliding 500 ms for few/standard/more. Area defaults to the upper 50%; size, opacity, outline, speed and types follow settings. The text cache pool budget remains 8 MiB (not a total memory limit). Fixtures cover twelve seconds before the comment position and five minutes after it; leaving the window regenerates the fixture. Record those rebuilds during long runs. Synchronization uses the player's absolute media time, including seeks and transcoding offsets. The existing video SurfaceView is retained.

`LumyDanmaku` logs position, visible comment count and UI dimensions every ten seconds. Video counters remain in `LumyPlayer`. Release excludes the synthetic menu and fixture resources. Production source support now includes the shared AkDanmaku/libGDX runtime. See [the dependency patch](../third_party/akdanmaku/README.md) for the upstream worker-release fix and reproducible build.

After installing the Debug and AndroidTest APKs:

```bash
adb -s <device> shell am instrument -w -e danmaku_only true \
  com.hesier.lumy.debug.test/com.hesier.lumy.ui.player.PgsInstrumentation
adb -s <device> logcat -s LumyDanmaku LumyPlayer
```

The smoke check loops the existing synthetic 320×180 H.264/subtitle fixture without network access or server progress writes. It covers D-pad density selection, focus restoration, absolute timeline offsets, paused seeking, 2x speed, stop/restart and worker shutdown. `uiDraws` counts View-tree draws, not presented frames or smooth movement.

Full low-end hardware acceptance plan (the 4K SDR measurements are at the end of this section): Compare all three profiles on the same media, start position, subtitles, quality and display refresh rate. Warm up, observe at least five minutes and repeat; cover 4K SDR/HDR, dual subtitles, seeks and sustained playback. Record video dropped frames, UI/GPU frame times and interval distributions, CPU, PSS, GC, thermal state and visible scrolling uniformity. Use Perfetto to inspect SurfaceFlinger/HWC composition where needed. Neither video drop counters nor View draw counts alone establish smoothness, and emulator results do not establish low-end box performance.

Historical pre-optimization baseline (2026-11-02): the API 31 ARM64 TV emulator smoke check passed. With a 1920×1080 UI and 320×180 synthetic video, separate five-second normal/stress samples recorded 299/299 View-tree draws, 11/85 visible comments at the end and zero video drops in both samples. Off produced no continuing UI draws over 500 ms and no detected surviving comment workers. These are not 4K performance or presented-FPS results. An initial cold-start attempt ended in an Activity input timeout; subsequent checks passed.


### 2026-11-02 scheduling and load bounds

The strategy was reviewed against [blbl's frame and load budgets](https://github.com/cat3399/blbl/tree/cd09bd903e6d0c22f5986580c2105796d8df6471/app/src/main/java/blbl/cat3399/feature/player/danmaku). The adapter is independently implemented; no blbl source is copied, and Ak layout/rendering algorithms remain in use.

- All debug Ak instances use upstream manual mode, driven by one serial worker and its Choreographer. Paused/buffering playback retains snapshots and caches without continuous updates. Seeks, size changes and asynchronous cache completion coalesce finite refreshes. UI consumption provides backpressure before render snapshots can be recycled. Do not mix automatic/manual Ak players in this debug process.
- State and speed commands are deduplicated. Initialization/discontinuity seeks immediately; periodic correction requires three consecutive one-second drift samples over 300 ms, with a ten-second correction cooldown. Paused playback is not periodically corrected.
- The earlier sliding 500 ms admission window allowed one normal or six stress comments; the current limit follows saved density as described above, with 80 Unicode code points per text. Fixture generation/sorting/filtering happens off the UI thread, entity creation uses the worker, and the next window is prepared 30 seconds early. Cancelled results cannot install after close/profile changes.
- Each update admits up to eight measure/cache tasks. The waiting queue is capped at 64, plus one active task. Rejection/cancellation restores retryable transient states. Rejection counters include repeated attempts, not unique dropped comments.
- Logs report update/draw/interval count and rolling 256-sample p95/max, cache hits, queue depth/peak/rejections, work elapsed time, Java/native heaps and sync command counts. Update/draw are wall-clock durations of CPU paths; draw covers UI recording of the comment subtree. Intervals are submission intervals, not GPU time or presented FPS. Heap figures are not PSS; use system tools for GPU, GC, PSS, composition and thermal measurements.

The targeted instrumentation additionally checks that paused update/draw counters remain unchanged over 2.2 seconds, paused seeking changes actual visible IDs and then settles, heartbeat commands are deduplicated, and sliding-window boundaries, Unicode length, cache limits and cancellation/retry behave correctly. Use the same `-e danmaku_only true` command. Existing shutdown fixes remain; see [the patch instructions](../third_party/akdanmaku/README.md). Tests still use a small synthetic video and do not establish low-end 4K performance.

The historical final run before settings binding on the API 31 ARM64 TV emulator used host GPU mode and the same 1920×1080 UI/320×180 synthetic H.264 fixture. Separate five-second normal/stress samples recorded 291/248 UI draws and zero video drops; update p95 was 0.88/2.34 ms, comment UI recording p95 was 0.26/0.60 ms, and submission interval p95 was 20.92/31.28 ms. Stress still has interval spikes; this does not demonstrate stable 60 FPS. Observed waiting queue peaks were eight in both profiles (hard bounds are tested separately). Off stopped drawing and left no workers. These single-run smoke figures are not a controlled before/after speedup comparison.


### 2026-11-02 preference binding for test comments

Profile selection is still transient, while style and behavior read Settings → Danmaku. Master off or all three types off removes the engine; enabling restores the selected test input. Changes coalesce and rebuild the current window, including paused refreshes. Font sizes map to 14/18/22/26/32 sp; slow/standard/fast rolling durations are 10/8/6 seconds. Outline off skips outline drawing. The adapter distinguishes pending/current Ak configs and restores the selected duration after viewport changes.

Positive offsets delay comments: the comment clock is `max(absolute media position - offset, 0)`. Synthetic types rotate after admission limiting so the stress input stride cannot exclude an enabled type.

Automatic reduction samples at most once per second against the display refresh budget. Pressure means the sum of rolling 256-sample update/draw p95 exceeds 60% of a frame budget, or submission interval p95 exceeds 1.5 budgets. Three consecutive pressure samples halve the stable-ID subset, down to one quarter; twenty healthy samples restore one level. Changes have a five-second cooldown. Pause/buffering clears consecutive counts; disabling automatic reduction restores selected density. Saved density is never changed. Submission intervals cannot identify the source of pressure and are not GPU presentation timings.

Targeted checks cover master/type switches, all types off, extreme sizes, area, opacity, outline pixels, rolling duration, signed offsets, defaults, adaptive reduction/recovery and preference preservation. Historical fixed-density measurements above cannot be directly compared with the current preference-controlled load.

The API 31 ARM64 TV emulator regression passed using the 1920×1080 UI and 320×180 synthetic video, including preference binding, delayed origin, pause/seek/speed and worker shutdown. Adaptive policy, filtering and recovery use deterministic samples and verify that saved density stays unchanged; thresholds still need sustained low-end 4K hardware validation. Debug, AndroidTest and Release builds passed; that historical Release APK contained no AkDanmaku/libGDX package references; production source integration now excludes only the synthetic menu/data.


### 2026-11-02 production sources and rendering

The shared online client implements search, details and full-episode comments. Custom services explicitly request JSON; official signing and related comments are restricted to the official origin. Optional `DANDANPLAY_APP_ID` / `DANDANPLAY_APP_SECRET` build environment variables enable official access; missing credentials report unavailable.

Jellyfin XML and Emby grouped JSON use separate parsers and current server authentication. Media headers never reach online sources. Authentication failures remain distinct; missing sidecars do not disable the plugin for other items. No refresh/download administration endpoint is called. Plugin lookup uses the library item ID; independent cuts and sidecar selection need deployment validation.

Requests with the same client, URL and effective headers share in-flight work. The last consumer cancels body reading; cross-origin redirects strip private headers. Bounds are 8 MiB decoded responses, 100,000 raw entries and 80 code points per text. Automatic matching is conservative; ambiguous platforms, missing seasons and specials require explicit selection. The first release does not fetch video hashes or call optional match endpoints.

Manual corrections are isolated by local server/account, media ID and service identity, with descriptive metadata checked when IDs expire or are reused. Normalized unfiltered comments cache for six hours within 64 MiB total/16 MiB per file, keyed by service and work/episode fingerprint. Only twelve seconds before and sixty seconds after the current comment clock enter the renderer. Seeking reuses downloaded data. The 8 MiB bitmap pool remains a pool budget, not a total-memory limit.

The production runtime shares pause/sync/load bounds and adaptive density with debug. Item changes, master off and exit cancel loading/search; old selection panels validate the playback generation. Synthetic fixtures/menu/strings remain debug-only.

Instrumentation modes: `danmaku_api_only` for owned loopback priority/correction/cache checks; `danmaku_overlay_only` for production rendering; `danmaku_settings_only` for persistence/backups/D-pad. Explicit `danmaku_live_url` and `danmaku_live_anime` arguments exercise real details→episode one→download→Overlay without storing private endpoints in source.


Final validation: 197 JVM tests passed, including 67 network tests. Debug/AndroidTest/Release builds passed. Emulator checks covered source preferences/backups, real D-pad URL entry/save, plugin priority/fallback, pinned choices, expired/reused IDs, cache isolation, production windows and the original probe. A deployed custom service returned 6,631 normalized comments; paused initial/seek samples showed 4/9 visible comments, confirmed by screenshots. Video remained the local 320×180 synthetic fixture, so this is not real 4K performance acceptance. Official access and actual server plugins remain untested against deployments.

Real-data validation exposed a paused refresh bug: changing layout generation cleared visibility while the old retainer reused it. Paused cache refreshes and runtime density changes now invalidate the retainer together with layout. Regression checks include near-origin positive/negative IDs, all three modes, density recovery and visibility after cache settling. Global admission is cached by density/types so overlapping windows retain the same IDs. A long-running emulator developed input/frame scheduling delays; after restarting the task-owned emulator, the original speed checks passed without relaxed thresholds.


### 2026-11-02 projector 4K measurements and scheduling race

K-DM-01 is an XGIMI TV projector, Android 8 / API 26, ARM32 app, about 1.64 GiB RAM, with a 1920×1080 / 60 Hz display. Anonymous source DM-V1 is 3840×2160 HEVC Main10 SDR, about 27.7 Mbps in MKV. `OMX.MS.HEVC.Decoder` hardware decoding reported 3840×2160 output. The audio track was fixed to EAC3 6ch → PCM; subtitles were disabled. Display resolution does not reduce the decoded source to 1080p.

The API 24+ device benchmark uses the current server's Direct Play and an explicitly selected online work/episode. It bypasses watch-progress reporting, uses isolated temporary preferences, and releases playback/rendering resources afterward. Downloading, parsing, windowing and rendering use production implementations; explicit selection does not validate automatic matching. Install both Debug and AndroidTest APKs and dismiss an existing screensaver first.

Use the `danmaku_benchmark_*` instrumentation arguments shown in the [Chinese command reference](playback-compatibility-performance.zh-CN.md#2026-11-02-投影仪-4k-实测与调度竞态). Required arguments are `item`, `url`, and `anime`; `episode` defaults to `1`, `version` to `0`. Set a fixed `audio_mime`, `profiles=off,low,high`, `seconds=300`, `warmup_seconds=30`, and `auto_reduce=false` for fixed-density comparisons. Video and comment starts default to 60000 ms. A separate comment start is allowed only for explicitly labeled load replay. Each phase repeats the same video position. Private `files/danmaku-device-benchmark.jsonl` is authoritative; final stdout duplicates summaries.

Metrics separate Media3 drops/buffering, window HWUI duration, process CPU/PSS/GC, and external read-only SurfaceFlinger/thermal samples. CPU 100% means one core; PSS covers the whole process. Window timing is not GPU time; static UI with comments off has N/A frame timing. Internal danmaku percentiles retain only 256 recent frames; visible count/drift are sampled around 1 Hz. Presentation intervals use only originally adjacent valid actual timestamps within each SF snapshot, deduplicating edges across snapshots so missing history does not become a false stall. Video cadence is interpreted against the approximately 24 fps source. Sequential player/cache reuse limits causal comparisons.

The initial off/low/high five-minute phases all reported zero video drops and buffering. Off used 36.0% of one core and 102.1 MiB PSS. Low nevertheless showed a 2818.9 ms UI update gap and 2745 ms maximum sampled comment drift, followed by one heartbeat correction. Maximum window HWUI duration was only 160 ms: percentiles of rendered frames alone miss periods that produce no frame. This event did not overlap screenshot capture.

Review confirmed a lost-wakeup interleaving between independent credit/dirty atomic updates and consumption notification. Frame scheduling could stop until three one-second drift checks requested another frame. Post-fix measurements must be reported separately; zero video drops do not establish completely smooth comments.


Fix `5e6b4ce` uses one synchronized credit/pending gate while retaining serial production and consumption backpressure. Deterministic producer/consumer orders, coalescing and paused draws passed. The projector passed the full danmaku Probe regression (small video fixture); Debug/AndroidTest/Release builds passed.

Two fresh post-fix 4K runs each warmed for 30 seconds and measured five minutes. Settings were standard size, upper 50%, 80% opacity, thin outline, rolling/top enabled, adaptive density enabled:

| Scenario | Video drops / buffering | CPU, one-core average | Sampled process PSS | UI actual interval p95 / p99 / maximum | Visible average / maximum |
| --- | --- | --- | --- | --- | --- |
| Off, pre-fix same-source baseline | 0 / 0 | 36.0% | 100.3–103.7 MiB | N/A | 0 / 0 |
| Fixed, matching movie comments, low | 0 / 0 | 69.7% | 127.8–146.8 MiB | 18.13 / 23.38 / 283.89 ms | 3.1 / 8 |
| Fixed, dense real-comment replay, high | 0 / 0 | 84.1% | 100.7–121.0 MiB | 19.24 / 34.44 / 150.67 ms | 15.3 / 35 |

The matching movie had about 3,216 comments. Dense replay had about 6,627 total / 1,377 candidates in the replay interval, from a different work solely to exercise load; it does not validate matching. Both played the same DM-V1 fragment. UI presentation coverage was about 300 seconds per run, with no further multi-second update gap captured. Low still had an unexplained long-frame cluster around phase seconds 157–160, so this is not a claim of perfectly smooth scrolling. Window HWUI duration p95 was 14.03 / 21.18 ms; 3.21% / 9.51% exceeded 16.7 ms. These are separate from SF presentation intervals.

Adaptive density reached one quarter temporarily in both runs; low recovered to the saved target, while dense high ended at one quarter. Saved density was unchanged; neither run needed heartbeat correction. Raw `maxObservedDriftMs` is a roughly 1 Hz engine-clock difference. During window replacement, a new engine can briefly report zero before its initial seek, producing a raw 362.6-second difference in the dense run. This does not mean comments were actually misplaced for 362 seconds or establish long-term sync acceptance. Maximum SF update interval remained about 151 ms; initialization/generation telemetry needs improvement.

Temperature samples spanned about 86–96°C, without establishing throttling. Low/dense recorded 53 / 66 GCs and 4.28 / 7.44 seconds cumulative GC work, with zero blocking-GC counts; cumulative work is not a main-thread pause duration. Startup/cache differences prevent attributing PSS deltas directly to comments, and sampling does not prove peak memory bounds.

Server sessions, all original preferences, track memory and the selected movie's view offset/count were unchanged afterward; test playback was released. Private media/account/log/screenshot artifacts are excluded from commits. The evidence supports low density plus adaptive reduction on this device: no video drops or buffering were observed with 4K SDR, while denser comments increased UI long frames. Other boxes, HDR, dual subtitles and full-length playback remain outside this validation.


### 2026-11-02 search connection recovery after idle

An API 31 ARM64 TV emulator reproduced successful consecutive searches but an immediate `CONNECTION / IOException` failure after six seconds idle, followed by a successful next search: five of six attempts passed. The failed request reused a connection and received no response headers, consistent with missing recovery after the server closed an idle pooled socket. Both source client and Transport had forced connection retries off.

The source client now enables OkHttp connection recovery; Transport inherits the caller policy. Original connect/read/call budgets remain 5/15/20 seconds, along with redirect controls, private-header isolation, body bounds and cancellation. MockWebServer regression failed before the fix and now covers successful recovery plus termination when the replacement connection also fails.

The fixed emulator passed six of six identical-keyword searches. Six/fifteen-second idle attempts returned in about 575/676 ms with connection replacement visible in the event trace. Explicit `-e danmaku_search_keyword <keyword>` runs this sequence using current source configuration; output omits keywords, URLs, credentials and response contents. All 69 net tests and Debug/AndroidTest/Release builds passed. The emulator has the fixed APK and its original settings remain unchanged.
