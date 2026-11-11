# Changelog

English | [简体中文](CHANGELOG.zh-CN.md)

Each release has a `## <versionName> - <date>` section here and in the Chinese changelog. The release workflow uses both sections as the GitHub release notes, so add them before tagging. Changes that are not released yet go under `## Unreleased`; rename it to the new version when you bump `versionName`.

## 0.6.3 - 2026-11-11

### Playback and controls

- Fixed the selected episode, title and focus when returning to details after continuous playback, including transitions to another season
- Automatic next-episode countdowns now respect the credits skip setting. Without credits markers, the next-episode prompt appears only in the final 10 seconds and waits for playback to finish naturally; cancelling the countdown preserves the remaining credits
- Fixed horizontal D-pad focus escaping detail action buttons, season tabs, episode, cast and recommendation rows
- Fixed D-pad focus boundaries on the last Favorites category and the playlist Play All button

## 0.6.2 - 2026-11-07

### Android TV home screen

- Added Continue Watching and Next Up cards on compatible Android TV launchers, scoped to the current server and account, with direct playback from the home screen
- Added support for displaying server-authenticated poster artwork on the launcher
- Added Watch Next toggles, manual refresh and card clearing under Settings; automatically hides settings and stops background queries on unsupported devices

### Sign-in and browsing

- Added Quick Connect sign-in for Jellyfin when enabled on the server: enter the server address, then authorize the TV's pairing code from an already signed-in Jellyfin client
- Added library letter indexing for Jellyfin, Emby and fnOS Media when sorting by title, for fast navigation in large libraries
- Added fnOS cast filmography browsing and recommendations derived from shared cast and genres; Next Up episodes are derived from the episode playback records returned by the server

### Playback and controls

- Added a style entry in the subtitle menu to adjust ordinary text subtitles' size, vertical position and background, with immediate effect and saved preferences. These adjustments are unavailable for ASS / SSA, bitmap and burned-in primary subtitles
- Added danmaku settings panel to the playback menu to adjust display area, opacity, text size and scrolling speed during playback
- Added playback navigation hints, including hold-to-speed on the OK button and down-arrow shortcuts for episodes, chapters and playlists. Moved the chapters entry to More

### Improvements and fixes

- Reorganized player internals to simplify maintenance
- Merged rapid consecutive seeks that require a new transcoding stream to reduce repeated server requests and playback interruptions
- Optimized media reads, sidebar animations and settings category switches
- Improved recovery and manual confirmation when danmaku matching responses are too large
- Fixed visible comments disappearing, reappearing or changing lanes during continuous playback and automatic density adjustments
- Prevented failed Continue Watching requests from being treated as empty results on Plex and Jellyfin / Emby, preserving existing launcher cards when no reliable result is available

## 0.6.1 - 2026-11-03

### Danmaku and cards

- Fixed comments changing lanes or disappearing during paused refreshes and after seeking
- Danmaku now uses the full player viewport, including black bars around videos with a different aspect ratio, while respecting the configured display area
- Added round watched checkmarks to cards on Home, in libraries and in episode lists. Watched cards no longer show stale progress bars
- Episode cards show elapsed / total time while partially watched, and total duration when unwatched or finished

### Backup and restore

- Backup passwords no longer require eight characters. Empty passwords still save without encryption; new passwords may contain up to 32 characters, with a limit message only when exceeded
- File-picker exports and phone downloads include the device date and time in their filenames
- Reduced the password derivation cost for new encrypted backups to shorten creation and restore waits on TVs. Existing encrypted backups remain readable with their original derivation cost

### Compatibility

- New encrypted backups require Lumy 0.6.1 or later to restore. Older encrypted backups still take their original processing time; export them again after restoration to use the faster format

## 0.6.0 - 2026-11-03

### Danmaku

- Added playback danmaku, off by default, with DandanPlay, custom compatible APIs and Jellyfin / Emby server plugins. Automatic mode prefers plugin data and falls back to online sources when no valid data is available
- The Settings danmaku master switch hides the player entry and stops loading and display when off. When enabled, the player can independently toggle danmaku for the current movie / episode, remembered per server and account without changing the master or the whole season; clearing caches or matches preserves these choices
- Added a danmaku entry to playback controls for manual search and episode selection, reloading and restoring automatic matching. Manual choices are remembered per server, account, movie / episode and source, and take priority on the next playback
- Automatic matching checks titles, aliases, year, type and season / episode metadata, preserving server-confirmed episode mappings and timing offsets. Uncertain matches ask for manual confirmation without interrupting video
- Configure density, display area, text size, opacity, scrolling speed, stroke, comment types and a ±60-second timing offset. Sustained performance pressure can temporarily reduce density, then gradually restore it
- Local danmaku caches have no time expiry and can be reused offline, with a 64 MiB total limit. Cache and match-history clearing are separate actions; cache write failures no longer discard comments already loaded for playback

