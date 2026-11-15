# LoopCast — Senior-Grade Build Guide (Android · Kotlin)

> **1 step = 1 prompt = 1 test = 1 commit.** Never batch steps. Never move on while a gate is red.
> App: pick a video → loop it forever → push it to YouTube Live over RTMP(S). No login, no backend, no database.
> Stack: Kotlin · Jetpack Compose · Media3 ExoPlayer · RootEncoder · H.264 + AAC · Coroutines/Flow · DataStore · JUnit/MockK/Turbine

---

## 0. READ FIRST — Verified facts and what I'm changing in your plan

### Facts I checked (Nov 2026 — re-verify versions the day you start)

| Topic | What I found |
|---|---|
| **RootEncoder can stream a video *file* natively** | It decodes the file (video + audio), encodes H.264/AAC and pushes RTMP. `VideoFileSource` / `AudioFileSource` exist (added ~v2.3.8–2.3.9), older `FromFile` classes had loop-mode fixes in 2.3.x, and the repo has a `streamer/file/FromFileActivity.kt` sample. Latest versions I saw are in the 2.8.x range → **pin whatever JitPack shows as latest stable** |
| **Media3** | Latest stable on the release page: **1.10.1** (a 1.11 alpha exists). All Media3 modules must use the same version |
| **YouTube ingest** | RTMPS is recommended. Primary `rtmps://a.rtmp.youtube.com:443/live2`, plain `rtmp://a.rtmp.youtube.com/live2` (port 1935), backup host `b.rtmp.youtube.com/live2?backup=1`. **Stream key goes in a separate field, not appended to the URL by the user.** CBR, keyframe every 2 s (never > 4 s), AAC audio, H.264 |
| **Android 15 foreground-service limits** | `dataSync` and `mediaProcessing` FGS types are capped at **6 h per 24 h** for apps targeting API 35+. A 24/7 looper must use `mediaPlayback`, never `dataSync` |

### Changes to your original plan (and why)

| # | Original plan | Problem | Decision |
|---|---|---|---|
| 1 | ExoPlayer plays video **and** its frames/audio are fed to RootEncoder | ExoPlayer doesn't hand you raw PCM audio or an encoder-ready video surface easily — this is the "biggest unknown" you already flagged | **Spike first (Module 5).** Expected winner: let RootEncoder's own file sources decode + loop + encode. ExoPlayer is then only the idle preview (released when streaming starts) so you never run two decoders |
| 2 | "1–2 days" | That's a working demo, not a stream that survives 12 hours | Realistic: **MVP demo ≈ 2–3 days (Modules 0–7)**, reliable 24/7 ≈ 40–50 h total. Timeline table in §2 |
| 3 | Streaming lives in the Activity | Lock screen / app switch kills it | **Foreground service** (`mediaPlayback`) + wake/Wi-Fi locks. Service only keeps the process alive; a singleton `StreamController` owns the logic |
| 4 | No reconnect story | Mobile networks drop constantly | Reconnect with backoff + network monitor + backup ingest |
| 5 | Stream key typed in plain text, URL pasted whole | Key leaks in logs/backups; users paste `rtmp://…/live2/KEY` into the URL field | `StreamKey` value class (masked `toString`), URL parser that splits pasted full URLs, RTMPS default, encrypted storage, no-log audit |
| 6 | Test straight against YouTube | Can't tell *app bug* from *YouTube config* | **Local RTMP sandbox (MediaMTX) first**; verify with `ffplay`/`ffprobe` |
| 7 | "Select video from gallery" (storage permission) | Legacy permission dance; URI access can vanish | Photo Picker (no permission) and **copy into app-private storage** once |
| 8 | (Implicit) mic permission | Audio comes from the file | **No `RECORD_AUDIO` permission.** Smaller privacy surface |
| 9 | — | Hilt/Room/Navigation add weight for one screen | Manual DI via `Application`, DataStore, single screen + bottom sheet |
| 10 | — | Heat, battery, mobile-data burn | Thermal monitor, "charge your phone" + cellular data warning (≈ 2 GB/hour at 4.5 Mbps), optional auto-stop timer |
| 11 | — | Looping other people's content | YouTube Content ID / "repetitious content" rules can block or demonetize. Use your own content |

---

## 1. Architecture rules (these ALSO go in `.github/copilot-instructions.md` in step 1.4)

```
UI (Compose, stateless) → ViewModel (StateFlow<UiState>) → StreamController (app singleton, owns state machine)
                                                              ↓
                                           StreamEngine (interface) → RootEncoderEngine  (ONLY file importing com.pedro.*)
domain/  = pure Kotlin (no android.* imports) → JVM unit tests, fast
Service  = keeps process alive + notification. No business logic.
```

```
com.loopcast
 ├─ LoopCastApp.kt, MainActivity.kt
 ├─ domain/      StreamKey, RtmpEndpoint, EncoderProfile, StreamState(+reduce), ReconnectPolicy, BitratePolicy, StreamError
 ├─ data/        media/ (VideoImporter, VideoProbe), prefs/ (SettingsStore, KeyVault)
 ├─ engine/      StreamEngine, RootEncoderEngine, FakeStreamEngine
 ├─ stream/      StreamController, StreamService, NetworkMonitor, ThermalMonitor
 └─ ui/          theme/, home/ (HomeScreen, HomeViewModel, components/)
```

| Budget | Limit |
|---|---|
| Composable | ≤ 80 lines, stateless (state + lambdas in) |
| ViewModel | ≤ 150 lines; no Android framework types except `Application`-free helpers |
| Function | ≤ 30 lines |
| Files | one public class per file |
| Forbidden | `!!`, `GlobalScope`, `Thread.sleep`, logging the stream key or full URL, hard-coded dispatchers in classes (inject them), magic numbers (constants in `domain/Defaults.kt`) |

**Non-negotiables:** state changes only through the pure `reduce()` · every `domain/` class ships with a JVM test in the same step · only `RootEncoderEngine` touches the library · all long work is cancellable · resources (`ExoPlayer`, encoder, locks) are released in exactly one place each.

---

## 2. Build order, time estimates, gates

| # | Module | Est. | Gate |
|---|---|---|---|
| 0 | Pre-flight: device, YouTube, local RTMP sandbox, test videos | 2–4 h (+ YouTube verification wait) | `ffplay` plays a looped test stream from your laptop |
| 1 | Project setup, deps, manifest, rules | 1–2 h | App launches, unit test runs |
| 2 | Domain core (pure Kotlin) | 3–4 h | JVM tests green |
| 3 | Video pick, import, probe | 3 h | Pick → copy → probe info shown |
| 4 | Loop playback (ExoPlayer, idle preview) | 2 h | Video loops forever on device |
| 5 | **Spike**: RootEncoder file source → local RTMP | 3–5 h (timebox) | Decision recorded (ADR) |
| 6 | Engine + controller (production code) | 4–6 h | Controller tests green + real stream to MediaMTX |
| 7 | UI + ViewModel | 4–5 h | Full flow on device against MediaMTX |
| 8 | Foreground service + locks | 3–4 h | Stream survives screen-off 10 min |
| 9 | Resilience (network, reconnect, ABR, thermal) | 5–7 h | Chaos tests pass |
| 10 | YouTube E2E + soak | 4 h + overnight | 6 h soak passes, YouTube health "Good" |
| 11 | Security, privacy, polish | 3–4 h | Key never appears in logcat |
| 12 | Release | 2 h | Signed release build streams |

> **MVP demo = Modules 0–7.** Don't ship to anyone before Module 8 and 9.

---

## 3. Debug protocol (when a gate is red)

1. **Stop.** Don't start the next step.
2. Give the AI: failing command/output + `adb logcat` excerpt + the files from *this step only*. Say "fix only this".
3. Two failed attempts → `git reset --hard HEAD` (every green step is a commit) and re-run the prompt with the failure appended.
4. Never weaken a test to pass unless the test is wrong — say why in the commit.

