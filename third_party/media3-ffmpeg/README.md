# Media3 FFmpeg TrueHD initialization patch

The bundled `media3-ffmpeg-decoder-1.8.0+1-lumy1.aar` replaces the native
ARM libraries of `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`.
Its Java classes, manifest, consumer rules and AAR metadata are unchanged.
Only `armeabi-v7a` and `arm64-v8a` are included, both built for API 21.
Normal app builds use this AAR and do not require an NDK or network access
beyond the app's usual Gradle dependencies.

## Why this patch exists

FFmpeg 6.0 can require the container's initial channel count to identify
a single-substream TrueHD layout. The upstream JNI supplies that information
only for PCM A-law / mu-law, so affected TrueHD tracks produce invalid-data
results without PCM. `truehd-layout.patch` supplies positive TrueHD channel
counts and preserves the count when a seek recreates the native context.
Other codecs retain their initialization paths. This keeps local PCM decoding
and does not require server transcoding or a Media3 upgrade.

## Rebuilding

Requires Python 3.12+, `make`, `patch`, and Android NDK **26.1.10909125**.
On macOS the NDK toolchain also requires the host's normal developer tools.
Run from the repository root:

```sh
python3 third_party/media3-ffmpeg/build.py --ndk "$ANDROID_NDK_HOME"
```

The script downloads checksum-verified inputs, builds both ARM ABIs, and
repackages the upstream AAR with only the patched native libraries. Build
sources, tools' outputs and downloads stay under ignored `.local/native-ffmpeg/`.
It does not publish anything or modify the Gradle cache. Retain the patched
AAR in version control so clean app builds receive the fix.

Inputs are pinned to the original Jellyfin release:

- [Jellyfin v1.8.0+1](https://github.com/jellyfin/jellyfin-androidx-media/tree/84ef4b7201a9e1edfc699c31acbc505511a4af1f)
- [AndroidX Media JNI](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc)
- [FFmpeg 6.0 branch commit](https://github.com/FFmpeg/FFmpeg/tree/d388c347d41e4eb516dec05910551c5461e65615)
- The original Maven AAR and source SHA-256 checksums are embedded in `build.py`.

The enabled decoders match Jellyfin's release: FLAC, ALAC, PCM A-law / mu-law,
MP3, AAC, AC3, EAC3, DTS, MLP, and TrueHD. The libraries keep 16 KiB ELF alignment.

## Source and license notices

The original JNI and Java code are Copyright The Android Open Source Project,
licensed under [Apache 2.0](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/LICENSE).
FFmpeg is built without GPL components or external codec libraries and is licensed
under [LGPL 2.1 or later](https://github.com/FFmpeg/FFmpeg/blob/d388c347d41e4eb516dec05910551c5461e65615/COPYING.LGPLv2.1).
Jellyfin distributes its original AAR under GPL 3.0; Lumy is GPL 3.0 as well.
Corresponding source is available at the immutable links above and can be
retrieved with this build script. The complete local modifications are in
`truehd-layout.patch`; the app repository's GPL license also covers this patch.

## Regression checks

`TrueHdDecoderTest` runs with the project's platform instrumentation runner.
It uses generated mono, stereo and 5.1 sine-wave fixtures, checks single-AU
and 16-AU inputs, and exercises native reset before and after valid output.
Run it on both ARM ABIs when changing the AAR or patch. Verify real playback,
audio track changes and seeking separately on a device.
