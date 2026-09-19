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
  widget, a Quick Settings tile, a share target and launcher shortcuts.
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
