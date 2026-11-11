# Server Support Matrix

English | [简体中文](server-support.zh-CN.md)

Updated: 2026-11-11.

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
| Additional sign-in methods | Plex Home user switching not integrated | Quick Connect integrated; server must enable it | No additional flow | Does not use the NAS administrator sign-in flow |

Jellyfin Quick Connect displays a code to authorize from an already signed-in Jellyfin client after supplying the server address. This flow is covered by API tests; it has not been verified against every Jellyfin version.

All integrations support multiple saved entries, switching, renaming and removal. Phone input requires the phone and TV to share a LAN. See [TLS compatibility](tls-compatibility.md) for HTTPS on older Android versions.

## Home, libraries and details

| Feature | Plex | Jellyfin | Emby | fnOS Media |
|---|---|---|---|---|
| Movie and TV libraries | Integrated | Integrated | Integrated | Integrated |
| Continue Watching and resume positions | Integrated | Integrated | Integrated | Integrated through native records |
| Recently Added | Server-provided home rows | Integrated | Integrated | Integrated |
| Separate Next Up row | Server-provided home rows | Integrated; hidden when empty | Integrated; hidden when empty | Derived from returned episode playback records; hidden when empty |
| Sort by title, added date, release date, rating and last viewed | Server query | Server query | Server query | First four use native queries; last viewed sorts locally |
| Watch status, genre and resolution filters | Server query | Server query | Server query | Native queries; complete SD filtering runs locally |
| Pagination | Server pagination | Server pagination | Server pagination | Native pagination for common queries; compatibility catalog mapping still required |
| First-letter quick index | Integrated | Local counts for title sorting | Local counts for title sorting | Local counts in actual title-query order |
| Search scope | Movies, shows, seasons and episodes | Movies, shows and episodes | Movies, shows and episodes | Movies and shows |
| Collections and their contents | Integrated | Integrated | Integrated | Not integrated |
| Video playlists and their contents | Integrated | Integrated | Integrated | Not integrated |
| Cast filmographies | Integrated within the source library | Integrated across libraries | Integrated across libraries | Integrated through native pagination, with catalog mapping |
| More Like This / recommendations | Integrated | Integrated | Integrated | Derived locally from shared cast / genres |
| Show, season and episode details and lists | Integrated | Integrated | Integrated | Integrated |
| Version selection | Integrated | Integrated | Integrated | Integrated through native file lists, with independent tracks and playback URLs per version |
| Personal favorites (movies, shows, episodes) | Not offered | Integrated, server sync | Integrated, server sync | Integrated, native API sync |
| Mark watched / unwatched | Integrated | Integrated | Integrated | Integrated through native API |
| Auto-play next episode, including across seasons | Integrated | Integrated | Integrated | Integrated |

The Favorites sidebar entry can be hidden and reordered per server in Settings. Detail actions and card menus can add or remove favorites; the page groups them into movies, shows and episodes. Plex does not offer favorites, and its Watchlist is not mapped to favorites.

Home rows vary by integration. Plex uses server-provided rows; Jellyfin/Emby combine Continue Watching, Next Up and Recently Added in the client; fnOS combines Continue Watching, conditional Next Up and Recently Added. Empty rows are hidden. fnOS uses recently watched or in-progress episodes in the server’s returned `play/list` records as anchors and finds the following unwatched episode, including the episode after the current Continue Watching item. Current progress stays in Continue Watching; a candidate that already has progress is not repeated in Next Up. The endpoint is not assumed to provide complete watch history: if the server removes completed records, Lumy cannot reconstruct the full follow-up queue.

Jellyfin/Emby build the first-letter index by paging lightweight Items with the same library filters and server title order; counts merge adjacent equal initials without reordering items. The cache holds up to eight queries for 60 seconds, and watched-state changes invalidate filtered entries. Non-title sorting, scans exceeding 100,000 items or inconsistent pagination yield no index rather than partial jump offsets. This path is covered by API and unit tests, without a claim of validation on every server version.

fnOS builds its title-sorted first-letter index by paging the same query used for the grid, keeping the actual returned order and all active filters. It adds no separate index cache and reuses the existing catalog cache. Scans above 100,000 items, repeated entries, changing totals or incomplete catalog mapping hide the index instead of exposing unreliable jump offsets. This index is covered by unit tests; it has not been independently validated across server versions.

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
| Intro / credits markers | Conditional; reads server markers | Conditional; reads MediaSegments / chapter markers | Conditional; reads chapter markers | Conditional; reads native skip durations / compatibility chapter markers |
| Progress reporting and transcode session cleanup | Integrated | Integrated | Integrated | Integrated through native APIs |

