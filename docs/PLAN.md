# Silicon Buddy — plan

Decided 2026-09-18 with the owner.

## Decisions

| Question | Decision |
|---|---|
| Reach | Tailscale only. The Mac app listens on its tailnet address when the owner turns it on; phones join the tailnet; pairing by QR. No relay, no public ports. Transport sits behind an interface so a relay could be added later. |
| Scope | Full remote parity with the Mac app, not a Bonsai chat client: any model (local, swarm, cloud providers), install/load, image/video/3D jobs on Mac or node, agent sessions (harness, Codex, Pi) with approvals, decisions, swarm and node view. Plus: quick-chat widgets, share sheet, camera, voice, Shortcuts/Assistant, on-device fallback model. |
| Devices | Samsung Galaxy S24 Ultra (Android) and iPad mini (iPadOS); iPhone via Simulator. Apple ID is in Xcode (personal team); USB/wireless debugging on the S24. Tailscale must be installed on both before the first real-device test. |
| Code | One repo, two fully native apps, sharing only the API contract. |
| Name | Silicon Buddy. Bundle id `dev.siliconoptimizer.buddy`, Android `dev.siliconoptimizer.buddy`. |
| Build | Builder + critic pair of Opus 5 agents per milestone, orchestrated from the Mac. Every milestone ends verified in the iOS Simulator and an Android emulator, then on the real devices. |

## Reference points

- Claude Code Remote Control: the desktop keeps executing; the phone renders the transcript
  and sends prompts; state stays in sync across devices. Our equivalent is the Mac's agent
  session API over the tailnet (M4).
- Grok Bot: approve steps and review results from the phone while the work runs elsewhere.
  Our equivalent is the approvals view (M4) and job progress (M3).
- Codex: cloud tasks reviewed from the phone. We keep everything on the owner's machines.

## Milestones

### M0 — Mac: remote access (silicon-optimizer)
- Opt-in tailnet listener for the control API (same port, same routes) beside loopback.
- Pairing: Settings → Silicon Buddy → "Pair a device" shows a QR (`siliconbuddy://pair?host&port&code`), one-time 6-digit code, 5-minute expiry, rate-limited `POST /buddy/pair` mints a per-device token (hashed at rest in `buddy.json`), device list with revoke, last-seen.
- Streaming: `POST /chat/stream` (SSE of tokens/reasoning/finished) and `GET /events` (SSE: status, download and job progress, later agent events).
- Conversations on the Mac: list/create/get/append, so phone and Mac share history.
- Contract export: fixtures for every route into `contract/` here.
- Tests for all of it.

### M1 — Foundation: both apps
- Repo scaffolds (XcodeGen project for iOS; Gradle KTS for Android), and the checks run on
  the Mac itself by `scripts/ci-android.sh` — no hosted CI.
- Pairing by QR, tailnet reachability check, token in Keychain / EncryptedSharedPreferences, biometric lock.
- Dashboard: what is loaded, metrics, swarm and node health. Model picker: installed, catalog, cloud; load/unload/install with progress.
- Chat with streaming, conversations synced with the Mac, images from camera or photos, markdown rendering.
- Verified: iOS Simulator (iPhone and iPad) and Android emulator.

### M2 — Reach in: widgets, share, camera, voice, intents — **done**
- iOS: interactive Home Screen widget (small and medium) with a configurable preset
  question it fires itself, a Lock Screen accessory for the loaded model, a share
  extension, and App Intents ("Ask my Mac", "Load a model", "What is loaded on my Mac").
- Android: Glance app widget with the same content and button, a Quick Settings tile
  that opens the composer, a share target, and static and dynamic launcher shortcuts
  the Assistant binds to.
- Camera mode on both: the question is typed first and the shutter sends it.
- Push-to-talk on both, recognised on the device where the phone can do it, with the
  reply read back and a toggle for that.
- Not done: the Control Center control, and "Start a video…" — the video routes are
  M3's, and an intent that starts a render before the queue view exists would be a
  button with nowhere to look at the result. Six routes the Mac grew alongside this —
  `GET`/`POST /jev`, `GET /jev/calibration`, `POST /jev/calibrate`,
  `GET /jev/guardrails/recent` and the task-shaped `POST /recommend` — are in
  `contract/` but are not mirrored by either app yet; they belong to M3's node and
  recommend pages.

### M2.1 — Android device pass — **done**

An afternoon with the S24 Ultra (SM-S928U) itself rather than an emulator. The phone
turned out to be on **Android 16 (SDK 36)**, not 14/15, with **three-button navigation** —
both of which matter, because the inset bug below only shows on an opaque navigation bar.

Verified on the phone: cold start (~0.95–1.1s, debug build); pairing restored from
EncryptedSharedPreferences after `am force-stop`; `GET /events` opened over the tailnet
and stayed open; `TailnetHost` gating every request; the Glance widget already on the
One UI home screen taking an update from the app; the Quick Settings tile, the share
target and both launcher shortcuts — one static, one dynamic — resolving.

Verified on an Android 16 emulator against `demo_mac.py`, because they need a screen and
the phone's is locked: pairing by link, live dashboard, conversation sync, streaming and
markdown, the composer against the keyboard, dark theme, font scale 1.3, and the
`/events` backoff across a real network drop (0 → 1s → 2s → 4s → 8s, reset after a
stream that had stayed up).

Fixed here:

- **The push-to-talk caption lied.** `VoiceController` decided correctly whether it had
  built an on-device recogniser and then overwrote the answer with `SDK_INT >= 33`, so
  every phone on Android 13 or later was told its voice was recognised locally — the S24
  Ultra included — whether or not the audio was going to Google. The decision moved to
  `RecognitionRoute`, which has tests.
