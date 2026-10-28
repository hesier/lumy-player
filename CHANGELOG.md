# Changelog

English | [简体中文](CHANGELOG.zh-CN.md)

Each release has a `## <versionName> - <date>` section here and in the Chinese changelog. The release workflow uses both sections as the GitHub release notes, so add them before tagging. Changes that are not released yet go under `## Unreleased`; rename it to the new version when you bump `versionName`.

## Unreleased

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