- **Direct Play audio differs from transcoded audio.** Direct Play is not constrained by the transcoding formats or channel counts above. Counts describe the server output; the player defaults to stereo PCM decoding. Receiver passthrough also depends on HDMI capabilities, the device and the receiver.
- **Secondary subtitles are a shared player feature.** Only tracks present in the current source can be selected. Transcoding does not request an additional embedded track for secondary subtitles, so retaining two embedded subtitle tracks cannot be assumed.
- **Jellyfin/Emby MKV output** starts at the requested position; seeking rebuilds the stream. The selected embedded subtitle can be rendered locally, while the server still decides whether source audio can be copied.
- **Track memory has two layers.** Local file / show-language preferences are shared player features. Server memory uses a dedicated Plex save request, Jellyfin/Emby playback reports, or native fnOS records. Server user settings may affect persistence. Secondary selection applies only to the current video/version.

## fnOS APIs and validation limits

fnOS uses the independent `server-fnos` module and has no dependency on `server-jellyfin`. Its protocol uses both native APIs and the Jellyfin compatibility endpoints provided by fnOS itself.

| Area | Current implementation |
|---|---|
| Native APIs | Search, common filters/sorting/pagination, genres, cast filmographies, playback records, progress, watched and favorite state, accurate stream metadata, transcode creation and cleanup |
| Compatibility APIs | Sign-in and reachability, libraries, catalog ID mapping, base detail metadata, seasons/episodes, Direct Play URLs, external subtitles and images |
| Local fallbacks | Last-viewed sorting uses compatibility `LastPlayedDate`; complete SD filtering uses video width; recommendations use shared cast / genres; Next Up uses returned episode playback records |

Search, native page and cast-filmography results still need the compatibility catalog to resolve playable file IDs. The catalog is cached for 30 seconds and invalidated by progress reports or watched-state changes, so initial loading of large libraries may still be slow. Last-viewed and SD queries additionally collect all native results before processing locally.

Local fnOS recommendations use the compatibility catalog, keep the same movie/show type, exclude the source and its native media versions, and return up to 20 matches ranked by shared cast / genres and year distance. Missing related metadata produces no recommendations. This is a client heuristic, not a native recommendation feed. Unit tests cover filtering and ranking; the emulator integration check below covers this specific server instance.

The fnOS checks in this integration were limited to **media service version 0.8.43**:

- Movie / TV browsing, search, filtering/sorting, resume, progress and watched-state synchronization, transcode startup, seeking, quality changes and subtitle changes were verified.
- AAC, AC3 and EAC3 preservation is supported by comparisons of source and output audio packets; 5.1 output was verified. A sample requested with 8 channels still produced 5.1, so 7.1 is not currently offered.
- HDR10 → SDR requests and output color metadata were verified; this does not validate color reproduction for every HDR / Dolby Vision source.
- Native HLS can carry the selected text subtitle; the current playback plan may also choose burn-in. Bitmap subtitles use burn-in, without general preservation of arbitrary embedded tracks.
- Native cast-filmography pagination was verified against this service version and is now integrated. `ServerCatalogInstrumentation` also exercised native cast filmographies, local recommendations and Next Up on an emulator against this instance; that result does not cover all service versions or devices.
- Collections and playlists remain unintegrated: no usable native routes were found on the tested 0.8.43 instance, and compatibility queries returned empty results. No usable native recommendation endpoint was found; recommendations instead use the local metadata heuristic above. These findings do not establish that the fnOS product lacks those features.
- Next Up also has unit tests for returned episode playback records; neither these tests nor the emulator check establish that the server provides complete watch history or validate every show’s follow-up queue.
- Version selection enumerates native `media/itemfile` entries. Files can share one compatibility `MediaSourceId`; Lumy distinguishes versions by file ID and uses the selected file for direct playback, transcoding and progress reports. The local Zootopia 4K HDR10 / 1080p SDR pair passed independent file reads and native transcode manifest checks. Emulator remote input switched 1080p → 4K → 1080p, rendered each source through 720p transcoding and retained playback position. Progress writes are covered by unit tests and were suppressed during live playback checks. This does not validate every version combination.
- Intro/credits support reads native `play/info.play_config.skip_opening` / `skip_ending` in seconds, measuring the intro from zero and credits backward from the file end. Configure these durations per season in the fnOS web or official client; Lumy only reads them, with no configuration UI or write-back calls. The shared off / button / automatic skip modes, next-episode countdown and cancellation rules apply. Valid native markers take precedence over compatibility markers of the same type; null, zero and invalid values create no native marker. Tail markers follow the selected file duration on version switches. These are custom skip durations, not automatic detection. Local Dragon Raja Season 1 returns a 46-second intro and 40-second ending; Season 2 is unset. Native chapter endpoints remain unintegrated.
- The Dragon Raja configuration passed emulator playback checks for the intro button and automatic seek, credits button, off mode, paused next-episode countdown and remote-back cancellation. Server progress writes were suppressed. Regression checks cover switching between different file durations and invalid ranges; this does not validate every show's configuration.

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
