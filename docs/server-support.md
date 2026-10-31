# Server Support Matrix

English | [简体中文](server-support.zh-CN.md)

Updated: 2026-11-01. Code baseline: `c2d5826`.

This page describes **the integrations currently implemented in Lumy Player**, rather than the complete capabilities of each server product. “Integrated” is based on code and API tests; availability still depends on server version, permissions, metadata and device capabilities. “Not integrated” does not mean the server itself lacks the feature.

See [Playback and Decoding](playback.md) for playback policy and device support, and the [compatibility and performance reference](playback-compatibility-performance.md) for anonymized playback measurements and their limits.

## Sign-in and connections

| Capability | Plex | Jellyfin | Emby | fnOS Media |
|---|---|---|---|---|
| Sign-in | plex.tv PIN | Address, username and password | Address, username and password | Address, media account and password |
| Defaults for a bare address | Discover servers through the account | HTTP / 8096 | HTTP / 8096 | HTTP / 8005 |
| Account server discovery and connection probing | Integrated, including local, remote and relay candidates | Manually supplied address | Manually supplied address | Manually supplied address |
| HTTPS, saved sessions and reachability checks | Integrated | Integrated | Integrated | Integrated |
| Phone QR input for credentials | Not applicable; uses PIN sign-in | Integrated | Integrated | Integrated |
| Additional sign-in methods | Plex Home user switching not integrated | Quick Connect not integrated | No additional flow | Does not use the NAS administrator sign-in flow |

All integrations support multiple saved entries, switching, renaming and removal. Phone input requires the phone and TV to share a LAN. See [TLS compatibility](tls-compatibility.md) for HTTPS on older Android versions.

## Home, libraries and details

| Feature | Plex | Jellyfin | Emby | fnOS Media |
|---|---|---|---|---|
| Movie and TV libraries | Integrated | Integrated | Integrated | Integrated |
| Continue Watching and resume positions | Integrated | Integrated | Integrated | Integrated through native records |
| Recently Added | Server-provided home rows | Integrated | Integrated | Integrated |
| Separate Next Up row | Server-provided home rows | Integrated; hidden when empty | Integrated; hidden when empty | Not integrated |
| Sort by title, added date, release date, rating and last viewed | Server query | Server query | Server query | First four use native queries; last viewed sorts locally |
| Watch status, genre and resolution filters | Server query | Server query | Server query | Native queries; complete SD filtering runs locally |
| Pagination | Server pagination | Server pagination | Server pagination | Native pagination for common queries; compatibility catalog mapping still required |
| First-letter quick index | Integrated | Not integrated | Not integrated | Not integrated |
| Search scope | Movies, shows, seasons and episodes | Movies, shows and episodes | Movies, shows and episodes | Movies and shows |
| Collections and their contents | Integrated | Integrated | Integrated | Not integrated |
| Video playlists and their contents | Integrated | Integrated | Integrated | Not integrated |
| Cast filmographies | Integrated within the source library | Integrated across libraries | Integrated across libraries | Not integrated |
| More Like This / recommendations | Integrated | Integrated | Integrated | Not integrated |
| Show, season and episode details and lists | Integrated | Integrated | Integrated | Integrated |
| Version selection | Integrated | Integrated | Integrated | Compatibility mapping implemented; not independently playback-tested |
| Mark watched / unwatched | Integrated | Integrated | Integrated | Integrated through native API |
| Auto-play next episode, including across seasons | Integrated | Integrated | Integrated | Integrated |

Home rows vary by integration. Plex uses server-provided rows; Jellyfin/Emby combine Continue Watching, Next Up and Recently Added in the client; fnOS combines Continue Watching and Recently Added. Empty rows are hidden.

Next-episode handling has boundaries: Plex takes the first episode of the immediately following season and does not continue past an empty season; Jellyfin/Emby depend on server ordering of all episodes; fnOS sorts season and episode numbers and skips subsequent empty seasons. These code paths do not establish playback validation for every special episode-ordering case.

## Playback, audio and subtitles

Transcoding requires permission in both the client and server. Allowing stream copy does not guarantee that the server will copy on every request.

| Capability | Plex | Jellyfin | Emby | fnOS Media |
|---|---|---|---|---|
| Direct Play, resume, audio selection and external subtitles | Integrated | Integrated | Integrated | Integrated |
| Quality / bitrate limits and fallback to transcoding | Integrated | Integrated | Integrated | Integrated |
| Server transcode output | HLS / TS | HLS / TS; MKV when retaining embedded subtitles | HLS / TS; MKV when retaining embedded subtitles | Native HLS with H.264 video requested |
| Remuxing with video stream copy | Integrated; server decides | Integrated; server decides | Integrated; server decides | Not verified; currently re-encodes video |
| Audio retained during transcoding | Negotiates AAC / AC3 / EAC3 / MP3 | Negotiates supported formats; MKV can advertise additional source codecs | Negotiates supported formats; MKV can advertise additional source codecs | Keeps AAC / AC3 / EAC3 when locally decodable; converts other formats to AAC |
| Transcode audio channels | Requested limits: AC3 5.1, EAC3 7.1 | Requests up to 8 channels | Requests up to 8 channels | Currently targets stereo or 5.1; no 7.1 output |
| Embedded subtitles retained for local rendering during transcoding | Not retained in HLS; use external tracks or burn-in | Conditional: selected track in MKV | Conditional: selected track in MKV | Primarily burn-in; no general MKV retention path |
| Server subtitle burn-in | Integrated | Integrated | Integrated | Integrated |
| HDR → SDR requests | Integrated; server tone mapping required | Integrated; server tone mapping required | Integrated; server tone mapping required | Integrated; HDR10 sample verified |
| Multi-part files, such as CD1 / CD2 | Integrated | Not integrated; uses the current media source | Not integrated; uses the current media source | Not integrated; uses the current media source |
| Chapters and chapter seeking | Conditional on chapter metadata | Conditional on chapter metadata | Conditional on chapter metadata | Compatibility mapping retained; unverified |
| Intro / credits markers | Conditional; reads server markers | Conditional; reads MediaSegments / chapter markers | Conditional; reads chapter markers | Compatibility mapping retained; unverified |
| Progress reporting and transcode session cleanup | Integrated | Integrated | Integrated | Integrated through native APIs |

