# Playback and Decoding

English | [简体中文](playback.zh-CN.md)

This page explains when Lumy Player uses Direct Play and when it asks the server to transcode. It also covers HDR, Dolby Vision and audio support.

For differences between integrations and their output paths, see the [server support matrix](server-support.md). For format matrices, anonymized device measurements and validation limits, see the [compatibility and performance reference](playback-compatibility-performance.md).

## Principles

Lumy Player uses Direct Play whenever possible. Direct Play keeps the original quality, puts no extra load on the server, and handles subtitles and audio tracks on the device. The server transcodes when the device cannot handle a file or a quality limit is selected.

- **Video uses hardware decoding first.** Settings › Compatibility › **Allow software video decoding** is off by default. When enabled, H.264 (including High10) and RV40 SDR video can fall back to local software decoding if hardware support is absent or fails. The longest edge must be at most 1920 and the shortest at most 1080; unknown dimensions, HDR and larger videos are excluded. This is a safety ceiling, not a promise of smooth playback: CPU speed, codec and frame rate still matter. Software decoding keeps the original stream and does not use server transcoding.
- **RM/RMVB files** are demuxed locally. RV40 uses the device decoder when available and can use the optional software path otherwise; Cook audio uses FFmpeg independently of the video setting.
- **Audio uses the system decoders first.** Formats the system doesn't support (DTS, DTS-HD, TrueHD and others) are decoded by the bundled FFmpeg extension, so audio rarely needs transcoding.

## When the server transcodes

Transcoding requires **Allow server transcoding** to be on (the default). When it's off, Lumy Player always uses Direct Play. If the device can't play a file, an error explains why.

### Decided before playback starts

“Remuxes only” and “converts the audio only” require an integration that permits video stream copy. fnOS currently re-encodes the video instead.

| Reason | What the server does |
|---|---|
| A bitrate-limited quality is selected (1080p 20/12 Mbps, 720p, 480p) | Re-encodes the video |
| Unsupported container (MKV, MP4, MOV, WebM, TS, FLV, AVI, OGG, RM/RMVB and others are supported) | Remuxes only; the video is copied with little server load |
| Unknown video codec (H.264, HEVC, VP9, VP8, AV1, MPEG-2, MPEG-4, VC-1 and RV40 are supported) | Re-encodes |
| Neither hardware nor the enabled, eligible software path can decode the codec | Re-encodes |
| Resolution or frame rate beyond the hardware decoder's limits | Re-encodes and scales anything above 1080p down to 1080p |
| 10-bit video with no usable local decoder (eligible H.264 Hi10P can use the software setting) | Re-encodes and scales anything above 1080p down to 1080p |
| Audio codec that neither the device nor FFmpeg can decode | Converts the audio only |
| HDR source while **TV supports HDR** is off | Converts to SDR at up to 1080p |
| A subtitle the device can't show, with **Allow server subtitle burn-in** on (off by default) | Re-encodes and burns in the subtitle |

### Switching during playback

In these cases, Lumy Player switches to transcoding and continues from the current position. It switches at most once per playback start.

