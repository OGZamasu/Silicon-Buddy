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
  button with nowhere to look at the result. `POST /jev`, `POST /recommend` and the
  three other routes the Mac grew alongside this are in `contract/` but are not
  mirrored by either app yet; they belong to M3's node and recommend pages.

### M3 — Media jobs and machines
- Image, video, 3D from the phone on the Mac or the node; queue view; progress (Live Activity on iOS, ongoing notification on Android); results saved to the phone.
- Node page: GPU, loaded GGUF, adapters, restart lanes. Swarm page.

### M4 — Agent sessions (Mac API + apps)
- Mac: sessions API over harness/Codex/Pi engines: list, create, send, event stream, tool-call approvals, cancel.
- Apps: session list and transcript, approve/deny from the phone (local notifications; APNs deferred until a paid developer account exists), handoff from Mac to iPad.

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
