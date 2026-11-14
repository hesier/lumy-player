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

## Data slice boundaries

Upstream `DataSystem.updateCurrentSlice()` passes its boundary search results
directly to `subList(start, end)`. The end search never returns `size`, so the
last loaded comment is excluded even when its timestamp lies inside the slice.
At a 60-second app window handoff, appending later comments finally admits that
old tail. It may already be seconds late and first appear inside the screen.
Exact timestamp matches also produce incorrect indices in the upstream searches.

`PatchData.java` redirects only these two searches to `LumyDataBounds.java`.
The lower bound includes all items at the start timestamp; the upper bound is
exclusive and includes all items at the end timestamp, returning `size` when
appropriate. Empty ranges remain valid empty slices. Rendering coordinates,
comment timestamps and lane assignments are unchanged.

## Bounded scheduling and cache work

The production and debug controllers drive the existing engine in its upstream manual mode.
`DanmakuFrameDriver` owns one serial worker and its Choreographer. Pausing stops
continuous callbacks while retaining draw snapshots and text caches. Seeking,
layout changes and cache completion request coalesced, finite updates. The UI
acknowledges snapshot consumption before the next update may recycle it. All Ak
instances in this app use manual mode; this process-wide upstream flag is
not suitable for mixing unrelated automatic/manual Ak players.

Paused cache refreshes invalidate only the render snapshot, preserving existing
lane assignments and visibility. Adaptive density changes preserve cached admission
and lanes for existing entities, including the short pre-admission buffer; later
entities use the new divisor. They do not invalidate filter/layout generations,
which would remove, revive or move comments already on screen. Seek still invalidates
layout and its retainer together.
The comment layer uses PlayerView's full-screen overlay rather than its
aspect-ratio-constrained video content frame.

Continuous window handoffs append only timestamps beyond the previous window's
end to the same engine. The serial worker trims expired input before the new
12-second preroll and refreshes slice indices without clearing live entities.
`AkDanmakuAccess.java` uses version-pinned internal JVM APIs, plus constant-field
reflection for `DataSystem.sortedData` and `forceUpdate` only at window handoff.
An Ak version upgrade must revisit this bridge; release/R8 checks must preserve
these reflected fields. Normal frame updates do not use reflection.
The app's ProGuard rules retain ECS system and pooled component constructors,
which Ak/Ashley invoke reflectively; without them R8 can remove engine startup.

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
then applies the release, cache and data slice patches with checked hook counts
and compiles the runtime helpers for Java 8/API 21. Inputs and compiler
outputs stay under ignored `.local/akdanmaku/`. Normal app builds use the bundled
AAR; they do not download ASM or run this script. Gradle declares the original
libGDX/Ashley runtime dependencies explicitly because a local AAR has no POM.
The app's existing ARM ABI filters remain in effect.

Expected patched AAR SHA-256:
`962920cae875d12d26f86cbd99a262343be66f779550a631851c96759bdfa524`.

Run the data slice regression without a device after Gradle has cached the app's
Kotlin, Ashley and libGDX dependencies:

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ANDROID_HOME="/path/to/android-sdk" \
  python3 third_party/akdanmaku/tests/test_data_slice.py
```

The test executes the bundled AAR's actual private slice method. Host stubs replace
only Android logging and timestamp-only comment items; it does not test drawing.
It covers the tail before/after window append, empty/single slices, duplicate and
exact timestamps, and 500 deterministic random slices against a linear oracle.
Use `--aar /path/to/old.aar` to reproduce the failure on the previous runtime.

See [the performance guide](../../docs/playback-compatibility-performance.md#12-danmaku-performance-probe)
for measurements, device checks and the instrumentation command.