- **The camera kept running after its sheet closed**, bound to the activity rather than
  to the preview, so the privacy indicator stayed lit. Fixing it exposed a race — close
  the sheet while the camera service is still starting and the listener bound afterwards,
  with no dispose left to undo it — so the wait is now a cancellable `awaitInstance`
  rather than a blocking `get()` on the main thread. The bind itself still runs on the
  main thread, which is where CameraX wants it; it is the *waiting* that moved. Unbinds
  the two use cases rather than everything, because the QR scanner shares the provider,
  and closes the `ImageProxy` in a `finally`.
- **The event stream healed silently.** Nothing downstream could tell a live stream from
  one that had been flapping for ten minutes, and Settings said "Streaming from /events"
  during an outage. It now emits `ServerEvent.Disconnected` and logs both ends of every
  reconnect under one `SiliconBuddy` tag — but only `TransportError.logSummary`, a fixed
  tag per case, never the error's message. The first version of that logging wrote
  `error.message`, and `Unreachable` names the Mac's tailnet address in its message by
  construction, so every flap printed the owner's 100.x address into a log that outlives
  the moment and travels in bug reports.
- **The composer floated above the keyboard.** Scaffold's padding and `imePadding()`
  each counted the navigation bar, leaving ~128dp of dead space under the composer on a
  three-button phone. Fixed with `consumeWindowInsets`.
- **The open tab and conversation were lost** to any restart One UI felt like doing.
  Saving the id then exposed a second bug: it comes back before the Mac has been asked
  whether it stores conversations, and `open` used to answer that with a fabricated empty
  transcript wearing the right title. It now waits for the answer — but only when there
  is a Mac to wait for, since unpaired that flag is never set and a blunter guard made
  every local conversation permanently unopenable.
- **Release was built unminified at 75 MB.** R8 and resource shrinking take it to
  28.9 MB; restricting to `arm64-v8a` takes it to **12.7 MB** (debug 94 → 74.6 MB). The
  keep rules are the substance: a Glance `ActionCallback` is reached by class *name*, so
  without one the widget's button becomes a no-op on release and nothing reports it.
  The gate asserts both callbacks and all 48 serializers appear in R8's own `seeds.txt`, and
  an instrumented test (`testBuildType = "release"`) resolves them through the real
  classloader on the minified APK — three tests, green on an Android 16 emulator.
  Minifying the *test* APK against an already-shrunk app is awkward in both directions:
  classes the app dropped are not copied back into it, so the runner needs a couple of
  app-side keeps to exist at all, and two tests that referenced app classes directly had
  to go, because R8 renames them and that is R8 working correctly.

  Two things to know about that `abiFilters` line. It sits in `defaultConfig`, so it
  applies to **every variant including debug**: an x86_64 emulator cannot install this
  build at all, and `-Pbuddy.abis=x86_64` is the way back. And `assembleRelease` signs
  with the local **debug keystore** when there is one, purely so the build can be
  installed and instrumented — that is not a distribution key and must not become one,
  since every debug keystore shares a password. A real key, kept in the owner's
  keychain, is still outstanding; on a machine without `~/.android/debug.keystore` the
  release APK comes out unsigned.

Not done, and why:

- **The phone's screen is locked** behind a secure keyguard, so nothing that needs eyes
  or taps on the real hardware was checked there: camera capture, the voice state
  machine on a real recogniser, TTS, the widget's own button, One UI's widget picker
  preview, and frame timing while streaming. The emulator stands in for the layout and
  the state machines; it cannot stand in for Samsung's recogniser or its battery policy.
- **The phone's network was not flapped.** Wireless debugging on Android is tied to
  Wi-Fi, so aeroplane mode would have severed the only channel to a locked phone with no
  way back short of physical access. The backoff was exercised on the emulator instead.
- **The widget has no `previewLayout`**, so One UI's picker shows Glance's placeholder.
- **The share target now has its own task** (`taskAffinity=""`). Joining MainActivity's
  meant `excludeFromRecents` applied to the whole task, so using the share sheet took
  Silicon Buddy out of recents, and backing out of a share dropped into the app instead
  of returning to whatever was being shared from.
- **`material-icons-extended` is still the largest single cost**, ~45,000 icon classes
  for the 24 this app draws. R8 removes them from release; debug still carries them.

### M3 — Media jobs and machines — **Android done, iOS to follow**
- Image, video, 3D from the phone on the Mac or the node; queue view; progress (Live Activity on iOS, ongoing notification on Android); results saved to the phone.
- Node page: GPU, loaded GGUF, adapters, restart lanes. Swarm page.

