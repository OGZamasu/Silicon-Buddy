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
- Repo scaffolds (XcodeGen project for iOS; Gradle KTS for Android), CI on the Mac runner.
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
  CI asserts both callbacks and all 48 serializers appear in R8's own `seeds.txt`, and
  an instrumented test (`testBuildType = "release"`) resolves them through the real
  classloader on the minified APK — three tests, green on an Android 16 emulator.
  Minifying the *test* APK against an already-shrunk app is awkward in both directions:
  classes the app dropped are not copied back into it, so the runner needs a couple of
  app-side keeps to exist at all, and two tests that referenced app classes directly had
  to go, because R8 renames them and that is R8 working correctly.

  Two things to know about that `abiFilters` line. It sits in `defaultConfig`, so it
  applies to **every variant including debug**: an x86_64 emulator or an Intel CI runner
  cannot install this build at all. `-Pbuddy.abis=x86_64` is the way back, and CI uses
  it. And `assembleRelease` signs with the local **debug keystore** when there is one,
  purely so the build can be installed and instrumented — that is not a distribution key
  and must not become one, since every debug keystore shares a password. A real key, in
  the owner's keychain and in CI secrets, is still outstanding; on a machine without
  `~/.android/debug.keystore` the release APK comes out unsigned.

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
clear finished, each behind a confirmation where it throws work away. Image shows
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
hiding and the receiver's lock reading on the emulator. CI's floor on kept serializers
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

### M5 — On-device fallback
- A 1–4B model running natively on the S24 Ultra and iPad mini when the Mac is unreachable, same chat UI, clearly labelled.

## Further suggestions (not yet decided)
- Approvals with real push (APNs/FCM sent by the Mac) once a paid Apple Developer account exists.
- Multi-Mac: pair with more than one Mac; the node reachable directly for GGUF chat.
- Wake the PC from the phone (Wake-on-LAN sent by the Mac).
- LAN discovery (Bonjour) as a second path when both are at home but Tailscale is off.
- Apple Watch and Wear OS: last answer, quick prompt, job done.
- Clipboard hand-off: phone clipboard to the Mac model and back.
- Handoff (NSUserActivity) between Mac and iPad for the current conversation.
