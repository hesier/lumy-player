# Contributing to Lumy Player

Thanks for your interest! Bug reports, fixes and translations are all welcome.

## Reporting bugs

Please include:

- Device model and Android version
- Server type and version (Plex / Jellyfin / Emby)
- Steps to reproduce, and what you expected instead
- For playback issues: the media info (container, video / audio / subtitle codecs) and a log:

  ```bash
  adb logcat -s LumyPlayer LumyHome
  ```

## Building and testing

See [README](README.md#build-and-install) for requirements. Before opening a pull request:

```bash
./gradlew :core:test :net:test :server-plex:test :server-jellyfin:test
./gradlew :app:assembleDebug
```

Debug builds use the package `com.hesier.lumy.debug` (shown as "Lumy Debug"), so they install alongside the release build with separate data. They export `DetailActivity`, `PlayerActivity` and `SettingsActivity`, so they can be opened directly (the class names stay under `com.hesier.lumy`):

```bash
adb shell am start -n com.hesier.lumy.debug/com.hesier.lumy.ui.detail.DetailActivity --es id <itemId>
```

On devices with logcat disabled (e.g. XGIMI projectors), debug builds also write a log you can read with `adb shell run-as com.hesier.lumy.debug cat files/debug.log`.

## Architecture

- `core` is plain JVM: the `MediaServer` interface and media models. It must not depend on Android or on any specific server.
- `net` is plain JVM: networking that is not tied to a media server (the LAN receiver for phone input, extra TLS trust roots). It must not depend on Android.
- `server-plex` and `server-jellyfin` (shared by Jellyfin and Emby) implement `MediaServer`. They are plain JVM and tested with MockWebServer.
- `app` talks to servers **only** through `MediaServer`. When the UI needs a new capability, add it to the interface in `core` first, then implement it in each server module. Do not call server-specific APIs from `app`.
- For Jellyfin / Emby, prefer endpoints that exist in both and across versions; fall back on 404 where Jellyfin 10.9 moved things.

## Hard constraints

- **minSdk 21 (Android 5.0).** Check that any new or upgraded dependency still supports API 21. Media3 is pinned at 1.8.0 (1.9 requires API 23), and the FFmpeg extension and `ass-media` versions must match Media3. Guard newer platform APIs with `Build.VERSION.SDK_INT`.
- Only ARM ABIs are packaged.
- Cleartext HTTP must stay allowed — LAN servers are usually plain `http://192.168.x.x`.
- Release builds are minified. New `@Serializable` classes should live under the `com.hesier` package so `proguard-rules.pro` keeps their serializers.

## UI and remote control

- No Leanback, no Compose: Views + ViewBinding + RecyclerView.
- **Every screen must be fully usable with only D-pad, OK, Back and Menu.** Focus behaviour (sidebar left / right, per-row focus memory, up / down constraints, focus scale animation) has been tuned carefully. When you change a layout or a RecyclerView, verify it with the D-pad on a device or emulator and make sure focus is never lost and never jumps.
- Touch should keep working too, for boxes and projectors used with a mouse or touchscreen.
- Activities are landscape-only and handle `configChanges` themselves.

## Strings and translations

- Never hard-code user-visible text. `core` and the server modules cannot read Android resources; express displayable text as types (e.g. `HubLabel`, `PlaybackIssue`) and translate them in `app`.
- Every string must be added or changed in **all three** resource sets:
  - `values/` — English (fallback)
  - `values-zh/` — Simplified Chinese
  - `values-zh-rTW/` — Traditional Chinese (Taiwan wording)
- Do not create `values-zh-rHK` / `values-zh-rMO`; the build generates them from `values-zh-rTW`.

## Code style

- Official Kotlin style, coroutines with `lifecycleScope`, no DI framework.
- Keep comments short and focused on *why*.
- User preferences go in `data/Settings.kt`; server list and current server in `data/SessionStore.kt`. Library-specific settings are stored per server (`SavedServer.id`).
- Commit messages: short imperative English sentences, e.g. `Fix focus loss when the hub row reloads`.

## License

By contributing, you agree that your contributions are licensed under the [GPL-3.0](LICENSE).
