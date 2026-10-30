# Media3 FFmpeg local compatibility decoding

The bundled `media3-ffmpeg-decoder-1.8.0+1-lumy1.aar` replaces the native
ARM libraries of `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`.
The manifest, consumer rules and AAR metadata are unchanged. Cook support patches
`FfmpegLibrary` and `FfmpegAudioDecoder`; the other Java classes stay upstream.
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
python3 third_party/media3-ffmpeg/build.py --ndk "$ANDROID_NDK_HOME" --sdk "$ANDROID_HOME"
```

The script downloads checksum-verified inputs, builds both ARM ABIs, and
repackages the upstream AAR with patched native libraries and Cook Java classes.
Rebuilding also requires JDK 17 and Android SDK platform 35. Build
sources, tools' outputs and downloads stay under ignored `.local/native-ffmpeg/`.
It does not publish anything or modify the Gradle cache. Retain the patched
AAR in version control so clean app builds receive the fix.

Inputs are pinned to the original Jellyfin release:

- [Jellyfin v1.8.0+1](https://github.com/jellyfin/jellyfin-androidx-media/tree/84ef4b7201a9e1edfc699c31acbc505511a4af1f)
- [AndroidX Media JNI](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc)
- [FFmpeg 6.0 branch commit](https://github.com/FFmpeg/FFmpeg/tree/d388c347d41e4eb516dec05910551c5461e65615)
- The original Maven AAR and source SHA-256 checksums are embedded in `build.py`.

The enabled decoders retain Jellyfin's FLAC, ALAC, PCM A-law / mu-law, MP3, AAC,
AC3, EAC3, DTS, MLP and TrueHD, and add Cook, H.264 and RV40. The RM demuxer,
Cook parser and swscale are enabled; networking remains in the app DataSource.
The libraries keep 16 KiB ELF alignment.

## Local playback additions

`legacy_rm.cc` and `legacy_rm_io.h` expose RM demuxing through authenticated,
seekable Media3 DataSources. RV40 samples retain the FFmpeg slice table and
use the platform decoder first. Cook initialization keeps the original CSD
and a separate big-endian block alignment; `cook-java.patch` and
`cook-native.patch` supply both to the existing audio decoder. The TrueHD
initialization/reset patch is preserved.

`legacy_video.cc` and `legacy_video.h` provide a bounded send/receive video decoder
and SDR RGBA EGL/GLES2 Surface output for the app's SoftwareVideoRenderer. It drains B-frames
at EOS and flushes on seek. EGL surfaces are destroyed when video stops or the
output changes so the same Surface can be reused by MediaCodec. Only H.264 and RV40 are accepted, with a maximum long
edge of 1920 and short edge of 1080; unknown dimensions and HDR are rejected.
Video software decoding is opt-in and stays behind platform decoding. This size
limit is a safety ceiling, not a performance guarantee. Cook audio is independent
of the video software setting.

The Java patch inputs and compiler dependencies are checksum pinned in `build.py`.
Normal Gradle builds still use the packaged AAR without an NDK.

## Source and license notices

The original JNI and Java code are Copyright The Android Open Source Project,
licensed under [Apache 2.0](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/LICENSE).
FFmpeg is built without GPL components or external codec libraries and is licensed
under [LGPL 2.1 or later](https://github.com/FFmpeg/FFmpeg/blob/d388c347d41e4eb516dec05910551c5461e65615/COPYING.LGPLv2.1).
Jellyfin distributes its original AAR under GPL 3.0; Lumy is GPL 3.0 as well.
Corresponding source is available at the immutable links above and can be
retrieved with this build script. The complete local modifications are in
`truehd-layout.patch`, `cook-java.patch`, `cook-native.patch` and the `legacy_*`
JNI sources; the app repository's GPL license also covers these modifications.

## Regression checks

`TrueHdDecoderTest` runs with the project's platform instrumentation runner.
It uses generated mono, stereo and 5.1 sine-wave fixtures, checks single-AU
and 16-AU inputs, and exercises native reset before and after valid output.
Run it on both ARM ABIs when changing the AAR or patch. Verify real playback,
audio track changes and seeking separately on a device.

`SoftwareVideoDecoderTest` uses a generated five-frame High10 fixture with B-frames
and non-square pixels. It checks EOS, seek/flush, resolution rejection and Surface
output. `LegacyRmExtractorTest` covers transport and demuxing boundaries. Run these
through the platform instrumentation runner and verify real network playback,
audio sync, seeking, subtitle rendering and setting focus on a device.