**Useful commands**
```bash
adb logcat -s LoopCast:* AndroidRuntime:E        # our tag only (our Log wrapper uses tag "LoopCast")
adb shell dumpsys meminfo com.loopcast           # memory
adb shell dumpsys thermalservice | head -50      # thermal status
ffprobe -v error -show_streams rtmp://<laptop-ip>/live/test    # what are we really sending?
ffplay -fflags nobuffer rtmp://<laptop-ip>/live/test           # watch the stream
```

**Standard prompt footer** (append to any prompt if the AI drifts):
```text
Follow .github/copilot-instructions.md. Create/modify ONLY the files listed. Do not invent library API:
if you are unsure a method exists, say so and show me where to check it. Do not add dependencies without
saying why. When done, list files changed and anything you were unsure about.
```

---

# MODULE 0 — Pre-flight (no app code yet)
> Goal: remove external unknowns (YouTube account, network, test media) *before* writing code.

### Step 0.1 — Tooling and devices
```text
Checklist (no prompt needed):
 - Android Studio: current stable. Use its bundled JDK.
 - A PHYSICAL Android phone (Android 10+; ideally also a second, weaker one). Emulators are useless for MediaCodec
   performance, thermals and RTMP behaviour. Enable Developer options + USB debugging; `adb devices` shows it.
 - Write down in docs/DEVICES.md: model, Android version, chipset, RAM. This is your test matrix.
```
**Test:** `adb devices` lists the phone; a blank Android Studio sample runs on it. ✅

### Step 0.2 — YouTube channel ready (start NOW — it can take up to ~24 h)
```text
 1. YouTube → profile → Create → Go live. Verify the channel if asked (phone verification). Live access can take up to 24 h to activate.
 2. YouTube Studio → Go live → "Stream" tab (encoder streaming, NOT "Webcam"). Create a stream with a REUSABLE stream key
    and visibility "Unlisted" or "Private" for testing.
 3. Note: Stream URL (RTMP and the RTMPS variant via the lock icon) and Stream key (treat as a password; rotate it if it ever shows up in a screenshot/log).
 4. Only use videos you own (Content ID / repetitious-content policies).
```
**Test:** YouTube Studio shows the stream waiting for data ("No data" state) ✅

### Step 0.3 — Local RTMP sandbox (MediaMTX)
```text
On your laptop (Docker):
  docker run --rm -it -p 1935:1935 -p 8554:8554 -p 8888:8888 bluenviron/mediamtx
Find your laptop LAN IP; phone and laptop on the same Wi-Fi; allow inbound TCP 1935 in the laptop firewall.
Prove the sandbox itself works BEFORE involving Android:
  ffmpeg -re -stream_loop -1 -i test720.mp4 -c copy -f flv rtmp://localhost:1935/live/test
  ffplay -fflags nobuffer rtmp://localhost:1935/live/test
```
**Test:** ffplay plays the looping test file. ✅ (If not, fix this first — otherwise every later failure is ambiguous.)

### Step 0.4 — Test videos (the compatibility matrix)
```text
Create with ffmpeg from any source clip (keep each ≤ 30 s, short loops expose seam bugs):
 A. test720.mp4        — 1280x720, 30 fps, H.264 + AAC
 B. test1080.mp4       — 1920x1080, 30 fps, H.264 + AAC
 C. portrait.mp4       — 1080x1920, H.264 + AAC
 D. hevc.mp4           — 1080p HEVC + AAC
 E. noaudio.mp4        — H.264, NO audio track  (ffmpeg -an)
 F. vfr.mp4            — variable frame rate (phone-recorded clip)
 G. long.mp4           — ~2 GB file, 10+ minutes (copy-time and storage edge case)
Put them on the phone (Downloads / gallery) and note them in docs/DEVICES.md.
```
**Test:** every file plays in the phone's stock gallery. Commit docs: `docs: devices + test media`

---

# MODULE 1 — Project Setup
> Goal: empty-but-professional project: pinned deps, correct permissions, rules for the AI assistant.

### Step 1.1 — Create the project
```text
Create a new Android Studio project: "Empty Activity" (Jetpack Compose / Material3), name LoopCast, package com.loopcast,
language Kotlin, Gradle Kotlin DSL, minSdk 26, targetSdk/compileSdk = the current stable API level Android Studio proposes.
Do not add any feature code. Initialise git and commit.
```
**Test:** app runs on the phone showing the template screen. Commit `chore: scaffold`.

> **DONE TILL HERE (initial marker):** Step 1.1 scaffold created and `assembleDebug` passed.

### Step 1.2 — Version catalog + dependencies (pinned)
```text
Edit gradle/libs.versions.toml and app/build.gradle.kts. Add (versions as variables in the catalog, none hard-coded in the app file):
  androidx.media3:media3-exoplayer, media3-ui  — SAME version, currently 1.10.1 (check the Media3 release page; do not mix versions)
  com.github.pedroSG94.RootEncoder:library     — latest stable from JitPack/GitHub Releases (I will tell you the number; do not guess)
  androidx.lifecycle:lifecycle-runtime-compose, lifecycle-viewmodel-compose
  androidx.datastore:datastore-preferences
  org.jetbrains.kotlinx:kotlinx-coroutines-android  + kotlinx-coroutines-test (test)
  junit, io.mockk:mockk, app.cash.turbine:turbine (test)
In settings.gradle.kts add the JitPack maven repository inside dependencyResolutionManagement.repositories (https://jitpack.io).
Do NOT add Hilt, Room, Retrofit, Navigation, or any analytics.
```
**Test:** Gradle sync OK; `./gradlew assembleDebug` OK; method count irrelevant. Commit `chore: dependencies`.

### Step 1.3 — Manifest, backup rules, theme
```text
AndroidManifest.xml:
  permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, WAKE_LOCK, FOREGROUND_SERVICE,
               FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS.   (NO RECORD_AUDIO, NO storage permissions.)
  <application>: android:allowBackup="false", android:dataExtractionRules pointing to res/xml/data_extraction_rules.xml that excludes everything
                 (cloud-backup and device-transfer), android:usesCleartextTraffic="false"  (RTMP is not HTTP, so this is fine).
  MainActivity: exported=true (launcher only), windowSoftInputMode adjustResize; keep configChanges default (we will survive rotation by design).
Theme: Material3 dynamic color with dark theme support. No custom fonts.
```
**Test:** builds; Settings → Apps shows no permission prompts at install. Commit `chore: manifest hardening`.

### Step 1.4 — AI rules file + code quality
```text
Create .github/copilot-instructions.md with EXACTLY:
 - Kotlin, Jetpack Compose, Coroutines/Flow. No `!!`, no GlobalScope, no Thread.sleep.
 - Layers: ui -> viewmodel -> StreamController -> StreamEngine(interface) -> RootEncoderEngine. Only RootEncoderEngine imports com.pedro.*.
 - domain/ is pure Kotlin: no android.*, no Context, no Log. Every domain class has a JVM unit test written in the same change.
 - Composables <= 80 lines and stateless (state down, events up). ViewModels <= 150 lines. Functions <= 30 lines.
 - State changes only through the pure function reduce(state, event). Illegal transitions are ignored and reported, never crash.
 - Never log the stream key or full RTMP URL. Use the Log wrapper in util/Log.kt (tag "LoopCast", redacts anything that looks like a key).
 - Time/Random/Dispatchers are injected so tests are deterministic (runTest + virtual time).
 - No library API invented from memory: verify against source/docs, or say "unsure".
 - No new dependency without stating why. Constants live in domain/Defaults.kt.
Also add a minimal ktlint/spotless (or Android Studio default formatting) — decide once, apply once.
```
**Test:** `./gradlew test` passes (add one trivial test). Commit `chore: ai rules + formatting`.

### Step 1.5 — Application class + logger
```text
Create LoopCastApp : Application (register in manifest) — empty for now except a `val container` placeholder (manual DI; filled in Module 6).
Create util/Log.kt: object Log { d/i/w/e(msg, throwable?) } with tag "LoopCast"; a pure function redact(msg: String): String that masks
(a) any token that looks like a YouTube key (4 groups of 4 alphanumerics separated by dashes) and (b) the path part after "/live2/" in RTMP URLs.
Test redact() in the JVM: key pattern masked; "rtmps://a.rtmp.youtube.com:443/live2/abcd-efgh-ijkl-mnop-qrst" -> ".../live2/****"; normal text unchanged.
```
**Test:** `./gradlew test` (redact tests). ✅ Gate: app launches, tests green. Commit `feat: logger with redaction`.