Android has a Create tab — Video, Image, 3D and the render Queue — and a Machines
page. Video picks a lane from `GET /video/models` (and "Auto" when `GET /jev` says
this Mac's media router is on), offers only the lengths that lane advertises, and
queues takes through `POST /video/queue`; the Queue screen is driven by the `job`
events, with the Mac's own controls — pause, resume, retry, remove, stop following,
clear finished, and cancel render where the Mac marks a clip `canCancel` — each behind a
confirmation where it throws work away. iOS has the same queue and controls as a screen
of its own (Queue on iPhone, Render queue on iPad); the rest of Create is still to follow. Image shows
`POST /image/plan`'s phase-by-phase memory before it renders. 3D uploads a photo
from the phone and makes a mesh out of it. Results are saved to the phone's own
photo library. Long renders run in a foreground service with an ongoing
notification that can be stopped waiting on, and a local notification says when one
is done or has failed.
Machines lists this Mac (`/status`, `/profile`, `/metrics`, `/v1/node`) and every
swarm peer (`/swarm`).

**What the Mac grew for it.** The first pass of this milestone was written against a
control API with no way to fetch a result and no way to send a picture, and the app
had to say so in six places. The Mac's M3 routes landed while this was in review, so
those sentences are gone and the things they stood in for work:

- `GET /media/{id}` answers the bytes, with an `ETag` and `Range` support. "Save to
  Photos" writes a finished clip or picture straight into the phone's own library
  through MediaStore. Scope is per id: a chat-only device may fetch the preview images
  and is answered 403 for the renders themselves, so the button is not offered there
  and the card says why.
- `POST /uploads` takes a picture from the phone — 24 MiB, type read off the bytes,
  kept a week — and answers with the two ids a render can start from. The 3D tab sends
  the photo and the video tab can send a still to animate. Neither names a path: a
  request from a paired device that names a file on the Mac is refused, and rightly.
- The `job` event carries `stage`, `reason` and `mediaID`. The queue screen shows what
  the renderer is doing ("video-denoise 18/30"), explains a failure in the Mac's own
  words, and offers the finished file — all from the stream. The six-second poll beside
  it is gone, and with it the whole class of poll-versus-event disagreements; the queue
  is read once, and again only when the stream is down or when an event names a clip
  this phone has not seen before.
- `GET /swarm` carries a peer's hardware, memory, GPU and loaded GGUF, and
  `GET /swarm/peers/{name}/status` asks one node now rather than remembering a poll —
  the only place its adapter appears. The Machines page shows the poll and has an "Ask
  it now" on each peer.
- `GET /video/models` says which sizes a lane renders and whether it reads a negative
  prompt, so the pickers offer what the lane offers and nothing else.

What is still outstanding, and why:

1. **No push.** A render that finishes while the app is not running is learned about at
   the next open and treated as history rather than fired as a notification — otherwise
   opening the app would ring once per clip in the queue's memory. Push is still
   outstanding after M4 (see there): FCM sent by the Mac, and APNs once there is a paid
   Apple account.
2. **No mesh viewer a device may open.** `POST /ui/open3d` is on the Mac's loopback
   gateway, so "show me this on the Mac" is still not offerable; the mesh is saved to
   the phone instead.
3. **`POST /video/queue` takes no upload id**, so image-to-video is only on the
   synchronous route. The queue is where long work belongs, so a still to animate
   cannot be queued.

### M4 — Agent sessions — **Android done, iOS to follow**
- Mac: sessions API over harness/Codex/Pi engines: list, create, send, event stream, tool-call approvals, cancel.
- Apps: session list and transcript, approve/deny from the phone (local notifications; APNs deferred until a paid developer account exists), handoff from Mac to iPad.

The Mac side is silicon-optimizer PR #46, merged as 3944ba2 (`/agent/sessions`, and
`agent` and `resync` frames on `/events`); `contract/` is the export of that main. Android has an **Agents**
tab — Codex and Pi, one card each — and a session screen, built on a pure reducer
(`AgentSession`) over the reads and the frames. iOS mirrors every new type and route in
its contract tests and has no screen yet.

What the phone does:

- **The Agents tab**: state, the session's model and where it runs, the folder, turn
  activity, the newest line, pending approvals, and a "Runs without asking" line when the
  Mac says nothing asks. Start is one tap; Stop and New thread ask first. Hidden for a
  device paired for chat, with one line in Settings saying why. On a phone, Settings
  moved from the bottom bar to the top bar to make room: seven destinations do not fit a
  bar at a readable size, and a tablet's rail still lists all seven. A phone on its side
  keeps the gear in the top bar and its rail scrolls; Back from Settings returns to the
  tab it was opened from. A phone the Mac no longer knows (401) says so, stops asking —
  the agents, and the dashboard's metrics and status with them — and offers Pair again; a
  403 is said in the Mac's words and not retried. Pairing again takes down a watcher's
  "no longer paired" notice.
- **The session**: the transcript in the Mac's eight kinds, reasoning folded, output
  scrolling inside its row with the Mac's "truncated" said, "N earlier rows are on the
  Mac" when a read was capped by `limit`; the composer with a model picker that offers
  the session's `modelChoices` and nothing else (and is labelled as the session's model,
  because a pick sticks on the Mac); Interrupt while a turn runs; Codex's send waits for
  its turn to end, as the Mac's own button does, while Pi takes a message mid-turn. The
  transcript follows new rows only while it is at the bottom. At 200% text or on a phone
  on its side, a card's Accept and Decline stay pinned below its scrolling body and the
  composer stays on screen; from 600 dp wide the card sits beside the transcript. On
  Android 13 and later the screen leaves no Recents thumbnail.
- **Approvals**: a card per call with the guardrail's verdict and sentence; Accept and
  Decline; a 409 leaves the Mac's sentence on the card and asks what is true now
  (answered at the Mac first, still screening, engine stopped), a 404 takes the card down
  quietly, and an `approval` frame answered on the Mac takes it down saying which way it
  went. An answer is recorded as this phone's before it is sent, so a card answered here
  never comes down reading "on the Mac" when the Mac's frame beats the reply, or the
  reply is lost — but a frame is credited to this phone only when its decision is the one
  this phone sent, and a 404 or 409 takes any credit back. While a card is on screen
  other apps' overlays are hidden (Android 12+), and its buttons refuse a press that went
  through another app's window at the point pressed — not one whose window merely
  overlaps elsewhere, and not the next answer from a keyboard or switch access. The tab
  carries a badge that TalkBack reads, and the Quick Settings tile's second line says "2
  approvals waiting" — while that is fresh — and opens the Agents tab.