- **Direct Play fails.**
  - If video decoding fails (the device claimed support but couldn't decode), the video is re-encoded.
  - Container or parsing failures use remuxing where video stream copy is supported; fnOS still re-encodes the video.
- **The file can't seek** (for example an MKV without an index). When you resume or skip, the server starts the stream at the target position.

Network errors never trigger transcoding. The player reconnects and retries instead.

### Transcode output

- **Plex** uses HLS + TS and advertises H.264 / HEVC video with AAC / AC3 / EAC3 / MP3 audio; SDR conversion advertises H.264 only. Requested limits are 5.1 for AC3 and 7.1 for EAC3, while the server determines actual output. The current HLS profile does not advertise DTS / TrueHD / FLAC / Opus, so sources using these formats may require audio conversion.
- **Jellyfin / Emby** normally use HLS + TS. Retaining the selected embedded subtitle switches to progressive MKV generated from the requested start position; the subtitle is copied for local rendering, and seeking rebuilds the stream. HLS requests allow AAC / AC3 / EAC3 / MP3 / Opus / FLAC; the MKV path can additionally advertise the source audio codec when locally decodable. Allowing copy does not guarantee the server will copy, or establish playback validation for every server/container combination.
- **fnOS Media** uses native H.264 HLS transcoding. AAC / AC3 / EAC3 can be kept when locally decodable; other audio falls back to AAC. Output currently targets stereo or 5.1. Video stream copy and 7.1 output are not integrated.
- **Subtitle handling depends on the output path**:
  - Embedded tracks omitted from HLS cannot be rendered locally. Plex needs external subtitles or burn-in; Jellyfin/Emby can retain the selected embedded track through the MKV path above.
  - Native fnOS HLS can carry the selected text subtitle, or the playback plan may choose burn-in. Bitmap subtitles use burn-in; there is no general MKV subtitle-retention path.
  - External subtitles are rendered locally when the playback source supplies the corresponding tracks. Required burn-in needs permission or an existing reason to re-encode the video.

## HDR

- **Detecting HDR in the source**:
  - Plex: HDR10, HLG and Dolby Vision. HDR10+ is reported as HDR10.
  - Jellyfin / Emby: HDR10, HDR10+, HLG and Dolby Vision.
  - fnOS Media: native stream metadata identifies HDR10, HDR10+, HLG and Dolby Vision; see the server matrix for validation limits.
- **The *TV supports HDR* setting decides whether HDR is sent to the TV.** It's on by default. Lumy Player doesn't rely on the system's own HDR detection, because many boxes always report support or report nothing. The detected value is only shown in Playback info.
- **When the setting is on**, HDR sources use Direct Play, and the box and TV handle the HDR signal.
- **When the setting is off**, every HDR source, including HLG and Dolby Vision, is converted by the server to 1080p SDR H.264. This avoids a washed-out picture on TVs without HDR. HDR tone mapping must be enabled on the server, or the colors will still be wrong.
- The player itself doesn't do any tone mapping.

## Dolby Vision

| Profile | Device has a Dolby Vision decoder | Device has no Dolby Vision decoder |
|---|---|---|
| **5** (no HDR10 base layer) | Native playback preferred; tries HEVC compatibility playback on failure | Tries HEVC compatibility playback, with potentially incorrect colors |
| **8** (8.1 / 8.4) | Uses an available decoder | Plays the compatible HDR10 / HLG base layer with HEVC, depending on the variant |
| **7** (UHD Blu-ray dual layer) | Native playback preferred; tries the HEVC base layer if the decoder stalls | Plays the HDR10 base layer with HEVC |

**If the Dolby Vision decoder stalls**: some devices report support without producing a picture. After at least 8 seconds without a first frame and with 5 seconds buffered, the player tries HEVC compatibility playback. Without enough buffered media, it waits up to 30 seconds. It remembers failed profiles locally for subsequent playback. P5 video initialization or decoding errors can also trigger this fallback.

P7 / P8 base-layer playback does not prove native Dolby Vision output or full enhancement-layer reconstruction. **P5 has no HDR10-compatible base layer.** Its HEVC compatibility path only produces a picture; it does not correct colors or perform tone mapping, so green or pink tinting may remain. Correct native Dolby Vision requires the decoder, box, display and output chain to support the relevant profile.

The table does not override the HDR setting: when **TV supports HDR** is off and server transcoding is allowed, conversion to SDR still applies.

## Seek intervals

**Settings → Playback → Seek forward duration / Seek back duration** independently sets each step to 5, 10, 15, 30, 60 or 120 seconds. Defaults are 30 seconds forward and 10 seconds back. These apply to remote seek keys, Left/Right with controls hidden, and single steps on the progress bar. Holding a direction on the progress bar still accelerates seeking.

## Audio

- **Settings → Audio → Remember manual audio choices** controls local file and show memories (on by default). Turning it off ignores and stops updating audio memories while keeping existing records. **Clear audio memories** removes only audio records and keeps the switch state.

- **Passthrough is off by default.** All audio is decoded to PCM, and multichannel audio is downmixed to stereo: center and surround channels are mixed into left and right at -3 dB, and LFE is dropped. This allows speed changes and works with built-in TV and projector speakers. The downside is that Dolby Atmos information is lost.
- **Turn on Audio passthrough when you use a receiver.** AC3, EAC3 (including Atmos EAC3-JOC), DTS, TrueHD and similar formats are sent to the receiver as raw bitstreams. Which formats pass through depends on the HDMI capabilities the system reports. Passthrough tracks always play at 1x speed.

## Subtitles

- **Settings → Subtitles → Remember manual subtitle choices** independently controls primary subtitle memories, including Off (on by default). Disabling it keeps existing records while ignoring and not updating them. **Clear subtitle memories** removes only subtitle records and keeps the switch state. When enabled, each track type prefers file memory, then show memory, then language preferences, then the server selection.

- Choose **More → Secondary subtitles** to display a second track. Ordinary text is shown below the primary text, defaulting to 85% of its size. With an SRT / WebVTT secondary track selected, **More → Secondary subtitle style** adjusts its size, text color and background immediately and saves those preferences. ASS / SSA and bitmap subtitles retain their original styling and positions, so authored layouts may overlap; no style controls are provided for them. Both tracks share the subtitle delay setting. Secondary selection applies to the current video/version and resets when switching to another one.
- Secondary subtitles must be available in the current playback source. During transcoding, embedded tracks omitted from the output cannot be selected as secondary subtitles.
- During Direct Play:
  - SRT, ASS / SSA, WebVTT, PGS, VobSub and DVB subtitles are all rendered on the device.
  - ASS / SSA subtitles are rendered with libass, which keeps fonts, positioning and effects.
  - External SUP / PGS uses a dedicated local parser and seek-state warm-up path. Embedded MKV PGS uses compressed snapshots and asynchronous rendering to avoid PNG encoding and decoding on the video loading thread.
- For transcoded streams, see "Transcode output" above.

## Troubleshooting

- Open **Playback info** from the player menu to see the playback mode, the reasons for transcoding, the video and audio decoders, the passthrough state and the HDR state.
- Debug builds log each playback decision (`plan ... -> <mode> reasons=...`). Read it with `adb logcat -s LumyPlayer`. On devices with logcat disabled (such as XGIMI), the debug build also writes the log to a file:

  ```bash
  adb shell run-as com.hesier.lumy.debug cat files/debug.log
  ```