> **DONE TILL HERE (implementation):** Module 1 is implemented through Step 1.5. The dependency build and JVM test gate is still pending because terminal execution is blocked by the sandbox.

> **CURRENT STEP:** Module 2 Step 2.1 is implemented locally; its JVM test gate is pending because Gradle cannot download dependencies in the restricted terminal.

---

# MODULE 2 — Domain Core (pure Kotlin)
> Goal: all rules that don't need Android are built and proven on the JVM. ~50% of your risk disappears here.

### Step 2.1 — `StreamKey` and `RtmpEndpoint`
```text
domain/StreamKey.kt: @JvmInline value class StreamKey private constructor(private val raw: String).
  - companion fun parse(input: String): Result<StreamKey> — trims; rejects blank, whitespace inside, length outside 8..128.
  - fun reveal(): String (only used by the engine at connect time); toString() returns "••••" + last 4 chars (never the full key).
domain/RtmpEndpoint.kt: data class RtmpEndpoint(val baseUrl: String, val key: StreamKey).
  - fun fullUrl(): String = baseUrl.trimEnd('/') + "/" + key.reveal()   (used ONLY by the engine)
  - override toString() -> safe form (host + masked key).
  - companion object Presets: YOUTUBE_RTMPS = "rtmps://a.rtmp.youtube.com:443/live2", YOUTUBE_RTMP = "rtmp://a.rtmp.youtube.com/live2",
    YOUTUBE_BACKUP_RTMPS = "rtmps://b.rtmp.youtube.com:443/live2?backup=1".
  - fun parseFromUserInput(url: String, key: String): Result<RtmpEndpoint>:
      * accepts only rtmp:// or rtmps:// schemes;
      * if the user pasted a FULL url with the key already appended (…/live2/xxxx-xxxx-…), split it into base + key (and ignore the empty key field);
      * a trailing "?backup=1" query belongs to the base URL — don't split it;
      * clear, typed failures: InvalidScheme, MissingKey, InvalidKey, MalformedUrl.
Tests (JVM): every branch above, toString never contains the key, full-URL split, backup URL handled, whitespace/newline pasted from clipboard trimmed.
```
**Test:** `./gradlew test --tests "*Rtmp*" --tests "*StreamKey*"` ✅ Commit `feat(domain): stream key + endpoint`

### Step 2.2 — `EncoderProfile` and profile selection
```text
domain/EncoderProfile.kt: data class (width, height, fps, videoBitrateKbps, keyframeIntervalSec = 2, audioBitrateKbps = 128, audioSampleRate = 48_000, stereo = true).
domain/VideoInfo.kt: data class (width, height [already rotation-adjusted], durationMs, frameRate: Float?, videoMime, hasAudio, audioMime?).
Presets: P720_30 (1280x720, 30, 3000 kbps), P1080_30 (1920x1080, 30, 4500 kbps). Constants in Defaults.kt; I will verify against YouTube's current table.
fun chooseProfile(info: VideoInfo, preference: QualityPreference /* AUTO, P720, P1080 */): EncoderProfile
  - never upscale: if source height < preset height pick the largest preset that fits (floor at 720p-class if source is smaller; just use source-sized even dims)
  - orientation aware: portrait source -> swap width/height (e.g., 720x1280); dimensions always EVEN
  - fps = min(sourceFps ?: 30, 30); if frameRate unknown -> 30
  - bitrate scaled proportionally if resolution is below the preset
Tests: landscape 720p, 1080p, 4K source (downscaled to 1080p preset), portrait, tiny 480x270 (even dims), null fps, odd dimensions (1281x721 -> even).
```
**Test:** `./gradlew test` ✅ Commit `feat(domain): encoder profile`

### Step 2.3 — State machine (`StreamState` + `reduce`)
```text
domain/StreamState.kt (sealed interface):
  Idle(source: VideoInfo?)            — nothing streaming; source may be selected
  Preparing                            — encoder/source being set up
  Ready                                — prepared, not connected
  Connecting(attempt: Int)
  Live(sinceMs: Long)
  Reconnecting(attempt: Int, nextDelayMs: Long, reason: StreamError)
  Stopping
  Failed(error: StreamError)
domain/StreamEvent.kt: SourceSelected(info), SourceCleared, StartRequested, PrepareOk, PrepareFailed(err), ConnectOk(nowMs),
  ConnectFailed(err), Dropped(err), RetryScheduled(attempt, delayMs), StopRequested, Stopped, Reset.
fun reduce(state: StreamState, event: StreamEvent): Transition   where Transition(state, ignored: Boolean = false)
Rules: StartRequested only from Idle(with source) or Failed; StopRequested valid from Preparing/Ready/Connecting/Live/Reconnecting;
  Stopped -> Idle(source kept); Dropped only from Live -> Reconnecting(attempt 1); ConnectFailed from Connecting(attempt n) -> Reconnecting(n+1) unless the error is non-retryable (InvalidKey/AuthFailed/UnsupportedMedia) -> Failed.
Illegal combos return ignored=true with the SAME state (never throw).
domain/StreamError.kt: sealed — InvalidKey, AuthFailed, Network, ServerRejected, UnsupportedMedia, EncoderInit, Thermal, Unknown(msg); each has `retryable: Boolean` and `userMessage: String` (plain language, no jargon).
Tests: a table-driven test of EVERY (state, event) pair listed above; plus a "random walk" test: 10,000 random events never throw and never produce Live without passing through Connecting.
```
**Test:** `./gradlew test` ✅ Commit `feat(domain): state machine`

### Step 2.4 — `ReconnectPolicy`
```text
domain/ReconnectPolicy.kt: class ReconnectPolicy(random: Random, config: Config(baseMs=1000, maxMs=30_000, jitter=0.2, stableResetMs=60_000, maxAttempts: Int? = null)).
  fun nextDelay(attempt: Int): Long? — exponential (1,2,4,8,16,30,30,…s) with ±jitter; null when maxAttempts exceeded.
  fun shouldResetAttempts(liveDurationMs: Long): Boolean — true when the connection stayed up >= stableResetMs.
Tests: sequence without jitter; cap at maxMs; jitter bounds with a seeded Random; maxAttempts -> null; reset rule.
```
**Test:** `./gradlew test` ✅ Commit `feat(domain): reconnect policy`

### Step 2.5 — `Defaults.kt` audit
```text
Move every constant introduced so far (limits, presets, URLs, timeouts) into domain/Defaults.kt with a one-line comment each. Ensure no magic numbers remain in domain/ (grep for digits outside Defaults/tests).
```
**Test:** `./gradlew test` ✅ **Gate:** all domain tests green; `grep -rn "android\." app/src/main/java/com/loopcast/domain` returns nothing. Commit `refactor(domain): constants`

---

# MODULE 3 — Video Selection, Import, Probe
> Goal: user picks a video; we own a stable copy; we know exactly what's in it before touching any encoder.

### Step 3.1 — Pure copy utility (testable without Android)
```text
data/media/StreamCopier.kt (pure Kotlin + java.io only):
  suspend fun copy(input: InputStream, output: OutputStream, totalBytes: Long?, onProgress: (Long) -> Unit, bufferSize = 64 KiB)
  - runs on the caller's dispatcher; checks `coroutineContext.ensureActive()` every buffer so cancel is immediate
  - flushes + closes only the output; the caller owns the input
  - throws IOException unchanged (don't swallow)
Tests (JVM): copies bytes exactly; progress is monotonic and ends at total; cancellation mid-copy stops promptly; failing input stream propagates the exception.
```
**Test:** `./gradlew test --tests "*StreamCopier*"` ✅ Commit `feat(media): stream copier`