- **In the background**: leaving the app while a turn runs in a session this phone opened
  starts `AgentWatchService`, a foreground service of type `remoteMessaging` (Android
  14+; the type for carrying on a conversation that lives on another device — not
  `dataSync`, which is for moving files and is budgeted on Android 15). It opens its own
  `/events`, reads the watched sessions whole, and posts one high-priority notification
  per approval. The app's own stream and the dashboard's polling close when the app
  leaves the screen and open again when it returns, so in the background the watcher's is
  the only stream. A notification never carries the command, the paths or the tool:
  marked private, it is still shown whole on a lock screen set to show all content, so it
  says only the guardrail's verdict and "Review it to see exactly what it wants; you
  accept it in the app"; each kind has its own generic public version. With the command
  not on it, nothing on it can allow the command: its buttons are Decline and Review, and
  Accept exists only in the app, on the card, under what it runs. Both carry
  `setAuthenticationRequired(true)` on Android 12+; Decline goes to a non-exported
  receiver, which also refuses while the device is locked — asking once more a second
  later, for an unlock not yet recorded — and Review opens the session with that
  approval's card in front. On Android 10 and 11 the one button is Review. What comes
  back from a Decline is one of a dozen fixed sentences, never an error's own words,
  which can name the Mac. It stops when the turn ends; when nothing has been heard from
  the Mac for a minute, reconnecting with the client's backoff meanwhile (a relaunch or a
  network change is seconds), saying so once; at once on a 401 or a 403, saying which;
  when its notification is dismissed; or when the app returns, taking its approval
  notifications with it, and any a watcher whose process died left behind.