### Favorites and backup

- Added personal favorites synced with Jellyfin, Emby and fnOS Media. The sidebar page groups movies, shows and episodes; detail pages and card menus can add or remove favorites, and the sidebar entry can be hidden or reordered per server
- Added Settings → About → Backup and restore. Export app settings, servers and sign-in state, sidebar settings and track memories as a `.lumy` file, with optional password encryption
- Transfer backups by scanning a QR code on the same LAN, or use the system file picker for files / USB storage. Restore is also available when adding the first server, with a preview and selectable overwrite scopes; device-specific settings are preserved by default

### Playback and navigation

- Allow server transcoding and the new Available server bandwidth setting are saved separately for each server, preserving the previous transcoding preference on upgrade
- Uses 75% of available bandwidth as the combined audio/video target budget, requesting transcoding when the source exceeds it or its bitrate is unknown. Plex and Jellyfin / Emby reserve audio bandwidth; fnOS selects a server profile within budget. Manual quality limits still apply
- Starting or switching to server processing shows the transcoding / streaming reasons for about 6 seconds, consistent with playback information. Seeking or reconnecting with the same plan does not repeat the notice
- Danmaku feedback and transcoding reasons share an in-player notice with the app icon. Transcoding reasons take priority, followed by only the latest danmaku message; switching media or versions clears old notices
- Danmaku search and episode selection, More submenus and secondary-subtitle styles now return through their parent panels and restore focus. Returning to search results preserves the query and results without another network search
- Back from the sidebar now directly prompts for a second press to exit, without first returning to Home. Improved D-pad focus and IME completion in input dialogs

### Limitations

- DandanPlay requires proxy configuration at build time and may be rate-limited; custom APIs and server plugins remain available without it. Global danmaku preferences and source URLs are backed up, but per-media switches, comment caches and match records are not. See [danmaku guidance](docs/playback.md)
- Personal favorites are not provided for Plex. File / USB backup requires a system file picker. Lost backup passwords cannot be recovered, and expired sign-in state still requires signing in again
- Bandwidth settings are transcoding targets rather than strict network limits, and do not apply when server transcoding is disabled. fnOS audio reservations are estimates. See [server bandwidth](docs/playback.md)

## 0.5.1 - 2026-11-01

### Fixes

- Fixed missing button focus after pressing OK to pause or resume playback. The controls now focus the play/pause button when opened, and the clickable background no longer traps D-pad focus
- Playback controls now hide after 3 seconds of inactivity while paused as well as playing. Closing subtitle or other options panels restarts the timeout, and finishing a touch seek also restarts it

## 0.5.0 - 2026-11-01

### Servers and sign-in

- Added **fnOS Media** support: sign in with a media account to browse movies and TV shows, search, filter and sort, resume playback, sync progress and watched status, and use server transcoding. A bare IP address defaults to HTTP on port 8005
- Jellyfin, Emby and fnOS sign-in screens now show the phone-input QR code inline, with a refresh button

### Playback

- Added **secondary subtitles**: display two available tracks together, with a shared subtitle delay. Ordinary secondary text supports separate size, color and background settings; ASS / SSA and bitmap subtitles keep their original styling and positions
- Added an extra-small subtitle size
- Added configurable **hold-to-speed playback**: with the controls hidden, hold OK to speed up and release to restore the previous speed. Defaults to 2×; unavailable during audio passthrough
- Seek forward and back intervals can now be configured independently, defaulting to 30 seconds forward and 10 seconds back
- Audio and primary subtitle memories now have separate switches and clear actions, so clearing one keeps the other
- The progress bar can show total or remaining time on the right. Playback controls now hide after 3 seconds while playing

### Interface

- Fixed card focus jumps when returning to the home screen after playback; landscape cards now show the saved playback position
- Quickly press OK twice on a movie or episode card to open its details and start or resume playback; show cards play the next target episode once it is available
- Reorganized settings into Interface, Playback, Audio, Subtitles, Compatibility, Servers and About, and shortened descriptions

### Documentation and limitations

- Added a [server support matrix](docs/server-support.md) covering integration differences and validation limits
- fnOS checks cover media service version 0.8.43. Collections, playlists, cast filmographies and recommendations are not integrated; transcoding currently re-encodes video and targets stereo or 5.1 audio
- Secondary subtitles must be present in the current playback source. Transcoding may omit embedded tracks; ASS / SSA and bitmap layouts may overlap

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