### Step 3.2 — `VideoImporter` (Photo Picker → private copy)
```text
data/media/VideoImporter.kt (takes ContentResolver, filesDir, dispatcher):
  fun import(uri: Uri): Flow<ImportProgress>  where ImportProgress = Copying(bytes, total?) | Done(File) | Failed(ImportError)
  Steps: (1) query size via OpenableColumns.SIZE (may be null); (2) check free space with StatFs: require size + 10% headroom else Failed(NotEnoughSpace);
  (3) copy to filesDir/source/current.tmp using StreamCopier; (4) on success atomically rename to current.<ext> (ext from MIME), deleting the previous file;
  (5) on cancel/failure delete the .tmp. Never leave a half file that could be mistaken for valid.
  ImportError: NotEnoughSpace, Unreadable, Cancelled, Io(message).
UI hook (no UI yet): in HomeScreen later we use rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) with
PickVisualMediaRequest(PickVisualMedia.VideoOnly). Document WHY we copy: Photo Picker grants temporary access; a foreground service later needs a stable file.
Instrumented test (androidTest): import a small asset via a content Uri provider (use a FileProvider or `ContentResolver` from a test file) -> file exists, size matches;
low-space simulation via a fake StatFs wrapper interface (inject `FreeSpaceProvider`).
```
**Test:** `./gradlew connectedAndroidTest --tests "*VideoImporter*"` ✅ Commit `feat(media): video importer`

### Step 3.3 — `VideoProbe`
```text
data/media/VideoProbe.kt: fun probe(file: File): Result<VideoInfo> using MediaExtractor (tracks, mime, width/height, frame-rate, rotation via KEY_ROTATION or
MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, duration) — ALWAYS release extractors in finally.
  - width/height must be rotation-adjusted (a 1920x1080 clip with rotation 90 is 1080x1920).
  - if no video track -> Failure(UnsupportedMedia("No video track"))
  - check the device can DECODE the video mime: MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null else UnsupportedMedia("This phone can't decode <mime>")
  - hasAudio = any audio track; also report audioMime.
  - frameRate may be absent (VFR) -> null.
Instrumented tests with the fixtures from step 0.4 copied into androidTest/assets: A/B/C/D/E/F -> expected width/height/hasAudio; HEVC either decodes or returns the typed failure (device-dependent — assert "one of the two").
```
**Test:** `./gradlew connectedAndroidTest --tests "*VideoProbe*"` ✅ Commit `feat(media): probe`

### Step 3.4 — `SettingsStore` (DataStore) with last-selected video
```text
data/prefs/SettingsStore.kt (DataStore Preferences): stores lastVideoPath, qualityPreference, rtmpBaseUrl, keepScreenOn, autoStopHours (0=off).
  Flow<Settings> + suspend setters. Provide a `validateLastVideo(): File?` that returns the file only if it still exists and is non-empty (else clears the setting).
Tests: JVM test using an in-memory/temporary DataStore (TestScope + temp file): defaults, round trip, stale path cleared.
```
**Test:** `./gradlew test` ✅ Commit `feat(prefs): settings store`

---

# MODULE 4 — Loop Playback (ExoPlayer, idle preview)
> Goal: feature #2 from your list on its own — the video plays continuously on screen. Independent of streaming, so it is also your baseline for "does this file play at all".

### Step 4.1 — `LoopPlayer` wrapper
```text
ui/player/LoopPlayer.kt: class LoopPlayer(context) owning ONE ExoPlayer.
  - repeatMode = Player.REPEAT_MODE_ONE; playWhenReady = true after prepare; volume default 0f (muted preview) with setMuted(Boolean)
  - fun load(file: File), play(), pause(), release() (idempotent), val state: StateFlow<PlayerState{Idle, Buffering, Playing, Error(msg)}>
  - listener maps Player.Listener.onPlayerError to state=Error (don't crash)
  - NO global singletons; exactly one release() path.
Compose helper ui/player/LoopPlayerView.kt: AndroidView { PlayerView } with useController=false, resizeMode FIT; DisposableEffect releases the view binding;
lifecycle: pause on ON_STOP, resume on ON_START ONLY when we are not streaming (parameter `active: Boolean`).
```
**Test (manual):** temporary Activity content = LoopPlayerView with test720.mp4: loops indefinitely for 5 min, no memory growth (Android Studio Profiler → heap flat), rotation doesn't leak (LeakCanary optional in debug). ✅ Commit `feat(player): loop preview`

### Step 4.2 — HomeScreen v0 (pick + preview + info)
```text
ui/home/HomeScreen.kt + small components in ui/home/components/: VideoCard (preview, "Choose video" button, chips: resolution, duration, codec, audio yes/no),
ErrorBanner. A temporary HomeViewModel exposing UiState(videoFile, info, importProgress, error) — real wiring comes in Module 7.
Flow: pick -> VideoImporter.import (progress bar with MB copied) -> VideoProbe -> show info; UnsupportedMedia -> ErrorBanner with the typed message.
Each composable <= 80 lines, with @Preview for: empty, importing 40%, ready, error.
```
**Test (manual, all fixtures):** A/B/C show correct info; D shows info or a clear "can't decode HEVC" message; E shows "no audio"; G (2 GB) shows progress, can be cancelled, and doesn't freeze the UI; app killed during copy leaves no `.tmp` after next launch. ✅ Commit `feat(ui): home v0`

---

# MODULE 5 — SPIKE: RootEncoder File Source → Local RTMP (timebox 3–5 h)
> This is the module that decides whether the project is easy or hard. **Do it on a branch (`spike/rootencoder`). Throw the code away; keep the knowledge.**
> Rule: no production code, no UI polish. Questions only.

### Step 5.1 — API reconnaissance (reading, no code)
```text
Ask the AI to help me READ (not write) these and summarise exact signatures:
 1. RootEncoder GitHub wiki: "StreamBase" page and the file-streaming section.
 2. RootEncoder repo sample app/src/main/java/com/pedro/streamer/file/FromFileActivity.kt.
 3. The source of VideoFileSource and AudioFileSource in the library module (constructor args, how a File/Uri is set, how looping is enabled, how to know the file ended, whether start/stop is automatic).
 4. RtmpStream / GenericStream constructor (context, ConnectChecker, videoSource, audioSource), prepareVideo(...) and prepareAudio(...) parameters, startStream(url), stopStream(), release(), startPreview/stopPreview, and ConnectChecker callbacks.
 5. How to change video bitrate on the fly, and how to set keyframe interval (iFrameInterval).
Output: a table "capability -> exact API -> source link" saved to docs/SPIKE.md. If anything isn't found, mark it UNKNOWN (do not guess).
```
**Test:** docs/SPIKE.md has no empty cells. ✅

### Step 5.2 — Minimal streaming Activity
```text
In package com.loopcast.spike (throwaway): an Activity with a SurfaceView/OpenGlView for preview, EditText for URL, "Start/Stop" button,
using the API recorded in docs/SPIKE.md: RtmpStream with VideoFileSource + AudioFileSource pointing at the imported file, loop enabled.
prepareVideo(profile from chooseProfile()), prepareAudio(48000, stereo, 128 kbps), keyframe interval 2 s, startStream("rtmp://<laptop-ip>:1935/live/test").
Log (with our Log wrapper) every ConnectChecker callback. NO foreground service, NO reconnect logic. Hard-code the URL in a debug field.
```
**Test:** MediaMTX console shows a publisher; `ffplay rtmp://<laptop-ip>:1935/live/test` shows video AND audio. ✅

### Step 5.3 — Measure (this is the actual deliverable)
Run each experiment and fill the table in `docs/SPIKE.md`:

| # | Question | How to measure | Pass criterion |
|---|---|---|---|
| a | Does it loop? | Use a 10 s clip, watch 5 min in ffplay | Loops without stopping the stream |
| b | **Timestamp continuity at the loop seam** | `ffprobe -v error -show_entries frame=pts_time,pkt_pts_time -of csv rtmp://… \| head -2000` — PTS must keep increasing across the seam | No backward jumps / no gaps > 1 frame |
| c | Seam glitch | Watch ffplay at the loop point | ≤ 1 dropped frame, no audio pop |
| d | A/V sync after 20+ loops | Clip with a clap/flash; watch after 10 min | Drift < 100 ms |
| e | File without audio (`noaudio.mp4`) | Stream it | Streams (video), no crash; note whether silent audio is needed for YouTube |
| f | HEVC (`hevc.mp4`), 4K, VFR (`vfr.mp4`), portrait (`portrait.mp4`) | Stream each | Works or fails with a catchable error |
| g | CPU/thermal over 10 min | `dumpsys thermalservice`, touch the phone | Thermal status ≤ LIGHT on mid-range phone |
| h | Preview attach/detach while streaming | Rotate phone; background/foreground | Stream keeps going |
| i | Encoder output | `ffprobe -show_streams` | H.264 (note profile), AAC, keyframe interval ≈ 2 s, bitrate ≈ requested |
| j | Stop/start 10× | Tap stop/start rapidly | No leak/crash; ConnectChecker events sensible |

### Step 5.4 — Decision gate (ADR)
```text
Write docs/adr/001-streaming-pipeline.md (Context / Options / Decision / Consequences) based on the table.
```
| Outcome | Decision |
|---|---|
| **Expected:** a–d, i pass | **Path A** — stream with RootEncoder file sources. ExoPlayer = idle preview only (released on Start) |
| Seam gaps/drift fail but fixable | Path A + workaround (e.g., recreate sources at loop boundary, or pre-concatenate the file N× into a long temp file with `MediaMuxer`) — record the workaround |
| Library can't loop/handle file at all | **Path B**: ExoPlayer → Surface into a custom RootEncoder `VideoSource`, and tap decoded PCM through a Media3 `AudioProcessor` into a custom `AudioSource`. Costs 2–4 extra days; come back and ask for a dedicated guide before starting |

✅ Gate: ADR committed. Merge **only** `docs/` from the spike branch (`git checkout main && git checkout spike/rootencoder -- docs`). Commit `docs: spike findings + ADR 001`.

---

# MODULE 6 — Engine + Controller (production code)
> Goal: rebuild the spike properly behind an interface, with a fake engine so all logic is unit-tested without a phone or a network.

### Step 6.1 — `StreamEngine` interface + `FakeStreamEngine`
```text
engine/StreamEngine.kt:
  interface StreamEngine {
    val events: Flow<EngineEvent>                 // hot SharedFlow, replay 0, extraBufferCapacity 16
    suspend fun prepare(file: File, profile: EncoderProfile): Result<Unit>   // maps failures to StreamError
    suspend fun connect(endpoint: RtmpEndpoint): Result<Unit>                // returns after start requested; real outcome arrives via events
    suspend fun disconnect()
    fun attachPreview(surface: PreviewSurface); fun detachPreview()
    fun setVideoBitrate(kbps: Int)
    fun release()                                  // idempotent
  }
  sealed interface EngineEvent { Connected, Disconnected(reason: String?), ConnectionFailed(reason: String), AuthError, BitrateReport(bps: Long), SourceEnded }
engine/FakeStreamEngine.kt: scriptable (queue of results, emitters `emit(EngineEvent)`), records calls — lives in the main source set under a `testing` package OR in test fixtures; used by controller tests.
PreviewSurface is our own tiny wrapper type so the interface doesn't leak SurfaceView/OpenGlView.
```
**Test:** compiles; a trivial JVM test drives the fake. Commit `feat(engine): interface + fake`

### Step 6.2 — `RootEncoderEngine`
```text
engine/RootEncoderEngine.kt — the ONLY file importing com.pedro.*. Implement StreamEngine using the API recorded in docs/SPIKE.md (do not re-guess).
 - prepare(): create sources (video/audio file), prepareVideo(...) && prepareAudio(...); false/exception -> Result.failure(StreamError.EncoderInit/UnsupportedMedia)
 - ConnectChecker callbacks -> EngineEvent via tryEmit (callbacks arrive on library threads; never block there)
 - connect(): startStream(endpoint.fullUrl()) — this is the only place the full URL exists; never log it
 - disconnect(): stopStream then stop sources; release(): idempotent, null-safe, called exactly once from the controller
 - setVideoBitrate(): library's on-the-fly API
 - map failure reason strings to errors in ONE pure function `mapFailure(reason: String): StreamError` (auth/denied -> AuthFailed; timeout/unreachable -> Network; else Unknown) + JVM tests with the strings you saw in logcat during the spike
```
**Test:** `./gradlew test --tests "*mapFailure*"`; build passes. Commit `feat(engine): rootencoder adapter`

### Step 6.3 — `StreamController` (the brain)
```text
stream/StreamController.kt: class StreamController(engine: StreamEngine, policy: ReconnectPolicy, clock: Clock, scope: CoroutineScope, dispatcher injected).
  val state: StateFlow<StreamState>; all mutations go through a private `dispatch(event)` using reduce() under a Mutex.
  Commands: selectSource(info, file), clearSource(), start(endpoint, profile), stop().
  start(): Preparing -> engine.prepare -> Ready -> Connecting(1) -> engine.connect; on EngineEvent.Connected -> Live; on ConnectionFailed/Disconnected ->
    consult ReconnectPolicy: retryable -> Reconnecting(attempt, delay) -> delay (cancellable) -> engine.connect again; non-retryable -> Failed.
  stop(): cancels any pending retry, engine.disconnect(), engine.release() once, -> Idle. stop() during Reconnecting must work instantly.
  Reset attempts when Live lasted >= policy.stableResetMs.
  Exposes `liveStats: StateFlow<LiveStats(uptimeMs, lastBitrateBps, reconnects)>`.
Tests (runTest + FakeStreamEngine + virtual time): happy path; connect failure then success (backoff delays asserted); drop while Live -> reconnect; AuthError -> Failed and NO retry;
stop during Reconnecting; start twice quickly (second ignored); stop twice (idempotent); prepare failure -> Failed; release called exactly once in every path.
```
**Test:** `./gradlew test` ✅ Commit `feat(stream): controller`

### Step 6.4 — Manual DI + dev panel against MediaMTX
```text
LoopCastApp.container: lazy singletons (SettingsStore, VideoProbe, VideoImporter, StreamController(RootEncoderEngine(app), ReconnectPolicy(Random), System clock)). No DI framework.
Add a DEBUG-only screen (debug/DevPanelActivity or a hidden long-press on the title) with: URL field prefilled from a debug BuildConfig field (local MediaMTX), start/stop, and a text dump of state + liveStats.
Release builds must not contain it (use the debug source set).
```
**Test (real, end-to-end without UI polish):** import test720.mp4 → Start → `ffplay` shows it with sound → Stop → state Idle → Start again → works → kill MediaMTX while Live → state Reconnecting with growing delays → restart MediaMTX → state returns to Live automatically. ✅ **Gate:** all of that passes + `./gradlew test`. Commit `feat: wire controller + dev panel`

---

# MODULE 7 — UI + ViewModel
> Goal: the real screen. Stateless composables, one ViewModel, previews for every state.

### Step 7.1 — `HomeViewModel` + `UiState`
```text
ui/home/HomeUiState.kt (immutable): source: SourceUi (None | Importing(progress) | Ready(info, file)), form: FormUi(baseUrl, keyInput (String), urlError?, keyError?, showKey),
  quality: QualityPreference, stream: StreamState, stats: LiveStats, canStart: Boolean, warnings: List<Warning>.
ui/home/HomeViewModel.kt: combines SettingsStore, StreamController.state/liveStats, import flow, and the form into one StateFlow<HomeUiState> (stateIn WhileSubscribed(5s)).
  Events: onPickedUri, onUrlChanged, onKeyChanged, onToggleShowKey, onStartClicked, onStopClicked, onQualityChanged, onClearVideo.
  onStartClicked(): RtmpEndpoint.parseFromUserInput(...) -> on failure show field errors, no start; success -> controller.start(...).
  One-shot UI effects via a Channel (e.g., ShowSnackbar, RequestNotificationPermission).
  The ViewModel never holds Context/Activity/View.
Tests (Turbine + runTest + FakeStreamEngine): canStart false without video/key; pasted full URL gets split; invalid scheme -> urlError; start -> controller.start called; state flows reflect controller states; clearing video while Live is ignored.
```
**Test:** `./gradlew test` ✅ Commit `feat(ui): viewmodel`