- **Direct Play audio differs from transcoded audio.** Direct Play is not constrained by the transcoding formats or channel counts above. Counts describe the server output; the player defaults to stereo PCM decoding. Receiver passthrough also depends on HDMI capabilities, the device and the receiver.
- **Secondary subtitles are a shared player feature.** Only tracks present in the current source can be selected. Transcoding does not request an additional embedded track for secondary subtitles, so retaining two embedded subtitle tracks cannot be assumed.
- **Jellyfin/Emby MKV output** starts at the requested position; seeking rebuilds the stream. The selected embedded subtitle can be rendered locally, while the server still decides whether source audio can be copied.
- **Track memory has two layers.** Local file / show-language preferences are shared player features. Server memory uses a dedicated Plex save request, Jellyfin/Emby playback reports, or native fnOS records. Server user settings may affect persistence. Secondary selection applies only to the current video/version.

## fnOS APIs and validation limits

fnOS uses the independent `server-fnos` module and has no dependency on `server-jellyfin`. Its protocol uses both native APIs and the Jellyfin compatibility endpoints provided by fnOS itself.

| Area | Current implementation |
|---|---|
| Native APIs | Search, common filters/sorting/pagination, genres, progress and watched state, accurate stream metadata, transcode creation and cleanup |
| Compatibility APIs | Sign-in and reachability, libraries, catalog ID mapping, base detail metadata, seasons/episodes, Direct Play URLs, external subtitles and images |
| Local fallbacks | Last-viewed sorting uses compatibility `LastPlayedDate`; complete SD filtering uses video width |

Search and native page results still need the compatibility catalog to resolve playable file IDs. The catalog is cached for 30 seconds and invalidated by progress reports or watched-state changes, so initial loading of large libraries may still be slow. Last-viewed and SD queries additionally collect all native results before processing locally.

The fnOS checks in this integration were limited to **media service version 0.8.43**:

- Movie / TV browsing, search, filtering/sorting, resume, progress and watched-state synchronization, transcode startup, seeking, quality changes and subtitle changes were verified.
- AAC, AC3 and EAC3 preservation is supported by comparisons of source and output audio packets; 5.1 output was verified. A sample requested with 8 channels still produced 5.1, so 7.1 is not currently offered.
- HDR10 → SDR requests and output color metadata were verified; this does not validate color reproduction for every HDR / Dolby Vision source.
- Native HLS can carry the selected text subtitle; the current playback plan may also choose burn-in. Bitmap subtitles use burn-in, without general preservation of arbitrary embedded tracks.
- Collections, playlists, filmographies and recommendations remain unintegrated. A native local-filmography endpoint was successfully probed; no usable recommendation endpoint was found. This does not establish that the fnOS product lacks recommendations.
- Native chapter and intro/credits endpoints have not been integrated. These features and version selection have not received independent playback validation.

Native requests reuse the signed-in session and discover signing parameters from the server's public web assets. Changes to that web protocol may require an adapter update.

## Shared limitations

No integration currently offers music/photo browsing, Live TV / DVR, offline downloads, or collection/playlist creation and editing. Server type alone does not determine smooth decoding, correct HDR / Dolby Vision output or receiver passthrough.

The Plex, Jellyfin and Emby entries reflect the current implementations and API tests; they have not been revalidated against every server version. Findings about devices, versions and subtitle/audio combinations must retain their own validation scope.

## Implementation references

- [Shared server interface](../core/src/main/kotlin/com/hesier/lumy/core/MediaServer.kt)
- [Plex adapter](../server-plex/src/main/kotlin/com/hesier/lumy/plex/PlexMediaServer.kt)
- [Jellyfin / Emby adapter](../server-jellyfin/src/main/kotlin/com/hesier/lumy/jellyfin/JellyfinMediaServer.kt)
- [fnOS adapter](../server-fnos/src/main/kotlin/com/hesier/lumy/fnos/FnOsMediaServer.kt), [native queries](../server-fnos/src/main/kotlin/com/hesier/lumy/fnos/FnOsQueries.kt), [playback](../server-fnos/src/main/kotlin/com/hesier/lumy/fnos/FnOsPlayback.kt), [progress](../server-fnos/src/main/kotlin/com/hesier/lumy/fnos/FnOsProgress.kt)
- [Shared playback planner](../app/src/main/java/com/hesier/lumy/ui/player/PlaybackPlanner.kt)

When adding a capability, update the adapter, tests and this matrix together. Change an unverified status only after obtaining playback evidence for that specific behavior.