**Keeping in step**, which is most of the reducer: rows keyed by id and known at a
sequence, so a frame older than a held row is dropped and the same frame twice is
harmless; an answer is a snapshot at its `seq`, `complete` replaces and a slice merges,
placing the rows it brings where they happened rather than after rows held from later; an
approval id is settled for good once it stops waiting, so no stale read resurrects a card
— nor one this phone answered, whatever a read taken before the answer says; the
transcript's epoch guards against merging rows from a thread or a launch that has gone
(`reset` drops rows and cards; any other frame or read from a different epoch means
reading it all again, and the stream's continuity starts again with it); and the catch-up
cursor is how far the rows are *known* to be complete — the Mac's opening frames on
connect carry no rows, so a read answered before the stream opened leaves a gap that only
`?since=` can fill, and frames move the cursor only while the stream has been unbroken
since it. Breaks — a dropped connection, a `resync`, a restart of the stream — reach the
reducer in order with the frames, so no frame after a break is taken to follow on from
the ones before it; a frame the client cannot decode counts as a break too. Letting go of
the stream — the watcher at a turn's end, the app leaving the screen — closes its socket
at once, not at the next heartbeat. Returning to the app opens the stream again and costs
one catch-up read per engine, however the stream's opening frames and the reads
interleave; a relaunched Mac costs one full read per engine and no more.

Verified on the SiliconBuddyM3 emulator (Android 16) against a stand-in Mac with fake
Codex and Pi sessions: pairing, both cards, a turn sent from the phone and streamed back,
an approval accepted in the app, one answered on the stand-in's side coming down with
"Accepted on the Mac", a new thread clearing both screens, Pi's unattended banner, the
watcher starting on Home with its `remoteMessaging` type, the approval notification
answered from the shade, and the stream reopening after a long absence. After review, the
same way: in the background only the watcher's stream is open and the metrics polling has
stopped; a 20-second outage of the stand-in is ridden out, the approval's notification
replaced by the new one, and a longer one ends the watch with one notification saying so;
a stand-in that forgets the phone gets "no longer paired", Pair again and no further
reads; 200% text and a phone on its side keep the pinned answer row and the composer;
Back from Settings returns to its tab; a return costs one catch-up per engine; a Decline
in the shade reaches the stand-in once and leaves "Declined on this phone.", and the
watcher lets go as the turn ends; Review in the shade opens the app on that card. After
the second review: the app's stream socket is closed within a second of leaving the
screen; a stand-in that revokes the phone ends the watcher with its "no longer paired"
notice, the app back in front asks nothing more — no metrics, no status, no reads — and
pairing again takes the notice down and starts everything afresh. Four instrumented tests
on the minified release build drive the tab from the outside: an approval accepted in the
app; the posted notification read (its text the verdict and not the command, its actions
Decline and Review, both `isAuthenticationRequired`), declined from the shade, and the
watcher seen to start and stop; Review on the second of two approvals opening the app on
that card, where it is accepted; and a press carrying `FLAG_WINDOW_IS_OBSCURED` refused
while the window asks for overlays to be hidden, the next answer by accessibility let
through, and a partly-covered press let through.

Tests: Android 535 unit (199 new — the reducer, every agent route over a socket with the
Mac's own error bodies, notifications, the picker, the tile, strict `errorVariants`;
after the first review the Agents tab's view model, the watcher's loop from its first
read to each way it ends, a press in the shade from the lock check to the sentence it
leaves, the event feed's agent path, and the review's five probes kept as regression
tests — the fifth, whose excerpt is gone, now aimed at what the app still shows; after
the second, whose answer a card was when the Mac says otherwise, Decline and Review and
never Accept in the shade, the lock asked again a second later, the touch guard, the
stream letting go of its socket, a 401 stopping the dashboard, the masking, and the
second review's probe) and 7 instrumented; iOS 270, with a round trip for every new
fixture part and the same strict read of `errorVariants`. Every fix from both reviews was
checked by undoing it and seeing a test fail — the touch guard's wiring, the overlay
hiding and the receiver's lock reading on the emulator. The gate's floor on kept serializers
rises from 48 to 75, and it checks the agent frame types by name. Release APK 13.9 MB.

Not done, and why:

1. **No push.** The watcher only exists if a turn the phone is following is running when
   the app leaves the screen. A turn started on the Mac while the app is closed, or an
   approval after the watcher let go, rings nothing until the app is opened. That needs
   FCM (and APNs for the iPad), sent by the Mac.
2. **The locked-phone path was not walked end to end.** The flag is on the posted
   notification's actions (asserted on the device) and a Decline from the shade reaches
   the Mac on an unlocked emulator; the keyguard prompt in between, and the receiver's own
   refusal while locked — with its second look a second later, for an unlock Android has
   not recorded yet — need a PIN set on the device, which is a security setting and was
   not changed. The rule is unit-tested; the owner will try it on the real phone.
3. **The harness is not here**, on the Mac's side: it holds no transcript to mirror.
4. **iPad**: types and contract tests only. The Agents tab comes when iOS catches up.
5. **Handoff from the Mac to the iPad** is iOS work and waits with it.

### M5 — On-device fallback — **Android done, iOS to follow**
- A 1–4B model running natively on the S24 Ultra and iPad mini when the Mac is unreachable, same chat UI, clearly labelled.

The phone answers by itself with a small GGUF model on its own CPU, through llama.cpp —
only when the owner taps for it, and every answer says so. The Mac side is
silicon-optimizer PR #48 (merged as 653e669): `GET /ondevice/models`, `POST …/prepare`,
`GET …/file` and `DELETE`, full scope only; `contract/` is its export.

**Runtime.** llama.cpp b11053 (commit `1af554f8fc78ba029665a47b839484d9763e2a75`) as a
submodule at `third_party/llama.cpp`, built by Gradle in a new `android/llama` module with
NDK 29.0.14206865 and the SDK's own CMake 3.22.1 (which b11053 accepts): shared libraries,
`GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS` + `GGML_CPU_KLEIDIAI` on; `GGML_NATIVE`,
`GGML_LLAMAFILE`, `GGML_OPENMP`, `LLAMA_OPENSSL`/`LLAMA_CURL`, subprocesses, tests, examples,
tools and the server off; `c++_shared`, so eleven libraries share one C++ runtime. arm64
only; any other ABI has no library and the app says the model is not available on this
phone. The CPU backend is seven libraries, one per ARM generation, and at start llama.cpp
asks each what it can run here and keeps the best — which needs them extracted at install,
so the app uses legacy (compressed) JNI packaging; it is also what keeps the APK small.
`backends()` reports the pick: `libggml-cpu-android_armv8.2_2.so` (dot product, KleidiAI) on
the M3 Max emulator, which has no i8mm; the S24 Ultra picked `armv8.6_1` in the benchmark.
The JNI bridge (`buddy_llama.cpp`, ~500 lines) loads a model, renders a conversation through
the model's own chat template with llama.cpp's Jinja engine and thinking off, streams UTF-8
bytes back (never JNI's "modified" UTF-8, in which an emoji breaks), reuses the context's
memory for a pure append, and stops between tokens or inside one through the abort
callback. All of it runs on one dedicated thread (`LlamaThread`); only cancel and the thread
count are touched from elsewhere, as atomics.

**Module layout.**
```
third_party/llama.cpp                   the submodule, b11053
android/llama/                          library module: CMake, JNI, consumer keep rules
  src/main/cpp/buddy_llama.cpp          load · generate(messages | raw, sampling, sink) · cancel · threads · unload · backends
  src/main/java/…/llama/LlamaNative.kt  the external functions, kept by name
                        LlamaSession.kt LlamaThread, LlamaRuntime (availability), a Flow per answer
android/app/…/ondevice/
  ModelStore        noBackupFilesDir/models: <sha>.part (+ .part.json progress), <sha>.gguf, installed.json
  ModelDownloader   the Mac's prepare, then one ranged transfer, then the phone's own SHA-256
  ModelDownloads    the user-initiated job (14+), the dataSync service (10–13), Wi-Fi rule, notifications
  OnDeviceEngine    load/answer/unload, guards, timers, trim-memory and thermal listeners
  FallbackPolicy    when the phone is offered — the truth table
  ResourceGuard     memory and heat, as rules
  OnDeviceChat      what the chat needs (a test can stand in), and the phone's conversation store
  ModelRuntime      the engine's two seams — llama.cpp and the phone — plus PhoneHistory's cap
  PhoneModelsSection Settings → On this phone, with the consent dialog
android/app/src/releaseProbe/…/OnDeviceProbe.kt
                    the engine by name, for the instrumented tests — in the build they run
                    on, never in the one the owner installs
android/app/…/ui/  NotificationsNotice (notifications off, and the button that fixes it)
                   ScreenPrivacy (keep the screen on while the phone writes; no Recents thumbnail)
```

**How the model gets to the phone — only through the Mac.** Settings → On this phone lists
`GET /ondevice/models`: Qwen3.5 2B (1.3 GB, the default) and Gemma 4 E2B (3.35 GB, marked
slower), with size, licence, what the owner's own S24 Ultra measured, and the phone's free
space. Nothing moves until the consent dialog — name, size, licence, free space, and Wi-Fi
only unless "use mobile data" is ticked for that download. Then: `prepare`, and the Mac's
fetch followed (`/events` frames with `ondevice:<id>` and a stage, and the list polled
beside them); the room for the whole file reserved with `StorageManager.allocateBytes`; one
`GET …/file` into `<sha>.part`, resumed with `Range` and `If-Range` (a 206 is the rest; a 200
means another file, so the partial goes; a 416 means the partial does not fit, so it goes; a
409 means the Mac's copy is not ready, so it waits for it again; a 503 names the Mac's
missing drive and stops); then the phone hashes it. A match is renamed into place. A
mismatch is deleted, `prepare?verify=1` asked once, and the file fetched once more from zero;
a second mismatch stops and keeps nothing. On Android 14+ this is a user-initiated data
transfer job whose `NetworkRequest` asks for `NOT_METERED` with `NOT_VPN` removed (Tailscale
is a VPN, and a default request would never be satisfied on the tailnet), and the job's own
progress notification with Cancel; on 10–13 a `dataSync` foreground service that watches the
default network, pauses when it is not free, and says so when it finally gives up.

"Wi-Fi only" is tested as Android's own `NOT_METERED` rather than the Wi-Fi transport: a
phone tethered to another phone is Wi-Fi and is somebody's data allowance. The tunnel is
judged first, because on the owner's phone the default network *is* Tailscale: an unmetered
tunnel is free, and a metered one is judged by the network underneath it — which keeps a
tunnel over mobile data refused and a tunnel over the owner's own Wi-Fi allowed. The job's
own constraint asks the system for the same thing. A download that
stopped half-way is a gigabyte of the owner's storage with nothing on screen to show for it,
so Settings says "42% of 1.19 MB is already here — paused" beside Resume and Delete, read
off the disk and so unchanged by a restart; every model's row also says what it needs to run
beside what the phone has free right now. The digest a model is named by is checked to be 64
hex characters before it becomes a file name, the digest the Mac serves in
`X-Content-SHA256` is compared against the one it listed, and a new pin deletes the file the
old one left. A verified model is re-hashed before use if its size or modification time ever
changes — before the preflight, and again in the load itself. The phone never leaves the
tailnet: every request still goes through `TailnetHost`, which now also refuses to follow a
redirect anywhere.

A new conversation is the Mac's to make, on a Mac that keeps them — but only while it is
answering. Out of reach, one is made here instead, with the offer on the screen beside it:
a "+" that does nothing, and a tile whose "Ask on this phone" lands on a list rather than a
composer, would make the phone's own model unreachable exactly when it is the only thing
left that works. Such a conversation stays this device's — the Mac never heard of it, so it
goes as plain history when the Mac comes back, is saved here, and sits in the list beside
the Mac's own. A 404 from the Mac's conversation route means *that conversation*, not that
route, so it falls back to history rather than ending the exchange: a Mac that has lost a
conversation still answers the question. And a Mac that answers some routes while failing
`/conversations` is asked again at most every thirty seconds, not on every dashboard poll.

**The fallback.** `FallbackPolicy` offers the phone only when the Mac is out of reach —
unreachable, the app not running, too slow, or no Mac paired (401 included) — a model is
verified here, and this phone can run it; a Mac that answered and refused (no model loaded,
busy, forbidden) is still the Mac answering. Never silent: "Your Mac isn't answering.
[Answer on this phone] [Try again]", before a send when the probe already knows, and after a
failed one — whose failed reply carries the same button. The tap starts a new conversation
that lives only on the phone (`noBackupFilesDir/ondevice`, ids `phone-…`, listed apart as "On
this phone — not synced"), and every answer in it carries the chip "On this phone · Qwen3.5
2B" in its own bubble style, with the phone's own numbers. `ControlClient` refuses a `phone-`
id outright, before a byte leaves. When the Mac is heard from again — a send, the probe, or
the event stream reopening — the conversation offers "New Mac conversation" and "Send to
Mac…", which asks first, says how many messages go, and starts a new Mac conversation
carrying the exchange (in one quoted message, since the Mac's route takes one; as history to
a Mac that keeps none); the phone keeps its copy. The Quick Settings tile looks at the Mac
itself when the shade opens and says "Mac unreachable · phone model ready"; the widget says
it too and offers "Ask on this phone"; both open `siliconbuddy://ask?offer=phone`, which
shows the offer and answers nothing.

**The guards**, from the owner's decisions of 2026-09-19. Memory, in three bands since the
first real reading off the owner's S24 (`MemAvailable` 2.55 GB against Qwen's 3.1 GB gate,
which refused the only model there is):

- **Refuse** below what must stay resident. Not "the weights are mapped, so they don't
  count": KleidiAI claims Q4_0 and Q8_0 matmul weights and repacks them into *anonymous*
  memory, so a loaded model exists twice — a file copy the kernel may drop, and a repacked
  copy it may not. Measured on the emulator with SmolLM2 Q8_0 (a 145 MB file): `RssAnon`
  +203 MB, `RssFile` +145 MB. So the Mac's `peakMemoryBytes` is the sum of both copies, and
  what cannot be dropped is `max(sizeBytes, peak − sizeBytes)` — the most those two fields
  can say without guessing, and exactly the 203 MB above. For Qwen:
  max(1,296,764,000, 2,586,836,992 − 1,296,764,000) = 1,296,764,000, × 1.25 = **1.62 GB**.
  Better still, the phone measures it for itself: `RssAnon` across the first successful
  load is recorded per model and context, and from then on that is the floor.
- **Warn and run** between that and the Mac's `minFreeMemoryBytes`: "Your phone is low on
  memory: Qwen3.5 2B runs best with 3.10 GB free and this phone has 2.62 GB. Other apps may
  close, and answers may be slower." Said before the answer starts, never after.
- **Load** at or above the Mac's figure, silently.

A catalogue entry with no `measured` block has nothing to derive from, so its gate stays the
single `minFreeMemoryBytes` it always was. Android's own low-memory state refuses at any
reading. A shorter context moves the advisory "runs best" figure and never the floor: a
recurrent model's state does not grow with the context, and the Mac's peak was taken with
640 tokens read, so a floor scaled by it would be below what the phone needs.

And if it still gets the app killed, `ApplicationExitInfo` says so on the next run — over
the whole time a model was held, not only while it loaded, because the peak is in the middle
of an answer; and reason-agnostic, because One UI reports some of its reclaiming under other
reasons and what matters is that the app did not choose to end. The record is opened when a
load starts, kept open while the model is held, and closed when the load fails, is cancelled
or the model is let go — so nothing is left for an unrelated kill hours later to be blamed
on. Then: the model is remembered, the floor rises to a tenth above the reading that failed,
and the next load asks for half the context, until an answer finishes and the phone has
shown it can. "Use … instead" names a smaller installed model when there is one and says plainly
when there is not.

**Making room.** The owner's own suggestion, holding a phone with 2.58 GB free: a sheet from
the Settings row and from either sentence in the chat, which says what is free *now* (polled
while it is open), both of the model's figures and which one decides, and one thing an app
would rather not admit — Android does not let one app close another, so this one can only
let go of what it is holding itself. It does that first (the model, this app's caches, and
what came back), offers the phone's own memory screen — Samsung's Device care first, then the
app list, then settings, each checked through the package manager rather than
`Intent.resolveActivity`, which hands an explicit component back without looking and put a
dead button on every non-Samsung phone — says how to close apps by hand, and offers the
other way to make it fit: half the context, with a figure only once this phone has run it
both ways. "Try again" turns on the moment the floor is met.

Name a smaller installed model that fits — "Use SmolLM2 135M instead" on the emulator, whose
2.5 GB cannot hold Qwen. Heat: MODERATE drops a
quarter of the threads, SEVERE half and keeps answering, CRITICAL or worse stops the answer
("Stopped — phone too hot") and starts no new one; changes apply from the next token.
Screen: an answer is written only while the app is in front — leaving stops it ("Stopped
when you left the app.") — and the model is let go after 30 s in the background, at once on
a background trim-memory signal, or after five idle minutes. While the phone is loading or
writing, the screen is kept awake (`FLAG_KEEP_SCREEN_ON`), and cleared the moment it stops:
a display that sleeps takes the app out of the foreground, and the app's own rule would then
stop the answer it is in the middle of. A conversation the phone answered is also kept out
of the Recents thumbnail. Threads come from the Mac's `recommended` block (Qwen 6 for the
prompt, 4 for writing; Gemma 4/6), context 4096, thinking off. History: about 1,500 tokens
of the newest turns go to the model — the prompt is read at about 120 tokens a second, so a
long conversation would be a minute of silence before the first word — and a reply written
without the beginning of the conversation says so under its chip.

Stop, at any point: during the minute a model takes to arrive in memory it reaches
llama.cpp's own load-cancel, and a load whose caller has gone is freed rather than left in
memory nothing can reach. The question is written to the phone's disk before the load
starts, not after the answer, so a process Android kills mid-load loses nothing.

Two things the owner found on their own phone rather than here: notifications were never
granted, and after two dismissals Android stops showing the dialog and `launch` does nothing
at all — so the Agents tab and the Create queue now carry one line, "Notifications are off,
so … won't reach you", with an Allow button that asks while asking still works and opens the
app's notification settings once it does not. And the Quick Settings tile no longer records
a slow answer as "Mac unreachable": Tailscale takes a few seconds to wake, and a timeout
means nothing was learned.

**Verified** on the SiliconBuddyM3 emulator (Android 16, arm64, 2.5 GB, mobile data only)
against a stand-in Mac serving the real files through the real routes: Qwen3.5 2B (1.3 GB)
and SmolLM2 fetched through Settings with consent and verified on the phone, the job's
notification showing the copy; a Wi-Fi-only download waiting on mobile data and saying so;
the offer after a send to an unreachable Mac; Qwen refused for memory with SmolLM2 offered,
and SmolLM2's labelled answer; and — on an install with no instrumentation attached, since
Android pins an instrumented process to the foreground and refuses it — `am send-trim-memory
… BACKGROUND` unloading the model 15 ms later, inside the 30 s grace.

Tests: Android 660 unit (117 new: the fallback truth table and what counts as out of reach;
the downloader over a socket against a fake Mac — fetch, 40% cut and resume with Range and
If-Range, 200 restart, 416, 409, 503, a mismatch leading to `verify=1` once and a clean
refetch, a second mismatch keeping nothing, a full disk, the Mac's own failure, a cancel
keeping the partial; heat and memory as tables; trim levels; the Wi-Fi rule; the chat's
offer, answer, isolation — zero Mac calls for a phone conversation, and the client refusing
its id without a byte — refusal and alternative, Try again, Send to Mac; the store; the
thinking splitter; the tile, widget and link lines; and the contract — and, after the
review: a load whose caller is cancelled being freed rather than left in memory, the engine
refusing for memory and stopping for heat with llama.cpp and the phone both stood in for, a
file that changed under it, a Mac unreachable at launch being asked again when it comes
back, the question saved before the model is loaded, the screen kept awake, the newest
turns only, metered Wi-Fi, a digest that is not a digest, a new pin replacing the old file,
the Mac serving a different file than it listed, a 404 from a conversation route, and a
redirect the client refuses to follow; and after the second review: "+" and the tile's link
opening a composer with the offer while the Mac is out of reach, a Mac that goes away
mid-request, the floor between asks on a Mac that answers some routes and not others, and
each way a Tailscale tunnel can present itself; and after the third: a conversation made
while the Mac was away being answered the moment it returns, one the Mac has forgotten being
answered rather than refused, and opening either of them; and — with the phone's own first
reading in hand, and then the critic's measurement of what KleidiAI does to a loaded model — the three
memory bands and the arithmetic under them, this phone's own measurement beating the
estimate, a catalogue entry with no measurement, a shorter context that moves the advisory
figure and never the floor, a kill in the middle of an answer, an ending the app chose, a
failed load leaving nothing to be blamed for, what "make room" frees and says, which memory
screen it offers on which phone, and a conversation naming a model the Mac has replaced), 22 instrumented on the minified release
build (15 new, below), iOS 275 (5 new: the new types round-trip, and 503/507 have cases of
their own). Every protection was checked by undoing it and seeing a test fail (14 mutants
in the first round, 5 for the first review's fixes, 5 for the second's, 4 for the third's,
9 for the memory bands and 10 for the review of those, all caught).
Release APK 17,958,645 bytes
(13,920,891 at M4); the llama.cpp libraries are 17.0 MB unpacked and 6.7 MB compressed in
the APK. `scripts/ci-android.sh` is the local CI: unit tests — both modules, forced with
`--rerun`, because an up-to-date test task prints nothing and passes, which is a gate that
can pass without running anything — the release build, R8's `seeds.txt` for the widget
callbacks, 83 serializers (75 at M4), every `LlamaNative` JNI method and the sink, the test
probe kept in the build the tests run on and *absent* from the one the owner installs, the
bridge's own imported symbols (nothing that reaches a network or starts a process), the
SHA-256 of the KleidiAI archive llama.cpp fetched, each native library present, compressed
and stripped, and the APK under 30 MB; `--connected` adds the instrumented tests in two
passes, `--ios` the iOS suite.

The instrumented tests (`OnDeviceModelTest`, with `StandInMac` reaching the stand-in at
10.0.2.2, not `adb reverse`): stories260K
(`ggml-org/test-model-stories260K@479896ec…`, sha256 `270cba1b…`) writes, greedy, the same 32
tokens upstream's own `llama-simple` wrote from the same pinned build on the same emulator,
and again after a reload; the CPU variant is one `/proc/cpuinfo` allows; SmolLM2 135M Q8_0
(`bartowski/SmolLM2-135M-Instruct-GGUF@09816acd…`, `5a139571…`) streams tokens to a
finished event with its numbers, stops within 500 ms of a cancel, and gives its memory back
on unload; SmolLM2's own template renders the conversation, and Qwen3.5 2B's — read from the
real 1.3 GB file's vocabulary, since its weights do not fit this emulator — closes an empty
thinking block before the answer (thinking off, as the Mac recommends) and opens one only
when asked; pressing HOME stops the answer at once and unloads after the grace; a background
trim unloads at once; a model comes through Settings — waiting on mobile data, then
consented to — and is verified; a download stopped half-way says how much is here and
offers Resume and Delete, unchanged by a trip to Home; and an unreachable Mac offers the
phone, whose answer carries its chip, holds the screen awake while it writes and lets go
the moment it stops, and never reaches the Mac; and — from a phone that had already talked
to its Mac, which is what made this a regression rather than a gap — the tile's link and
the "+" button each open a composer with the offer on it while the Mac is gone. Then a second pass, on the fresh install
every run begins with: `NotificationsOffTest` alone, because another class grants
notifications for the whole of the first pass, checking the owner's own case — nothing
granted, and the Agents tab and the Create queue each say so, with Allow. The stand-in
(`tools/standin/demo_mac.py` with the `/ondevice` routes and switches for dropping,
corrupting, refusing and going away) runs on the host: `tools/standin/fetch-models.sh` once
for the pinned models, then `tools/standin/standin.sh start` (`tools/standin/README.md`).

Not done, and why:

0. **An empty conversation left on the Mac.** If `createConversation` times out after the
   Mac has already made one, the app makes its own here and the Mac is left with an empty
   "New conversation" nobody asked for. The reply that would have named it never arrived,
   so there is no id to clean up by; it appears in the list and can be deleted there.
1. **The real phone.** Nothing here touched the owner's S24 Ultra. The benchmark there
   (llama-bench, 2026-09-19) is where the Mac's numbers come from; the app's own time to
   first word, speed, heat, the i8mm pick through the app, One UI's memory killer against a
   loaded model, and the notification's look are the owner's to see.
2. **The GPU.** The Adreno OpenCL backend stays for later, behind a flag, once measured.
3. **Android 10–13** run the download in a `dataSync` foreground service; it compiles and its
   network rule is unit-tested, but there is no API 29–33 image on this Mac to run it on,
   and installing one is an SDK change to ask about first.
4. **Two design tests left to the rules**: `cmd thermalservice override-status` and turning
   the emulator's Wi-Fi off are device settings, so heat is tested as a table and the Wi-Fi
   rule through the emulator's own lack of Wi-Fi. The 16 KB-page emulator image is another
   SDK install to ask about; NDK 29 already aligns the libraries to 16 KB.
5. **Qwen3.5 re-reads the conversation each turn.** Its memory is partly recurrent and cannot
   be cut back to a shared prefix, and its template re-renders earlier answers differently,
   so only a model with a plain cache reuses the prefix. At ~120 tokens a second of prompt
   on the S24, a long conversation costs seconds; llama.cpp's recurrent-state snapshots are
   experimental in b11053.
6. **The design's crash rule** (after a low-memory kill mid-answer, switch to the smaller
   model) and the thermal-headroom poll were superseded by the owner's memory and heat
   rules and are not built.
7. **iPad**: types and contract tests only. The same GGUF runs through llama.cpp's
   XCFramework when iOS catches up.

## Further suggestions (not yet decided)
- Approvals with real push (APNs/FCM sent by the Mac) once a paid Apple Developer account exists.
- Multi-Mac: pair with more than one Mac; the node reachable directly for GGUF chat.
- Wake the PC from the phone (Wake-on-LAN sent by the Mac).
- LAN discovery (Bonjour) as a second path when both are at home but Tailscale is off.
- Apple Watch and Wear OS: last answer, quick prompt, job done.
- Clipboard hand-off: phone clipboard to the Mac model and back.
- Handoff (NSUserActivity) between Mac and iPad for the current conversation.