### Step 7.2 — Components (each ≤ 80 lines, with @Preview)
```text
ui/home/components/: 
 - StreamForm: URL field + preset dropdown (YouTube RTMPS default / RTMP / custom), key field (password visual transformation, show/hide, Paste button, supportingText for errors, KeyboardOptions: no autocorrect, ImeAction.Done)
 - QualityPicker: segmented buttons Auto / 720p / 1080p
 - StatusBanner: colour + text per StreamState (Idle, Preparing, Connecting, LIVE with uptime timer, Reconnecting "attempt n — retrying in 4 s", Failed with userMessage)
 - StartStopButton: single large button, state-aware label, disabled when !canStart, loading while Preparing/Connecting
 - LiveStatsRow: uptime, bitrate (kbps), reconnect count, estimated data used (MB)
 - WarningList: e.g., "On mobile data (~2 GB/hour)", "Battery low — plug in", "Notifications off"
Previews: idle-no-video, ready, live, reconnecting, failed, long-text overflow, dark mode, font scale 1.5.
```
**Test:** Android Studio Preview pane renders all states; no truncation at font scale 1.5. ✅ Commit `feat(ui): components`

### Step 7.3 — Compose the screen + preview surface handling
```text
HomeScreen composes the components (≤ 80 lines; split into sections if longer). Preview area:
 - not streaming: LoopPlayerView (ExoPlayer, muted)
 - streaming: release ExoPlayer FIRST (stop/dispose), then attach the engine preview surface — never run both decoders at once.
 - when the screen leaves composition during a stream: detachPreview() only (stream continues).
Scaffold with SnackbarHost for one-shot effects. Keep screen on while Live if settings.keepScreenOn (window flag via DisposableEffect).
```
**Test (manual, against MediaMTX):** full flow with fixtures A–F: pick → info → paste URL+key → Start → LIVE banner + preview switches to engine preview → Stop → preview returns to ExoPlayer loop. Rotate during Live: stream continues, preview re-attaches. ✅

### Step 7.4 — Settings bottom sheet
```text
SettingsSheet: keep-screen-on switch, auto-stop timer (Off/1/2/6/12 h) , "Forget saved stream key" button (wired in Module 11), About (versions of RootEncoder/Media3 from BuildConfig fields).
Auto-stop: controller schedules stop() after N hours of Live (virtual-time tested).
```
**Test:** `./gradlew test` (auto-stop test) ✅ **Gate: Module 7 complete = MVP demo.** Tag `v0.1.0-demo`. Commit `feat(ui): settings + auto-stop`

---

# MODULE 8 — Foreground Service (survive screen-off)
> Goal: the stream keeps running when the screen locks or the user switches apps. The Service is a *process keeper*; the controller still owns the logic.

### Step 8.1 — `StreamService` + notification
```text
stream/StreamService.kt (Service, foregroundServiceType="mediaPlayback" in the manifest; <service exported="false">):
  - Started ONLY from a user action (Start button) via ContextCompat.startForegroundService; then immediately ServiceCompat.startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK).
  - Notification channel "stream" (IMPORTANCE_LOW), ongoing notification: title "LoopCast — LIVE", text = state/uptime (updated from controller.state, max once per 5 s), action button "Stop" (PendingIntent -> service ACTION_STOP -> controller.stop()).
  - Observes controller.state in a lifecycleScope-like CoroutineScope; when the state returns to Idle/Failed -> stopForeground(REMOVE) + stopSelf().
  - onStartCommand returns START_NOT_STICKY (we never auto-resurrect a stream after a process death).
  - Override onTimeout(startId, fgsType) (API 35+): stop the stream gracefully and stopSelf() — don't crash.
  - onDestroy: idempotent cleanup, no controller logic.
POST_NOTIFICATIONS (API 33+): request in context when the user first taps Start; if denied, stream anyway but show a WarningList item ("Notifications are off — you won't see a Stop button").
```
**Test (manual):** Start → notification visible with LIVE + Stop → press Home → ffplay still receives → tap notification Stop → stream ends, notification disappears. ✅ Commit `feat(service): foreground service`

### Step 8.2 — Wake lock + Wi-Fi lock
```text
stream/PowerLocks.kt: class PowerLocks(context) { fun acquire(); fun release() } holding a PARTIAL_WAKE_LOCK and (if on Wi-Fi) a WifiLock (high-perf/low-latency mode as available on the OS version).
  - acquire when state enters Preparing/Live, release on Idle/Failed/Stopping complete; idempotent; tag "LoopCast:stream".
  - Safety: acquire with a timeout (e.g., 13 h) and re-acquire while still Live, so a bug can never hold a lock forever.
Tests: a pure `LockPolicy` (state -> shouldHoldLocks: Boolean) in domain/ with JVM tests; PowerLocks itself manual.
```
**Test (manual, 30 min):** Start, turn screen off, leave phone unplugged-then-plugged: stream stays up. `adb shell dumpsys power | grep -i loopcast` shows the wake lock while Live and NOT after Stop. ✅ Commit `feat(service): power locks`

### Step 8.3 — Lifecycle edge cases
```text
Decide and implement (document in docs/LIFECYCLE.md):
 - Activity destroyed while Live -> stream continues (controller in Application).
 - App swiped from recents -> stream continues (service foreground) — notification remains with Stop.
 - Process killed by OS/OEM -> stream is gone; next launch shows Idle (no phantom "Live"). Verify with `adb shell am kill com.loopcast`.
 - Second Start tap / duplicate intents -> ignored.
 - Low-memory trim: release preview only (onTrimMemory), never the encoder.
Add instrumented/ manual test list to docs/LIFECYCLE.md and tick each.
```
**Test:** run the list on both phones. ✅ Commit `docs+fix: lifecycle`

### Step 8.4 — Battery-optimisation guidance
```text
One dialog (shown once, dismissible) explaining OEM battery savers can kill long streams, with a button opening Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (the settings LIST — NOT the direct "request ignore" intent, which is restricted on Google Play) and a link to dontkillmyapp.com for the user's manufacturer.
```
**Test:** dialog appears once; button opens the right screen. ✅ **Gate: Module 8 done.** Commit `feat: battery guidance`

---

# MODULE 9 — Resilience (the "24/7" module)

### Step 9.1 — `NetworkMonitor`
```text
stream/NetworkMonitor.kt: callbackFlow over ConnectivityManager.registerDefaultNetworkCallback -> Flow<NetworkStatus(Available(type: WIFI|CELLULAR|OTHER) | Lost)>, with unregister in awaitClose.
Controller behaviour: while Reconnecting and network is Lost -> don't burn attempts; wait for Available then retry IMMEDIATELY (reset delay). Network type change while Live (Wi-Fi->cellular) -> expect a drop; no manual action.
Pure part: `shouldAttemptNow(state, network)` in domain/ with JVM tests.
Warning: before Start on CELLULAR, show a confirm dialog "≈ X MB/hour at your bitrate" computed from the profile (pure function dataPerHourMb(kbps) + test).
```
**Test (manual chaos):** Wi-Fi off → state Reconnecting/waiting-for-network → Wi-Fi on → Live again within seconds. Airplane mode 30 s → same. ✅ Commit `feat(stream): network monitor`

### Step 9.2 — Reconnect hardening
```text
 - Ensure the library's own internal retry is disabled/minimal (verify the API in docs/SPIKE.md; we want exactly ONE retry brain: ReconnectPolicy).
 - On each reconnect create a fresh connection but REUSE the prepared encoder/sources when the library allows it; otherwise re-prepare (document which).
 - Distinguish: AuthFailed (wrong/rotated key) -> Failed with message "YouTube rejected the stream key"; ServerRejected; Network.
 - After a reconnect the loop position may restart — acceptable; record in docs.
 - Metrics: reconnect count, last failure reason (redacted) in LiveStats; exported through the diagnostics log (Module 11).
Add controller tests: 20 consecutive failures obey the backoff cap; success after 5 failures resets attempts after the stable window.
```
**Test:** `./gradlew test`; manual: change the key in YouTube Studio ("Reset stream key") while Live → app shows Failed/AuthFailed, doesn't spin. ✅ Commit `feat(stream): reconnect hardening`

