# Silicon Buddy

The phone and tablet companion to [Silicon Optimizer](https://github.com/OGZamasu/silicon-optimizer)
and [silicon-node](https://github.com/OGZamasu/silicon-node). Everything the Mac can do —
chat with any loaded or cloud model, install and load models, run image, video and 3D jobs on
the Mac or the node, drive agent sessions, ask the swarm — from an Android phone or an iPad,
over your own tailnet. Nothing public, no relay, no account.

- `ios/` — Swift and SwiftUI, iPhone and iPad. Pairing, dashboard, models and chat, plus
  a Home and Lock Screen widget, a share extension, camera mode, push-to-talk and three
  App Intents for Siri and Shortcuts.
- `android/` — Kotlin and Jetpack Compose, phone and tablet. The same, with a Glance
  widget, a Quick Settings tile, a share target and launcher shortcuts, plus the
  Create tab (video, image and 3D, and the Mac's render queue) and the Machines page.
- `contract/` — the control API as JSON fixtures exported by the Mac app's own
  `ContractExportTests`, plus `routes.md`. Both apps round-trip every type against these,
  so a change on the Mac fails a build here before it fails a user. Re-export with
  `./contract/refresh.sh`.
- `docs/PLAN.md` — the milestones and the decisions behind them.

Two native apps on purpose: they share the contract and the design, not code.

## Reaching in

Most of what you want from the Mac is one question, and opening an app to ask it is
three steps too many. So the question can start from a widget, from another app's share
sheet, from the camera, from the microphone, or from Siri and the Assistant — and every
one of them goes through the same `TailnetHost` gate and the same paired token as the
app itself.

| | iOS | Android |
|---|---|---|
| Widget | Home Screen (small and medium) and a Lock Screen accessory, with a configurable preset question the widget fires itself | Glance widget with the same content and button |
| One tap to the composer | The widget's "Ask", a `siliconbuddy://ask` deep link | The widget, the Quick Settings tile, the launcher's long-press menu |
| Share | Share extension: text, a link or up to eight pictures | Share target, the same |
| Camera | AVFoundation, question first, shutter sends | CameraX, the same |
| Voice | Hold to talk, `SFSpeechRecognizer` on-device where the phone can, `AVSpeechSynthesizer` for the reply | `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE`, `TextToSpeech` for the reply |
| Assistant | App Intents: "Ask my Mac", "Load a model", "What is loaded on my Mac" | Static and dynamic shortcuts bound to the same three |

A link never sends anything. `siliconbuddy://ask?text=…` fills the composer in and
stops there, because anything on the phone can open a URL in this app.

The token lives in the Keychain under a shared access group on iOS, and in
`EncryptedSharedPreferences` on Android where every surface is the same process. The
address and the last answer live in an app group container; the token never does.

None of it is backed up. On iOS the shared container is marked
`isExcludedFromBackup`, which covers the group's preferences file inside it; on Android
`res/xml/data_extraction_rules.xml` excludes every shared-preferences file from both
cloud backup and device transfer. The token is stored device-only on both and so is
never restored anywhere, and a backup that carried the address without it would restore
half a pairing that cannot work. The last answer is a piece of a conversation and stays
on the phone that heard it.

## Making things

The Create tab starts a render on the owner's machines: a clip through the Mac's
queue, an image, or a mesh from a picture the Mac already has. The queue screen is
`GET /video/queue` and the `job` events on `/events` read together — the queue knows
what the work is and why it failed, the events know how far along it is — and its
buttons are the Mac's own words: pause, resume, retry, remove, stop following, clear
finished. There is no cancel, because a clip already handed to a node keeps rendering
there, and the Mac will not claim otherwise.

A render takes minutes, so a request that has to be waited for runs in a foreground
service behind an ongoing notification, and a local notification says when the work is
done or has failed. Tapping it opens the queue.

Results come back as ids, and `GET /media/{id}` answers the bytes — so a finished clip
or picture is saved into the phone's own photo library, and a picture on the phone goes
the other way through `POST /uploads` to become the mesh the Mac makes or the still it
animates. Neither direction names a path: a request from a paired device that names a
file on the Mac is refused, because a device that could name one could name any of them.
A chat-only device may fetch the preview images and not the renders, which is the Mac's
rule and is what the buttons follow.

The Machines page is this Mac and every machine it can hand work to, one card each:
chip and cores, what is loaded, memory and pressure, GPU and CPU, the lanes each one
advertises. A peer's card is what the Mac's last poll saw, and "Ask it now" replaces it
with what the node says this second — including the adapter riding on its loaded GGUF,
which a poll cannot carry.

## Building

Both apps talk to Silicon Optimizer over your tailnet, and to nothing else: every
request is checked against `TailnetHost` first — loopback, `10.0.2.2` for the Android
emulator, Tailscale's `100.64.0.0/10` and `fd7a:115c:a1e0::/48`. A scanned QR or a
`siliconbuddy://` link never pairs on its own; it names the machine and asks.

```
# iOS — Xcode 26, xcodegen 2.46
cd ios && xcodegen generate
xcodebuild -scheme SiliconBuddy -destination 'platform=iOS Simulator,name=iPad mini (A17 Pro)' build test

# Android — the JDK inside Android Studio, SDK 35
cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug
```

The generated `ios/SiliconBuddy.xcodeproj` is not committed: `project.yml` is the source.
