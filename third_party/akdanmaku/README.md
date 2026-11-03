# AkDanmaku bounded runtime

The shared production/debug renderer uses `akdanmaku-1.0.3+1-lumy1.aar`, based on
[`com.kuaishou:akdanmaku:1.0.3`](https://github.com/KwaiAppTeam/AkDanmaku).
Release builds include the runtime and libGDX. The player no longer exposes a performance-test menu;
generated fixtures remain available only to debug automation.
The upstream MIT notice is bundled in main assets at
`third-party/AkDanmaku-LICENSE.txt`.

## Release waiter fix

Upstream `DanmakuPlayer.release()` sets `started=false` and calls
`HandlerThread.quitSafely()`. A worker already waiting in
`drawSemaphore.acquire()` cannot process the quit message. Repeated profile
switches left multiple `ActionThread:WAITING` workers in the emulator probe.

The patch inserts `drawSemaphore.release()` immediately before `quitSafely()`.
The waiting worker wakes, observes `started=false`, returns and can exit its
looper. Upstream engine release also omits `context.cacheManager.release()`, leaving
`AkDanmaku-Cache` workers alive. A second patch calls that existing cleanup method
at the end of `DanmakuEngine.release()`.

## Bounded scheduling and cache work

The production and debug controllers drive the existing engine in its upstream manual mode.
`DanmakuFrameDriver` owns one serial worker and its Choreographer. Pausing stops
continuous callbacks while retaining draw snapshots and text caches. Seeking,
layout changes and cache completion request coalesced, finite updates. The UI
acknowledges snapshot consumption before the next update may recycle it. All Ak
instances in this app use manual mode; this process-wide upstream flag is
not suitable for mixing unrelated automatic/manual Ak players.

Paused cache refreshes invalidate only the render snapshot, preserving existing
lane assignments and visibility. Seek and runtime density changes invalidate layout
and its retainer together; upstream clears visibility before re-layout, so retaining
old lane entries across that invalidation can otherwise hide visible comments.

`AkDanmakuAccess.java` uses the published JVM names of version-pinned internal
APIs without reflection. An Ak version upgrade must revisit this bridge.

`PatchCache.java` modifies CacheManager and its CacheHandler to admit at most eight
measure/build tasks per update and 64 waiting jobs per manager, plus one active
job. `LumyCacheBudget.java` restores rejected/cancelled transient states so they
can retry. Successful queue drain coalesces a paused refresh; close rejects new
work and clears callbacks. Counters report queue depth/peak, rejected attempts
(including retries), completed work and worker elapsed nanoseconds. They are not
unique dropped-comment counts or thread CPU time. Normal requests/cancellation
must remain serialized through the driver. The existing native libraries and
layout/rendering algorithms are unchanged.

The strategy was reviewed against
[blbl at cd09bd9](https://github.com/cat3399/blbl/tree/cd09bd903e6d0c22f5986580c2105796d8df6471/app/src/main/java/blbl/cat3399/feature/player/danmaku).
No blbl source is copied into this patch.

## Reproduce

Requires JDK 17, Python 3, Android SDK platform 35 and network access to Maven Central:

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ANDROID_HOME="/path/to/android-sdk" \
  python3 third_party/akdanmaku/build.py
```

`build.py` checks pinned SHA-256 hashes for the upstream AAR and ASM 9.7.1,
then applies the release and cache patches with checked hook counts and compiles
the runtime helper for Java 8/API 21. Inputs and compiler
outputs stay under ignored `.local/akdanmaku/`. Normal app builds use the bundled
AAR; they do not download ASM or run this script. Gradle declares the original
libGDX/Ashley runtime dependencies explicitly because a local AAR has no POM.
The app's existing ARM ABI filters remain in effect.

Expected patched AAR SHA-256:
`fee88cb172ac3a7089023d4469f0a5dae9cb97f4d353d10a6c9c6249667259c3`.

See [the performance guide](../../docs/playback-compatibility-performance.md#12-danmaku-performance-probe)
for measurements, device checks and the instrumentation command.