### Step 9.3 — Backup ingest (optional but cheap)
```text
After 3 consecutive failures to the primary host, try YOUTUBE_BACKUP_RTMPS once per cycle; if it connects, stay on it until the next manual start. Pure selection logic `chooseEndpoint(attempt, primary, backup)` + tests. Toggle in settings (default ON for YouTube presets only).
```
**Test:** JVM tests ✅ Commit `feat(stream): backup ingest`

### Step 9.4 — Adaptive bitrate
```text
domain/BitratePolicy.kt (pure): decide(current: Int, floor: Int, ceiling: Int, signals: Signals(reconnectsLast5Min, thermal: ThermalLevel, onCellular: Boolean, stableMs)): Int
  - step DOWN 25% (floor 1000 kbps) after 2+ reconnects in 5 min or thermal >= MODERATE
  - step UP 10% only after 5 stable minutes, never above ceiling (the profile's bitrate)
Controller applies via engine.setVideoBitrate and shows "Bitrate lowered: network unstable" in the status.
Tests: table-driven sequences, floor/ceiling respected, no oscillation (assert at most one change per minute).
```
**Test:** `./gradlew test` ✅ Commit `feat(stream): adaptive bitrate`

### Step 9.5 — `ThermalMonitor` + battery guard
```text
stream/ThermalMonitor.kt (API 29+, no-op below): PowerManager.addThermalStatusListener -> Flow<ThermalLevel>. Map: NONE/LIGHT -> OK, MODERATE -> WARN, SEVERE -> HIGH, CRITICAL+ -> STOP.
Controller: HIGH -> feed BitratePolicy; STOP -> stop the stream and show Failed(Thermal) with "Phone too hot — let it cool, then restart".
Battery: if not charging and level < 20% when starting -> WarningList; if < 5% and not charging while Live -> graceful stop with message.
Pure mapping functions + JVM tests (thermal status ints -> ThermalLevel).
```
**Test (manual):** run 20 min at 1080p on the weaker phone in a warm spot; observe `dumpsys thermalservice`; bitrate steps down before the app stops; no crash. ✅ Commit `feat(stream): thermal + battery guard`

### Step 9.6 — Source edge cases (from the spike results)
```text
Implement exactly the workarounds recorded in docs/adr/001 and docs/SPIKE.md:
 - file WITHOUT audio -> if YouTube/ffprobe shows it requires audio, generate silent audio (via the library's audio source options) — pick the simplest working approach and test with noaudio.mp4
 - source decode failure mid-stream (EngineEvent.SourceEnded unexpectedly) -> treat as Dropped(Unknown) and restart sources
 - portrait/odd-size sources -> profile from chooseProfile() already handles; verify the aspect-ratio mode (letterbox vs crop) matches the choice in Settings ("Fit" default)
 - VFR source -> output fps fixed at profile fps
Add one test per workaround on the JVM where logic is pure; the rest as manual rows in the test matrix (Appendix D).
```
**Test:** matrix rows for E, F, C pass. ✅ **Gate: Module 9 done** — run the chaos table below.

| Chaos test | Expected |
|---|---|
| Airplane mode 30 s | Reconnecting → Live, no manual action |
| Wi-Fi → cellular switch | Brief drop, auto-recover, cellular warning not re-shown |
| Kill MediaMTX 2 min, restart | Reconnecting with capped backoff → Live |
| Rotate 10× while Live | Stream uninterrupted |
| Start/Stop ×20 | No leaks (heap stable), no stuck notification |
| Revoke notification permission mid-stream | Stream continues |
| Fill the phone's storage during import | Typed NotEnoughSpace error |

Commit `feat(stream): source edge cases`

---

# MODULE 10 — YouTube End-to-End + Soak

### Step 10.1 — First real YouTube stream
```text
Checklist:
 1. YouTube Studio → Go live → Stream tab; stream key matches; visibility Unlisted/Private; "Stream latency" Normal/Low.
 2. In LoopCast: RTMPS preset, paste key, 720p Auto, Start.
 3. YouTube Studio shows "Receiving data"; "Stream health" → Excellent/Good with NO warnings (check: keyframe frequency, bitrate, audio).
 4. Click "Go live" in Studio; open the watch URL on another device; verify video + audio and the loop.
 5. Stop in LoopCast -> Studio shows the stream stopped; "End stream" in Studio.
If health shows warnings, fix profile settings (keyframe 2 s, CBR-like bitrate, AAC 48 kHz) — do not "work around" in the UI.
```
**Test:** 15-minute stream; zero health warnings. ✅ Commit `docs: first youtube run`

### Step 10.2 — Full compatibility matrix on YouTube
Run every row on both phones, 5 minutes each, record results in `docs/TEST_MATRIX.md` (template in Appendix D): fixtures A–F × presets {720p, 1080p} × network {Wi-Fi, cellular}. Any red cell becomes a GitHub issue before continuing.

### Step 10.3 — Soak tests
```text
Create docs/SOAK.md with a table: start/end time, phone, battery %, temperature, reconnect count, max memory (dumpsys meminfo every 30 min — script it:  while true; do adb shell dumpsys meminfo com.loopcast | grep -E "TOTAL|Native|Java"; sleep 1800; done), YouTube health notes.
Tests: 1 h (screen on), 6 h (screen off, plugged in), 12–24 h (overnight, plugged in, Wi-Fi).
```
| Soak pass criteria | |
|---|---|
| Stream remained up (auto-recovered drops allowed) | ✅ |
| Reconnects | < 3 per hour on stable Wi-Fi |
| Memory | no monotonic growth (±10 %) over 6 h |
| Audio/video sync (YouTube replay) | within ~100 ms at hour 6 |
| Thermal | never STOP level on the mid-range phone |
| FGS | no Android 15 timeout crash |

> Also run on Android 15+ with `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS com.loopcast` to prove the `mediaPlayback` type isn't subject to the 6 h dataSync-style cutoff.

**Gate:** 6 h soak green. Commit `docs: soak results`

---

# MODULE 11 — Security, Privacy, Polish

### Step 11.1 — Encrypted stream-key storage ("Remember key")
```text
data/prefs/KeyVault.kt: optional storage for the stream key. Use the Android Keystore directly (non-exportable AES-256-GCM key, random 12-byte IV per encryption, ciphertext+IV in DataStore as Base64). 
Do NOT use the deprecated androidx.security:security-crypto. API: suspend fun save(key: StreamKey), load(): StreamKey?, clear().
Behaviour: OFF by default; checkbox "Remember key on this phone". If decryption fails (keystore reset/backup restore) -> clear silently and ask again. "Forget saved key" (settings) wipes it.
Instrumented tests: round trip; tamper with ciphertext -> load() returns null (no crash); clear() removes it.
```
**Test:** `./gradlew connectedAndroidTest --tests "*KeyVault*"` ✅ Commit `feat(security): key vault`

### Step 11.2 — The no-leak audit (a real test, not a vibe)
```text
Procedure (document results in docs/SECURITY.md):
 1. Install a debug build; adb logcat -c.
 2. Run a full session: import, start, drop network, reconnect, stop — using a recognizable fake key like "zzzz-aaaa-bbbb-cccc-dddd" against MediaMTX.
 3. adb logcat -d | grep -i "zzzz-aaaa" — MUST return nothing. Also grep for the host/path "live2/".
 4. Same on the RELEASE build (R8 on).
 5. Inspect: adb shell run-as com.loopcast ls -R files/ datastore/ — key only inside the encrypted blob, and only when "Remember key" is on.
 6. Screenshot/screen-record while the key field is visible — consider FLAG_SECURE when the key is shown (implement: set FLAG_SECURE in the Activity while showKey==true).
If the library logs the URL, add a logcat-level guard: disable the library's logging (check its API) or wrap the call so the URL isn't logged.
```
**Test:** grep returns 0 hits on debug AND release. ✅ Commit `security: no-leak audit`

