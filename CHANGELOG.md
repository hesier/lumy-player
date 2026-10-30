# Changelog

English | [简体中文](CHANGELOG.zh-CN.md)

Each release has a `## <versionName> - <date>` section here and in the Chinese changelog. The release workflow uses both sections as the GitHub release notes, so add them before tagging. Changes that are not released yet go under `## Unreleased`; rename it to the new version when you bump `versionName`.

## 0.4.0 - 2026-10-31

### Playback

- Play RM/RMVB files locally without server transcoding: RV40 video prefers an available device decoder, and Cook audio uses FFmpeg
- New **Allow software video decoding** setting, off by default. Eligible H.264 (including High10) and RV40 SDR video can fall back to local decoding when hardware is unavailable or fails. Dimensions are limited to a longest edge of 1920 and a shortest edge of 1080; smooth playback still depends on the device and source
- Fixed MKV index discovery and video synchronization-point selection, addressing cases where resume or seeking showed a frozen picture despite buffered data
- Fixed repeated buffering and missing PCM output on certain TrueHD streams, including after seeking
- Dolby Vision P5 can try HEVC compatibility playback when native decoding is unavailable or fails. This can produce a picture on more devices, but does not correct P5 colors
- Fixed track selection for some embedded audio and subtitles, and added compatibility for some legacy external SRT encodings
- Fixed bitmap subtitle placement and proportions on cropped widescreen video, and multi-object PGS composition. External SUP/PGS subtitles now have local parsing and seek-state warm-up
- Reduced embedded MKV PGS processing on the video loading thread with compressed snapshots and asynchronous rendering; skips unselected PGS work when tracks can be safely matched
- Subtitle delay now applies to libass subtitles and refreshes paused cues. Superseded playback requests no longer replace a newer selection, and series playback stays within the selected season

### Interface and updates

- Reduced image and response-processing work during navigation; improved search/input D-pad behavior, navigation to library filters, and returning to detail-page actions
- Added server-type icons to the server switcher, sidebar dimming while browsing content, and a clock in player controls
- Cancels superseded update requests and isolates temporary downloads

### Documentation

- Added an anonymized [format compatibility and playback performance reference](docs/playback-compatibility-performance.md), including measured results and validation limits; updated DV P5 and subtitle guidance

### Known limitations

- Some starts and seeks still require several seconds of loading; this release does not eliminate all buffering or seek delays
- HEVC compatibility playback does not provide correct DV P5 colors or local HDR-to-SDR tone mapping
- PGS history is bounded to support subtitle-delay adjustments and may increase process memory. Full-file effects, cross-epoch seeks and fresh seeks with nonzero delay still need broader validation
- Actual listening, long-term A/V sync, receiver passthrough and seamless multi-Part playback have not been comprehensively validated

## 0.3.1 - 2026-10-29

### Playback

- Emby and Jellyfin: when the server has to transcode, embedded subtitles (including PGS and ASS) are now sent along and shown on this device instead of being burned in, so image subtitles are no longer squashed or misplaced. The original audio (such as TrueHD) is kept when this device can decode it
- Plex: switching subtitles while the server transcodes now takes effect; before, the previous subtitle stayed burned in
- Restarting playback (changing subtitles, audio or quality) no longer leaves extra sessions in the server's Now Playing list

## 0.3.0 - 2026-10-29

### Playback

- Resume and fast-forward now work in MKV files whose index the player could not find (common in concert and recorded videos), without server transcoding. Before, they always went back to the start
- In the movie player, press Down to open the chapter row. The chapters button sits right before More, and chapters without a thumbnail show their start time

### Interface

- New app icon and banner
- Moving focus up or down plays the focus sound

## 0.2.3 - 2026-10-28

### Updates

- Check for and install new versions from inside the app: once a day when the app opens, or from Settings › About. Download mirror routes the check and download through a GitHub proxy when GitHub is slow or blocked

## 0.2.2 - 2026-10-28

### Sign-in

- The Plex sign-in screen shows a QR code: scan it with a phone to open the link page with the code filled in

### Playback

- Playing from a playlist stays in the playlist: selecting an item starts it right away (Menu opens its details), the row below the player controls lists the playlist, and playback continues in playlist order
- Right after the player controls hide on their own, Back has to be pressed twice to leave the player
- The first card in the player's episode row is no longer cut off when focused

### Interface

- Holding a direction key moves twice as fast on the home screen, libraries and search
- Music and photo rows no longer appear on the Plex home screen
- Playlists sits with the libraries in the sidebar and can be reordered in Settings

## 0.2.1 - 2026-10-28

### Sign-in

- Jellyfin / Emby details can be typed on a phone: scan the QR code on the sign-in screen, and the TV fills in the form (thanks @zhiyxn)
- HTTPS servers with Let's Encrypt certificates now work on Android 5.0–7.0, whose built-in trust store lacks ISRG Root X1 (thanks @zhiyxn)

## 0.2.0 - 2026-10-28

### Playback

- Fixed silent DTS audio, files that couldn't be seeked, and hangs on Dolby Vision Profile 7
- Dolby Vision P7 now plays the HEVC base layer on devices without a Dolby Vision decoder
- Seek hints now show seconds only

### Detail page

- New file, video, audio and subtitle info cards
- Remembers which action button was focused
- Settings to hide cast, similar titles and file info

### Interface

- Smoother scrolling on home and library pages on slower devices
- New interface size setting
- Hidden libraries no longer appear in the sidebar order list; if the current library is hidden, the app returns home
- Device info in Settings
- Fixed a crash when the screen is recreated

### Docs

- New guide on [playback, transcoding, HDR and Dolby Vision support](docs/playback.md)

## 0.1.0 - 2026-10-27

First public release.

- Plex sign-in with a plex.tv PIN, server discovery and automatic address probing (local / remote / relay)
- Jellyfin and Emby sign-in with server address, username and password
- Multiple servers with a switcher at the top of the sidebar
- Home hubs, library grids with filters, sorting and an A–Z index, search, collections, playlists, cast filmographies and "More Like This"
- Detail pages for movies, shows, seasons and episodes, with version selection and watched state
- Media3 playback with direct play or HLS transcoding based on the device's decoders, FFmpeg audio decoding and AC3 / DTS passthrough
- Local ASS / SSA subtitle rendering, multi-part files, chapters, intro skipping and auto-play next episode
- Audio / subtitle delay, refresh-rate matching, and remembered audio and subtitle choices
- Light and dark themes, usable by remote or touch
- English, Simplified Chinese and Traditional Chinese