### Step 11.3 — Manifest/network hardening review
```text
Review & assert (a tiny instrumented test reading PackageInfo can enforce some): no exported components except the launcher; allowBackup=false; no cleartext config needed; RTMPS preset is the default; custom URLs starting with rtmp:// (not rtmps://) show a one-line note "unencrypted — your stream key can be sniffed on this network".
```
**Test:** manual review + test of the note logic (pure function). ✅ Commit `security: manifest + rtmp warning`

### Step 11.4 — Diagnostics export (no backend, no analytics)
```text
util/DiagnosticsLog.kt: in-memory ring buffer (last 500 lines, already redacted) fed by our Log wrapper + state transitions + reconnect reasons. "Share diagnostics" in Settings writes it to cacheDir and shares through a FileProvider (ACTION_SEND). Include: app version, library versions, device model, Android version, thermal status — never the key/URL.
Test: JVM test — ring buffer cap, redaction applied, no key in output.
```
**Test:** export after a failed session; open the text; it's useful and clean. ✅ Commit `feat: diagnostics export`

### Step 11.5 — Accessibility + UX polish
```text
Checklist: contentDescription on icon buttons; TalkBack announces state changes (use liveRegion semantics on StatusBanner); 48 dp touch targets; dark theme; font scale 1.5–2.0 no clipping; landscape layout works; error messages human ("YouTube rejected the stream key — copy it again from Studio"); empty/loading states everywhere. List defects first, fix in small commits.
```
**Test:** TalkBack walkthrough: pick → start → stop. Accessibility Scanner shows no major issues. ✅ Commit `fix(ui): a11y`

### Step 11.6 — R8 / release build verification
```text
Enable minify + shrink resources for release. Add keep rules ONLY if the release build misbehaves (start with none; check the library's README for consumer rules — most ship their own). Test a RELEASE build on the device: pick, import, start, reconnect, stop. Compare a debug vs release APK size and note in docs.
```
**Test:** release build streams to MediaMTX like debug. ✅ Gate: Module 11 done. Commit `build: r8 release`

---

# MODULE 12 — Release

### Step 12.1 — Identity and versioning
```text
App name/icon (adaptive icon, monochrome layer for themed icons), versionName from a single place (gradle.properties) and versionCode from a CI-safe counter, applicationId final. Signing: create a keystore OUTSIDE the repo; reference via ~/.gradle/gradle.properties or environment variables; never commit it; back it up (losing it = can't update the app on Play).
```
### Step 12.2 — Build + smoke
```bash
./gradlew clean testDebugUnitTest assembleRelease bundleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
Smoke script (manual, 10 min): fresh install → pick `test720.mp4` → RTMPS to a **real YouTube unlisted** stream → 10 min → stop → "Forget key" → uninstall/reinstall (verify nothing restored from backup).

### Step 12.3 — Distribution notes
```text
 - Sideload (APK) is enough for personal use.
 - Google Play: you must declare the foreground-service type (mediaPlayback) with a justification + demo video; Data safety form = "no data collected/shared"; target the API level Play currently requires; add a policy note that users must own streamed content.
 - Don't claim affiliation with YouTube in the name/icon (trademark) — "LoopCast for YouTube Live" is risky; use a neutral name.
```
### Step 12.4 — Maintenance policy
```text
Pin RootEncoder + Media3 versions; update monthly in a branch; after each bump run: unit tests → 1 h soak → matrix subset. Subscribe to GitHub Releases for RootEncoder. Keep docs/ADR current.
```
✅ **Gate:** signed release streams for 1 h to YouTube. Tag `v1.0.0`.

---

# APPENDIX A — Production readiness

| Area | Decision / risk | Where |
|---|---|---|
| **Security** | Stream key masked in `toString`, never logged, Keystore AES-GCM if remembered, no backups, no exported components, RTMPS default + warning for plain RTMP, FLAG_SECURE while key visible, no analytics | 2.1, 11.x |
| **Performance** | One decoder at a time (ExoPlayer released before streaming), hardware encoder via MediaCodec, chunked 64 KiB import with cancellation, state via StateFlow (no polling) | 4, 7.3, 3.2 |
| **Reliability** | Pure state machine with random-walk test, single retry brain, network-aware reconnect, backup ingest, thermal/battery guards, FGS `mediaPlayback` (no 6 h cap) | 2.3, 9.x, 8.x |
| **Observability** | Redacting logger, diagnostics ring buffer, LiveStats (uptime/bitrate/reconnects), YouTube Stream Health as the external truth | 1.5, 11.4, 10.1 |
| **Cost** | No servers. User-side: mobile data ≈ 2 GB/h @ 4.5 Mbps (warned), battery (plug in) | 9.1, 9.5 |
| **Compatibility** | Probe + decoder check up-front, typed unsupported-media errors, device matrix | 3.3, 10.2 |
| **Deployment** | Pinned deps, R8 verified, keystore outside repo, signed AAB/APK, Play FGS declaration | 1.2, 11.6, 12.x |

# APPENDIX B — Edge-case ledger (each has a test or manual row)

- Pasted full URL with key appended / trailing newline / backup `?backup=1` (2.1) · Odd or portrait dimensions (2.2) · Illegal state transitions (2.3)
- 2 GB import, low storage, cancel mid-copy, app killed mid-copy (3.1–3.2) · HEVC/4K not decodable (3.3) · No audio track (3.3, 9.6) · VFR (9.6)
- Loop seam: PTS continuity, A/V drift over many loops (5.3) · Preview attach/detach, rotation (7.3) · Rapid start/stop (6.3, chaos)
- Screen off / recents swipe / process death (8.x) · Notification permission denied (8.1) · OEM battery killers (8.4)
- Wi-Fi↔cellular switch, airplane mode, server down, key rotated mid-stream (9.1–9.2) · Overheating, low battery (9.5) · Android 15 FGS timeouts (10.3)
- Key in logs/backups/screenshots (11.2) · Keystore reset after restore (11.1)

# APPENDIX C — Anti-patterns avoided

Streaming logic inside the Activity · two decoders running at once · library types leaking into UI/domain · string-matched errors scattered around · retry loops in multiple places · `Thread.sleep` in reconnect · FGS type `dataSync` for a 24/7 task · stream key in `SharedPreferences` plaintext or logcat · Hilt/Room for a one-screen app · "works on my phone" without a second device · trusting a 5-minute test for a 24/7 feature.

# APPENDIX D — Test matrix template (`docs/TEST_MATRIX.md`)

| Fixture | Preset | Network | Phone | Result (✅/❌) | YouTube health | Notes |
|---|---|---|---|---|---|---|
| A test720 | 720p | Wi-Fi | Phone 1 | | | |
| B test1080 | 1080p | Wi-Fi | Phone 1 | | | |
| C portrait | Auto | Wi-Fi | Phone 1 | | | |
| D hevc | Auto | Wi-Fi | Phone 1 | | | |
| E noaudio | 720p | Wi-Fi | Phone 1 | | | |
| F vfr | 720p | Wi-Fi | Phone 1 | | | |
| A test720 | 720p | Cellular | Phone 1 | | | |
| (repeat all rows for Phone 2) | | | | | | |

# APPENDIX E — Sources used for the verified facts

- RootEncoder repo/wiki/releases (file sources, StreamBase, versions): github.com/pedroSG94/RootEncoder
- Android Media3 release page (stable 1.10.1): developer.android.com/jetpack/androidx/releases/media3
- YouTube Help — "Choose live encoder settings, bitrates, and resolutions": support.google.com/youtube/answer/2853702
- Android 15 behavior changes (FGS `dataSync` / `mediaProcessing` timeouts): developer.android.com/about/versions/15/behavior-changes-15

---

*Order recap: 0 → 1 → 2 → 3 → 4 → **5 (spike decides everything)** → 6 → 7 (MVP demo) → 8 → 9 → 10 → 11 → 12. Every arrow requires a green gate.*